package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.SiegeRules;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跳跃台：占领箭楼后在<b>己方</b>天守阁门前激活，站上去即可抵达已占领的箭楼。
 *
 * <p>需求第 13 条要的是"阵亡复活后也能在约 10 秒内抵达已占领的箭楼"——这是<b>机动性补偿</b>：
 * 阵亡一次不该让整条进攻线断掉。</p>
 *
 * <p><b>默认用 TP 而不是弹射</b>：TP 不受地形影响、没有落点误差、10 秒内必然抵达；
 * 弹射会被墙、树、台阶吃掉速度，还可能把人送进虚空或卡在方块里。所以
 * {@code launch} 只作为可配的备选（手感优先时用）。</p>
 *
 * <p>跳跃台本身<b>不改地形</b>——它是一块"点 + 半径"的逻辑区域，用粒子标出来。
 * 放过方块的话，玩家会把它挖掉、小局重置还得还原，得不偿失。</p>
 */
public final class JumpPadManager {

    /** 触发半径（格）：踩进这个范围就弹走。做成常量而不是配置，是因为它属于手感而非平衡。 */
    private static final double TRIGGER_RADIUS = 1.5D;
    /** 粒子标记的刷新间隔（tick）：每秒一次足够显眼，又不至于刷屏。 */
    private static final long MARK_INTERVAL_TICKS = 20L;

    private final GameRoom room;
    private final TaketoriPlugin plugin;
    /** 已激活的跳跃台（占领任一箭楼后置位，小局重置时清空）。 */
    private final Set<TeamId> active = EnumSet.noneOf(TeamId.class);
    /** 玩家使用冷却（毫秒时间戳）。 */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    /** 玩家退出对局/服务器时的按人清理（防冷却条目跨局滞留）。 */
    public void forget(UUID uuid) {
        if (uuid != null) {
            cooldowns.remove(uuid);
        }
    }

    public JumpPadManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private SiegeRules.JumpPadSpec spec() {
        return plugin.config().siegeRules().jumpPad();
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        stop();
        task = plugin.scheduler().runTimerTask(this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        active.clear();
        cooldowns.clear();
        ticks = 0L;
    }

    // ---------------------------------------------------------------- 激活

    /**
     * 占领箭楼后激活该队的跳跃台（幂等：已经激活时不重复播报）。
     *
     * <p>需求第 13 条：<b>占领任一箭楼</b>就出现，不是"占领特定那座"。</p>
     */
    public void ensurePad(TeamId team) {
        if (team == null || active.contains(team)) {
            return;
        }
        Location pad = padLocation(team);
        if (pad == null) {
            if (plugin.config().debug()) {
                plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 缺少 "
                        + team.key() + " 的跳跃台点位，无法激活");
            }
            return;
        }
        active.add(team);
        room.broadcast(plugin.config().messages().plain("sengoku.jumppad-ready",
                "team", team.display()));
        plugin.fx().particle(spec().particle(), pad, 30, 0.6D);
        plugin.fx().sound(spec().sound(), pad, 1.0F, 1.4F);
    }

    public boolean isActive(TeamId team) {
        return team != null && active.contains(team);
    }

    /** 跳跃台位置；未配置返回 null。 */
    public Location padLocation(TeamId team) {
        var arena = room.arena();
        if (arena == null) {
            return null;
        }
        var point = arena.sengoku().jumpPad(team);
        return point == null ? null : point.toBukkitLocation();
    }

