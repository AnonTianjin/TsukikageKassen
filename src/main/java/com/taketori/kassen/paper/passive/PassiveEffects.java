package com.taketori.kassen.paper.passive;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.skill.PassiveDef;
import com.taketori.kassen.core.weapon.WeaponDef;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 被动技能的命中结算。
 *
 * <p>近战与箭矢/投掷走同一条路径，区别只在触发概率：近战用 {@code chance}，
 * 远程用 {@code throw-chance}（未配置时回落到 {@code chance}）。
 * 这样"投掷必定减速、近战只有 25%"这种差异只写在配置里，不需要两套代码。</p>
 *
 * <p>附加伤害一律打内部伤害标记：否则它会被 {@code CombatListener} 当成一次新的命中，
 * 再次触发被动，形成递归。</p>
 */
public final class PassiveEffects {

    private final TaketoriPlugin plugin;

    public PassiveEffects(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 近战命中；{@code baseDamage} 是本次已经结算的伤害（吸血按它算）。 */
    public void melee(Player attacker, WeaponDef weapon, LivingEntity victim, double baseDamage) {
        if (attacker == null || weapon == null || victim == null || victim.isDead()) {
            return;
        }
        PassiveDef passive = weapon.passive();
        if (!passive.triggersOnMelee()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > passive.chance()) {
            return;
        }
        apply(attacker, weapon, passive, victim, baseDamage);
    }

    /** 箭矢 / 三叉戟投掷命中。 */
    public void projectile(Player attacker, WeaponDef weapon, LivingEntity victim, double baseDamage) {
        if (attacker == null || weapon == null || victim == null || victim.isDead()) {
            return;
        }
        PassiveDef passive = weapon.passive();
        if (!passive.triggersOnProjectile()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > passive.throwChance()) {
            return;
        }
        apply(attacker, weapon, passive, victim, baseDamage);
    }

    private void apply(Player attacker, WeaponDef weapon, PassiveDef passive,
                       LivingEntity victim, double baseDamage) {
        switch (passive.onHit()) {
            case MARK -> plugin.states().mark(victim.getUniqueId(),
                    passive.ticks(), passive.markBonus() > 0.0D ? passive.markBonus() : 0.1D);
            case ARMOR_BREAK -> plugin.states().armorBreak(victim.getUniqueId(),
                    passive.ticks(), passive.pierce() > 0.0D ? passive.pierce() : 0.1D, 1);
            case SLOW -> {
                PotionEffectType slow = plugin.versions().potionEffect("slowness");
                if (slow != null) {
                    victim.addPotionEffect(new PotionEffect(slow,
                            Math.max(1, passive.effectiveSlowTicks()),
                            passive.slowAmplifier(), false, true, true));
                }
            }
            case LIFESTEAL -> {
                double heal = baseDamage * passive.lifestealRatio();
                if (heal > 0.0D) {
                    double max = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                            ? attacker.getHealth()
                            : attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    attacker.setHealth(Math.min(max, attacker.getHealth() + heal));
                }
            }
            case KNOCKBACK -> {
                Vector direction = victim.getLocation().toVector()
                        .subtract(attacker.getLocation().toVector());
                if (direction.lengthSquared() > 0.0001D) {
                    victim.setVelocity(victim.getVelocity().add(direction.normalize().multiply(0.4D).setY(0.25D)));
                }
            }
            default -> {
                // NONE：只有常驻属性，没有命中效果
            }
        }

        if (passive.hasBonusDamage()) {
            double damage = passive.bonusDamage();
            plugin.markInternalDamage(attacker.getUniqueId());
            try {
                victim.damage(damage, attacker);
            } finally {
                plugin.unmarkInternalDamage(attacker.getUniqueId());
            }
        }

        if (!passive.particle().isBlank()) {
            plugin.fx().particle(passive.particle(),
                    victim.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.3D);
        }
        if (!passive.sound().isBlank()) {
            plugin.fx().sound(passive.sound(), victim.getLocation(), 0.6F, 1.2F);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[passive] " + attacker.getName() + " " + weapon.id()
                    + " → " + passive.display() + "(" + passive.onHit() + ") 命中 " + victim.getName());
        }
    }
}
