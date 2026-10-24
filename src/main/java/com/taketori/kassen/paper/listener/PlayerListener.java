package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.worlds.WorldScope;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家生命周期：恢复角色绑定，退出时清理状态。
 *
 * <p>清理很重要——冷却表、防御窗口、内部伤害标记如果随玩家退出而不释放，
 * 长时间运行的服务器会慢性泄漏。</p>
 */
public final class PlayerListener implements Listener {

    private final TaketoriPlugin plugin;

    public PlayerListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 隐性标签：不论有没有角色都恢复（只影响同队角色冲突时的优先级）
        plugin.config().characters().profile(player.getUniqueId())
                .setTag(plugin.dataStore().tagOf(player.getUniqueId()));
        String characterId = plugin.dataStore().characterIdOf(player.getUniqueId());
        if (characterId != null && plugin.config().characters().has(characterId)) {
            plugin.config().characters().bind(player.getUniqueId(), characterId);
        }
        // 断线重连：对局中掉线者在时限内自动回到原房原队（含背包快照还原/传送/记分板）。
        // 成功则跳过其余加入流程——不送大厅、不自动发武器（背包已由快照还原，重复发放会刷物品）。
        if (plugin.rejoin().tryRejoin(player)) {
            return;
        }
        // 进服补装备：只有「上线时正好在一局进行中的对局里」才发（正常进服已由重连分支或
        // 开局流程负责）。大厅与等待区只记角色，武器留到开局统一发。
        if (characterId != null && plugin.config().characters().has(characterId)
                && plugin.config().autoGiveOnJoin() && plugin.inRunningMatch(player)) {
            plugin.scheduler().runLater(() -> plugin.giveCharacterWeapons(player, characterId), 20L);
        }
        // 进服送到大厅：只在"接管白名单"内的世界才做。
        // 多世界服务器上默认只接管大厅出生点所在的世界，其它世界（别的玩法世界、资源世界）
        // 的出生点完全不碰 —— 过去是无条件把所有人拽到大厅，等于抢了所有世界的出生点。
        if (plugin.config().lobbyTeleportOnJoin() && plugin.lobby().isConfigured()) {
            Location target = plugin.lobby().spawn();
            String lobbyWorld = target == null || target.getWorld() == null ? null : target.getWorld().getName();
            String playerWorld = player.getWorld().getName();
            if (WorldScope.allowsTakeover(plugin.config().lobbyTakeoverWorlds(), lobbyWorld, playerWorld)) {
                plugin.scheduler().runLater(() -> plugin.lobby().sendToLobby(player), 10L);
            } else if (plugin.config().debug()) {
                plugin.getLogger().info("[lobby] " + player.getName() + " 从世界 " + playerWorld
                        + " 进服 → 不在接管范围内（" + WorldScope.describeTakeover(
                        plugin.config().lobbyTakeoverWorlds(), lobbyWorld) + "），保持原位");
            }
        }
        // 菜单时钟：右键打开玩家菜单（可在 config.yml 的 menu-clock 段关闭 / 换材质）
        if (plugin.config().menuClockEnabled() && plugin.config().menuClockGiveOnJoin()) {
            plugin.scheduler().runLater(() -> plugin.menuClock().giveOnJoin(player), 20L);
        }
        // 有管理权限的玩家自动发一把选区锄（可在 config.yml 的 setup-wand.give-on-join 关闭）
        if (plugin.config().setupWandGiveOnJoin() && player.hasPermission("taketori.admin")) {
            plugin.scheduler().runLater(() -> plugin.setupWand().wand().give(player), 30L);
        }
        // 重连返还：若上一次在对局中断线，把开局前备份的背包还回去
        plugin.rooms().restoreOfflineBackup(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 对局中断线：把该房间为其备份的原背包转到全局暂存，等重连再还
        plugin.rooms().stashOfflineBackup(player.getUniqueId());
        // 派对清理：成员退出移除，房主退出转让给最早成员（无成员才解散）
        plugin.party().onQuit(player.getUniqueId());
        // 大厅药水保留登记在退出时清掉，避免 UUID 条目无限累积
        plugin.lobby().forget(player.getUniqueId());
        plugin.forgetPlayer(player);
    }
}
