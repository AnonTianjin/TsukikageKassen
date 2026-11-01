package com.taketori.kassen.data;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 一次性 UUID 迁移：把旧键（离线 UUID、改名前的键）的玩家档案与战绩搬到新键，再删掉旧行。
 *
 * <p>场景：离线服切换正版验证 / 启用现代转发后，离线 UUID 与正版 UUID 对不上，
 * 角色绑定与跨局战绩会「变成新号」。用它按人搬一次，搬完旧键清空，
 * 因此同一条迁移重复执行不会把战绩翻倍（第二次旧行已经没有了）。</p>
 *
 * <p>合并口径：</p>
 * <ul>
 *   <li>玩家档案：新键已有值优先，只在新区段为空时补写旧值；</li>
 *   <li>战绩：逐项<b>相加</b>（积分、击杀本来就是累计值），0 项不写入；</li>
 *   <li>旧键的档案行与战绩行一律删除。</li>
 * </ul>
 *
 * <p>每次真正执行都会往 {@code data/migrations.log} 追加一行审计记录（时间、执行者、新旧 UUID、搬了多少项）。</p>
 */
public final class UuidMigration {

    /** 迁移计划（dry-run 也给这份数据，管理员据此确认）。 */
    public record Plan(UUID oldId, UUID newId, String oldName,
                       String characterId, String tagId, Map<String, Long> stats) {

        /** 旧键上没有任何可迁移数据。 */
        public boolean empty() {
            return blank(characterId) && blank(tagId) && (stats == null || stats.isEmpty());
        }

        /** 战绩各项数值之和（仅用于展示）。 */
        public long statTotal() {
            return stats == null ? 0L : stats.values().stream().mapToLong(Long::longValue).sum();
        }

        private static boolean blank(String text) {
            return text == null || text.isBlank();
        }
    }

    /** 实际迁移结果。 */
    public record Result(boolean characterMoved, boolean tagMoved, int statKeys, long statTotal) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaketoriPlugin plugin;

    public UuidMigration(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 只读盘点：旧键上有什么。不做任何写入。 */
    public Plan plan(UUID oldId, UUID newId) {
        DataStore store = plugin.dataStore();
        Map<String, Long> stats = new LinkedHashMap<>();
        Map<String, Long> row = store.loadStats().get(oldId);
        if (row != null) {
            stats.putAll(row);
        }
        String name = Bukkit.getOfflinePlayer(oldId).getName();
        return new Plan(oldId, newId, name, store.characterIdOf(oldId), store.tagOf(oldId), stats);
    }

    /**
     * 执行迁移。调用方应先把内存态落盘（{@code dataStore().saveAll()} + {@code stats().save()}），
     * 跑完再 {@code stats().load()} 刷新内存，否则排行榜还是旧键。
     */
    public Result apply(Plan plan, String actor) {
        DataStore store = plugin.dataStore();
        boolean characterMoved = false;
        boolean tagMoved = false;
        int statKeys = 0;
        long statTotal = 0L;

        if (plan.stats() != null && !plan.stats().isEmpty()) {
            Map<UUID, Map<String, Long>> all = store.loadStats();
            Map<String, Long> oldRow = all.remove(plan.oldId());
            if (oldRow != null && !oldRow.isEmpty()) {
                Map<String, Long> newRow = all.computeIfAbsent(plan.newId(), key -> new LinkedHashMap<>());
                for (Map.Entry<String, Long> entry : oldRow.entrySet()) {
                    long value = entry.getValue() == null ? 0L : entry.getValue();
                    if (value == 0L) {
                        continue;
                    }
                    newRow.merge(entry.getKey(), value, Long::sum);
                    statKeys++;
                    statTotal += value;
                }
                store.saveStats(all);
            }
        }

        if (notBlank(plan.characterId()) && blank(store.characterIdOf(plan.newId()))) {
            store.setCharacterId(plan.newId(), plan.characterId());
            characterMoved = true;
        }
        if (notBlank(plan.tagId()) && blank(store.tagOf(plan.newId()))) {
            store.setTag(plan.newId(), plan.tagId());
            tagMoved = true;
        }
        store.remove(plan.oldId());
        store.saveAll();   // YAML 后端在这一步才真正写盘

        Result result = new Result(characterMoved, tagMoved, statKeys, statTotal);
        audit(plan, result, actor);
        return result;
    }

    private void audit(Plan plan, Result result, String actor) {
        String line = LocalDateTime.now().format(STAMP)
                + "\tactor=" + (actor == null || actor.isBlank() ? "console" : actor)
                + "\told=" + plan.oldId() + (plan.oldName() == null ? "" : "(" + plan.oldName() + ")")
                + "\tnew=" + plan.newId()
                + "\tcharacter=" + (result.characterMoved() ? plan.characterId() : "-")
                + "\ttag=" + (result.tagMoved() ? plan.tagId() : "-")
                + "\tstatKeys=" + result.statKeys()
                + "\tstatTotal=" + result.statTotal();
        plugin.getLogger().info("UUID 迁移：" + line);
        File folder = new File(plugin.getDataFolder(), "data");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("无法创建数据目录，本次迁移只留在控制台日志里");
            return;
        }
        Path path = new File(folder, "migrations.log").toPath();
        try {
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "写迁移审计日志失败（迁移本身已完成）", ex);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean notBlank(String text) {
        return !blank(text);
    }
}
