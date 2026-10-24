package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.setup.SetupWandService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 选区锄的事件入口。
 *
 * <p><b>为什么同时接 BlockBreakEvent 和 PlayerInteractEvent</b>：左键点方块在创造模式走
 * {@code PlayerInteractEvent}，在生存/冒险模式走 {@code BlockBreakEvent}（同时还会真的把方块挖掉）。
 * 只接一个的话，管理员换个游戏模式就会发现"左键没反应"——这正是这类工具最常见的坑。</p>
 *
 * <p>所有相关事件都会被取消：既不挖方块、也不锄地、也不触发按钮等交互，
 * 避免划场地时把地图改坏。</p>
 */
public final class SetupWandListener implements Listener {

    private final TaketoriPlugin plugin;

    public SetupWandListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    private SetupWandService service() {
        return plugin.setupWand();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        SetupWandService service = service();
        if (service == null || !service.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!service.wand().holdingAny(player)) {
            return;
        }
        // 普通玩家捡到/被塞了一把选区锄时，不能因此被禁挖——只拦管理员
        if (!allowed(player)) {
            return;
        }
        event.setCancelled(true);   // 拿着选区锄不许破坏方块
        if (player.isSneaking()) {
            service.clear(player);
        } else {
            service.setFirst(player, event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        SetupWandService service = service();
        if (service == null || !service.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!service.wand().holdingAny(player)) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // 只认主手，避免同一次点击被处理两遍
        }
        event.setCancelled(true);
        if (!allowed(player)) {
            return;
        }
        boolean sneaking = player.isSneaking();
        Block block = event.getClickedBlock();
        switch (action) {
            case LEFT_CLICK_BLOCK -> {
                if (sneaking) {
                    service.clear(player);
                } else if (block != null) {
                    service.setFirst(player, block.getLocation());
                }
            }
            case RIGHT_CLICK_BLOCK -> {
                if (sneaking) {
                    service.describe(player);
                } else if (block != null) {
                    service.setSecond(player, block.getLocation());
                }
            }
            case LEFT_CLICK_AIR -> {
                if (sneaking) {
                    service.clear(player);
                } else {
                    service.setFirst(player, player.getLocation());
                }
            }
            case RIGHT_CLICK_AIR -> {
                if (sneaking) {
                    service.describe(player);
                } else {
                    service.setSecond(player, player.getLocation());
                }
            }
            default -> {
            }
        }
    }

    /** 权限提示用 actionBar，避免左键连点刷屏。 */
    private boolean allowed(Player player) {
        if (player.hasPermission("taketori.admin")) {
            return true;
        }
        player.sendActionBar(MiniMessage.miniMessage().deserialize("<red>选区锄需要 taketori.admin 权限"));
        return false;
    }
}
