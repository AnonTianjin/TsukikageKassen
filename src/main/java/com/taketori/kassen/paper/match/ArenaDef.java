package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板定义（月宫制）：arenas.yml 的每个条目是一份"月面地图"模板——
 * 区域、双方出生点、等待点都在模板世界里划定；玩家创建房间时以它为蓝本
 * 异步复制出专属世界（{@code copyForWorld} 重定向世界名），结算后世界删除。
 *
 * <p>持有：月人刷新区（可多个、带标签）、道具刷新点（可多个）、双方各 N 个基地、
 * 双方出生点、PVE 据点、<b>等待出生点</b>，以及启停状态。</p>
 *
 * <p>一个"能开局的模板"至少需要：**至少 1 个月人刷新区** + 2×N 个基地 + 2 个出生点
 * + 1 个等待出生点；道具刷新点可选。本类只负责数据与就绪判定，运行时状态在 GameRoom。</p>
 *
 * <p>点位用 {@link Point}（世界名 + 坐标）保存：模板编辑世界卸载后数据不丢，
 * {@link #isReady()} 只看数据完整性，不要求世界已加载。</p>
 */
public final class ArenaDef {

    /** 每队基地数量的默认值（config.yml 的 base.count-per-team 没写或写错时用它）。 */
    public static final int BASES_PER_TEAM = 3;
    /** 每队基地编号的硬上限：防止把编号写成几百之后遍历与提示失控。 */
    public static final int MAX_BASES_PER_TEAM = 16;

    /** 月人刷新区标签：只刷新普通月人。 */
    public static final String REGION_KIND_NORMAL = "normal";
    /** 月人刷新区标签：普通与精英都刷新（旧配置没有标签的区域也按此处理）。 */
    public static final String REGION_KIND_MIXED = "mixed";

    /**
     * 模板/房间通用点位：世界名 + 坐标。世界对象用时解析（{@link #toBukkitLocation()}），
     * 模板编辑世界卸载后仍保留完整数据；{@link #inWorld} 用于克隆到房间世界。
     */
    public record Point(String worldName, double x, double y, double z, float yaw, float pitch) {

        public static Point of(Location location) {
            return new Point(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }

        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }

        /** 解析为 Bukkit 位置；世界未加载返回 null（运行中的房间世界不会发生）。 */
        public Location toBukkitLocation() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : toLocation(world);
        }

        public Point inWorld(String newWorldName) {
            return new Point(newWorldName, x, y, z, yaw, pitch);
        }

        public void write(ConfigurationSection section) {
            section.set("world", worldName);
            section.set("x", x);
            section.set("y", y);
            section.set("z", z);
            section.set("yaw", (double) yaw);
            section.set("pitch", (double) pitch);
        }

        public static Point read(ConfigurationSection section) {
            if (section == null || !section.isString("world")) {
                return null;
            }
            return new Point(section.getString("world"),
                    section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
        }
    }

    private final TaketoriPlugin plugin;
    private final String id;

    private String display;
    private boolean enabled = true;

    /** 月人刷新区：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> minionRegions = new LinkedHashMap<>();
    /** 月人刷新区标签：编号 → normal / mixed（同一条 setminion 指令注册，标签可在 arenas.yml 修改）。 */
    private final Map<Integer, String> minionRegionKinds = new LinkedHashMap<>();
    /** 道具刷新点：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> lootRegions = new LinkedHashMap<>();
    private final Map<TeamId, Map<Integer, CuboidRegion>> bases = new EnumMap<>(TeamId.class);
    private final Map<TeamId, Point> spawns = new EnumMap<>(TeamId.class);

    /** PVE 保卫据点的位置（雪傀儡站在这里）；没设置时用第一个月人刷新区中心。 */
    private Point outpost;
    /**
     * 包点：PVP 里开局把这个方块换成拉杆，进攻方（红）连点拆开即胜。
     *
     * <p>没设置的场地照旧只按分数判胜负，不额外要求管理员先标点。</p>
     */
    private Point bomb;
    /** 中立等待出生点：匹配后玩家在此集结等待倒计时（开局才分队）。 */
    private Point waitSpawn;
    /** 等待区区域（可选）：设置了就用整个选区，加入者在区域内随机分布；未设置时用 waitSpawn 单点。 */
    private CuboidRegion waitRegion;

    /**
     * 战国 3v3 专用点位：天守阁、箭楼（含铜钟与守卫刷新点）、中地小兵刷新区、跳跃台。
     *
     * <p>独立成 {@link com.taketori.kassen.paper.match.sengoku.SengokuMapDef} 而不是继续往本类堆字段：
     * 这些点位只被新模式读，堆进来会让 PVP / PVE 两条线也跟着变大变脆。非战国地图这一整段为空，
     * 与既有逻辑完全无交集。</p>
     */
    private final com.taketori.kassen.paper.match.sengoku.SengokuMapDef sengoku;

    // ---- 本场地月人刷新节奏（arenas.yml 的 minion-spawn 段；null = 用 config.yml 的 minion 全局默认值）----
    private Integer spawnIntervalSeconds;
    private Integer spawnPerSpawn;
    private Integer spawnMaxAlive;

    public ArenaDef(TaketoriPlugin plugin, String id) {
        this.plugin = plugin;
        this.id = id;
        this.display = id;
        // 必须在 id 赋值之后再构造：SengokuMapDef 记着场地 id 用于告警文案
        this.sengoku = new com.taketori.kassen.paper.match.sengoku.SengokuMapDef(plugin, id);
        for (TeamId team : TeamId.values()) {
            bases.put(team, new LinkedHashMap<>());
        }
    }

    public String id() {
        return id;
    }

    public String display() {
        return display == null || display.isBlank() ? id : display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- 模板世界与克隆

    /** 本模板绑定的世界名（等待区 → 等待点 → 任一出生点 → 任一区域，取第一个可用的）。 */
    public String worldName() {
        if (waitRegion != null) {
            return waitRegion.worldName();
        }
        if (waitSpawn != null) {
            return waitSpawn.worldName();
        }
        for (TeamId team : TeamId.values()) {
            Point spawn = spawns.get(team);
            if (spawn != null) {
                return spawn.worldName();
            }
        }
        for (CuboidRegion region : regions()) {
            return region.worldName();
        }
        return null;
    }

    /** 本模板绑定的世界（未加载返回 null；复制世界文件夹不依赖世界对象）。 */
    public World arenaWorld() {
        String name = worldName();
        return name == null ? null : Bukkit.getWorld(name);
    }

    /**
     * 以本模板为蓝本克隆出<b>房间专属</b>定义：全部区域与点位重定向到房间世界。
     * 结果不持久化（不写回 arenas.yml），生命周期与房间一致。
     */
    public ArenaDef copyForWorld(String worldName) {
        ArenaDef copy = new ArenaDef(plugin, id);
        copy.display = display;
        copy.enabled = enabled;
        for (Map.Entry<Integer, CuboidRegion> entry : minionRegions.entrySet()) {
            copy.minionRegions.put(entry.getKey(), entry.getValue().inWorld(worldName));
            copy.minionRegionKinds.put(entry.getKey(), minionRegionKind(entry.getKey()));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : lootRegions.entrySet()) {
            copy.lootRegions.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (TeamId team : TeamId.values()) {
            for (Map.Entry<Integer, CuboidRegion> entry : bases(team).entrySet()) {
                copy.bases.get(team).put(entry.getKey(), entry.getValue().inWorld(worldName));
            }
            Point spawn = spawns.get(team);
            if (spawn != null) {
                copy.spawns.put(team, spawn.inWorld(worldName));
            }
        }
        if (outpost != null) {
            copy.outpost = outpost.inWorld(worldName);
        }
        if (bomb != null) {
            copy.bomb = bomb.inWorld(worldName);
        }
        if (waitSpawn != null) {
            copy.waitSpawn = waitSpawn.inWorld(worldName);
        }
        if (waitRegion != null) {
            copy.waitRegion = waitRegion.inWorld(worldName);
        }
        copy.spawnIntervalSeconds = spawnIntervalSeconds;
        copy.spawnPerSpawn = spawnPerSpawn;
        copy.spawnMaxAlive = spawnMaxAlive;
        // 战国点位：整份克隆并重定向到房间世界
        copy.sengoku.copyFrom(sengoku.copyForWorld(worldName));
        return copy;
    }

    // ---------------------------------------------------------------- 月人刷新区

    /** 下一个还没配置的月人刷新区编号。 */
    public int nextFreeMinionIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!minionRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 设置第 index 个月人刷新区（1 起），标签默认 mixed。 */
    public void setMinionRegion(int index, CuboidRegion region) {
        setMinionRegion(index, region, REGION_KIND_MIXED);
    }

    /** 设置第 index 个月人刷新区（1 起）并指定标签（normal / mixed，其它值一律按 mixed）。 */
    public void setMinionRegion(int index, CuboidRegion region, String kind) {
        int safeIndex = Math.max(1, index);
        minionRegions.put(safeIndex, region);
        minionRegionKinds.put(safeIndex, normalizeKind(kind));
    }

    private static String normalizeKind(String raw) {
        return raw != null && REGION_KIND_NORMAL.equalsIgnoreCase(raw.trim())
                ? REGION_KIND_NORMAL : REGION_KIND_MIXED;
    }

    /** 某刷新区的标签（未记录时按 mixed，兼容旧 arenas.yml）。 */
    public String minionRegionKind(int index) {
        return minionRegionKinds.getOrDefault(index, REGION_KIND_MIXED);
    }

    /** mixed 标签刷新区数量（精英月人只落在这些区）。 */
    public int mixedMinionRegionCount() {
        int count = 0;
        for (Integer index : minionRegions.keySet()) {
            if (REGION_KIND_MIXED.equals(minionRegionKind(index))) {
                count++;
            }
        }
        return count;
    }

    /** 删除某个月人刷新区；返回是否真的删掉了（编号本来就没配 → false）。 */
    public boolean clearMinionRegion(int index) {
        minionRegionKinds.remove(index);
        return minionRegions.remove(index) != null;
    }

    public Map<Integer, CuboidRegion> minionRegions() {
        return Map.copyOf(minionRegions);
    }

    /** 第 1 个刷新区（兼容只划了一个区域的场地）。 */
    public CuboidRegion minionRegion() {
        return minionRegions.get(1);
    }

    public CuboidRegion minionRegion(int index) {
        return minionRegions.get(index);
    }

    /**
     * 全部月人刷新区（按编号升序）。普通月人在这些区之间<b>轮转均分</b>：
     * 刷怪器按列表顺序依次取区，长期统计下各区刷新数量严格相等。
     */
    public List<CuboidRegion> minionRegionList() {
        List<Integer> indices = new ArrayList<>(minionRegions.keySet());
        indices.sort(Integer::compareTo);
        List<CuboidRegion> regions = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            regions.add(minionRegions.get(index));
        }
        return List.copyOf(regions);
    }

    /**
     * mixed 标签的月人刷新区（按编号升序）。精英月人（精英波 / PVE 大波次）只在这些区之间
     * 轮转均分；列表为空说明全部区都是 normal，这一批精英不刷新。
     */
    public List<CuboidRegion> mixedMinionRegionList() {
        List<Integer> indices = new ArrayList<>(minionRegions.keySet());
        indices.sort(Integer::compareTo);
        List<CuboidRegion> regions = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            if (REGION_KIND_MIXED.equals(minionRegionKind(index))) {
                regions.add(minionRegions.get(index));
            }
        }
        return List.copyOf(regions);
    }

    /**
     * normal 标签的月人刷新区（按编号升序）。normal 独立循环只在这些区之间轮转均分；
     * 列表为空说明没有 normal 区，该循环不启动。
     */
    public List<CuboidRegion> normalMinionRegionList() {
        List<Integer> indices = new ArrayList<>(minionRegions.keySet());
        indices.sort(Integer::compareTo);
        List<CuboidRegion> regions = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            if (REGION_KIND_NORMAL.equals(minionRegionKind(index))) {
                regions.add(minionRegions.get(index));
            }
        }
        return List.copyOf(regions);
    }

    public int minionRegionCount() {
        return minionRegions.size();
    }

    // ---------------------------------------------------------------- 道具刷新点

    /** 下一个还没配置的道具点编号。 */
    public int nextFreeLootIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!lootRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    public void setLootRegion(int index, CuboidRegion region) {
        lootRegions.put(Math.max(1, index), region);
    }

    public boolean clearLootRegion(int index) {
        return lootRegions.remove(index) != null;
    }

    public Map<Integer, CuboidRegion> lootRegions() {
        return Map.copyOf(lootRegions);
    }

    public CuboidRegion lootRegion(int index) {
        return lootRegions.get(index);
    }

    public int lootRegionCount() {
        return lootRegions.size();
    }

    // ---------------------------------------------------------------- 基地与出生点

    public void setBase(TeamId team, int index, CuboidRegion region) {
        bases.computeIfAbsent(team, key -> new LinkedHashMap<>()).put(index, region);
    }

    public boolean clearBase(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map != null && map.remove(index) != null;
    }

    public void setSpawn(TeamId team, Location location) {
        spawns.put(team, location == null || location.getWorld() == null ? null : Point.of(location));
    }

    public CuboidRegion base(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? null : map.get(index);
    }

    public Map<Integer, CuboidRegion> bases(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * 场地全部已配置的立体区域（双方基地 + 月人刷新区 + 道具点）。
     * 房间结算时只在这些区域内清理掉落物/弹体：同一世界并存多个场地时，
     * 绝不能按世界一刀切，否则会清掉别的房间乃至大厅的实体。
     * 区域外的散落物交给原版自然消失。
     */
    public List<CuboidRegion> regions() {
        List<CuboidRegion> all = new ArrayList<>();
        for (Map<Integer, CuboidRegion> map : bases.values()) {
            all.addAll(map.values());
        }
        all.addAll(minionRegions.values());
        all.addAll(lootRegions.values());
        // 战国专用区域（天守阁 / 箭楼 / 中地小兵）也算"对局区域"。
        // 不并入的话有两个后果：① 玩家能在箭楼占领区与天守阁门前自由放置方块
        // （把铜钟或击破器生成点围死），因为"对局中禁止破坏/放置"的判定只看 regions() 拼出的
        // playBounds；② 丢在这些区域的物品不会被结算清理，跨小局残留。
        all.addAll(sengoku.regions());
        return all;
    }

    /** 位置是否落在本场地已配置的某个区域内。 */
    public boolean containsRegionLocation(Location location) {
        if (location == null) {
            return false;
        }
        for (CuboidRegion region : regions()) {
            if (region.contains(location)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 场地<b>对局活动范围</b>的外包矩形：把所有基地、月人刷新区、道具点合并成一个
     * 最小外接长方体，用于：①在四周立屏障墙（防止外人闯入/玩家跑出）；
     * ②对局中禁止破坏/放置这个范围内的方块。
     *
     * <p>所有区域必须在同一世界（选区工具已强制）；跨世界时返回 null。
     * 没有任何区域时也返回 null。</p>
     */
    public CuboidRegion playBounds() {
        List<CuboidRegion> all = regions();
        if (all.isEmpty()) {
            return null;
        }
        String world = all.get(0).worldName();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (CuboidRegion region : all) {
            if (!region.worldName().equals(world)) {
                return null;  // 跨世界不合法
            }
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.maxX());
            maxY = Math.max(maxY, region.maxY());
            maxZ = Math.max(maxZ, region.maxZ());
        }
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int baseCount(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? 0 : map.size();
    }

    /** 双方出生点（点位；世界名 + 坐标）。 */
    public Point spawn(TeamId team) {
        return spawns.get(team);
    }

    // ---------------------------------------------------------------- 据点（PVE）

    public void setOutpost(Location location) {
        this.outpost = location == null || location.getWorld() == null ? null : Point.of(location);
    }

    public void clearOutpost() {
        this.outpost = null;
    }

    /** 据点位置；没显式设置时回落到第一个月人刷新区中心（还没有区域就返回 null）。 */
    public Point outpost() {
        if (outpost != null) {
            return outpost;
        }
        CuboidRegion region = minionRegion(1);
        return region == null ? null : region.centerPoint();
    }

    public boolean hasOutpost() {
        return outpost != null;
    }

    // ---------------------------------------------------------------- 包点

    public void setBomb(Location location) {
        this.bomb = location == null || location.getWorld() == null ? null : Point.of(location);
    }

    public void clearBomb() {
        this.bomb = null;
    }

    public Point bomb() {
        return bomb;
    }

    public boolean hasBomb() {
        return bomb != null;
    }

    // ---------------------------------------------------------------- 等待出生点

    public void setWaitSpawn(Location location) {
        this.waitSpawn = location == null || location.getWorld() == null ? null : Point.of(location);
    }

    /** 把选区设为等待区区域（加入者在区域内随机分布）；null 清除区域、回退到 wait-spawn 单点。 */
    public void setWaitRegion(CuboidRegion region) {
        this.waitRegion = region;
    }

    /** 等待区区域；未设置返回 null（此时用 wait-spawn 单点）。 */
    public CuboidRegion waitRegion() {
        return waitRegion;
    }

    /** 等待区的拉回目标：有区域用其中心，否则用单点；世界未加载返回 null。 */
    public Location waitAreaCenterOrSpawn() {
        if (waitRegion != null) {
            return waitRegion.center();
        }
        return waitSpawn == null ? null : waitSpawn.toBukkitLocation();
    }

    /** 中立等待出生点（未设置返回 null）。 */
    public Point waitSpawn() {
        return waitSpawn;
    }

    public boolean hasWaitSpawn() {
        return waitSpawn != null || waitRegion != null;
    }

    // ---------------------------------------------------------------- 月人刷新节奏（minion-spawn）

    /** 本场地刷新间隔（秒）；arenas.yml 的 minion-spawn 未配置时回落全局默认值。 */
    public int minionSpawnIntervalSeconds(int fallback) {
        return spawnIntervalSeconds == null ? fallback : Math.max(1, spawnIntervalSeconds);
    }

    /** 本场地每波刷新数量；未配置时回落全局默认值。 */
    public int minionSpawnPerSpawn(int fallback) {
        return spawnPerSpawn == null ? fallback : Math.max(1, spawnPerSpawn);
    }

    /** 本场地场上月人上限；未配置时回落全局默认值。 */
    public int minionSpawnMaxAlive(int fallback) {
        return spawnMaxAlive == null ? fallback : Math.max(1, spawnMaxAlive);
    }

    /** 是否配置了任一项场地级刷新节奏（arena list 展示用）。 */
    public boolean hasMinionSpawnSettings() {
        return spawnIntervalSeconds != null || spawnPerSpawn != null || spawnMaxAlive != null;
    }

    // ---------------------------------------------------------------- 基地数量规则

    /** 该队下一个还没配置的基地编号；都配满了返回 -1。 */
    public int nextFreeBaseIndex(TeamId team) {
        for (int i = 1; i <= baseIndexCeiling(); i++) {
            if (base(team, i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 每队基地数量上限；{@code 0} 表示 config.yml 里写了 {@code auto}（不设上限）。
     */
    public int baseLimit() {
        String raw = plugin.config().baseCountPerTeam();
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return 0;
        }
        try {
            return Math.max(1, Math.min(MAX_BASES_PER_TEAM, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("base.count-per-team 无法解析：" + raw
                    + "（可写正整数或 auto）→ 回退到每队 " + BASES_PER_TEAM + " 个");
            return BASES_PER_TEAM;
        }
    }

    /** 开局时每队需要配满的基地数量（auto 模式下只要求至少 1 个）。 */
    public int requiredBasesPerTeam() {
        int limit = baseLimit();
        return limit > 0 ? limit : 1;
    }

    /**
     * 可用基地编号的上界（提示与校验用）：固定数量时就是那个数量；
     * auto 时取"当前实际配到的最大编号 + 1"。
     */
    public int baseIndexCeiling() {
        int limit = baseLimit();
        if (limit > 0) {
            return limit;
        }
        int max = 0;
        for (TeamId team : TeamId.values()) {
            for (int index : bases(team).keySet()) {
                max = Math.max(max, index);
            }
        }
        return Math.max(1, Math.min(MAX_BASES_PER_TEAM, max + 1));
    }

    // ---------------------------------------------------------------- 就绪判定

    /** 模板是否已具备开局条件（等待出生点为动态房间的必备项；不要求世界已加载）。 */
    public boolean isReady() {
        if (minionRegions.isEmpty() || !hasWaitSpawn()) {
            return false;
        }
        int required = requiredBasesPerTeam();
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null || baseCount(team) < required) {
                return false;
            }
        }
        return true;
    }

    /** 还缺什么（给管理员的提示）。 */
    public String missingHint() {
        StringBuilder builder = new StringBuilder();
        if (minionRegions.isEmpty()) {
            builder.append("月人刷新区 ");
        }
        int required = requiredBasesPerTeam();
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null) {
                builder.append(team.display()).append("出生点 ");
            }
            int count = baseCount(team);
            if (count < required) {
                builder.append(team.display()).append("基地(").append(count).append('/')
                        .append(required).append(") ");
            }
        }
        if (!hasWaitSpawn()) {
            builder.append("等待出生点 ");
        }
        return builder.isEmpty() ? "无" : builder.toString().trim();
    }

    // ---------------------------------------------------------------- 持久化

    /**
     * 从配置段读入单个模板。新格式的段是 {@code arenas.<id>}；
     * 旧版 arena.yml 的根节点结构与单场地段一致，迁移时直接把根节点传进来即可。
     */
    public void read(ConfigurationSection section) {
        minionRegions.clear();
        minionRegionKinds.clear();
        lootRegions.clear();
        bases.values().forEach(Map::clear);
        spawns.clear();
        outpost = null;
        bomb = null;
        waitSpawn = null;
        waitRegion = null;
        spawnIntervalSeconds = null;
        spawnPerSpawn = null;
        spawnMaxAlive = null;
        if (section == null) {
            return;
        }
        enabled = section.getBoolean("enabled", true);
        String displayText = section.getString("display");
        if (displayText != null && !displayText.isBlank()) {
            display = displayText;
        }

        readMinionRegions(section.getConfigurationSection("minion-regions"));
        // 旧版单键 minion-region 当作 1 号区域（没有 kind 键，按 mixed 处理）
        CuboidRegion legacy = CuboidRegion.read(section.getConfigurationSection("minion-region"));
        if (legacy != null && !minionRegions.containsKey(1)) {
            setMinionRegion(1, legacy);
        }
        readRegions(section.getConfigurationSection("loot-regions"), lootRegions);

        ConfigurationSection baseSection = section.getConfigurationSection("bases");
        if (baseSection != null) {
            for (TeamId team : TeamId.values()) {
                ConfigurationSection teamSection = baseSection.getConfigurationSection(team.key());
                if (teamSection == null) {
                    continue;
                }
                for (String key : teamSection.getKeys(false)) {
                    try {
                        int index = Integer.parseInt(key);
                        CuboidRegion region = CuboidRegion.read(teamSection.getConfigurationSection(key));
                        if (region != null) {
                            setBase(team, index, region);
                        }
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("场地 " + id + " 的 " + team.key()
                                + " 基地编号非法: " + key);
                    }
                }
            }
        }

        ConfigurationSection spawnSection = section.getConfigurationSection("spawns");
        if (spawnSection != null) {
            for (TeamId team : TeamId.values()) {
                Point spawn = Point.read(spawnSection.getConfigurationSection(team.key()));
                if (spawn != null) {
                    spawns.put(team, spawn);
                }
            }
        }
        outpost = Point.read(section.getConfigurationSection("outpost"));
        bomb = Point.read(section.getConfigurationSection("bomb"));
        waitSpawn = Point.read(section.getConfigurationSection("wait-spawn"));
        waitRegion = CuboidRegion.read(section.getConfigurationSection("wait-region"));
        // 战国点位：整段缺失是正常的（非战国地图本来就没有）
        sengoku.read(section.getConfigurationSection("sengoku"));

        ConfigurationSection minionSpawnSection = section.getConfigurationSection("minion-spawn");
        if (minionSpawnSection != null) {
            if (minionSpawnSection.isInt("interval-seconds")) {
                spawnIntervalSeconds = minionSpawnSection.getInt("interval-seconds");
            } else {
                warnNonInt(minionSpawnSection, "interval-seconds");
            }
            if (minionSpawnSection.isInt("per-spawn")) {
                spawnPerSpawn = minionSpawnSection.getInt("per-spawn");
            } else {
                warnNonInt(minionSpawnSection, "per-spawn");
            }
            if (minionSpawnSection.isInt("max-alive")) {
                spawnMaxAlive = minionSpawnSection.getInt("max-alive");
            } else {
                warnNonInt(minionSpawnSection, "max-alive");
            }
        }
    }

    /** 键被显式写出但不是整数：静默忽略会意外回落全局默认值，必须警告。 */
    private void warnNonInt(ConfigurationSection section, String key) {
        if (!section.isSet(key)) {
            return;
        }
        plugin.getLogger().warning("场地 [" + id + "] minion-spawn." + key
                + " 不是整数（值: " + section.getString(key) + "），已忽略并使用默认值。");
    }

    private void readRegions(ConfigurationSection section, Map<Integer, CuboidRegion> target) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                CuboidRegion region = CuboidRegion.read(section.getConfigurationSection(key));
                if (region != null) {
                    target.put(index, region);
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("场地 " + id + " 的区域编号非法: " + key);
            }
        }
    }

    /** 读入月人刷新区（区域 + kind 标签；旧文件没有 kind 时按 mixed 处理）。 */
    private void readMinionRegions(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                ConfigurationSection sub = section.getConfigurationSection(key);
                CuboidRegion region = CuboidRegion.read(sub);
                if (region != null) {
                    setMinionRegion(index, region, sub == null ? null : sub.getString("kind"));
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("场地 " + id + " 的区域编号非法: " + key);
            }
        }
    }

    /** 把本模板写入 {@code arenas.<id>} 段。 */
    public void write(ConfigurationSection section) {
        section.set("display", display());
        section.set("enabled", enabled);
        for (Map.Entry<Integer, CuboidRegion> entry : minionRegions.entrySet()) {
            ConfigurationSection regionSection = section.createSection("minion-regions." + entry.getKey());
            entry.getValue().write(regionSection);
            regionSection.set("kind", minionRegionKind(entry.getKey()));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : lootRegions.entrySet()) {
            entry.getValue().write(section.createSection("loot-regions." + entry.getKey()));
        }
        for (TeamId team : TeamId.values()) {
            ConfigurationSection teamSection = section.createSection("bases." + team.key());
            for (Map.Entry<Integer, CuboidRegion> entry : bases(team).entrySet()) {
                entry.getValue().write(teamSection.createSection(String.valueOf(entry.getKey())));
            }
        }
        for (TeamId team : TeamId.values()) {
            Point spawn = spawns.get(team);
            if (spawn != null) {
                spawn.write(section.createSection("spawns." + team.key()));
            }
        }
        if (outpost != null) {
            outpost.write(section.createSection("outpost"));
        }
        if (bomb != null) {
            bomb.write(section.createSection("bomb"));
        }
        if (waitSpawn != null) {
            waitSpawn.write(section.createSection("wait-spawn"));
        }
        if (waitRegion != null) {
            waitRegion.write(section.createSection("wait-region"));
        }
        if (hasMinionSpawnSettings()) {
            ConfigurationSection spawnSection = section.createSection("minion-spawn");
            if (spawnIntervalSeconds != null) {
                spawnSection.set("interval-seconds", spawnIntervalSeconds);
            }
            if (spawnPerSpawn != null) {
                spawnSection.set("per-spawn", spawnPerSpawn);
            }
            if (spawnMaxAlive != null) {
                spawnSection.set("max-alive", spawnMaxAlive);
            }
        }
        // 战国点位：有内容才写，避免给非战国地图凭空加上一个空段
        if (!sengoku.isEmpty()) {
            sengoku.write(section.createSection("sengoku"));
        }
    }

    /** 战国 3v3 专用点位（非战国地图为空）。 */
    public com.taketori.kassen.paper.match.sengoku.SengokuMapDef sengoku() {
        return sengoku;
    }
}
