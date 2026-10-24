package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.CuboidRegion;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战国 3v3 的专用点位集合，读写 {@code arenas.yml} 的 {@code arenas.<id>.sengoku} 段。
 *
 * <p><b>为什么独立成类</b>：{@link ArenaDef} 已经 780 多行、同时服务 PVP 与 PVE 两条线。
 * 战国点位只被新模式读，塞进去会让另外两条线也跟着变大变脆。{@code ArenaDef} 只持有本类的一个
 * 实例并转发 {@code read / write / copyForWorld}，改动面压到最小。</p>
 *
 * <p>点位一律用 {@link ArenaDef.Point}（世界名 + 坐标）与 {@link CuboidRegion} 保存，
 * 与既有场地定义同构：模板编辑世界卸载后数据不丢，克隆到房间世界时统一 {@code inWorld} 重定向。</p>
 *
 * <p>整段缺失<b>不是错误</b>——非战国地图本来就没有这一段。判"这份地图能不能开战国局"
 * 用 {@link #isReady(int)}，它只看与需求相关的点位。</p>
 */
public final class SengokuMapDef {

    /** 箭楼编号上限：再多就该重新设计地图了，也给遍历与提示封个顶。 */
    public static final int MAX_TOWERS = 8;
    /** 中地小兵刷新区上限。 */
    public static final int MAX_MID_MINION_REGIONS = 16;

    /** 每队天守阁区域。 */
    private final Map<TeamId, CuboidRegion> keeps = new EnumMap<>(TeamId.class);
    /** 每队天守阁"门前"点：击破器与跳跃台都生成在这里。 */
    private final Map<TeamId, ArenaDef.Point> keepDoors = new EnumMap<>(TeamId.class);
    /** 箭楼占领区：编号 → 区域（1 起，上 / 下路）。 */
    private final Map<Integer, CuboidRegion> towers = new LinkedHashMap<>();
    /** 箭楼铜钟位置：编号 → 点。 */
    private final Map<Integer, ArenaDef.Point> bells = new LinkedHashMap<>();
    /** 箭楼守卫刷新点：编号 → 点（牛鬼与虾兵共用，落点偏移由刷新逻辑决定）。 */
    private final Map<Integer, ArenaDef.Point> guardSpawns = new LinkedHashMap<>();
    /** 中地小兵刷新区：编号 → 区域。 */
    private final Map<Integer, CuboidRegion> midMinionRegions = new LinkedHashMap<>();
    /** 每队跳跃台方块位置。 */
    private final Map<TeamId, ArenaDef.Point> jumpPads = new EnumMap<>(TeamId.class);

    private final TaketoriPlugin plugin;
    private final String arenaId;

    public SengokuMapDef(TaketoriPlugin plugin, String arenaId) {
        this.plugin = plugin;
        this.arenaId = arenaId;
    }

    // ---------------------------------------------------------------- 读取 / 写出

    /** 从配置段读入；整段缺失时清空后直接返回（非战国地图的正常情况）。 */
    public void read(ConfigurationSection section) {
        clear();
        if (section == null) {
            return;
        }

        ConfigurationSection keepSection = section.getConfigurationSection("keeps");
        if (keepSection != null) {
            for (TeamId team : TeamId.values()) {
                ConfigurationSection teamSection = keepSection.getConfigurationSection(team.key());
                if (teamSection == null) {
                    continue;
                }
                CuboidRegion region = CuboidRegion.read(teamSection.getConfigurationSection("region"));
                if (region != null) {
                    keeps.put(team, region);
                }
                ArenaDef.Point door = ArenaDef.Point.read(teamSection.getConfigurationSection("door"));
                if (door != null) {
                    keepDoors.put(team, door);
                }
            }
        }

        ConfigurationSection towerSection = section.getConfigurationSection("towers");
        if (towerSection != null) {
            for (String key : towerSection.getKeys(false)) {
                int index = parseIndex(key, MAX_TOWERS, "箭楼");
                if (index < 0) {
                    continue;
                }
                ConfigurationSection one = towerSection.getConfigurationSection(key);
                if (one == null) {
                    continue;
                }
                CuboidRegion region = CuboidRegion.read(one.getConfigurationSection("region"));
                if (region != null) {
                    towers.put(index, region);
                }
                ArenaDef.Point bell = ArenaDef.Point.read(one.getConfigurationSection("bell"));
                if (bell != null) {
                    bells.put(index, bell);
                }
                ArenaDef.Point guard = ArenaDef.Point.read(one.getConfigurationSection("guard-spawn"));
                if (guard != null) {
                    guardSpawns.put(index, guard);
                }
            }
        }

        readMidMinions(section.getConfigurationSection("mid-minion-regions"));

        ConfigurationSection padSection = section.getConfigurationSection("jump-pads");
        if (padSection != null) {
            for (TeamId team : TeamId.values()) {
                ArenaDef.Point pad = ArenaDef.Point.read(padSection.getConfigurationSection(team.key()));
                if (pad != null) {
                    jumpPads.put(team, pad);
                }
            }
        }
    }

    private void readMidMinions(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            int index = parseIndex(key, MAX_MID_MINION_REGIONS, "中地小兵刷新区");
            if (index < 0) {
                continue;
            }
            CuboidRegion region = CuboidRegion.read(section.getConfigurationSection(key));
            if (region != null) {
                midMinionRegions.put(index, region);
            }
        }
    }

    /** 编号非法时点名并返回 -1（静默忽略会让"划了但没生效"极难排查）。 */
    private int parseIndex(String key, int max, String what) {
        try {
            int index = Integer.parseInt(key);
            if (index < 1 || index > max) {
                plugin.getLogger().warning("场地 [" + arenaId + "] 的" + what + "编号超出范围 1.." + max
                        + "： " + key + "（已忽略）");
                return -1;
            }
            return index;
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("场地 [" + arenaId + "] 的" + what + "编号非法： " + key + "（已忽略）");
            return -1;
        }
    }

    /** 写出到配置段（{@code ArenaDef.write} 负责创建 {@code sengoku} 子段）。 */
    public void write(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection keepSection = section.createSection("keeps");
        for (TeamId team : TeamId.values()) {
            CuboidRegion region = keeps.get(team);
            ArenaDef.Point door = keepDoors.get(team);
            if (region == null && door == null) {
                continue;
            }
            ConfigurationSection teamSection = keepSection.createSection(team.key());
            if (region != null) {
                region.write(teamSection.createSection("region"));
            }
            if (door != null) {
                door.write(teamSection.createSection("door"));
            }
        }
        if (!towers.isEmpty() || !bells.isEmpty() || !guardSpawns.isEmpty()) {
            ConfigurationSection towerSection = section.createSection("towers");
            for (Integer index : towerIndexes()) {
                ConfigurationSection one = towerSection.createSection(String.valueOf(index));
                CuboidRegion region = towers.get(index);
                if (region != null) {
                    region.write(one.createSection("region"));
                }
                ArenaDef.Point bell = bells.get(index);
                if (bell != null) {
                    bell.write(one.createSection("bell"));
                }
                ArenaDef.Point guard = guardSpawns.get(index);
                if (guard != null) {
                    guard.write(one.createSection("guard-spawn"));
                }
            }
        }
        if (!midMinionRegions.isEmpty()) {
            ConfigurationSection midSection = section.createSection("mid-minion-regions");
            for (Map.Entry<Integer, CuboidRegion> entry : midMinionRegions.entrySet()) {
                entry.getValue().write(midSection.createSection(String.valueOf(entry.getKey())));
            }
        }
        if (!jumpPads.isEmpty()) {
            ConfigurationSection padSection = section.createSection("jump-pads");
            for (Map.Entry<TeamId, ArenaDef.Point> entry : jumpPads.entrySet()) {
                entry.getValue().write(padSection.createSection(entry.getKey().key()));
            }
        }
    }

    // ---------------------------------------------------------------- 克隆到房间世界

    /** 全部点位重定向到房间世界；结果不持久化。 */
    public SengokuMapDef copyForWorld(String worldName) {
        SengokuMapDef copy = new SengokuMapDef(plugin, arenaId);
        for (Map.Entry<TeamId, CuboidRegion> entry : keeps.entrySet()) {
            copy.keeps.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<TeamId, ArenaDef.Point> entry : keepDoors.entrySet()) {
            copy.keepDoors.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : towers.entrySet()) {
            copy.towers.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<Integer, ArenaDef.Point> entry : bells.entrySet()) {
            copy.bells.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<Integer, ArenaDef.Point> entry : guardSpawns.entrySet()) {
            copy.guardSpawns.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : midMinionRegions.entrySet()) {
            copy.midMinionRegions.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        for (Map.Entry<TeamId, ArenaDef.Point> entry : jumpPads.entrySet()) {
            copy.jumpPads.put(entry.getKey(), entry.getValue().inWorld(worldName));
        }
        return copy;
    }

    /**
     * 用另一份内容整体替换本实例。
     *
     * <p>{@code ArenaDef} 把本类持有为 final 字段，{@code copyForWorld} 无法直接换引用，
     * 于是克隆出新对象后灌回来。做成整体替换而不是逐项合并，是为了避免"房间世界点位与
     * 模板世界点位混在一起"这种最难查的脏数据。</p>
     */
    public void copyFrom(SengokuMapDef other) {
        clear();
        if (other == null) {
            return;
        }
        keeps.putAll(other.keeps);
        keepDoors.putAll(other.keepDoors);
        towers.putAll(other.towers);
        bells.putAll(other.bells);
        guardSpawns.putAll(other.guardSpawns);
        midMinionRegions.putAll(other.midMinionRegions);
        jumpPads.putAll(other.jumpPads);
    }

    // ---------------------------------------------------------------- 就绪判定
    /**
     * 这份地图能不能开战国局。
     *
     * @param requiredTowers 配置要求的箭楼数量（{@code sengoku-towers.yml} 的 {@code towers.count}）
     * @return 缺少任一项即 false；用 {@link #missingHint(int)} 看具体缺什么
     */
    public boolean isReady(int requiredTowers) {
        return missingHint(requiredTowers).isEmpty();
    }

    /** 缺失项清单（空 = 齐全），用于 {@code /taketori doctor} 与开局前拦人。 */
    public String missingHint(int requiredTowers) {
        List<String> missing = new ArrayList<>();
        for (TeamId team : TeamId.values()) {
            if (!keeps.containsKey(team)) {
                missing.add(team.display() + "天守阁");
            }
            if (!keepDoors.containsKey(team)) {
                missing.add(team.display() + "天守阁门前");
            }
            if (!jumpPads.containsKey(team)) {
                missing.add(team.display() + "跳跃台");
            }
        }
        int want = Math.max(1, requiredTowers);
        for (int index = 1; index <= want; index++) {
            if (!towers.containsKey(index)) {
                missing.add("箭楼 #" + index + " 占领区");
            }
            if (!bells.containsKey(index)) {
                missing.add("箭楼 #" + index + " 铜钟");
            }
            if (!guardSpawns.containsKey(index)) {
                missing.add("箭楼 #" + index + " 守卫刷新点");
            }
        }
        if (midMinionRegions.isEmpty()) {
            missing.add("中地小兵刷新区");
        }
        return missing.isEmpty() ? "" : String.join("、", missing);
    }

    public boolean isEmpty() {
        return keeps.isEmpty() && towers.isEmpty() && midMinionRegions.isEmpty() && jumpPads.isEmpty();
    }

    /**
     * 全部<b>区域</b>（天守阁 / 箭楼占领区 / 中地小兵刷新区）。
     *
     * <p>供 {@code ArenaDef.regions()} 并进"对局区域"用——那里拼出的范围决定了
     * 「对局中禁止破坏与放置」的覆盖面，以及结算时清理掉落物的范围。
     * 不含点位类（门前 / 铜钟 / 守卫点 / 跳跃台）：它们不是区域，无法用于包含判定。</p>
     */
    public List<CuboidRegion> regions() {
        List<CuboidRegion> result = new ArrayList<>(keeps.values());
        result.addAll(towers.values());
        result.addAll(midMinionRegions.values());
        return result;
    }

    public void clear() {
        keeps.clear();
        keepDoors.clear();
        towers.clear();
        bells.clear();
        guardSpawns.clear();
        midMinionRegions.clear();
        jumpPads.clear();
    }

    /** 箭楼编号的升序列表（写回配置时用，保证 yml 顺序稳定）。 */
    private List<Integer> towerIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (Integer index : towers.keySet()) {
            if (!indexes.contains(index)) {
                indexes.add(index);
            }
        }
        for (Integer index : bells.keySet()) {
            if (!indexes.contains(index)) {
                indexes.add(index);
            }
        }
        for (Integer index : guardSpawns.keySet()) {
            if (!indexes.contains(index)) {
                indexes.add(index);
            }
        }
        indexes.sort(Integer::compareTo);
        return indexes;
    }

    // ---------------------------------------------------------------- 访问器

    public Map<TeamId, CuboidRegion> keeps() {
        return keeps;
    }

    public CuboidRegion keep(TeamId team) {
        return keeps.get(team);
    }

    public void setKeep(TeamId team, CuboidRegion region) {
        if (team != null && region != null) {
            keeps.put(team, region);
        }
    }

    public Map<TeamId, ArenaDef.Point> keepDoors() {
        return keepDoors;
    }

    public ArenaDef.Point keepDoor(TeamId team) {
        return keepDoors.get(team);
    }

    public void setKeepDoor(TeamId team, ArenaDef.Point point) {
        if (team != null && point != null) {
            keepDoors.put(team, point);
        }
    }

    public Map<Integer, CuboidRegion> towers() {
        return towers;
    }

    public CuboidRegion tower(int index) {
        return towers.get(index);
    }

    public void setTower(int index, CuboidRegion region, ArenaDef.Point bell, ArenaDef.Point guardSpawn) {
        int safe = Math.max(1, Math.min(MAX_TOWERS, index));
        if (region != null) {
            towers.put(safe, region);
        }
        if (bell != null) {
            bells.put(safe, bell);
        }
        if (guardSpawn != null) {
            guardSpawns.put(safe, guardSpawn);
        }
    }

    public int towerCount() {
        return towers.size();
    }

    public ArenaDef.Point bell(int index) {
        return bells.get(index);
    }

    public ArenaDef.Point guardSpawn(int index) {
        return guardSpawns.get(index);
    }

    public Map<Integer, CuboidRegion> midMinionRegions() {
        return midMinionRegions;
    }

    public void setMidMinionRegion(int index, CuboidRegion region) {
        if (region == null) {
            return;
        }
        int safe = Math.max(1, Math.min(MAX_MID_MINION_REGIONS, index));
        midMinionRegions.put(safe, region);
    }

    /** 下一个还没配置的中地小兵刷新区编号（满了返回 -1）。 */
    public int nextFreeMidMinionIndex() {
        for (int i = 1; i <= MAX_MID_MINION_REGIONS; i++) {
            if (!midMinionRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    public Map<TeamId, ArenaDef.Point> jumpPads() {
        return jumpPads;
    }

    public ArenaDef.Point jumpPad(TeamId team) {
        return jumpPads.get(team);
    }

    // ---------------------------------------------------------------- 清除（画错了要能改）

    /** 清掉某队天守阁区域。 */
    public boolean clearKeep(TeamId team) {
        return team != null && keeps.remove(team) != null;
    }

    /** 清掉某队天守阁门前点（击破器与跳跃台的生成依据）。 */
    public boolean clearKeepDoor(TeamId team) {
        return team != null && keepDoors.remove(team) != null;
    }

    /**
     * 清掉整座箭楼：占领区 + 铜钟 + 守卫点。
     *
     * <p>三件是"同一座箭楼"的三个字段，只清其中一件会留下孤儿数据
     * （例如铜钟还在但占领区没了，敲钟后会读到一张空表）。</p>
     */
    public boolean clearTower(int index) {
        boolean removed = towers.remove(index) != null;
        removed |= bells.remove(index) != null;
        removed |= guardSpawns.remove(index) != null;
        return removed;
    }

    /** 只清铜钟（想换一个钟的位置，但保留占领区与守卫点）。 */
    public boolean clearBell(int index) {
        return bells.remove(index) != null;
    }

    /** 只清守卫刷新点。 */
    public boolean clearGuardSpawn(int index) {
        return guardSpawns.remove(index) != null;
    }

    public boolean clearMidMinionRegion(int index) {
        return midMinionRegions.remove(index) != null;
    }

    public boolean clearJumpPad(TeamId team) {
        return team != null && jumpPads.remove(team) != null;
    }

    public void setJumpPad(TeamId team, ArenaDef.Point point) {
        if (team != null && point != null) {
            jumpPads.put(team, point);
        }
    }

    /** 日志用摘要。 */
    public String describe() {
        return "天守阁 " + keeps.size() + "/2、箭楼 " + towers.size()
                + "、中地小兵区 " + midMinionRegions.size() + "、跳跃台 " + jumpPads.size() + "/2";
    }
}
