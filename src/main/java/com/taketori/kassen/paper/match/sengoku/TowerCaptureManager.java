package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.TowerContest;
import com.taketori.kassen.core.match.sengoku.TowerRules;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.skill.SkillManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 箭楼占领读条：敲钟激活 → 在占领区内累积进度 → 读满易主。
 *
 * <p><b>与 {@code BaseCaptureManager} 最本质的区别</b>：那边是单向的（只有敌方能在区域内推进，
 * 己方基地不存在"争夺"），这里是<b>双向争夺</b>——双方都能推进，且按需求第 9 条，
 * <b>双方同时在场时双方进度都停滞</b>。所以进度模型不能照搬，只有读条 / 播报 / 衰减
 * 这些外围写法是共通的。</p>
 *
 * <p>敲钟是<b>必要前置</b>：没敲过钟的箭楼不累积任何进度。占领成功后该标记会被重置，
 * 下一次争夺（无论谁来）都要重新敲钟。</p>
 */
public final class TowerCaptureManager {

    /** 一座箭楼的占领读条状态。 */
    private static final class Channel {
        /** 已经被敲钟激活（未激活时完全不累积进度）。 */
        private boolean armed;
        /** 双方各自的已读秒数。 */
        private final Map<TeamId, Double> progress = new EnumMap<>(TeamId.class);
        /** 上一次给在场玩家发提示的时刻（毫秒），用于节流。 */
        private long lastNoticeAt;
    }

    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private final Map<Integer, Channel> channels = new LinkedHashMap<>();
    private BukkitTask task;

    public TowerCaptureManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private TowerRules rules() {
        return plugin.config().towerRules();
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        stop();
        int count = rules().safeCount();
        for (int index = 1; index <= count; index++) {
            channels.put(index, new Channel());
        }
        task = plugin.scheduler().runTimerTask(this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        channels.clear();
    }

    // ---------------------------------------------------------------- 敲钟

    /**
     * 某队敲响了某座箭楼的铜钟。
     *
     * @return 失败原因；成功（或已经在读条中）返回 null
     */
    public String ringBell(int index, TeamId team) {
        Channel channel = channels.get(index);
        if (channel == null) {
            return "这座箭楼不存在";
        }
        // 守卫检查必须排在"已经敲过钟"之前：否则守卫重刷后再敲钟会被当成成功，
        // 玩家会以为可以占领，实际上 tick 会把它清掉。
        if (room.towers().hasGuards(index)) {
            return plugin.config().messages().plain("sengoku.tower-guards-alive",
                    "count", room.towers().guardCount(index));
        }
        if (channel.armed) {
            return null;   // 已经在读条，重复敲钟不算错
        }
        if (room.towers().ownerOf(index) == team) {
            return plugin.config().messages().plain("sengoku.tower-already-yours");
        }
        channel.armed = true;
        if (rules().effectiveCaptureSeconds() <= 0.0D) {
            // 瞬时模式：敲钟那一下就定归属。不放到 tick 里做，因为那时玩家可能已经走开，
            // 「谁敲的钟」就无从判断了（需求允许把交互配成瞬时触发）。
            channel.armed = false;
            room.towers().capture(index, team);
            return null;
        }
        room.broadcast(plugin.config().messages().plain("sengoku.tower-bell-rung",
                "team", team.display(), "tower", "箭楼 #" + index));
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 箭楼 #" + index + " 被 " + team.key() + " 敲钟激活");
        }
        return null;
    }

    /** 某座箭楼是否已被敲钟激活。 */
    public boolean isArmed(int index) {
        Channel channel = channels.get(index);
        return channel != null && channel.armed;
    }

    /** 某队在某座箭楼上的已读秒数（调试 / 进度条用）。 */
    public double progressOf(int index, TeamId team) {
        Channel channel = channels.get(index);
        return channel == null ? 0.0D : channel.progress.getOrDefault(team, 0.0D);
    }

