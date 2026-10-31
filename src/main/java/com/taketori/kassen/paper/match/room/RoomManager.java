package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaDef;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间注册表（月宫制 / 动态房间）：房间由玩家按需创建，每个房间独占一个从
 * {@code moonmaps/<模板>} 复制出来的专属世界（{@code kassen_<序号>}），结算完成后整场回收。
 *
 * <ul>
 *   <li>{@link #createRoom}：校验模板与房间数上限 → 异步复制模板世界文件夹（跳过 session.lock）
 *       → 主线程 {@link WorldCreator} 加载 → 统一世界规则（昼夜/天气/自然刷怪/火焰蔓延关闭、
 *       死亡保留物品开启）→ 克隆模板 {@link ArenaDef}（点位重定向到房间世界）→ 创建者进房；</li>
 *   <li>{@link #destroyRoom}：玩家回大厅 → 停组件 → 卸载世界（不保存）→ 异步删除文件夹
 *       （被占用时延迟重试），触发点：结算收尾完成 / 房主或管理员手动删除等待房 /
 *       等待房全员离线超时 / reload 与停服；</li>
 *   <li>维护全工程<b>唯一</b>的 {@code 玩家 → 房间} 映射，提供快速加入 {@link #quickJoin}
 *       （无房时由调用方走默认模板自动建房）、指定加入 {@link #joinRoom}、退出 {@link #leave}；</li>
 *   <li>每秒 {@link #tickAll()} 驱动各房间计时；启动/重载时清理崩溃残留的世界文件夹。</li>
 * </ul>
 */
public final class RoomManager {

    /** 模板编辑副本的前缀（moonmap load 复制到服务器根目录的世界名），启动时一并清理。 */
    public static final String TEMPLATE_EDIT_PREFIX = "k_tpl_";

    /** 加入/快速加入的结果（调用方据此映射 messages.yml 文案）。 */
    public enum JoinOutcome {
        /** 成功加入（原本不在任何房间）。 */
        SUCCESS,
        /** 从另一个等待中房间换房成功。 */
        SWITCHED,
        /** 玩家已经在目标房间 / 已在等待房间里重复匹配。 */
        ALREADY_IN,
        /** 玩家已经在进行中的房间，不能再加入别的房间。 */
        IN_GAME,
        /** 目标房间满员。 */
        FULL,
        /** 目标房间已开局。 */
        STARTED,
        /** 场地 id 不存在。 */
        NOT_FOUND,
        /** 场地未就绪（缺出生点/基地/刷新区/等待点）。 */
        NOT_READY,
        /** 对局中补位成功（直接编入缺人的队伍参战）。 */
        REINFORCED,
        /** 派对整队无房可装、已为房主自动建房（异步复制中；建房结果由回调播报）。 */
        CREATED
    }

    /** 加入结果：结果类型 + 目标/当前房间（可能为 null）。 */
    public record JoinResult(JoinOutcome outcome, GameRoom room) {
        public boolean ok() {
            return outcome == JoinOutcome.SUCCESS || outcome == JoinOutcome.SWITCHED;
        }
    }

    /** 断线背包暂存的淘汰期限：超过这个时间未取回的备份，随下一次暂存一起清掉（重启本身也会清空）。 */
    private static final long OFFLINE_BACKUP_TTL_MILLIS = 48L * 60L * 60L * 1000L;

    /** 一条断线背包暂存：背包快照 + 暂存时间（用于淘汰长期未取回的备份）。 */
    private record OfflineBackup(GameRoom.InventorySnapshot backup, long storedAtMillis) {
    }

    private final TaketoriPlugin plugin;
    /** 房间 id（自增数字字符串）→ 房间。 */
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    /** 玩家 → 房间：全工程唯一权威归属映射。 */
    private final Map<UUID, GameRoom> playerRooms = new ConcurrentHashMap<>();
    /**
     * 进房前的位置：结算时按 {@code lobby.return-to-entry} 把人送回这里。
     *
     * <p>只在玩家「还没在任何房间里」时记录。等待区换房（A 房→B 房）不覆盖，
     * 因为回程落点应当是最初离开的那个位置，而不是上一个房间的等待区。
     * 进房前的世界在对局期间被卸载时，读取方会检查世界是否存在并回落到大厅。</p>
     */
    private final Map<UUID, Location> entryPoints = new ConcurrentHashMap<>();

    /**
     * 记下进房前的位置（只在玩家还没在任何房间里时记）。
     *
     * @param player 准备进房的玩家
     */
    private void rememberEntry(Player player) {
        UUID id = player.getUniqueId();
        if (!playerRooms.containsKey(id)) {
            Location here = player.getLocation();
            entryPoints.put(id, here.clone());
        }
    }

    /**
     * 取玩家进房前的位置。
     *
     * @param id 玩家 UUID
     * @return 位置副本；没有记录、或记录的世界已被卸载时返回 {@code null}（调用方应回落到大厅）
     */
    public Location entryPointOf(UUID id) {
        Location saved = entryPoints.get(id);
        if (saved == null || saved.getWorld() == null) {
            return null;
        }
        // World 对象在卸载后仍非 null（只是不再注册）：必须按名字回查，否则会把玩家传送进
        // 已卸载的世界——Bukkit 直接抛异常，整队送回流程会在第一个人身上中断
        if (Bukkit.getWorld(saved.getWorld().getName()) == null) {
            return null;
        }
        return saved.clone();
    }
    /** 房间创建序号（世界名 kassen_<序号>；启动时接续历史最大值，避免与待删除的旧文件夹冲突）。 */
    private final AtomicLong sequence = new AtomicLong();
    /**
     * 注册代表次：每次 rebuild（启动 / 重载）自增。世界复制跨线程耗时较长，
     * 复制回调收尾时必须确认代次未变，否则 reload 后会注册出持旧配置的"幽灵房间"。
     */
    private final AtomicLong generation = new AtomicLong();
    /** 正在异步创建中的房间数（计入房间数上限，防并发超建）。 */
    private final AtomicInteger pendingCreations = new AtomicInteger();
    /** 正在异步创建中的房间的创建者（每人限建校验用，防复制窗口期内连点超建）。 */
    private final Set<UUID> pendingCreators = ConcurrentHashMap.newKeySet();
    /** 正在异步处理（load/unload）中的月面模板名，防同一模板的复制/写回操作交叠。 */
    private final Set<String> pendingMoonmaps = ConcurrentHashMap.newKeySet();
    /** 世界文件 IO 线程池：复制与删除都走它，绝不阻塞主线程。 */
    private final ExecutorService worldIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TsukikageKassen-WorldIO");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 对局中断线玩家的背包暂存：玩家在对局中退出时，把该房间为其备份的原背包
     * 转到这里（房间对象可能在结算时丢弃备份），等其重连后原样返还。
     * 玩家不再回归时不能永远持有整套装备快照：每次暂存前按 TTL 淘汰过期条目。
     */
    private final Map<UUID, OfflineBackup> offlineBackups = new ConcurrentHashMap<>();

    public RoomManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 启动 / 重载 / 停止

    /** 按当前配置就绪：清理崩溃残留的世界，不再预建任何房间（房间由玩家按需创建）。 */
    public void rebuild() {
        // 新代次：令所有在途的世界复制回调失效，杜绝 reload 后注册出持旧配置的幽灵房间
        generation.incrementAndGet();
        // reload 语义：全部对局作废——玩家回大厅、房间停止、世界卸载删除，然后清理残留文件夹
        if (!rooms.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (playerRooms.containsKey(player.getUniqueId())
                        || plugin.spectator().isAudience(player)) {
                    plugin.lobby().sendToLobby(player);
                }
            }
            stopAll();
            plugin.spectator().clearAll();
        }
        validateWorldPrefix();
        cleanupStaleWorlds();
        ensureMoonmapsDir();
        // 序号接续历史最大值：新世界名绝不与尚未删除完的旧世界文件夹冲突
        sequence.set(highestExistingWorldNumber());
        plugin.getLogger().info("动态房间注册表已就绪：月面模板 " + plugin.arena().enabledArenas().size()
                + " 个，房间按需创建（同时上限 " + plugin.config().roomMaxRooms() + "）");
    }

    /** 重建的语义化别名（首次启用时调用）。 */
    public void build() {
        rebuild();
    }

    /** 停止全部房间（reload / 插件禁用）：取消任务、还原状态、卸载并回收世界。 */
    public void stopAll() {
        plugin.characterMenu().clearForced();
        plugin.playerMenu().clearTeamForced();
        for (GameRoom room : rooms.values()) {
            room.shutdown();
            unloadAndDeleteNow(room);
        }
        rooms.clear();
        playerRooms.clear();
    }

    /**
     * 启动/重载清理：删除崩溃遗留的房间世界（kassen_*），
     * 模板编辑副本（k_tpl_*）则先转存到 {@code moonmap-recover/<名>} 再删——
     * 那份副本里可能有唯一一份地形，直接删等于把地图丢了。
     * 已加载的先卸载（不保存），文件夹异步删除。
     */
    public void cleanupStaleWorlds() {
        String prefix = plugin.config().roomWorldPrefix();
        // 26.1 的房间世界落在 <level>/dimensions/minecraft 下，旧存档留在服务器根目录：
        // 两处都要扫，否则升级后第一次启动清不掉旧残留，新布局的残留也永远看不见。
        Set<File> roots = new LinkedHashSet<>();
        roots.add(dimensionsRoot());
        roots.add(Bukkit.getWorldContainer());
        for (File root : roots) {
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                String name = child.getName();
                boolean staleRoom = child.isDirectory() && isRoomWorldName(name, prefix);
                boolean staleTemplateCopy = child.isDirectory() && name.startsWith(TEMPLATE_EDIT_PREFIX);
                if (!staleRoom && !staleTemplateCopy) {
                    continue;
                }
                World loaded = Bukkit.getWorld(name);
                if (loaded != null) {
                    Bukkit.unloadWorld(loaded, false);
                    plugin.getLogger().warning("[room] 已卸载残留世界 " + name);
                }
                if (staleTemplateCopy) {
                    // 编辑副本里可能有唯一一份地形（崩服/没写回）：先转存到 moonmap-recover，
                    // 下次 moonmap load 会自动取用；只有已知有转存副本时才敢直接删。
                    String templateName = name.substring(TEMPLATE_EDIT_PREFIX.length());
                    File recover = new File(new File(plugin.getDataFolder(), "moonmap-recover"), templateName);
                    if (!recover.exists() && stashEditDirToRecover(child, recover)) {
                        continue;
                    }
                    plugin.getLogger().warning("[room] 清理上次运行遗留的模板编辑副本：" + name
                            + "（moonmap-recover/" + templateName + " 已存在，保留转存副本）");
                } else {
                    plugin.getLogger().warning("[room] 清理上次运行残留的世界文件夹：" + name);
                }
                deleteFolderWithRetry(child, name, 1);
            }
        }
    }

    /** 启用/重载时确保 moonmaps 模板目录存在，并提示管理员放置世界文件夹。 */
    private void ensureMoonmapsDir() {
        File moonmaps = new File(plugin.getDataFolder(), "moonmaps");
        if (moonmaps.isDirectory()) {
            return;
        }
        if (moonmaps.mkdirs()) {
            plugin.getLogger().info("已创建月面模板目录 plugins/TsukikageKassen/moonmaps/："
                    + "把世界文件夹放进来（如 moonmaps/tsukuyomi/），再用 /taketori moonmap load <模板名> 加载划定。");
        } else {
            plugin.getLogger().warning("无法创建月面模板目录：" + moonmaps);
        }
    }

    /** 已存在的房间世界文件夹的最大序号（新房间序号接续它，避免命名冲突）。 */
    private long highestExistingWorldNumber() {
        String prefix = plugin.config().roomWorldPrefix();
        // 维度目录与旧版根目录都算：升级过一次的服务器两边都可能留着 kassen_N
        Set<File> roots = new LinkedHashSet<>();
        roots.add(dimensionsRoot());
        roots.add(Bukkit.getWorldContainer());
        long max = 0L;
        for (File root : roots) {
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                String name = child.getName();
                if (child.isDirectory() && isRoomWorldName(name, prefix)) {
                    max = Math.max(max, Long.parseLong(name.substring(prefix.length())));
                }
            }
        }
        return max;
    }

    /**
     * 判定目录名是不是本插件房间世界：必须是 {@code 前缀 + 纯数字}（如 kassen_3）。
     * 单纯 startsWith 会让 "world" 之类宽泛前缀波及同名前缀的无关世界。
     */
    private static boolean isRoomWorldName(String name, String prefix) {
        if (prefix == null || prefix.isBlank() || !name.startsWith(prefix)) {
            return false;
        }
        String suffix = name.substring(prefix.length());
        if (suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 启动期校验房间世界前缀：空白或与已存在世界重名 / 为已加载世界名前缀时告警，
     * 提醒服主这会让残留清理误伤无关世界。
     */
    private void validateWorldPrefix() {
        String prefix = plugin.config().roomWorldPrefix();
        if (prefix == null || prefix.isBlank()) {
            plugin.getLogger().severe("[room] room.world-prefix 为空白，已跳过残留世界清理，"
                    + "请立即修正配置，否则可能误删服务器世界！");
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            if (name.equals(prefix) || isRoomWorldName(name, prefix)) {
                plugin.getLogger().severe("[room] room.world-prefix='" + prefix
                        + "' 会匹配到已存在的世界 " + name + "，残留清理存在误删风险，请更换更专属的前缀！");
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 创建房间

    /**
     * 创建房间（降临月宫）：以 moonmaps 里的模板世界为蓝本，异步复制出
     * {@code kassen_<序号>} 专属世界，加载后创建者自动进入等待区。
     *
     * @param templateId 模板 id（arenas.yml 条目名）；null/空白时用默认模板
     * @deprecated 使用 {@link #createRoom(Player, RoomCreateRequest)}，以便一并指定房间模式
     */
    @Deprecated
    public void createRoom(Player creator, String templateId) {
        createRoom(creator, RoomCreateRequest.withTemplate(templateId));
    }

    /**
     * 创建房间（降临月宫）：以 moonmaps 里的模板世界为蓝本，异步复制出
     * {@code kassen_<序号>} 专属世界，加载后创建者自动进入等待区。
     *
     * @param request 创建参数（模板 + 初始模式）；{@code null} 等价于 {@link RoomCreateRequest#defaults()}
     */
    public void createRoom(Player creator, RoomCreateRequest request) {
        if (creator == null || !creator.isOnline()) {
            return;
        }
        if (request == null) {
            request = RoomCreateRequest.defaults();
        }
        if (playerRooms.containsKey(creator.getUniqueId())) {
            creator.sendMessage(plugin.config().messages().get("room.already-in",
                    "room", playerRooms.get(creator.getUniqueId()).display()));
            return;
        }
        if (rooms.size() + pendingCreations.get() >= plugin.config().roomMaxRooms()) {
            creator.sendMessage(plugin.config().messages().get("room.max-rooms",
                    "max", plugin.config().roomMaxRooms()));
            return;
        }
        // 每人限建：名下房间（含创建中）达到上限就拒绝，防单人/小号占满全部额度冻结匹配
        int perPlayerLimit = plugin.config().roomMaxRoomsPerPlayer();
        if (ownedRoomsBy(creator.getUniqueId())
                + (pendingCreators.contains(creator.getUniqueId()) ? 1 : 0) >= perPlayerLimit) {
            creator.sendMessage(plugin.config().messages().get("room.per-player-limit", "max", perPlayerLimit));
            return;
        }
        String resolved = request.templateId() == null || request.templateId().isBlank()
                ? resolveDefaultTemplate() : request.templateId().toLowerCase(java.util.Locale.ROOT);
        ArenaDef template = resolved == null ? null : plugin.arena().get(resolved);
        if (template == null || !template.enabled()) {
            creator.sendMessage(plugin.config().messages().get("room.no-template"));
            return;
        }
        if (!template.isReady()) {
            creator.sendMessage(plugin.config().messages().get("room.template-not-ready",
                    "template", template.id(), "missing", template.missingHint()));
            return;
        }
        File templateDir = new File(new File(plugin.getDataFolder(), "moonmaps"), template.id());
        if (!templateDir.isDirectory()) {
            creator.sendMessage(plugin.config().messages().get("room.template-missing",
                    "template", template.id()));
            return;
        }
        // 旧图被人删了 level.dat 是常见事故：先补一份再判定，别让模板直接被打回
        ensureTemplateLevelDat(templateDir);
        // 模板可能是整个 level 根目录（world/ 直接拷进来）：解析到真正装 region/ 的那一层再复制
        final File contentDir = templateSource(templateDir);
        if (contentDir == null) {
            creator.sendMessage(plugin.config().messages().get("room.moonmap-not-world",
                    "template", template.id()));
            return;
        }
        if (!contentDir.equals(templateDir)) {
            plugin.getLogger().info("[room] 模板 " + template.id() + " 是整份 level 目录，"
                    + "按世界内容复制：" + contentDir);
        }
        // 没有 region/*.mca 的模板复制过去只会得到一个空世界：宁可在建房前说清楚
        int templateRegions = regionFileCount(contentDir);
        if (templateRegions == 0) {
            plugin.getLogger().warning("[room] 模板 " + template.id() + " 没有已生成的地形"
                    + "（region/*.mca 为 0），房间世界会是空图。世界内容目录：" + contentDir);
            creator.sendMessage(plugin.config().messages().get("room.template-no-terrain",
                    "template", template.id()));
            return;
        }
        UUID creatorId = creator.getUniqueId();
        long seq = sequence.incrementAndGet();
        String roomId = Long.toString(seq);
        String worldName = plugin.config().roomWorldPrefix() + seq;
        long createdInGeneration = generation.get();
        // 快照模式，供 finishCreate 异步回主线程时使用（避免闭包引用可变字段）
        com.taketori.kassen.core.match.sengoku.SengokuMode mode = request.mode();
        pendingCreations.incrementAndGet();
        pendingCreators.add(creatorId);
        creator.sendMessage(plugin.config().messages().get("room.creating-world",
                "room", template.display()));
        File target = worldCopyTarget(worldName);
        // 残留副本必须先清：copyWorldFolder 是合并语义，直接复制会把两次的区块混成一个世界
        List<File> staleCopies = worldFolderCandidates(worldName);
        CompletableFuture
                .supplyAsync(() -> {
                    deleteFolders(staleCopies);
                    return copyWorldFolder(contentDir, target);
                }, worldIo)
                .whenComplete((copied, error) -> plugin.scheduler().runSync(() -> {
                    pendingCreations.decrementAndGet();
                    pendingCreators.remove(creatorId);
                    // 复制期间发生了 reload：代次已变，这份副本必须丢弃，绝不注册
                    if (generation.get() != createdInGeneration) {
                        if (Boolean.TRUE.equals(copied)) {
                            deleteWorldFolders(worldName, 1);
                        }
                        return;
                    }
                    if (error != null || !Boolean.TRUE.equals(copied)) {
                        String reason = error == null ? "复制失败" : String.valueOf(error.getMessage());
                        plugin.getLogger().warning("[room] 世界 " + worldName + " 复制失败：" + reason);
                        if (creator.isOnline()) {
                            creator.sendMessage(plugin.config().messages().get("room.create-failed",
                                    "reason", reason));
                        }
                        deleteWorldFolders(worldName, 1);
                        return;
                    }
                    // 创建者在等待期内已加入别的房间：不再为其注册空房，副本直接回收
                    // （掉线则保留，空房宽限期兜底；重连不影响他人）
                    if (creator.isOnline() && playerRooms.containsKey(creatorId)) {
                        deleteWorldFolders(worldName, 1);
                        return;
                    }
                    finishCreate(creator, creatorId, template, roomId, worldName, mode);
                }));
    }

    /** 主线程收尾：加载世界、套用规则、克隆模板定义、注册房间、创建者进房。 */
    private void finishCreate(Player creator, UUID creatorId, ArenaDef template,
                              String roomId, String worldName,
                              com.taketori.kassen.core.match.sengoku.SengokuMode mode) {
        World world;
        try {
            world = createWorldCleanly(worldName);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("[room] 世界 " + worldName + " 加载失败：" + throwable);
            if (creator != null && creator.isOnline()) {
                creator.sendMessage(plugin.config().messages().get("room.create-failed",
                        "reason", String.valueOf(throwable.getMessage())));
            }
            deleteWorldFolders(worldName, 1);
            return;
        }
        if (world == null) {
            if (creator != null && creator.isOnline()) {
                creator.sendMessage(plugin.config().messages().get("room.create-failed",
                        "reason", "服务器未能加载世界"));
            }
            deleteWorldFolders(worldName, 1);
            return;
        }
        applyRoomWorldRules(world);
        // 落盘位置与地形数量都记一笔：房间世界「起得来但空图」时，这两行是唯一能定位的证据
        File loadedFolder = world.getWorldFolder();
        plugin.getLogger().info("[room] 房间世界 " + worldName + " 已加载：folder=" + loadedFolder
                + " region=" + regionFileCount(loadedFolder) + " 文件");
        ArenaDef arena = template.copyForWorld(worldName);
        GameRoom room = new GameRoom(plugin, this, arena, Long.parseLong(roomId), template.id(), creatorId, mode);
        rooms.put(roomId, room);
        plugin.getLogger().info("[room] 房间 " + roomId + " 已创建（模板 " + template.id()
                + "，世界 " + worldName + "）");
        // 创建者自动进入自己的房间（等待复制期间掉线 / 已加入别的房间则跳过）
        if (creator != null && creator.isOnline() && !playerRooms.containsKey(creator.getUniqueId())) {
            plugin.lobby().joinRoom(creator, roomId);
        }
        // 派对整队建房：创建者进房后，在线队友自动跟进同一房
        var partyManager = plugin.party();
        if (creator != null && partyManager != null) {
            var p = partyManager.partyOf(creatorId);
            if (p != null && p.members().size() > 1) {
                for (UUID memberId : p.members()) {
                    if (memberId.equals(creatorId)) {
                        continue;
                    }
                    Player member = Bukkit.getPlayer(memberId);
                    if (member != null && member.isOnline() && !playerRooms.containsKey(memberId)) {
                        plugin.lobby().joinRoom(member, roomId);
                    }
                }
            }
        }
    }

    /**
     * 房间世界统一规则：
     * <ul>
     *   <li>锁昼夜/天气/自然刷怪/火焰蔓延，死亡保留物品；一次性世界关闭自动保存；</li>
     *   <li>难度固定 NORMAL：HARD 下月人（僵尸）会破门，PEACEFUL 行为不可预期；</li>
     *   <li>随机刻冻结：雪原模板不会融化/结冰、树叶不消退，地形不随时间变化；</li>
     *   <li>清空当前天气（模板可能存于雷暴），关闭生物破坏地形与死亡消息（避免全服泄漏）。</li>
     * </ul>
     */
    private void applyRoomWorldRules(World world) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(0);
        world.setAutoSave(false);
    }

    // ------------------------------------------------------- 世界文件夹布局（1.21 ↔ 26.1）

    /**
     * 房间/模板世界应当落盘的父目录，等同 {@link WorldCreator} 实际使用的维度目录。
     *
     * <p>26.1+：主世界文件夹是 {@code <level>/dimensions/minecraft/overworld}，父目录即
     * {@code <level>/dimensions/minecraft}；旧版主世界文件夹是 {@code world/}，父目录即
     * 服务器根目录（{@link Bukkit#getWorldContainer()}）。</p>
     */
    private File dimensionsRoot() {
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            File folder = worlds.get(0).getWorldFolder();
            if (folder != null && folder.getParentFile() != null) {
                return folder.getParentFile();
            }
        }
        try {
            java.nio.file.Path levelDir = Bukkit.getServer().getLevelDirectory();
            if (levelDir != null) {
                File dimensions = levelDir.resolve("dimensions").resolve("minecraft").toFile();
                if (dimensions.isDirectory()) {
                    return dimensions;
                }
                File parent = levelDir.toFile().getParentFile();
                if (parent != null) {
                    return parent;
                }
            }
        } catch (Throwable ignored) {
            // 旧服务端没有 getLevelDirectory()：直接落到世界容器
        }
        return Bukkit.getWorldContainer();
    }

    /** 26.1 起世界按 key 落盘：名称转小写、空格换下划线。 */
    private static String worldFolderName(String worldName) {
        return worldName.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }

    private File dimensionFolder(String worldName) {
        return new File(dimensionsRoot(), worldFolderName(worldName));
    }

    /** 副本的落盘目录：世界已加载就用它自己的文件夹，否则按当前服务端的维度布局推算。 */
    private File worldCopyTarget(String worldName) {
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null && loaded.getWorldFolder() != null) {
            return loaded.getWorldFolder();
        }
        return dimensionFolder(worldName);
    }

    /**
     * 该世界名可能对应的全部文件夹：已加载世界的真实目录、当前布局的维度目录、
     * 以及旧版服务器根目录下的同名文件夹——升级前遗留的残留要能一起清掉。
     */
    private List<File> worldFolderCandidates(String worldName) {
        Set<File> candidates = new LinkedHashSet<>();
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null && loaded.getWorldFolder() != null) {
            candidates.add(loaded.getWorldFolder());
        }
        candidates.add(dimensionFolder(worldName));
        candidates.add(new File(Bukkit.getWorldContainer(), worldName));
        return new ArrayList<>(candidates);
    }

    /** 候选里第一个真正可用的世界内容目录（moonmap 导入用），没有就返回 null。 */
    private File resolveWorldFolder(String worldName) {
        for (File candidate : worldFolderCandidates(worldName)) {
            File content = templateSource(candidate);
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    /**
     * 扫一遍世界容器，列出当前能当模板导入的世界名（最多 8 个，顿号分隔）。
     *
     * <p>只用于导入失败时的提示：26.1 与旧版的世界落盘位置不同，管理员很难猜，
     * 直接把他眼前真实存在的世界名报出来最快。</p>
     */
    private String importableWorlds() {
        List<String> names = new ArrayList<>();
        Set<File> roots = new LinkedHashSet<>();
        File dimensions = dimensionsRoot();
        if (dimensions != null) {
            roots.add(dimensions);
        }
        File container = Bukkit.getWorldContainer();
        if (container != null) {
            roots.add(container);
        }
        for (File root : roots) {
            File[] children = root.listFiles(File::isDirectory);
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (names.size() >= 8) {
                    break;
                }
                if (isWorldFolder(child) && !names.contains(child.getName())) {
                    names.add(child.getName());
                }
            }
        }
        return String.join("、", names);
    }

    /**
     * 目录是否是可用的「世界」（可直接当模板/房间世界）。
     *
     * <p>两种布局都算：单个维度的世界文件夹（旧版 {@code level.dat}，或 26.1 的
     * {@code region/}+{@code data/}），以及整份服务端 level 根目录（含 {@code level.dat} 与
     * {@code dimensions/minecraft/overworld}）——后者会自动下钻，见 {@link #templateSource(File)}。</p>
     */
    static boolean isWorldFolder(File dir) {
        return templateSource(dir) != null;
    }

    /** 统计世界文件夹里已生成的地形区域文件数（{@code region/*.mca}）。 */
    static int regionFileCount(File worldDir) {
        if (worldDir == null) {
            return 0;
        }
        File region = new File(worldDir, "region");
        File[] files = region.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".mca"));
        return files == null ? 0 : files.length;
    }

    /**
     * 从给定的目录里解析出「真正要复制的世界内容层」。
     *
     * <p>服主往 {@code plugins/TsukikageKassen/moonmaps/} 里放模板时，很容易直接拷进来一整个
     * 服务端 {@code world/} 目录（或者 1.21 时代带 {@code level.dat} 的老世界文件夹）。前者如果
     * 原样复制进 {@code dimensions/minecraft/<名>}，Paper 会把外层当成 level 根目录来读，
     * 地形全在 {@code dimensions/minecraft/overworld} 里 —— 地图就变成空白/默认地形。
     * 所以这里统一解析到真正装 {@code region/} 的那一层，调用方只负责复制它。</p>
     *
     * @return 可复制的目录；不是世界就返回 null
     */
    static File templateSource(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File overworld = new File(dir, "dimensions/minecraft/overworld");
        if (overworld.isDirectory()) {
            return overworld;
        }
        File dimensions = new File(dir, "dimensions/minecraft");
        if (new File(dir, "level.dat").isFile() && dimensions.isDirectory()) {
            File[] children = dimensions.listFiles(File::isDirectory);
            if (children != null && children.length > 0) {
                return children[0];
            }
        }
        if (new File(dir, "level.dat").isFile()
                || new File(dir, "region").isDirectory()
                || new File(dir, "entities").isDirectory()
                || new File(dir, "data").isDirectory()) {
            return dir;
        }
        return null;
    }

    /**
     * 仅为「旧版世界文件夹被人删掉了 level.dat」补一份，避免模板被打回。
     *
     * <p>26.1+ 的维度文件夹本来就没有 level.dat（设置已拆到 {@code data/minecraft/*.dat}），
     * 强行塞入主世界的 level.dat 反而会让 Paper 误判成待迁移的旧版地图，故跳过。</p>
     */
    public boolean ensureTemplateLevelDat(File dir) {
        if (dir == null || !dir.isDirectory() || new File(dir, "level.dat").isFile()) {
            return false;
        }
        if (new File(dir, "data").isDirectory()
                || new File(dir, "paper-world.yml").isFile()
                || new File(dir, "dimensions").isDirectory()) {
            return false;
        }
        if (!new File(dir, "region").isDirectory() && !new File(dir, "entities").isDirectory()) {
            return false;
        }
        File source = serverLevelDat();
        if (source == null) {
            return false;
        }
        try {
            java.nio.file.Files.copy(source.toPath(), new File(dir, "level.dat").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("[moonmap] 模板缺少 level.dat，已用主世界的补齐"
                    + "（只影响未生成区块）：" + dir);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("[moonmap] 补齐 level.dat 失败：" + ex.getMessage());
            return false;
        }
    }

    /** 主世界的 level.dat：优先服务端 level 目录，退到已加载世界里的第一个。 */
    private File serverLevelDat() {
        try {
            java.nio.file.Path levelDir = Bukkit.getServer().getLevelDirectory();
            if (levelDir != null) {
                File candidate = levelDir.resolve("level.dat").toFile();
                if (candidate.isFile()) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // 旧服务端没这个 API：走世界容器
        }
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder();
            if (folder != null && new File(folder, "level.dat").isFile()) {
                return new File(folder, "level.dat");
            }
        }
        return null;
    }

    /** 世界名对应的全部残留目录一并删除（旧版容器路径与 26.1 维度路径都可能存在）。 */
    private void deleteWorldFolders(String worldName, int retries) {
        for (File dir : worldFolderCandidates(worldName)) {
            if (dir.isDirectory()) {
                deleteFolderWithRetry(dir, worldName, retries);
            }
        }
    }

    /** 该世界名是否已经有落盘的文件夹（任一候选位置）。 */
    private boolean anyWorldFolderExists(String worldName) {
        for (File dir : worldFolderCandidates(worldName)) {
            if (dir.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /** 同步删除若干目录（worldIo 线程内调用：复制前清残留副本）。 */    private void deleteFolders(List<File> dirs) {
        for (File dir : dirs) {
            if (dir.isDirectory() && !deleteRecursively(dir)) {
                plugin.getLogger().warning("[room] 残留副本未清干净，复制可能混入旧区块：" + dir);
            }
        }
    }

    /**
     * 加载房间世界，失败重试一次。
     *
     * <p>刚复制完的世界偶尔会因为文件句柄尚未释放（Windows 上尤其）而首次加载失败，
     * 立刻重试一次的成本远低于让玩家看到「创建失败」并丢掉整个副本。</p>
     */
    private World createWorldCleanly(String worldName) {
        try {
            return new WorldCreator(worldName).createWorld();
        } catch (Throwable first) {
            plugin.getLogger().warning("[room] 世界 " + worldName + " 加载失败，重试一次："
                    + first.getMessage());
            try {
                return new WorldCreator(worldName).createWorld();
            } catch (Throwable second) {
                plugin.getLogger().warning("[room] 世界 " + worldName + " 重试加载仍失败："
                        + second.getMessage());
                return null;
            }
        }
    }

    /** 世界复制时的脏文件/目录：世界锁与唯一 id 不该复制，玩家痕迹不该进模板与房间。 */
    private static final Set<String> SKIP_COPY_FILES = Set.of("session.lock", "uid.dat");
    private static final Set<String> SKIP_COPY_DIRS = Set.of("playerdata", "stats", "advancements");

    /**
     * 递归复制世界文件夹（跳过 session.lock / uid.dat / playerdata / stats / advancements）；
     * 返回是否全部成功。
     */
    private boolean copyWorldFolder(File source, File target) {
        File[] children = source.listFiles();
        if (children == null) {
            return false;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            return false;
        }
        boolean ok = true;
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory() && SKIP_COPY_DIRS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;   // 玩家痕迹不进模板/房间
            }
            if (child.isFile() && SKIP_COPY_FILES.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                continue;   // 世界锁与唯一 id：每个世界应有自己的
            }
            // Paper 26.1 把世界 UUID 存在 data/paper/metadata.dat，跟着复制过去会让 Paper
            // 判定成「重复世界」而拒绝加载，所以 data/paper 这一层要跳过它
            if (child.isFile()
                    && "metadata.dat".equals(name.toLowerCase(java.util.Locale.ROOT))
                    && "paper".equals(source.getName().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            File copy = new File(target, name);
            if (child.isDirectory()) {
                ok &= copyWorldFolder(child, copy);
            } else {
                try {
                    java.nio.file.Files.copy(child.toPath(), copy.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    plugin.getLogger().warning("[room] 复制世界文件失败：" + child + " → " + ex.getMessage());
                    ok = false;
                }
            }
        }
        return ok;
    }

    /**
     * 异步删除世界文件夹；失败（文件被占用）5 秒后重试，超次记警告留给下次启动清理。
     * 重试间隔由主线程调度而非在 worldIo 线程内 sleep —— 单线程池被 sleep 占住会
     * 阻塞队列中后续所有房间的复制。
     */
    private void deleteFolderWithRetry(File folder, String worldName, int retries) {
        int attempts = Math.max(1, retries);
        CompletableFuture
                .supplyAsync(() -> deleteRecursively(folder), worldIo)
                .whenComplete((deleted, error) -> plugin.scheduler().runSync(() -> {
                    if (Boolean.TRUE.equals(deleted)) {
                        return;
                    }
                    if (attempts <= 1) {
                        plugin.getLogger().warning("[room] 世界文件夹删除失败：" + worldName
                                + "，下次启动时清理");
                        return;
                    }
                    plugin.scheduler().runLater(
                            () -> deleteFolderWithRetry(folder, worldName, attempts - 1), 100L);
                }));
    }

    /** onDisable 调用：停止世界 IO 线程池（短等待后强制中断）。 */
    public void shutdownWorldIo() {
        worldIo.shutdown();
        try {
            if (!worldIo.awaitTermination(2L, TimeUnit.SECONDS)) {
                worldIo.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            worldIo.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        return !file.exists() || file.delete();
    }

    /** 默认模板：优先 room.default-template 指定的，否则第一个启用且就绪的模板。 */
    public String resolveDefaultTemplate() {
        String configured = plugin.config().roomDefaultTemplate();
        if (configured != null && !configured.isBlank()) {
            ArenaDef def = plugin.arena().get(configured);
            if (def != null && def.enabled() && def.isReady()) {
                return configured;
            }
        }
        for (ArenaDef def : plugin.arena().enabledArenas().values()) {
            if (def.isReady()) {
                return def.id();
            }
        }
        return null;
    }

    /**
     * 已通过白名单校验的 moonmap 名字登记表：方法体里用于拼路径的名字都从这里取规范名，
     * 白名单外的输入（路径分隔符、{@code ..} 等）永远进不了文件操作。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> validatedMoonmapNames =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * moonmap 模板名 / 世界名白名单：小写字母数字、下划线、连字符（世界名另允许大写）。
     * 返回登记表里的规范名；不合格返回 null。
     */
    private String validatedMoonmapName(String raw, String pattern) {
        if (raw == null || !raw.matches(pattern)) {
            return null;
        }
        return validatedMoonmapNames.computeIfAbsent(raw, name -> name);
    }

    /** 从零创建一个平坦模板世界：生成 → 卸载 → 收进 moonmaps/&lt;名&gt; 并注册模板定义，随后可 load 进去编辑。 */
    public void createMoonmap(Player admin, String rawTemplateName) {
        final String templateName = validatedMoonmapName(rawTemplateName, "[a-z0-9_-]{1,32}");
        if (templateName == null) {
            admin.sendMessage(plugin.config().messages().get("room.arena-bad-id"));
            return;
        }
        if (plugin.arena().exists(templateName)
                || new File(new File(plugin.getDataFolder(), "moonmaps"), templateName).isDirectory()) {
            admin.sendMessage(plugin.config().messages().get("room.arena-exists", "id", templateName));
            return;
        }
        String worldName = TEMPLATE_EDIT_PREFIX + templateName;
        if (Bukkit.getWorld(worldName) != null || anyWorldFolderExists(worldName)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-busy"));
            return;
        }
        if (!pendingMoonmaps.add(templateName)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-busy"));
            return;
        }
        admin.sendMessage(plugin.config().messages().get("room.creating-world", "room", templateName));
        World world = new WorldCreator(worldName)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            pendingMoonmaps.remove(templateName);
            admin.sendMessage(plugin.config().messages().get("room.create-failed", "reason", "世界创建失败"));
            return;
        }
        // 世界文件夹要在卸载前取：26.1 下它不在服务器根目录，而在 dimensions/minecraft 里
        File loadedFolder = world.getWorldFolder();
        final File loadedDir = loadedFolder != null ? loadedFolder : worldCopyTarget(worldName);
        final File loadedContent = templateSource(loadedDir) != null ? templateSource(loadedDir) : loadedDir;
        final File worldDir = loadedContent;
        Bukkit.unloadWorld(world, true);
        File target = new File(new File(plugin.getDataFolder(), "moonmaps"), templateName);
        CompletableFuture.runAsync(() -> {
            try {
                boolean ok = copyWorldFolder(worldDir, target);
                if (ok) {
                    deleteRecursively(worldDir);
                }
                boolean finalOk = ok;
                plugin.scheduler().runSync(() -> {
                    pendingMoonmaps.remove(templateName);
                    if (!finalOk) {
                        if (admin.isOnline()) {
                            admin.sendMessage(plugin.config().messages().get("room.create-failed",
                                    "reason", "模板复制失败"));
                        }
                        return;
                    }
                    // 顺手注册模板定义（管理员 load 后即可直接划定，不用先 arena create）
                    if (!plugin.arena().exists(templateName)) {
                        plugin.arena().create(templateName);
                        plugin.arena().save();
                    }
                    plugin.getLogger().info("[moonmap] 平坦模板已创建：moonmaps/" + templateName);
                    if (admin != null && admin.isOnline()) {
                        admin.sendMessage(plugin.config().messages().get("room.moonmap-created",
                                "template", templateName));
                    }
                });
            } finally {
                pendingMoonmaps.remove(templateName);
            }
        }, worldIo);
    }

    // ---------------------------------------------------------------- 销毁房间

    /**
     * 销毁房间并回收世界：移除注册表与玩家映射 → （可选）残留玩家回大厅 → 停房间组件
     * → 卸载世界（不保存）→ 异步删除世界文件夹。
     *
     * @param evict true = 先把房内玩家送回大厅（手动删除等待房）；
     *              false = 玩家已由结算流程回大厅（endCleanup 路径）
     */
    public void destroyRoom(GameRoom room, boolean evict) {
        if (rooms.remove(room.id()) == null) {
            return;   // 已销毁（幂等）
        }
        // 房间没了：指向它的断线重连会话一并作废（重连后走背包返还兜底）
        if (plugin.rejoin() != null) {
            plugin.rejoin().forgetRoom(room.id());
        }
        if (evict) {
            Set<UUID> members = new LinkedHashSet<>(room.waitingView());
            members.addAll(room.teamsView().keySet());
            for (UUID uuid : members) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    // 先还背包再送大厅：避免玩家穿着对局装备短暂出现在大厅
                    room.restoreInventoryIfAny(player);
                    plugin.lobby().sendToLobby(player);
                }
                playerRooms.remove(uuid);
                entryPoints.remove(uuid);
                plugin.spectator().forgetQuietly(uuid);
            }
        } else {
            List<UUID> detached = new ArrayList<>();
            playerRooms.entrySet().removeIf(entry -> {
                if (entry.getValue() != room) {
                    return false;
                }
                detached.add(entry.getKey());
                return true;
            });
            for (UUID uuid : detached) {
                entryPoints.remove(uuid);
            }
        }
        plugin.spectator().clearAudienceOfRoom(room);
        room.shutdown();
        unloadAndDelete(room, 6);
    }

    /**
     * 卸载房间世界（不保存）并异步删除文件夹。卸载失败时按 5 秒间隔在主线程重试，
     * 多次仍失败才放弃（下次启动清理）——避免任何残留占用导致世界永久加载、内存泄漏。
     */
    private void unloadAndDelete(GameRoom room, int attemptsLeft) {
        String worldName = room.arena().worldName();
        if (worldName == null) {
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world != null && !world.getPlayers().isEmpty()) {
            // 卸载带人的世界必然失败（Bukkit 返回 false），随后就是「5 秒后重试」无限循环，
            // 人卡在已废弃的房间世界里。先把人送出去再卸载，只可能在落点也失效时才会漏。
            for (Player occupant : new java.util.ArrayList<>(world.getPlayers())) {
                plugin.getLogger().warning("[room " + room.id() + "] 世界 " + worldName
                        + " 卸载前仍有玩家 " + occupant.getName() + "，先送回大厅");
                plugin.lobby().sendToLobby(occupant);
            }
        }
        if (world != null && !Bukkit.unloadWorld(world, false)) {
            if (attemptsLeft <= 1) {
                plugin.getLogger().warning("[room " + room.id() + "] 世界 " + worldName
                        + " 多次卸载仍失败，下次启动时清理");
                return;
            }
            plugin.getLogger().warning("[room " + room.id() + "] 世界 " + worldName
                    + " 卸载失败，5 秒后重试（剩余 " + (attemptsLeft - 1) + " 次）");
            plugin.scheduler().runLater(() -> unloadAndDelete(room, attemptsLeft - 1), 100L);
            return;
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + room.id() + "] 世界 " + worldName + " 已卸载，文件夹回收中");
        }
        deleteWorldFolders(worldName, 3);
    }

    /** 停用/重载路径：同步卸载世界（不保存），文件夹异步删除（未完成由下次启动清理兜底）。 */
    private void unloadAndDeleteNow(GameRoom room) {
        String worldName = room.arena().worldName();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world != null) {
            Bukkit.unloadWorld(world, false);
        }
        if (worldName != null) {
            deleteWorldFolders(worldName, 1);
        }
    }

    // ---------------------------------------------------------------- 玩家归属与查询

    public List<GameRoom> rooms() {
        List<GameRoom> list = new ArrayList<>(rooms.values());
        list.sort(Comparator.comparingLong(GameRoom::order));
        return list;
    }

    public GameRoom room(String id) {
        return id == null ? null : rooms.get(id);
    }

    public GameRoom roomOf(UUID uuid) {
        return uuid == null ? null : playerRooms.get(uuid);
    }

    public GameRoom roomOf(Player player) {
        return player == null ? null : playerRooms.get(player.getUniqueId());
    }

    /**
     * 实体归属：遍历各房间月人刷怪器的归属集合（典型房间数 1~10，O(房间数) 可接受）。
     * 找不到返回 null（野生怪 / 其他玩法实体）。
     */
    public GameRoom roomOfEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (GameRoom room : rooms.values()) {
            if (room.minions().isTracked(entity)) {
                return room;
            }
        }
        return null;
    }

    /** 模板是否正被某个房间使用（模板删除拦截用）。 */
    public boolean isTemplateInUse(String templateId) {
        return rooms.values().stream().anyMatch(room -> room.templateId().equals(templateId));
    }

    /** 该玩家名下的房间数（creatorId 匹配，含自己不在其中的等待房）。 */
    private int ownedRoomsBy(UUID creatorId) {
        int count = 0;
        for (GameRoom room : rooms.values()) {
            if (creatorId.equals(room.creatorId())) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- 快速加入 / 加入 / 退出

    /**
     * 快速加入：自动选择"WAITING/STARTING、模板就绪、未满"中等待人数最多的房间，
     * 平局取先创建者。没有任何可加入的房间时返回 <b>null</b>——调用方（LobbyManager）
     * 会走默认模板自动建房并加入（月宫制下快速加入永不落空）。
     *
     * <p>派对（组队）规则：发起者是派对成员时改为<b>整队匹配</b>——
     * ① 队里已有人在等待房 → 全队跳进那个房；② 否则选一个能装下整队的房间一起进；
     * ③ 装不下且发起者是房主 → 用默认模板建房，建好后整队自动进入（见 {@link #finishCreate}）。</p>
     */
    public JoinResult quickJoin(Player player) {
        if (player == null) {
            return null;
        }
        rememberEntry(player);
        GameRoom current = playerRooms.get(player.getUniqueId());
        if (current != null) {
            return current.isJoinable()
                    ? new JoinResult(JoinOutcome.ALREADY_IN, current)
                    : new JoinResult(JoinOutcome.IN_GAME, current);
        }
        // ---- 派对整队匹配 ----
        var party = plugin.party();
        var p = party == null ? null : party.partyOf(player.getUniqueId());
        if (p != null && p.members().size() > 1) {
            JoinResult partyResult = quickJoinParty(player, p);
            if (partyResult != null) {
                return partyResult;
            }
            // 派对路径没走通（无房可装且不是房主）：落回单人逻辑
        }
        GameRoom best = rooms.values().stream()
                .filter(room -> room.arena().isReady())
                .filter(GameRoom::isJoinable)
                // 等待人数最多；order 反向比较 → 平局时序号小（先创建）者在 max 中胜出
                .max(Comparator.comparingInt(GameRoom::waitingCount)
                        .thenComparing(Comparator.comparingLong(GameRoom::order).reversed()))
                .orElse(null);
        if (best == null) {
            // 没有等待中的房间：找正在打且缺人的对局补位（掉线不再意味着 3v2 打到底）
            GameRoom reinforce = rooms.values().stream()
                    .filter(room -> room.phase() == GameRoom.Phase.PLAYING
                            && room.reinforcementTeam() != null)
                    .min(Comparator.comparingLong(GameRoom::order))
                    .orElse(null);
            if (reinforce != null) {
                return joinRoom(player, reinforce.id());
            }
            return null;
        }
        return joinRoom(player, best.id());
    }

    /** 无房时的快速加入：用默认模板自动建房并加入（异步完成后自动传送）。 */
    public void createDefaultAndJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String templateId = resolveDefaultTemplate();
        if (templateId == null) {
            player.sendMessage(plugin.config().messages().get("room.no-template"));
            return;
        }
        createRoom(player, templateId);
    }

    /**
     * 派对整队快速加入。返回 null 表示"本次没走派对路径"
     * （队里没人有房可跟、没有装得下整队的房、且自己不是房主）。
     */
    private JoinResult quickJoinParty(Player player, com.taketori.kassen.paper.party.PartyManager.Party party) {
        boolean isLeader = party.leader().equals(player.getUniqueId());
        // ① 队里已有人在可加入的等待房：全队（本次仅加入者）跳进去
        for (UUID memberId : party.members()) {
            if (memberId.equals(player.getUniqueId())) {
                continue;
            }
            GameRoom membersRoom = playerRooms.get(memberId);
            if (membersRoom != null && membersRoom.isJoinable()
                    && membersRoom.arena().isReady()
                    && membersRoom.waitingCount() < membersRoom.maxPlayers()) {
                return joinRoom(player, membersRoom.id());
            }
        }
        int partySize = party.members().size();
        // ② 找一个能装下整队的房间（等待人数最多者优先，平局先创建者）
        GameRoom fit = rooms.values().stream()
                .filter(room -> room.arena().isReady())
                .filter(GameRoom::isJoinable)
                .filter(room -> room.waitingCount() + partySize <= room.maxPlayers())
                .max(Comparator.comparingInt(GameRoom::waitingCount)
                        .thenComparing(Comparator.comparingLong(GameRoom::order).reversed()))
                .orElse(null);
        if (fit != null) {
            return joinRoom(player, fit.id());
        }
        // ③ 房主发起且无房可装：用默认模板建房（finishCreate 建好后整队自动进入）
        if (isLeader) {
            createDefaultAndJoin(player);
            return new JoinResult(JoinOutcome.CREATED, null);
        }
        return null;
    }

    /**
     * 加入指定房间（房间列表 GUI / 指令 / 创建后自动进房用）。等待中允许换房：
     * 先静默离开旧房，再加入新房；已在进行中对局的玩家不能再加入。
     */
    public JoinResult joinRoom(Player player, String roomId) {
        if (player == null) {
            return new JoinResult(JoinOutcome.NOT_FOUND, null);
        }
        rememberEntry(player);
        GameRoom target = rooms.get(roomId);
        if (target == null) {
            return new JoinResult(JoinOutcome.NOT_FOUND, null);
        }
        if (!target.arena().isReady()) {
            return new JoinResult(JoinOutcome.NOT_READY, target);
        }
        GameRoom current = playerRooms.get(player.getUniqueId());
        if (current == target) {
            return new JoinResult(JoinOutcome.ALREADY_IN, target);
        }
        if (current != null && !current.isJoinable()) {
            return new JoinResult(JoinOutcome.IN_GAME, current);
        }
        // 对局进行中：只允许补位（有队伍人数严格少于对方且未满编时），否则提示已开局
        if (target.phase() == GameRoom.Phase.CAGED || target.phase() == GameRoom.Phase.PLAYING) {
            TeamId openTeam = target.reinforcementTeam();
            if (openTeam == null) {
                return new JoinResult(JoinOutcome.STARTED, target);
            }
            if (current != null) {
                // 从等待房转来：取回旧房封存的背包再进战场
                current.removeWaiting(player.getUniqueId());
                current.scoreboard().hide(player);
                current.restoreInventoryIfAny(player);
            }
            target.joinAsReinforcement(player, openTeam);
            playerRooms.put(player.getUniqueId(), target);
            // 补位入局同样给功能壳（开局路径不会经过 addWaiting，必须在这里补发）
            plugin.roomShell().give(player);
            return new JoinResult(JoinOutcome.REINFORCED, target);
        }
        if (target.waitingCount() >= target.maxPlayers()) {
            return new JoinResult(JoinOutcome.FULL, target);
        }

        boolean switched = false;
        if (current != null) {
            // 等待中换房：先离开旧房（不发退房文案、不回大厅，直接进新房等待区）
            current.removeWaiting(player.getUniqueId());
            current.scoreboard().hide(player);
            current.restoreInventoryIfAny(player);   // 取回旧房封存的背包，进新房时重新封存
            switched = true;
        }

        target.addWaiting(player);
        playerRooms.put(player.getUniqueId(), target);
        target.stashAndClearInventory(player);   // 进房即封存：等待区人人平等（重复调用不覆盖备份）
        // 进房即发对局功能壳（在清包之后写第 9 格，否则会被 inv.clear() 抹掉）；
        // 比角色武器更早：等待期就能用它退出房间，开局时会再补发一次确保位置正确。
        plugin.roomShell().give(player);

        // 传送到该房世界的等待区：划了等待区区域则区域内随机安全落点，否则等待出生点
        com.taketori.kassen.paper.match.CuboidRegion waitRegion = target.arena().waitRegion();
        if (waitRegion != null) {
            Location spread = waitRegion.randomStandLocation();
            if (spread != null) {
                player.teleport(spread);
            }
        } else {
            ArenaDef.Point waitPoint = target.arena().waitSpawn();
            if (waitPoint != null) {
                Location waitSpawn = waitPoint.toBukkitLocation();
                if (waitSpawn != null) {
                    player.teleport(waitSpawn);
                }
            }
        }
        // 进房强制流程（两步，分别触发——角色是持久化的，不能只靠角色菜单成功回调衔接）：
        //   ① 还没绑定角色 → 强制弹角色菜单（选完角色 CharacterMenu 会自动衔接选队伍）；
        //   ② 已有角色但 PVP 下还没队伍 → 直接强制弹队伍菜单；
        //   PVE（开局统一红队）/ 已有队伍时不弹。
        // 菜单被关掉会自动重开，直到完成或离开房间。
        var profile = plugin.config().characters().profileOrNull(player.getUniqueId());
        boolean hasCharacter = profile != null && profile.hasCharacter();
        if (!hasCharacter) {
            plugin.scheduler().runLater(() -> {
                // 延迟任务执行前重新确认玩家还在该等待房（防退房后菜单弹到大厅）
                if (plugin.rooms().roomOf(player) != target
                        || (target.phase() != GameRoom.Phase.WAITING
                        && target.phase() != GameRoom.Phase.STARTING)) {
                    return;
                }
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<gold>请先选择角色与装备<gray>（必须选完才能继续等待开局）"));
                plugin.characterMenu().openForced(player);
            }, 5L);
        } else if (!target.isPve() && target.teamOf(player.getUniqueId()) == null) {
            plugin.scheduler().runLater(() -> {
                if (plugin.rooms().roomOf(player) != target
                        || (target.phase() != GameRoom.Phase.WAITING
                        && target.phase() != GameRoom.Phase.STARTING)) {
                    return;
                }
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<gold>请选择阵营<gray>（只能加入人数不占优的一边）"));
                plugin.playerMenu().openTeamForced(player);
            }, 5L);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room] " + player.getName() + (switched ? " 换房加入 " : " 加入 ")
                    + target.id() + "（" + target.waitingCount() + "/" + target.maxPlayers() + "）");
        }
        return new JoinResult(switched ? JoinOutcome.SWITCHED : JoinOutcome.SUCCESS, target);
    }

    /**
     * 玩家离开房间：移除权威映射。WAITING/STARTING 阶段释放等待名额；
     * CAGED/PLAYING/ENDING 沿用"队伍位置保留"的旧规则（teams 条目保留，
     * 只隐藏记分板），房间倒计时/空房判定只数在线者。
     *
     * @return 离开的房间；玩家本来就不在任何房间时返回 null
     */
    public GameRoom leave(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        // 离开房间（退房/退出/对局中掉线）：解除选角色与选队伍强制，菜单不再自动重开
        plugin.characterMenu().cancelForce(uuid);
        plugin.playerMenu().cancelTeamForce(uuid);
        GameRoom room = playerRooms.remove(uuid);
        // 入口位置只服务于「结算时送回进房前的位置」，人一旦离开房间就不再需要；
        // 不清会随玩家进出无限累积（这张表没有 TTL，也不随房间销毁回收）。
        entryPoints.remove(uuid);
        if (room == null) {
            return null;
        }
        room.removeWaiting(uuid);
        GameRoom.Phase phase = room.phase();
        if (phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING) {
            // 等待阶段退出：若已被提前分队（管理指令），释放队伍槽位
            if (room.teamOf(uuid) != null) {
                room.leave(uuid);
            } else {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    room.restoreInventoryIfAny(online);   // 返还进房时封存的背包
                }
                room.scoreboard().hide(Bukkit.getPlayer(uuid));
            }
        } else {
            if (phase == GameRoom.Phase.ENDING) {
                // 结算阶段退房：先还开局前封存的背包，否则玩家会短暂穿着对局装备回大厅
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    room.restoreInventoryIfAny(online);
                }
            }
            // 对局中退出：释放队伍槽位（可被补位），整局成员缓存保留到结算；只撤掉记分板
            room.quitMatch(uuid);
        }
        return room;
    }

    /**
     * 对局中掉线且已登记重连会话的退出路径：与 {@link #leave(UUID)} 相同的解绑，
     * 但<b>保留</b> teams 条目——重连窗口内位置占住，重连直接回原队；
     * 会话过期后由 {@code RejoinManager.purgeExpired} 调 {@code quitMatch} 释放。
     */
    public GameRoom leaveForRejoin(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        plugin.characterMenu().cancelForce(uuid);
        plugin.playerMenu().cancelTeamForce(uuid);
        GameRoom room = playerRooms.remove(uuid);
        if (room == null) {
            return null;
        }
        room.removeWaiting(uuid);
        room.quitMatchKeepEntry(uuid);
        return room;
    }

    /**
     * 结算收尾用：解除所有"当前指向该房间"的玩家映射（人已回大厅）。
     * 只移除指向目标房间的条目，不影响其他并发房间。
     */
    public void detachRoom(GameRoom target) {
        List<UUID> detached = new ArrayList<>();
        playerRooms.entrySet().removeIf(entry -> {
            if (entry.getValue() != target) {
                return false;
            }
            detached.add(entry.getKey());
            return true;
        });
        // 房间没了，它记下的入口位置也就没用了：这张表没有 TTL，不跟着清会一直涨。
        for (UUID uuid : detached) {
            entryPoints.remove(uuid);
        }
    }

    // ---------------------------------------------------------------- 断线背包暂存

    /**
     * 玩家退出服务器时调用：若其所在房间为其存了开局前背包备份，把备份转移到
     * 全局暂存，避免房间结算时因玩家不在线而丢弃原物品。
     */
    public void stashOfflineBackup(UUID uuid) {
        if (uuid == null) {
            return;
        }
        GameRoom room = playerRooms.get(uuid);
        if (room == null) {
            return;
        }
        GameRoom.InventorySnapshot backup = room.extractBackup(uuid);
        if (backup != null) {
            purgeStaleOfflineBackups();
            offlineBackups.put(uuid, new OfflineBackup(backup, System.currentTimeMillis()));
        }
    }

    /** 直接存入一份离线状态快照（房间销毁时离线参与者的封存转移用）。 */
    public void stashOfflineBackup(UUID uuid, GameRoom.InventorySnapshot backup) {
        if (uuid == null || backup == null) {
            return;
        }
        purgeStaleOfflineBackups();
        offlineBackups.put(uuid, new OfflineBackup(backup, System.currentTimeMillis()));
    }

    /** 淘汰超过 TTL 未取回的断线背包暂存（玩家不再回归时不能一直持有整套装备快照）。 */
    private void purgeStaleOfflineBackups() {
        long cutoff = System.currentTimeMillis() - OFFLINE_BACKUP_TTL_MILLIS;
        offlineBackups.values().removeIf(entry -> entry.storedAtMillis() < cutoff);
    }

    /**
     * 玩家重连时调用：若有断线暂存的背包，清掉当前背包并原样返还。
     * 没有暂存则什么都不做。
     */
    public void restoreOfflineBackup(Player player) {
        if (player == null) {
            return;
        }
        OfflineBackup entry = offlineBackups.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        entry.backup().restoreTo(player);
    }

    // ---------------------------------------------------------------- 月面模板编辑（moonmap）

    /**
     * 加载模板编辑世界：把 {@code moonmaps/<名>} 复制到服务器根目录为 {@code k_tpl_<名>}
     * 并加载，管理员进入后用现有选区指令划定区域/点位（写入 arenas.yml）。
     */
    public void loadMoonmap(Player admin, String rawTemplateName) {
        final String templateName = validatedMoonmapName(rawTemplateName, "[a-z0-9_-]{1,32}");
        if (templateName == null) {
            admin.sendMessage(plugin.config().messages().get("room.arena-bad-id"));
            return;
        }
        String worldName = TEMPLATE_EDIT_PREFIX + templateName;
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            // 编辑世界已加载：把管理员直接送进去。否则他收到「已加载」却没有入口，
            // 而命令提示的「再执行 setup / moonmap load」也会空转（本方法在这里早退）。
            // 对局中/观战中不拽人：否则他被拉进编辑世界，却仍被登记为那个房间的玩家。
            if (roomOf(admin) == null && !plugin.spectator().isSpectator(admin)) {
                admin.teleport(existing.getSpawnLocation());
            }
            admin.sendMessage(plugin.config().messages().get("room.moonmap-loaded",
                    "world", worldName, "template", templateName));
            return;
        }
        if (!pendingMoonmaps.add(templateName)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-busy"));
            return;
        }
        File moonmapsDir = new File(plugin.getDataFolder(), "moonmaps");
        // 上次写回失败的编辑副本优先恢复：它是比 moonmaps/<名> 更新的现场
        File recoverDir = new File(new File(plugin.getDataFolder(), "moonmap-recover"), templateName);
        File source = recoverDir.isDirectory() ? recoverDir
                : new File(moonmapsDir, templateName);
        if (!source.isDirectory()) {
            pendingMoonmaps.remove(templateName);
            admin.sendMessage(plugin.config().messages().get("room.template-missing", "template", templateName));
            return;
        }
        // 旧图被删了 level.dat 时先补一份；26.1 维度布局本来就没有，靠 templateSource 判
        ensureTemplateLevelDat(source);
        final File contentDir = templateSource(source);
        if (contentDir == null) {
            pendingMoonmaps.remove(templateName);
            admin.sendMessage(plugin.config().messages().get("room.moonmap-not-world", "template", templateName));
            return;
        }
        if (!contentDir.equals(source)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-level-root",
                    "layer", contentDir.getName()));
        }
        File target = worldCopyTarget(worldName);
        admin.sendMessage(plugin.config().messages().get("room.moonmap-loading", "template", templateName));
        if (recoverDir.isDirectory()) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-recovered", "template", templateName));
        }
        CompletableFuture
                .supplyAsync(() -> {
                    // 残留的编辑副本（上次写回失败/崩溃留下）必须先清掉：copyWorldFolder 是「合并」
                    // 语义，直接复制会把旧图和新图混成一个世界。世界一定没加载（上面已早退），
                    // 这次删除放 worldIo 线程做，免得在主线程递归删几千个文件把服务器卡住。
                    if (target.isDirectory()) {
                        plugin.getLogger().warning("[moonmap] 清理残留的编辑副本目录：" + target);
                        if (!deleteRecursively(target)) {
                            plugin.getLogger().warning("[moonmap] 残留目录未清干净，"
                                    + "复制可能混入旧区块：" + target);
                        }
                    }
                    return copyWorldFolder(contentDir, target);
                }, worldIo)
                .whenComplete((copied, error) -> plugin.scheduler().runSync(() -> {
                    pendingMoonmaps.remove(templateName);
                    if (error != null || !Boolean.TRUE.equals(copied)) {
                        admin.sendMessage(plugin.config().messages().get("room.create-failed",
                                "reason", error == null ? "复制失败" : String.valueOf(error.getMessage())));
                        return;
                    }
                    World world = new WorldCreator(worldName).createWorld();
                    if (world == null) {
                        admin.sendMessage(plugin.config().messages().get("room.create-failed",
                                "reason", "服务器未能加载世界"));
                        return;
                    }
                    // 编辑现场已恢复到 k_tpl：安全起见清掉 recover 残留（内容一致）
                    if (recoverDir.isDirectory()) {
                        deleteRecursively(recoverDir);
                    }
                    // 与「已加载」分支同一守卫：对局中/旁观中不把人拽进编辑世界
                    if (roomOf(admin) == null && !plugin.spectator().isSpectator(admin)) {
                        admin.teleport(world.getSpawnLocation());
                    }
                    admin.sendMessage(plugin.config().messages().get("room.moonmap-loaded",
                            "world", worldName, "template", templateName));
                }));
    }

    /**
     * 保存并卸载模板编辑世界：世界存盘后写回 {@code moonmaps/<名>}（先备份旧模板，
     * 失败可回滚），最后删除服务器根目录的编辑副本。
     */
    public void unloadMoonmap(Player admin, String templateName) {
        unloadMoonmap(admin, templateName, null);
    }

    /**
     * 同上；写回**真正落盘后**在主线程回调 {@code onSuccess}（null = 不需要）。
     *
     * <p>调用方若要紧接着改动 {@code moonmaps/<名>} 目录本身（例如 arena setup 收尾时把
     * 模板名对齐成场地 id），必须走这个回调：写回是异步的，在方法返回后立刻改名会与写回
     * 抢同一个文件夹——新图会落在旧名下（房间按 id 读到旧图），或改出一个半截模板。</p>
     */
    public void unloadMoonmap(Player admin, String rawTemplateName, Runnable onSuccess) {
        final String templateName = validatedMoonmapName(rawTemplateName, "[a-z0-9_-]{1,32}");
        if (templateName == null) {
            admin.sendMessage(plugin.config().messages().get("room.arena-bad-id"));
            return;
        }
        String worldName = TEMPLATE_EDIT_PREFIX + templateName;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-not-loaded", "template", templateName));
            return;
        }
        if (!pendingMoonmaps.add(templateName)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-busy"));
            return;
        }
        // 先把编辑世界内的玩家（含执行 unload 的管理员自己）传出去：
        // unloadWorld 对有玩家的世界必定失败——这是标准流程下最容易踩的坑
        Location fallback = plugin.lobby().spawn();
        if (fallback == null && !Bukkit.getWorlds().isEmpty()) {
            fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        for (Player occupant : world.getPlayers()) {
            if (fallback != null) {
                occupant.teleport(fallback);
            }
        }
        // 编辑世界还加载着，用世界自己的文件夹最准（26.1 下它在 dimensions/minecraft 里）
        File loadedDir = world.getWorldFolder();
        File editDir = loadedDir != null ? loadedDir : worldCopyTarget(worldName);
        File moonmapsDir = new File(plugin.getDataFolder(), "moonmaps");
        File target = new File(moonmapsDir, templateName);
        File backup = new File(moonmapsDir, templateName + "_bak");
        File recover = new File(new File(plugin.getDataFolder(), "moonmap-recover"), templateName);
        world.save();
        if (!Bukkit.unloadWorld(world, true)) {
            pendingMoonmaps.remove(templateName);
            admin.sendMessage(plugin.config().messages().get("room.create-failed", "reason", "世界卸载失败"));
            return;
        }
        admin.sendMessage(plugin.config().messages().get("room.moonmap-unloading", "template", templateName));
        // 管理员 UUID 在主线程取好：异步块里只认这个值，不碰 Player/Bukkit 对象
        UUID adminId = admin.getUniqueId();
        CompletableFuture.runAsync(() -> {
            boolean written = false;
            try {
                if (target.exists()) {
                    // 上次失败留下的 <名>_bak 会让「目标已存在时的 renameTo」永久失败，把该模板
                    // 钉死在备份分支上：先清掉它；清不掉就别往下走，免得只做了一半。
                    if (backup.exists() && !deleteRecursively(backup)) {
                        plugin.getLogger().warning("[moonmap] 旧备份清理失败，本次未写回：" + backup);
                        // 编辑成果不能留在服务器根，先转存 recover，再按转存结果如实告知
                        notifyStashResult(adminId, editDir, recover, templateName);
                        return;
                    }
                    if (!target.renameTo(backup)) {
                        plugin.getLogger().warning("[moonmap] 旧模板备份失败，本次未写回，新图仍在 "
                                + editDir + "：" + templateName);
                        notifyStashResult(adminId, editDir, recover, templateName);
                        return;
                    }
                } else if (backup.exists()) {
                    // 目标不在、但留着备份：那可能是上一轮回滚失败后仅存的旧图，必须保留，
                    // 不能当垃圾清掉（清掉就永久失去旧模板）。
                }
                File editContent = templateSource(editDir) != null ? templateSource(editDir) : editDir;
                if (!copyWorldFolder(editContent, target)) {
                    // 写回失败：回滚旧模板，编辑副本转存 moonmap-recover（防启动清理误删，下次 load 自动恢复）
                    deleteRecursively(target);
                    if (backup.exists()) {
                        backup.renameTo(target);
                    }
                    notifyStashResult(adminId, editDir, recover, templateName);
                    return;
                }
                // 编辑世界丢过 level.dat（旧版地图常见）时，用备份里的那份救回来，
                // 否则模板下一轮就不是世界文件夹了
                if (!new File(target, "level.dat").isFile() && new File(backup, "level.dat").isFile()) {
                    try {
                        java.nio.file.Files.copy(new File(backup, "level.dat").toPath(),
                                new File(target, "level.dat").toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception levelDatError) {
                        plugin.getLogger().warning("[moonmap] 恢复 level.dat 失败："
                                + levelDatError.getMessage());
                    }
                }
                deleteRecursively(backup);
                if (recover.isDirectory()) {
                    deleteRecursively(recover);   // 清掉上次写回失败遗留的副本
                }
                deleteRecursively(editDir);
                plugin.getLogger().info("[moonmap] 模板已保存回 moonmaps：" + templateName);
                written = true;
            } finally {
                if (written && onSuccess != null) {
                    // 先跑回调（含异名改名）再释放 pendingMoonmaps：否则这个窗口里管理员重入
                    // setup 会从正在被改名的源目录开始复制，复制到一半源就没了。
                    plugin.scheduler().runSync(() -> {
                        try {
                            onSuccess.run();
                        } finally {
                            pendingMoonmaps.remove(templateName);
                        }
                    });
                } else {
                    pendingMoonmaps.remove(templateName);
                }
            }
        }, worldIo);
    }

    /**
     * 把编辑副本转存到 {@code moonmap-recover/<名>}（写回失败的兜底）：留在服务器根的
     * {@code k_tpl_<名>} 会在下次启动/重载时被当残留清掉，转存后 {@code loadMoonmap} 会自动恢复。
     *
     * @return 是否转存成功
     */
    private boolean stashEditDirToRecover(File editDir, File recover) {
        recover.getParentFile().mkdirs();
        if (editDir.renameTo(recover)) {
            plugin.getLogger().warning("[moonmap] 编辑副本已转存 moonmap-recover/"
                    + recover.getName() + "（下次 load 自动恢复）");
            return true;
        }
        plugin.getLogger().warning("[moonmap] 编辑副本转存失败，仍在 " + editDir
                + "（请立刻手动处理，重启会被当残留清理）");
        return false;
    }

    /**
     * 写回失败时统一收尾：先把编辑副本转存 {@code moonmap-recover/}，再按**真实结果**告知管理员。
     *
     * <p>转存成功 → 下次 load 会自动恢复，照提示重进即可；转存失败 → 编辑副本还留在服务器根，
     * reload / 重启都会被当残留清掉，必须人工搬走。此时若还用「已转存 recover」的文案就是谎报，
     * 会让管理员以为什么都不用做。</p>
     */
    private void notifyStashResult(UUID adminId, File editDir, File recover, String templateName) {
        boolean stashed = stashEditDirToRecover(editDir, recover);
        notifyMoonmapFailure(adminId,
                stashed ? "room.moonmap-writeback-failed" : "room.moonmap-backup-failed",
                templateName);
    }

    /**
     * 从 worldIo 线程安全地告诉管理员「模板写回失败」：回主线程再取玩家对象并发送，
     * 避免在异步线程上碰 Bukkit（玩家可能已下线）。UUID 由主线程取好传进来。
     */
    private void notifyMoonmapFailure(UUID adminId, String key, String templateName) {
        if (adminId == null) {
            return;
        }
        plugin.scheduler().runSync(() -> {
            Player online = Bukkit.getPlayer(adminId);
            if (online != null && online.isOnline()) {
                online.sendMessage(plugin.config().messages().get(key, "template", templateName));
            }
        });
    }

    /**
     * 导入服务器已有世界为月面模板：把世界容器里的 {@code <worldName>} 文件夹复制为
     * {@code moonmaps/<arenaId>}（跳过玩家痕迹与世界锁），供房间复制与 arena setup 使用。
     *
     * <p>世界正加载着时先 {@code world.save()} 刷盘再复制，保证磁盘上是完整数据；
     * 复制在 worldIo 线程异步进行，期间该模板名的 load/unload 会被挂起（pendingMoonmaps）。</p>
     */
    public void importMoonmap(Player admin, String rawWorldName, String rawArenaId) {
        if (admin == null || !admin.isOnline() || rawWorldName == null || rawWorldName.isBlank()
                || rawArenaId == null || rawArenaId.isBlank()) {
            return;
        }
        // 世界名同样直接拼路径，只收普通文件夹名（允许大写），拒绝分隔符与 ..
        final String worldName = validatedMoonmapName(rawWorldName, "[A-Za-z0-9_-]{1,64}");
        if (worldName == null) {
            admin.sendMessage(plugin.config().messages().get("room.import-not-world", "world", rawWorldName));
            return;
        }
        final String templateKey = validatedMoonmapName(
                rawArenaId.toLowerCase(java.util.Locale.ROOT), "[a-z0-9_-]{1,32}");
        if (templateKey == null
                || !com.taketori.kassen.paper.match.ArenaManager.isValidId(templateKey)) {
            admin.sendMessage(plugin.config().messages().get("room.arena-bad-id"));
            return;
        }
        // 26.1 的世界在 <level>/dimensions/minecraft/<name>，旧版在容器根目录，两处都要找
        final File source = resolveWorldFolder(worldName);
        if (source == null) {
            admin.sendMessage(plugin.config().messages().get("room.import-not-world", "world", worldName));
            // 「导入不了」光说一句没用：把找过的位置与这里真正能导入的世界一并列出来，
            // 管理员才知道该把地图放哪、或者名字是不是写错了。
            File looked = worldCopyTarget(worldName);
            admin.sendMessage("<dark_gray>  插件查找的位置：<white>" + looked.getAbsolutePath() + "</white>"
                    + (looked.isDirectory() ? "（目录存在，但不是可用的世界文件夹）" : "（该目录不存在）"));
            String importable = importableWorlds();
            if (!importable.isEmpty()) {
                admin.sendMessage("<dark_gray>  这个位置下能导入的世界：<white>" + importable);
            }
            return;
        }
        File target = new File(new File(plugin.getDataFolder(), "moonmaps"), templateKey);
        if (target.exists()) {
            admin.sendMessage(plugin.config().messages().get("room.import-exists", "id", templateKey));
            return;
        }
        if (!pendingMoonmaps.add(templateKey)) {
            admin.sendMessage(plugin.config().messages().get("room.moonmap-busy"));
            return;
        }
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            loaded.save();   // 世界还开着：先把内存区块刷到磁盘再复制
        }
        admin.sendMessage(plugin.config().messages().get("room.import-started", "world", worldName, "id", templateKey));
        CompletableFuture
                .supplyAsync(() -> copyWorldFolder(source, target), worldIo)
                .whenComplete((copied, error) -> plugin.scheduler().runSync(() -> {
                    pendingMoonmaps.remove(templateKey);
                    if (error != null || !Boolean.TRUE.equals(copied)) {
                        String reason = error == null ? "复制失败" : String.valueOf(error.getMessage());
                        plugin.getLogger().warning("[moonmap] 导入 " + worldName + " → " + templateKey + " 失败：" + reason);
                        deleteRecursively(target);   // 半截副本不留
                        admin.sendMessage(plugin.config().messages().get("room.import-failed", "reason", reason));
                        return;
                    }
                    plugin.getLogger().info("[moonmap] 已从服务器世界导入模板：" + worldName + " → moonmaps/" + templateKey);
                    admin.sendMessage(plugin.config().messages().get("room.import-done", "id", templateKey));
                }));
    }

    // ---------------------------------------------------------------- 驱动

    /** 由主类每秒调用：观战提醒（全局一份）+ 各房间计时 + 重连会话过期清理。 */
    public void tickAll() {
        plugin.spectator().tickReminders();
        if (plugin.rejoin() != null) {
            plugin.rejoin().purgeExpired();
        }
        for (GameRoom room : new ArrayList<>(rooms.values())) {
            room.tick();
        }
    }

    // ---------------------------------------------------------------- 重连挂载

    /**
     * 断线重连：把重连玩家重新绑定到原房间（队伍条目断线时一直保留着，
     * 这里只补回 playerRooms 权威映射——与 joinRoom 不同，不再走等待区/封存流程）。
     */
    public void attachAfterRejoin(Player player, GameRoom room) {
        if (player == null || room == null) {
            return;
        }
        playerRooms.put(player.getUniqueId(), room);
    }
}
