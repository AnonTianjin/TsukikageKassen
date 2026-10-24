package com.taketori.kassen.paper.editor;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编辑器菜单的持有者：把"哪个格子点了做什么"随菜单实例一起带走。
 *
 * <p>为什么不用标题比对来识别菜单：多个管理员同时开菜单、或者有人把菜单标题改掉时，
 * 标题匹配会串页或失效；{@link InventoryHolder} 是实例级的，天然隔离。</p>
 */
public final class EditorHolder implements InventoryHolder {

    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;

    /** 菜单创建后回填，供 Bukkit 返回。 */
    public void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    public void onClick(int slot, Consumer<Player> action) {
        actions.put(slot, action);
    }

    public void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            action.accept(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
