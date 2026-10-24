package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.entity.Player;

/**
 * 镜面技能（月镜右键）：展开镜面，窗内吸收伤害并按比例反弹。
 *
 * <p>与强化防御共用同一套"防御窗口"机制，只是数值与表现不同，
 * 这样反弹逻辑在监听器里只需要写一次。</p>
 */
public final class MirrorSkill implements Skill {

    private final TaketoriPlugin plugin;

    public MirrorSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "mirror_skill";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        int duration = context.integer("duration-ticks", 80);
        double reflection = context.dbl("reflect-ratio", 0.6D);
        double absorption = context.dbl("absorption", 6.0D);

        plugin.states().setDefense(player.getUniqueId(), duration, reflection, absorption, context.weapon().id());
        if (absorption > 0.0D) {
            SkillTargets.addAbsorption(player, absorption);
        }

        plugin.fx().actionBar(player, plugin.config().messages().get("skill.mirror-ready",
                "time", SkillManager.formatSeconds(duration / 20.0D)));
        plugin.fx().sound(context.str("sound", "BLOCK_GLASS_BREAK"), player, 0.8F, 1.6F);
        plugin.fx().particle(context.str("particle", "END_ROD"), player.getLocation().add(0.0D, 1.0D, 0.0D), 30, 0.6D);
        return SkillResult.SUCCESS;
    }
}