    // ---------------------------------------------------------------- tick

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        TowerRules towerRules = rules();
        double need = towerRules.effectiveCaptureSeconds();
        if (need <= 0.0D) {
            return;   // 瞬时模式的结算在 ringBell 里，tick 不需要参与
        }
        for (Map.Entry<Integer, Channel> entry : channels.entrySet()) {
            int index = entry.getKey();
            Channel channel = entry.getValue();
            if (!channel.armed) {
                continue;
            }
            // 守卫重刷了 → 占领窗口关闭。没有这一步的话，红队清完守卫敲钟走开、
            // 20 秒后守卫刷回来，蓝队可以直接走进来读满——「先清守卫」这条规则被绕过，
            // 而且"谁敲的钟"与"谁能读条"完全解耦。
            if (room.towers().hasGuards(index)) {
                channel.armed = false;
                channel.progress.clear();
                continue;
            }

            List<Player> red = playersInside(index, TeamId.RED);
            List<Player> blue = playersInside(index, TeamId.BLUE);
            TowerContest contest = new TowerContest(!red.isEmpty(), !blue.isEmpty(),
                    towerRules.contestLock());

            if (contest.isLocked()) {
                // 需求第 9 条：双方同时占领时双方都不推进（进度原样停住，不清零）
                noticeContested(channel, red, blue);
                continue;
            }

            // 进度变化统一走 core 层的纯逻辑，保证这条规则只有一处实现（且可离线测）
            double decay = towerRules.decayPerSecond();
            for (TeamId team : TeamId.values()) {
                channel.progress.put(team, contest.advanceFor(team,
                        channel.progress.getOrDefault(team, 0.0D), need, decay));
            }

            // 进度条提示：互锁关闭时两队可能同时在读条，各队看各自的进度
            for (TeamId team : List.of(TeamId.RED, TeamId.BLUE)) {
                if (!contest.isPresent(team)) {
                    continue;
                }
                double current = channel.progress.getOrDefault(team, 0.0D);
                for (Player player : (team == TeamId.RED ? red : blue)) {
                    String bar = SkillManager.progressBar(current, need);
                    int percent = (int) Math.round(current / need * 100.0D);
                    room.scoreboard().actionBar(player, plugin.config().messages().plain(
                            "sengoku.tower-progress",
                            "tower", "箭楼 #" + index, "bar", bar, "percent", percent));
                }
            }

            // 占领判定按队独立检查：互锁关闭时双方同场各自推进，先读满的一队易主；
            // 同一 tick 双双读满时进度相同，按红队先手结算（枚举顺序）
            TeamId winner = null;
            double winnerProgress = -1.0D;
            for (TeamId team : TeamId.values()) {
                double current = channel.progress.getOrDefault(team, 0.0D);
                if (contest.isComplete(team, current, need) && current > winnerProgress) {
                    winner = team;
                    winnerProgress = current;
                }
            }
            if (winner != null) {
                channel.armed = false;
                channel.progress.clear();
                room.towers().capture(index, winner);
            }
        }
    }

    /** 争夺中的提示（节流：每 2 秒一次，否则每秒刷两条会很吵）。 */
    private void noticeContested(Channel channel, List<Player> red, List<Player> blue) {
        long now = System.currentTimeMillis();
        if (now - channel.lastNoticeAt < 2000L) {
            return;
        }
        channel.lastNoticeAt = now;
        List<Player> both = new ArrayList<>(red);
        both.addAll(blue);
        for (Player player : both) {
            room.scoreboard().actionBar(player, plugin.config().messages().plain("sengoku.tower-contested"));
        }
    }

    /** 某队当前在占领区内的玩家。 */
    private List<Player> playersInside(int index, TeamId team) {
        List<Player> result = new ArrayList<>();
        CuboidRegion region = towerRegion(index);
        if (region == null) {
            return result;
        }
        for (Player player : room.teamPlayers(team)) {
            if (plugin.spectator().isSpectator(player)) {
                continue;
            }
            if (region.contains(player.getLocation())) {
                result.add(player);
            }
        }
        return result;
    }

    private CuboidRegion towerRegion(int index) {
        var arena = room.arena();
        return arena == null ? null : arena.sengoku().tower(index);
    }
}
