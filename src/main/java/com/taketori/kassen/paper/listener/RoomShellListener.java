package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 对局功能壳（鹦鹉螺壳）的交互与防护：
 *
 * <ul>
 *   <li><b>右键打开</b>「退出对局 / 投降」GUI（等待期也开，界面里只有退出房间）；</li>
 *   <li><b>丢不掉</b>：{@link PlayerDropItemEvent} 拦截；</li>
 *   <li><b>拖不动</b>：点击 / 快捷栏交换 / 拖拽三条路径都拦，防止塞进容器或换手；</li>
 *   <li><b>死亡保留</b>：对局内阵亡不因掉落规则而丢失壳。</li>
 * </ul>
 *
 * <p>与 {@code MenuClockListener} 同一套写法：{@code ignoreCancelled = false}，
 * 因为领地 / 保护类插件可能先取消这次右键，沿用 true 的话壳会「按了没反应」。</p>
 */
public final class RoomShellListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public RoomShellListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 右键打开

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;   // 副手不参与，避免与主手判定混淆
        }
        Player player = event.getPlayer();
        if (!plugin.roomShell().isRoomShell(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        // 只在房间内可用：在大厅误拿到壳（不应该发生）时不弹界面
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            player.sendMessage(plugin.config().messages().get("room-shell.not-in-room"));
            return;
        }
        plugin.roomShellMenu().open(player);
    }

    // ---------------------------------------------------------------- 丢不掉

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.roomShell().isRoomShell(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.config().messages().get("room-shell.undroppable"));
    }

    // ---------------------------------------------------------------- 拖不动 / 塞不进容器

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 在自己背包里点击不算「塞进容器」，但仍然禁止移动壳本身
        boolean inOwnInventory = event.getView().getTopInventory().getHolder()
                instanceof org.bukkit.inventory.PlayerInventory;
        if (!inOwnInventory && shellInvolved(event, player)) {
            event.setCancelled(true);
            player.sendMessage(plugin.config().messages().get("room-shell.locked"));
            return;
        }
        if (inOwnInventory && shellInvolved(event, player)) {
            event.setCancelled(true);
        }
    }

    /** 这次点击有没有牵涉壳（光标 / 当前格 / 快捷栏交换三条路径）。 */
    private boolean shellInvolved(InventoryClickEvent event, Player player) {
        if (plugin.roomShell().isRoomShell(event.getCurrentItem())
                || plugin.roomShell().isRoomShell(event.getCursor())) {
            return true;
        }
        if (event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            int button = event.getHotbarButton();
            ItemStack hotbar = button >= 0
                    ? player.getInventory().getItem(button)
                    : player.getInventory().getItemInOffHand();
            return plugin.roomShell().isRoomShell(hotbar);
        }
        return false;
    }

    /**
     * 壳不能通过<b>拖拽</b>进容器。
     *
     * <p>点击与拖拽是两条独立的事件路径：只拦 {@code InventoryClickEvent} 的话，
     * 按住左键把壳拖进箱子依然能成功。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder()
                instanceof org.bukkit.inventory.PlayerInventory) {
            return;
        }
        if (!plugin.roomShell().isRoomShell(event.getOldCursor())) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(plugin.config().messages().get("room-shell.locked"));
    }

    /**
     * 壳不能被 F 键换到副手。
     *
     * <p>{@link PlayerSwapHandItemsEvent} 是<b>独立事件</b>，不经过
     * {@code InventoryClickEvent} 的 {@code HOTBAR_SWAP} 路径——只拦点击的话，
     * 未打开任何容器时按 F 就能把壳换到副手。而右键入口只认主手，
     * 壳一旦进了副手，面板就再也打不开（玩家会以为「按了没反应」）。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!plugin.roomShell().isRoomShell(event.getMainHandItem())
                && !plugin.roomShell().isRoomShell(event.getOffHandItem())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.config().messages().get("room-shell.locked"));
    }

    // ---------------------------------------------------------------- 死亡保留

    /**
     * 对局内阵亡不掉壳。
     *
     * <p>对局里本来就走「死亡保留物品」的世界规则，这里再显式兜一层：服务器若关了
     * keepInventory，或别的插件改了掉落，壳仍会被重新加回，避免玩家复活后没有入口。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (event.getKeepInventory()) {
            return;   // 世界规则已经保留，无需处理
        }
        // 掉落列表里若已有壳，先从列表移除，再确保玩家背包里有一份
        event.getDrops().removeIf(drop -> plugin.roomShell().isRoomShell(drop));
        if (!plugin.roomShell().hasShell(player)) {
            plugin.roomShell().give(player);
        }
    }
}
