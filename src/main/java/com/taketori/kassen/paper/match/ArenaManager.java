package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 场地管理（多房间版）：<b>场地注册表</b> + <b>管理员划场会话状态</b>。
 *
 * <p>注册表：{@code arenas.yml} 里可配置多个 {@link ArenaDef}，每个启用的场地对应一个
 * 可反复开局的游戏房间（GameRoom）。首次在新版本启动且发现旧 {@code arena.yml} 时，
 * 自动把旧场地导入为 id={@code default} 的场地，旧文件保留不删。</p>
 *
 * <p>会话状态（不持久化）：管理员当前选中的场地 id（set 开头与 del 等指令作用于它），
 * 以及每个管理员手里选区锄的 pos1/pos2 两个角点。</p>
 */
public final class ArenaManager {

    /** 旧版单场地配置文件名（仅用于自动迁移读取）。 */
    public static final String LEGACY_FILE = "arena.yml";
    /** 多场地配置文件名。 */
    public static final String ARENAS_FILE = "arenas.yml";
    /** 迁移旧场地时使用的场地 id。 */
    public static final String DEFAULT_ARENA_ID = "default";

    /** 合法场地 id：小写字母/数字/下划线/横线，1~32 位。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private final TaketoriPlugin plugin;
    private final File file;
    private final File legacyFile;

    /** 场地注册表（保持创建/文件中的顺序，default 迁移场地排最前）。 */
    private final Map<String, ArenaDef> arenas = new LinkedHashMap<>();

    /** 管理员 UUID → 当前选中的场地 id。 */
    private final Map<UUID, String> selected = new HashMap<>();
    /** 管理员 UUID → 选区角点（与具体场地无关，应用时才作用于选中场地）。 */
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public ArenaManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), ARENAS_FILE);
        this.legacyFile = new File(plugin.getDataFolder(), LEGACY_FILE);
    }

    // ---------------------------------------------------------------- 注册表

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    public Map<String, ArenaDef> all() {
        return Map.copyOf(arenas);
    }

    public ArenaDef get(String id) {
        return id == null ? null : arenas.get(id);
    }

    public boolean exists(String id) {
        return id != null && arenas.containsKey(id);
    }

    /** 已启用的场地列表（RoomManager 为它们创建房间），保持注册顺序。 */
    public Map<String, ArenaDef> enabledArenas() {
        Map<String, ArenaDef> result = new LinkedHashMap<>();
        for (Map.Entry<String, ArenaDef> entry : arenas.entrySet()) {
            if (entry.getValue().enabled()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 创建并注册新场地（已存在同名 id 返回 null）；不会自动保存，由调用方决定时机。 */
    public ArenaDef create(String id) {
        if (!isValidId(id) || arenas.containsKey(id)) {
            return null;
        }
        ArenaDef def = new ArenaDef(plugin, id);
        arenas.put(id, def);
        return def;
    }

    /** 删除场地；返回是否真的删掉了。运行中房间的删除拦截在命令层（需要 RoomManager）。 */
    public boolean delete(String id) {
        return id != null && arenas.remove(id) != null;
    }

    // ---------------------------------------------------------------- 管理员选中场地

    /** 选中场地；场地不存在返回 false。 */
    public boolean select(UUID uuid, String id) {
        if (!arenas.containsKey(id)) {
            return false;
        }
        selected.put(uuid, id);
        return true;
    }

    public String selectedId(UUID uuid) {
        return selected.get(uuid);
    }

    /** 清除管理员的选中场地（删除场地后用）。 */
    public void clearSelected(UUID uuid) {
        selected.remove(uuid);
    }

    /** 当前选中的场地；没选过返回 null（命令层提示先 select/create）。 */
    public ArenaDef selected(UUID uuid) {
        String id = selected.get(uuid);
        return id == null ? null : arenas.get(id);
    }

    // ---------------------------------------------------------------- 选区角点

    public void setPos1(UUID uuid, Location location) {
        pos1.put(uuid, location.clone());
    }

    public void setPos2(UUID uuid, Location location) {
        pos2.put(uuid, location.clone());
    }

    public Location pos1(UUID uuid) {
        return pos1.get(uuid);
    }

    public Location pos2(UUID uuid) {
        return pos2.get(uuid);
    }

    /** 由当前两点构成的选区；不完整或跨世界返回 null。 */
    public CuboidRegion selection(UUID uuid) {
        Location a = pos1.get(uuid);
        Location b = pos2.get(uuid);
        if (a == null || b == null) {
            return null;
        }
        return CuboidRegion.of(a, b);
    }

    public void clearSelection(UUID uuid) {
        pos1.remove(uuid);
        pos2.remove(uuid);
    }

    /** 选区当前缺什么（指令报错用，避免只丢一句"选区不完整"）。 */
    public String selectionStatus(UUID uuid) {
        Location a = pos1.get(uuid);
        Location b = pos2.get(uuid);
        if (a == null && b == null) {
            return "还没标记任何角点";
        }
        if (a == null) {
            return "只有角点 2，缺角点 1（站到一角执行 /taketori arena pos1）";
        }
        if (b == null) {
            return "只有角点 1，缺角点 2（走到对角执行 /taketori arena pos2）";
        }
        String worldA = a.getWorld() == null ? "?" : a.getWorld().getName();
        String worldB = b.getWorld() == null ? "?" : b.getWorld().getName();
        return "两个角点不在同一世界（当前是 " + worldA + " / " + worldB + "）";
    }

    // ---------------------------------------------------------------- 持久化

    public void load() {
        arenas.clear();
        if (file.exists()) {
            loadArenas(YamlConfiguration.loadConfiguration(file));
        } else if (legacyFile.exists()) {
            importLegacy();
        }
        int ready = 0;
        int enabled = 0;
        for (ArenaDef def : arenas.values()) {
            if (def.enabled()) {
                enabled++;
                if (def.isReady()) {
                    ready++;
                }
            }
        }
        plugin.getLogger().info("已载入场地 " + arenas.size() + " 个（启用 " + enabled
                + " / 就绪 " + ready + "）；多房间匹配使用 " + ARENAS_FILE);
    }

    /** 读入 {@code arenas.<id>} 多场地结构。 */
    private void loadArenas(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("arenas");
        if (root == null) {
            // 配置文件存在但为空：什么也不做
            return;
        }
        for (String id : root.getKeys(false)) {
            if (!isValidId(id)) {
                plugin.getLogger().warning("忽略非法场地 id: " + id);
                continue;
            }
            ArenaDef def = new ArenaDef(plugin, id);
            def.read(root.getConfigurationSection(id));
            arenas.put(id, def);
        }
    }

    /**
     * 旧版 arena.yml 自动迁移：旧文件根节点的键结构与单场地段一致
     * （minion-regions / bases / spawns / outpost），直接读入为 default 场地，
     * 然后写出 arenas.yml；旧文件保留不删。
     */
    private void importLegacy() {
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        ArenaDef def = new ArenaDef(plugin, DEFAULT_ARENA_ID);
        def.read(legacy);
        arenas.put(DEFAULT_ARENA_ID, def);
        plugin.getLogger().warning("检测到旧版 " + LEGACY_FILE + "，已自动迁移为场地 ["
                + DEFAULT_ARENA_ID + "]（新文件 " + ARENAS_FILE + "，旧文件保留）。");
        save();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, ArenaDef> entry : arenas.entrySet()) {
            entry.getValue().write(yaml.createSection("arenas." + entry.getKey()));
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 " + ARENAS_FILE + " 失败", ex);
        }
    }
}