    // ---------------------------------------------------------------- tick：踩踏检测 + 粒子标记

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        ticks++;
        boolean markNow = ticks % MARK_INTERVAL_TICKS == 0L;
        for (TeamId team : TeamId.values()) {
            if (!active.contains(team)) {
                continue;
            }
            Location pad = padLocation(team);
            if (pad == null || pad.getWorld() == null) {
                continue;
            }
            if (markNow) {
                plugin.fx().particle(spec().particle(), pad.clone().add(0.0D, 0.2D, 0.0D), 8, 0.35D);
            }
            for (Player player : room.teamPlayers(team)) {
                if (plugin.spectator().isSpectator(player)) {
                    continue;
                }
                if (!player.getWorld().equals(pad.getWorld())) {
                    continue;
                }
                if (player.getLocation().distanceSquared(pad) > TRIGGER_RADIUS * TRIGGER_RADIUS) {
                    continue;
                }
                use(player, team, pad);
            }
        }
    }

    // ---------------------------------------------------------------- 使用

    private void use(Player player, TeamId team, Location pad) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && now < until) {
            return;   // 冷却中：静默忽略，否则站在上面会被刷屏
        }
        Location target = targetOf(team, pad, player);
        if (target == null) {
            // 一座己方箭楼都没有（跳跃台刚激活但归属又被打回去了）：给一次说明就走
            if (until == null) {
                cooldowns.put(player.getUniqueId(), now + 3000L);
                room.scoreboard().actionBar(player,
                        plugin.config().messages().plain("sengoku.jumppad-no-target"));
            }
            return;
        }
        cooldowns.put(player.getUniqueId(), now + Math.max(0, spec().cooldownSeconds()) * 1000L);

        if (spec().isLaunch()) {
            launch(player, pad, target);
        } else {
            teleport(player, pad, target);
        }
    }

    /** 默认方式：直接传送，并给免摔窗口与短暂抗性。 */
    private void teleport(Player player, Location pad, Location target) {
        Location destination = target.clone();
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        plugin.fx().particle(spec().particle(), pad.clone().add(0.0D, 0.2D, 0.0D), 20, 0.3D);
        player.teleport(destination);
        player.setFallDistance(0.0F);
        afterArrive(player, destination);
    }

    /** 备选方式：给初速度弹过去（手感优先，但会被地形吃掉速度）。 */
    private void launch(Player player, Location pad, Location target) {
        Vector direction = target.toVector().subtract(pad.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            teleport(player, pad, target);
            return;
        }
        direction.normalize().multiply(Math.max(0.0D, spec().power()));
        direction.setY(Math.max(0.0D, spec().upward()));
        player.setVelocity(direction);
        player.setFallDistance(0.0F);
        afterArrive(player, pad);
    }

    private void afterArrive(Player player, Location where) {
        plugin.scheduler().runLater(() -> {
            if (player.isOnline()) {
                player.setFallDistance(0.0F);
            }
        }, 5L);
        int immunity = Math.max(0, spec().fallImmunityTicks());
        if (immunity > 0) {
            plugin.states().setFallImmunity(player.getUniqueId(), immunity);
        }
        int resistanceTicks = Math.max(0, spec().resistanceTicks());
        PotionEffectType resistance = plugin.versions().potionEffect("RESISTANCE");
        if (resistanceTicks > 0 && resistance != null) {
            player.addPotionEffect(new PotionEffect(resistance, resistanceTicks, 0, false, false, true));
        }
        plugin.fx().particle(spec().particle(), where.clone().add(0.0D, 0.5D, 0.0D), 20, 0.4D);
        plugin.fx().sound(spec().sound(), where, 1.0F, 1.2F);
    }

    /**
     * 选择一个落点。
     *
     * <p>默认取<b>最近的己方已占领箭楼</b>；{@code target=specified} 时固定用配置的序号
     * （但那种情况下如果该箭楼不在己方手里，仍然返回 null——不能让跳跃台变成"传送进敌阵"）。</p>
     */
    private Location targetOf(TeamId team, Location pad, Player player) {
        int count = room.towers().towerCount();
        SiegeRules.JumpPadSpec jumpPad = spec();
        if (!jumpPad.isNearest()) {
            int index = Math.max(1, Math.min(count, jumpPad.specifiedTower()));
            return room.towers().ownerOf(index) == team ? towerLanding(index) : null;
        }
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 1; index <= count; index++) {
            if (room.towers().ownerOf(index) != team) {
                continue;
            }
            Location landing = towerLanding(index);
            if (landing == null) {
                continue;
            }
            double distance = landing.distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = landing;
            }
        }
        return best;
    }

    /** 箭楼的落点：占领区中心校正到可站立位置（否则可能落在墙里、悬空或虚空）。 */
    private Location towerLanding(int index) {
        var arena = room.arena();
        if (arena == null) {
            return null;
        }
        CuboidRegion region = arena.sengoku().tower(index);
        if (region == null) {
            return null;
        }
        Location center = region.center();
        return center == null ? null : SengokuSpots.onGround(center);
    }

    /** 某玩家还要等多久才能再用（秒，0 = 可用）。 */
    public long cooldownSecondsLeft(Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0L ? 0L : (left / 1000L) + 1L;
    }
}
