package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对局记分板（侧边栏）：实时显示双方比分与自己的得分。
 *
 * <p>每个玩家一份独立记分板 —— 因为"你的得分"是因人而异的。
 * 3v3 只有 6 人，这点开销可以忽略；玩家离线或对局结束时恢复主记分板。</p>
 */
public final class MatchScoreboard {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * 侧边栏行只保留固定槽位语义：每行的排序分恒为固定值（见 {@link #update}），
     * 数据写进行文本。旧实现把"比分 / 百分比 / 波次"本身当排序分，
     * 数值相对大小一变，行序就上下跳动，玩家读不到固定位置。
     */

    /** 所属房间：比分/阶段/波次/据点全部按房间取。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Objective> objectives = new HashMap<>();

    public MatchScoreboard(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    public void showTo(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective("taketori", Criteria.DUMMY,
                    MINI.deserialize("<gold><bold>月影合战 3v3</bold>"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            boards.put(player.getUniqueId(), board);
            objectives.put(player.getUniqueId(), objective);
        }
        player.setScoreboard(board);
        update(player);
    }

    public void hide(Player player) {
        if (player == null) {
            return;
        }
        boards.remove(player.getUniqueId());
        objectives.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 刷新所有在线玩家的记分板。 */
    public void updateAll() {
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                boards.remove(uuid);
                objectives.remove(uuid);
                continue;
            }
            update(player);
        }
    }

    public void clearAll() {
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        objectives.clear();
    }

    private void update(Player player) {
        Objective objective = objectives.get(player.getUniqueId());
        if (objective == null) {
            return;
        }
        var match = room;
        long minutes = (match.remainingMillis() + 59_999L) / 60_000L;

        // 清掉旧条目，避免残留旧行
        for (String entry : objective.getScoreboard().getEntries()) {
            objective.getScoreboard().resetScores(entry);
        }

        // 按显示顺序自上而下组装行文本；排序分 = 行数 - 位置（固定，不随数据变化）
        List<String> lines = new ArrayList<>();
        if (match.isSengoku()) {
            // 战国 3v3：小局比分是这一模式的核心信息，放在侧栏而不是再开一条 BossBar
            // （冷却条与复活倒计时已经各占一条，再加会三条堆叠）。
            var session = room.sengoku();
            objective.displayName(MINI.deserialize("<gold><bold>战国 3v3</bold> <gray>三局两胜"));
            lines.add("第 " + Math.max(1, session.currentRound()) + " 小局");
            lines.add("小局比分: " + session.display());
            lines.add("红队箭楼: " + room.towers().countOf(TeamId.RED));
            lines.add("蓝队箭楼: " + room.towers().countOf(TeamId.BLUE));
            lines.add("你的能量: " + room.energy().energyOf(player)
                    + "/" + plugin.config().energyRules().safeMax());
            long left = session.remainingSeconds();
            lines.add(left < 0L ? "不限时" : "本局剩余: " + (left / 60L) + " 分 " + (left % 60L) + " 秒");
        } else if (match.isPve()) {
            objective.displayName(MINI.deserialize("<gold><bold>月影合战 PVE</bold> <gray>目标 "
                    + match.rules().scoreToWin()));
            lines.add("总分: " + match.teamScore(TeamId.RED));
            OutpostManager outpost = room.outpost();
            if (outpost.isActive()) {
                lines.add("据点: " + Math.round(outpost.healthRatio() * 100.0D) + "%");
            }
            lines.add("你的得分: " + match.playerScore(player.getUniqueId()));
            lines.add("全队击杀: " + match.teamTotalKills(TeamId.RED));
            PveSettings pve = plugin.pveSettings();
            if (pve.bigWavesEnabled() && pve.bigWaveCount() > 0) {
                lines.add("大波次: " + room.minions().bigWave() + "/" + pve.bigWaveCount());
            }
            lines.add("波次: " + room.minions().wave());
            lines.add("剩余: " + minutes + " 分");
        } else {
            objective.displayName(MINI.deserialize("<gold><bold>月影合战 3v3</bold>"));
            // 攻防目标分不同，所以两行都带上各自的目标：只写「红队: 120」看不出还差多少
            lines.add("红队: " + match.teamScore(TeamId.RED) + "/" + match.scoreTargetOf(TeamId.RED));
            lines.add("蓝队: " + match.teamScore(TeamId.BLUE) + "/" + match.scoreTargetOf(TeamId.BLUE));
            BombObjective bomb = room.bomb();
            if (bomb != null) {
                // 相位决定「包点」这行显示什么：没激活报状态、激活中报读条、超时报已守住，
                // 否则三种情况都显示「0s/10s」，玩家根本不知道炸弹现在是什么状态。
                switch (match.bombPhase()) {
                    case FREE_TIME -> lines.add("包点: 未激活");
                    case DEFUSE -> lines.add("包点: " + bomb.progressText());
                    case BASE_FIGHT -> lines.add("包点: 蓝队守住");
                }
            }
            if (match.isOvertimeActive()) {
                lines.add("加时人头: 红 " + match.overtimeKillsOf(TeamId.RED)
                        + " : 蓝 " + match.overtimeKillsOf(TeamId.BLUE));
            }
            lines.add("你的得分: " + match.playerScore(player.getUniqueId()));
            lines.add("本局击杀: " + match.totalKillsOf(player.getUniqueId()));
            lines.add("波次: " + room.minions().wave());
            lines.add("剩余: " + minutes + " 分");
        }

        // 投降表决：只给发起那一队的成员显示（三种模式共用，故放在分支之后统一追加）
        String surrender = surrenderLine(player);
        if (surrender != null) {
            lines.add(surrender);
        }

        for (int i = 0; i < lines.size(); i++) {
            objective.getScore(uniqueEntry(lines, i)).setScore(lines.size() - i);
        }
    }

    /**
     * 本队投降表决行：只有<b>发起投票那一队</b>的成员看得见，其他队与旁观者不显示。
     *
     * <p>放在侧栏而不是聊天刷屏，是因为表决有 30 秒窗口，玩家需要随时看到
     * 「还差几票 / 还剩几秒」才能判断要不要跟票。返回 null 表示当前没有需要显示的行。</p>
     */
    private String surrenderLine(Player player) {
        TeamId team = room.teamOf(player.getUniqueId());
        if (team == null) {
            return null;
        }
        GameRoom.SurrenderStatus status = room.surrenderStatus(team);
        if (status == null) {
            return null;
        }
        return "投降表决: " + status.confirmed() + "/" + status.need()
                + " (剩 " + status.remainingSeconds() + "s)";
    }

    /**
     * 记分板条目必须互不相同：若两行文本恰好一致，给后出现的行追加不可见的
     * 原版颜色码后缀（§x），既不改变视觉显示，又保证条目唯一。
     */
    private static String uniqueEntry(List<String> lines, int index) {
        String line = lines.get(index);
        for (int i = 0; i < index; i++) {
            if (lines.get(i).equals(line)) {
                return line + org.bukkit.ChatColor.RESET.toString()
                        + org.bukkit.ChatColor.values()[index % 16];
            }
        }
        return line;
    }

    /** 基地占点进度等临时提示走 ActionBar，不占用记分板。 */
    public void actionBar(Player player, String miniMessage) {
        if (player != null && player.isOnline()) {
            player.sendActionBar(MINI.deserialize(miniMessage));
        }
    }
}
