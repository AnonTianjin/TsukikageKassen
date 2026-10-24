package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.item.ItemFactory;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 载体护栏：把"原版物品自带的那些行为"逐条挡掉（计划 §3.4 拦截矩阵）。
 *
 * <p>漏掉任何一条都会出洋相：钻石锹会铲路、斧头会去皮、钓竿会抛钩、
 * 三叉戟会投掷、玻璃板会被放置、武器会被挖方块或塞进箱子转手。</p>
 */
public final class CarrierGuardListener implements Listener {

    private final TaketoriPlugin plugin;

    public CarrierGuardListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 手持插件武器时不挖方块。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.items().isPluginWeapon(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    /** 手持插件武器时不放置方块（玻璃板、三叉戟等载体）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.items().isPluginWeapon(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** 钓竿（疾风的钢丝）不得真的抛钩——否则钩子实体满地飞。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (plugin.items().isPluginWeapon(hand)) {
            event.setCancelled(true);
        }
    }

    /** 三叉戟（冰棘鱼）不得走原版投掷；插件技能自己生成的弹体不受影响。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        if (projectile instanceof Trident) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (plugin.items().isPluginWeapon(hand)) {
                event.setCancelled(true);
            }
        }
    }

    /** 绑定武器不允许进容器（防止转手 / 复制）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.config().soulbound()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 只在"打开了容器"时限制：玩家在自己背包里整理物品不该被拦
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof org.bukkit.inventory.PlayerInventory) {
            return;
        }
        // HOTBAR_SWAP：被交换的是快捷栏 / 副手物品，既不在 currentItem 也不在 cursor，
        // 必须按 hotbarButton 显式检查，否则数字键（1-9 / F）能把绑定武器塞进容器。
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP
                && event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            int button = event.getHotbarButton();
            ItemStack hotbarItem = button >= 0
                    ? player.getInventory().getItem(button)
                    : player.getInventory().getItemInOffHand();
            if (isSoulbound(hotbarItem)) {
                event.setCancelled(true);
            }
            return;
        }
        ItemStack moved = event.getCurrentItem();
        if (!plugin.items().isPluginWeapon(moved)) {
            moved = event.getCursor();
        }
        if (!isSoulbound(moved)) {
            return;
        }
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction().name().startsWith("MOVE_TO_OTHER_INVENTORY") || event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    /** 拖拽（InventoryDragEvent）同样会把物品分布进容器，与点击同规则拦截。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!plugin.config().soulbound()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        var top = event.getView().getTopInventory();
        if (top.getHolder() instanceof org.bukkit.inventory.PlayerInventory) {
            return;
        }
        if (!isSoulbound(event.getOldCursor())) {
            return;
        }
        // 拖拽落点只要有一个在容器槽（raw slot 小于顶层库存大小）即取消整场拖拽
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isSoulbound(ItemStack stack) {
        ItemFactory.Identity identity = plugin.items().read(stack);
        return identity != null && identity.soulbound();
    }

    /** 绑定武器不能被别人捡走。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        ItemFactory.Identity identity = plugin.items().read(item.getItemStack());
        if (identity == null || !identity.soulbound()) {
            return;
        }
        if (!identity.ownerId().equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
        }
    }
}
