package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.SkillTargets;
import com.taketori.kassen.paper.skill.impl.ProjectileSkill;
import com.taketori.kassen.paper.state.CombatStates;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * 插件弹体的命中结算：范围伤害 + 距离衰减 + 点燃 + 减速 + 击退 + 表现。
 *
 * <p>弹体在飞行途中带着自己的参数（写在 PDC 里），所以不同武器可以共用同一个
 * {@code projectile} 技能类型，只是数值不同。所有数值都来自配置：
 * 衰减下限 {@code min-falloff}、点燃时长 {@code ignite-ticks} 都可调。</p>
 */
public final class ProjectileListener implements Listener {

    /** 镜面弹回的速度上限（约等于满蓄弓速）：与 CombatListener.onReflectProjectile 的
     *  MAX_REFLECT_SPEED 同值，防止两个镜面玩家往复对弹插件弹体时速度按倍率连乘失控。 */
    private static final double MAX_REFLECT_SPEED = 3.0D;

    private final TaketoriPlugin plugin;

    public ProjectileListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSkill skill = plugin.projectileSkill();
        if (skill == null || !skill.isSkillProjectile(projectile)) {
            return;
        }

        Location center = event.getHitEntity() != null
                ? event.getHitEntity().getLocation()
                : projectile.getLocation();
        double damage = skill.damageOf(projectile).orElse(0.0D);
        double radius = Math.max(0.5D, skill.radiusOf(projectile, 2.0D));
        double knockback = skill.knockbackOf(projectile);
        boolean ignite = skill.igniteOf(projectile);
        int igniteTicks = skill.igniteTicksOf(projectile);
        int slowDuration = skill.slowDurationOf(projectile);
        int slowAmplifier = skill.slowAmplifierOf(projectile);
        double minFalloff = skill.minFalloffOf(projectile);
        int freezeTicks = skill.freezeTicksOf(projectile);

