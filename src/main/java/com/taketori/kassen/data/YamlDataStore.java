package com.taketori.kassen.data;

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
    public Map<String, Map<String, Long>> loadStats() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        if (!statsFile.exists()) {
            return result;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return result;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            Map<String, Long> row = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                row.put(key, section.getLong(key, 0L));
            }
            if (!row.isEmpty()) {
                result.put(name, row);
            }
        }
        return result;
    }

    @Override
    public void saveStats(Map<String, Map<String, Long>> stats) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (stats != null) {
            for (Map.Entry<String, Map<String, Long>> entry : stats.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isBlank() || entry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, Long> stat : entry.getValue().entrySet()) {
                    long value = stat.getValue() == null ? 0L : stat.getValue();
                    if (value != 0L) {
                        yaml.set("players." + name + "." + stat.getKey(), value);
                    }
                }
            }
        }
        writeAtomically(yaml, statsFile);
    }

    @Override
    public void close() {
        saveAll();
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
