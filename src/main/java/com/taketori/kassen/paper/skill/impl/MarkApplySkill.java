package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * 易伤标记施加（2.0 新增）：范围或视线方向挂易伤标记，可选自身隐身与附加减速。
 *
 * <p>标记复用 {@code CombatStates.Mark}，由 {@code CombatListener.onMarkedDamage} 自动放大后续
 * <b>所有</b>伤害来源——所以这个技能自身伤害刻意很低，价值全在"给全队开一个窗口"。</p>
 *
 * <p>标记是<b>覆盖</b>语义（不叠加倍率），重复施放只刷新时长：否则连续挂标会变成无限增伤。
 * 数值（伤害 / 半径 / 加成 / 时长 / 冷却）全部来自 weapons.yml，可热重载。</p>
 */
public final class MarkApplySkill implements Skill {

    /** radius ≥ 这个值就走"视线射线"分支（远程角色的瞄准射击）。 */
    private static final double RAY_THRESHOLD = 8.0D;

    private final TaketoriPlugin plugin;

    public MarkApplySkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "mark_apply";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double damage = context.dbl("damage", 4.0D);
        double radius = context.dbl("radius", 3.0D);
        double angle = context.dbl("angle", 45.0D);
        double markBonus = context.dbl("mark-bonus", 0.2D);
        int markTicks = context.integer("mark-ticks", 80);
        int slowDuration = context.integer("slow-duration", 0);

        List<LivingEntity> targets = SkillTargets.select(plugin, player, radius, angle, RAY_THRESHOLD);

        for (LivingEntity target : targets) {
            if (damage > 0.0D) {
                target.damage(damage, player);
                plugin.damageNumbers().hit(player, target, damage);
            }
            plugin.states().mark(target.getUniqueId(), markTicks, markBonus);
            if (slowDuration > 0) {
                applySlow(context, target, slowDuration);
            }
        }

        plugin.fx().particle(context.str("particle", "END_ROD"),
                player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.6D)), 16, 0.4D);
        plugin.fx().sound(context.str("sound", "BLOCK_AMETHYST_BLOCK_CHIME"), player, 0.8F, 1.4F);
        if (targets.isEmpty()) {
            // 一个都没挂上：明确说一声，免得玩家以为技能没生效；且不给自身隐身（否则"失败施法"白赚隐身）
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.no-target"));
            return SkillResult.NO_TARGET;
        }
        // 自身隐身：远程角色挂完标记要能重新找角度（0 = 关闭）。放在 targets 判空之后，
        // 保证隐身只在真正挂上标记（SUCCESS）时给——空目标时玩家不白拿隐身。
        int invisibleTicks = context.integer("invisible-ticks", 0);
        if (invisibleTicks > 0) {
            PotionEffectType invisibility = plugin.versions().potionEffect("INVISIBILITY");
            if (invisibility != null) {
                player.addPotionEffect(new PotionEffect(invisibility, invisibleTicks, 0, true, false, true));
            }
        }
        plugin.fx().actionBar(player, plugin.config().messages().get("skill.mark-apply",
                "count", targets.size(),
                "bonus", Math.round(markBonus * 100.0D),
                "seconds", markTicks / 20));
        return SkillResult.SUCCESS;
    }

    private void applySlow(SkillContext context, LivingEntity target, int durationTicks) {
        PotionEffectType type = plugin.versions().potionEffect(context.str("slow-type", "SLOWNESS"));
        if (type == null) {
            return;
        }
        int amplifier = Math.max(0, context.integer("slow-amplifier", 0));
        target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, true, true));
    }
}
