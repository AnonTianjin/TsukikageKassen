package com.taketori.kassen.paper.skill;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.cooldown.CooldownManager;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.effect.CooldownBars;
import com.taketori.kassen.paper.effect.Fx;
import com.taketori.kassen.paper.item.ItemFactory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能调度：所有输入的唯一出口。
 *
 * <p>流程严格按设计稿的「实现流程」：
 * 物品身份（PDC）→ 角色/武器判定 → 模式解析 → 技能查表 → 冷却检查 → 执行 → 表现。</p>
 *
 * <p><b>每个早退分支都必须留下日志</b>：否则玩家按了键、输入层也收到了（有 [input] 行），
 * 但这里悄悄 return，控制台就会既没有 [skill] 也没有任何解释 —— 排查到此断线。
 * 现在无论走到哪一步，都会有 {@code [skill] … → 原因} 输出。</p>
 */
public final class SkillManager {

    private final TaketoriPlugin plugin;
    private final SkillRegistry registry;
    private final CooldownManager cooldowns;
    private final ItemFactory items;
    private final Fx fx;
    private final CooldownBars cooldownBars;
    private final Set<UUID> resolving = ConcurrentHashMap.newKeySet();

    public SkillManager(TaketoriPlugin plugin,
                        SkillRegistry registry,
                        CooldownManager cooldowns,
                        ItemFactory items,
                        Fx fx) {
        this.plugin = plugin;
        this.registry = registry;
        this.cooldowns = cooldowns;
        this.items = items;
        this.fx = fx;
        // 冷却就绪提示音：BossBar 满的瞬间播一声短促高音（A4），玩家不用盯条听声即知
        this.cooldownBars = new CooldownBars(player -> {
            if (plugin.config().cooldownBossBar()) {
                fx.sound("BLOCK_NOTE_BLOCK_PLING", player, 0.5F, 1.9F);
            }
        });
        // 冷却条刷新：每 5 tick 更新进度；任务由 SchedulerAdapter 统一管理，卸载时自动取消
        plugin.scheduler().runTimerTask(cooldownBars::tick, 20L, 5L);
    }

    /** 冷却条组件（玩家退出与插件卸载时需要清理）。 */
    public CooldownBars cooldownBars() {
        return cooldownBars;
    }

    public SkillResult dispatch(Player player, SkillSlot slot, boolean checkCooldown) {
        return dispatch(player, player.getInventory().getItemInMainHand(), slot, checkCooldown);
    }

    /** 指定物品的调度入口（Q 键 / 副手 / 特定槽位场景）。 */
    public SkillResult dispatch(Player player, ItemStack stack, SkillSlot slot, boolean checkCooldown) {
        ItemFactory.Identity identity = items.read(stack);
        if (identity == null) {
            trace(player, "?", slot, "主手物品没有 weapon_id（不是插件武器）→ 忽略");
            return SkillResult.NOT_A_WEAPON;
        }

        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            plugin.getLogger().warning("物品引用了未定义的武器: " + identity.weaponId());
            trace(player, identity.weaponId(), slot, "weapons.yml 里没有这把武器 → 忽略");
            return SkillResult.FAILED;
        }

        if (!items.usableBy(stack, player)) {
            fx.actionBar(player, plugin.config().messages().get("input.wrong-weapon"));
            trace(player, weapon.id(), slot, "这把武器绑定给了别的玩家 → 拒绝");
            return SkillResult.FAILED;
        }

        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        SkillDef def = weapon.skill(slot, mode);
        if (!def.isPresent()) {
            trace(player, weapon.id(), slot, "mode=" + mode + " 下这个槽位没有绑定技能 → 不派发");
            return SkillResult.NOT_BOUND;
        }

        String cooldownKey = CooldownManager.key(player.getUniqueId(), weapon.id(), slot);
        if (checkCooldown && !cooldowns.isReady(cooldownKey)) {
            double remaining = cooldowns.remainingSeconds(cooldownKey);
            if (plugin.config().actionbar()) {
                if (plugin.config().cooldownActionBar()) {
                    fx.actionBar(player, plugin.config().messages().get("input.on-cooldown-bar",
                            "skill", displayName(def),
                            "bar", progressBar(remaining, def.cooldownSeconds()),
                            "time", formatSeconds(remaining)));
                } else {
                    fx.actionBar(player, plugin.config().messages().get("input.on-cooldown",
                            "skill", displayName(def),
                            "time", formatSeconds(remaining)));
                }
            }
            trace(player, weapon.id(), slot, def.type() + " 冷却中（剩余 " + formatSeconds(remaining) + "s）");
            return SkillResult.ON_COOLDOWN;
        }

