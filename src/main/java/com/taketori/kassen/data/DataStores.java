package com.taketori.kassen.data;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;

/**
 * 数据后端工厂：按 {@code config.yml} 的 {@code storage.type} 选实现。
 *
 * <p><b>失败即回退</b>：SQLite/MySQL 连不上（驱动没下载、库没权限、手滑写错密码）时，
 * 记一条 SEVERE 后回退到 YAML——插件照常能跑，玩家数据不会因为一次配置错误就写不进去。
 * 回退后后端名会出现在启动日志里，方便一眼看出「我配的到底是哪个」。</p>
 *
 * <p>切换后端只在插件启用时生效：{@code /taketori reload} 不会热切数据库——
 * 运行中换存储等于把内存状态与另一份文件对半劈开，风险远大于收益。真要换，重启服务器。</p>
 */
public final class DataStores {

    private DataStores() {
    }

    public static DataStore create(Plugin plugin) {
        String type = plugin.getConfig().getString("storage.type", "yaml");
        String normalized = type == null ? "yaml" : type.trim().toLowerCase(Locale.ROOT);
        String prefix = plugin.getConfig().getString("storage.table-prefix", "kassen_");
        if (prefix == null) {
            prefix = "";
        }
        prefix = prefix.trim();

        switch (normalized) {
            case "sqlite" -> {
                File file = resolveFile(plugin, plugin.getConfig()
                        .getString("storage.sqlite.file", "data/kassen.db"));
                try {
                    SqliteDataStore store = new SqliteDataStore(plugin, prefix, file);
                    store.init();
                    plugin.getLogger().info("数据后端：SQLite（" + file.getName() + "）");
                    return store;
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.SEVERE,
                            "SQLite 不可用，已回退到 YAML：" + ex.getMessage(), ex);
                }
            }
            case "mysql", "mariadb" -> {
                String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
                int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String database = plugin.getConfig().getString("storage.mysql.database", "tsukikage");
                String username = plugin.getConfig().getString("storage.mysql.username", "root");
                String password = plugin.getConfig().getString("storage.mysql.password", "");
                boolean useSsl = plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);
                int timeout = plugin.getConfig().getInt("storage.mysql.timeout-seconds", 5);
                try {
                    MySqlDataStore store = new MySqlDataStore(plugin, prefix, host, port, database,
                            username, password, useSsl, timeout);
                    store.init();
                    plugin.getLogger().info("数据后端：MySQL/MariaDB（" + host + ":" + port
                            + "/" + database + "，表前缀 " + prefix + "）");
                    return store;
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.SEVERE,
                            "MySQL 不可用，已回退到 YAML：" + ex.getMessage(), ex);
                }
            }
            default -> {
                if (!normalized.equals("yaml")) {
                    plugin.getLogger().warning("未知的 storage.type：" + type + "，按 yaml 处理。");
                }
            }
        }
        plugin.getLogger().info("数据后端：YAML（data/players.yml + data/stats.yml）");
        return new YamlDataStore(plugin);
    }

    /** 相对路径按插件数据目录解析；绝对路径原样使用。 */
    private static File resolveFile(Plugin plugin, String raw) {
        String value = raw == null || raw.isBlank() ? "data/kassen.db" : raw.trim();
        File file = new File(value);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), value);
    }
}
