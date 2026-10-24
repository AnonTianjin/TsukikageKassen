package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.paper.match.CuboidRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对局区域屏障墙：在场地活动范围（{@link ArenaDef#playBounds()}）的四周围上
 * 从世界最低点到最高点的 {@code BARRIER}（屏障）方块，防止外人闯入、也防止玩家
 * 跑出对局区域。对局结束时原样还原。
 *
 * <p>与 {@link CageBuilder} 同模式：放置前保存原始 BlockData 快照，还原时只写回
 * "事后没被改动过"的格子。屏障本身是原版不可破坏方块（生存下敲不掉），但为防
 * 创造/管理员误操作仍走快照还原。</p>
 *
 * <p>只立四面墙（x=min、x=max、z=min、z=max），不封顶不封底——天花板与地底
 * 由世界边界自然限制，且不挡玩家在区域内上下活动。</p>
 */
public final class BarrierBuilder {

    private final GameRoom room;

    /** 位置 → 放置前的原始方块（只存第一份快照）。 */
    private final Map<Location, BlockData> snapshots = new HashMap<>();
    /** 位置 → 本插件放置后的屏障方块（还原时比对用）。 */
    private final Map<Location, BlockData> placed = new LinkedHashMap<>();

    public BarrierBuilder(GameRoom room) {
        this.room = room;
    }

    /**
     * 在场地活动范围四周立屏障墙。
     *
     * @return 实际放置的方块数；场地无活动范围或世界缺失时返回 0
     */
    public int build() {
        restore();
        CuboidRegion bounds = room.arena().playBounds();
        if (bounds == null) {
            return 0;
        }
        World world = bounds.world();
        if (world == null) {
            return 0;
        }
        int minX = (int) Math.floor(bounds.minX());
        int maxX = (int) Math.floor(bounds.maxX());
        int minZ = (int) Math.floor(bounds.minZ());
        int maxZ = (int) Math.floor(bounds.maxZ());
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        int placedCount = 0;
        Material barrier = Material.BARRIER;
        // x = minX 与 x = maxX 两面墙
        for (int x : new int[]{minX, maxX}) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    placedCount += place(world, x, y, z, barrier);
                }
            }
        }
        // z = minZ 与 z = maxZ 两面墙（x 在内侧走，避免四角重复放）
        for (int z : new int[]{minZ, maxZ}) {
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int y = minY; y <= maxY; y++) {
                    placedCount += place(world, x, y, z, barrier);
                }
            }
        }
        if (room.plugin().config().debug()) {
            room.plugin().getLogger().info("[room " + room.id() + "] 区域屏障墙已生成："
                    + placedCount + " 格（活动范围 " + minX + "," + minZ + " → " + maxX + "," + maxZ
                    + "，高度 " + minY + "~" + maxY + "）");
        }
        return placedCount;
    }

    /** 放置一格屏障：保存快照 → 写入材质。返回是否实际写入。 */
    private int place(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        Location at = block.getLocation();
        snapshots.putIfAbsent(at, block.getBlockData().clone());
        if (block.getType() == material) {
            placed.put(at, block.getBlockData().clone());
            return 0;
        }
        block.setType(material, false);
        placed.put(at, block.getBlockData().clone());
        return 1;
    }

    /**
     * 还原全部屏障墙：只处理"当前仍是本插件放置方块"的格子。
     * 还原后清空清单（可安全再次 build）。幂等。
     */
    public void restore() {
        for (Map.Entry<Location, BlockData> entry : placed.entrySet()) {
            Location at = entry.getKey();
            if (at.getWorld() == null) {
                continue;
            }
            Block block = at.getBlock();
            if (!block.getBlockData().matches(entry.getValue())) {
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

    /** 某方块是否属于当前已放置的屏障墙（防破坏判定用）。 */
    public boolean isBarrierBlock(Location location) {
        return location != null && placed.containsKey(location.getBlock().getLocation());
    }
}
