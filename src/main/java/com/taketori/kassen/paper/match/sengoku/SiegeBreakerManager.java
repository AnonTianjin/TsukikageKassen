package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.RoundResult;
import com.taketori.kassen.core.match.sengoku.SiegeRules;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.skill.SkillManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 大将击破器：占领箭楼后生成 → 拾取携带 → 到敌方天守阁读条 → 本小局胜利。
 *
 * <p><b>为什么是"拾取携带 + 到点读条"而不是推动 / 投掷实体</b>：</p>
 * <ol>
 *   <li>需求要求"不可破坏、不可丢弃、不可被他人捡走"。插件里已经有整套绑定物护栏
 *       （{@code CarrierGuardListener} 的拾取 / 入箱 / 拖拽拦截），按队伍归属套一遍即可，
 *       几乎零新增成本；而推动 / 投掷要自己保证这三条，物理上很难做干净。</li>
 *   <li>掉落物被推动时极易卡进方块、台阶与地形，不同客户端延迟下位置还不同步。</li>
 *   <li>物品在背包里是绝对稳定的，读条阶段玩家站着不动，与占点逻辑同构。</li>
 * </ol>
 *
 * <p>击破器生成在<b>敌方</b>天守阁门前（需求第 11 条），所以拿到它意味着要深入敌后。</p>
 */
