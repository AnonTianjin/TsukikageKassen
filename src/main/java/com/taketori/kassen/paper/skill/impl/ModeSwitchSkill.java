package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 模式切换（Q）：在武器声明的多个 mode 之间循环。
 *
 * <p>真源写在玩家档案里（计划 §3.3），PDC 里的 mode 只是展示用冗余，
 * 避免每次切换都重写物品造成物品栏闪烁。</p>
 */
public final class ModeSwitchSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ModeSwitchSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "mode_switch";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        WeaponDef weapon = context.weapon();
        if (!weapon.hasModes()) {
            // 单模式武器按 Q 不做任何事，交给输入层的"切换装备"逻辑处理
            return SkillResult.NO_TARGET;
        }

        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String nextMode = weapon.nextMode(context.mode());
        profile.setMode(weapon.id(), nextMode);

        ItemStack hand = player.getInventory().getItemInMainHand();
        plugin.items().writeMode(hand, nextMode);

        plugin.fx().actionBar(player, plugin.config().messages().get("input.mode-switched",
                "mode", weapon.modeDisplay(nextMode),
                "id", nextMode));
        plugin.fx().sound(context.str("sound", "BLOCK_BEACON_ACTIVATE"), player, 0.6F, 1.6F);
        plugin.fx().particle(context.str("particle", "ENCHANTED_HIT"),
                player.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.3D);
        return SkillResult.SUCCESS;
    }
}
