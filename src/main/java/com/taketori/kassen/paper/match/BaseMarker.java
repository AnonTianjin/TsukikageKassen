package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;

/**
 * 基地标记：用各队颜色的粒子把双方基地圈出来，让玩家在场上一眼看清攻防目标。
 *
 * <p>基地既是攻防目标也是地图参照物，光靠 ActionBar 的拆除读条不足以建立方位感。
 * 本组件每秒在每个<b>尚未被拆</b>的基地顶面描一圈方框，并在中心点一根短立柱——
 * 远处看柱、近处看框。</p>
 *
 * <p>已拆除的基地立即停画：「标记消失」本身就是最直观的拆除反馈，不必再额外做一套灰色状态。
 * PVE 没有拆基地玩法，所以只在 PVP 对局里启动（由 {@code GameRoom#startPlay} 决定）。</p>
 *
 * <p><b>不要缓存 {@code room.plugin()}：</b>本组件是 {@code GameRoom} 的字段初始化器，
 * 构造那一刻 {@code GameRoom.plugin} 还没在构造器体里赋值（与 {@code CageBuilder} 同一个坑），
 * 所以一律用时再取。</p>
 */
public final class BaseMarker {

    /** 刷新间隔（tick）：1 秒。粒子只是提示，1 秒足够，再密只是白刷数据包。 */
    private static final long INTERVAL_TICKS = 20L;
    /** 单个基地每轮最多撒的边框点数（四条边合计，含端点）；立柱另算。 */
    private static final int MAX_EDGE_POINTS = 44;
    /** 边框离基地顶面（方块上表面）的高度。 */
    private static final double EDGE_HEIGHT = 0.35D;
    /** 中心立柱高度（格）。 */
    private static final int PILLAR_HEIGHT = 4;
    /** 粉尘粒子大小（1.0 为原版默认）。 */
    private static final float DUST_SIZE = 1.0F;

    private final GameRoom room;
    private BukkitTask task;

    public BaseMarker(GameRoom room) {
        this.room = room;
    }

    /** 用时再取：字段初始化器阶段 {@code GameRoom.plugin} 还是 null（见类注释）。 */
    private TaketoriPlugin plugin() {
        return room.plugin();
    }

    public void start() {
        stop();
        if (plugin().versions().particle("DUST") == null) {
            // 没有可着色的粉尘粒子就区分不出队伍，宁可什么都不画
            plugin().getLogger().warning("[marker] 当前服务端解析不到 DUST 粒子，基地标记已跳过");
            return;
        }
        task = plugin().scheduler().runTimerTask(this::tick, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!room.isRunning() || !plugin().config().particles()) {
            return;
        }
        Particle dust = plugin().versions().particle("DUST");
        if (dust == null) {
            return;
        }
        for (TeamId team : TeamId.values()) {
            Particle.DustOptions options = new Particle.DustOptions(colorOf(team), DUST_SIZE);
            for (Map.Entry<Integer, CuboidRegion> entry : room.arena().bases(team).entrySet()) {
                if (room.baseCapture().isCaptured(team, entry.getKey())) {
                    continue;   // 已拆除：不再标记
                }
                draw(entry.getValue(), dust, options);
            }
        }
    }

    /** 顶面描边 + 中心立柱。 */
    private void draw(CuboidRegion region, Particle dust, Particle.DustOptions options) {
        World world = region.world();
        if (world == null) {
            return;
        }
        // maxY 是顶层「方块」坐标，它的上表面在 maxY+1：粒子必须浮在顶面之上，
        // 放在 maxY+0.35 会埋进顶层方块里被挡掉（实心顶层时整圈框都看不见）。
        double y = region.maxY() + 1.0D + EDGE_HEIGHT;
        // 选区记的是方块坐标，粒子放 +0.5 才落在方块正中
        double minX = region.minX() + 0.5D;
        double maxX = region.maxX() + 0.5D;
        double minZ = region.minZ() + 0.5D;
        double maxZ = region.maxZ() + 0.5D;

        // 每条边的段数按「四条边合计不超过 MAX_EDGE_POINTS」自适应：大选区自动变稀疏，不会刷爆
        double perEdge = Math.max(1.0D, (MAX_EDGE_POINTS - PILLAR_HEIGHT) / 4.0D);
        double stepX = Math.max(1.0D, (maxX - minX) / perEdge);
        double stepZ = Math.max(1.0D, (maxZ - minZ) / perEdge);

        for (double x = minX; x <= maxX + 0.001D; x += stepX) {
            point(world, dust, options, x, y, minZ);
            point(world, dust, options, x, y, maxZ);
        }
        for (double z = minZ; z <= maxZ + 0.001D; z += stepZ) {
            point(world, dust, options, minX, y, z);
            point(world, dust, options, maxX, y, z);
        }

        Location center = region.center();
        if (center != null) {
            for (int i = 0; i < PILLAR_HEIGHT; i++) {
                point(world, dust, options, center.getX(), y + i, center.getZ());
            }
        }
    }

    private void point(World world, Particle dust, Particle.DustOptions options,
                       double x, double y, double z) {
        // force=true：基地之间常常超过客户端默认的 32 格粒子视距，不强制发送就看不到对面基地
        world.spawnParticle(dust, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, options, true);
    }

    /** 队伍颜色（粒子染色）。 */
    private static Color colorOf(TeamId team) {
        return team == TeamId.RED ? Color.RED : Color.BLUE;
    }
}