        Entity shooter = projectile.getShooter() instanceof Entity entity ? entity : null;
        if (shooter instanceof Player owner) {
            plugin.markInternalDamage(owner.getUniqueId());
        }
        try {
            for (Entity nearby : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (!(nearby instanceof LivingEntity living) || nearby.equals(shooter) || living.isDead()) {
                    continue;
                }
                // 等待区/玻璃笼保护期玩家：伤害事件会被取消，连燃烧/减速/击退等直接效果也一并跳过
                // （笼保护无条件生效；等待区保护跟随 waiting.protect 开关，与 WaitingListener 一致）
                if (living instanceof Player protectedPlayer) {
                    var protectedRoom = plugin.rooms().roomOf(protectedPlayer);
                    if (protectedRoom != null && (protectedRoom.isCaged(protectedPlayer.getUniqueId())
                            || (plugin.config().waitingProtect()
                            && protectedRoom.isProtected(protectedPlayer.getUniqueId())))) {
                        continue;
                    }
                }
                // 受保护的队友与召唤物整个跳过：否则伤害被事件层取消，但状态与击退会照常泄漏
                if (shooter instanceof Player owner && SkillTargets.isFilteredTarget(plugin, owner, living)) {
                    continue;
                }
                // 镜面反射：被打的人正处于反射窗口 → 不结算伤害，改为把弹体弹回去
                if (nearby instanceof Player victim && !victim.equals(shooter) && shooter != null) {
                    CombatStates.Reflection reflection = plugin.states().reflection(victim.getUniqueId());
                    if (reflection != null) {
                        Vector bounced = projectile.getVelocity().multiply(-reflection.speedMultiplier());
                        if (bounced.length() > MAX_REFLECT_SPEED) {
                            bounced.normalize().multiply(MAX_REFLECT_SPEED);
                        }
                        projectile.setVelocity(bounced);
                        projectile.setShooter(victim);
                        projectile.setTicksLived(1);
                        plugin.fx().particle("END_ROD", victim.getLocation().add(0.0D, 1.0D, 0.0D), 20, 0.4D);
                        plugin.fx().sound("BLOCK_GLASS_BREAK", victim, 0.8F, 1.8F);
                        if (plugin.config().debug()) {
                            plugin.getLogger().info("[combat] 镜面把插件弹体弹回给 " + victim.getName());
                        }
                        return;
                    }
                }
                double distance = living.getLocation().distance(center);
                // getNearbyEntities 是立方体包围盒，角落目标最远可达 radius 的 √3 倍——
                // 必须再做球形距离裁剪，否则范围外的角落目标也被炸到（与 SkillTargets.enemiesInRadius 同口径）
                if (distance > radius) {
                    continue;
                }
                // 距离衰减：从中心满伤线性衰减到 min-falloff（默认 50%）
                double ratio = Math.min(1.0D, distance / (radius + 0.5D));
                double falloff = 1.0D - (1.0D - minFalloff) * ratio;
                // 兑现：目标带标记 / 破甲 / 减速 / 冻结时放大（echo-bonus ≤ 1.0 = 关闭）
                double echoBonus = skill.echoBonusOf(projectile);
                double echoMultiplier = echoBonus > 1.0D
                        && SkillTargets.hasConsumableState(plugin, living) ? echoBonus : 1.0D;
                if (echoMultiplier > 1.0D && shooter instanceof Player echoOwner) {
                    plugin.fx().actionBar(echoOwner, plugin.config().messages().get("skill.echo-consume",
                            "bonus", Math.round(echoMultiplier * 100.0D)));
                }
                double applied = damage * falloff * echoMultiplier;
                applyDamage(living, applied, shooter);
                if (shooter instanceof Player owner) {
                    plugin.damageNumbers().hit(owner, living, applied);   // 伤害数字（A1）
                }
                // 命中施加状态：放在伤害结算之后，避免"这一发自己吃自己刚挂上的标记"
                double markBonus = skill.markBonusOf(projectile);
                if (markBonus > 0.0D) {
                    plugin.states().mark(living.getUniqueId(), skill.markTicksOf(projectile), markBonus);
                }
                double armorPierce = skill.armorPierceOf(projectile);
                if (armorPierce > 0.0D) {
                    // 破甲时长走自己的键：此前复用 mark-ticks，导致"只想挂破甲"时被标记时长绑架
                    plugin.states().armorBreak(living.getUniqueId(), skill.armorPierceTicksOf(projectile),
                            armorPierce, 1);
                }
                if (ignite && igniteTicks > 0) {
                    living.setFireTicks(Math.max(living.getFireTicks(), igniteTicks));
                }
                if (slowDuration > 0) {
                    PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
                    if (slow != null) {
                        living.addPotionEffect(new PotionEffect(slow, slowDuration, Math.max(0, slowAmplifier), false, true, true));
                    }
                }
                if (freezeTicks > 0) {
                    applyFreeze(living, freezeTicks);
                }
                if (knockback > 0.0D) {
                    Vector push = living.getLocation().toVector().subtract(center.toVector());
                    push.setY(0.0D);
                    if (push.lengthSquared() > 0.0001D) {
                        push.normalize().multiply(knockback).setY(0.2D);
                        living.setVelocity(living.getVelocity().add(push));
                    }
                }
            }
        } finally {
            if (shooter instanceof Player owner) {
                plugin.unmarkInternalDamage(owner.getUniqueId());
            }
        }

        plugin.fx().particle(skill.hitParticleOf(projectile), center, 16, radius * 0.3D, 0.05D);
        plugin.fx().sound(skill.hitSoundOf(projectile), center, 0.9F, 1.0F);
        projectile.remove();
    }

    /**
     * 定身：缓慢等级拉满（几乎无法移动）+ 负跳跃（无法起跳）。
     * 纯原版药水效果，跨版本安全，也不会把玩家卡在方块里。
     */
    private void applyFreeze(LivingEntity target, int ticks) {
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        if (slow != null) {
            target.addPotionEffect(new PotionEffect(slow, ticks, 250, false, true, true));
        }
        PotionEffectType jump = plugin.versions().potionEffect("JUMP_BOOST");
        if (jump != null) {
            target.addPotionEffect(new PotionEffect(jump, ticks, 128, false, false, true));
        }
    }

    private void applyDamage(LivingEntity target, double amount, Entity shooter) {
        if (amount <= 0.0D) {
            return;
        }
        if (shooter != null) {
            target.damage(amount, shooter);
        } else {
            target.damage(amount);
        }
    }
}
