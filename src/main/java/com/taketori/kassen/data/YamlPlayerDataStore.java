package com.taketori.kassen.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * YAML 实现：<code>data/players.yml</code>。
 *
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     character: tsukuyomi
 *     tag: vip
 * </pre>
 *
 * <p>兼容 0.8.0 之前的旧格式（<code>players.&lt;uuid&gt;: tsukuyomi</code> 直接存角色 id）：
 * 旧值会被当作角色读入，标签留空，之后保存时自动升级成新结构。</p>
 *
 * <p>写入采用"内存缓存 + 定期/退出时落盘"，避免每次绑定都做磁盘 IO。</p>
 */
public final class YamlPlayerDataStore implements PlayerDataStore {

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, String> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, String> tags = new ConcurrentHashMap<>();

    public YamlPlayerDataStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/players.yml");
    }

    @Override
    public void loadAll() {
        bindings.clear();
        tags.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("players.yml 中存在非法 UUID: " + key);
                continue;
            }
            if (root.isConfigurationSection(key)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String character = section.getString("character");
                if (character != null && !character.isBlank()) {
                    bindings.put(uuid, character);
                }
                String tag = section.getString("tag");
                if (tag != null && !tag.isBlank()) {
                    tags.put(uuid, tag);
                }
            } else {
                // 旧格式：值就是角色 id
                String character = root.getString(key);
                if (character != null && !character.isBlank()) {
                    bindings.put(uuid, character);
                }
            }
        }
        plugin.getLogger().info("已载入 " + bindings.size() + " 条角色绑定 / " + tags.size() + " 条隐性标签");
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
            bindings.remove(uuid);   // 只解除角色，保留标签
        } else {
            bindings.put(uuid, characterId);
        }
    }

    @Override
    public int clearCharacters() {
        int removed = bindings.size();
        bindings.clear();
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
    }

    @Override
    public void remove(UUID uuid) {
        if (uuid != null) {
            bindings.remove(uuid);
            tags.remove(uuid);
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

    @Override
    public void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        java.util.Set<UUID> all = new java.util.LinkedHashSet<>(bindings.keySet());
        all.addAll(tags.keySet());
        for (UUID uuid : all) {
            String character = bindings.get(uuid);
            String tag = tags.get(uuid);
            String path = "players." + uuid;
            if (character != null && !character.isBlank()) {
                yaml.set(path + ".character", character);
            }
            if (tag != null && !tag.isBlank()) {
                yaml.set(path + ".tag", tag);
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            // 原子写入：先写临时文件再移动覆盖，避免服务器在写入中途崩溃时留下截断/损坏的 players.yml
            //（旧实现直接 yaml.save(file) 原地覆盖，崩溃即丢整份绑定文件）
            File tmp = new File(parent, file.getName() + ".tmp");
            yaml.save(tmp);
            if (!tmp.renameTo(file)) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 players.yml 失败", ex);
        }
    }
}
