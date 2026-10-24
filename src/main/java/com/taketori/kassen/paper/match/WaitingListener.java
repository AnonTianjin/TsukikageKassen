package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 房间等待区 / 玻璃笼保护（BedWars 式等待区机制）：
 *
 * <ul>
 *   <li>WAITING/STARTING 的等待者：免疫一切伤害（近战/箭/虚空/坠落/火焰/溺亡…）、
 *       不掉饥饿、不能破坏/放置方块、不能操作场地里的方块（箱/门/按钮/踏板）；
 *       掉入虚空（Y 低于 wait-spawn + waiting.void-y-offset）或跑到别的世界时
 *       立刻拉回 wait-spawn；</li>
 *   <li>CAGED 的参赛者：同样免伤免饥饿（笼内另有无敌 + 缓慢冻结），
 *       且任何玩家都不能破坏/占用玻璃笼方块，掉出笼外时拉回队伍出生点；</li>
 *   <li>进入 PLAYING 后保护自动解除，战斗规则全部恢复。</li>
 * </ul>
 *
 * <p>所有判定都经 RoomManager 路由：无房间归属的玩家（如大厅里的人）一律不拦截。</p>
 */
public final class WaitingListener implements Listener {

    private final TaketoriPlugin plugin;

    public WaitingListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 伤害 / 饥饿

    /** 保护期玩家受到的任何伤害（含虚空、坠落、火焰、近战、弹射物）一律取消。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !guarded(room, player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        // 等待者身上若残留燃烧，灭火避免视觉上一直烧（伤害已取消但火苗还在）
        if (player.getFireTicks() > 0) {
            player.setFireTicks(0);
        }
    }

    /** 保护期不掉饥饿。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room != null && guarded(room, player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- 方块保护

    /** 保护期玩家不能破坏；玻璃笼/屏障墙方块任何人不能破坏；对局区域内方块任何人不能破坏。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room != null && guarded(room, player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (isAnyCageBlock(loc) || isAnyBarrierBlock(loc)) {
            event.setCancelled(true);
            return;
        }
        // 对局区域内所有方块禁止破坏（不论破坏者是否属于该房间）—— 防止外人进场拆家
        if (isInsideAnyActivePlayArea(loc)) {
            event.setCancelled(true);
        }
    }

    /** 保护期玩家不能放置；也不许往玻璃笼/屏障墙位置塞方块；对局区域内禁止放置。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room != null && (guarded(room, player.getUniqueId())
                || room.isCageBlock(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (isAnyBarrierBlock(loc)) {
            event.setCancelled(true);
            return;
        }
        if (isInsideAnyActivePlayArea(loc)) {
            event.setCancelled(true);
        }
    }

    /**
     * 桶也是方块改动，保护期同样禁止；目标点落在玻璃笼 / 屏障墙 / 对局区域内
     * 也禁止（与破坏 / 放置同一套区域判定），防岩浆/水搞乱等待区与场地。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (guarded(player)) {
            event.setCancelled(true);
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (isAnyCageBlock(loc) || isAnyBarrierBlock(loc) || isInsideAnyActivePlayArea(loc)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        if (guarded(player)) {
            event.setCancelled(true);
            return;
        }
        Location loc = event.getBlock().getLocation();
        if (isAnyCageBlock(loc) || isAnyBarrierBlock(loc) || isInsideAnyActivePlayArea(loc)) {
            event.setCancelled(true);
        }
    }

    /**
     * 保护期禁止操作场地方块（右键容器/门/按钮 + 踩踏板）；
     * 右键空气（使用菜单时钟等物品）不受影响。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !guarded(room, player.getUniqueId())) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- 出界拉回

    /**
     * 虚空 / 跨世界拉回：
     * WAITING/STARTING → wait-spawn（阈值 wait-spawn.y + waiting.void-y-offset）；
     * CAGED → 队伍出生点（笼有底板，正常不可能掉出，只做兜底）。
     * 只在方块坐标变化时处理，视角转动不触发判定。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // 传送事件也走这里：只处理确实跨格的移动，避免每帧噪声
        if (event.getFrom().getX() == to.getX()
                && event.getFrom().getY() == to.getY()
                && event.getFrom().getZ() == to.getZ()) {
            return;
        }
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            return;
        }

        if (room.phase() == GameRoom.Phase.WAITING || room.phase() == GameRoom.Phase.STARTING) {
            if (!guarded(room, player.getUniqueId())) {
                return;
            }
            Location waitSpawn = room.arena().waitAreaCenterOrSpawn();
            if (waitSpawn == null) {
                return;
            }
            double voidY = waitSpawn.getY() + plugin.config().waitingVoidYOffset();
            World world = waitSpawn.getWorld();
            if ((world != null && !to.getWorld().equals(world)) || to.getY() < voidY) {
                pullBack(player, waitSpawn);
            }
        } else if (room.isCaged(player.getUniqueId())) {
            TeamId team = room.teamOf(player.getUniqueId());
            ArenaDef.Point spawnPoint = team == null ? null : room.arena().spawn(team);
            Location spawn = spawnPoint == null ? null : spawnPoint.toBukkitLocation();
            if (spawn == null) {
                return;
            }
            World world = spawn.getWorld();
            if ((world != null && !to.getWorld().equals(world)) || to.getY() < spawn.getY() - 3.0D) {
                pullBack(player, spawn);
            }
        }
    }

    private boolean guarded(Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        return room != null && guarded(room, player.getUniqueId());
    }

    /**
     * 是否受本监听器保护：CAGED 参赛者无条件保护（笼机制本身）；
     * WAITING/STARTING 等待者受 waiting.protect 开关控制（默认开）。
     */
    private boolean guarded(GameRoom room, java.util.UUID uuid) {
        if (room.isCaged(uuid)) {
            return true;
        }
        return plugin.config().waitingProtect()
                && (room.phase() == GameRoom.Phase.WAITING || room.phase() == GameRoom.Phase.STARTING)
                && room.isProtected(uuid);
    }

    /** 该坐标是否属于任意房间当前的玻璃笼（CAGED 期间笼方块对所有人受保护）。 */
    private boolean isAnyCageBlock(Location location) {
        for (GameRoom room : plugin.rooms().rooms()) {
            if (room.phase() == GameRoom.Phase.CAGED && room.isCageBlock(location)) {
                return true;
            }
        }
        return false;
    }

    /** 该坐标是否属于任意房间当前的区域屏障墙。 */
    private boolean isAnyBarrierBlock(Location location) {
        for (GameRoom room : plugin.rooms().rooms()) {
            if (room.isBarrierBlock(location)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该坐标是否落在任意"进行中对局"（CAGED/PLAYING/ENDING）的场地活动范围内。
     * 用于禁止任何玩家（含非参赛者）破坏/放置对局区域内的方块。
     */
    private boolean isInsideAnyActivePlayArea(Location location) {
        for (GameRoom room : plugin.rooms().rooms()) {
            GameRoom.Phase phase = room.phase();
            if (phase == GameRoom.Phase.CAGED
                    || phase == GameRoom.Phase.PLAYING
                    || phase == GameRoom.Phase.ENDING) {
                if (room.isPlayArea(location)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 拉回指定点：保留视角，清除坠落距离，避免拉回后被摔血（保护期虽免伤也不留隐患）。 */
    private void pullBack(Player player, Location destination) {
        Location target = destination.clone();
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.setFallDistance(0.0F);
        player.teleport(target);
        player.setFallDistance(0.0F);
    }
}
