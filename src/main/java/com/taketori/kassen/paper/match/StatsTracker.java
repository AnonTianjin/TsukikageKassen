package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跨局累计统计（由 {@code DataStore} 落盘：YAML / SQLite / MySQL 皆可）：总积分、对局数、胜场、击杀、月人击杀、拆家、死亡、单局最高分。
 *
 * <p>所有数值都是跨局累加、服务器重启后保留，是总计排行榜（{@link StatsMenu}）的数据来源。
 * 单局内的得分王由各 {@link com.taketori.kassen.paper.match.room.GameRoom} 在结算时播报，两者互不影响。</p>
 *
 * <p>键名保持向后兼容：老版本只有 <code>total-score</code> / <code>matches</code> /
 * <code>wins</code> / <code>best-score</code>，新键缺失时按 0 读入，不会报错。</p>
 */
public final class StatsTracker {

    /** 可排行的统计项（key 是 stats.yml 里的字段名）。 */
    public enum Stat {

        SCORE("总积分", "total-score"),
        KILLS("击杀数", "kills"),
        MINION_KILLS("月人击杀", "minion-kills"),
        BASE_CAPTURES("拆家数", "base-captures"),
        DEATHS("死亡数", "deaths"),
        MATCHES("对局数", "matches"),
        WINS("胜场", "wins"),
        BEST_SCORE("单局最高", "best-score");

        private final String display;
        private final String key;

        Stat(String display, String key) {
            this.display = display;
            this.key = key;
        }

        public String display() {
            return display;
        }

        public String key() {
            return key;
        }
    }

    /** 排行榜中的一行。 */
    public record Row(int rank, String name, long value) {
    }

