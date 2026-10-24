package com.taketori.kassen.paper.skill;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.item.PDCKeys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 技能选目标的公共判定：友伤保护、召唤物护栏、圆形范围、视线扇形。
 *
 * <p>抽出来的原因：2.0 版新增的 {@code mark_apply} / {@code armor_break} 等技能都要用同一套
 * "谁算敌人"的规则，散在各类里复制粘贴迟早会走偏（例如漏掉旁观者、或把队友也算成目标）。
 * 玩家对玩家与玩家对月人两条伤害链共用这里，保证同一攻击对两类目标的判定口径一致。</p>
 */
public final class SkillTargets {

    private SkillTargets() {
    }

    /** 吸收（金心）叠加硬上限（点数）：多个技能/领域反复给吸收时的统一闸门，防止无限叠。 */
    public static final double ABSORPTION_CAP = 20.0D;

    /** 给目标叠加吸收金心，封顶 {@link #ABSORPTION_CAP}。 */
    public static void addAbsorption(LivingEntity target, double amount) {
        if (target == null || amount <= 0.0D) {
            return;
        }
        target.setAbsorptionAmount(Math.min(ABSORPTION_CAP, target.getAbsorptionAmount() + amount));
    }

    /**
     * 对方是不是"受友伤保护的同队队友"：是则整个目标跳过——
     * 伤害事件会被友伤处理器取消，但击退 / 减速 / 标记等控制效果不会，必须在这里拦截。
     */
    public static boolean isProtectedTeammate(TaketoriPlugin plugin, Player caster, Player other) {
        var room = plugin.rooms().roomOf(caster);
        if (room == null || !room.isFriendlyFireProtected()) {
            return false;
        }
        if (plugin.rooms().roomOf(other) != room) {
            return false;
        }
        TeamId casterTeam = room.teamOf(caster.getUniqueId());
        TeamId otherTeam = room.teamOf(other.getUniqueId());
        return casterTeam != null && casterTeam == otherTeam;
    }

    /**
     * 目标是不是"不该被打的召唤物"：主人本人，或主人受友伤保护的队友——
     * 口径与 {@code CombatListener#onSummonedDamage} 的事件级护栏一致。
     * 是则整个目标跳过（伤害、击退、状态都不施加），否则守夜犬会被自家范围技能挂满状态。
     */
    public static boolean isProtectedSummon(TaketoriPlugin plugin, Player caster, LivingEntity target) {
        String ownerId = target.getPersistentDataContainer()
                .get(PDCKeys.summonedOwner(), PersistentDataType.STRING);
        if (ownerId == null) {
            return false;
        }
        if (caster.getUniqueId().toString().equals(ownerId)) {
            return true;
        }
        Player owner;
        try {
            owner = Bukkit.getPlayer(UUID.fromString(ownerId));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return owner != null && isProtectedTeammate(plugin, owner, caster);
    }

    /**
     * 统一的"这个目标要不要整个跳过"判定：受保护的队友 / 旁观者玩家、受保护的召唤物。
     * 所有范围类结算（近战扇形、冲击波、弹体溅射、箭矢溅射）都应先过这里，
     * 保证玩家目标与月人目标走同一套过滤口径。
     */
    public static boolean isFilteredTarget(TaketoriPlugin plugin, Player caster, LivingEntity target) {
        if (target instanceof Player other) {
            return isUntargetable(other) || isProtectedTeammate(plugin, caster, other)
                    || isProtectionShielded(plugin, other);
        }
        return isProtectedSummon(plugin, caster, target);
    }

    /**
     * 目标是否处于笼内冻结或等待区保护期：口径与弹体结算（ProjectileListener）一致——
     * CAGED 的参赛者必过滤，WAITING/STARTING 的等待者仅在 waitingProtect 开启时过滤。
     * 是则整个目标跳过（伤害、击退、状态都不施加），否则笼内玩家会被范围技能直接打中。
     */
    public static boolean isProtectionShielded(TaketoriPlugin plugin, Player target) {
        var room = plugin.rooms().roomOf(target);
        if (room == null) {
            return false;
        }
        return room.isCaged(target.getUniqueId())
                || (plugin.config().waitingProtect() && room.isProtected(target.getUniqueId()));
    }

    /** 旁观者（或非存活）跳过。 */
    public static boolean isUntargetable(Player player) {
        return player == null || player.isDead() || player.getGameMode().name().equals("SPECTATOR");
    }

    /** 以 {@code center} 为圆心、{@code radius} 格内的敌方生物（含月人等非玩家生物）。 */
    public static List<LivingEntity> enemiesInRadius(TaketoriPlugin plugin, Player caster,
                                                     Location center, double radius) {
        List<LivingEntity> targets = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return targets;
        }
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster) || living.isDead()) {
                continue;
            }
            if (isFilteredTarget(plugin, caster, living)) {
                continue;
            }
            // getNearbyEntities 是立方体包围盒，角落目标最远可达 radius 的 √3 倍，必须再做圆形距离校验
            Vector toTarget = living.getLocation().add(0.0D, living.getHeight() * 0.5D, 0.0D)
                    .toVector().subtract(center.toVector());
            if (toTarget.lengthSquared() > radius * radius) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    /**
     * 视线前方、半径 {@code range} 内、与视线夹角不超过 {@code halfAngleDegrees} 的敌方生物。
     * 与近战扇形同口径（MeleeSmashSkill 也走这里）。
     */
    public static List<LivingEntity> enemiesInCone(TaketoriPlugin plugin, Player caster,
                                                   double range, double halfAngleDegrees) {
        List<LivingEntity> targets = new ArrayList<>();
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double halfAngle = Math.toRadians(halfAngleDegrees);
        for (Entity entity : caster.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster) || living.isDead()) {
                continue;
            }
            if (isFilteredTarget(plugin, caster, living)) {
                continue;
            }
            Vector toTarget = living.getLocation().add(0.0D, living.getHeight() * 0.5D, 0.0D)
                    .toVector().subtract(eye.toVector());
            if (toTarget.length() > range || toTarget.length() < 0.01D) {
                continue;
            }
            if (direction.angle(toTarget) > halfAngle) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    /**
     * 统一的"选目标"入口：{@code radius >= rayThreshold} 时走视线射线（远程角色用它做"瞄准射击"），
     * 否则走以自身为圆心的范围判定。
     */
    public static List<LivingEntity> select(TaketoriPlugin plugin, Player caster,
                                            double radius, double angleDegrees, double rayThreshold) {
        if (radius >= rayThreshold) {
            return enemiesInCone(plugin, caster, radius, angleDegrees);
        }
        return enemiesInRadius(plugin, caster, caster.getLocation(), radius);
    }

    /**
     * 目标身上是否有"可兑现"的前置状态：易伤标记 / 破甲 / 减速 / 冻结，四选一。
     *
     * <p>{@code echo_consume} 技能与弹体的 {@code echo-bonus} 共用这份判定——两条兑现链路
     * 必须对"什么算前置"有完全一致的理解，否则同一套连招换个武器就失效。</p>
     */
    public static boolean hasConsumableState(TaketoriPlugin plugin, LivingEntity target) {
        if (target == null) {
            return false;
        }
        UUID id = target.getUniqueId();
        if (plugin.states().markBonus(id) > 0.0D) {
            return true;
        }
        if (plugin.states().armorBreak(id) != null) {
            return true;
        }
        if (target.getFreezeTicks() > 0) {
            return true;
        }
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        return slow != null && target.hasPotionEffect(slow);
    }
}
