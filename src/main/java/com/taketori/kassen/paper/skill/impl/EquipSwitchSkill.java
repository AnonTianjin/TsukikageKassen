package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

/**
 * 装备切换（疾风的 Q）：在快捷栏里切到另一件本角色武器。
 *
 * <p>这是"Q 键方案 A"（计划 §3.2 推荐）：不拦截丢弃事件，也不造物品，
 * 只切换选中槽位——零抖动、零复制风险。</p>
 *
 * <p>若玩家身上没有目标武器（例如刚换角色），补发一件到预留槽位，
 * 但补发前会先全背包搜索，避免重复发放。</p>
 */
public final class EquipSwitchSkill implements Skill {

    private final TaketoriPlugin plugin;

    public EquipSwitchSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "equip_switch";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String targetId = context.str("target", "");
        WeaponDef target = plugin.config().weapons().get(targetId);
        if (target == null) {
            plugin.getLogger().warning("equip_switch 指向了不存在的武器: " + targetId);
            return SkillResult.FAILED;
        }

        Player player = context.player();
        PlayerInventory inventory = player.getInventory();

        // 1) 快捷栏直接切换
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, targetId)) {
                inventory.setHeldItemSlot(slot);
                plugin.items().writeMode(stack, plugin.config().characters()
                        .profile(player.getUniqueId()).mode(targetId, target.defaultMode()));
                feedback(player, context, target, false);
                return SkillResult.SUCCESS;
            }
        }

        // 2) 背包里已有（只是不在快捷栏）→ 与当前手持槽交换，避免凭空生成
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, targetId)) {
                int held = inventory.getHeldItemSlot();
                ItemStack previous = inventory.getItem(held);
                inventory.setItem(held, stack);
                inventory.setItem(slot, previous);
                feedback(player, context, target, false);
                return SkillResult.SUCCESS;
            }
        }

        // 3) 真的没有 → 补发到预留武器槽
        int freeSlot = findFreeWeaponSlot(inventory);
        if (freeSlot < 0) {
            plugin.fx().actionBar(player, plugin.config().messages().get("input.equip-missing",
                    "weapon", target.display()));
            return SkillResult.NO_TARGET;
        }
        inventory.setItem(freeSlot, plugin.items().create(target, player));
        inventory.setHeldItemSlot(freeSlot);
        feedback(player, context, target, true);
        return SkillResult.SUCCESS;
    }

    private boolean matches(ItemStack stack, String weaponId) {
        ItemFactory.Identity identity = plugin.items().read(stack);
        return identity != null && weaponId.equals(identity.weaponId());
    }

    private int findFreeWeaponSlot(PlayerInventory inventory) {
        List<Integer> reserved = plugin.config().weaponSlots();
        for (int slot : reserved) {
            if (slot >= 0 && slot < 9 && (inventory.getItem(slot) == null || inventory.getItem(slot).getType().isAir())) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot) == null || inventory.getItem(slot).getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    private void feedback(Player player, SkillContext context, WeaponDef target, boolean granted) {
        String key = granted ? "input.equip-missing" : "input.equip-switched";
        plugin.fx().actionBar(player, plugin.config().messages().get(key, "weapon", target.display()));
        plugin.fx().sound(context.str("sound", "ITEM_ARMOR_EQUIP_GENERIC"), player, 0.7F, 1.3F);
    }
}
