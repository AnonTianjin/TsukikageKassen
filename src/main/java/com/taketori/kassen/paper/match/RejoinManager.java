package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 断线重连：对局中（CAGED/PLAYING）掉线的参赛者在时限内重连，直接回到原房原队。
 *
 * <p>参考 BedWars 的 RejoinSession / rejoin-time，并复用本项目已有的掉线兜底：</p>
 * <ul>
 *   <li>断线时沿用既有流程（playerRooms 移除、teams 条目保留、背包转全局暂存），
 *       这里只<b>追加登记</b>一条重连会话；</li>
 *   <li>重连时若会话未过期且房间还在 → 自动恢复：重新绑定房间映射、传送回本队出生点、
 *       还原背包、重新显示记分板并广播回归；</li>
 *   <li>过期 / 房间已回收 → 什么都不做，由 PlayerListener 既有逻辑返还背包；</li>
 *   <li>时限配置 {@code room.rejoin-seconds}（默认 300；0 = 关闭重连）。</li>
 * </ul>
 */
public final class RejoinManager {

    /** 一条重连会话。 */
    public record RejoinSession(String roomId, TeamId team, long expiresAtMillis) {
    }

    private final TaketoriPlugin plugin;
    /** 玩家 → 重连会话（主线程读写；ConcurrentHashMap 防御异步清理路径）。 */
    private final Map<UUID, RejoinSession> sessions = new ConcurrentHashMap<>();

    public RejoinManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重连时限（秒）；0 = 关闭。 */
    public int rejoinSeconds() {
        return Math.max(0, plugin.getConfig().getInt("room.rejoin-seconds", 300));
    }

    /**
     * 断线时登记重连会话（只对 CAGED/PLAYING 的参赛者有意义）。
     * 房间不存在 / 玩家不在队伍里 / 时限为 0 时静默跳过。
     */
    public void register(UUID playerId, GameRoom room, TeamId team) {
        if (playerId == null || room == null || team == null) {
            return;
        }
        int seconds = rejoinSeconds();
        if (seconds <= 0) {
            return;
        }
        sessions.put(playerId, new RejoinSession(room.id(), team,
                System.currentTimeMillis() + seconds * 1000L));
    }

    /**
     * 玩家上线 / 执行 rejoin：尝试恢复到原房原队。
     *
     * @return true = 已恢复（调用方跳过其余加入流程）；false = 无可用会话
     *         （过期会话已清理，由既有 restoreOfflineBackup 兜底返还背包）
     */
    public boolean tryRejoin(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID id = player.getUniqueId();
        RejoinSession session = sessions.get(id);
        if (session == null) {
            return false;
        }
        sessions.remove(id);
        if (session.expiresAtMillis() < System.currentTimeMillis()) {
            player.sendMessage(plugin.config().messages().get("rejoin.expired"));
            return false;
        }
        GameRoom room = plugin.rooms().room(session.roomId());
        if (room == null || !session.team().equals(room.teamOf(id)) || !room.isRunning()) {
            player.sendMessage(plugin.config().messages().get("rejoin.gone"));
            // 释放掉线时占住的队伍条目（quitMatchKeepEntry 留下的），让槽位回归补位池
            if (room != null) {
                room.quitMatch(id);
            }
            return false;
        }
        // 恢复房间映射与现场：队伍条目断线时本来就保留，这里补回映射与传送
        plugin.rooms().attachAfterRejoin(player, room);
        ArenaDef.Point spawnPoint = room.arena().spawn(session.team());
        if (spawnPoint != null) {
            player.teleport(spawnPoint.toLocation(room.world()));
        }
        // 两条重连来源的背包状态不同，必须先还原、再重新封存，否则不是裸装就是丢物品：
        //  ① 掉线重连：开局前快照在房间 backups（已由 stashOfflineBackup 转到全局暂存），
        //     先还原出来，让玩家手里重新拿回"自带物品"；
        //  ② 主动离局后 rejoin：leaveMatch 已经把自带背包还给他、快照已消费，
        //     这里两个还原调用都是空操作，他手里就是自带物品。
        // 归一之后执行与 beginMatch / joinAsReinforcement 相同的动作序列：
        // 重新封存自带物品 → 按角色补发对局装备 → 补发功能壳。
        room.restoreInventoryIfAny(player);
        plugin.rooms().restoreOfflineBackup(player);
        // 封存自带物品（此时 backups 已被上面消费，会拍下新的快照，结算时照常返还）
        room.stashAndClearInventory(player);
        String charId = room.characterIdOf(id);
        if (charId != null) {
            plugin.giveCharacterWeapons(player, charId);
        }
        // 壳最后发：固定落在快捷栏第 9 格，不被武器发放覆盖
        plugin.roomShell().give(player);
        room.scoreboard().showTo(player);
        room.broadcastMessage("rejoin.back", "player", player.getName(),
                "room", room.display());
        player.sendMessage(plugin.config().messages().get("rejoin.welcome",
                "room", room.display()));
        return true;
    }

    /** 房间销毁时清掉指向它的全部会话（避免悬挂 roomId）。 */
    public void forgetRoom(String roomId) {
        if (roomId == null) {
            return;
        }
        Iterator<Map.Entry<UUID, RejoinSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (roomId.equals(iterator.next().getValue().roomId())) {
                iterator.remove();
            }
        }
    }

    /** 由房间 tick 顺带清理过期会话（防长期累积），并释放掉线时占住的队伍条目。 */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, RejoinSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RejoinSession> entry = iterator.next();
            if (entry.getValue().expiresAtMillis() >= now) {
                continue;
            }
            iterator.remove();
            // 掉线玩家没在窗口内回来：释放 quitMatchKeepEntry 留下的队伍条目，槽位回归补位池
            GameRoom room = plugin.rooms().room(entry.getValue().roomId());
            if (room == null) {
                continue;
            }
            room.quitMatch(entry.getKey());
            // 兜底：队伍条目一撤，结算的 members 就再也不含他，房间备份里若还留着
            // 他的快照就没人会消费。转进全局暂存，等他下次进服由
            // PlayerListener.onJoin → restoreOfflineBackup 取回。
            // 注意不能用单参 stashOfflineBackup(UUID)——它依赖 playerRooms 映射，
            // 而离局/掉线路径早已把它移除，会直接空转。
            GameRoom.InventorySnapshot backup = room.extractBackup(entry.getKey());
            if (backup != null) {
                plugin.rooms().stashOfflineBackup(entry.getKey(), backup);
            }
        }
    }

    /** 插件卸载时清空。 */
    public void clearAll() {
        sessions.clear();
    }

    /** 该玩家是否有一条未过期的重连会话（"已离局占位"的判定依据）。 */
    public boolean hasSession(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        RejoinSession session = sessions.get(playerId);
        return session != null && session.expiresAtMillis() >= System.currentTimeMillis();
    }

    /** 调试用：当前会话数。 */
    public int size() {
        return sessions.size();
    }
}