public final class SiegeBreakerManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 每队当前掉在地上、还没被捡走的击破器。 */
    private final Map<TeamId, Item> dropped = new EnumMap<>(TeamId.class);
    /** 曾经发放过击破器的队伍（respawn-on-recapture 关闭时用来做到"一队只发一次"）。 */
    private final java.util.Set<TeamId> everSpawned = java.util.EnumSet.noneOf(TeamId.class);
    /** 每个携带者的读条进度（秒）。 */
    private final Map<UUID, Double> progress = new HashMap<>();
    /** 同一个玩家两次"离开范围"提示之间的间隔，避免刷屏。 */
    private final Map<UUID, Long> lastLeaveNotice = new HashMap<>();

    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private BukkitTask task;
    /** tick 计数：击破器巡检等低频动作按它节流。 */
    private long ticks;

    public SiegeBreakerManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private SiegeRules rules() {
        return plugin.config().siegeRules();
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        stop();
        task = plugin.scheduler().runTimerTask(this::tick, 20L, 20L);
    }

    /** 收尾 / 小局重置：停 tick 并清掉地上的击破器（背包里的由玩家重开时一并清空）。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Item item : dropped.values()) {
            if (item != null && item.isValid()) {
                item.remove();
            }
        }
        dropped.clear();
        ticks = 0L;
        // 兜底：不在登记表里的击破器（背包溢出掉落、死亡掉落等）按 PDC 全场清掉，
        // 防止跨小局残留给下一局白送读条起点。只在 stop 时做一次，不在 tick 里扫。
        if (room.world() != null) {
            for (Item item : room.world().getEntitiesByClass(Item.class)) {
                if (breakerTeamOf(item.getItemStack()) != null) {
                    item.remove();
                }
            }
        }
        // everSpawned 也要清：它记的是"这一小局发过没有"。
        // 留着的话，respawn-on-recapture=false 时第 2 局起该队永远拿不到击破器，
        // 只能靠超时取胜——那是个没人会预期的副作用。
        everSpawned.clear();
        progress.clear();
        lastLeaveNotice.clear();
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 保证某队有一枚击破器可拿。
     *
     * <p>触发点有两处：占领箭楼（{@code TowerManager.onCaptured}）与"重新占领后自动补齐"。
     * 已经有一枚在地上、或已经有人拿在手里时不再生成（{@code one-per-team}）。</p>
     */
    public void ensureBreaker(TeamId team) {
        if (team == null) {
            return;
        }
        if (rules().onePerTeam() && hasBreaker(team)) {
            return;
        }
        if (!rules().respawnOnRecapture() && everSpawned.contains(team)) {
            // 关掉"重新占领自动补齐"时，一队只发一次——否则每占一次箭楼就多发一枚
            return;
        }
        Location spot = breakerSpawn(team);
        if (spot == null || spot.getWorld() == null) {
            if (plugin.config().debug()) {
                plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 缺少 "
                        + team.opposite().key() + " 天守阁门前点，无法生成击破器");
            }
            return;
        }
        // 落点校正：门前点 + 外推很容易把物品塞进墙里或悬空，玩家根本捡不到
        spot = SengokuSpots.onGround(spot);
        ItemStack stack = buildBreaker(team);
        // one-per-team=false 时允许同时存在多枚，但 dropped 只能记住一个引用——
        // 旧的那枚会从 stop() 的清理范围里漏掉（跨局残留）。所以发放前先收掉旧的。
        Item previous = dropped.remove(team);
        if (previous != null && previous.isValid()) {
            previous.remove();
        }
        Item item = spot.getWorld().dropItem(spot, stack);
        item.setUnlimitedLifetime(true);   // 需求：不可破坏，同时也不该自己消失
        item.setCanMobPickup(false);
        item.setGlowing(rules().glow());
        dropped.put(team, item);
        everSpawned.add(team);
        room.broadcast(plugin.config().messages().plain("sengoku.breaker-spawned",
                "team", team.display(), "keep", team.opposite().display() + " 天守阁门前"));
        plugin.fx().particle("END_ROD", spot, 30, 0.6D);
        plugin.fx().sound("BLOCK_BEACON_ACTIVATE", spot, 1.0F, 1.2F);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 为 " + team.key()
                    + " 生成击破器于 " + spot.getBlockX() + "," + spot.getBlockY() + "," + spot.getBlockZ());
        }
    }

    /** 某队是否已经有击破器（在地上或某人手里）。 */
    public boolean hasBreaker(TeamId team) {
        Item item = dropped.get(team);
        if (item != null && item.isValid() && !item.isDead()) {
            return true;
        }
        dropped.remove(team);
        for (Player player : room.teamPlayers(team)) {
            if (carriedBreakerTeam(player) == team) {
                return true;
            }
        }
        return false;
    }

    /** 击破器生成点：己方占领箭楼后，出现在<b>敌方</b>天守阁门前。 */
    private Location breakerSpawn(TeamId team) {
        Location door = room.keep().keepDoor(team.opposite());
        if (door == null) {
            return null;
        }
        double distance = Math.max(0.0D, rules().spawnDistanceFromKeep());
        if (distance <= 0.0D) {
            return door;
        }
        // 从门前点朝"远离天守阁中心"的方向再推几格，避免正好卡在门框里
        Location keepCenter = room.keep().keepCenter(team.opposite());
        if (keepCenter == null || keepCenter.getWorld() == null
                || !keepCenter.getWorld().equals(door.getWorld())) {
            return door;
        }
        var away = door.toVector().subtract(keepCenter.toVector()).setY(0.0D);
        if (away.lengthSquared() < 0.0001D) {
            return door;
        }
        return door.clone().add(away.normalize().multiply(distance));
    }

    private ItemStack buildBreaker(TeamId team) {
        Material material = Material.matchMaterial(
                rules().material() == null ? "NETHER_STAR" : rules().material().trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null) {
            plugin.getLogger().warning("[sengoku] 击破器材质无法解析：" + rules().material()
                    + "（回退 NETHER_STAR）");
            material = Material.NETHER_STAR;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MINI.deserialize(rules().display()));
        meta.lore(List.of(MINI.deserialize("<gray>带到 " + team.opposite().display()
                + " 天守阁前读条即可攻陷")));
        if (rules().glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(PDCKeys.sengokuBreakerTeam(),
                PersistentDataType.STRING, team.key());
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------------------------------------------------------- 携带判定

    /** 玩家手里 / 背包里那枚击破器属于哪一队；没有返回 {@code null}。 */
    public TeamId carriedBreakerTeam(Player player) {
        if (player == null) {
            return null;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            TeamId team = breakerTeamOf(stack);
            if (team != null) {
                return team;
            }
        }
        return null;
    }

    /** 单个物品是不是击破器、属于哪队。监听器用它做护栏判定。 */
    public static TeamId breakerTeamOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer()
                .get(PDCKeys.sengokuBreakerTeam(), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        return TeamId.byName(raw);
    }

    /** 该玩家是不是当前携带者（用于提示与统计）。 */
    public boolean isCarrier(Player player) {
        return player != null && carriedBreakerTeam(player) != null;
    }

    /**
     * 小局重置：把所有人背包里的击破器收掉。
     *
     * <p>必须单独做这一步——{@link #stop()} 只清了<b>掉在地上</b>的那些，
     * 而击破器的常态是"在某个人背包里"。漏掉的话上一局的攻城件会直接带进下一局，
     * 等于白送一次攻陷机会。</p>
     */
    public void removeFromInventories() {
        for (TeamId team : TeamId.values()) {
            for (Player player : room.teamPlayers(team)) {
                removeFromInventory(player);
            }
        }
        progress.clear();
        lastLeaveNotice.clear();
    }

    private void removeFromInventory(Player player) {
        if (player == null) {
            return;
        }
        var inventory = player.getInventory();
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (breakerTeamOf(inventory.getItem(slot)) != null) {
                inventory.setItem(slot, null);
                changed = true;
            }
        }
        if (breakerTeamOf(inventory.getItemInOffHand()) != null) {
            inventory.setItemInOffHand(null);
            changed = true;
        }
        if (changed && player.isOnline()) {
            player.updateInventory();
        }
    }

    // ---------------------------------------------------------------- tick：读条

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        SiegeRules siege = rules();
        double need = siege.effectiveArmSeconds();

        // 先清掉"已经不在场上"的携带者进度（阵亡进旁观 / 退出房间）。
        // 不清的话，携带者死亡期间进度会原地保留、复活后接着读满——等于死亡没有惩罚，
        // 也与设计文档「中断：离开范围 / 死亡 / 掉线 → 进度归零」不符。
        java.util.Set<UUID> active = new java.util.HashSet<>();
        List<Player> activePlayers = participants();
        for (Player player : activePlayers) {
            active.add(player.getUniqueId());
        }
        progress.keySet().removeIf(id -> !active.contains(id));
        lastLeaveNotice.keySet().removeIf(id -> !active.contains(id));

        // 主动拾取：走进 pick-radius 就直接进背包。
        // 原版的拾取半径是固定值、配置改不动，所以这里自己判定——
        // 这样 siege-breaker.pick-radius 才真的有效。
        tryPickup(activePlayers);

        // 巡检：把"不在登记表里的地上击破器"重新纳入追踪（死亡掉落、溢出掉落等）。
        // 不追踪的话 one-per-team 会被绕过，stop() 也清不掉它。每 5 秒扫一次，房间世界很小。
        if (room.world() != null && (ticks++ % 5L) == 0L) {
            for (Item item : room.world().getEntitiesByClass(Item.class)) {
                TeamId team = breakerTeamOf(item.getItemStack());
                if (team != null && !item.equals(dropped.get(team)) && item.isValid() && !item.isDead()) {
                    trackDropped(team, item);
                }
            }
        }

        for (Player player : activePlayers) {
            TeamId carrier = carriedBreakerTeam(player);
            if (carrier == null) {
                progress.remove(player.getUniqueId());
                continue;
            }
            if (!siege.allowsCharacter(characterOf(player))) {
                // 该职业不允许操作击破器：不推进，但要说明原因，否则玩家会以为坏了
                progress.remove(player.getUniqueId());
                room.scoreboard().actionBar(player,
                        plugin.config().messages().plain("sengoku.breaker-role-blocked"));
                continue;
            }
            if (!insideKeep(player, carrier.opposite())) {
                progress.remove(player.getUniqueId());
                noticeLeft(player, carrier);
                continue;
            }

            double current = progress.getOrDefault(player.getUniqueId(), 0.0D) + 1.0D;
            progress.put(player.getUniqueId(), current);
            if (need <= 0.0D) {
                arm(player, carrier);
                return;
            }
            String bar = SkillManager.progressBar(current, need);
            int percent = (int) Math.round(current / need * 100.0D);
            room.scoreboard().actionBar(player, plugin.config().messages().plain(
                    "sengoku.breaker-progress",
                    "keep", carrier.opposite().display() + " 天守阁",
                    "bar", bar, "percent", percent));
            if (current >= need) {
                arm(player, carrier);
                return;
            }
        }
    }

    /**
     * 归属队玩家走进 {@code pick-radius} 内即自动拾取。
     *
     * <p>自己判定而不是靠原版：原版的拾取半径是固定值、配置改不动，所以
     * {@code siege-breaker.pick-radius} 一直是死键。原版拾取的护栏
     * （{@code SengokuListener.onBreakerPickup}）仍然保留做兜底。</p>
     */
    private void tryPickup(List<Player> activePlayers) {
        double radius = Math.max(0.5D, rules().pickRadius());
        for (TeamId team : TeamId.values()) {
            Item item = dropped.get(team);
            if (item == null) {
                continue;
            }
            if (!item.isValid() || item.isDead()) {
                dropped.remove(team);
                continue;
            }
            Location spot = item.getLocation();
            if (spot.getWorld() == null) {
                continue;
            }
            for (Player player : activePlayers) {
                if (room.teamOf(player.getUniqueId()) != team) {
                    continue;   // 只有归属队能拿
                }
                if (!player.getWorld().equals(spot.getWorld())) {
                    continue;
                }
                if (player.getLocation().distanceSquared(spot) > radius * radius) {
                    continue;
                }
                give(player, team, item);
                break;
            }
        }
    }

    private void give(Player player, TeamId team, Item item) {
        ItemStack stack = item.getItemStack();
        item.remove();
        dropped.remove(team);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        if (!leftovers.isEmpty()) {
            // 背包满了：掉回脚边而不是凭空消失——它不可破坏，等队友来捡。
            // 必须立刻重新登记进 dropped，否则这枚不受追踪：
            // one-per-team 会被绕过（下次占领再刷一枚），stop() 也清不到它。
            for (ItemStack left : leftovers.values()) {
                Item fallen = player.getWorld().dropItemNaturally(player.getLocation(), left);
                fallen.setUnlimitedLifetime(true);
                fallen.setCanMobPickup(false);
                fallen.setGlowing(rules().glow());
                trackDropped(team, fallen);
            }
        }
        player.updateInventory();
        room.scoreboard().actionBar(player, plugin.config().messages().plain("sengoku.breaker-picked",
                "keep", team.opposite().display() + " 天守阁"));
        plugin.fx().sound("ENTITY_ITEM_PICKUP", player, 1.0F, 1.2F);
    }

    /** 把一枚地上击破器登记为某队的现役掉落物（同一队旧的先收掉，维持 one-per-team 语义）。 */
    private void trackDropped(TeamId team, Item item) {
        Item previous = dropped.remove(team);
        if (previous != null && previous.isValid() && !previous.equals(item)) {
            previous.remove();
        }
        dropped.put(team, item);
    }

    /**
     * 读满：本小局立即判定携带方胜利。
     *
     * <p>回传给 RoundResult 的用时是<b>本小局实际用时</b>（{@code roundElapsedSeconds()}），
     * 不是读条所需秒数——播报里的 {seconds} 占位符按契约是"这局打了多久"。</p>
     */
    private void arm(Player player, TeamId team) {
        progress.remove(player.getUniqueId());
        room.broadcast(plugin.config().messages().plain("sengoku.breaker-armed",
                "player", player.getName(),
                "keep", team.opposite().display() + " 天守阁"));
        plugin.fx().sound("ENTITY_ENDER_DRAGON_GROWL", player.getLocation(), 1.0F, 1.0F);
        room.sengoku().onRoundEnd(new RoundResult(team, RoundResult.Reason.BREAKER_ARMED,
                room.sengoku().roundElapsedSeconds()));
    }

    private void noticeLeft(Player player, TeamId carrier) {
        long now = System.currentTimeMillis();
        Long last = lastLeaveNotice.get(player.getUniqueId());
        if (last != null && now - last < 3000L) {
            return;
        }
        lastLeaveNotice.put(player.getUniqueId(), now);
        room.scoreboard().actionBar(player, plugin.config().messages().plain("sengoku.breaker-leave",
                "keep", carrier.opposite().display() + " 天守阁"));
    }

    /**
     * 玩家是否进到了某队天守阁的范围内。
     *
     * <p>判定口径是「<b>进入区域</b>」，不是「距区域中心 N 格」——后者有个致命退化：
     * 天守阁区域一旦大于约 2N×2N，球心就落进建筑内部（甚至实心墙里），
     * 玩家可能<b>永远读不满</b>；而门前点离中心近时又会退化成「蹲在门前一键攻陷」。</p>
     *
     * <p>{@code keep.arm-radius} 保留为<b>外扩容差</b>：区域边界常与墙体重合，
     * 贴着墙站不该被判成"在外面"。</p>
     */
    private boolean insideKeep(Player player, TeamId team) {
        var arena = room.arena();
        if (arena == null) {
            return false;
        }
        CuboidRegion keep = arena.sengoku().keep(team);
        if (keep == null || keep.world() == null || !keep.world().equals(player.getWorld())) {
            return false;
        }
        if (keep.contains(player.getLocation())) {
            return true;
        }
        Location center = keep.center();
        if (center == null) {
            return false;
        }
        double tolerance = Math.max(0.5D, plugin.config().sengokuRules().keepArmRadius());
        return player.getLocation().distance(center) <= tolerance;
    }

    private List<Player> participants() {
        List<Player> result = new ArrayList<>();
        for (TeamId team : TeamId.values()) {
            for (Player player : room.teamPlayers(team)) {
                if (!plugin.spectator().isSpectator(player)) {
                    result.add(player);
                }
            }
        }
        return result;
    }

    private String characterOf(Player player) {
        var profile = plugin.config().characters().profileOrNull(player.getUniqueId());
        return profile == null ? null : profile.characterId();
    }

    // ---------------------------------------------------------------- 查询

    /** 某队击破器的当前位置描述（调试 / admin 命令用）。 */
    public String describe(TeamId team) {
        Item item = dropped.get(team);
        if (item != null && item.isValid()) {
            Location location = item.getLocation();
            return "地上 (" + location.getBlockX() + "," + location.getBlockY() + ","
                    + location.getBlockZ() + ")";
        }
        for (Player player : room.teamPlayers(team)) {
            if (carriedBreakerTeam(player) == team) {
                return player.getName() + " 携带中";
            }
        }
        return "未生成";
    }

    /** 某玩家当前的读条进度（秒）。 */
    public double progressOf(Player player) {
        return player == null ? 0.0D : progress.getOrDefault(player.getUniqueId(), 0.0D);
    }
}
