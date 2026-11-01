package com.taketori.kassen.data;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * YAML 后端（默认）：玩家档案走 {@code data/players.yml}，战绩走 {@code data/stats.yml}。
 *
 * <p>玩家部分直接委托 {@link YamlPlayerDataStore}（那份实现带着老格式兼容与原子写入，
 * 没必要重写一遍）；这个类只负责把战绩也收进同一套接口，以及补上 {@link #backend()} /
 * {@link #close()} 这类生命周期方法。</p>
 *
 * <p>写盘仍然沿用「内存缓存 + 定期/退出落盘」：YAML 每次绑定都写文件在小服上看起来无所谓，
 * 但 5 分钟一次的定时刷与结算刷已经够用，频繁整文件重写反而更容易在崩溃时踩到半截文件。</p>
 */
public final class YamlDataStore implements DataStore {

    private final Plugin plugin;
    private final YamlPlayerDataStore players;
    private final File statsFile;
    /**
     * 老格式（以玩家名为键）里无法映射到 UUID 的行：读取时原样留存，写回时照旧写出去，
     * 保证升级不会把历史战绩删掉。
     */
    private final Map<String, Map<String, Long>> unmappedLegacyRows = new LinkedHashMap<>();

    public YamlDataStore(Plugin plugin) {
        this.plugin = plugin;
        this.players = new YamlPlayerDataStore(plugin);
        this.statsFile = new File(plugin.getDataFolder(), "data/stats.yml");
    }

    @Override
    public String backend() {
        return "yaml";
    }

    // ---------------------------------------------------------------- 玩家档案（委托）

    @Override
    public void loadAll() {
        players.loadAll();
    }

    @Override
    public String characterIdOf(UUID uuid) {
        return players.characterIdOf(uuid);
    }

    @Override
    public void setCharacterId(UUID uuid, String characterId) {
        players.setCharacterId(uuid, characterId);
    }

    @Override
    public int clearCharacters() {
        return players.clearCharacters();
    }

    @Override
    public String tagOf(UUID uuid) {
        return players.tagOf(uuid);
    }

    @Override
    public void setTag(UUID uuid, String tag) {
        players.setTag(uuid, tag);
    }

    @Override
    public void remove(UUID uuid) {
        players.remove(uuid);
    }

    @Override
    public void saveAll() {
        players.saveAll();
    }

    @Override
    public Map<UUID, String> snapshot() {
        return players.snapshot();
    }

    @Override
    public Map<UUID, String> tagSnapshot() {
        return players.tagSnapshot();
    }

    // ---------------------------------------------------------------- 战绩

    @Override
    public Map<UUID, Map<String, Long>> loadStats() {
        Map<UUID, Map<String, Long>> result = new LinkedHashMap<>();
        unmappedLegacyRows.clear();
        if (!statsFile.exists()) {
            return result;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return result;
        }
        int migrated = 0;
        int unmapped = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Map<String, Long> row = new LinkedHashMap<>();
            for (String statKey : section.getKeys(false)) {
                row.put(statKey, section.getLong(statKey, 0L));
            }
            if (row.isEmpty()) {
                continue;
            }
            UUID uuid = parseUuid(key);
            if (uuid != null) {
                result.put(uuid, row);
                continue;
            }
            // 老格式：键是玩家名。只用 usercache 里已有的映射，绝不发起联网查询。
            UUID cached = cachedUuid(key);
            if (cached != null) {
                result.merge(cached, row, (oldRow, newRow) -> {
                    newRow.forEach((statKey, value) -> oldRow.merge(statKey, value, Long::sum));
                    return oldRow;
                });
                migrated++;
            } else {
                unmappedLegacyRows.put(key, row);
                unmapped++;
            }
        }
        if (migrated > 0 || unmapped > 0) {
            plugin.getLogger().info("战绩文件：已把 " + migrated + " 条按玩家名存的旧记录迁移为 UUID"
                    + (unmapped > 0 ? "，" + unmapped + " 条在 usercache 里查不到 UUID，原样保留" : ""));
        }
        return result;
    }

    @Override
    public void saveStats(Map<UUID, Map<String, Long>> stats) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (stats != null) {
            for (Map.Entry<UUID, Map<String, Long>> entry : stats.entrySet()) {
                UUID uuid = entry.getKey();
                if (uuid == null || entry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, Long> stat : entry.getValue().entrySet()) {
                    long value = stat.getValue() == null ? 0L : stat.getValue();
                    if (value != 0L) {
                        yaml.set("players." + uuid + "." + stat.getKey(), value);
                    }
                }
            }
        }
        // 映射不上的旧行原样写回，不因为换了键就丢数据
        for (Map.Entry<String, Map<String, Long>> entry : unmappedLegacyRows.entrySet()) {
            for (Map.Entry<String, Long> stat : entry.getValue().entrySet()) {
                long value = stat.getValue() == null ? 0L : stat.getValue();
                if (value != 0L) {
                    yaml.set("players." + entry.getKey() + "." + stat.getKey(), value);
                }
            }
        }
        writeAtomically(yaml, statsFile);
    }

    @Override
    public void close() {
        saveAll();
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

    /** 玩家名 → UUID，只用本地 usercache；{@code Bukkit.getOfflinePlayer(String)} 会联网，不能用。 */
    private UUID cachedUuid(String name) {
        try {
            OfflinePlayer cached = org.bukkit.Bukkit.getOfflinePlayerIfCached(name);
            return cached == null ? null : cached.getUniqueId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 先写临时文件再替换，避免崩在写入中途留下截断文件。 */
    private void writeAtomically(YamlConfiguration yaml, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            File tmp = new File(parent, file.getName() + ".tmp");
            yaml.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 " + file.getName() + " 失败", ex);
        }
    }
}