    /** 把类别名（score / kills / minion / base / deaths / matches / wins / best）解析成统计项。 */
    public static Stat parse(String text) {
        String key = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "kills", "kill" -> Stat.KILLS;
            case "minion", "minions", "minion-kills" -> Stat.MINION_KILLS;
            case "base", "bases", "captures", "base-captures" -> Stat.BASE_CAPTURES;
            case "deaths", "death" -> Stat.DEATHS;
            case "matches", "games", "match" -> Stat.MATCHES;
            case "wins", "win" -> Stat.WINS;
            case "best", "best-score" -> Stat.BEST_SCORE;
            default -> Stat.SCORE;
        };
    }

    private static final class Entry {

        private final Map<Stat, Long> values = new EnumMap<>(Stat.class);

        long get(Stat stat) {
            return values.getOrDefault(stat, 0L);
        }

        void add(Stat stat, long amount) {
            values.merge(stat, amount, Long::sum);
        }

        void max(Stat stat, long value) {
            values.merge(stat, value, Math::max);
        }

        void set(Stat stat, long value) {
            values.put(stat, value);
        }
    }

    private final TaketoriPlugin plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public StatsTracker(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public Stat[] stats() {
        return Stat.values();
    }

    /**
     * 从当前数据后端载入战绩（YAML / SQLite / MySQL 走同一个接口）。
     *
     * <p>统计项键名沿用 {@code stats.yml} 的字段名，所以换后端不需要做数据迁移。</p>
     */
    public void load() {
        entries.clear();
        for (Map.Entry<String, Map<String, Long>> row : plugin.dataStore().loadStats().entrySet()) {
            Entry entry = new Entry();
            for (Stat stat : Stat.values()) {
                Long value = row.getValue().get(stat.key());
                if (value != null) {
                    entry.set(stat, value);
                }
            }
            entries.put(row.getKey(), entry);
        }
        plugin.getLogger().info("已载入 " + entries.size() + " 名玩家的历史战绩（"
                + plugin.dataStore().backend() + "）");
    }

    /** 整批写回当前数据后端（YAML 重写文件；SQL 是一条条 UPSERT）。 */
    public void save() {
        Map<String, Map<String, Long>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            Map<String, Long> row = new LinkedHashMap<>();
            for (Stat stat : Stat.values()) {
                long value = mapEntry.getValue().get(stat);
                if (value != 0L) {
                    row.put(stat.key(), value);
                }
            }
            if (!row.isEmpty()) {
                snapshot.put(mapEntry.getKey(), row);
            }
        }
        plugin.dataStore().saveStats(snapshot);
    }

    // ---------------------------------------------------------------- 写入

    /** 给某个玩家累加一项统计（名字为空或数量为 0 时忽略）。 */
    public void add(String name, Stat stat, long amount) {
        if (name == null || name.isBlank() || amount == 0L || stat == null) {
            return;
        }
        entries.computeIfAbsent(name, key -> new Entry()).add(stat, amount);
    }

    /** 只增不减的统计（例如单局最高分）。 */
    public void max(String name, Stat stat, long value) {
        if (name == null || name.isBlank() || stat == null || value <= 0L) {
            return;
        }
        entries.computeIfAbsent(name, key -> new Entry()).max(stat, value);
    }

    /**
     * 一局结束时写入：每个参赛者累加场次与得分（并按需刷新单局最高分）。
     *
     * @param winner 胜方（null = 平局 / PVE 未达标）
     */
    public void recordMatch(TeamId winner,
                            Map<TeamId, Integer> teamScores,
                            Map<UUID, Integer> playerScores,
                            Map<UUID, String> playerNames) {
        for (Map.Entry<UUID, String> mapEntry : playerNames.entrySet()) {
            String name = mapEntry.getValue();
            int score = playerScores.getOrDefault(mapEntry.getKey(), 0);
            Entry entry = entries.computeIfAbsent(name, key -> new Entry());
            entry.add(Stat.MATCHES, 1L);
            entry.add(Stat.SCORE, score);
            entry.max(Stat.BEST_SCORE, score);
        }
        save();
    }

    /** 记录胜场（需要队伍成员名单，由 GameRoom 在结算时调用）。 */
    public void recordWin(List<String> winners) {
        for (String name : winners) {
            entries.computeIfAbsent(name, key -> new Entry()).add(Stat.WINS, 1L);
        }
        save();
    }

    // ---------------------------------------------------------------- 查询

    public long valueOf(String name, Stat stat) {
        Entry entry = entries.get(name);
        return entry == null ? 0L : entry.get(stat);
    }

    /** 排行榜（按某项统计降序，跳过 offset 行，取 limit 行）。 */
    public List<Row> top(Stat stat, int limit, int offset) {
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.removeIf(entry -> entry.getValue().get(stat) <= 0L);
        sorted.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().get(stat)).reversed());
        List<Row> rows = new ArrayList<>();
        for (int i = Math.max(0, offset); i < sorted.size() && rows.size() < limit; i++) {
            Map.Entry<String, Entry> mapEntry = sorted.get(i);
            rows.add(new Row(i + 1, mapEntry.getKey(), mapEntry.getValue().get(stat)));
        }
        return rows;
    }

    /** 某项统计上有记录的人数（用于分页）。 */
    public int rankedCount(Stat stat) {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.get(stat) > 0L) {
                count++;
            }
        }
        return count;
    }

    /** 聊天栏排行榜文本。 */
    public List<String> leaderboard(Stat stat, int limit) {
        List<String> lines = new ArrayList<>();
        for (Row row : top(stat, limit, 0)) {
            lines.add(String.format("%d. %s — %s %d", row.rank(), row.name(), stat.display(), row.value()));
        }
        if (lines.isEmpty()) {
            lines.add("（还没有记录）");
        }
        return lines;
    }

    /** 兼容旧调用：默认按总积分排行。 */
    public List<String> leaderboard(int limit) {
        List<String> lines = new ArrayList<>();
        for (Row row : top(Stat.SCORE, limit, 0)) {
            Entry entry = entries.get(row.name());
            lines.add(String.format("%d. %s — 总积分 %d / 对局 %d / 胜场 %d / 击杀 %d / 月人 %d / 拆家 %d / 死亡 %d / 单局最高 %d",
                    row.rank(), row.name(), row.value(),
                    entry == null ? 0L : entry.get(Stat.MATCHES),
                    entry == null ? 0L : entry.get(Stat.WINS),
                    entry == null ? 0L : entry.get(Stat.KILLS),
                    entry == null ? 0L : entry.get(Stat.MINION_KILLS),
                    entry == null ? 0L : entry.get(Stat.BASE_CAPTURES),
                    entry == null ? 0L : entry.get(Stat.DEATHS),
                    entry == null ? 0L : entry.get(Stat.BEST_SCORE)));
        }
        if (lines.isEmpty()) {
            lines.add("（还没有记录）");
        }
        return lines;
    }

    public int size() {
        return entries.size();
    }
}
