package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.editor.EditorHolder;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 武器编辑 GUI 的事件入口：点击、拖拽、聊天栏输入。
 *
 * <p>聊天事件是异步的，而打开菜单/写文件必须在主线程，所以这里只负责把文本取出来，
 * 真正处理交给 {@code runSync}（避免"异步线程碰背包"这类偶发崩溃）。</p>
 */
public final class WeaponEditorListener implements Listener {

    private final TaketoriPlugin plugin;

    public WeaponEditorListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) {
            return;   // 不是编辑器菜单（点自己背包时不拦）
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            // 纵深防御：入口 /taketori editor 已 admin-gated，这里与 TagMenu/AdminMenu 一致再查一道
            if (!player.hasPermission("taketori.admin")) {
                player.closeInventory();
                return;
            }
            holder.click(player, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    /** 编辑器在等输入时，把这条聊天消息吃掉当作"新值"。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.editor().hasPending(player)) {
            return;
        }
        event.setCancelled(true);
        String text = plainText(event.message());
        plugin.scheduler().runSync(() -> plugin.editor().handleChat(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.editor().forget(event.getPlayer());
    }

    /** 取纯文本：不依赖 serializer-plain（离线构建的 classpath 里没有它）。 */
    private String plainText(Component component) {
        StringBuilder builder = new StringBuilder();
        if (component instanceof TextComponent text) {
            builder.append(text.content());
        }
        for (Component child : component.children()) {
            builder.append(plainText(child));
        }
        return builder.toString();
    }
}
