package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

/**
 * 标记 / 破甲兑现（2.0 新增）：判定与 {@link MeleeSmashSkill} 完全一致，只在伤害上多一层
 * "条件满足则放大"。
 *
 * <p>前置条件是<b>四选一</b>，命中任一即满足：</p>
 * <ol>
 *   <li>易伤标记未过期（来自 {@code mark_apply} / 特殊射击 / 带 {@code mark-bonus} 的弹体）；</li>
 *   <li>破甲未过期（来自 {@code armor_break}）；</li>
 *   <li>目标身上有减速（丝线 / 冰系 / 拉扯的 {@code decelerate}）；</li>
 *   <li>目标处于冻结（{@code getFreezeTicks() > 0}）。</li>
 * </ol>
 *
 * <p>把"减速"也算作条件，是因为它最容易达成（多把武器的常态左键就能挂）——低门槛组合配低倍率、
 * 高门槛组合配高倍率，收益与操作成本对齐。{@code echo-bonus ≤ 1.0} 时等同关闭，便于一键回退。</p>
 */
public final class EchoConsumeSkill extends MeleeSmashSkill {

    public EchoConsumeSkill(TaketoriPlugin plugin) {
        super(plugin);
    }

    @Override
    public String type() {
        return "echo_consume";
    }

    @Override
    protected double bonusMultiplier(SkillContext context, LivingEntity target) {
        double bonus = context.dbl("echo-bonus", 1.5D);
        if (bonus <= 1.0D) {
            return 1.0D;   // 显式关闭
        }
        return SkillTargets.hasConsumableState(plugin, target) ? bonus : 1.0D;
    }
}
