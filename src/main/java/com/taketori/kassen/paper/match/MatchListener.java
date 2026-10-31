package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 对局相关的事件监听：击杀计分、死亡旁观、复活、退出清理。
 *
 * <p>多房间化后所有判定都经 {@link com.taketori.kassen.paper.match.room.RoomManager}
 * 路由：玩家事件取 {@code roomOf(player)}，月人事件取 {@code roomOfEntity(entity)}，
 * 无房间归属一律不碰；复活延时回调会再次校验"仍是同一房间且仍在战斗中"，
 * 防止两轮对局之间串台。</p>
 *
 * <p>死亡流程（需求：死亡旁观）：</p>
 * <ol>
 *   <li>死亡时按规则计分、保留物品；</li>
 *   <li>下一 tick 自动跳过死亡界面（{@code spigot().respawn()}）；</li>
 *   <li>重生事件里把玩家切成<b>旁观</b>并看向战场；</li>
 *   <li>倒计时结束恢复生存并传送到己方出生点。</li>
 * </ol>
 */
public final class MatchListener implements Listener {

    private final TaketoriPlugin plugin;

    public MatchListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 拆包交互：<b>左键</b>包点 = 开始/继续读条，<b>右键</b> = 中断。
     *
     * <p>用 {@code ignoreCancelled = false} 是必须的：TNT 本身可被点燃、可被交互，
     * 别的插件（保护插件、菜单插件）完全可能把事件取消掉，取消之后玩家点它照样没有反应——
     * 但这个方块是插件自己放的目标物，取消与否都得算数。</p>
     *
     * <p>左右键分工而不是「右键一下读一次」：读条模型里进度只属于当前拆弹人，
     * 右键必须能随时中断（换人、撤退），否则拆到一半被顶掉的人没法主动放弃。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBombInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            return;
        }
        BombObjective bomb = room.bomb();
        if (bomb == null || !bomb.isBombBlock(clicked)) {
            return;
        }
        event.setCancelled(true);
        // 只认主手：副手也会触发一次，进度会按两次累加
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // 读条是每秒累加的，这里只负责「把这个人登记成拆弹人」
            if (bomb.startDefuse(player) && bomb.isDefused()) {
                room.finish(TeamId.RED);
            }
        } else {
            bomb.cancelDefuse(player);
        }
    }

    /**
     * 布置地雷：主手拿着带 {@code land_mine} 标记的绊线钩右键方块。
     *
     * <p>落点取点击面的相邻方块（{@code getRelative(face)}）而不是被点方块本身：
     * 玩家通常是对着地面右键，直接替换地面会把地形挖出一个洞。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onMinePlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TRIPWIRE_HOOK) {
            return;
        }
        if (hand.getItemMeta() == null
                || !hand.getItemMeta().getPersistentDataContainer()
                .has(PDCKeys.landMine(), PersistentDataType.BYTE)) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            return;
        }
        event.setCancelled(true);
        Location target = clicked.getRelative(event.getBlockFace()).getLocation();
        if (!room.placeMine(player, target)) {
            // 布设被拒（不在对局 / 不是蓝队 / 已达上限）：手上的地雷不能被白白吞掉
            return;
        }
        // 一枚地雷对应一个绊线钩：布置成功就消耗一个
        int amount = hand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amount - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }

    /** 包点方块禁止被挖：被挖掉就等于把这一局的获胜条件从地图上删了。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBombBreak(BlockBreakEvent event) {
        GameRoom room = plugin.rooms().roomOf(event.getPlayer());
        if (room == null) {
            return;
        }
        BombObjective bomb = room.bomb();
        if (bomb != null && bomb.isBombBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** 月人（普通/精英，任意实体类型）被玩家击杀 → 按实体所属房间计分（归属在刷怪器，天然按房间隔离）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinionDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // 召唤物不是月人：击杀它既不计分也不计击杀（否则对面可以刷狗刷分）
        if (entity.getPersistentDataContainer()
                .get(com.taketori.kassen.paper.item.PDCKeys.summonedOwner(),
                        org.bukkit.persistence.PersistentDataType.STRING) != null) {
            return;
        }
        GameRoom room = plugin.rooms().roomOfEntity(entity);
        if (room == null) {
            return;
        }
        room.minions().onMinionDeath(entity);

        Player killer = entity.getKiller();
        if (killer == null || !room.isRunning()) {
            return;
        }
        // 击杀者必须仍在该房间的战斗中（防止跨房/收尾瞬间误计分）
        if (plugin.rooms().roomOf(killer) != room) {
            return;
        }
        MatchRules rules = room.rules();
        room.addScore(killer, rules.minionKillScore(), "击杀月人");
        room.addMinionKill(killer);                                                  // 本局计数（记分板）
        plugin.stats().add(killer.getName(), StatsTracker.Stat.MINION_KILLS, 1L);     // 跨局累计
        double healed = healOnKill(killer, rules.minionKillHeal());
        room.scoreboard().actionBar(killer, "<green>+<white>" + rules.minionKillScore()
                + " <gray>击杀月人" + healText(healed));
    }

    /** 玩家死亡：计分、保留物品、安排跳过死亡界面。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        GameRoom room = plugin.rooms().roomOf(victim);
        if (room == null || !room.isRunning()) {
            return;
        }
        MatchRules rules = room.rules();

        if (rules.keepInventory()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        // 拆弹钳是一次性特权：死了就抹掉，keepInventory 也不留情。
        // 否则「花 30 分买的钳子能用到对局结束」等于拆弹没有成本，商店就没意义了。
        room.removeClippers(victim);
        // 跨局累计：死亡数（被玩家、月人、环境杀死都算）
        plugin.stats().add(victim.getName(), StatsTracker.Stat.DEATHS, 1L);

        Player killer = victim.getKiller();
        if (killer != null && plugin.rooms().roomOf(killer) == room) {
            TeamId victimTeam = room.teamOf(victim.getUniqueId());
            TeamId killerTeam = room.teamOf(killer.getUniqueId());
            if (killerTeam != null && victimTeam != null && killerTeam != victimTeam) {
                room.addScore(killer, rules.playerKillScore(), "击杀 " + victim.getName());
                room.addPlayerKill(killer);
                // 加时赛只比人头：个人分已被清零，这里单独记队伍人头
                room.addOvertimeKill(killerTeam);
                plugin.stats().add(killer.getName(), StatsTracker.Stat.KILLS, 1L);
                // 击杀回血（需求）：满血时不回、也不刷提示
                double healed = healOnKill(killer, rules.playerKillHeal());
                room.scoreboard().actionBar(killer, "<green>+<white>" + rules.playerKillScore()
                        + " <gray>击杀 " + victim.getName() + healText(healed));
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[room " + room.id() + "] " + killer.getName() + " 击杀 "
                            + victim.getName() + "，回血 " + healed + " 点");
                }
            }
        }

        // 死亡旁观：1 tick 后跳过死亡界面，交给 onRespawn 接管
        String victimName = victim.getName();
        plugin.scheduler().runLater(() -> {
            Player player = plugin.getServer().getPlayerExact(victimName);
            if (player != null && player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        }, 1L);
    }

    /** 重生：先进入死亡旁观，倒计时结束后复活到己方出生点（全部按死亡时所在房间结算）。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            return;
        }
        TeamId team = room.teamOf(player.getUniqueId());
        if (team == null) {
            return;
        }
        var spawnPoint = room.arena().spawn(team);
        Location spawn = spawnPoint == null ? null : spawnPoint.toBukkitLocation();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }

        // 观战视角：优先看该房间中央刷怪区（战场热点）
        Location viewPoint = room.spectatorViewPoint();
        plugin.spectator().enterTemporary(player, viewPoint);

        // 复活倒计时 BossBar：与 respawn-delay 同步，复活瞬间由 spectator().leave() 撤除
        plugin.spectator().startRespawnCountdown(player, Math.max(1, room.rules().respawnDelaySeconds()));

        int delay = Math.max(1, room.rules().respawnDelaySeconds()) * 20;
        String name = player.getName();
        // 捕获死亡时的房间实例：回调时必须还是同一房间、仍在战斗、队伍未变，否则送去大厅
        final GameRoom deathRoom = room;
        final TeamId deathTeam = team;
        plugin.scheduler().runLater(() -> {
            Player target = plugin.getServer().getPlayerExact(name);
            if (target == null || !target.isOnline()) {
                return;
            }
            GameRoom current = plugin.rooms().roomOf(target);
            if (current != deathRoom || !deathRoom.isRunning()
                    || deathRoom.teamOf(target.getUniqueId()) != deathTeam) {
                // 对局已结束/房间已重置/玩家已不在原队伍：离开旁观回大厅
                plugin.spectator().leave(target, plugin.lobby().spawn());
                return;
            }
            plugin.spectator().leave(target, spawn);
            deathRoom.scoreboard().showTo(target);
            deathRoom.applySpawnBuff(target);   // 复活后的出生增益
            deathRoom.scoreboard().actionBar(target, "<green>已复活，返回战场！");
        }, delay);
    }

    /**
     * 友伤保护：**同一房间同一队**的玩家互相不造成伤害（近战、箭矢、技能弹体都算）。
     *
     * <p>是否生效看 <code>combat.friendly-fire-protection</code>：
     * 默认 <code>auto</code> = <b>PVP 与 PVE 均开启</b>（PVE 承诺玩家间不互相伤害）；
     * 想允许同队互打，把它设成 <code>off</code> 即可。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(victim);
        if (room == null || !room.isRunning() || !room.isFriendlyFireProtected()) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;   // 自伤（技能反噬之类）不拦
        }
        // 攻击者必须在同一房间；跨房物理接触（理论上传送隔离不会发生）不拦截
        if (plugin.rooms().roomOf(attacker) != room) {
            return;
        }
        TeamId attackerTeam = room.teamOf(attacker.getUniqueId());
        TeamId victimTeam = room.teamOf(victim.getUniqueId());
        if (attackerTeam == null || victimTeam == null || attackerTeam != victimTeam) {
            return;   // 不同队、或有人不在比赛里 → 正常结算
        }
        event.setCancelled(true);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + room.id() + "] 友伤保护：已取消 " + attacker.getName()
                    + " 对队友 " + victim.getName() + " 的伤害");
        }
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * 玩家退出：经 RoomManager 解除房间映射。等待/倒计时阶段立即释放名额
     * （下一 tick 房间倒计时自动重算/取消）；CAGED/PLAYING 保留队伍位置只撤记分板，
     * 并登记断线重连会话（时限内重连回原房原队）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.spectator().forgetQuietly(player.getUniqueId());
        // 队伍信息必须在解绑前取：普通 leave 路径会随 quitMatch 移除 teams 条目
        GameRoom room = plugin.rooms().roomOf(player.getUniqueId());
        TeamId team = room == null ? null : room.teamOf(player.getUniqueId());
        boolean rejoinable = room != null && room.isRunning() && team != null
                && plugin.rejoin().rejoinSeconds() > 0;
        // 先把进房封存的背包转全局暂存（房间世界随后可能被删除，玩家数据随世界丢失，
        // 重连后由 restoreOfflineBackup 返还），再解除房间映射
        plugin.rooms().stashOfflineBackup(player.getUniqueId());
        if (rejoinable) {
            // 对局中掉线：保留队伍条目占位，登记重连会话（时限内重连回原房原队）；
            // 会话过期后由 purgeExpired 释放槽位
            plugin.rooms().leaveForRejoin(player.getUniqueId());
            plugin.rejoin().register(player.getUniqueId(), room, team);
        } else {
            plugin.rooms().leave(player.getUniqueId());
        }
        if (room != null && plugin.config().debug() && room.isRunning()) {
            plugin.getLogger().info("[room " + room.id() + "] " + player.getName()
                    + " 退出对局" + (rejoinable ? "（重连窗口内保留队伍位置）" : "（队伍保留）"));
        }
    }

    /**
     * 击杀回血：回复指定"生命点"（2 点 = 1 颗心），不会超过最大生命值。
     *
     * <p>用 {@code getMaxHealth()} 而不是写死 20，因为角色属性会改上限
     * （月读之类血量不同的角色也能正确回满）。</p>
     *
     * @return 实际回复的点数；0 表示没回复（配置为 0、已经满血、或击杀者已阵亡）
     */
    private double healOnKill(Player killer, double amount) {
        if (amount <= 0.0D || killer == null || !killer.isOnline() || killer.isDead()) {
            return 0.0D;
        }
        double max = killer.getMaxHealth();
        double before = killer.getHealth();
        double after = Math.min(max, before + amount);
        double healed = after - before;
        if (healed <= 0.0D) {
            return 0.0D;   // 满血：不刷提示、不放特效
        }
        killer.setHealth(after);
        plugin.fx().particle("HEART", killer.getLocation().add(0.0D, 1.2D, 0.0D), 6, 0.4D);
        plugin.fx().sound("ENTITY_PLAYER_LEVELUP", killer, 0.35F, 1.7F);
        return healed;
    }

    /** 回血提示片段（" · 回血 +3❤"）；没回血时是空串，直接拼在击杀提示后面。 */
    private String healText(double healed) {
        return healed <= 0.0D ? "" : " <green>· 回血 +" + formatHearts(healed) + "❤";
    }

    /** 生命点 → 颗心（整数不带小数）。 */
    private String formatHearts(double healthPoints) {
        double hearts = healthPoints / 2.0D;
        return hearts == Math.floor(hearts)
                ? Long.toString((long) hearts)
                : String.format(java.util.Locale.ROOT, "%.1f", hearts);
    }
}
