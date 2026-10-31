package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.worlds.WorldScope;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.match.room.RoomManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * 大厅（Hub）：玩家集结点、快速匹配的入口、角色选择与告示牌注册。
 *
 * <p>BedWars 式流程：玩家进服传送到大厅 → 点「加入对局」告示牌 / 菜单按钮 /
 * 指令发起 {@link #quickJoin} → 自动进入人最多的等待房间（等待区可退出、可换房）
 * → 房间自己倒计时、开局分队、结算后把人送回大厅。大厅不再维护队列与提前分队，
 * "玩家属于哪个房间"的唯一权威在 {@link RoomManager}。</p>
 */
public final class LobbyManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final File file;

    private CuboidRegion region;
    private Location spawn;
    /** 大厅落点缺失的告警只打一次，避免每次退房都刷警告。 */
    private boolean warnedLobbyFallback;
    private final List<LobbySign> signs = new CopyOnWriteArrayList<>();
    /** 实时房间状态牌（绑定模板；文本每秒刷新，点击直接加入/旁观/创建）。 */
    private final List<StatusSign> statusSigns = new CopyOnWriteArrayList<>();
    /** 快照药水保留登记（一次性，clearTransient 消费）：开局前自带的药水不洗掉。 */
    private final java.util.Map<java.util.UUID, List<PotionEffect>> preservedPotions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 实时房间状态牌：绑定模板 id 的一个告示牌坐标。 */
    public record StatusSign(String world, int x, int y, int z, String templateId) {

        public boolean matches(Block block) {
            return block.getWorld().getName().equals(world)
                    && block.getX() == x && block.getY() == y && block.getZ() == z;
        }
    }

    public LobbyManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lobby.yml");
    }

    // ---------------------------------------------------------------- 配置

    public void load() {
        signs.clear();
        statusSigns.clear();   // 重载同样要清空状态牌，否则同一块牌子随重载次数重复累积
        region = null;
        spawn = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        region = CuboidRegion.read(yaml.getConfigurationSection("region"));

        ConfigurationSection spawnSection = yaml.getConfigurationSection("spawn");
        if (spawnSection != null && spawnSection.isString("world")) {
            String spawnWorldName = spawnSection.getString("world");
            var world = org.bukkit.Bukkit.getWorld(spawnWorldName);
            if (world != null) {
                spawn = new Location(world,
                        spawnSection.getDouble("x"), spawnSection.getDouble("y"), spawnSection.getDouble("z"),
                        (float) spawnSection.getDouble("yaw"), (float) spawnSection.getDouble("pitch"));
            } else {
                // 世界没加载就是没有落点：不吭声会让退房时静默不传送（人留在已回收的对局世界里）
                plugin.getLogger().warning("[lobby] lobby.yml 里的出生点世界 " + spawnWorldName
                        + " 没有加载，退房时先送回服务器主世界出生点。请确认这个世界在 bukkit.yml 的 worlds 里，"
                        + "或站到大厅里重新执行 /taketori lobby set。");
            }
        }

        ConfigurationSection signSection = yaml.getConfigurationSection("signs");
        if (signSection != null) {
            for (String key : signSection.getKeys(false)) {
                LobbySign sign = LobbySign.read(signSection.getConfigurationSection(key));
                if (sign != null) {
                    signs.add(sign);
                }
            }
        }
        ConfigurationSection statusSection = yaml.getConfigurationSection("status-signs");
        if (statusSection != null) {
            for (String key : statusSection.getKeys(false)) {
                ConfigurationSection section = statusSection.getConfigurationSection(key);
                if (section == null || !section.isString("world") || !section.isString("template")) {
                    continue;
                }
                statusSigns.add(new StatusSign(section.getString("world"),
                        section.getInt("x"), section.getInt("y"), section.getInt("z"),
                        section.getString("template")));
            }
        }
        plugin.getLogger().info("已载入大厅：出生点 " + (spawn == null ? "未设置" : "已设置")
                + " / 告示牌 " + signs.size() + " 个");
        if (spawn == null) {
            plugin.getLogger().warning("[lobby] 大厅出生点未设置：退房/结算时会先送回服务器主世界出生点，"
                    + "站到大厅位置执行 /taketori lobby set 才能真正回大厅。");
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (region != null) {
            region.write(yaml.createSection("region"));
        }
        if (spawn != null && spawn.getWorld() != null) {
            ConfigurationSection section = yaml.createSection("spawn");
            section.set("world", spawn.getWorld().getName());
            section.set("x", spawn.getX());
            section.set("y", spawn.getY());
            section.set("z", spawn.getZ());
            section.set("yaw", (double) spawn.getYaw());
            section.set("pitch", (double) spawn.getPitch());
        }
        int index = 0;
        for (LobbySign sign : signs) {
            sign.write(yaml.createSection("signs." + (index++)));
        }
        int statusIndex = 0;
        for (StatusSign sign : statusSigns) {
            ConfigurationSection section = yaml.createSection("status-signs." + (statusIndex++));
            section.set("world", sign.world());
            section.set("x", sign.x());
            section.set("y", sign.y());
            section.set("z", sign.z());
            section.set("template", sign.templateId());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 lobby.yml 失败", ex);
        }
    }

    public CuboidRegion region() {
        return region;
    }

    public void setRegion(CuboidRegion region) {
        this.region = region;
    }

    public Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    public void setSpawn(Location location) {
        this.spawn = location == null ? null : location.clone();
    }

    public boolean isConfigured() {
        return spawn != null;
    }

    // ---------------------------------------------------------------- 告示牌

    public List<LobbySign> signs() {
        return List.copyOf(signs);
    }

    public LobbySign signAt(Block block) {
        for (LobbySign sign : signs) {
            if (sign.matches(block)) {
                return sign;
            }
        }
        return null;
    }

    public void addSign(LobbySign sign) {
        // 同一坐标重复添加时覆盖旧动作
        signs.removeIf(existing -> existing.world().equals(sign.world())
                && existing.x() == sign.x() && existing.y() == sign.y() && existing.z() == sign.z());
        signs.add(sign);
    }

    /** 注册告示牌并立即保存（指令路径用；返回新注册的告示牌）。 */
    public LobbySign addSignAndSave(String world, int x, int y, int z, String action) {
        LobbySign sign = new LobbySign(world, x, y, z, action);
        addSign(sign);
        save();
        return sign;
    }

    public boolean removeSignAt(Block block) {
        boolean actionRemoved = signs.removeIf(sign -> sign.matches(block));
        boolean statusRemoved = statusSigns.removeIf(sign -> sign.matches(block));
        if (actionRemoved || statusRemoved) {
            save();
        }
        return actionRemoved || statusRemoved;
    }

    // ---------------------------------------------------------------- 实时状态牌

    public List<StatusSign> statusSigns() {
        return List.copyOf(statusSigns);
    }

    /** 准星所指的状态牌；不是状态牌返回 null。 */
    public StatusSign statusSignAt(Block block) {
        for (StatusSign sign : statusSigns) {
            if (sign.matches(block)) {
                return sign;
            }
        }
        return null;
    }

    /** 注册实时状态牌并立即保存（同一坐标重复添加时覆盖旧模板）。 */
    public StatusSign addStatusSignAndSave(String world, int x, int y, int z, String templateId) {
        StatusSign sign = new StatusSign(world, x, y, z, templateId);
        statusSigns.removeIf(existing -> existing.world().equals(world)
                && existing.x() == x && existing.y() == y && existing.z() == z);
        statusSigns.add(sign);
        save();
        return sign;
    }

    /** 状态牌展示的房间：该模板下第一个可加入（等待/倒计时）的房；没有则第一个进行中的房。 */
    private GameRoom statusTargetRoom(String templateId) {
        GameRoom fallback = null;
        for (GameRoom room : plugin.rooms().rooms()) {
            if (!room.templateId().equals(templateId)) {
                continue;
            }
            GameRoom.Phase phase = room.phase();
            if (phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING) {
                return room;
            }
            if (fallback == null && phase != GameRoom.Phase.ENDING) {
                fallback = room;
            }
        }
        return fallback;
    }

    /** 状态牌点击：等待房加入、进行中观战（有缺口则补位）、无房创建。 */
    public void clickStatusSign(Player player, String templateId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GameRoom room = statusTargetRoom(templateId);
        if (room == null) {
            createRoom(player, templateId);
            return;
        }
        GameRoom.Phase phase = room.phase();
        if (phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING) {
            joinRoom(player, room.id());
            return;
        }
        if (phase == GameRoom.Phase.CAGED || phase == GameRoom.Phase.PLAYING) {
            // 参赛者不许从状态牌把自己切成观众：切了就回不了战场（计分/退场/重连全被拒）
            GameRoom mine = plugin.rooms().roomOf(player);
            if (mine == room && room.teamOf(player.getUniqueId()) != null) {
                player.sendMessage(MINI.deserialize("<gray>你正在对局中，不能切换为旁观。"
                        + "要结束整局请找管理员执行 <white>/taketori match stop"));
                return;
            }
            if (plugin.rooms().roomOf(player) == null && room.reinforcementTeam() != null) {
                joinRoom(player, room.id());
                return;
            }
            if (plugin.spectator().isAudience(player)) {
                plugin.spectator().leaveAudience(player);
                return;
            }
            plugin.spectator().enterAudience(player, room.spectatorViewPoint(), room);
            player.sendMessage(plugin.config().messages().get("room.spectating", "room", room.display()));
            return;
        }
        player.sendMessage(MINI.deserialize("<gray>该房间正在结算，很快会回到等待状态，稍后再试。"));
    }

    /** 每秒刷新全部状态牌文本（由主类计时器驱动；牌子被破坏时自动从列表摘除）。 */
    public void refreshStatusSigns() {
        for (StatusSign sign : statusSigns) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(sign.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(sign.x(), sign.y(), sign.z());
            if (!(block.getState() instanceof org.bukkit.block.Sign signState)) {
                // 牌子已被破坏或坐标被替换为别的方块：条目失效，自动摘除
                // （旧逻辑只在空气时摘除，替换成实体方块的旧条目会永久残留）
                statusSigns.removeIf(existing -> existing.matches(block));
                continue;
            }
            GameRoom room = statusTargetRoom(sign.templateId());
            String[] lines = statusLines(sign.templateId(), room);
            org.bukkit.block.sign.Side front = org.bukkit.block.sign.Side.FRONT;
            boolean changed = false;
            for (int i = 0; i < 4; i++) {
                if (!lines[i].equals(signState.getSide(front).getLine(i))) {
                    changed = true;
                }
                signState.getSide(front).setLine(i, lines[i]);
            }
            if (changed) {
                signState.update();
            }
        }
    }

    /** 状态牌四行文本（§ 色码）。 */
    private String[] statusLines(String templateId, GameRoom room) {
        String title = "§6§l[月影合战]";
        String mapLine = "§f" + templateId;
        if (room == null) {
            return new String[]{title, mapLine, "§7暂无房间", "§e▶ 点击创建"};
        }
        String stateLine;
        String clickLine;
        switch (room.phase()) {
            case WAITING -> {
                stateLine = "§a等待中 " + room.waitingCount() + "/" + room.maxPlayers();
                clickLine = "§e▶ 点击加入";
            }
            case STARTING -> {
                stateLine = "§e倒计时 " + room.countdownSeconds() + "s";
                clickLine = "§e▶ 点击加入";
            }
            case CAGED -> {
                stateLine = "§d开局准备";
                clickLine = "§7已开始";
            }
            case PLAYING -> {
                stateLine = "§c游戏中 " + room.onlineParticipantCount() + "/" + room.maxPlayers();
                clickLine = "§b▶ 点击观战 / 补位";
            }
            default -> {
                stateLine = "§7结算中";
                clickLine = "§7请稍候";
            }
        }
        return new String[]{title, mapLine, stateLine, clickLine};
    }

    /** 启动状态牌刷新任务（主类启用时调用，每秒刷新一次文本）。 */
    public void startStatusTask() {
        plugin.scheduler().runTimerTask(this::refreshStatusSigns, 20L, 20L);
    }

    // ---------------------------------------------------------------- 快速加入 / 离开房间

    /**
     * 快速加入：告示牌 {@code join} / 玩家菜单匹配按钮 / {@code /taketori lobby join}
     * 的<b>唯一入口</b>。自动进入等待人数最多的可加入房间；加入/失败文案与房间播报统一处理。
     */
    public void quickJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        RoomManager.JoinResult result = plugin.rooms().quickJoin(player);
        if (result != null) {
            handleJoinResult(player, result, null);
            return;
        }
        // 没有任何房间：降临月宫——用默认模板自动建房并加入（异步完成后自动传送）
        plugin.rooms().createDefaultAndJoin(player);
    }

    /**
     * 创建房间（降临月宫）：用指定模板（null = 默认模板）异步复制出新世界，
     * 完成后创建者自动进入等待区。所有玩家可用，受 room.max-rooms 上限约束。
     */
    public void createRoom(Player player, String templateId) {
        plugin.rooms().createRoom(player, templateId);
    }

    /**
     * 创建房间（降临月宫）：同时指定模板与初始模式。
     *
     * @param mode null 表示沿用全局配置
     */
    public void createRoom(Player player, String templateId,
                           com.taketori.kassen.core.match.sengoku.SengokuMode mode) {
        plugin.rooms().createRoom(player,
                com.taketori.kassen.paper.match.room.RoomCreateRequest.of(templateId, mode));
    }

    /**
     * 删除房间：房主可删自己的等待房，管理员（taketori.admin）可删任意等待房；
     * 进行中的房间不在此删（先 /taketori match stop，结算完成后自动回收世界）。
     */
    public void deleteRoom(Player player, String roomId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GameRoom room = plugin.rooms().room(roomId);
        if (room == null) {
            player.sendMessage(plugin.config().messages().get("room.arena-not-found", "id", roomId));
            return;
        }
        boolean owner = player.getUniqueId().equals(room.creatorId());
        if (!owner && !player.hasPermission("taketori.admin")) {
            player.sendMessage(plugin.config().messages().get("room.room-delete-denied"));
            return;
        }
        if (room.phase() != GameRoom.Phase.WAITING) {
            player.sendMessage(MINI.deserialize("<red>该房间已开局，不能直接删除。"
                    + "<gray>先用 <white>/taketori match stop " + roomId + "</white> 结束，结算后自动回收世界。"));
            return;
        }
        plugin.rooms().destroyRoom(room, true);
        player.sendMessage(plugin.config().messages().get("room.room-deleted", "room", room.display()));
    }

    /** 加入指定房间（房间列表 GUI / 管理指令用）；等待中允许自动换房。 */
    public void joinRoom(Player player, String roomId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        handleJoinResult(player, plugin.rooms().joinRoom(player, roomId), roomId);
    }

    private void handleJoinResult(Player player, RoomManager.JoinResult result, String requestedId) {
        GameRoom room = result.room();
        switch (result.outcome()) {
            case SUCCESS -> {
                message(player, "room.join-success", "room", room.display());
                room.broadcastMessage("room.join-broadcast", "player", player.getName(),
                        "count", room.waitingCount(), "max", room.maxPlayers());
            }
            case SWITCHED -> {
                message(player, "room.switch-room", "room", room.display());
                room.broadcastMessage("room.join-broadcast", "player", player.getName(),
                        "count", room.waitingCount(), "max", room.maxPlayers());
            }
            case ALREADY_IN, IN_GAME -> message(player, "room.already-in", "room", room.display());
            case FULL -> message(player, "room.room-full", "room", room.display());
            case STARTED -> message(player, "room.room-started");
            case CREATED -> {
                // 派对整队无房可装、已自动建房：createRoom 已发"正在复制"消息，这里不重复
                // 房间建好后整队会被自动带进房（finishCreate 的派对跟进逻辑）
            }
            case NOT_READY -> message(player, "room.list-not-ready",
                    "missing", room == null ? "" : room.arena().missingHint());
            case REINFORCED -> message(player, "room.reinforced", "room", room.display());
            case NOT_FOUND -> message(player, "room.arena-not-found",
                    "id", requestedId == null ? "?" : requestedId);
        }
    }

    /**
     * 主动离开房间回大厅（{@code /taketori leave}、告示牌 leave、菜单按钮、鹦鹉螺壳 GUI 的统一入口）：
     * <ul>
     *   <li>观众：退出观战并回大厅（SpectatorManager.leaveAudience）；阵亡旁观者提示等待复活；</li>
     *   <li>WAITING/STARTING：释放等待名额（房间倒计时自动重算），房间内播报，传送回大厅；</li>
     *   <li>CAGED/PLAYING 的参赛者：走 {@link #leaveMatch} 主动离局（释放槽位、可被补位、保留重连窗口）；</li>
     *   <li>ENDING / 无房间：直接送大厅。</li>
     * </ul>
     */
    public void returnToLobby(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (plugin.spectator().leaveAudience(player)) {
            return;
        }
        if (plugin.spectator().isSpectator(player)) {
            player.sendMessage(MINI.deserialize(
                    "<gray>你正在等待复活（阵亡旁观），稍后会自动回到战场，不需要退出。"));
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            message(player, "room.not-in-room");
            sendToLobby(player);
            return;
        }
        if ((room.phase() == GameRoom.Phase.CAGED || room.phase() == GameRoom.Phase.PLAYING)
                && room.teamOf(player.getUniqueId()) != null) {
            // 对局中主动离局：不再硬拒绝，改为释放槽位 + 回大厅（详见 leaveMatch）
            leaveMatch(player);
            return;
        }
        boolean waitingRoom = room.phase() == GameRoom.Phase.WAITING
                || room.phase() == GameRoom.Phase.STARTING;
        String name = player.getName();
        plugin.rooms().leave(player.getUniqueId());
        if (waitingRoom) {
            room.broadcastMessage("room.leave-broadcast", "player", name,
                    "count", room.waitingCount(), "max", room.maxPlayers());
        }
        message(player, "room.leave");
        sendToLobby(player);
    }

    /**
     * 对局中主动离局（鹦鹉螺壳 GUI 的「退出对局」/ 对局中执行 {@code /taketori leave}）。
     *
     * <p>三条约束同时成立，顺序不能换：</p>
     * <ol>
     *   <li><b>不判负</b>：离局只把自己从在场名单移除（{@code quitMatch}），槽位空出、
     *       可被快速加入补位；对局继续，缺人超宽限期仍由既有逻辑判负，离局本身不额外惩罚。</li>
     *   <li><b>保留重连窗口</b>：先登记 {@link com.taketori.kassen.paper.match.RejoinManager}
     *       会话再解绑（{@code leaveForRejoin}），窗口内 {@code /taketori rejoin} 能回原队；
     *       配置关掉时走普通 {@code leave}，立即释放。</li>
     *   <li><b>离局即把开局前封存的背包还给玩家</b>：两条离局路径在 CAGED/PLAYING 阶段都
     *       <b>不</b>还原背包。若就这么回大厅，快照会一直留在房间 {@code backups} 里，
     *       而 {@code endCleanup} / {@code shutdown} 会对<b>在线</b>玩家强制写回快照
     *       （{@code restoreTo} 先 clear 再写）——那时他人在大厅或已进别的房间，
     *       身上和大厅里拿到的东西会被这份旧快照整个覆盖。当场还原即消除这个隐患；
     *       窗口内 {@code rejoin} 时再重新封存一遍（见 {@code RejoinManager.tryRejoin}），
     *       所以不会出现「改了背包就裸装回战场」。</li>
     * </ol>
     */
    public void leaveMatch(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            sendToLobby(player);
            return;
        }
        var team = room.teamOf(player.getUniqueId());
        String name = player.getName();
        // 必须同时满足两个开关，缺一不可：只有 leave.rejoin-window 打开、
        // 而 room.rejoin-seconds=0 时，RejoinManager.register 会静默跳过（时限 0 = 关闭），
        // 于是 teams 条目被保留却没有任何会话能到期释放它——槽位永久占用、
        // onlineParticipantCount 永远算他在场，房间世界再也回收不了。
        boolean keepEntry = plugin.config().roomLeaveRejoinWindow()
                && plugin.rejoin().rejoinSeconds() > 0
                && team != null;
        if (keepEntry) {
            // 登记重连会话 → 保留 teams 条目与槽位（窗口内别人补不进来）
            plugin.rejoin().register(player.getUniqueId(), room, team);
            plugin.rooms().leaveForRejoin(player.getUniqueId());
        } else {
            plugin.rooms().leave(player.getUniqueId());
        }
        // 两条路径统一：把开局前封存的背包还给玩家，卸下对局装备再回大厅。
        // 快照被消费掉，房间里不再留一份可能覆盖他大厅物品的旧状态。
        room.restoreInventoryIfAny(player);
        // CAGED 阶段离局必须解除笼内状态：enterCageState 设了 setInvulnerable(true)
        // 与满级缓慢，而 sendToLobby 只清药水、不清无敌——不解除的话玩家会带着
        // 无敌状态回大厅（还能免疫大厅 PVO/环境伤害）。
        room.exitCageState(player);
        room.broadcastMessage("room.leave-match-broadcast", "player", name,
                "team", team == null ? "—" : team.display());
        message(player, keepEntry ? "room.leave-match-rejoinable" : "room.leave-match");
        sendToLobby(player);
    }


    // ---------------------------------------------------------------- 播报

    /**
     * 大厅播报：默认只发给<b>大厅世界</b>里的玩家（{@code worlds.broadcast-scope: world}）。
     *
     * <p>多世界服务器上，排队与开局的消息不该刷到其它世界的玩家；把 scope 配成 {@code all}
     * 就恢复旧的全服广播。大厅出生点还没配（拿不到世界）时同样按全服处理，避免消息静默消失。</p>
     */
    public void broadcastLobby(String miniMessage) {
        String scope = plugin.config().broadcastScope();
        Location target = spawn();
        String world = target == null || target.getWorld() == null ? null : target.getWorld().getName();
        if (world == null || "all".equalsIgnoreCase(scope)) {
            plugin.getServer().sendMessage(MINI.deserialize(miniMessage));
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (WorldScope.shouldReceive(scope, Set.of(world), player.getWorld().getName())) {
                player.sendMessage(MINI.deserialize(miniMessage));
            }
        }
    }

    // ---------------------------------------------------------------- 传送

    /** 把玩家送到大厅，并清掉技能残留状态。 */
    public void sendToLobby(Player player) {
        sendToLocation(player, spawnOrFallback());
    }

    /**
     * 大厅落点；未设置或所在世界已卸载时回落到服务器主世界出生点。
     *
     * <p>{@link #spawn()} 为 {@code null} 时直接不传送，玩家会留在正在被回收的对局世界里
     * （世界卸载失败 → 卡在半空/虚空，且下次重试仍失败）。退房是必经路径，宁可送到主世界
     * 出生点并告警，也不能把人丢在原地。</p>
     */
    private Location spawnOrFallback() {
        Location configured = spawn();
        if (configured != null && configured.getWorld() != null) {
            return configured;
        }
        if (!warnedLobbyFallback) {
            warnedLobbyFallback = true;
            plugin.getLogger().warning("[lobby] 没有可用的返回落点，已改送主世界出生点"
                    + "（站到大厅位置执行 /taketori lobby set 可修正）。");
        }
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            if (world != null) {
                return world.getSpawnLocation();
            }
        }
        return null;
    }

    /**
     * 把玩家送到指定落点，并清掉技能残留状态。
     *
     * <p>与 {@link #sendToLobby} 的差别只有落点：对局结束后的回程落点由
     * {@code lobby.return-to-entry} 决定，其余收尾动作（清药水、解除旁观、
     * 撤记分板）两条路径必须完全一致，所以共用这一个实现。</p>
     *
     * @param player 目标玩家
     * @param target 落点；{@code null} 或世界已卸载时只做清场不传送
     */
    public void sendToLocation(Player player, Location target) {
        if (player == null || !player.isOnline()) {
            return;
        }
        clearTransient(player);
        if (target != null && target.getWorld() != null) {
            try {
                player.teleport(target);
            } catch (RuntimeException teleportError) {
                // 落点世界在传送瞬间被卸载（房间回收/模板写回）时 Bukkit 会抛异常：
                // 吞掉并记日志，后面的清场与记分板还原照做，别让一个人把整队送回流程打断
                plugin.getLogger().log(Level.WARNING, "[lobby] 由 " + describe(player.getLocation())
                        + " 传送到 " + describe(target) + " 失败：" + teleportError.getMessage());
            }
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        // 回大厅：按所属房间（参赛者或观众）找到对应房间记分板并隐藏；都没有就直接还原主记分板
        var room = plugin.rooms().roomOf(player);
        if (room == null) {
            room = plugin.spectator().audienceRoom(player.getUniqueId());
        }
        if (room != null) {
            room.scoreboard().hide(player);
        } else if (player.isOnline()) {
            player.setScoreboard(org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 位置的可读描述（日志用）：世界名 + 取整坐标，仅供排障定位。 */
    private static String describe(Location location) {
        if (location == null) {
            return "null";
        }
        String worldName = location.getWorld() == null ? "?" : location.getWorld().getName();
        return worldName + "(" + location.getBlockX() + "," + location.getBlockY() + ","
                + location.getBlockZ() + ")";
    }

    /**
     * 快照药水保留登记：开局前自带的药水效果被 {@code restoreInventoryIfAny} 还原后登记到这里，
     * {@link #clearTransient} 只清对局期间获得的临时效果。一次性：下次 clearTransient 消费后失效。
     */
    public void preservePotions(java.util.UUID playerId, List<PotionEffect> potions) {
        if (playerId != null && potions != null && !potions.isEmpty()) {
            preservedPotions.put(playerId, List.copyOf(potions));
        }
    }

    /** 清掉药水效果与技能相关状态（进大厅 / 出对局时用）。 */
    public void clearTransient(Player player) {
        // 消费保留登记：快照还原的自带药水跳过，其余（对局期间获得的）全部清掉
        List<PotionEffect> keep = preservedPotions.remove(player.getUniqueId());
        java.util.Set<PotionEffectType> keepTypes = new java.util.HashSet<>();
        if (keep != null) {
            for (PotionEffect effect : keep) {
                if (effect != null) {
                    keepTypes.add(effect.getType());
                }
            }
        }
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            if (!keepTypes.contains(effect.getType())) {
                player.removePotionEffect(effect.getType());
            }
        }
        PotionEffectType absorption = plugin.versions().potionEffect("ABSORPTION");
        if (absorption == null || !keepTypes.contains(absorption)) {
            player.setAbsorptionAmount(0.0D);
        }
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
    }

    /** 玩家退出时清掉药水保留登记，避免 UUID 条目无限累积（否则重连窗口结束后永不回收）。 */
    public void forget(java.util.UUID playerId) {
        if (playerId != null) {
            preservedPotions.remove(playerId);
        }
    }

    public boolean isInLobby(Player player) {
        if (player == null || region == null) {
            return false;
        }
        return region.contains(player.getLocation());
    }

    /** 发送一条 messages.yml 文案（占位符按 key/value 成对传入）。 */
    private void message(Player player, String key, Object... placeholders) {
        player.sendMessage(plugin.config().messages().get(key, placeholders));
    }
}
