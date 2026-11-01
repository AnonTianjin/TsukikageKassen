package com.taketori.kassen.data;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 后端：单文件数据库（默认 {@code plugins/TsukikageKassen/data/kassen.db}）。
 *
 * <p>适合单机服：零配置、不用额外进程，读性能比 YAML 好得多（YAML 每次落盘要重写整份文件）。</p>
 *
 * <p>连接开 WAL：定期落盘与主线程写入可能撞在一起，rollback journal 模式下会互相等锁；
 * WAL 让读不阻塞写。{@code busy_timeout} 给 5 秒，避免偶发锁冲突直接抛异常。</p>
 */
public final class SqliteDataStore extends SqlDataStore {

    private final File file;

    public SqliteDataStore(Plugin plugin, String prefix, File file) {
        super(plugin, prefix);
        this.file = file;
    }

    @Override
    public String backend() {
        return "sqlite";
    }

    @Override
    protected String driverClass() {
        return "org.sqlite.JDBC";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("无法创建数据目录: " + parent);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    @Override
    protected String createPlayersTableSql() {
        return "CREATE TABLE IF NOT EXISTS {p}players ("
                + "uuid TEXT NOT NULL PRIMARY KEY,"
                + "character_id TEXT NULL,"
                + "tag_id TEXT NULL)";
    }

    @Override
    protected String createStatsTableSql() {
        return "CREATE TABLE IF NOT EXISTS {p}stats ("
                + "uuid TEXT NOT NULL,"
                + "stat_key TEXT NOT NULL,"
                + "stat_value INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (uuid, stat_key))";
    }

    @Override
    protected String upsertPlayerSql() {
        return "INSERT INTO {p}players (uuid, character_id, tag_id) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET "
                + "character_id = excluded.character_id, tag_id = excluded.tag_id";
    }

    @Override
    protected String upsertStatSql() {
        return "INSERT INTO {p}stats (uuid, stat_key, stat_value) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid, stat_key) DO UPDATE SET stat_value = excluded.stat_value";
    }

    @Override
    protected String renameLegacyStatsTableSql() {
        return "ALTER TABLE {p}stats RENAME TO {p}stats_legacy";
    }
}
