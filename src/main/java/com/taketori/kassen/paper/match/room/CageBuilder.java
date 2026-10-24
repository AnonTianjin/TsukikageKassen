package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 出生点玻璃笼：开局瞬间在各队出生点生成封闭笼，{@code waiting.cage-hold-seconds}
 * 秒后原样还原。BedWars 式"进笼集结 → 冻结倒计时 → 同时开战"。
 *
 * <p>结构（以出生点脚部方块为中心，外径 3×3，内部空腔 1×1）：</p>
 * <ul>
 *   <li>底板：中心 y-1 的 3×3（防止开局前挖下去）；</li>
 *   <li>围墙：中心 y / y+1 两层的外圈（每层 8 格），中心 1×1 留空站人；</li>
 *   <li>顶盖：中心 y+2 的 3×3（防止叠人/跳出）。</li>
 * </ul>
 *
 * <p><b>只还原自己放出去且事后未被改动的方块</b>：放置前保存原始 BlockData
 * 快照；还原时逐格比对，当前方块仍是本插件放置的材质才写回快照，玩家/其他插件
 * 改动过的格子保持现状。多个出生点的笼子区域重叠时，快照只取第一次。</p>
 */
public final class CageBuilder {

    private final GameRoom room;

    public CageBuilder(GameRoom room) {
        this.room = room;
    }

    /** 位置 → 放置前的原始方块（只存第一份快照）。 */
    private final Map<Location, BlockData> snapshots = new HashMap<>();
    /** 位置 → 本插件放置后的方块（还原时用于"事后是否被改动"比对）。 */
    private final Map<Location, BlockData> placed = new LinkedHashMap<>();

    // 注意：CageBuilder 是 GameRoom 的字段初始化器，构造那一刻 room 的 plugin 字段
    // 还没在构造器体里赋值，所以这里不能缓存 room.plugin()，用时再取。
    private TaketoriPlugin plugin() {
        return room.plugin();
    }

    /**
     * 在各出生点建笼（同一坐标的多个出生点只建一次）。
     *
     * @return 实际放置的方块数；世界缺失等异常时可能为 0
     */
    public int build(Collection<Location> spawns) {
        restore();
        Material material = cageMaterial();
        Set<Location> centers = new LinkedHashSet<>();
        for (Location spawn : spawns) {
            if (spawn == null || spawn.getWorld() == null) {
                continue;
            }
            centers.add(spawn.getBlock().getLocation());
        }
        int placedCount = 0;
        for (Location center : centers) {
            // 底板 y-1：3×3
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    placedCount += place(center.clone().add(dx, -1, dz), material);
                }
            }
            // 围墙 y / y+1：只放外圈，内部 1×1 留空
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        placedCount += place(center.clone().add(dx, dy, dz), material);
                    }
                }
            }
            // 顶盖 y+2：3×3
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    placedCount += place(center.clone().add(dx, 2, dz), material);
                }
            }
        }
        if (plugin().config().debug()) {
            plugin().getLogger().info("[room " + room.id() + "] 玻璃笼已生成：" + centers.size()
                    + " 个出生点，" + placedCount + " 格（材质 " + material.name() + "）");
        }
        return placedCount;
    }

    /** 放置一格：保存快照 → 写入材质（不触发物理更新）。返回是否实际写入。 */
    private int place(Location at, Material material) {
        Block block = at.getBlock();
        snapshots.putIfAbsent(at, block.getBlockData().clone());
        if (block.getType() == material) {
            // 原始方块就是该材质：登记进 placed，还原时写回同样的快照（视觉无变化）
            placed.put(at, block.getBlockData().clone());
            return 0;
        }
        block.setType(material, false);
        placed.put(at, block.getBlockData().clone());
        return 1;
    }

    /**
     * 还原全部笼子：只处理"当前仍是本插件放置方块"的格子。
     * 还原后清空清单（可安全再次 build）。幂等，重复调用无副作用。
     */
    public void restore() {
        for (Map.Entry<Location, BlockData> entry : placed.entrySet()) {
            Location at = entry.getKey();
            if (at.getWorld() == null) {
                continue;
            }
            Block block = at.getBlock();
            BlockData placedData = entry.getValue();
            // 事后被玩家/其他插件改过就不动它
            if (!block.getBlockData().matches(placedData)) {
                continue;
            }
            BlockData original = snapshots.get(at);
            if (original != null) {
                block.setBlockData(original.clone(), false);
            }
        }
        clear();
    }

    /** 清空快照与放置清单（不还原世界；正常应走 {@link #restore()}）。 */
    public void clear() {
        snapshots.clear();
        placed.clear();
    }

    public boolean isEmpty() {
        return placed.isEmpty();
    }

    /**
     * 某方块是否属于当前已放置的笼子（等待区保护监听器用：CAGED 期间任何人
     * 都不得破坏笼方块）。只按方块坐标比对，与世界对象实例无关。
     */
    public boolean isCageBlock(Location location) {
        return location != null && placed.containsKey(location.getBlock().getLocation());
    }

    /** 解析配置材质：非方块 / 空气 / 非法名一律回落 GLASS。 */
    private Material cageMaterial() {
        String name = plugin().config().waitingCageMaterial();
        Material material = Material.matchMaterial(name == null ? "GLASS" : name.trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null || !material.isBlock() || material.isAir()) {
            if (name != null && !"GLASS".equalsIgnoreCase(name.trim()) && plugin().config().debug()) {
                plugin().getLogger().warning("[room " + room.id() + "] waiting.cage-material 非法：" + name + "，回落 GLASS");
            }
            return Material.GLASS;
        }
        return material;
    }
}
