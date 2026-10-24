package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.sengoku.EnergyRules;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 能量槽与必杀技：击杀中地小兵攒能量，满能量按 Q 释放。
 *
 * <p><b>与 Q 原行为的关系（需求确认的方案 a）</b>：能量<b>未满</b>时本类完全不介入，
 * Q 照旧走模式切换 / 装备切换；只有满了才被必杀技接管，放完立即清空、Q 随即恢复。
 * 这样"想切模式但能量正好满了"的唯一代价是放一次必杀，而不是永久失去切换能力。</p>
 *
 * <p>释放入口在 {@code InputListener} 的 Q 分支最前面：它是唯一能让"满能量时 Q 抢在
 * 模式切换之前"的位置，放在技能分发之后就没用了。</p>
 *
 * <p>效果本身刻意不复用技能系统：必杀是"角色之外的公共奖励"，用一套参数直接生成
 * 范围伤害 + 击退 + 可选减速，比伪造一个 {@code SkillContext}（需要 WeaponDef / SkillDef /
 * 冷却等一堆上下文）简单得多，也不会被武器冷却或模式判断意外拦住。</p>
 */
public final class EnergyManager {

    private final Map<UUID, Integer> energy = new HashMap<>();
    private final GameRoom room;
    private final TaketoriPlugin plugin;

    public EnergyManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private EnergyRules rules() {
        return plugin.config().energyRules();
    }

    // ---------------------------------------------------------------- 攒能量

    /**
     * 加能量（击杀中地小兵时调用）。
     *
     * <p>只有"从没满变成满"的那一次会播报，避免连续击杀时提示刷屏。</p>
     */
    public void add(Player player, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        EnergyRules config = rules();
        int before = energyOf(player);
        int after = config.clamp(before + amount);
        energy.put(player.getUniqueId(), after);
        if (before == after) {
            return;
        }
        if (!config.isFull(before) && config.isFull(after)) {
            room.scoreboard().actionBar(player,
                    plugin.config().messages().plain("sengoku.energy-full"));
            plugin.fx().sound("BLOCK_NOTE_BLOCK_BELL", player, 1.0F, 1.4F);
            return;
        }
        showBar(player, after);
    }

    public int energyOf(Player player) {
        return player == null ? 0 : energy.getOrDefault(player.getUniqueId(), 0);
    }

    public boolean isFull(Player player) {
        return rules().isFull(energyOf(player));
    }

    // ---------------------------------------------------------------- 释放

    /**
     * 满能量时释放必杀技。
     *
     * @return true = 已接管（调用方应中断 Q 的原逻辑）；false = 能量没满，交回原行为
     */
    public boolean tryUltimate(Player player) {
        if (player == null || !isFull(player)) {
            return false;
        }
        EnergyRules.UltimateSpec spec = rules().ultimateFor(characterOf(player));
        // 先清空：万一效果里有别的路径又打到这里，也不会重复释放
        energy.put(player.getUniqueId(), 0);
        cast(player, spec);
        return true;
    }

    private void cast(Player player, EnergyRules.UltimateSpec spec) {
        Location center = player.getLocation();
        double radius = Math.max(0.5D, spec.radius());
        List<LivingEntity> targets = SkillTargets.enemiesInRadius(plugin, player, center, radius);
        // 必杀伤害不是玩家近战：打内部标记避免被近战改写监听取消并误派发左键技能
        if (!targets.isEmpty() && spec.damage() > 0.0D) {
            plugin.markInternalDamage(player.getUniqueId());
        }
        try {
            for (LivingEntity target : targets) {
                if (spec.damage() > 0.0D) {
                    target.damage(spec.damage(), player);
                    plugin.damageNumbers().hit(player, target, spec.damage());
                }
                Vector push = target.getLocation().toVector().subtract(center.toVector());
                push.setY(0.0D);
                if (push.lengthSquared() > 0.0001D) {
                    push.normalize().multiply(spec.knockback()).setY(spec.launch());
                } else {
                    push = new Vector(0.0D, spec.launch(), 0.0D);
                }
                target.setVelocity(target.getVelocity().add(push));
                if (spec.slowDuration() > 0) {
                    PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
                    if (slow != null) {
                        target.addPotionEffect(new PotionEffect(slow, spec.slowDuration(),
                                Math.max(0, spec.slowAmplifier()), false, true, true));
                    }
                }
            }
        } finally {
            if (!targets.isEmpty() && spec.damage() > 0.0D) {
                plugin.unmarkInternalDamage(player.getUniqueId());
            }
        }

        plugin.fx().particle(spec.particle(), center.clone().add(0.0D, 0.5D, 0.0D), 40, radius * 0.4D);
        plugin.fx().sound(spec.sound(), center, 1.2F, 1.0F);
        room.broadcast(plugin.config().messages().plain("sengoku.ultimate-broadcast",
                "player", player.getName(), "count", targets.size()));
        room.scoreboard().actionBar(player,
                plugin.config().messages().plain("sengoku.ultimate-cast"));
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] " + player.getName() + " 释放必杀 "
                    + spec.type() + "，命中 " + targets.size() + " 个目标");
        }
    }

    // ---------------------------------------------------------------- 显示

    private void showBar(Player player, int current) {
        EnergyRules config = rules();
        if (config.display() == EnergyRules.Display.NONE) {
            return;
        }
        // BossBar 那条留给小局比分，所以这里只实现动作栏；配成 bossbar 时退化为动作栏
        String bar = buildBar(current, config.safeMax());
        room.scoreboard().actionBar(player, plugin.config().messages().plain("sengoku.energy-bar",
                "bar", bar, "current", current, "max", config.safeMax()));
    }

    /** 十格能量条。 */
    private String buildBar(int current, int max) {
        int filled = (int) Math.round(current / (double) Math.max(1, max) * 10.0D);
        filled = Math.max(0, Math.min(10, filled));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            builder.append(i < filled ? "█" : "░");
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 清理

    /** 小局重置：清空所有人的能量（能量属于战场状态）。 */
    public void clearAll() {
        energy.clear();
    }

    public void forget(UUID uuid) {
        if (uuid != null) {
            energy.remove(uuid);
        }
    }

    public int trackedPlayers() {
        return energy.size();
    }

    private String characterOf(Player player) {
        var profile = plugin.config().characters().profileOrNull(player.getUniqueId());
        return profile == null ? null : profile.characterId();
    }
}
