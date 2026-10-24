package com.taketori.kassen.paper.setup;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * 选区锄的行为：两个角点的设置与提示、选区边框可视化。
 *
 * <p>为什么要有边框：划场地时最容易出错的是"以为选好了其实只点了一个点"或者
 * "选到了隔壁世界"。所以手持锄头时用粒子把选区描出来，只发给本人（不影响其他玩家）。</p>
 *
 * <p>与指令完全共用同一份选区数据（{@link ArenaManager} 的 pos1 / pos2），
 * 所以锄头点选与 <code>/taketori arena pos1|pos2</code> 可以混着用。</p>
 */
public final class SetupWandService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 边框粒子（已在 version 适配层验证可解析的名字）。 */
    private static final String OUTLINE_PARTICLE = "END_ROD";
    private static final String CORNER1_PARTICLE = "FLAME";
    private static final String CORNER2_PARTICLE = "SNOWFLAKE";

    /** 每条棱最多画多少个点，避免大选区把客户端刷爆。 */
    private static final double MAX_POINTS_PER_EDGE = 24.0D;

    private final TaketoriPlugin plugin;
    private final SetupWand wand;
    private BukkitTask outlineTask;

    public SetupWandService(TaketoriPlugin plugin, SetupWand wand) {
        this.plugin = plugin;
        this.wand = wand;
    }

    public SetupWand wand() {
        return wand;
    }

    public boolean enabled() {
        return wand.enabled();
    }

    public void start() {
        stop();
        // 每 10 tick（0.5 秒）刷一次边框：够跟手，开销可忽略（只发给手持锄头的管理员）
        outlineTask = plugin.scheduler().runTimerTask(this::tickOutline, 10L, 10L);
    }

    public void stop() {
        if (outlineTask != null) {
            outlineTask.cancel();
            outlineTask = null;
        }
    }

    // ---------------------------------------------------------------- 点选

    /** 左键：设角点 1。 */
    public void setFirst(Player player, Location location) {
        plugin.arena().setPos1(player.getUniqueId(), location);
        plugin.fx().sound("ENTITY_PLAYER_ATTACK_STRONG", player, 0.5F, 1.7F);
        mark(player, "角点 1", location);
        report(player);
    }

    /** 右键：设角点 2。 */
    public void setSecond(Player player, Location location) {
        plugin.arena().setPos2(player.getUniqueId(), location);
        plugin.fx().sound("ENTITY_PLAYER_ATTACK_STRONG", player, 0.5F, 1.0F);
        mark(player, "角点 2", location);
        report(player);
    }

    /** 潜行 + 左键：清空选区。 */
    public void clear(Player player) {
        plugin.arena().clearSelection(player.getUniqueId());
        plugin.fx().sound("BLOCK_ANVIL_LAND", player, 0.4F, 1.8F);
        player.sendActionBar(MINI.deserialize("<gray>选区已清空"));
        say(player, "<gray>选区已清空（左键点方块重新开始）。");
    }

    /** 潜行 + 右键：查看当前选区。 */
    public void describe(Player player) {
        report(player);
    }

    private void mark(Player player, String node, Location location) {
        player.sendActionBar(MINI.deserialize("<green>" + node + " <white>" + format(location)));
        say(player, "<green>" + node + " 已设置：<white>" + format(location));
        String existing = locate(player.getUniqueId(), location);
        if (existing != null) {
            say(player, "<yellow>注意：这个位置已经在 <white>" + existing + "</white> 的范围内。");
        }
    }

    private void report(Player player) {
        UUID uuid = player.getUniqueId();
        Location a = plugin.arena().pos1(uuid);
        Location b = plugin.arena().pos2(uuid);
        if (a == null && b == null) {
            say(player, "<gray>当前没有选区。左键点方块 = 角点 1，右键点方块 = 角点 2。");
            return;
        }
        if (a == null || b == null) {
            Location only = a != null ? a : b;
            String which = a != null ? "<red>角点 1</red>" : "<blue>角点 2</blue>";
            say(player, "<gray>已标记 <white>1</white>/2 个角点：" + which + " <white>" + format(only));
            say(player, "<dark_gray>把另一个角点也点上就完成选区了。");
            return;
        }
        CuboidRegion region = plugin.arena().selection(uuid);
        if (region == null) {
            say(player, "<red>两个角点不在同一世界，选区无效：<white>" + plugin.arena().selectionStatus(uuid));
            return;
        }
        say(player, "<green>选区完成：<white>" + region.describe());
        say(player, "<gray>尺寸 <white>" + size(region) + "</white>，体积 <white>"
                + (long) region.volume() + "</white> 格");
        say(player, "<gray>下一步：<white>/taketori arena setbase red 1</white> <dark_gray>（或 setminion / setspawn）");
    }

    // ---------------------------------------------------------------- 位置判定

    /**
     * 该位置是否已经落在某个已配置的区域里（防止把基地划到刷怪区上）。
     * 多房间版：只检查管理员当前选中的场地；没选中场地时不做重叠提示。
     */
    private String locate(UUID admin, Location location) {
        ArenaDef arena = plugin.arena().selected(admin);
        if (arena == null) {
            return null;
        }
        if (arena.minionRegion() != null && arena.minionRegion().contains(location)) {
            return "小怪刷新区";
        }
        for (TeamId team : TeamId.values()) {
            for (int i = 1; i <= arena.baseIndexCeiling(); i++) {
                CuboidRegion region = arena.base(team, i);
                if (region != null && region.contains(location)) {
                    return team.display() + " 基地 #" + i;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 边框可视化

    private void tickOutline() {
        if (!enabled() || !plugin.config().setupWandOutline() || !plugin.config().particles()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("taketori.admin") || !wand.holdingAny(player)) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            CuboidRegion region = plugin.arena().selection(uuid);
            if (region != null) {
                drawOutline(player, region);
                continue;
            }
            Location a = plugin.arena().pos1(uuid);
            Location b = plugin.arena().pos2(uuid);
            if (a != null) {
                sparkle(player, a, CORNER1_PARTICLE);
            }
            if (b != null) {
                sparkle(player, b, CORNER2_PARTICLE);
            }
        }
    }

    private void drawOutline(Player player, CuboidRegion region) {
        World world = region.world();
        if (world == null) {
            return;
        }
        Particle particle = plugin.versions().particle(OUTLINE_PARTICLE);
        if (particle == null) {
            return;
        }
        double minX = region.minX() - 0.5D;
        double minY = region.minY();
        double minZ = region.minZ() - 0.5D;
        double maxX = region.maxX() + 0.5D;
        double maxY = region.maxY() + 1.0D;
        double maxZ = region.maxZ() + 0.5D;

        double longest = Math.max(Math.max(maxX - minX, maxZ - minZ), maxY - minY);
        double step = Math.max(1.0D, longest / MAX_POINTS_PER_EDGE);

        for (double x = minX; x <= maxX; x += step) {
            sparkle(player, world, particle, x, minY, minZ);
            sparkle(player, world, particle, x, minY, maxZ);
            sparkle(player, world, particle, x, maxY, minZ);
            sparkle(player, world, particle, x, maxY, maxZ);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            sparkle(player, world, particle, minX, minY, z);
            sparkle(player, world, particle, maxX, minY, z);
            sparkle(player, world, particle, minX, maxY, z);
            sparkle(player, world, particle, maxX, maxY, z);
        }
        for (double y = minY; y <= maxY; y += step) {
            sparkle(player, world, particle, minX, y, minZ);
            sparkle(player, world, particle, maxX, y, minZ);
            sparkle(player, world, particle, minX, y, maxZ);
            sparkle(player, world, particle, maxX, y, maxZ);
        }
    }

    private void sparkle(Player player, World world, Particle particle, double x, double y, double z) {
        player.spawnParticle(particle, new Location(world, x, y, z), 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private void sparkle(Player player, Location location, String particleName) {
        Particle particle = plugin.versions().particle(particleName);
        if (particle == null) {
            return;
        }
        player.spawnParticle(particle, location.clone().add(0.5D, 0.5D, 0.5D), 4, 0.25D, 0.25D, 0.25D, 0.0D);
    }

    // ---------------------------------------------------------------- 小工具

    private void say(Player player, String miniMessage) {
        player.sendMessage(MINI.deserialize(miniMessage));
    }

    private String format(Location location) {
        return String.format("%s %.0f,%.0f,%.0f",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    private String size(CuboidRegion region) {
        return String.format("%.0f×%.0f×%.0f",
                region.maxX() - region.minX() + 1.0D,
                region.maxY() - region.minY() + 1.0D,
                region.maxZ() - region.minZ() + 1.0D);
    }
}
