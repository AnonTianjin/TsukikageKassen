package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.core.worlds.WorldScope;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.BaseMarker;
import com.taketori.kassen.paper.match.BaseCaptureManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.LootSpawner;
import com.taketori.kassen.paper.match.MatchScoreboard;
import com.taketori.kassen.paper.match.MinionSpawner;
import com.taketori.kassen.paper.match.OutpostManager;
import com.taketori.kassen.paper.item.PDCKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 游戏房间：一个启用场地对应一个房间，可反复开局、多房间并发互不干扰。
 *
 * <p>生命周期：{@code WAITING（等待加入）→ STARTING（倒计时）→ CAGED（进笼冻结）
 * → PLAYING（战斗）→ ENDING（结算）→ WAITING}：人数达 waiting.min-players 开始倒计时，
 * 满员切短倒计时、掉人取消；开局瞬间均衡分队并传送进出生点玻璃笼，笼解除后正式开战；
 * 胜负后 ENDING 延迟 end-delay 秒回大厅并重置复用。本类聚合从 MatchManager 迁入的
 * <b>队伍、比分、击杀、角色裁决、胜负判定、播报</b>等单局状态与逻辑，以及该房间自有的
 * 刷怪/占点/据点/道具/记分板组件。</p>
 *
 * <p>注意：玩家"属于哪个房间"的唯一权威映射在 {@link RoomManager}；本类的
 * {@link #waiting} 与 {@link #teams} 只是参与者名单。WAITING/STARTING 期间玩家中立无队伍，
 * 开局瞬间才均衡分队。</p>
 */
public final class GameRoom {

    /** 房间阶段。 */
    public enum Phase {
        /** 等待玩家加入。 */
        WAITING,
        /** 人数达标，倒计时中。 */
        STARTING,
        /** 已分队传送进出生点玻璃笼，短暂无敌冻结。 */
        CAGED,
        /** 正式战斗。 */
        PLAYING,
        /** 胜负已分，结算收尾中。 */
        ENDING
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final RoomManager manager;
    private final ArenaDef arena;
    /** 创建序号：房间 id（自增数字），快速加入平局时优先先创建的房间。 */
    private final long order;
    /** 模板 id（arenas.yml 条目名，本房间世界由它复制而来）。 */
    private final String templateId;
    /** 房主（创建者）；快速加入自动建房时为发起玩家。 */
    private final UUID creatorId;
    /** 房间创建时间（毫秒）。 */
    private final long createdAt;
    /** 等待房全员离线的起始时刻（0 = 房内有人）；超过 room.empty-dispose-seconds 解散回收世界。 */
    private long emptySinceMillis;

    private Phase phase = Phase.WAITING;
    /** 房间模式（每房独立，不再写回全局 config）。 */
    private boolean pve;

    /**
     * 本房间是否战国 3v3 模式。
     *
     * <p>与 {@code pve} 同一个口径：<b>创建时从全局配置快照一次</b>，之后只受
     * {@link #setMode(String)} 影响。刻意不在 {@link #isSengoku()} 里现读配置——
     * 那样 {@code /taketori reload} 改一下 {@code match.mode} 就会让<b>正在进行的</b>
     * 房间瞬间变异（PVP 房突然开始跑战国逻辑），是最难查的一类事故。</p>
     */
    private boolean sengoku;
    private MatchRules rules = MatchRules.defaults();

    /** WAITING/STARTING 阶段的中立等待者（开局分队后清空，参与者转由 teams 记录）。 */
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    /** 本房对局中由插件施加过 Saturation 的玩家：收尾只清这里记过的，不动其他来源的效果。 */
    private final Set<UUID> pluginSaturation = new HashSet<>();

    private final Map<TeamId, Integer> teamScores = new EnumMap<>(TeamId.class);
    private final Map<UUID, Integer> playerScores = new HashMap<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Map<UUID, TeamId> teams = new HashMap<>();
    /** 本局每个玩家的击杀数：玩家与月人分开记，记分板显示两者合计。 */
    private final Map<UUID, Integer> playerKills = new HashMap<>();
    private final Map<UUID, Integer> minionKills = new HashMap<>();

    private long startedAt;
    private long endedAt;
    private TeamId winner;

    private UUID lastScorer;
    private String lastScoreReason = "-";
    private int lastScoreAmount;

    // ---- 房间自有组件（Task 5 起构造注入本房间，替换全局单例）----
    private final MinionSpawner minions;
    private final BaseCaptureManager baseCapture;
    private final OutpostManager outpost;
    private final LootSpawner loot;
    private final MatchScoreboard board;
    /** 出生点玻璃笼（每局重建/还原）。 */
    private final CageBuilder cage = new CageBuilder(this);
    /** 对局区域四周屏障墙（每局重建/还原）。 */
    private final BarrierBuilder barrier = new BarrierBuilder(this);
    /** 双方基地的队伍颜色粒子标记（仅 PVP 对局启用）。 */
    private final BaseMarker baseMarker = new BaseMarker(this);

    // ---- 进房封存（BedWars2020 PlayerGoods 式全量快照）：自带背包/状态封存，结算或退房返还 ----
    private final Map<UUID, InventorySnapshot> backups = new HashMap<>();
    /** 整局成员缓存（membersCache 双轨）：掉线移出 teams 后仍保留，用于结算名单。 */
    private final Map<UUID, TeamId> rosterCache = new HashMap<>();
    /**
     * 开局时各参赛者的角色快照：结算清理只清「角色仍与本局一致」的人。玩家中途离队后到别处
     * 重新选了角色，那是他的新状态，不能被本局结算顺手抹掉（武器/属性一并清就更严重了）。
     */
    private final Map<UUID, String> roleSnapshot = new HashMap<>();
    /** 对局缺人（可补位）持续的起始时刻；补满即清零，超过宽限期无人补位 → 缺人队判负。 */
    private long understaffedSinceMillis;

    /**
     * 进房封存的玩家状态快照（对齐 BedWars2020 的 PlayerGoods）：背包三件套、血量、饥饿、
     * 经验、游戏模式、飞行、药水效果。{@link #restoreTo} 清掉当前状态后原样写回。
     */
    public record InventorySnapshot(org.bukkit.inventory.ItemStack[] contents,
                                    org.bukkit.inventory.ItemStack[] armor,
                                    org.bukkit.inventory.ItemStack offhand,
                                    double health, int foodLevel,
                                    int level, float exp,
                                    GameMode gameMode, boolean flying,
                                    List<PotionEffect> potions) {

        public void restoreTo(Player player) {
            var inv = player.getInventory();
            inv.clear();
            if (contents != null) {
                inv.setStorageContents(contents);
            }
            if (armor != null) {
                inv.setArmorContents(armor);
            }
            if (offhand != null) {
                inv.setItemInOffHand(offhand);
            }
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            if (potions != null) {
                for (PotionEffect effect : potions) {
                    if (effect != null) {
                        player.addPotionEffect(effect);
                    }
                }
            }
            player.setExp(exp);
            player.setLevel(level);
            player.setFoodLevel(Math.max(0, foodLevel));
            player.setGameMode(gameMode == null ? GameMode.SURVIVAL : gameMode);
            double maxHealth = player.getMaxHealth();
            player.setHealth(health <= 0.0D ? maxHealth : Math.max(1.0D, Math.min(health, maxHealth)));
            if (flying) {
                try {
                    player.setFlying(true);
                } catch (Throwable ignored) {
                    // 生存模式不接受飞行：静默忽略
                }
            }
        }
    }

    // ---- 倒计时 / 笼子 / 结算的定时状态 ----
    /** STARTING 倒计时起点（毫秒）。 */
    private long countdownStart;
    /** STARTING 当前剩余总时长（毫秒，满员时缩短）。 */
    private long countdownMillis;
    /** 满员短倒计时毫秒（0 = 满员立即开局）。 */
    private long countdownFullMillis;
    /** 是否已切过满员短倒计时（只播报一次）。 */
    private boolean countdownFull;
    /** 是否已切过半挡倒计时（人数过半场时一次性压缩）。 */
    private boolean countdownHalf;
    /** CAGED 解除任务句柄。 */
    private BukkitTask cageTask;
    /** ENDING 延迟收尾任务句柄。 */
    private BukkitTask endTask;

    GameRoom(TaketoriPlugin plugin, RoomManager manager, ArenaDef arena, long order,
             String templateId, UUID creatorId) {
        this(plugin, manager, arena, order, templateId, creatorId, null);
    }

    GameRoom(TaketoriPlugin plugin, RoomManager manager, ArenaDef arena, long order,
             String templateId, UUID creatorId, SengokuMode mode) {
        this.plugin = plugin;
        this.manager = manager;
        this.arena = arena;
        this.order = order;
        this.templateId = templateId == null ? arena.id() : templateId;
        this.creatorId = creatorId;
        this.createdAt = System.currentTimeMillis();
        if (mode != null) {
            this.pve = mode == SengokuMode.PVE;
            this.sengoku = mode.isSengoku();
        } else {
            // 未显式指定模式：沿用全局配置（兼容旧入口）
            this.pve = "pve".equalsIgnoreCase(plugin.getConfig().getString("match.mode", "pvp"));
            this.sengoku = SengokuMode
                    .parse(plugin.getConfig().getString("match.mode", "pvp"),
                            SengokuMode.PVP)
                    .isSengoku();
        }
        this.minions = new MinionSpawner(this);
        this.baseCapture = new BaseCaptureManager(this);
        this.outpost = new OutpostManager(this);
        this.loot = new LootSpawner(this);
        this.loot.load();
        this.board = new MatchScoreboard(this);
        loadRules();
        resetScores();
    }

    // ---------------------------------------------------------------- 基本信息

    public TaketoriPlugin plugin() {
        return plugin;
    }

    public ArenaDef arena() {
        return arena;
    }

    /** 房间 id = 创建序号（自增数字字符串），与房间世界名 kassen_<id> 对应。 */
    public String id() {
        return Long.toString(order);
    }

    /** 模板 id（arenas.yml 条目名，本房间世界由它复制而来）。 */
    public String templateId() {
        return templateId;
    }

    /** 房主（创建者）。 */
    public UUID creatorId() {
        return creatorId;
    }

    /** 房间创建时间（毫秒）。 */
    public long createdAt() {
        return createdAt;
    }

    public String display() {
        return arena.display() + " · 第" + order + "场";
    }

    public long order() {
        return order;
    }

    /** 房间专属世界（克隆模板时全部点位已重定向到它）。 */
    public World world() {
        return arena.arenaWorld();
    }

    public Phase phase() {
        return phase;
    }

    /** 是否处于正式战斗阶段（组件 tick / 计分判定用）。 */
    /** 战国模式的天守阁管理（懒加载：非战国房间永远不会创建它）。 */
    private com.taketori.kassen.paper.match.sengoku.KeepManager sengokuKeep;

    /** 战国模式的小局编排（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.SengokuSession sengokuSession;

    /** 战国模式的箭楼（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.TowerManager sengokuTowers;

    /** 战国模式的箭楼占领读条（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.TowerCaptureManager sengokuCapture;

    /** 战国模式的大将击破器（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.SiegeBreakerManager sengokuSiege;

    /** 战国模式的跳跃台（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.JumpPadManager sengokuJumpPads;

    /** 战国模式的中地小兵（懒加载）。 */
    private com.taketori.kassen.paper.match.sengoku.MidMinionManager sengokuMidMinions;

    /** 战国模式的能量槽（懒加载；纯状态，没有 tick）。 */
    private com.taketori.kassen.paper.match.sengoku.EnergyManager sengokuEnergy;

    /**
     * 本房间是否战国 3v3 模式。
     *
     * <p>读的是创建时的快照（与 {@code pve} 同口径），不是全局配置的实时值——
     * 所以「改 config.yml 再 reload」只影响之后新建的房间，不会让正在进行的对局变异。</p>
     */
    public boolean isSengoku() {
        return sengoku;
    }

    /** 战国模式的天守阁管理（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.KeepManager keep() {
        if (sengokuKeep == null) {
            sengokuKeep = new com.taketori.kassen.paper.match.sengoku.KeepManager(this);
        }
        return sengokuKeep;
    }

    /** 战国模式的小局编排（懒加载：非战国房间永远不会创建它）。 */
    public com.taketori.kassen.paper.match.sengoku.SengokuSession sengoku() {
        if (sengokuSession == null) {
            sengokuSession = new com.taketori.kassen.paper.match.sengoku.SengokuSession(this);
        }
        return sengokuSession;
    }

    /** 战国模式的箭楼（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.TowerManager towers() {
        if (sengokuTowers == null) {
            sengokuTowers = new com.taketori.kassen.paper.match.sengoku.TowerManager(this);
        }
        return sengokuTowers;
    }

    /** 战国模式的箭楼占领读条（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.TowerCaptureManager towerCapture() {
        if (sengokuCapture == null) {
            sengokuCapture = new com.taketori.kassen.paper.match.sengoku.TowerCaptureManager(this);
        }
        return sengokuCapture;
    }

    /** 战国模式的大将击破器（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.SiegeBreakerManager siege() {
        if (sengokuSiege == null) {
            sengokuSiege = new com.taketori.kassen.paper.match.sengoku.SiegeBreakerManager(this);
        }
        return sengokuSiege;
    }

    /** 战国模式的跳跃台（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.JumpPadManager jumpPads() {
        if (sengokuJumpPads == null) {
            sengokuJumpPads = new com.taketori.kassen.paper.match.sengoku.JumpPadManager(this);
        }
        return sengokuJumpPads;
    }

    /** 战国模式的中地小兵（懒加载）。 */
    public com.taketori.kassen.paper.match.sengoku.MidMinionManager midMinions() {
        if (sengokuMidMinions == null) {
            sengokuMidMinions = new com.taketori.kassen.paper.match.sengoku.MidMinionManager(this);
        }
        return sengokuMidMinions;
    }

    /** 战国模式的能量槽（懒加载；纯状态，没有 tick）。 */
    public com.taketori.kassen.paper.match.sengoku.EnergyManager energy() {
        if (sengokuEnergy == null) {
            sengokuEnergy = new com.taketori.kassen.paper.match.sengoku.EnergyManager(this);
        }
        return sengokuEnergy;
    }

    public boolean isRunning() {
        return phase == Phase.PLAYING;
    }

    /**
     * 对局是否已经开始（玻璃笼准备 + 正式战斗）。
     *
     * <p>比 {@link #isRunning()} 多算 CAGED：这两个阶段玩家都应当持有角色装备，所以在玻璃笼里
     * 换角色必须立刻换装——否则角色切了、旧武器被清掉、新武器又不发，开局就是整局空手。</p>
     */
    public boolean isMatchInProgress() {
        return phase == Phase.CAGED || phase == Phase.PLAYING;
    }

    // ---------------------------------------------------------------- 规则与模式

    /**
     * 从 config.yml 读取对局规则作为本房间快照；模式取本房间自己的 pve 标志。
     * 插件启用、reload 重建房间、管理员切模式时调用。
     */
    public void loadRules() {
        var cfg = plugin.getConfig();
        // 月人刷新节奏：arenas.yml 的 minion-spawn（场地级）优先，未配置回落 config.yml 的 minion.* 全局默认；
        // normal / mixed 两类刷新区各有独立的启用开关与间隔，未单独配置时都回落 minion.interval-seconds（旧行为）
        int baseInterval = Math.max(1, cfg.getInt("minion.interval-seconds", 9));
        rules = new MatchRules(
                cfg.getInt("match.score-to-win", 600),
                Math.max(1, cfg.getInt("match.time-limit-minutes", 20)) * 60,
                Math.max(0, cfg.getInt("match.respawn-delay-seconds", 5)),
                cfg.getInt("scoring.minion-kill", 3),
                cfg.getInt("scoring.player-kill", 10),
                cfg.getInt("scoring.base-capture", 50),
                Math.max(1, cfg.getInt("minion.health", 40)),
                cfg.getBoolean("minion.iron-armor", true),
                arena.minionSpawnIntervalSeconds(baseInterval),
                cfg.getBoolean("minion.normal.enabled", true),
                arena.minionSpawnIntervalSeconds(
                        Math.max(1, cfg.getInt("minion.normal.interval-seconds", baseInterval))),
                cfg.getBoolean("minion.mixed.enabled", true),
                Math.max(1, cfg.getInt("minion.mixed.interval-seconds", baseInterval)),
                arena.minionSpawnPerSpawn(Math.max(1, cfg.getInt("minion.per-spawn", 3))),
                arena.minionSpawnMaxAlive(Math.max(1, cfg.getInt("minion.max-alive", 15))),
                Math.max(0.1D, cfg.getDouble("base.capture-seconds", 10.0D)),
                Math.max(0.0D, cfg.getDouble("base.capture-delay-seconds", 60.0D)),
                Math.max(0.0D, cfg.getDouble("base.decay-per-second", 0.5D)),
                cfg.getBoolean("base.multi-player-bonus", true),
                cfg.getBoolean("match.keep-inventory", true),
                Math.max(0.0D, cfg.getDouble("combat.kill-heal", 6.0D)),
                Math.max(0.0D, cfg.getDouble("combat.minion-kill-heal", 0.0D)),
                pve,
                cfg.getString("combat.friendly-fire-protection", "auto"));
    }

    public MatchRules rules() {
        return rules;
    }

    /** 当前是不是 PVE 模式（所有人一队打月人：玩家之间无伤害、不做基地占点）。 */
    public boolean isPve() {
        return pve;
    }

    /**
     * 友伤保护是否生效（同一房间同一队的玩家互相不造成伤害）。
     * auto（默认）：PVP 与 PVE 均开启保护（PVE 开局播报即承诺"玩家之间不会互相造成伤害"）；
     * on：两种模式都保护；off：都不保护。
     */
    public boolean isFriendlyFireProtected() {
        String policy = rules().friendlyFire() == null
                ? "auto" : rules().friendlyFire().trim().toLowerCase(Locale.ROOT);
        return switch (policy) {
            case "on", "true", "always", "yes" -> true;
            case "off", "false", "never", "no" -> false;
            default -> true;
        };
    }

    /**
     * 切换本房间 PVP / PVE（只影响本房间的规则快照，不写全局 config）。
     * 仅 WAITING 阶段允许切换。
     *
     * @return 失败原因；成功返回 null
     */
    public String setMode(String mode) {
        if (phase != Phase.WAITING) {
            return "房间已经开始，不能切换模式。";
        }
        this.pve = "pve".equalsIgnoreCase(mode);
        this.sengoku = com.taketori.kassen.core.match.sengoku.SengokuMode
                .parse(mode, com.taketori.kassen.core.match.sengoku.SengokuMode.PVP)
                .isSengoku();
        loadRules();
        return null;
    }

    // ---------------------------------------------------------------- 等待者与容量

    /** 把玩家加入等待名单（玩家→房间的映射由 RoomManager 维护）。 */
    public boolean addWaiting(Player player) {
        if (player == null) {
            return false;
        }
        boolean added = waiting.add(player.getUniqueId());
        if (added) {
            playerNames.put(player.getUniqueId(), player.getName());
            playerScores.putIfAbsent(player.getUniqueId(), 0);
        }
        return added;
    }

    public void removeWaiting(UUID uuid) {
        if (uuid != null) {
            waiting.remove(uuid);
        }
    }

    public Set<UUID> waitingView() {
        return Set.copyOf(waiting);
    }

    /** 在线的等待者（掉线者不计入人数与倒计时）。 */
    public List<Player> waitingPlayers() {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : waiting) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    /** 在线等待人数（倒计时判定用）。 */
    public int waitingCount() {
        return waitingPlayers().size();
    }

    /** 每队人数上限。 */
    public int teamSizeCap() {
        return Math.max(1, plugin.config().matchTeamSize());
    }

    /** 房间满员人数：PVP=每队上限×2；PVE 取 waiting.pve-full-players。 */
    public int maxPlayers() {
        return isPve() ? Math.max(1, plugin.config().waitingPveFullPlayers()) : teamSizeCap() * 2;
    }

    /** 房间当前是否可被匹配加入（等待/倒计时且未满）。 */
    public boolean isJoinable() {
        return (phase == Phase.WAITING || phase == Phase.STARTING)
                && waitingCount() < maxPlayers();
    }

    /**
     * 房间内所有<b>在场</b>参与者（等待者 + 已分队玩家），空房自动停止判定用。
     *
     * <p>必须排除"已离局但保留重连窗口"的人：主动离局走 {@code leaveForRejoin} 会保留
     * teams 条目占位，同时登记一条重连会话，但人已经被送回大厅了。只按
     * {@code isOnline()} 计数会把他算成在场者，导致 {@code abortEmpty} 与 WAITING
     * 空房回收永远等不到 {@code count == 0}，房间世界要卡到重连窗口过期才回收。</p>
     *
     * <p>判据用「有活跃重连会话」而不是「不在本房世界」：后者会把管理员 {@code /tp}
     * 拉走的在役玩家也算成离场，反而可能误收掉一个还该继续的房间。</p>
     */
    public int onlineParticipantCount() {
        Set<UUID> all = new LinkedHashSet<>(waiting);
        all.addAll(teams.keySet());
        var rejoin = plugin.rejoin();
        int count = 0;
        for (UUID uuid : all) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (rejoin != null && rejoin.hasSession(uuid)) {
                continue;   // 已离局占位（人在大厅），不算在场
            }
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- 队伍

    public TeamId teamOf(UUID uuid) {
        return teams.get(uuid);
    }

    /** 开局分队时调用：登记队伍（在场名单 + 整局成员缓存）、名字、记分板。 */
    public void join(Player player, TeamId team) {
        teams.put(player.getUniqueId(), team);
        rosterCache.put(player.getUniqueId(), team);
        playerNames.put(player.getUniqueId(), player.getName());
        playerScores.putIfAbsent(player.getUniqueId(), 0);
        board.showTo(player);
    }

    public void leave(UUID uuid) {
        teams.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            board.hide(player);
            // 对局中被移出（管理员 team none 等）：把开局前备份的背包还回去
            restoreInventoryIfAny(player);
        }
    }

    /** 对局中退出（掉线/主动）：从在场名单移除（槽位可被补位），整局成员缓存保留到结算；只撤记分板。 */
    public void quitMatch(UUID uuid) {
        teams.remove(uuid);
        board.hide(Bukkit.getPlayer(uuid));
    }

    /**
     * 对局中掉线且已登记重连会话：只撤记分板，<b>保留</b> teams 条目——
     * 重连窗口内该位置被占住，重连直接回原队（{@code RejoinManager.tryRejoin}）；
     * 会话过期后由 {@code RejoinManager.purgeExpired} 调 {@link #quitMatch(UUID)} 释放。
     */
    public void quitMatchKeepEntry(UUID uuid) {
        board.hide(Bukkit.getPlayer(uuid));
    }

    /**
     * 玩家自助选择队伍（大厅 GUI 的「队伍选择」）。
     * 战斗中不允许换边；PVE 全部并到红队；PVP 队伍满员会被拒绝。
     *
     * @return 失败原因；成功返回 null
     */
    public String chooseTeam(Player player, TeamId team) {
        if (player == null || team == null) {
            return "队伍无效。";
        }
        if (phase == Phase.PLAYING || phase == Phase.CAGED) {
            return "对局进行中不能换队（观战请点「旁观」）。";
        }
        if (isPve()) {
            join(player, TeamId.RED);
            return null;
        }
        TeamId current = teams.get(player.getUniqueId());
        if (current == team) {
            return null;   // 已在该队
        }
        if (!canChooseTeam(player, team)) {
            TeamId other = team == TeamId.RED ? TeamId.BLUE : TeamId.RED;
            int targetSize = teamPlayers(team).size();
            int otherSize = teamPlayers(other).size();
            if (targetSize >= teamSizeCap()) {
                return team.display() + " 已满（每队上限 " + teamSizeCap() + " 人）。";
            }
            return team.display() + " 人数多于" + other.display()
                    + "（" + targetSize + " : " + otherSize
                    + "），为保持平衡请选择" + other.display() + "。";
        }
        join(player, team);
        return null;
    }

    /**
     * 平衡规则（等待区手动选边）：把玩家从当前归属取出后，只能加入人数<b>不超过对方</b>
     * 的队伍——即加入后两队人数差最多 1。去人多的一边（会让差扩大到 2+）一律禁止。
     *
     * <p>例：红 3 蓝 0，新玩家只能选蓝（0 ≤ 3）；红 2 蓝 2 时两边都可选（加入后 3:2）；
     * 蓝队玩家在红 1 蓝 3 时可以换到红（取出后红 1 ≤ 蓝 2）。</p>
     */
    public boolean canChooseTeam(Player player, TeamId team) {
        if (player == null || team == null || isPve()) {
            return isPve();
        }
        int target = 0;
        int other = 0;
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            // 判定时先把玩家自己从计数里取出（他可能正从另一队换过来）
            if (entry.getKey().equals(player.getUniqueId())) {
                continue;
            }
            if (entry.getValue() == team) {
                target++;
            } else {
                other++;
            }
        }
        if (target >= teamSizeCap()) {
            return false;
        }
        return target <= other;
    }

    public List<Player> teamPlayers(TeamId team) {
        List<Player> result = new ArrayList<>();
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() != team) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    public Map<UUID, TeamId> teamsView() {
        return Map.copyOf(teams);
    }

    public void clearTeams() {
        teams.clear();
        rosterCache.clear();
        playerNames.clear();
    }

    // ---------------------------------------------------------------- 补位与投降

    /** 投降表决：发起后 30 秒内，半数以上在线队友确认即整场判负结束。 */
    private static final long SURRENDER_WINDOW_MILLIS = 30_000L;

    private record SurrenderVote(Set<UUID> confirmed, long deadlineMillis) {
    }

    private final Map<TeamId, SurrenderVote> surrenderVotes = new HashMap<>();

    /**
     * 某队当前的投降表决快照；没有进行中的表决返回 {@code null}。
     *
     * <p>只读查询，供侧栏记分板与鹦鹉螺壳 GUI 显示「已确认 / 所需票数 + 剩余秒数」。
     * 返回的是不可变快照，调用方不会改到内部表决状态。</p>
     *
     * @param team 查询的队伍
     * @return 表决进度；无表决为 null
     */
    public SurrenderStatus surrenderStatus(TeamId team) {
        if (team == null) {
            return null;
        }
        SurrenderVote vote = surrenderVotes.get(team);
        if (vote == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now > vote.deadlineMillis()) {
            return null;   // 已过期，等同于没有表决（下次调用会重建）
        }
        // 与判负口径一致：只算仍在线、且还在本队的票
        int confirmed = 0;
        for (UUID uuid : vote.confirmed()) {
            Player voter = Bukkit.getPlayer(uuid);
            if (voter != null && voter.isOnline() && teams.get(uuid) == team) {
                confirmed++;
            }
        }
        int online = teamPlayers(team).size();
        int need = Math.max(1, (online + 1) / 2);
        long remainingSeconds = Math.max(0L, (vote.deadlineMillis() - now + 999L) / 1000L);
        return new SurrenderStatus(team, confirmed, need, remainingSeconds);
    }

    /** 投降表决进度快照：队伍、已确认票数、所需票数、剩余秒数。 */
    public record SurrenderStatus(TeamId team, int confirmed, int need, long remainingSeconds) {
    }

    /** 对局中补位：还需要人的队伍（严格人少的一方）；不需要补位返回 null。 */
    public TeamId reinforcementTeam() {
        if (phase != Phase.PLAYING) {
            return null;
        }
        if (isPve()) {
            return rosterSize(TeamId.RED) < maxPlayers() ? TeamId.RED : null;
        }
        int cap = teamSizeCap();
        int red = rosterSize(TeamId.RED);
        int blue = rosterSize(TeamId.BLUE);
        // 只补"严格人少"的一方：不掉线不补人，避免补位反而打破均势
        if (red < cap && red < blue) {
            return TeamId.RED;
        }
        if (blue < cap && blue < red) {
            return TeamId.BLUE;
        }
        return null;
    }

    private int rosterSize(TeamId team) {
        int count = 0;
        for (TeamId value : teams.values()) {
            if (value == team) {
                count++;
            }
        }
        return count;
    }

    /**
     * 对局中补位：把玩家直接编入缺人的队伍并传送进场——封存自带背包、发对局装备、
     * 记分板与出生增益一应俱全；结算后与其他参赛者同等返还。
     */
    public void joinAsReinforcement(Player player, TeamId team) {
        join(player, team);
        stashAndClearInventory(player);
        String charId = characterIdOf(player.getUniqueId());
        if (charId != null) {
            plugin.giveCharacterWeapons(player, charId);
        }
        ArenaDef.Point spawnPoint = arena.spawn(team);
        if (spawnPoint != null) {
            player.teleport(spawnPoint.toLocation(world()));
        }
        board.showTo(player);
        applySpawnBuff(player);
        broadcast("<gray>" + player.getName() + " 补位加入 " + team.colorTag() + team.display()
                + "<gray>！装备已发放，直接参战。");
        board.updateAll();
    }

    /**
     * 发起/确认本队投降（仅 PLAYING 阶段）：首次调用发起表决，之后每次调用算一票。
     * 30 秒内在线队友半数以上（含发起者）确认即 {@link #finish} 判对方胜。
     */
    public void surrender(Player player) {
        if (phase != Phase.PLAYING) {
            return;
        }
        TeamId team = teams.get(player.getUniqueId());
        if (team == null) {
            return;
        }
        long now = System.currentTimeMillis();
        SurrenderVote vote = surrenderVotes.get(team);
        if (vote == null || now > vote.deadlineMillis()) {
            vote = new SurrenderVote(new LinkedHashSet<>(), now + SURRENDER_WINDOW_MILLIS);
            surrenderVotes.put(team, vote);
        }
        vote.confirmed().add(player.getUniqueId());
        // 清除离线 / 已不在本队者的旧票：否则可能有人掉线后，靠其离线票通过表决
        vote.confirmed().removeIf(uuid -> {
            Player voter = Bukkit.getPlayer(uuid);
            return voter == null || !voter.isOnline() || teams.get(uuid) != team;
        });
        int online = teamPlayers(team).size();
        int need = Math.max(1, (online + 1) / 2);
        int confirmed = vote.confirmed().size();
        if (confirmed >= need) {
            surrenderVotes.remove(team);
            broadcast("<red>" + team.display() + " 投降表决通过（" + confirmed + "/" + need
                    + "），本场结束！");
            finish(team == TeamId.RED ? TeamId.BLUE : TeamId.RED);
            return;
        }
        net.kyori.adventure.text.Component voteMessage = MINI.deserialize(
                "<yellow>" + player.getName() + " 同意投降 <dark_gray>(" + confirmed + "/" + need
                        + ") <gray>—— <click:run_command:'/taketori surrender'>"
                        + "<hover:show_text:'<gray>同意结束本局，判对方获胜'>"
                        + "<red><bold>[同意投降]</bold></red></hover></click>"
                        + " <dark_gray>30 秒内半数以上在线队友同意即生效");
        for (Player teammate : teamPlayers(team)) {
            teammate.sendMessage(voteMessage);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + id() + "] 投降表决：" + team.key() + " " + confirmed + "/" + need);
        }
    }

    // ---------------------------------------------------------------- 本局击杀计数

    /** 记录一次玩家击杀（本局记分板与结算用；跨局统计由事件层写入）。 */
    public void addPlayerKill(Player killer) {
        if (killer == null) {
            return;
        }
        playerKills.merge(killer.getUniqueId(), 1, Integer::sum);
        playerNames.put(killer.getUniqueId(), killer.getName());
    }

    /** 记录一次月人击杀。 */
    public void addMinionKill(Player killer) {
        if (killer == null) {
            return;
        }
        minionKills.merge(killer.getUniqueId(), 1, Integer::sum);
        playerNames.put(killer.getUniqueId(), killer.getName());
    }

    public int playerKillsOf(UUID uuid) {
        return playerKills.getOrDefault(uuid, 0);
    }

    public int minionKillsOf(UUID uuid) {
        return minionKills.getOrDefault(uuid, 0);
    }

    /** 本局击杀总计（玩家 + 月人），记分板显示的就是它。 */
    public int totalKillsOf(UUID uuid) {
        return playerKillsOf(uuid) + minionKillsOf(uuid);
    }

    /** 本局全队击杀总计（PVE 记分板用）。 */
    public int teamTotalKills(TeamId team) {
        int total = 0;
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() == team) {
                total += totalKillsOf(entry.getKey());
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- 角色分配（同队唯一 + 权重裁决）

    /** 角色分配结果。 */
    public record RoleDecision(boolean granted, String displaced, String reason) {

        public boolean displacedSomeone() {
            return displaced != null;
        }
    }

    /** 玩家的隐性标签权重（未设置标签按 0）。 */
    public int weightOf(UUID uuid) {
        var profile = plugin.config().characters().profileOrNull(uuid);
        String tag = profile == null ? null : profile.tag();
        return plugin.tags().weightOf(tag);
    }

    /**
     * 请求把某个角色分配给玩家。作用域仅限本房间。
     *
     * <p>同一个队伍里不允许出现相同角色（不同队伍之间可以），冲突时按隐性标签权重裁决：
     * 权重高者拿到角色，权重低者被拒绝；权重相同则先到先得。玩家还没分队时直接放行，
     * 等分队或开局时再统一裁决。</p>
     */
    public RoleDecision requestRole(Player player, String characterId) {
        if (player == null || characterId == null || characterId.isBlank()) {
            return new RoleDecision(false, null, "参数无效。");
        }
        TeamId team = teams.get(player.getUniqueId());
        if (team == null) {
            return new RoleDecision(true, null, null);
        }
        UUID holder = holderOf(team, characterId, player.getUniqueId());
        if (holder == null) {
            return new RoleDecision(true, null, null);
        }
        int mine = weightOf(player.getUniqueId());
        int theirs = weightOf(holder);
        String holderName = nameOf(holder);
        if (mine > theirs) {
            // 顶替：解除对方的角色（他需要重新选一个）
            Player holderPlayer = Bukkit.getPlayer(holder);
            if (holderPlayer != null) {
                // 用 unbindCharacter 而不是 bindCharacter(null)：后者会连隐性标签一起删掉
                plugin.unbindCharacter(holderPlayer);
            } else {
                plugin.config().characters().unbind(holder);
                plugin.dataStore().setCharacterId(holder, null);
            }
            broadcast("<yellow>" + nameOf(player.getUniqueId()) + " 接管了 " + team.display()
                    + " 的 " + characterId + "：" + holderName + " 的隐性标签权重较低，角色已被解除。");
            return new RoleDecision(true, holderName, null);
        }
        return new RoleDecision(false, null, team.display() + " 已经有该角色（由 " + holderName
                + " 占用，权重 " + theirs + " ≥ 你的 " + mine + "）。");
    }

    /** 开局前的最终裁决：每队同一角色只保留权重最高的那个（其余解除角色待重选）。 */
    public void enforceUniqueRoles() {
        Map<String, UUID> taken = new HashMap<>();
        for (Map.Entry<UUID, TeamId> entry : new ArrayList<>(teams.entrySet())) {
            var profile = plugin.config().characters().profileOrNull(entry.getKey());
            if (profile == null || !profile.hasCharacter()) {
                continue;
            }
            String key = entry.getValue().key() + ":" + profile.characterId();
            UUID holder = taken.get(key);
            if (holder == null) {
                taken.put(key, entry.getKey());
                continue;
            }
            UUID keeper = weightOf(entry.getKey()) > weightOf(holder) ? entry.getKey() : holder;
            UUID loser = keeper.equals(holder) ? entry.getKey() : holder;
            taken.put(key, keeper);
            Player loserPlayer = Bukkit.getPlayer(loser);
            if (loserPlayer != null) {
                // 同上：解除角色不能顺手删掉隐性标签
                plugin.unbindCharacter(loserPlayer);
            } else {
                plugin.config().characters().unbind(loser);
                plugin.dataStore().setCharacterId(loser, null);
            }
            broadcast("<yellow>" + nameOf(loser) + " 的角色被解除："
                    + entry.getValue().display() + " 不允许出现两个相同角色（权重更高者优先）。");
        }
    }

    /** 该队里已经占用某角色的玩家（可排除自己）。 */
    private UUID holderOf(TeamId team, String characterId, UUID exclude) {
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() != team || entry.getKey().equals(exclude)) {
                continue;
            }
            var profile = plugin.config().characters().profileOrNull(entry.getKey());
            if (profile != null && characterId.equals(profile.characterId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 出生增益：开局与复活共用，持续秒数与效果都来自 config.yml 的 combat.spawn-buff。
     * 配置写 0 秒或清空 effects 即为关闭；效果名解析失败会跳过（不报错）。
     */
    public void applySpawnBuff(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        int seconds = plugin.config().spawnBuffSeconds();
        if (seconds <= 0) {
            return;
        }
        int ticks = seconds * 20;
        for (Map<?, ?> raw : plugin.config().spawnBuffEffects()) {
            Object typeName = raw.get("type");
            if (typeName == null) {
                continue;
            }
            var type = plugin.versions().potionEffect(String.valueOf(typeName));
            if (type == null) {
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[room] 出生增益的名字无法解析：" + typeName);
                }
                continue;
            }
            int amplifier = raw.get("amplifier") instanceof Number number ? number.intValue() : 0;
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks,
                    Math.max(0, amplifier), false, true, true));
        }
    }

    /** 死亡旁观的观察点：优先看中央刷怪区。 */
    public Location spectatorViewPoint() {
        CuboidRegion region = arena.minionRegion();
        return region == null ? null : region.center();
    }

    // ---------------------------------------------------------------- 计分

    private void resetScores() {
        teamScores.clear();
        for (TeamId team : TeamId.values()) {
            teamScores.put(team, 0);
        }
        playerScores.clear();
        playerKills.clear();
        minionKills.clear();
        lastScorer = null;
        lastScoreReason = "-";
        lastScoreAmount = 0;
        winner = null;
        surrenderVotes.clear();
    }

    public int teamScore(TeamId team) {
        return teamScores.getOrDefault(team, 0);
    }

    public int playerScore(UUID uuid) {
        return playerScores.getOrDefault(uuid, 0);
    }

    public UUID lastScorer() {
        return lastScorer;
    }

    public String lastScoreReason() {
        return lastScoreReason;
    }

    public int lastScoreAmount() {
        return lastScoreAmount;
    }

    /** 给玩家记分（同时计入其队伍）。只在 PLAYING 阶段生效。 */
    public void addScore(Player player, int amount, String reason) {
        if (player == null || amount == 0) {
            return;
        }
        // 观众不计分
        if (plugin.spectator().isSpectator(player)) {
            return;
        }
        TeamId team = teams.get(player.getUniqueId());
        if (team == null) {
            return;
        }
        addScore(team, player.getUniqueId(), player.getName(), amount, reason);
    }

    public void addScore(TeamId team, UUID scorer, String scorerName, int amount, String reason) {
        if (phase != Phase.PLAYING || team == null) {
            return;
        }
        teamScores.merge(team, amount, Integer::sum);
        if (scorer != null) {
            playerScores.merge(scorer, amount, Integer::sum);
            playerNames.put(scorer, scorerName == null ? "?" : scorerName);
            lastScorer = scorer;
            lastScoreReason = reason;
            lastScoreAmount = amount;
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format("[room %s] %s +%d（%s）→ 总分 %d:%d",
                    id(), team.display(), amount, reason, teamScore(TeamId.RED), teamScore(TeamId.BLUE)));
        }
        board.updateAll();
        if (teamScore(team) >= rules().scoreToWin()) {
            finish(team);
        }
    }

    /** 本局得分最高的玩家（用于结算播报）。 */
    public Map.Entry<UUID, Integer> topScorer() {
        return playerScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .orElse(null);
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        String name = playerNames.get(uuid);
        if (name != null) {
            return name;
        }
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 倒计时归零后开局：锁定在线等待者名单 → PVE 全红 / PVP 沿用玩家手动选择的队伍
     * （未选队伍不开局）
     * → 角色裁决 → 传送出生点 / 记分板 / 出生增益 → 建玻璃笼进 CAGED（无敌冻结）
     * → 到时笼解除进 PLAYING 并启动该房间刷怪/道具/占点(PVP)/据点(PVE) → 播报。
     *
     * <p>{@code waiting.cage-hold-seconds=0}（或出生点异常导致建笼 0 格）时跳过 CAGED 直接开战。</p>
     *
     * @param force true = 强制开局：PVP 允许某一队暂时无人
     * @return 失败原因；成功返回 null
     */
    public String beginMatch(boolean force) {
        if (phase == Phase.PLAYING || phase == Phase.CAGED || phase == Phase.ENDING) {
            // ENDING 也拦：结算延时还挂着 endTask，此时强行重开会被随后的 endCleanup 连新对局一起销毁
            return "房间已经在对局中。";
        }
        // 就绪判定按模式分流：战国地图不必配月人刷新区与基地（那是 PVP/PVE 的要求），
        // 反过来它有自己的七类点位。两套判定混用会出现「check 说齐全、开局却报缺月人刷新区」
        // 或者「点位缺一半照样开局、小局主线永远走不通」。
        if (isSengoku()) {
            String sengokuMissing = arena.sengoku()
                    .missingHint(plugin.config().towerRules().safeCount());
            if (!sengokuMissing.isEmpty()) {
                return "战国点位未就绪，还缺：" + sengokuMissing;
            }
        } else if (!arena.isReady()) {
            return "场地未就绪，还缺：" + arena.missingHint();
        }
        // 锁定名单：只取在线等待者；PVE 全红，PVP 沿用玩家在等待区手动选择的队伍
        List<Player> roster = waitingPlayers();
        if (roster.isEmpty()) {
            return "房间里一个人都没有。";
        }
        java.util.Set<UUID> rosterIds = new java.util.HashSet<>();
        for (Player player : roster) {
            rosterIds.add(player.getUniqueId());
        }
        // 以锁定名单为准：清掉名单外的队伍/名字残留（被管理指派过的离线者或旧等待者）
        teams.keySet().removeIf(id -> !rosterIds.contains(id));
        playerNames.keySet().removeIf(id -> !rosterIds.contains(id));

        if (isPve()) {
            // PVE：所有人统一红队（之前选成别的也并回红队）
            for (Player player : roster) {
                if (teams.get(player.getUniqueId()) != TeamId.RED) {
                    join(player, TeamId.RED);
                }
            }
        } else {
            // PVP：手动选边，开局不再自动分队。检查每个人是否都选了队伍
            List<String> missing = new ArrayList<>();
            for (Player player : roster) {
                if (teams.get(player.getUniqueId()) == null) {
                    missing.add(player.getName());
                }
            }
            if (!missing.isEmpty()) {
                if (!force) {
                    // 不开局：已选队伍保留，把锁定的名单退回等待区
                    waiting.clear();
                    for (Player player : roster) {
                        waiting.add(player.getUniqueId());
                    }
                    return "还有玩家未选择队伍：<white>" + String.join("、", missing)
                            + "</white> <gray>——选完队伍才能开局（只能去人数不占优的一边）。";
                }
                // force 兜底：未选者依次补进当前人少的队
                for (String name : missing) {
                    Player unassigned = null;
                    for (Player player : roster) {
                        if (player.getName().equals(name)) {
                            unassigned = player;
                        }
                    }
                    if (unassigned == null) {
                        continue;
                    }
                    TeamId fallback = teamPlayers(TeamId.RED).size() <= teamPlayers(TeamId.BLUE).size()
                            ? TeamId.RED : TeamId.BLUE;
                    if (teamPlayers(fallback).size() >= teamSizeCap()) {
                        fallback = fallback == TeamId.RED ? TeamId.BLUE : TeamId.RED;
                    }
                    join(unassigned, fallback);
                }
            }
        }
        waiting.clear();

        boolean red = !teamPlayers(TeamId.RED).isEmpty();
        boolean blue = !teamPlayers(TeamId.BLUE).isEmpty();
        if (!isPve() && (!red || !blue) && !force) {
            // 双方都缺人：退回等待区，已手动选择的队伍保留
            for (Player player : roster) {
                waiting.add(player.getUniqueId());
            }
            return "双方都需要至少一名玩家（人数不够也想开：/taketori match force）。";
        }

        // 重建整局成员缓存：最终名单 + 最终队伍（结算时掉线队友也按此判定胜方）
        rosterCache.clear();
        rosterCache.putAll(teams);
        // 角色快照必须取在 enforceUniqueRoles 之前：裁决会临时解绑失败者的角色，
        // 但「本局他到底用没用角色」应按开局那一刻算，否则裁决下来的人反而不被清。
        snapshotRoles();

        resetScores();
        endedAt = 0L;

        // 开局前最终裁决：每队同一角色只保留权重最高的那个（不同队伍之间不受影响）
        enforceUniqueRoles();

        // 开局时只清掉本房参赛者的旁观残留（例如带着别的房间的观众身份进了等待区），
        // 不能用全局 clearAll —— 那会把正在旁观其他并发房间的人也踢掉。
        for (UUID rosterId : teams.keySet()) {
            plugin.spectator().forgetQuietly(rosterId);
        }

        // 先传送 / 记分板 / 出生增益，同 tick 内立刻建笼：玩家不会先掉出笼外
        List<Location> spawns = new ArrayList<>();
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            // 开局清背包：先把玩家当前背包（含已发的角色武器 + 自带物品）整体备份，
            // 然后清空，再按绑定角色重新发放武器与护甲——保证对局里只有统一装备，
            // 结算时把备份原样还回去。
            stashAndClearInventory(player);
            String charId = characterIdOf(player.getUniqueId());
            if (charId != null) {
                plugin.giveCharacterWeapons(player, charId);
            }
            // 补发对局功能壳：上面的 stashAndClearInventory 会清掉等待期发的那一个，
            // 放在发武器之后是为了让壳稳定落在第 9 格（武器不会占用该格）。
            plugin.roomShell().give(player);
            ArenaDef.Point spawnPoint = arena.spawn(entry.getValue());
            if (spawnPoint != null) {
                Location spawn = spawnPoint.toLocation(world());
                player.teleport(spawn);
                spawns.add(spawn);
            }
            board.showTo(player);
            applySpawnBuff(player);
        }

        // 对局区域四周立屏障墙（从世界最低点到最高点）—— v1.2.0 动态房间制下每房独占世界，
        // 世界本身就是隔离边界，屏障墙停用（BarrierBuilder 保留，需要时恢复这一行即可）
        // barrier.build();

        // CAGED：出生点玻璃笼内无敌冻结 hold 秒，到时笼解除再正式开战；hold=0 或建笼失败则直接开战
        int hold = Math.max(0, plugin.config().waitingCageHoldSeconds());
        int cageBlocks = hold > 0 ? cage.build(spawns) : 0;
        if (hold > 0 && cageBlocks > 0) {
            phase = Phase.CAGED;
            for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    enterCageState(player, hold);
                }
            }
            broadcastMessage("room.cage-hold", "seconds", hold);
            cageTask = scheduleOnce(this::releaseCage, hold * 20L);
            return null;
        }
        cage.clear();
        startPlay(red, blue);
        return null;
    }

    /** 笼内状态：无敌 + 满额缓慢（围笼本身已封死位移，效果用于阻止笼内互打/乱动）。 */
    private void enterCageState(Player player, int holdSeconds) {
        player.setInvulnerable(true);
        var slowness = plugin.versions().potionEffect("SLOWNESS");
        if (slowness != null) {
            player.addPotionEffect(new PotionEffect(slowness, holdSeconds * 20 + 20, 255,
                    false, false, false));
        }
    }

    /**
     * 解除笼内状态：只对生存/冒险玩家关无敌，避免误改创造模式管理员。
     *
     * <p>对局中主动离局也要调用（见 {@code LobbyManager.leaveMatch}），否则玩家会带着
     * {@code setInvulnerable(true)} 回大厅。重复调用是安全的（幂等）。</p>
     */
    public void exitCageState(Player player) {
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            player.setInvulnerable(false);
        }
        var slowness = plugin.versions().potionEffect("SLOWNESS");
        if (slowness != null) {
            player.removePotionEffect(slowness);
        }
    }

    /** 玻璃笼到时：还原方块、解除冻结与无敌，正式开战。 */
    private void releaseCage() {
        cageTask = null;
        if (phase != Phase.CAGED) {
            return;
        }
        cage.restore();
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                exitCageState(player);
            }
        }
        startPlay(!teamPlayers(TeamId.RED).isEmpty(), !teamPlayers(TeamId.BLUE).isEmpty());
    }

    /**
     * 玩家是否正处于本房间的笼内冻结阶段（Task 7 等待区保护监听器判定用）。
     */
    public boolean isCaged(UUID uuid) {
        return phase == Phase.CAGED && teams.containsKey(uuid);
    }

    /**
     * 玩家是否处于"房间保护期"：WAITING/STARTING 的等待者或 CAGED 的参赛者。
     * 保护期内免一切伤害/不掉饥饿/禁破坏放置；事件监听器据此统一放行或取消。
     */
    public boolean isProtected(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return switch (phase) {
            case WAITING, STARTING -> waiting.contains(uuid);
            case CAGED -> teams.containsKey(uuid);
            default -> false;
        };
    }

    /** 某方块是否是本房间当前玻璃笼的一部分（CAGED 期间防破坏用）。 */
    public boolean isCageBlock(Location location) {
        return cage.isCageBlock(location);
    }

    /** 某方块是否是本房间当前区域屏障墙的一部分（对局期间防破坏用）。 */
    public boolean isBarrierBlock(Location location) {
        return barrier.isBarrierBlock(location);
    }

    /** 某位置是否落在本房间场地活动范围（基地/刷新区/道具点的外包矩形）内。 */
    public boolean isPlayArea(Location location) {
        CuboidRegion bounds = arena.playBounds();
        return bounds != null && bounds.contains(location);
    }

    /** 玩家当前绑定的角色 id（未绑定返回 null）。 */
    public String characterIdOf(UUID uuid) {
        var profile = plugin.config().characters().profileOrNull(uuid);
        return profile == null ? null : profile.characterId();
    }

    // ---------------------------------------------------------------- 背包备份与返还

    /**
     * 把玩家当前状态整体封存后清空——<b>进房即封存</b>，等待区人人平等。
     * 封存粒度对齐 BedWars2020 的 PlayerGoods：背包三件套 + 血量/饥饿/经验/药水/游戏模式/飞行。
     * 带防重入保护：已有快照时只清背包、绝不覆盖（否则真实物品会被对局装备顶掉），
     * 换房、开局等场景重复调用都是安全的。快照按 UUID 存，结算/退房时 {@link #restoreInventoryIfAny} 返还。
     */
    public void stashAndClearInventory(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        var inv = player.getInventory();
        if (!backups.containsKey(uuid)) {
            backups.put(uuid, new InventorySnapshot(
                    inv.getStorageContents().clone(),
                    inv.getArmorContents().clone(),
                    inv.getItemInOffHand().clone(),
                    player.getHealth(), player.getFoodLevel(),
                    player.getLevel(), player.getExp(),
                    player.getGameMode(), player.isFlying(),
                    new ArrayList<>(player.getActivePotionEffects())));
        }
        inv.clear();
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setFlying(false);
    }

    /**
     * 结算时把开局前备份的背包原样还给玩家（先清掉对局中捡的道具/装备，再写回备份）。
     * 玩家不在线则跳过（备份丢弃；重连时由 {@link #restoreInventoryIfAny} 兜底）。
     */
    private void restoreInventory(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        restoreInventoryIfAny(player);
    }

    /**
     * 取出并移除该玩家的状态快照（玩家对局中掉线时由 RoomManager 转存到全局暂存，
     * 等其重连后再返还，避免结算时因不在线而丢失原状态）。没有快照返回 null。
     */
    public InventorySnapshot extractBackup(UUID uuid) {
        return backups.remove(uuid);
    }

    /** 若该玩家在本房间有状态快照，清掉当前状态并原样写回（重连兜底/结算/退房通用）。 */
    public void restoreInventoryIfAny(Player player) {
        if (player == null) {
            return;
        }
        InventorySnapshot snapshot = backups.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        snapshot.restoreTo(player);
        // 快照里的药水是玩家"开局前自带"的：标记保留，回大厅时 clearTransient 不得洗掉
        if (snapshot.potions() != null && !snapshot.potions().isEmpty()) {
            plugin.lobby().preservePotions(player.getUniqueId(), snapshot.potions());
        }
    }

    /** 进入 PLAYING：计时起算、启动房间组件、开局播报。 */
    private void startPlay(boolean red, boolean blue) {
        phase = Phase.PLAYING;
        startedAt = System.currentTimeMillis();
        endedAt = 0L;

        // 战国模式：只跑自己的组件，刻意【不】启动月人、道具、基地占点与基地标记。
        // 那一套会给对局分（addScore）并在达到 score-to-win 时调 finish()，
        // 而战国模式的胜负只由击破器与超时决定——两套判定并存会让整场被提前结束；
        // 月人刷新还会与中地小兵叠加成两套怪。非战国房间走原来的分支，一行不变。
        if (isSengoku()) {
            sengoku().onRoundStart();
            towers().start();
            towerCapture().start();
            siege().start();
            jumpPads().start();
            midMinions().start();
            minions.stop();
            loot.stop();
            baseCapture.stop();
            baseMarker.stop();
            outpost.stop();
        } else {
            minions.start();
            loot.start();
            if (isPve()) {
                baseCapture.stop();   // PVE 没有敌方基地要拆
                baseMarker.stop();    // 也没有「双方基地」要标
                String outpostError = outpost.start(plugin.pveSettings());
                if (outpostError != null) {
                    plugin.getLogger().warning("[pve] 房间 " + id() + " 据点未能生成：" + outpostError);
                    broadcast("<yellow>据点未生成：<gray>" + outpostError);
                }
            } else {
                baseCapture.start();
                baseMarker.start();
                outpost.stop();
            }
        }
        board.updateAll();

        // 开局播报按模式分流：战国模式不看积分、也没有基地保护期，
        // 沿用 PVP 的"先到 X 分获胜"会直接把玩家引到错误的目标上。
        if (isSengoku()) {
            var sengokuRules = plugin.config().sengokuRules();
            broadcast("<gold><bold>战国 3v3 开始！</bold></gold> <gray>三局两胜，先赢 <white>"
                    + sengokuRules.winsNeeded() + "</white> 小局者胜；每小局 <white>"
                    + (sengokuRules.timeLimitSeconds() / 60) + "</white> 分钟。");
            broadcast("<gray>清掉箭楼守卫 → 敲响铜钟 → 站进占领区读条；"
                    + "占领后拿大将击破器攻陷敌方天守阁即可赢下本小局。");
            return;
        }
        broadcast("<gold><bold>对局开始！</bold></gold> <gray>先到 <white>" + rules().scoreToWin()
                + "</white> 分获胜，时限 <white>" + (rules().timeLimitSeconds() / 60) + "</white> 分钟。");
        if (isPve()) {
            broadcast("<aqua>PVE 模式</aqua> <gray>所有人同一队，击杀月人得分：<white>+"
                    + rules().minionKillScore() + "</white> / 只；<gray>玩家之间不会互相造成伤害。");
            broadcast("<gray>死亡 <white>" + rules().respawnDelaySeconds()
                    + "</white> 秒后在出生点复活；本模式没有基地占点。");
        } else {
            broadcast("<gray>击杀月人 <white>+" + rules().minionKillScore()
                    + "</white> / 击杀玩家 <white>+" + rules().playerKillScore()
                    + "</white> / 拆除基地 <white>+" + rules().baseCaptureScore() + "</white>");
            long delay = (long) rules().baseCaptureDelaySeconds();
            if (delay > 0L) {
                broadcast("<gray>基地保护期 <white>" + delay + "</white> 秒，期间无法占点。");
            }
            if (!red || !blue) {
                TeamId empty = red ? TeamId.BLUE : TeamId.RED;
                broadcast("<yellow>强制开局：<white>" + empty.display()
                        + "</white> 暂时无人，之后可以用 <white>/taketori team <玩家> "
                        + empty.key() + "</white> 补人。");
            }
        }
    }

    /**
     * 管理员手动结束：等待/倒计时阶段直接解散房间（房内玩家送回大厅）；
     * 笼内/战斗中进入 ENDING 结算。返回 false 表示房间已在结算中，无需重复操作。
     */
    public boolean stop(String reason) {
        if (phase == Phase.ENDING) {
            return false;
        }
        if (phase == Phase.WAITING || phase == Phase.STARTING) {
            // 先置 ENDING 再广播：重复调用（理论上房查不到，仍防御）直接走 false，不重复播报
            phase = Phase.ENDING;
            broadcast("<yellow>房间被管理员解散：" + reason);
            manager.destroyRoom(this, true);
            return true;
        }
        broadcast("<yellow>对局被结束：" + reason);
        settle(null, false);
        return true;
    }

    /**
     * 由 RoomManager 每秒调用：等待人数→倒计时→开局、CAGED/PLAYING 时限与空房收房、
     * ENDING 提前收房（观战提醒由 RoomManager 统一处理）。
     */
    public void tick() {
        // 每秒清扫过期战斗状态（月人消失后残留的 mark 等），防止状态表无限增长
        plugin.states().sweepExpired();
        switch (phase) {
            case WAITING -> {
                if (waitingCount() >= effectiveMinPlayers()) {
                    enterStarting();
                } else if (onlineParticipantCount() == 0) {
                    // 房主与等待者全部离线：宽限期后解散空房，回收世界
                    if (emptySinceMillis == 0L) {
                        emptySinceMillis = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - emptySinceMillis
                            >= plugin.config().roomEmptyDisposeSeconds() * 1000L) {
                        emptySinceMillis = 0L;
                        manager.destroyRoom(this, false);
                    }
                } else {
                    emptySinceMillis = 0L;
                }
            }
            case STARTING -> tickStarting();
            case CAGED -> {
                refreshSaturation();
                // 笼子阶段全员离线：不结算，直接还原收房
                if (onlineParticipantCount() == 0) {
                    abortEmpty();
                }
            }
            case PLAYING -> {
                if (onlineParticipantCount() == 0) {
                    abortEmpty();
                    return;
                }
                refreshSaturation();
                // 掉线缓冲（借鉴 BedWars2020 的 rejoin-time）：缺人宽限期内等补位，
                // 超时仍无人补位 → 缺人队判负，不再让剩余玩家打着注定失衡的垃圾局
                if (!isPve()) {
                    TeamId shortTeam = reinforcementTeam();
                    if (shortTeam == null) {
                        understaffedSinceMillis = 0L;
                    } else {
                        int grace = plugin.config().roomUnderstaffedGraceSeconds();
                        if (understaffedSinceMillis == 0L) {
                            understaffedSinceMillis = System.currentTimeMillis();
                            if (grace > 0) {
                                broadcast("<yellow>" + shortTeam.display() + " 缺人！<white>" + grace
                                        + "</white> 秒内无人补位将判负（大厅快速加入即可补位）。");
                            }
                        } else if (grace > 0
                                && System.currentTimeMillis() - understaffedSinceMillis >= grace * 1000L) {
                            understaffedSinceMillis = 0L;
                            broadcast("<red>" + shortTeam.display() + " 超时无人补位，判负！");
                            finish(shortTeam == TeamId.RED ? TeamId.BLUE : TeamId.RED);
                            return;
                        }
                    }
                } else {
                    understaffedSinceMillis = 0L;
                }
                // 通用总时限：只对 PVP / PVE 生效。
                // 战国模式的胜负由小局比分决定（每局 startPlay 会重置计时基准），
                // 而它从不 addScore、积分恒 0-0——一旦管理员把单局时限配到 ≥ 这个通用时限，
                // 整场会在中途被按 0-0 平局收掉。小局自己的超时判定在 SengokuSession 里。
                if (!isSengoku() && remainingMillis() <= 0L) {
                    int redScore = teamScore(TeamId.RED);
                    int blueScore = teamScore(TeamId.BLUE);
                    if (redScore == blueScore) {
                        finish(null);
                    } else {
                        finish(redScore > blueScore ? TeamId.RED : TeamId.BLUE);
                    }
                }
            }
            case ENDING -> {
                // 结算期间人走光了：不必再等 end-delay
                if (onlineParticipantCount() == 0) {
                    endCleanup();
                }
            }
        }
    }

    /** 生效的最低开局人数：配置值不得大于当前模式的满员人数。 */
    private int effectiveMinPlayers() {
        return Math.max(1, Math.min(plugin.config().waitingMinPlayers(), maxPlayers()));
    }

    /** WAITING → STARTING：按配置初始化常规倒计时；达标瞬间已满员则直接用短倒计时。 */
    private void enterStarting() {
        phase = Phase.STARTING;
        countdownStart = System.currentTimeMillis();
        long normalMillis = Math.max(1L, plugin.config().waitingCountdownSeconds()) * 1000L;
        countdownFullMillis = (long) plugin.config().waitingFullCountdownSeconds() * 1000L;
        long halfMillis = plugin.config().waitingHalfCountdownSeconds() * 1000L;
        boolean alreadyFull = waitingCount() >= maxPlayers();
        boolean alreadyHalf = !alreadyFull && halfMillis > 0L && waitingCount() > maxPlayers() / 2;
        countdownFull = alreadyFull;
        countdownHalf = alreadyHalf;
        countdownMillis = alreadyFull ? Math.min(normalMillis, countdownFullMillis)
                : (alreadyHalf ? Math.min(normalMillis, halfMillis) : normalMillis);
        long seconds = (countdownMillis + 999L) / 1000L;
        broadcastMessage(alreadyFull ? "room.countdown-full" : "room.countdown", "seconds", seconds);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + id() + "] 倒计时开始：" + seconds
                    + "s（" + (alreadyFull ? "满员短倒计时" : alreadyHalf ? "过半倒计时" : "常规倒计时") + "，"
                    + waitingCount() + "/" + maxPlayers() + "）");
        }
    }

    /** STARTING 每秒：满员缩短、掉人取消、归零开局。 */
    private void tickStarting() {
        int online = waitingCount();
        if (online < effectiveMinPlayers()) {
            phase = Phase.WAITING;
            countdownMillis = 0L;
            countdownFull = false;
            broadcastMessage("room.countdown-cancel");
            return;
        }
        long remaining = countdownStart + countdownMillis - System.currentTimeMillis();

        // 满员：一次性切到短倒计时（不大于当前剩余；full=0 时下一秒立即开局）
        if (!countdownFull && online >= maxPlayers()) {
            countdownFull = true;
            countdownHalf = true;
            countdownStart = System.currentTimeMillis();
            countdownMillis = Math.min(Math.max(0L, remaining), countdownFullMillis);
            remaining = countdownMillis;
            broadcastMessage("room.countdown-full", "seconds", (countdownMillis + 999L) / 1000L);
        }
        // 过半压缩（半挡）：人数超过半场（3v3 的第 4 人起）一次性压到半挡倒计时
        long halfMillis = plugin.config().waitingHalfCountdownSeconds() * 1000L;
        if (!countdownFull && !countdownHalf && halfMillis > 0L && online > maxPlayers() / 2) {
            countdownHalf = true;
            countdownStart = System.currentTimeMillis();
            countdownMillis = Math.min(Math.max(0L, remaining), halfMillis);
            remaining = countdownMillis;
            broadcastMessage("room.countdown", "seconds", (countdownMillis + 999L) / 1000L);
        }

        long seconds = Math.max(0L, (remaining + 999L) / 1000L);
        Component actionBar = plugin.config().messages().get(
                countdownFull ? "room.countdown-full" : "room.countdown", "seconds", seconds);
        for (Player player : waitingPlayers()) {
            player.sendActionBar(actionBar);
        }

        if (remaining <= 0L) {
            String error = beginMatch(false);
            if (error != null) {
                // 理论上倒计时归零时人数刚校验过不会失败；兜底回到等待并提示
                phase = Phase.WAITING;
                countdownMillis = 0L;
                countdownFull = false;
                countdownHalf = false;
                broadcast("<red>自动开局失败：<gray>" + error);
            }
        }
    }

    /** CAGED/PLAYING 全员离线：不写战绩、不播报胜负，直接停组件收房。 */
    private void abortEmpty() {
        if (cageTask != null) {
            cageTask.cancel();
            cageTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        cage.restore();
        stopComponents();
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + id() + "] 参与者全部离线，空房自动收房回 WAITING");
        }
        endCleanup();
    }

    /** 停掉房间全部对局组件（刷怪/据点/道具/占点）并清掉房间世界里的技能弹体与掉落物。 */
    private void stopComponents() {
        minions.stop();
        outpost.stop();
        loot.stop();
        baseCapture.stop();
        baseMarker.stop();
        clearSkillProjectiles();
        clearDroppedItems();
        clearSummons();
        // 战国：小局计时器、箭楼守卫与读条都归这里停（房间收尾 / 重开都会经过本方法）
        if (sengokuSession != null) {
            sengokuSession.stop();
        }
        if (sengokuTowers != null) {
            sengokuTowers.stop();
        }
        if (sengokuCapture != null) {
            sengokuCapture.stop();
        }
        if (sengokuSiege != null) {
            sengokuSiege.stop();
        }
        if (sengokuJumpPads != null) {
            sengokuJumpPads.stop();
        }
        if (sengokuMidMinions != null) {
            sengokuMidMinions.stop();
        }
    }

    /**
     * 清掉本房间世界里的召唤物（带 {@code summoned_owner} 标记的实体）。
     *
     * <p>世界卸载虽然也会带走它们，但玩家中途退场时房间还在，狗会继续自己找目标咬人；
     * 所以结算收尾与房间回收都要主动清一遍。</p>
     */
    private void clearSummons() {
        for (String worldName : roomWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer()
                        .get(com.taketori.kassen.paper.item.PDCKeys.summonedOwner(),
                                org.bukkit.persistence.PersistentDataType.STRING) != null) {
                    entity.remove();
                }
            }
        }
    }

    /**
     * 清掉本房间<b>场地区域内</b>的掉落物（结算重置 / reload / 禁用时，下一轮不得残留上局物品）。
     * 只按场地配置区域（基地/刷新区/道具点）判定，不按整个世界清空——同一世界并存多个场地时，
     * 世界一刀切会误删其他房间乃至大厅的掉落物；区域外的散落物交给原版自然消失。
     */
    private void clearDroppedItems() {
        for (String worldName : roomWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            for (Item drop : world.getEntitiesByClass(Item.class)) {
                if (arena.containsRegionLocation(drop.getLocation())) {
                    drop.remove();
                }
            }
        }
    }

    /**
     * 移除本房间<b>场地区域内</b>的技能弹体（带 proj_damage PDC 的投射物；普通箭自行消失，不动它）。
     * 同样限定场地区域，避免同世界多场地时删掉别的房间在飞弹体。
     */
    private void clearSkillProjectiles() {
        for (String worldName : roomWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            for (Projectile projectile : world.getEntitiesByClass(Projectile.class)) {
                if (projectile.getPersistentDataContainer()
                        .has(PDCKeys.projDamage(), PersistentDataType.DOUBLE)
                        && arena.containsRegionLocation(projectile.getLocation())) {
                    projectile.remove();
                }
            }
        }
    }

    /**
     * 一次性延时任务：SchedulerAdapter 只暴露自管句柄的周期任务，
     * 这里包成"首次执行先取消自己"的单次任务并返回句柄。
     */
    private BukkitTask scheduleOnce(Runnable runnable, long delayTicks) {
        long delay = Math.max(0L, delayTicks);
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            if (holder[0] != null) {
                holder[0].cancel();
            }
            runnable.run();
        }, delay, Math.max(1L, delay));
        return holder[0];
    }

    /** 把 messages.yml 文案按房间播报范围发出去（加入/离开等大厅层播报也走这里）。 */
    public void broadcastMessage(String key, Object... placeholders) {
        broadcastComponent(plugin.config().messages().get(key, placeholders));
    }

    /** 与 {@link #broadcast(String)} 同样世界范围，但发送已渲染组件。 */
    private void broadcastComponent(Component component) {
        String scope = plugin.config().broadcastScope();
        Set<String> worlds = roomWorlds();
        if ("all".equalsIgnoreCase(scope) || worlds.isEmpty()) {
            Bukkit.getServer().sendMessage(component);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (WorldScope.shouldReceive(scope, worlds, player.getWorld().getName())) {
                player.sendMessage(component);
            }
        }
    }

    public long remainingMillis() {
        // 计时从正式开战（PLAYING）起算：等待/倒计时/笼内冻结都不消耗对局时间
        if (phase != Phase.PLAYING && phase != Phase.ENDING) {
            return rules().timeLimitMillis();
        }
        long base = phase == Phase.PLAYING ? System.currentTimeMillis() : endedAt;
        return Math.max(0L, rules().timeLimitMillis() - (base - startedAt));
    }

    public String remainingText() {
        long seconds = remainingMillis() / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** STARTING 剩余整秒（房间列表倒计时显示用）；其他阶段返回 0。 */
    public int countdownSeconds() {
        if (phase != Phase.STARTING) {
            return 0;
        }
        long remaining = countdownStart + countdownMillis - System.currentTimeMillis();
        return (int) Math.max(0L, (remaining + 999L) / 1000L);
    }

    /** 开局后已经过的秒数（基地保护期判定用）。 */
    public long elapsedSeconds() {
        if (phase != Phase.PLAYING && phase != Phase.ENDING) {
            return 0L;
        }
        long base = phase == Phase.PLAYING ? System.currentTimeMillis() : endedAt;
        return Math.max(0L, (base - startedAt) / 1000L);
    }

    /** 基地是否已过保护期、可以开始占点。 */
    public boolean isBaseCaptureOpen() {
        return phase == Phase.PLAYING && elapsedSeconds() >= (long) rules().baseCaptureDelaySeconds();
    }

    /** 保护期剩余秒数（0 = 已开放占点）。 */
    public long baseCaptureDelayRemaining() {
        if (phase != Phase.PLAYING) {
            return 0L;
        }
        return Math.max(0L, (long) rules().baseCaptureDelaySeconds() - elapsedSeconds());
    }

    // ---------------------------------------------------------------- 结算

    /** 达到目标分 / 时间到。 */
    public void finish(TeamId winnerTeam) {
        settle(winnerTeam, true);
    }

    /**
     * 战国模式：小局之间重开（队伍保留，重新进笼开局）。
     *
     * <p>{@link #beginMatch(boolean)} 依赖"等待名单"，而小局重开时玩家已经在 {@code teams} 里，
     * 所以先把参赛者填回 waiting 再复用它——这样传送、建笼、开局播报的既有流程一行都不用改。
     * 离线者会被 {@code beginMatch} 的名单锁定机制自动剔除，这里不必特殊处理。</p>
     *
     * @return 失败原因；成功返回 null
     */
    public String restartRound() {
        // 关键：小局结束时 phase 还停在 PLAYING，而 beginMatch 的第一道门就是
        // 「房间已经在对局中」。不退这一步，小局重开会【必然】失败——整场会在
        // 第 1 小局结束时被当成平局收尾，三局两胜与逐局重置全部不可达。
        // 同一个 tick 内马上会重新开局，不存在"外部看到空房"的窗口。
        phase = Phase.WAITING;
        waiting.clear();
        for (UUID id : teams.keySet()) {
            Player player = Bukkit.getPlayer(id);
            // 阵亡观战中的队员也要算进来：他们仍是本局参赛者，小局重开时应当一起回去。
            // 漏掉的话 beginMatch 的名单锁定会把他们在 teams 里删掉（下一局少人 + 战绩丢失）。
            if (player != null && player.isOnline()) {
                waiting.add(id);
            }
        }
        if (waiting.isEmpty()) {
            // 失败要把 phase 交还给对局状态机：settle 的第一道守卫会吞掉 WAITING，
            // 不还原的话调用方的 room.finish() 直接空转，房间卡死在 WAITING。
            phase = Phase.PLAYING;
            return "没有在线玩家可以继续";
        }
        String failure = beginMatch(true);
        if (failure != null) {
            phase = Phase.PLAYING;
        }
        return failure;
    }

    /**
     * 战国模式：清掉上一小局留下的战场状态（小兵、召唤物、掉落物、技能弹体）。
     *
     * <p>队伍、比分与跨局战绩<b>不动</b>——它们属于整场而不是某一小局，
     * 这也是 {@code SengokuScore} 单独成类的原因。</p>
     */
    public void resetSengokuBattlefield() {
        // stopComponents 里已经清过召唤物 / 掉落物 / 技能弹体，这里不再重复调用
        // （那三个都是全实体扫描，重复两轮纯属浪费）
        stopComponents();
        if (sengokuSiege != null) {
            sengokuSiege.removeFromInventories();
        }
        if (sengokuEnergy != null) {
            sengokuEnergy.clearAll();
        }
        clearParticipantCombatState();
    }

    /**
     * 清掉参赛者的临时战斗状态与药水效果（需求第 33 条要求重置"临时状态"）。
     *
     * <p>不清的话，上一局被挂的减速 / 易伤标记 / 破甲 / 防御窗口 / 连击层数会直接带进
     * 下一局——玩家在新一局开局就莫名其妙地"被标着""被破着甲"。</p>
     *
     * <p>只清参赛者，不碰 {@code CombatStates.clearAll()}（那是全局的，会误伤其它房间）。</p>
     */
    private void clearParticipantCombatState() {
        for (TeamId team : TeamId.values()) {
            for (Player player : teamPlayers(team)) {
                plugin.states().clear(player.getUniqueId());
                for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                    player.removePotionEffect(effect.getType());
                }
                player.setFireTicks(0);
                player.setFallDistance(0.0F);
            }
        }
    }

    /**
     * 结算收尾：停组件、播报胜负/得分王、写跨局战绩，进入 ENDING；
     * 等待 {@code waiting.end-delay-seconds} 后由 {@link #endCleanup()} 回大厅并重置为 WAITING。
     * 笼内被结束时先取消解除任务、还原笼子。
     *
     * @param recordStats 管理员手动停止时不写战绩
     */
    private void settle(TeamId winnerTeam, boolean recordStats) {
        if (phase == Phase.ENDING || phase == Phase.WAITING) {
            return;
        }
        // 笼内被结束：取消解除任务、还原玻璃笼、解除玩家无敌/冻结
        if (cageTask != null) {
            cageTask.cancel();
            cageTask = null;
        }
        boolean fromCage = phase == Phase.CAGED;
        if (fromCage) {
            cage.restore();
        }
        phase = Phase.ENDING;
        endedAt = System.currentTimeMillis();
        winner = winnerTeam;
        stopComponents();
        if (fromCage) {
            for (UUID uuid : new ArrayList<>(teams.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    exitCageState(player);
                }
            }
        }
        board.updateAll();

        Map.Entry<UUID, Integer> top = topScorer();
        if (isPve()) {
            boolean cleared = teamScore(TeamId.RED) >= rules().scoreToWin();
            broadcast("<dark_gray>========================================");
            broadcast(cleared
                    ? "<green><bold>挑战成功！</bold></green> <gray>总分 <white>" + teamScore(TeamId.RED)
                    + "</white> / " + rules().scoreToWin()
                    : "<yellow><bold>时间到</bold></yellow> <gray>未能达成目标：总分 <white>"
                    + teamScore(TeamId.RED) + "</white> / " + rules().scoreToWin());
            broadcast("<gray>参与成员：<white>" + memberNames(TeamId.RED));
            broadcast("<gray>最后得分：<white>" + nameOf(lastScorer) + " <gray>(+"
                    + lastScoreAmount + " " + lastScoreReason + ")");
            broadcast(top == null
                    ? "<gray>本局得分王：<white>无"
                    : "<gray>本局得分王：<white>" + nameOf(top.getKey())
                    + " <gray>(<white>" + top.getValue() + " 分<gray>)");
            broadcast("<dark_gray>========================================");
        } else {
            String resultLine = winnerTeam == null
                    ? "<yellow>平局！"
                    : winnerTeam.colorTag() + "<bold>" + winnerTeam.display() + "</bold></color> <green>获胜！";
            broadcast("<dark_gray>========================================");
            broadcast(resultLine + " <gray>比分 " + TeamId.RED.colorTag() + teamScore(TeamId.RED)
                    + " <gray>: " + TeamId.BLUE.colorTag() + teamScore(TeamId.BLUE));
            broadcast("<gray>获胜队伍成员：<white>" + memberNames(winnerTeam));
            broadcast("<gray>最后得分：<white>" + nameOf(lastScorer) + " <gray>(+"
                    + lastScoreAmount + " " + lastScoreReason + ")");
            broadcast(top == null
                    ? "<gray>本局得分王：<white>无"
                    : "<gray>本局得分王：<white>" + nameOf(top.getKey())
                    + " <gray>(<white>" + top.getValue() + " 分<gray>)");
            broadcast("<dark_gray>========================================");
        }

        if (recordStats) {
            plugin.stats().recordMatch(winnerTeam, teamScores, playerScores, playerNames);
            if (winnerTeam != null) {
                // 结算名单走整局成员缓存（rosterCache）：掉线的队友同样记为胜方
                List<String> winners = new ArrayList<>();
                for (Map.Entry<UUID, TeamId> entry : rosterCache.entrySet()) {
                    if (entry.getValue() == winnerTeam) {
                        winners.add(nameOf(entry.getKey()));
                    }
                }
                plugin.stats().recordWin(winners);
            }
        }

        // ENDING：停留 end-delay 秒再统一回大厅/重置房间；期间人走光则由 tick 提前收房
        int delaySeconds = Math.max(0, plugin.config().waitingEndDelaySeconds());
        broadcastMessage("room.end-return");
        endTask = scheduleOnce(this::endCleanup, delaySeconds * 20L);
    }

    /**
     * 结算后的收尾（由 end-delay 延时任务 / 空房判定触发，幂等）：取消残留定时任务、
     * 还原笼子、停组件、清旁观、在线者回大厅、隐藏记分板，然后解除玩家→房间映射，
     * 清空名单/比分回到 WAITING，可立即匹配下一轮。
     */
    /**
     * 对局期间给参赛者续 Saturation（CAGED/PLAYING，由每秒的 {@link #tick()} 驱动，不新建调度任务）。
     *
     * <p>用 2 秒短时长覆盖式刷新：玩家离开对局后效果最迟 2 秒自然过期，不依赖清理逻辑；
     * {@code combat.match-saturation} 可整体关闭。效果由插件施加的记录进 {@link #pluginSaturation}，
     * 收尾时只清自己记过的。</p>
     */
    private void refreshSaturation() {
        if (!plugin.config().matchSaturation()) {
            return;
        }
        var saturation = plugin.versions().potionEffect("SATURATION");
        if (saturation == null) {
            return;
        }
        for (UUID uuid : teams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            // addPotionEffect 同类型同时长覆盖刷新，天然幂等；粒子/图标关闭避免刷屏
            player.addPotionEffect(new PotionEffect(saturation, 40, 0, false, false, false));
            pluginSaturation.add(uuid);
        }
    }

    /** 收尾清除：只移除本房插件标记过的 Saturation，其他插件或玩家自带的效果不动。 */
    private void clearPluginSaturation() {
        if (pluginSaturation.isEmpty()) {
            return;
        }
        var saturation = plugin.versions().potionEffect("SATURATION");
        for (UUID uuid : pluginSaturation) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && saturation != null) {
                player.removePotionEffect(saturation);
            }
        }
        pluginSaturation.clear();
    }

    private void endCleanup() {
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        if (cageTask != null) {
            cageTask.cancel();
            cageTask = null;
        }
        cage.restore();
        barrier.restore();
        stopComponents();
        clearPluginSaturation();

        List<UUID> members = new ArrayList<>(teams.keySet());
        // 对局结束清掉参赛者的角色选择：取「整局成员缓存 ∪ 当前队伍」而不是只取 teams——
        // 掉线/中途退出的人已经从 teams 里移除了，但角色仍留在数据层，不清就会在他们
        // 下次进服时被 restoreOnlinePlayers 原样恢复出来。
        Set<UUID> roleHolders = new LinkedHashSet<>(rosterCache.keySet());
        roleHolders.addAll(teams.keySet());
        clearRoles(new ArrayList<>(roleHolders));
        // 只清旁观本房的观众（退回大厅）；其他房间的观众不受影响
        plugin.spectator().clearAudienceOfRoom(this);
        boolean returnToLobby = plugin.config().lobbyReturnAfterMatch();
        for (UUID uuid : members) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            // 阵亡等待复活的参赛者：静默清掉死亡旁观登记，下面统一送回大厅
            plugin.spectator().forgetQuietly(uuid);
            exitCageState(player);
            // 结算返还开局前备份的背包（先还再送大厅，避免回大厅时还穿着对局装备）
            restoreInventory(uuid);
            if (returnToLobby) {
                plugin.lobby().sendToLobby(player);
            }
        }
        for (UUID uuid : members) {
            board.hide(Bukkit.getPlayer(uuid));
        }

        // 人已回大厅：解除玩家→本房间映射，清空名单与比分，然后销毁房间并回收世界
        // （月宫制：房间是一次性的——结算完成、玩家回到大厅即整场删除）
        manager.detachRoom(this);
        waiting.clear();
        clearTeams();
        resetScores();
        countdownMillis = 0L;
        countdownFull = false;
        countdownHalf = false;
        phase = Phase.WAITING;
        board.updateAll();
        if (plugin.config().lobbyReturnAfterMatch()) {
            // 月宫制：结算完成、玩家回到大厅即整场删除并回收世界
            manager.destroyRoom(this, false);
        }
        // return-after-match=false：玩家留在原地，房间退回等待状态、世界保留——
        // 此时仍直接销毁的话，世界里有在线玩家，unloadWorld 必然失败，
        // 世界与文件夹会滞留到下次启动。空房超时（room-empty-dispose-seconds）
        // 或管理员删除房间时再走正常回收。
    }

    /**
     * 对局结束清掉参赛者的角色选择（含已掉线者），下一局由玩家重新选。
     *
     * <p>只解绑角色、<b>不动隐性标签</b>：这里走 {@code setCharacterId(uuid, null)}（语义是
     * 「只解除角色，保留标签」，见 {@link com.taketori.kassen.data.YamlPlayerDataStore}），
     * 而不是 {@code dataStore.remove(uuid)}——后者会把标签一起删掉。</p>
     */
    private void clearRoles(List<UUID> members) {
        for (UUID uuid : members) {
            if (roleChangedSinceStart(uuid)) {
                continue;   // 他离队后已在别处重新选了角色：那是新状态，不动
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                plugin.unbindCharacter(player);   // 角色 + 武器 + 属性，且保留隐性标签
                continue;
            }
            // 离线者：只能清数据层。属性挂在在线 Player 上，他下次上线读不到角色就不会再绑定；
            // 武器由背包返还（在线）或重连返还（离线）兜底。
            plugin.config().characters().unbind(uuid);
            plugin.dataStore().setCharacterId(uuid, null);
        }
        roleSnapshot.clear();
        // 立刻落盘：否则服务器崩溃/被强杀后 players.yml 里仍是旧角色，下次启动又被恢复出来
        plugin.dataStore().saveAll();
    }

    /** 开局时记录每个参赛者的角色，供结算判断「这个角色是不是本局的」。 */
    private void snapshotRoles() {
        roleSnapshot.clear();
        for (UUID uuid : rosterCache.keySet()) {
            roleSnapshot.put(uuid, plugin.dataStore().characterIdOf(uuid));
        }
    }

    /**
     * 该玩家的角色是否已与开局时不同。中途离队（{@code /taketori leave}）后可以立刻加入别的
     * 房间并重新选角色——那种情况下本局结算不该动他：清角色是小事，连武器和属性一起清掉
     * 会把他在新对局里正在用的东西抹掉。
     *
     * <p>不在快照里的人（中途补位加入等）按「需要清」处理。</p>
     */
    private boolean roleChangedSinceStart(UUID uuid) {
        if (!roleSnapshot.containsKey(uuid)) {
            return false;
        }
        String snapshot = roleSnapshot.get(uuid);
        String current = plugin.dataStore().characterIdOf(uuid);
        return snapshot == null ? current != null : !snapshot.equals(current);
    }

    /**
     * 注册表重建 / 插件卸载时调用：取消延时任务、还原玻璃笼、停掉全部对局组件与记分板。
     * 幂等；不重置 phase（该房间对象之后不再被使用）。
     */
    public void shutdown() {
        if (cageTask != null) {
            cageTask.cancel();
            cageTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        cage.restore();
        barrier.restore();
        stopComponents();
        // 插件卸载/重载时：在线参与者（含等待区玩家）拿回自己的状态；离线者的快照转全局暂存等重连返还
        Set<UUID> holders = new LinkedHashSet<>(teams.keySet());
        holders.addAll(backups.keySet());
        for (UUID uuid : holders) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                restoreInventoryIfAny(player);
            } else {
                InventorySnapshot backup = extractBackup(uuid);
                if (backup != null) {
                    manager.stashOfflineBackup(uuid, backup);
                }
            }
        }
        board.clearAll();
    }

    private String memberNames(TeamId team) {
        if (team == null) {
            return "无";
        }
        List<Player> players = teamPlayers(team);
        if (players.isEmpty()) {
            return "（已离线）";
        }
        StringBuilder builder = new StringBuilder();
        for (Player player : players) {
            if (!builder.isEmpty()) {
                builder.append("、");
            }
            builder.append(player.getName());
        }
        return builder.toString();
    }

    public TeamId winner() {
        return winner;
    }

    // ---------------------------------------------------------------- 房间组件

    public MinionSpawner minions() {
        return minions;
    }

    public BaseCaptureManager baseCapture() {
        return baseCapture;
    }

    public OutpostManager outpost() {
        return outpost;
    }

    public LootSpawner loot() {
        return loot;
    }

    public MatchScoreboard scoreboard() {
        return board;
    }

    // ---------------------------------------------------------------- 播报

    /**
     * 本房间涉及的世界集合（出生点 / 基地 / 月人刷新区 / PVE 据点 / 等待点）。
     * 用途：把房间播报限制在"场地世界"内。
     */
    public Set<String> roomWorlds() {
        Set<String> worlds = new LinkedHashSet<>();
        for (TeamId team : TeamId.values()) {
            ArenaDef.Point spawn = arena.spawn(team);
            if (spawn != null) {
                worlds.add(spawn.worldName());
            }
            for (CuboidRegion region : arena.bases(team).values()) {
                if (region != null && region.worldName() != null) {
                    worlds.add(region.worldName());
                }
            }
        }
        for (CuboidRegion region : arena.minionRegions().values()) {
            if (region != null && region.worldName() != null) {
                worlds.add(region.worldName());
            }
        }
        ArenaDef.Point outpostSpot = arena.outpost();
        if (outpostSpot != null) {
            worlds.add(outpostSpot.worldName());
        }
        if (arena.waitRegion() != null) {
            worlds.add(arena.waitRegion().worldName());
        } else {
            ArenaDef.Point wait = arena.waitSpawn();
            if (wait != null) {
                worlds.add(wait.worldName());
            }
        }
        return worlds;
    }

    /**
     * 房间播报：默认只发给场地世界里的玩家（worlds.broadcast-scope: world）；
     * 配成 all 时全服广播。尚未记录到任何场地世界时按全服发送，避免消息静默丢失。
     */
    public void broadcast(String miniMessage) {
        String scope = plugin.config().broadcastScope();
        Component component = MINI.deserialize(miniMessage);
        Set<String> worlds = roomWorlds();
        if ("all".equalsIgnoreCase(scope) || worlds.isEmpty()) {
            Bukkit.getServer().sendMessage(component);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (WorldScope.shouldReceive(scope, worlds, player.getWorld().getName())) {
                player.sendMessage(component);
            }
        }
    }
}
