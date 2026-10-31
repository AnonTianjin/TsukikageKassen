package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.CharacterDef;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * 头顶名牌的 Team 名前缀。Team 名有 16 字符上限，所以用「前缀 + 序号」这种短名。
     */
    private static final String NAME_TAG_TEAM = "kt";

    /** 所属房间：比分/阶段/波次/据点全部按房间取。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Objective> objectives = new HashMap<>();
    /** 上一次刷新名牌时的成员指纹；成员 / 队伍 / 角色 / 观看者都没变就跳过重发。 */
    private String nameTagSignature;

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
        refreshNameTags();
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
        refreshNameTags();
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
        refreshNameTags();
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
        nameTagSignature = null;
    }

    // ---------------------------------------------------------------- 头顶名牌

    /**
     * 刷新头顶名牌：队伍成员按<b>当前所属队伍颜色</b>高亮，并在名字前显示<b>所选角色</b>。
     *
     * <p>名牌装饰由「观看者客户端上那块记分板」里的 Team 决定，所以每个在场玩家
     * 自己的记分板都要各自挂一遍；而 Team 的颜色与前缀是整队共用的、不能逐人设置，
     * 因此按「队伍 + 角色」的组合各建一个 Team（同队同角色共用，最多 2×角色数+2 个）。</p>
     *
     * <p>成员 / 队伍 / 角色 / 观看者都没变化时用指纹短路，不重发数据包。</p>
     */
    public void refreshNameTags() {
        List<Player> viewers = new ArrayList<>();
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                viewers.add(player);
            }
        }
        if (viewers.isEmpty()) {
            nameTagSignature = null;
            return;
        }
        List<Player> members = new ArrayList<>();
        for (UUID uuid : room.teamsView().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                members.add(player);
            }
        }
        // 排序后再算指纹：HashMap 的迭代顺序不保证，排序可以避免无意义的重建
        viewers.sort(Comparator.comparing(Player::getName));
        members.sort(Comparator.comparing(Player::getName));
        String signature = nameTagSignatureOf(viewers, members);
        if (signature.equals(nameTagSignature)) {
            return;
        }
        nameTagSignature = signature;
        for (Player viewer : viewers) {
            applyNameTags(viewer, members);
        }
    }

    private String nameTagSignatureOf(List<Player> viewers, List<Player> members) {
        StringBuilder sb = new StringBuilder();
        for (Player viewer : viewers) {
            sb.append(viewer.getUniqueId()).append(',');
        }
        sb.append('#');
        for (Player member : members) {
            TeamId team = room.teamOf(member.getUniqueId());
            sb.append(member.getUniqueId()).append(':')
                    .append(team == null ? "-" : team.key()).append(':')
                    .append(room.characterIdOf(member.getUniqueId())).append(',');
        }
        return sb.toString();
    }

    /** 把某一观看者的记分板上的名牌 Team 整片重建。 */
    private void applyNameTags(Player viewer, List<Player> members) {
        Scoreboard board = boards.get(viewer.getUniqueId());
        if (board == null) {
            return;
        }
        // 成员会换队 / 换角色，整片摘掉重建最省心（每次刷新只发一次 Team 包）
        for (Team team : new ArrayList<>(board.getTeams())) {
            if (team.getName().startsWith(NAME_TAG_TEAM)) {
                team.unregister();
            }
        }
        Map<String, Team> byKey = new LinkedHashMap<>();
        int index = 0;
        for (Player member : members) {
            TeamId teamId = room.teamOf(member.getUniqueId());
            if (teamId == null) {
                continue;   // 旁观 / 已离队：名牌保持原版
            }
            String characterId = room.characterIdOf(member.getUniqueId());
            String key = teamId.key() + ':' + (characterId == null ? "" : characterId);
            Team tag = byKey.get(key);
            if (tag == null) {
                tag = registerNameTagTeam(board, NAME_TAG_TEAM + index++, teamId, characterId);
                byKey.put(key, tag);
            }
            tag.addEntry(member.getName());
        }
    }

    private Team registerNameTagTeam(Scoreboard board, String name, TeamId teamId, String characterId) {
        NamedTextColor color = teamId == TeamId.RED ? NamedTextColor.RED : NamedTextColor.BLUE;
        Team team = board.registerNewTeam(name);
        // 队色直接给名牌上色；角色名放进前缀，名牌就变成「[角色] 玩家名」
        team.color(color);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        CharacterDef character = characterId == null ? null : plugin.config().characters().get(characterId);
        if (character != null && character.display() != null && !character.display().isBlank()) {
            // display 是 MiniMessage（含渐变 / 颜色标签），必须解析后再拼，
            // 直接当纯文本会把 <gradient:…> 原样显示在名牌上
            team.prefix(Component.text("[", color)
                    .append(MINI.deserialize(character.display()))
                    .append(Component.text("] ", color)));
        }
        return team;
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
            objective.displayName(MINI.deserialize(match.isBombMode()
                    ? "<gold><bold>拆包战 3v3</bold>"
                    : "<gold><bold>月影合战 3v3</bold>"));
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
