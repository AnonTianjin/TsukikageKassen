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
 *   <li>断线 / 换服时沿用既有流程（playerRooms 移除、teams 条目保留、背包转全局暂存），
 *       这里只<b>追加登记</b>一条重连会话；Bukkit 端无法区分「切到别的服」与「掉线」，
 *       两条路径共用同一套封存与恢复逻辑；</li>
 *   <li>重连时若会话未过期且房间还在 → 自动恢复：重新绑定房间映射、传送回本队出生点、
 *       还原背包、重新显示记分板并广播回归；</li>
 *   <li>会话过期但房间还在 → 槽位已释放，<b>原队伍未满员</b>才允许重新加入，满员则拒绝；</li>
 *   <li>房间已回收 → 什么都不做，由 PlayerListener 既有逻辑返还背包；</li>
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
    /**
     * 已过期、但房间还在的会话：不再占位（{@link #hasSession(UUID)} 为 false），只作为
     * 「他原本在哪个房、哪一队」的线索。玩家超时回来时靠它判断原队伍还有没有空位，
     * 有就重新加入、没有就拒绝；房间回收时随 {@link #forgetRoom(String)} 一并清掉。
     */
    private final Map<UUID, RejoinSession> expired = new ConcurrentHashMap<>();

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
     * <p>两条来源分开处理：</p>
     * <ul>
     *   <li>窗口内回来（活跃会话）：队伍条目还占着，直接回原队；</li>
     *   <li>窗口过了才回来（过期会话）：槽位已被释放，<b>原队伍没满员</b>就重新占回去，
     *       满员则拒绝并提示（背包由 {@code restoreOfflineBackup} 照常返还）。</li>
     * </ul>
     *
     * @return true = 已恢复（调用方跳过其余加入流程）；false = 无可用会话 / 房间已结束 /
     *         原队伍已满员
     */
    public boolean tryRejoin(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID id = player.getUniqueId();
        // 已经在某个房间里（手动 /taketori rejoin 时可能发生）：不把人从别的房间拽走
        if (plugin.rooms().roomOf(id) != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        RejoinSession active = sessions.get(id);
        if (active != null && active.expiresAtMillis() < now) {
            // 会话刚好过期：转为"仅保留原队伍线索"的过期会话，继续尝试重新加入
            sessions.remove(id);
            expired.put(id, active);
            active = null;
        }
        RejoinSession stale = expired.get(id);
        RejoinSession ref = active != null ? active : stale;
        if (ref == null) {
            return false;
        }
        sessions.remove(id);
        expired.remove(id);

        GameRoom room = plugin.rooms().room(ref.roomId());
        TeamId team = ref.team();
        if (room == null || !room.isMatchInProgress()) {
            player.sendMessage(plugin.config().messages().get(
                    active != null ? "rejoin.gone" : "rejoin.expired"));
            // 房间还在但已经结束/回收中：把断线时占住的条目一并释放
            if (room != null) {
                room.quitMatch(id);
            }
            return false;
        }
        boolean holdsSlot = team.equals(room.teamOf(id));
        if (!canRejoinTeam(room, team, holdsSlot)) {
            // 原队伍满员：不能挤回去。占位若还在就释放，让槽位回归补位池
            if (holdsSlot) {
                room.quitMatch(id);
            }
            player.sendMessage(plugin.config().messages().get("rejoin.team-full"));
            return false;
        }
        if (!holdsSlot) {
            // 槽位已被释放（会话过期/被清理），但原队伍还缺人：重新占回原队
            room.join(player, team);
        }
        // 恢复房间映射与现场：队伍条目断线时本来就保留，这里补回映射与传送
        plugin.rooms().attachAfterRejoin(player, room);
        ArenaDef.Point spawnPoint = room.arena().spawn(team);
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

    /**
     * 原队伍还有没有位置：占位还在时算上他自己（{@code members <= cap} 即可），
     * 占位已释放时他要额外占一个（{@code members < cap}）。PVE 不分边，上限取房间总人数。
     */
    private boolean canRejoinTeam(GameRoom room, TeamId team, boolean holdsSlot) {
        if (team == null) {
            return false;
        }
        int cap = room.isPve() ? room.maxPlayers() : room.teamSizeCap();
        int members = room.rosterSize(team);
        return holdsSlot ? members <= cap : members < cap;
    }

    /** 房间销毁时清掉指向它的全部会话（避免悬挂 roomId）。 */
    public void forgetRoom(String roomId) {
        if (roomId == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> roomId.equals(entry.getValue().roomId()));
        expired.entrySet().removeIf(entry -> roomId.equals(entry.getValue().roomId()));
    }

    /**
     * 只清某个房间的过期线索：房间复用进入新一局时调用，避免上一局的"原队伍"记录
     * 把玩家塞进新一局的队伍。活跃会话不动——窗口内的占位仍然有效。
     */
    public void forgetExpired(String roomId) {
        if (roomId == null) {
            return;
        }
        expired.entrySet().removeIf(entry -> roomId.equals(entry.getValue().roomId()));
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
            // 过期不等于作废：留一条线索，玩家之后回来仍能凭原队伍重新加入（未满员时）
            expired.put(entry.getKey(), entry.getValue());
            // 掉线玩家没在窗口内回来：释放 quitMatchKeepEntry 留下的队伍条目，槽位回归补位池
            GameRoom room = plugin.rooms().room(entry.getValue().roomId());
            if (room == null) {
                expired.remove(entry.getKey());
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
        expired.clear();
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
