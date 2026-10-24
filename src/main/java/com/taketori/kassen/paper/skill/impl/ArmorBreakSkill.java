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
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 破甲 / 削防（2.0 新增）：范围伤害 + 击飞，并给目标挂破甲层数。
 *
 * <p>破甲写进 {@code CombatStates.ArmorBreak}（与易伤标记分表存放，两者可共存），
 * 由 {@code CombatListener} 的破甲监听统一放大后续伤害，硬上限
 * {@code CombatStates.MAX_ARMOR_PIERCE}（0.30）——破满也只把对铁甲的减伤从 65.2% 降到 51.2%，
 * 不会出现"一击半血"。</p>
 *
 * <p>层数 {@code debuff-stacks} 只用于提示与语义（真正的强度由 {@code armor-pierce} 决定），
 * 这样实现简单，也不会出现"叠十层破甲秒人"。参数全部来自 weapons.yml，可热重载。</p>
 */
public final class ArmorBreakSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ArmorBreakSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "armor_break";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double damage = context.dbl("damage", 10.0D);
        double radius = context.dbl("radius", 3.5D);
        double knockback = context.dbl("knockback", 0.6D);
        double launch = context.dbl("launch", 0.4D);
        double minFalloff = Math.max(0.0D, Math.min(1.0D, context.dbl("min-falloff", 0.5D)));
        double pierce = context.dbl("armor-pierce", 0.15D);
        int debuffTicks = context.integer("debuff-ticks", 100);
        int stacks = context.integer("debuff-stacks", 1);
        int slowDuration = context.integer("slow-duration", 0);

        Location center = player.getLocation();
        List<LivingEntity> targets = SkillTargets.enemiesInRadius(plugin, player, center, radius);

        for (LivingEntity target : targets) {
            // 边缘衰减：站在圈子边上只吃 min-falloff 的比例
            double ratio = 1.0D;
            if (radius > 0.01D) {
                double distance = target.getLocation().distance(center);
                ratio = Math.max(minFalloff, 1.0D - distance / radius);
            }
            double applied = damage * ratio;
            if (applied > 0.0D) {
                target.damage(applied, player);
                plugin.damageNumbers().hit(player, target, applied);
            }

            Vector push = target.getLocation().toVector().subtract(center.toVector());
            push.setY(0.0D);
            if (push.lengthSquared() > 0.0001D) {
                push.normalize().multiply(knockback).setY(launch);
                target.setVelocity(target.getVelocity().add(push));
            } else if (launch > 0.0D) {
                target.setVelocity(target.getVelocity().add(new Vector(0.0D, launch, 0.0D)));
            }

            plugin.states().armorBreak(target.getUniqueId(), debuffTicks, pierce, stacks);
            if (slowDuration > 0) {
                PotionEffectType slow = plugin.versions().potionEffect(context.str("slow-type", "SLOWNESS"));
                if (slow != null) {
                    target.addPotionEffect(new PotionEffect(slow, slowDuration,
                            Math.max(0, context.integer("slow-amplifier", 0)), false, true, true));
                }
            }
        }

        plugin.fx().particle(context.str("particle", "EXPLOSION"), center, targets.isEmpty() ? 4 : 18, 0.6D);
        plugin.fx().sound(context.str("sound", "BLOCK_ANVIL_LAND"), center, 1.0F, 0.8F);
        if (targets.isEmpty()) {
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.no-target"));
            return SkillResult.NO_TARGET;
        }
        plugin.fx().actionBar(player, plugin.config().messages().get("skill.armor-break",
                "count", targets.size(),
                "bonus", Math.round(Math.min(com.taketori.kassen.paper.state.CombatStates.MAX_ARMOR_PIERCE,
                        pierce) * 100.0D),
                "seconds", debuffTicks / 20,
                "stacks", stacks));
        return SkillResult.SUCCESS;
    }
}
