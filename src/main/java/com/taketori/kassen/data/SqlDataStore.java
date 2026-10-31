package com.taketori.kassen.data;

import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * JDBC 公共实现：玩家档案与战绩都落在同一套表里，SQLite / MySQL 只差方言与连接串。
 *
 * <p><b>内存 + 写穿</b>：档案读写走内存（{@code ConcurrentHashMap}），每次修改立刻 UPSERT；
 * 战绩在结算时整批 UPSERT。查排行榜仍由 {@code StatsTracker} 在内存里做——数据量是几百到几千行，
 * 每秒去 SELECT 一次不值得。</p>
 *
 * <p><b>为什么不用连接池</b>：这里的并发只有「主线程绑定角色 + 每 5 分钟一次落盘」，
 * 单连接 + {@code synchronized} 足够；引入 HikariCP 反而要多一个运行期依赖，
 * 与「插件零依赖、驱动交给 Paper 的 {@code libraries} 下载」这个前提冲突。</p>
 *
 * <p>子类只提供方言：建表语句、UPSERT 语句、连接串。连接断了会自动重连一次。</p>
 */
public abstract class SqlDataStore implements DataStore {

    protected final Plugin plugin;
    /** 表名前缀（多套服务共用一个库时用来隔开）。 */
    protected final String prefix;

    private final Map<UUID, String> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, String> tags = new ConcurrentHashMap<>();

    private Connection connection;

    protected SqlDataStore(Plugin plugin, String prefix) {
        this.plugin = plugin;
        this.prefix = prefix == null ? "" : prefix;
    }

    // ---------------------------------------------------------------- 方言

    /** 建表 / UPSERT 语句（{@code {p}} 会被替换成实际表名前缀）。 */
    protected abstract String createPlayersTableSql();

    protected abstract String createStatsTableSql();

    protected abstract String upsertPlayerSql();

    protected abstract String upsertStatSql();

    /** 驱动类名，用于给出「缺依赖」时的可读报错。 */
    protected abstract String driverClass();

    /** 建立连接（子类可先建库/建目录）。 */
    protected abstract Connection openConnection() throws SQLException;

    // ---------------------------------------------------------------- 生命周期

    /** 建连接、建表；失败直接抛出，由工厂回退到 YAML。 */
    public void init() throws Exception {
        // 驱动按需加载：类路径里没有就去 drivers/*.jar 里找（见 DriverLoader）
        DriverLoader.ensure(plugin, driverClass());
        connection = openConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql(createPlayersTableSql()));
            statement.execute(sql(createStatsTableSql()));
        }
    }

    @Override
    public void loadAll() {
        bindings.clear();
        tags.clear();
        String query = "SELECT uuid, character_id, tag_id FROM " + table("players");
        synchronized (this) {
            try (PreparedStatement ps = connection().prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = parseUuid(rs.getString(1));
                    if (uuid == null) {
                        continue;
                    }
                    String character = rs.getString(2);
                    String tag = rs.getString(3);
                    if (character != null && !character.isBlank()) {
                        bindings.put(uuid, character);
                    }
                    if (tag != null && !tag.isBlank()) {
                        tags.put(uuid, tag);
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 读取玩家档案失败", ex);
            }
        }
        plugin.getLogger().info("已从 " + backend() + " 载入 " + bindings.size()
                + " 条角色绑定 / " + tags.size() + " 条隐性标签");
    }

    @Override
    public String characterIdOf(UUID uuid) {
        return uuid == null ? null : bindings.get(uuid);
    }

    @Override
    public void setCharacterId(UUID uuid, String characterId) {
        if (uuid == null) {
            return;
        }
        if (characterId == null || characterId.isBlank()) {
            bindings.remove(uuid);   // 只解除角色，标签保留
        } else {
            bindings.put(uuid, characterId);
        }
        upsertPlayer(uuid);
    }

    @Override
    public int clearCharacters() {
        int removed = bindings.size();
        bindings.clear();
        synchronized (this) {
            try (Statement statement = connection().createStatement()) {
                statement.executeUpdate("UPDATE " + table("players") + " SET character_id = NULL");
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 清空角色绑定失败", ex);
            }
        }
        return removed;
    }

    @Override
    public String tagOf(UUID uuid) {
        return uuid == null ? null : tags.get(uuid);
    }

    @Override
    public void setTag(UUID uuid, String tag) {
        if (uuid == null) {
            return;
        }
        if (tag == null || tag.isBlank()) {
            tags.remove(uuid);
        } else {
            tags.put(uuid, tag);
        }
        upsertPlayer(uuid);
    }

    @Override
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        bindings.remove(uuid);
        tags.remove(uuid);
        synchronized (this) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "DELETE FROM " + table("players") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 删除玩家档案失败", ex);
            }
        }
    }

    @Override
    public void saveAll() {
        // 档案是写穿模型，这里只把可能断开的连接探活一遍；真正需要落盘的是战绩
        synchronized (this) {
            try {
                if (connection == null || connection.isClosed()) {
                    connection = openConnection();
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 数据库连接不可用", ex);
            }
        }
    }

    @Override
    public Map<UUID, String> snapshot() {
        return new LinkedHashMap<>(bindings);
    }

    @Override
    public Map<UUID, String> tagSnapshot() {
        return new LinkedHashMap<>(tags);
    }

    // ---------------------------------------------------------------- 战绩

    @Override
    public Map<String, Map<String, Long>> loadStats() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        String query = "SELECT player_name, stat_key, stat_value FROM " + table("stats");
        synchronized (this) {
            try (PreparedStatement ps = connection().prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String key = rs.getString(2);
                    if (name == null || key == null) {
                        continue;
                    }
                    result.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
                            .put(key, rs.getLong(3));
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 读取战绩失败", ex);
            }
        }
        return result;
    }

    @Override
    public void saveStats(Map<String, Map<String, Long>> stats) {
        if (stats == null || stats.isEmpty()) {
            return;
        }
        String sql = sql(upsertStatSql());
        synchronized (this) {
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                for (Map.Entry<String, Map<String, Long>> entry : stats.entrySet()) {
                    String name = entry.getKey();
                    if (name == null || name.isBlank() || entry.getValue() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Long> stat : entry.getValue().entrySet()) {
                        long value = stat.getValue() == null ? 0L : stat.getValue();
                        if (value == 0L) {
                            continue;
                        }
                        ps.setString(1, name);
                        ps.setString(2, stat.getKey());
                        ps.setLong(3, value);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 写入战绩失败", ex);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[" + backend() + "] 关闭连接失败", ex);
            } finally {
                connection = null;
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 单行 UPSERT：两列都写，但值取内存里的当前状态，所以「只改标签」不会把角色抹掉。 */
    private void upsertPlayer(UUID uuid) {
        synchronized (this) {
            try (PreparedStatement ps = connection().prepareStatement(sql(upsertPlayerSql()))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, bindings.get(uuid));
                ps.setString(3, tags.get(uuid));
                ps.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[" + backend() + "] 写入玩家档案失败", ex);
            }
        }
    }

    /** 取连接；已关闭则重连一次。 */
    protected Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = openConnection();
        }
        return connection;
    }

    protected String table(String name) {
        return prefix + name;
    }

    protected String sql(String template) {
        return template.replace("{p}", prefix);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
