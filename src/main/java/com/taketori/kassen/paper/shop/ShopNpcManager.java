package com.taketori.kassen.paper.shop;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.item.PDCKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 队伍商店商人 NPC 的生命周期管理：生成、位置持久化、看护与身份识别。
 *
 * <p><b>为什么单独一个类</b>：商人 NPC 属于「世界里的常驻实体」，既不属于某个房间
 * （它在大厅，跨局长期存在），也不属于某个菜单（菜单只是它的消费者）。它的失败模式
 * 又都很安静——位置没配、世界没加载、实体被区块重载吃掉——所以把它收在一处，
 * 用一条看护任务兜底，比散在命令与监听器里更可靠。</p>
 *
 * <p><b>身份靠 PDC 不靠名字</b>：{@link PDCKeys#shopNpc()} 存 {@link TeamId#key()}，
 * 与武器/对局壳同一套原则——玩家改名、改 Lore、改村民职业都不影响识别。</p>
 *
 * <p><b>启动顺序</b>：{@link #start()} 先清理上一轮运行留下的残留，再逐队生成，
 * 最后挂上看护任务。清理这层是参考实现没有的额外防护：改了商人位置之后，旧 NPC
 * 仍站在老地方，既点不出菜单也没人认领，只能靠 {@code /kill} 手动收拾。</p>
 */
public final class ShopNpcManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 看护周期（tick）：10 秒一次。开销只有两次 {@code getEntity}，可以放心常驻。 */
    private static final long WATCH_PERIOD_TICKS = 200L;

    /** 判定「残留 NPC 与配置点是不是同一只」的容差（格）。 */
    private static final double PLACE_TOLERANCE = 2.0;

    private final TaketoriPlugin plugin;

    /** 队伍 → 当前这只商人 NPC 的 UUID（只记录本管理器生成/收养的）。 */
    private final Map<TeamId, UUID> npcIds = new EnumMap<>(TeamId.class);

    /** 看护任务句柄；{@link #stop()} 必须取消，否则重载插件会留下孤儿任务。 */
    private BukkitTask watchTask;

    public ShopNpcManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 启停

    /**
     * 插件启用时调用：清理残留 → 逐队生成 → 挂看护任务。
     *
     * <p>配置里关掉商店（{@code shop.npc.enabled=false}）时不生成、也不挂看护任务，
     * 免得每个周期都去问一遍「要不要生成」；但会顺手扫一遍残留——商人带 PDC 标记、
     * {@code setPersistent(true)} 又让它们永不消失，不扫的话关掉开关后世界里还站着两只。</p>
     */
    public void start() {
        if (!plugin.config().shopNpcEnabled()) {
            purgeAll();
            plugin.getLogger().info("商店 NPC 已关闭（shop.npc.enabled=false），已清理残留商人");
            return;
        }
        cancelWatch();
        adoptOrPurge();   // 位置仍对的残留就地收养，对不上的直接移除
        for (TeamId team : TeamId.values()) {
            if (isNpcAlive(team)) {
                continue;   // 刚才收养到一只可用的，不必再生成第二只
            }
            Location location = npcLocation(team);
            if (location == null) {
                plugin.getLogger().info(team.display() + " 商店 NPC 没有可用位置，跳过生成"
                        + "（用 /taketori shop setnpc " + team.key() + " 站到位置后设置）");
                continue;
            }
            spawn(team, location);
        }
        watchTask = plugin.scheduler().runTimerTask(this::ensureAlive, WATCH_PERIOD_TICKS, WATCH_PERIOD_TICKS);
    }

    /** 插件卸载时调用：停看护任务 + 移除所有商人（世界即将卸载，别留孤儿实体）。 */
    public void stop() {
        cancelWatch();
        purgeAll();
    }

    /**
     * 移除世界上所有商店商人。
     *
     * <p>用 PDC 标记扫世界，而不是只删 {@link #npcIds} 里记着的那几只：
     * 记录只覆盖本进程生成过的实体，重启前留下的、或生成到一半崩掉的都在记录之外。</p>
     */
    private void purgeAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (teamOfNpc(entity) != null) {
                    entity.remove();
                }
            }
        }
        npcIds.clear();
    }

    private void cancelWatch() {
        if (watchTask != null) {
            watchTask.cancel();
            watchTask = null;
        }
    }

    // ---------------------------------------------------------------- 位置

    /**
     * 读取某队商人的目标位置。
     *
     * <p>世界名没配时用大厅世界；配了但那个世界当前没加载，则返回 {@code null} 让这一轮跳过——
     * 不能回落，否则多世界服务器卸掉空世界时会在错误的世界里生成一只，还会把配置世界里
     * 那只「位置对不上」的旧商人当成残留删掉。坐标全为 0 时回落到大厅出生点
     * （「0,0,0」几乎不可能是有意的配置，更像是没填）。都拿不到也返回 {@code null}，
     * 由调用方决定跳过——这里不抛异常，因为「没配位置」是合法状态。</p>
     */
    public Location npcLocation(TeamId team) {
        String worldName = plugin.config().shopNpcWorld(team);
        Location lobbySpawn = plugin.lobby() == null ? null : plugin.lobby().spawn();
        World world;
        if (worldName != null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;   // 配置的世界没加载：先别动，等它加载回来再看护任务会补
            }
        } else {
            world = lobbySpawn == null ? null : lobbySpawn.getWorld();
        }
        if (world == null) {
            return null;   // 世界还没加载：交给看护任务下一轮重试
        }
        double x = plugin.config().shopNpcX(team);
        double y = plugin.config().shopNpcY(team);
        double z = plugin.config().shopNpcZ(team);
        float yaw = plugin.config().shopNpcYaw(team);
        if (x == 0.0 && y == 0.0 && z == 0.0 && lobbySpawn != null) {
            return new Location(world, lobbySpawn.getX(), lobbySpawn.getY(), lobbySpawn.getZ(), yaw, 0.0F);
        }
        return new Location(world, x, y, z, yaw, 0.0F);
    }

    /**
     * 把某队商人的位置写进 config.yml 并立刻重生。
     *
     * <p>先写盘再生成：写盘失败时旧 NPC 已经被移除、新 NPC 尚未出现，
     * 宁可「暂时没有商人」，也不要「玩家以为位置改了、其实只是内存里改了」。</p>
     */
    public void setNpcLocation(TeamId team, Location location) {
        if (team == null || location == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        // 超出世界高度的坐标会让 spawnEntity 抛异常，玩家会看到一条看不懂的报错；
        // 这里直接拒绝，位置与配置都保持原样。
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()) {
            return;
        }
        removeNpc(team);
        String path = "shop.npc." + team.key();
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.saveConfig();
        spawn(team, location.clone());
    }

    // ---------------------------------------------------------------- 识别

    /** 这只实体是不是商店商人（任一队伍）。 */
    public boolean isShopNpc(Entity entity) {
        return teamOfNpc(entity) != null;
    }

    /** 商店商人属于哪一队；不是商人（或标记损坏）返回 {@code null}。 */
    public TeamId teamOfNpc(Entity entity) {
        if (entity == null) {
            return null;
        }
        String key = entity.getPersistentDataContainer().get(PDCKeys.shopNpc(), PersistentDataType.STRING);
        return key == null ? null : TeamId.byName(key);
    }

    // ---------------------------------------------------------------- 内部：生成与清理

    /**
     * 清理上一轮运行留下的商人实体。
     *
     * <p>三种情况都当残留处理：标记里的队伍已不存在、配置里没有位置、实体站的位置
     * 与配置点差出容差。位置仍正确的那种则就地「收养」——按当前配置重设外观与属性，
     * 并记进 {@link #npcIds}，避免下面再生成一只重复的。</p>
     */
    private void adoptOrPurge() {
        // 配置点所在区块没加载时，world.getEntities() 里看不到那只旧商人，
        // 后面 spawn() 就会再生成一只 —— 先把配置点所在区块拉起来再扫。
        for (TeamId team : TeamId.values()) {
            Location configured = npcLocation(team);
            World world = configured == null ? null : configured.getWorld();
            if (world != null) {
                world.getChunkAt(configured).load();
            }
        }
        for (World world : Bukkit.getWorlds()) {
            // 拷贝一份再遍历：remove() 会改动世界的实体表
            List<Entity> entities = new ArrayList<>(world.getEntities());
            for (Entity entity : entities) {
                String key = entity.getPersistentDataContainer()
                        .get(PDCKeys.shopNpc(), PersistentDataType.STRING);
                if (key == null) {
                    continue;   // 不是商人，不碰
                }
                TeamId team = TeamId.byName(key);
                Location expected = team == null ? null : npcLocation(team);
                if (team == null || expected == null || !samePlace(expected, entity.getLocation())) {
                    entity.remove();
                    continue;
                }
                if (!(entity instanceof Villager villager)) {
                    entity.remove();   // 标记在、类型不对：宁可丢掉，也别留个点不动的怪东西
                    continue;
                }
                configure(villager, team);
                npcIds.put(team, villager.getUniqueId());
            }
        }
    }

    /** 两个位置是否落在同一处（世界相同且水平距离在容差内）。 */
    private boolean samePlace(Location expected, Location actual) {
        if (expected == null || actual == null || expected.getWorld() == null || actual.getWorld() == null) {
            return false;
        }
        if (!expected.getWorld().equals(actual.getWorld())) {
            return false;
        }
        return expected.distanceSquared(actual) <= PLACE_TOLERANCE * PLACE_TOLERANCE;
    }

    /** 生成一只商人；生成前先移除该队已有的那只，保证「一队一只」。 */
    private void spawn(TeamId team, Location location) {
        removeNpc(team);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Entity entity = world.spawnEntity(location, EntityType.VILLAGER);
        if (entity instanceof Villager villager) {
            configure(villager, team);
            npcIds.put(team, villager.getUniqueId());
        } else {
            entity.remove();
        }
    }

    /**
     * 把一只村民按商店商人的规格配好。
     *
     * <p>{@code setAI(false)} + {@code setGravity(false)} 让它站桩不动；
     * {@code setInvulnerable(true)} 与 {@code ShopMenu} 的伤害拦截是双保险
     * （拦截挡住玩家，无敌挡住环境伤害与其它插件的伤害）；{@code setPersistent(true)}
     * 防止区块卸载时被当成自然生成的生物清掉。</p>
     */
    private void configure(Villager villager, TeamId team) {
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setGravity(false);
        villager.setPersistent(true);
        // 给个职业，别让商人长得像刚出生的野村民：红队铁匠、蓝队工具匠。
        // 交易表用不上——右键在 ShopMenu 里就被取消了，原版交易界面根本弹不出来。
        villager.setProfession(team == TeamId.RED
                ? Villager.Profession.WEAPONSMITH
                : Villager.Profession.TOOLSMITH);
        villager.customName(npcName(team));
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(PDCKeys.shopNpc(), PersistentDataType.STRING, team.key());
    }

    /**
     * 商人的显示名。
     *
     * <p>配置里的名字有两种写法：MiniMessage（{@code <red>红队商人}）与传统颜色码
     * （{@code §c红队商人}，config.yml 的默认值就是这种）。MiniMessage 只认尖括号，
     * 遇到 {@code §} 会把它当普通字符原样显示，所以这里按是否含 {@code §} 分流。</p>
     */
    private Component npcName(TeamId team) {
        String raw = plugin.config().shopNpcName(team);
        if (raw == null || raw.isBlank()) {
            return Component.text(team.display());
        }
        return raw.indexOf('§') >= 0
                ? LegacyComponentSerializer.legacySection().deserialize(raw)
                : MINI.deserialize(raw);
    }

    /**
     * 看护：NPC 丢了就补。
     *
     * <p>比参考实现多补一种情况——{@code start()} 时世界还没加载导致压根没生成过
     * （{@code npcIds} 里没有这一队）。那种情况下「按 UUID 查」永远查不到，
     * 于是永远不会自愈；这里改成「查不到就试着按位置补一只」。</p>
     */
    private void ensureAlive() {
        for (TeamId team : TeamId.values()) {
            if (isNpcAlive(team)) {
                continue;
            }
            npcIds.remove(team);
            Location location = npcLocation(team);
            if (location != null) {
                spawn(team, location);
            }
        }
    }

    /** 该队商人是否还活着（UUID 仍在、实体有效）。 */
    private boolean isNpcAlive(TeamId team) {
        UUID id = npcIds.get(team);
        if (id == null) {
            return false;
        }
        Entity entity = Bukkit.getEntity(id);
        return entity != null && entity.isValid() && !entity.isDead();
    }

    /** 移除该队商人（没有记录时静默返回）。 */
    private void removeNpc(TeamId team) {
        UUID id = npcIds.remove(team);
        if (id == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.remove();
        }
    }
}
