package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场地技能（2.0 新增）：在施法位置留下一个<b>静态</b>领域，周期性结算。
 *
 * <p>治疗场（月读镇魂铃）与丝网陷阱（吉野丝线）共用这一个类型，差别全在参数：</p>
 * <ul>
 *   <li>对<b>敌人</b>：每周期 {@code damage} 伤害 + 刷新 {@code slow-duration} 减速；</li>
 *   <li>对<b>友方</b>（{@code ally-radius > 0} 时，含自己）：每秒 {@code heal-per-second} 治疗，
 *       首次进入额外给 {@code absorption} 吸收（同一领域同一玩家只给一次）。</li>
 * </ul>
 *
 * <p><b>参数在施法瞬间快照</b>：一次施法对应一套数值，热重载从下一次施法开始生效——
 * 否则中途改 yml 会让同一个领域前后半段行为不一致。</p>
 *
 * <p>领域不落方块、不生成实体，纯逻辑 + 粒子环；玩家退场或房间销毁时由
 * {@link #clear(UUID)} / {@link #clearAll()} 终止。</p>
 */
public final class DeployZoneSkill implements Skill {

    /** 一名玩家名下最多一个领域（再放会顶掉旧的，避免刷屏与性能问题）。 */
    private final Map<UUID, ActiveZone> zones = new ConcurrentHashMap<>();

    private final TaketoriPlugin plugin;

    public DeployZoneSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "deploy_zone";
    }

    /** 一次施法的运行句柄：定时任务、已发放过吸收的玩家、已过去的时间。 */
    private static final class ActiveZone {
        private BukkitTask task;
        private final Set<UUID> absorbed = new HashSet<>();
        private int elapsedTicks;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        UUID owner = player.getUniqueId();
        cancel(owner);   // 顶掉上一个领域

        final Location center = player.getLocation().clone();
        final double radius = context.dbl("radius", 3.5D);
        final double allyRadius = context.dbl("ally-radius", 0.0D);
        final int durationTicks = Math.max(20, context.integer("duration-ticks", 120));
        final int periodTicks = Math.max(1, context.integer("period-ticks", 20));
        final double damage = context.dbl("damage", 0.0D);
        final int slowDuration = context.integer("slow-duration", 0);
        final int slowAmplifier = Math.max(0, context.integer("slow-amplifier", 0));
        final double healPerSecond = context.dbl("heal-per-second", 0.0D);
        final double absorption = context.dbl("absorption", 0.0D);
        final String slowType = context.str("slow-type", "SLOWNESS");
        final String particle = context.str("particle", "END_ROD");
        final double healPerTick = healPerSecond * (periodTicks / 20.0D);

        final ActiveZone zone = new ActiveZone();
        BukkitTask task = plugin.scheduler().runTimerTask(() -> {
            // 自清理：玩家下线 / 回了大厅 / 世界被回收（房间销毁）时立即终止，
            // 否则定时任务会一直对着已卸载的世界撒粒子。
            var room = plugin.rooms().roomOf(player);
            if (!player.isOnline() || center.getWorld() == null || room == null
                    || room.phase() != com.taketori.kassen.paper.match.room.GameRoom.Phase.PLAYING) {
                // 领域只属于正式对局：结算/重开/回大厅阶段一律熄火，防止上一局的伤害带进下一局笼内
                cancel(owner);
                return;
            }
            zone.elapsedTicks += periodTicks;
            if (zone.elapsedTicks > durationTicks) {
                cancel(owner);
                return;
            }
            // 敌人：伤害 + 刷新减速
            List<LivingEntity> enemies = SkillTargets.enemiesInRadius(plugin, player, center, radius);
            if (!enemies.isEmpty() && damage > 0.0D) {
                // 领域周期伤害不是玩家近战，打上内部标记避免被近战改写监听取消并误派发左键技能
                plugin.markInternalDamage(player.getUniqueId());
            }
            try {
                for (LivingEntity enemy : enemies) {
                    if (damage > 0.0D) {
                        enemy.damage(damage, player);
                        plugin.damageNumbers().hit(player, enemy, damage);
                    }
                    if (slowDuration > 0) {
                        PotionEffectType type = plugin.versions().potionEffect(slowType);
                        if (type != null) {
                            enemy.addPotionEffect(new PotionEffect(type, slowDuration, slowAmplifier,
                                    false, true, true));
                        }
                    }
                }
            } finally {
                if (!enemies.isEmpty() && damage > 0.0D) {
                    plugin.unmarkInternalDamage(player.getUniqueId());
                }
            }
            // 友方：治疗 + 首次进入给吸收
            if (allyRadius > 0.0D && (healPerTick > 0.0D || absorption > 0.0D)) {
                for (Player ally : center.getWorld().getPlayers()) {
                    if (!ally.getWorld().equals(center.getWorld())
                            || ally.getLocation().distanceSquared(center) > allyRadius * allyRadius) {
                        continue;
                    }
                    if (SkillTargets.isUntargetable(ally) || !isFriendly(player, ally)) {
                        continue;
                    }
                    if (healPerTick > 0.0D && ally.getHealth() < ally.getMaxHealth()) {
                        ally.setHealth(Math.min(ally.getMaxHealth(), ally.getHealth() + healPerTick));
                    }
                    if (absorption > 0.0D && zone.absorbed.add(ally.getUniqueId())) {
                        // 多个领域/多次技能的吸收有硬上限，防止无上限叠加
                        SkillTargets.addAbsorption(ally, absorption);
                    }
                }
            }
            drawRing(center, radius, particle);
        }, periodTicks, periodTicks);

        zone.task = task;
        zones.put(owner, zone);

        plugin.fx().sound(context.str("sound", "BLOCK_TRIPWIRE_ATTACH"), center, 0.9F, 1.2F);
        plugin.fx().actionBar(player, plugin.config().messages().get("skill.zone-ready",
                "seconds", durationTicks / 20));
        return SkillResult.SUCCESS;
    }

    /** 终止某玩家名下的领域（退场 / 房间销毁 / 再次施法时调用）。 */
    public void clear(UUID owner) {
        cancel(owner);
    }

    /** 终止全部领域（房间销毁 / 插件卸载）。 */
    public void clearAll() {
        for (UUID owner : Set.copyOf(zones.keySet())) {
            cancel(owner);
        }
        zones.clear();
    }

    public int size() {
        return zones.size();
    }

    private void cancel(UUID owner) {
        ActiveZone zone = zones.remove(owner);
        if (zone != null && zone.task != null) {
            zone.task.cancel();
        }
    }

    /** 治疗只对友方生效：自己、同队队友，或 PVE 里同一房间的所有人。 */
    private boolean isFriendly(Player owner, Player other) {
        if (owner.equals(other)) {
            return true;
        }
        var room = plugin.rooms().roomOf(owner);
        if (room == null || plugin.rooms().roomOf(other) != room) {
            return false;
        }
        if (room.isPve()) {
            return true;
        }
        var team = room.teamOf(owner.getUniqueId());
        return team != null && team == room.teamOf(other.getUniqueId());
    }

    /** 粒子环：让领域的边界肉眼可见（不落方块、不改地形）。 */
    private void drawRing(Location center, double radius, String particle) {
        if (center.getWorld() == null || radius <= 0.0D) {
            return;
        }
        int points = Math.max(8, (int) Math.round(radius * 8));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.2D, Math.sin(angle) * radius);
            plugin.fx().particle(particle, point, 1, 0.02D);
        }
    }
}
