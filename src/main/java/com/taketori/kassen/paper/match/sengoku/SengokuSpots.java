package com.taketori.kassen.paper.match.sengoku;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * 战国模式的落点校正。
 *
 * <p>三处生成都需要它：箭楼守卫、大将击破器、跳跃台落点。它们的坐标都来自
 * 「点位 + 随机偏移」或「区域中心」，直接生成会有三种翻车方式——</p>
 * <ul>
 *   <li>生成在实心方块里：守卫窒息、掉落物卡住捡不到；</li>
 *   <li>生成悬空：掉落物掉进下方缝隙、守卫摔伤；</li>
 *   <li>生成在岩浆/水里：掉落物被销毁（击破器不可破坏也救不回已被原版清掉的实体）。</li>
 * </ul>
 *
 * <p>校正策略：先在原始高度上下各找 4 格，取第一个"脚可站、头可容、脚下有实心支撑、
 * 且不是岩浆或水"的位置；找不到再回退到地表（{@code getHighestBlockYAt}）。
 * 先上下找而不是直接用地表，是因为室内场地（天守阁内部、箭楼塔内）的地表是屋顶。</p>
 */
public final class SengokuSpots {

    /** 上下搜索的最大格数。 */
    private static final int SEARCH_RANGE = 4;

    private SengokuSpots() {
    }

    /**
     * 把落点校正到可站立的位置。
     *
     * @return 校正后的位置；入参为 null 或世界未加载时原样返回
     */
    public static Location onGround(Location location) {
        if (location == null || location.getWorld() == null) {
            return location;
        }
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int startY = location.getBlockY();

        for (int offset = 0; offset <= SEARCH_RANGE; offset++) {
            // 先试向上（被埋在地下时更常见），再试向下（悬空时）
            for (int sign : new int[]{1, -1}) {
                int y = startY + offset * sign;
                if (offset == 0 && sign == -1) {
                    continue;   // 0 偏移只算一次
                }
                if (isStandable(world, x, y, z)) {
                    return at(world, x, y, z, location);
                }
            }
        }
        int surface = world.getHighestBlockYAt(x, z) + 1;
        return at(world, x, surface, z, location);
    }

    /** 脚两格可通行、脚下有实心支撑，且不踩在岩浆或水里。 */
    private static boolean isStandable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (feet.getType() == Material.LAVA || feet.getType() == Material.WATER) {
            return false;
        }
        return below.getType().isSolid();
    }

    /** 方块坐标 → 方块中心的精确位置（保留原来的朝向）。 */
    private static Location at(World world, int x, int y, int z, Location source) {
        return new Location(world, x + 0.5D, y, z + 0.5D, source.getYaw(), source.getPitch());
    }
}
