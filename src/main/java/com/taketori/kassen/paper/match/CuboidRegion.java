package com.taketori.kassen.paper.match;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 长方体区域：刷怪区、基地范围都用它表示。
 *
 * <p>坐标取两点的最小/最大值，所以用指令划定时不必关心先后顺序。
 * 可序列化进 arena.yml，服务器重启后仍然有效。</p>
 */
public final class CuboidRegion {

    private final String worldName;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public CuboidRegion(String worldName,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        this.worldName = worldName;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    public static CuboidRegion of(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return null;
        }
        if (!a.getWorld().getName().equals(b.getWorld().getName())) {
            return null;   // 跨世界选区不合法
        }
        return new CuboidRegion(a.getWorld().getName(),
                a.getX(), a.getY(), a.getZ(),
                b.getX(), b.getY(), b.getZ());
    }

    public String worldName() {
        return worldName;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public double minX() {
        return minX;
    }

    public double minY() {
        return minY;
    }

    public double minZ() {
        return minZ;
    }

    public double maxX() {
        return maxX;
    }

    public double maxY() {
        return maxY;
    }

    public double maxZ() {
        return maxZ;
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(worldName)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX - 0.5D && x <= maxX + 0.5D
                && y >= minY - 0.5D && y <= maxY + 1.5D
                && z >= minZ - 0.5D && z <= maxZ + 0.5D;
    }

    /** 区域内随机一点（用于生成小怪；Y 固定区域底部 +1，落点为方块中心）。 */
    public Location randomLocation() {
        World world = world();
        if (world == null) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Location(world,
                Math.floor(random.nextDouble(minX, maxX + 1.0D)) + 0.5D,
                minY + 1.0D,
                Math.floor(random.nextDouble(minZ, maxZ + 1.0D)) + 0.5D);
    }

    /**
     * 等待区安全落点：区域内随机整块，取该列最高可站方块的上一格。
     *
     * <p>与 {@link #randomLocation()} 的区别：Y 不再固定区域底部 +1（选区是平片时才碰巧正确，
     * 斜坡/高差/悬空选区会把人埋进方块或丢在半空），而是逐列取最高方块；液面/纯空列重试，
     * 多次失败回落区域中心。X/Z 为方块中心（+0.5），绝不越过区域边界。</p>
     */
    public Location randomStandLocation() {
        World world = world();
        if (world == null) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Block last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = (int) Math.floor(random.nextDouble(minX, maxX + 1.0D));
            int z = (int) Math.floor(random.nextDouble(minZ, maxZ + 1.0D));
            Block ground = world.getHighestBlockAt(x, z);
            last = ground;
            if (!ground.getType().isAir() && !ground.isLiquid()) {
                return new Location(world, x + 0.5D, ground.getY() + 1.0D, z + 0.5D);
            }
        }
        return last == null ? null : new Location(world, last.getX() + 0.5D, last.getY() + 1.0D, last.getZ() + 0.5D);
    }

    /** 区域中心（用于复活点、提示）。 */
    public Location center() {
        World world = world();
        if (world == null) {
            return null;
        }
        return new Location(world, (minX + maxX) / 2.0D + 0.5D, minY + 1.0D, (minZ + maxZ) / 2.0D + 0.5D);
    }

    public double volume() {
        return (maxX - minX + 1.0D) * (maxY - minY + 1.0D) * (maxZ - minZ + 1.0D);
    }

    /** 同一区域重定向到另一个世界（动态房间克隆模板定义用）。 */
    public CuboidRegion inWorld(String newWorldName) {
        return new CuboidRegion(newWorldName, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 区域中心点（世界名 + 坐标；PVE 据点回落位置用，不要求世界已加载）。 */
    public com.taketori.kassen.paper.match.ArenaDef.Point centerPoint() {
        return new com.taketori.kassen.paper.match.ArenaDef.Point(worldName,
                (minX + maxX) / 2.0D + 0.5D, minY + 1.0D, (minZ + maxZ) / 2.0D + 0.5D, 0.0F, 0.0F);
    }

    public String describe() {
        return String.format("%s [%.0f,%.0f,%.0f → %.0f,%.0f,%.0f]",
                worldName, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void write(ConfigurationSection section) {
        section.set("world", worldName);
        section.set("min.x", minX);
        section.set("min.y", minY);
        section.set("min.z", minZ);
        section.set("max.x", maxX);
        section.set("max.y", maxY);
        section.set("max.z", maxZ);
    }

    /**
     * 六个坐标键名；缺任何一个都不能读——旧实现缺键时 getDouble 静默返回 0，
     * 区域退化为以 (0,0,0) 为角点，并入 playBounds 后屏障墙会建到错误位置。
     */
    private static final List<String> REQUIRED_KEYS = List.of(
            "min.x", "min.y", "min.z", "max.x", "max.y", "max.z");

    public static CuboidRegion read(ConfigurationSection section) {
        if (section == null || !section.isString("world")) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            if (!section.isSet(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            org.bukkit.Bukkit.getLogger().warning("[CuboidRegion] 区域配置不完整，缺少坐标键 "
                    + missing + "（world=" + section.getString("world")
                    + "），已跳过该区域；请修正配置，避免退化到 (0,0,0)。");
            return null;
        }
        return new CuboidRegion(
                section.getString("world"),
                section.getDouble("min.x"), section.getDouble("min.y"), section.getDouble("min.z"),
                section.getDouble("max.x"), section.getDouble("max.y"), section.getDouble("max.z"));
    }
}
