package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import com.taketori.kassen.version.VersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 近战范围攻击：对视线前方扇形内的目标造成伤害、击退，可选减速、定身或连击叠伤。
 *
 * <p><b>攻击节奏由原版攻击冷却控制</b>（武器的 attack-speed 属性）：冷却未满不结算伤害，
 * 这是防止连点无限刷伤害的关键。</p>
 *
 * <p>技能伤害就是配置里的 {@code damage}；近战的原版伤害已在 CombatListener 里取消，
 * 所以不存在"原版 + 技能"双重结算。</p>
 */
public class MeleeSmashSkill implements Skill {

    protected final TaketoriPlugin plugin;

    public MeleeSmashSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "melee_smash";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();

        // 攻击冷却未就绪 → 本次挥击不结算伤害（空挥也不结算，与原版一致）
        float charge = player.getAttackCooldown();
        double minCharge = context.dbl("min-charge", 0.9D);
        if (charge < minCharge) {
            return SkillResult.NO_TARGET;
        }

        double damage = context.dbl("damage", 6.0D);
        double range = context.dbl("range", 3.0D);
        double knockback = context.dbl("knockback", 0.35D);
        int slowDuration = context.integer("slow-duration", 0);
        int freezeTicks = context.integer("freeze-ticks", 0);

        Location eye = player.getEyeLocation();

        // 目标筛选统一走 SkillTargets（受保护队友 / 召唤物 / 旁观者整个跳过），与其他技能同口径
        List<LivingEntity> targets = SkillTargets.enemiesInCone(plugin, player, range,
                context.dbl("angle", 55.0D));

        // 连击：短时间内的连续<b>命中</b>让伤害递增（疾风的剑靠这个吃贴脸）。
        // 叠层必须在确认命中之后：空挥不计数，否则对着空气挥也能把连击叠满。
        double comboBonus = context.dbl("combo-bonus", 0.0D);
        if (!targets.isEmpty() && comboBonus > 0.0D) {
            int stacks = plugin.states().bumpChain(player.getUniqueId(), context.weapon().id(),
                    context.integer("combo-window", 60));
            damage *= 1.0D + stacks * comboBonus;
            // 上限：叠到一定程度就不再加（0 = 不封顶），避免贴脸连打把人一秒秒掉
            double cap = context.dbl("combo-cap", 0.0D);
            if (cap > 0.0D && damage > cap) {
                damage = cap;
            }
            if (plugin.config().debug() && stacks > 0) {
                plugin.getLogger().info("[combat] melee 连击第 " + (stacks + 1) + " 段，伤害 "
                        + String.format("%.2f", damage)
                        + (cap > 0.0D ? "（上限 " + String.format("%.0f", cap) + "）" : ""));
            }
            // 连击可视化（C11）：叠上第 2 段起在动作栏报层数
            if (stacks > 0) {
                plugin.fx().actionBar(player, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<yellow>连击 <white>×" + (stacks + 1) + "</white> <gray>伤害提升"));
            }
        }

        for (LivingEntity target : targets) {
            // 子类钩子：兑现类技能（echo_consume）在这里按目标身上的前置状态放大伤害
            double multiplier = bonusMultiplier(context, target);
            double finalDamage = damage * multiplier;
            if (multiplier > 1.0D) {
                // 兑现反馈：不给提示的话，玩家根本不知道 echo-bonus 到底生效没有
                plugin.fx().actionBar(player, plugin.config().messages().get("skill.echo-consume",
                        "bonus", Math.round(multiplier * 100.0D)));
            }
            target.damage(finalDamage, player);
            plugin.damageNumbers().hit(player, target, finalDamage);   // 伤害数字（A1）
            if (knockback > 0.0D) {
                Vector push = target.getLocation().toVector().subtract(player.getLocation().toVector());
                push.setY(0.0D);
                if (push.lengthSquared() > 0.0001D) {
                    push.normalize().multiply(knockback).setY(0.2D);
                    target.setVelocity(target.getVelocity().add(push));
                }
            }
            if (slowDuration > 0) {
                applySlow(context, target, slowDuration);
            }
            if (freezeTicks > 0) {
                applyFreeze(target, freezeTicks);
            }
        }

        Location impact = eye.clone().add(player.getEyeLocation().getDirection()
                .normalize().multiply(Math.min(range, 2.0D)));
        plugin.fx().particle(context.str("particle", "SWEEP_ATTACK"), impact, targets.isEmpty() ? 1 : 3, 0.3D);
        if (!targets.isEmpty()) {
            plugin.fx().particle(context.str("hit-particle", "CRIT"), impact, 5, 0.3D);
            plugin.fx().sound(context.str("sound", "ENTITY_PLAYER_ATTACK_STRONG"), player, 0.8F, 1.0F);
        } else {
            plugin.fx().sound(context.str("miss-sound", "ENTITY_PLAYER_ATTACK_WEAK"), player, 0.4F, 1.2F);
        }
        return SkillResult.SUCCESS;
    }

    /**
     * 伤害倍率钩子：{@code melee_smash} 本体恒为 1.0；{@link EchoConsumeSkill} 覆写它，
     * 按目标身上的标记 / 破甲 / 减速 / 冰冻状态放大伤害。
     *
     * <p>放在这里而不是在 {@code execute} 里写 if，是为了让"兑现"这条线只多一个子类，
     * 不改动近战本体已经被验证过的判定与手感。</p>
     */
    protected double bonusMultiplier(SkillContext context, LivingEntity target) {
        return 1.0D;
    }

    protected void applySlow(SkillContext context, LivingEntity target, int durationTicks) {
        VersionAdapter versions = plugin.versions();
        PotionEffectType type = versions.potionEffect(context.str("slow-type", "SLOWNESS"));
        if (type == null) {
            return;
        }
        int amplifier = Math.max(0, context.integer("slow-amplifier", 0));
        target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, true, true));
    }

    /** 定身：缓慢拉满 + 负跳跃（纯原版药水效果，跨版本安全）。 */
    protected void applyFreeze(LivingEntity target, int ticks) {
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        if (slow != null) {
            target.addPotionEffect(new PotionEffect(slow, ticks, 250, false, true, true));
        }
        PotionEffectType jump = plugin.versions().potionEffect("JUMP_BOOST");
        if (jump != null) {
            target.addPotionEffect(new PotionEffect(jump, ticks, 128, false, false, true));
        }
    }
}