        Skill skill = registry.get(def.type());
        if (skill == null) {
            plugin.getLogger().warning("未注册的技能类型: " + def.type() + "（武器 " + weapon.id() + " 的 " + slot.key() + "）");
            trace(player, weapon.id(), slot, "技能类型未注册: " + def.type());
            return SkillResult.UNKNOWN_TYPE;
        }

        // 技能执行期间必须打上"内部伤害"标记：技能自己调用 damage() 会再次触发
        // EntityDamageByEntityEvent，若不加标记就会被近战改写逻辑接住，形成
        // dispatch → damage → 事件 → dispatch 的无限递归（会把服务器打崩）。
        SkillResult result;
        UUID uuid = player.getUniqueId();
        resolving.add(uuid);
        plugin.markInternalDamage(uuid);
        try {
            result = skill.execute(new SkillContext(plugin, player, weapon, slot, def, mode));
        } catch (Throwable throwable) {
            plugin.getLogger().severe("技能 " + def.type() + " 执行异常: " + throwable);
            result = SkillResult.FAILED;
        } finally {
            plugin.unmarkInternalDamage(uuid);
            resolving.remove(uuid);
        }

        // 技能释放自动挥手（A3）：右键/第三槽/Q 的成功施法补一次挥手动画，让"按下去有动作"；
        // 左键近战在攻击瞬间已由原版挥手，不重复
        if (result == SkillResult.SUCCESS && slot != SkillSlot.LEFT) {
            player.swingMainHand();
        }

        if (result.consumesCooldown() && checkCooldown) {
            cooldowns.set(cooldownKey, def.cooldownSeconds());
            // 冷却条：屏幕上方进度条（骑马时马血条那种位置），可配成文字形式
            if (plugin.config().cooldownBossBar() && def.cooldownSeconds() > 0.0D) {
                cooldownBars.show(player, slot, displayName(def), def.cooldownSeconds());
            }
        }
        trace(player, weapon.id(), slot, String.format("mode=%s type=%s -> %s", mode, def.type(), result));

        // 第三槽（F 键 / 潜行+右键 / /taketori f）成功后附带增益。
        // 放在冷却结算之后、且只认 SUCCESS：冷却被拦下或没有目标时不会白送增益。
        if (result == SkillResult.SUCCESS && slot == SkillSlot.SHIFT_RIGHT) {
            applyThirdSlotBuff(player);
        }
        return result;
    }

    /**
     * 第三槽技能的附加增益（默认 2 秒跳跃提升 V）。
     *
     * <p>只有技能真的放出来才给：否则按住 F 键就能无限刷跳跃提升。
     * 效果名走 VersionAdapter 解析，写错只会在启动校验里被点名。</p>
     */
    private void applyThirdSlotBuff(Player player) {
        if (!plugin.config().thirdSlotBuffEnabled()) {
            return;
        }
        int ticks = plugin.config().thirdSlotBuffTicks();
        if (ticks <= 0) {
            return;
        }
        PotionEffectType type = plugin.versions().potionEffect(plugin.config().thirdSlotBuffType());
        if (type == null) {
            return;
        }
        int amplifier = plugin.config().thirdSlotBuffAmplifier();
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, true, true));
        if (plugin.config().debug()) {
            trace(player, "third-slot-buff", SkillSlot.SHIFT_RIGHT,
                    "附加增益 " + type.getKey() + " 等级 " + (amplifier + 1) + " 持续 " + ticks + " tick");
        }
    }

    public boolean isResolving(Player player) {
        return player != null && resolving.contains(player.getUniqueId());
    }

    public String displayName(SkillDef def) {
        return def.str("display", def.type());
    }

    public static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    /** 某槽位当前剩余冷却秒数（供命令与表现层查询）。 */
    public double remainingSeconds(Player player, String weaponId, SkillSlot slot) {
        return cooldowns.remainingSeconds(CooldownManager.key(player.getUniqueId(), weaponId, slot));
    }

    /** 文字进度条：剩余比例越高填充越多（底部文字形式冷却提示用）。 */
    public static String progressBar(double remaining, double total) {
        int slots = 10;
        double ratio = total <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, remaining / total));
        int filled = (int) Math.round(ratio * slots);
        return "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, slots - filled));
    }

    /** 统一的调度日志：debug 关闭时完全静默。 */
    private void trace(Player player, String weaponId, SkillSlot slot, String note) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.getLogger().info(String.format("[skill] %s weapon=%s slot=%s → %s",
                player.getName(), weaponId, slot == null ? "?" : slot.key(), note));
    }
}
