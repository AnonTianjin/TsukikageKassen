package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.entity.Player;

/**
 * 弓的特殊射击开关（夕月）：Shift+右键切换状态，射箭时由监听器读取。
 *
 * <p>为什么不拦截拉弓：原版潜行时仍可拉弓，"取消拉弓"既做不到也不该做，
 * 所以这里做成状态开关，把差异留到 EntityShootBowEvent 里结算。</p>
 */
public final class SpecialShotToggleSkill implements Skill {

    private final TaketoriPlugin plugin;

    public SpecialShotToggleSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "special_shot_toggle";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        boolean enabled = profile.toggleSpecialShot();

        plugin.fx().actionBar(player, plugin.config().messages().get(
                enabled ? "input.special-shot-on" : "input.special-shot-off"));
        plugin.fx().sound(context.str("sound", "BLOCK_BEACON_ACTIVATE"), player, 0.7F, enabled ? 1.4F : 0.8F);
        plugin.fx().particle(context.str("particle", "CRIT"), player.getLocation().add(0.0D, 1.2D, 0.0D), 12, 0.3D);
        return SkillResult.SUCCESS;
    }
}
