package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旁观状态管理：两种旁观。
 *
 * <ul>
 *   <li><b>死亡旁观</b>：对局中死亡后立刻切成旁观（而不是停在死亡界面），
 *       在复活倒计时结束前可以继续看战况，时间到了自动恢复生存并传送到己方出生点。</li>
 *   <li><b>观众</b>：不参赛的玩家以旁观身份观看整局，不参与计分、不刷怪不掉落。
 *       进入时会在聊天栏给出<b>可点击的「退出观战」按钮</b>，并每隔一段时间重发一次，
 *       所以玩家不需要记指令也能自己退出来。</li>
 * </ul>
 *
 * <p>离开旁观时统一恢复为生存模式；插件卸载或对局结束会清空所有旁观状态。</p>
 */
public final class SpectatorManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 聊天栏里的退出按钮：点一下就等于执行 /taketori leave。 */
    private static final String EXIT_BUTTON = "<click:run_command:'/taketori leave'>"
            + "<hover:show_text:'<gray>点击退出观战，回到大厅'>"
            + "<yellow><bold>[ 退出观战 ]</bold></yellow></hover></click>";

    /** 观众提示重发间隔（毫秒）：聊天栏刷过去之后还能再看到按钮。 */
    private static final long REMINDER_MILLIS = 45_000L;

    private final TaketoriPlugin plugin;
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    /** 主动进入的观众（区别于死亡旁观：只有他们需要"退出观战"按钮）。 */
    private final Set<UUID> audience = ConcurrentHashMap.newKeySet();
    /** 观众正在旁观的房间（房间列表按房间进入时登记；房间结算时据此只清本房观众）。 */
    private final Map<UUID, GameRoom> audienceRooms = new ConcurrentHashMap<>();
    /** 进观众前的位置：没有配置大厅出生点时用来回退。 */
    private final Map<UUID, Location> returnPoints = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextReminderAt = new ConcurrentHashMap<>();
    /** 死亡复活倒计时 BossBar（每人一条；复活 / 退出 / 停服时撤除）。 */
    private final Map<UUID, BossBar> respawnBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> respawnBarTasks = new ConcurrentHashMap<>();

    public SpectatorManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSpectator(Player player) {
        return player != null && spectators.contains(player.getUniqueId());
    }

    public boolean isSpectator(UUID uuid) {
        return uuid != null && spectators.contains(uuid);
    }

    /** 是否是"主动进入的观众"（死亡旁观不算）。 */
    public boolean isAudience(Player player) {
        return player != null && audience.contains(player.getUniqueId());
    }

    public Set<UUID> spectators() {
        return Set.copyOf(spectators);
    }

    public int audienceCount() {
        return audience.size();
    }

    /** 死亡旁观：切换到旁观模式并看向战场（可选指定观察点）。不会给退出按钮。 */
    public void enterTemporary(Player player, Location viewAt) {
        if (player == null || !player.isOnline()) {
            return;
        }
        spectators.add(player.getUniqueId());
        audience.remove(player.getUniqueId());
        nextReminderAt.remove(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        if (viewAt != null) {
            player.teleport(viewAt);
        }
        player.sendActionBar(MINI.deserialize("<gray>你已阵亡，正在旁观…"));
    }

    /**
     * 死亡复活倒计时 BossBar：屏幕上方进度条显示"复活倒计时 N 秒"，每秒刷新剩余秒数与进度，
     * 归零自动撤除。玩家提前离开旁观状态（复活 / 结算收尾 / 退服 / 停服）时由
     * {@link #leave}、{@link #forgetQuietly}、{@link #clearAll} 统一撤除。
     *
     * @param seconds 复活等待秒数（取房间规则 respawn-delay-seconds）
     */
    public void startRespawnCountdown(Player player, int seconds) {
        if (player == null || !player.isOnline() || seconds <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        cancelRespawnBar(uuid);
        BossBar bar = BossBar.bossBar(
                MINI.deserialize("<gold>复活倒计时 <white>" + seconds + "</white> 秒"),
                1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);
        respawnBars.put(uuid, bar);
        int[] remaining = {seconds};
        respawnBarTasks.put(uuid, plugin.scheduler().runTimerTask(() -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                cancelRespawnBar(uuid);
                return;
            }
            bar.name(MINI.deserialize("<gold>复活倒计时 <white>" + remaining[0] + "</white> 秒"));
            bar.progress(Math.max(0.0F, (float) remaining[0] / seconds));
        }, 20L, 20L));
    }

    /** 撤除某玩家的复活倒计时 BossBar（周期任务与进度条一起清，幂等）。 */
    private void cancelRespawnBar(UUID uuid) {
        BukkitTask task = respawnBarTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        BossBar bar = respawnBars.remove(uuid);
        if (bar != null) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.hideBossBar(bar);
            }
        }
    }

    /**
     * 观众：指定观察点与所属房间（房间列表入口用）。房间用于结算时的作用域清理与提醒归属；
     * 已经是观众时只换视角/换房间，不覆盖最初的返回点。viewAt 为 null 时不传送。
     */
    public void enterAudience(Player player, Location viewAt, GameRoom room) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!spectators.contains(player.getUniqueId())) {
            returnPoints.put(player.getUniqueId(), player.getLocation().clone());
        }
        spectators.add(player.getUniqueId());
        audience.add(player.getUniqueId());
        if (room != null) {
            audienceRooms.put(player.getUniqueId(), room);
        }
        player.setGameMode(GameMode.SPECTATOR);
        if (viewAt != null) {
            player.teleport(viewAt);
        }
        nextReminderAt.put(player.getUniqueId(), System.currentTimeMillis() + REMINDER_MILLIS);
        sendAudienceHint(player, "<gray>已进入观众视角。");
    }

    /** 该观众正在旁观的房间；未指定房间（旧入口/默认观战点）返回 null。 */
    public GameRoom audienceRoom(UUID uuid) {
        return uuid == null ? null : audienceRooms.get(uuid);
    }

    /**
     * 房间结算 / 中止时：把<b>正在旁观该房间</b>的观众退回大厅（不影响其他房间的观众）。
     * 在线的走与手动退出相同的回大厅流程，离线的静默清理。
     */
    public void clearAudienceOfRoom(GameRoom room) {
        if (room == null) {
            return;
        }
        for (UUID uuid : Set.copyOf(audienceRooms.keySet())) {
            if (audienceRooms.get(uuid) != room) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                leaveAudience(player);
            } else {
                forgetQuietly(uuid);
            }
        }
    }

    /** 每秒调用：给观战中的玩家定期重发一次退出按钮提示。 */
    public void tickReminders() {
        if (audience.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID uuid : audience) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                audience.remove(uuid);
                nextReminderAt.remove(uuid);
                continue;
            }
            Long next = nextReminderAt.get(uuid);
            if (next != null && now < next) {
                continue;
            }
            nextReminderAt.put(uuid, now + REMINDER_MILLIS);
            sendAudienceHint(player, "<gray>你正在观战。");
        }
    }

    /**
     * 退出观众：恢复生存并回到大厅（没有大厅就回到进观众前的位置）。
     *
     * @return true 表示原本确实是观众（已处理）；false 表示他不是观众，调用方自行决定后续
     */
    public boolean leaveAudience(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!audience.remove(uuid)) {
            return false;
        }
        spectators.remove(uuid);
        audienceRooms.remove(uuid);
        nextReminderAt.remove(uuid);
        // 只把"因观战被切成旁观"的玩家改回生存：管理员自带创造模式退观战不应被降级
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        Location target = plugin.lobby().spawn();
        Location fallback = returnPoints.remove(uuid);
        if (target == null) {
            target = fallback;
        }
        if (target != null) {
            player.teleport(target);
        }
        player.setFallDistance(0.0F);
        plugin.lobby().clearTransient(player);
        player.sendMessage(MINI.deserialize("<green>已退出观战，回到大厅。"));
        return true;
    }

    /**
     * 玩家离线时静默清掉旁观登记：不改游戏模式、不传送（人已下线）。
     * 避免死亡旁观中途退出后，下次进服残留 isSpectator 状态影响计分等判定。
     */
    public void forgetQuietly(UUID uuid) {
        if (uuid == null) {
            return;
        }
        cancelRespawnBar(uuid);
        spectators.remove(uuid);
        audience.remove(uuid);
        audienceRooms.remove(uuid);
        nextReminderAt.remove(uuid);
        returnPoints.remove(uuid);
    }

    /** 结束旁观：恢复生存模式（可选传送到指定位置）。死亡复活的正常路径。 */
    public void leave(Player player, Location destination) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        cancelRespawnBar(uuid);
        spectators.remove(uuid);
        audience.remove(uuid);
        audienceRooms.remove(uuid);
        nextReminderAt.remove(uuid);
        returnPoints.remove(uuid);
        player.setGameMode(GameMode.SURVIVAL);
        if (destination != null) {
            player.teleport(destination);
        }
        player.setFallDistance(0.0F);
    }

    /** 对局结束时把所有人拉回正常状态。 */
    public void clearAll() {
        for (UUID uuid : Set.copyOf(respawnBars.keySet())) {
            cancelRespawnBar(uuid);
        }
        for (UUID uuid : Set.copyOf(spectators)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            spectators.remove(uuid);
        }
        audience.clear();
        audienceRooms.clear();
        returnPoints.clear();
        nextReminderAt.clear();
    }

    private void sendAudienceHint(Player player, String prefix) {
        player.sendMessage(MINI.deserialize(prefix + " <gray>想退出就点 " + EXIT_BUTTON
                + " <dark_gray>（也可以输入 /taketori leave）"));
    }
}
