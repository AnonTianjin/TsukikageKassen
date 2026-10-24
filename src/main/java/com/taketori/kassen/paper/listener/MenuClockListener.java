package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 菜单时钟（{@link com.taketori.kassen.paper.item.MenuClock}）的交互：
 * 右键打开玩家菜单，并且丢不掉。
 *
 * <p>用 {@code ignoreCancelled = false}：领地 / 保护类插件可能先取消这次右键，
 * 沿用 {@code ignoreCancelled = true} 的话时钟会"按了没反应"（与大厅告示牌同一类坑）。</p>
 */
public final class MenuClockListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public MenuClockListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 副手不参与，避免与主手的判定混淆
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.menuClock().isMenuClock(hand)) {
            return;
        }
        // 吃掉这次交互：右键方块不会真的放东西/交互
        event.setCancelled(true);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[menu-clock] " + player.getName() + " 右键时钟 → 打开玩家菜单");
        }
        plugin.playerMenu().open(player);
    }

    /** 时钟丢不掉：误丢之后玩家就没有菜单入口了。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.menuClock().isMenuClock(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(MINI.deserialize("<gray>菜单时钟不能丢弃（右键可以打开菜单）。"));
    }
}
