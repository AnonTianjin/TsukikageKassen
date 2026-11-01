package com.taketori.kassen.data;

import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * MySQL / MariaDB 后端（同一套驱动，MariaDB 与 MySQL 协议兼容）。
 *
 * <p>连接串里的几个开关都是有原因的：</p>
 * <ul>
 *   <li>{@code useSSL=false}：自建服基本不开 TLS，强行开启会握手失败；需要时在配置里打开；</li>
 *   <li>{@code allowPublicKeyRetrieval=true}：MySQL 8 默认 {@code caching_sha2_password}，
 *       不开这项首次连接会报 "Public Key Retrieval is not allowed"；</li>
 *   <li>{@code serverTimezone=UTC} + {@code characterEncoding=utf8}：避免时区告警与中文名乱码。</li>
 * </ul>
 *
 * <p>库不存在时会自动建库（连服务器不带库名 → {@code CREATE DATABASE IF NOT EXISTS} → 再连库），
 * 省掉「先手动建库再填配置」这一步；没有建库权限时报错会原样打出来，方便定位。</p>
 */
public final class MySqlDataStore extends SqlDataStore {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSsl;
    private final int timeoutSeconds;

    public MySqlDataStore(Plugin plugin, String prefix, String host, int port, String database,
                          String username, String password, boolean useSsl, int timeoutSeconds) {
        super(plugin, prefix);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSsl = useSsl;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    @Override
    public String backend() {
        return "mysql";
    }

    @Override
    protected String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        ensureDatabase();
        Properties properties = new Properties();
        properties.setProperty("user", username == null ? "" : username);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("useSSL", String.valueOf(useSsl));
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("characterEncoding", "utf8");
        properties.setProperty("useUnicode", "true");
        properties.setProperty("serverTimezone", "UTC");
        properties.setProperty("connectTimeout", String.valueOf(timeoutSeconds * 1000));
        properties.setProperty("socketTimeout", String.valueOf(timeoutSeconds * 1000));
        return DriverManager.getConnection(url(database), properties);
    }

    /** 库不存在就建一个；建库本身需要账号有 CREATE 权限。 */
    private void ensureDatabase() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username == null ? "" : username);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("useSSL", String.valueOf(useSsl));
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("connectTimeout", String.valueOf(timeoutSeconds * 1000));
        try (Connection server = DriverManager.getConnection(url(null), properties);
             Statement statement = server.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }

    private String url(String db) {
        String suffix = "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC"
                + "&connectTimeout=" + (timeoutSeconds * 1000)
                + "&socketTimeout=" + (timeoutSeconds * 1000);
        return "jdbc:mysql://" + host + ":" + port + "/" + (db == null ? "" : db) + suffix;
    }

    @Override
    protected String createPlayersTableSql() {
        return "CREATE TABLE IF NOT EXISTS {p}players ("
                + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                + "character_id VARCHAR(64) NULL,"
                + "tag_id VARCHAR(64) NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    protected String createStatsTableSql() {
        return "CREATE TABLE IF NOT EXISTS {p}stats ("
                + "uuid CHAR(36) NOT NULL,"
                + "stat_key VARCHAR(48) NOT NULL,"
                + "stat_value BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (uuid, stat_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    protected String upsertPlayerSql() {
        return "INSERT INTO {p}players (uuid, character_id, tag_id) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE character_id = VALUES(character_id), tag_id = VALUES(tag_id)";
    }

    @Override
    protected String upsertStatSql() {
        return "INSERT INTO {p}stats (uuid, stat_key, stat_value) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE stat_value = VALUES(stat_value)";
    }

    @Override
    protected String renameLegacyStatsTableSql() {
        return "RENAME TABLE {p}stats TO {p}stats_legacy";
    }
}
