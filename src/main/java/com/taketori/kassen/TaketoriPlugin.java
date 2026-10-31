package com.taketori.kassen;

import com.taketori.kassen.config.ConfigManager;
import com.taketori.kassen.config.ConfigValidator;
import com.taketori.kassen.core.character.TagManager;
import com.taketori.kassen.core.cooldown.CooldownManager;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.worlds.WorldScope;
import com.taketori.kassen.data.PlayerDataStore;
import com.taketori.kassen.data.YamlPlayerDataStore;
import com.taketori.kassen.paper.command.AdminMenu;
import com.taketori.kassen.paper.command.TagMenu;
import com.taketori.kassen.paper.effect.Fx;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.item.MenuClock;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.listener.CarrierGuardListener;
import com.taketori.kassen.paper.listener.CombatListener;
import com.taketori.kassen.paper.listener.InputListener;
import com.taketori.kassen.paper.listener.MenuClockListener;
import com.taketori.kassen.paper.listener.ProjectileListener;
import com.taketori.kassen.paper.listener.RoomShellListener;
import com.taketori.kassen.paper.lobby.CharacterMenu;
import com.taketori.kassen.paper.lobby.LobbyListener;
import com.taketori.kassen.paper.lobby.LobbyManager;
import com.taketori.kassen.paper.lobby.PlayerMenu;
import com.taketori.kassen.paper.listener.PlayerListener;
import com.taketori.kassen.paper.listener.SengokuListener;
import com.taketori.kassen.paper.listener.SetupWandListener;
import com.taketori.kassen.paper.listener.WeaponEditorListener;
import com.taketori.kassen.paper.editor.WeaponEditor;
import com.taketori.kassen.paper.setup.SetupWand;
import com.taketori.kassen.paper.setup.SetupWandService;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.MatchListener;
import com.taketori.kassen.paper.match.MinionTypes;
import com.taketori.kassen.paper.match.room.RoomManager;
import com.taketori.kassen.paper.match.SpectatorManager;
import com.taketori.kassen.paper.match.StatsMenu;
import com.taketori.kassen.paper.match.StatsTracker;
import com.taketori.kassen.paper.scheduler.SchedulerAdapter;
import com.taketori.kassen.paper.shop.ShopMenu;
import com.taketori.kassen.paper.shop.ShopNpcManager;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillRegistry;
import com.taketori.kassen.paper.skill.impl.ArmorBreakSkill;
import com.taketori.kassen.paper.skill.impl.BlinkSkill;
import com.taketori.kassen.paper.skill.impl.EquipSwitchSkill;
import com.taketori.kassen.paper.skill.impl.DeployZoneSkill;
import com.taketori.kassen.paper.skill.impl.EchoConsumeSkill;
import com.taketori.kassen.paper.skill.impl.GrappleSkill;
import com.taketori.kassen.paper.skill.impl.MarkApplySkill;
import com.taketori.kassen.paper.skill.impl.MeleeSmashSkill;
import com.taketori.kassen.paper.skill.impl.MirrorBurstSkill;
import com.taketori.kassen.paper.skill.impl.MirrorSkill;
import com.taketori.kassen.paper.skill.impl.ModeSwitchSkill;
import com.taketori.kassen.paper.skill.impl.ProjectileSkill;
import com.taketori.kassen.paper.skill.impl.PullSkill;
import com.taketori.kassen.paper.skill.impl.ReflectSkill;
import com.taketori.kassen.paper.skill.impl.RocketJumpSkill;
import com.taketori.kassen.paper.skill.impl.ShockwaveSkill;
import com.taketori.kassen.paper.skill.impl.SelfBoostSkill;
import com.taketori.kassen.paper.skill.impl.ShieldGuardSkill;
import com.taketori.kassen.paper.skill.impl.SpecialShotToggleSkill;
import com.taketori.kassen.paper.skill.impl.SummonAllySkill;
import com.taketori.kassen.paper.state.CombatStates;
import com.taketori.kassen.version.DefaultVersionAdapter;
import com.taketori.kassen.version.VersionAdapter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 插件主类：负责装配与卸载，本身不含玩法逻辑。
 *
 * <p>分层（计划 §5.2）：core 为纯逻辑，paper 为 Bukkit 适配，version 收拢版本差异，
 * data 管持久化。主类只做 wire-up，方便单测与将来替换实现。</p>
 */
public final class TaketoriPlugin extends JavaPlugin {

    private ConfigManager config;
    private VersionAdapter versions;
    private ItemFactory items;
    private Fx fx;
    /** 伤害数字（动作栏聚合汇报，A1 手感项）。 */
    private com.taketori.kassen.paper.effect.DamageNumbers damageNumbers;
    private SchedulerAdapter scheduler;
    private CombatStates states;
    private SkillRegistry registry;
    private CooldownManager cooldowns;
    private SkillManager skills;
    private ProjectileSkill projectileSkill;
    private PlayerDataStore dataStore;

    // ---- 玩法层（3v3 积分赛，多房间）----
    private ArenaManager arena;
    /** 多房间注册表（BedWars 式匹配）；玩家→房间归属的唯一权威。 */
    private RoomManager rooms;
    private MenuClock menuClock;
    /** 对局功能壳与其 GUI（懒加载：构造时无需依赖 config 已就绪）。 */
    private com.taketori.kassen.paper.item.RoomShell roomShell;
    private com.taketori.kassen.paper.match.room.RoomShellMenu roomShellMenu;
    private InputListener input;
    /** 月人种类与精英规格（从 config.yml 读取）。 */
    private MinionTypes minionTypes;
    private StatsTracker stats;
    private SpectatorManager spectator;
    private LobbyManager lobby;
    private CharacterMenu characterMenu;
    /** 玩家入口菜单（匹配 / 队伍选择 / 角色选择 / 排行榜）。 */
    private PlayerMenu playerMenu;
    /** 房间列表 GUI（等待房加入 / 游戏房旁观）。 */
    private com.taketori.kassen.paper.lobby.RoomListMenu roomListMenu;
    /** 创建房间配置 GUI（选模板 + 选模式）。 */
    private com.taketori.kassen.paper.lobby.RoomCreateMenu roomCreateMenu;
    /** 总计排行榜（跨局累计）。 */
    private StatsMenu statsMenu;
    /** 管理员菜单（把常用管理指令映射成按钮）。 */
    private AdminMenu adminMenu;
    private com.taketori.kassen.paper.command.SengokuMenu sengokuMenu;
    /** 隐性标签设置界面（管理员）。 */
    private TagMenu tagMenu;
    /** 隐性标签与权重（config.yml 的 tags 段）。 */
    private final TagManager tagManager = new TagManager();

    /** 管理用具：选区锄（划场地的快捷方式）。 */
    private SetupWandService setupWand;
    /** 管理用具：武器数据编辑 GUI。 */
    private WeaponEditor editor;
    /** 派对（组队）：整队进同一房间、开局整组同队。 */
    private com.taketori.kassen.paper.party.PartyManager party;
    /** 断线重连：对局中掉线者在时限内重连回原房原队。 */
    private com.taketori.kassen.paper.match.RejoinManager rejoin;
    /** 划场地一条龙会话（arena setup 单入口）。 */
    private com.taketori.kassen.paper.setup.ArenaSetupSession arenaSetup;
    /** 派对图形界面（成员头颅 / 邀请 / 踢人 / 解散）。 */
    private com.taketori.kassen.paper.party.PartyMenu partyMenu;
    /** 队伍商店商人 NPC（大厅常驻实体，跨局存在）。 */
    private ShopNpcManager shopNpc;
    /** 队伍商店界面（右键商人打开，购买由 GameRoom 裁决）。 */
    private ShopMenu shopMenu;

    /** 插件自身造成的伤害标记：防止与近战改写逻辑互相递归。 */
    private final Set<UUID> internalDamage = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        PDCKeys.init(this);
        versions = new DefaultVersionAdapter();

        config = new ConfigManager(this);
        try {
            config.load();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "配置加载失败，插件将禁用：" + throwable.getMessage(), throwable);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        items = new ItemFactory(this, versions);
        loadTags();
        fx = new Fx(config, versions);
        damageNumbers = new com.taketori.kassen.paper.effect.DamageNumbers(this);
        scheduler = new SchedulerAdapter(this);
        states = new CombatStates();
        cooldowns = new CooldownManager();

        registry = new SkillRegistry();
        registerSkills();
        skills = new SkillManager(this, registry, cooldowns, items, fx);

        dataStore = new YamlPlayerDataStore(this);
        dataStore.loadAll();
        clearCharacterBindings();
        restoreOnlinePlayers();

        // ---- 玩法层：场地、多房间注册表（每房自有记分板/小怪/据点/道具组件）、跨局统计 ----
        arena = new ArenaManager(this);
        arena.load();
        stats = new StatsTracker(this);
        stats.load();
        // 多房间：为每个启用场地各建一个等待房间
        rooms = new RoomManager(this);
        rooms.build();
        minionTypes = new MinionTypes(this);
        minionTypes.load();
        menuClock = new MenuClock(this);
        // ---- 大厅与旁观 ----
        spectator = new SpectatorManager(this);
        lobby = new LobbyManager(this);
        lobby.load();
        lobby.startStatusTask();   // 实时状态牌：每秒刷新一次文本
        characterMenu = new CharacterMenu(this);
        // ---- 玩家菜单与总计排行榜（原有的告示牌与指令全部保留）----
        playerMenu = new PlayerMenu(this);
        roomListMenu = new com.taketori.kassen.paper.lobby.RoomListMenu(this);
        roomCreateMenu = new com.taketori.kassen.paper.lobby.RoomCreateMenu(this);
        statsMenu = new StatsMenu(this);
        adminMenu = new AdminMenu(this);
        sengokuMenu = new com.taketori.kassen.paper.command.SengokuMenu(this);
        tagMenu = new TagMenu(this);
        // ---- 管理用具：选区锄（左键/右键点方块划区域，带边框可视化）----
        setupWand = new SetupWandService(this, new SetupWand(this));
        setupWand.start();
        getServer().getPluginManager().registerEvents(new SetupWandListener(this), this);
        // ---- 派对 / 断线重连 / 划场地一条龙 ----
        party = new com.taketori.kassen.paper.party.PartyManager(this);
        rejoin = new com.taketori.kassen.paper.match.RejoinManager(this);
        arenaSetup = new com.taketori.kassen.paper.setup.ArenaSetupSession(this);
        partyMenu = new com.taketori.kassen.paper.party.PartyMenu(this);
        getServer().getPluginManager().registerEvents(partyMenu, this);
        // ---- 队伍商店：商人 NPC + 购买界面（界面只读房间状态，购买一律由 GameRoom 裁决）----
        shopNpc = new ShopNpcManager(this);
        shopMenu = new ShopMenu(this);
        getServer().getPluginManager().registerEvents(shopMenu, this);
        shopNpc.start();
        // ---- 管理用具：武器数据编辑 GUI（改数值 → 写回 weapons.yml → 自动 reload）----
        editor = new WeaponEditor(this);
        getServer().getPluginManager().registerEvents(new WeaponEditorListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
        getServer().getPluginManager().registerEvents(
                new com.taketori.kassen.paper.match.WaitingListener(this), this);
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(characterMenu, this);
        getServer().getPluginManager().registerEvents(playerMenu, this);
        getServer().getPluginManager().registerEvents(roomListMenu, this);
        getServer().getPluginManager().registerEvents(roomCreateMenu, this);
        getServer().getPluginManager().registerEvents(statsMenu, this);
        getServer().getPluginManager().registerEvents(adminMenu, this);
        getServer().getPluginManager().registerEvents(sengokuMenu, this);
        getServer().getPluginManager().registerEvents(tagMenu, this);
        // 每秒驱动全部房间计时（含观战提醒）
        scheduler.runTimerTask(() -> {
            if (rooms != null) {
                rooms.tickAll();
            }
        }, 20L, 20L);
        // 玩家数据自动落盘：角色绑定/隐性标签是长期资产，只靠 onDisable 保存会在
        // 服务器被强杀/崩溃时整份丢失（没有优雅 disable 就永远不落盘）。每 5 分钟异步刷一次。
        scheduler.runTimerTask(() -> {
            if (dataStore != null) {
                dataStore.saveAll();
            }
        }, 20L * 60L * 5L, 20L * 60L * 5L);

        input = new InputListener(this);
        getServer().getPluginManager().registerEvents(input, this);
        getServer().getPluginManager().registerEvents(new MenuClockListener(this), this);
        // 对局功能壳（鹦鹉螺壳）：右键开「退出对局 / 投降」GUI，含防丢弃与死亡保留
        getServer().getPluginManager().registerEvents(new RoomShellListener(this), this);
        // 注册访问器返回的同一个实例：open() 走的是 plugin.roomShellMenu()，
        // 这里若再 new 一个就会出现两个 RoomShellMenu 对象（一个注册事件、一个打开界面）。
        // 当前两者行为等价——该类没有实例状态、Session 又是静态嵌套类——但只要有谁
        // 日后往这里加实例字段，两个对象就会各自持有一份而互相看不见。统一成单例消除隐患。
        getServer().getPluginManager().registerEvents(roomShellMenu(), this);
        getServer().getPluginManager().registerEvents(new CarrierGuardListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new ProjectileListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        // 战国 3v3：目前只做天守阁保护，后续阶段在这里挂铜钟 / 击破器 / 跳跃台
        getServer().getPluginManager().registerEvents(new SengokuListener(this), this);

        var command = getCommand("taketori");
        if (command != null) {
            var executor = new com.taketori.kassen.paper.command.TaketoriCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        validateConfig();

        getLogger().info(String.format("TsukikageKassen v%s 已启用：%d 把武器 / %d 个角色 / %d 种技能类型（适配层 %s）",
                pluginVersion(), config.weapons().size(), config.characters().size(), registry.size(), versions.name()));
        getLogger().info("debug=" + config.debug()
                + "（/taketori debug on 可临时开启；/taketori doctor 可自检识别与名字解析）");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (dataStore != null) {
            dataStore.saveAll();
        }
        if (cooldowns != null) {
            cooldowns.clearAll();
        }
        if (states != null) {
            states.clearAll();
        }
        if (skills != null) {
            skills.cooldownBars().clearAll();   // 移除所有玩家屏幕上的冷却条
        }
        // 商店 NPC 是常驻实体：赶在房间世界回收之前撤掉，否则世界卸载后句柄悬空
        if (shopNpc != null) {
            shopNpc.stop();
        }
        // 玩法层清理：先把房内玩家送回大厅（房间世界即将卸载），再停所有房间并回收世界
        if (rooms != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                boolean inRoom = rooms.roomOf(player.getUniqueId()) != null;
                boolean isAudience = spectator != null && spectator.isAudience(player);
                if (!inRoom && !isAudience) {
                    continue;
                }
                if (lobby != null) {
                    lobby.sendToLobby(player);
                } else {
                    // lobby 尚未初始化的极端窗口：至少移出房间世界，保证世界可以卸载
                    player.teleport(getServer().getWorlds().get(0).getSpawnLocation());
                }
            }
            rooms.stopAll();
            rooms.shutdownWorldIo();
        }
        if (arena != null) {
            arena.save();
        }
        if (setupWand != null) {
            setupWand.stop();
        }
        if (lobby != null) {
            lobby.save();
        }
        if (spectator != null) {
            spectator.clearAll();
        }
        if (stats != null) {
            stats.save();
        }
        if (damageNumbers != null) {
            damageNumbers.clearAll();
        }
        if (rejoin != null) {
            rejoin.clearAll();
        }
        if (arenaSetup != null) {
            arenaSetup.clearAll();
        }
        internalDamage.clear();
    }

    /**
     * /taketori doctor 的内容：把"按了没反应"的每一个可能环节都报一遍
     * （事件是否到达 → 是不是插件武器 → 角色是否绑定 → 槽位是否绑定技能 → 名字解析是否可用）。
     */
    /**
     * doctor 的「战国 3v3」一节。
     *
     * <p>覆盖三类容易静默失效的东西：配置值是否被读到、实体名与材质名能否解析、
     * 场地里的七类点位是否齐全。前两类失败只会让对应效果"什么都不发生"，
     * 不报错；第三类失败会让房间开不起来。这三样都必须能一眼看出来。</p>
     */
    private List<String> sengokuDoctorLines(boolean includePlaces) {
        List<String> lines = new java.util.ArrayList<>();
        var rules = config.sengokuRules();
        lines.add("--- 战国 3v3（三局两胜）---");
        lines.add("赛制: best-of " + rules.maxRounds() + "（先到 " + rules.winsNeeded()
                + " 胜）/ 小局 " + rules.timeLimitSeconds() + " 秒 / 超时判 "
                + (rules.timeoutWinner() == com.taketori.kassen.core.match.sengoku.SengokuRules.TimeoutWinner.TOWER_COUNT
                        ? "箭楼数（持平重开）" : "平局"));
        lines.add("天守阁: 不可直接破坏=" + rules.keepInvulnerable()
                + " 拦截方块与爆炸=" + rules.protectKeepBlocks()
                + " 击破器读条半径=" + rules.keepArmRadius());

        var towers = config.towerRules();
        lines.add("箭楼: " + towers.safeCount() + " 座 / 触发=" + towers.captureMode()
                + " / 读条 " + towers.effectiveCaptureSeconds() + "s"
                + " / 衰减 " + towers.decayPerSecond() + "/s"
                + " / 互锁=" + towers.contestLock());
        lines.add("守卫实体: 牛鬼 " + resolveEntityName(towers.oxDemon().entity(), towers.oxDemon().count())
                + "｜虾兵蟹将 " + resolveEntityName(towers.shrimpCrab().entity(), towers.shrimpCrab().count()));
        lines.add("铜钟材质: " + resolveMaterialName(towers.bellMaterial())
                + "｜音效 " + resolveState(versions.sound(towers.bellSound())));

        var siege = config.siegeRules();
        lines.add("击破器: 材质 " + resolveMaterialName(siege.material())
                + " / 读条 " + siege.effectiveArmSeconds() + "s"
                + " / 不可破坏=" + siege.indestructible()
                + " / 不可丢弃=" + siege.undroppable()
                + " / 职业限制=" + (siege.allowedCharacters() == null || siege.allowedCharacters().isEmpty()
                        ? "全职业" : siege.allowedCharacters().toString()));
        var pad = siege.jumpPad();
        lines.add("跳跃台: " + pad.mode() + " → " + pad.target()
                + (pad.isNearest() ? "" : " #" + pad.specifiedTower())
                + " / 冷却 " + pad.cooldownSeconds() + "s / 免摔 " + pad.fallImmunityTicks() + "tick");

        var midMinions = config.midMinionRules();
        lines.add("中地小兵: " + resolveEntityName(midMinions.entity(), 1)
                + " / 每 " + midMinions.intervalSeconds() + " 秒 " + midMinions.safePerSpawn() + " 只"
                + " / 上限 " + midMinions.safeMaxAlive()
                + " / 分片 " + midMinions.shardTick());

        var energy = config.energyRules();
        var ultimate = energy.ultimateFor(null);
        lines.add("能量: 上限 " + energy.safeMax() + " / 每只小兵 +" + energy.safePerMinion()
                + " / 显示 " + energy.display()
                + " / 释放槽 " + (energy.usesQSlot() ? "Q" : "⚠ " + energy.ultimateSlot() + "（当前只支持 q）"));
        lines.add("必杀(默认): " + ultimate.type() + " 伤害 " + ultimate.damage()
                + " 半径 " + ultimate.radius()
                + " / 已单独配置的角色 " + energy.perCharacter().size() + " 个");

        if (includePlaces) {
            int required = towers.safeCount();
            boolean any = false;
            for (var def : arena().all().values()) {
                var map = def.sengoku();
                if (map.isEmpty()) {
                    continue;   // 非战国地图不报，避免刷屏
                }
                any = true;
                String missing = map.missingHint(required);
                lines.add("场地 [" + def.id() + "] " + map.describe()
                        + (missing.isEmpty() ? " ✅ 点位齐全" : " ❌ 还缺：" + missing));
            }
            if (!any) {
                lines.add("场地点位: 还没有任何场地配置战国点位"
                        + "（用 /taketori sengoku setkeep … 逐项划）");
            }
        } else {
            lines.add("场地点位: 需要管理员权限才能查看");
        }
        return lines;
    }

    /** 实体名能否解析（解析不到只会在刷怪时静默什么都不发生，所以要在这里点名）。 */
    private String resolveEntityName(String raw, int count) {
        if (raw == null || raw.isBlank()) {
            return "未设置 ❌";
        }
        try {
            org.bukkit.entity.EntityType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            return raw + " ×" + count + " ✅";
        } catch (IllegalArgumentException ex) {
            return raw + " ×" + count + " ❌ 无法解析";
        }
    }

    /** 材质名能否解析。 */
    private String resolveMaterialName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未设置 ❌";
        }
        return org.bukkit.Material.matchMaterial(raw.trim()) != null
                ? raw + " ✅"
                : raw + " ❌ 无法解析";
    }

    public List<String> doctorReport(Player player, boolean includeConfigProblems) {        List<String> lines = new java.util.ArrayList<>();
        lines.add("版本 " + pluginVersion() + " / 适配层 " + versions.name());
        lines.add("debug=" + config.debug() + "（用 /taketori debug on 可临时开启）");
        lines.add("已加载 " + config.weapons().size() + " 把武器 / " + config.characters().size()
                + " 个角色 / " + registry.size() + " 种技能类型");
        lines.add("Q 键行为: q-mode=" + config.qMode() + describeQMode());
        lines.add("config: weapon-slots=" + config.weaponSlots()
                + " soulbound=" + config.soulbound() + " allow-drop=" + config.allowDrop());

        lines.addAll(sengokuDoctorLines(includeConfigProblems));

        lines.add("--- 名字解析（解析失败会让对应效果静默失效）---");
        lines.add("能力探测: attribute=" + versions.supports("registry.attribute")
                + " particle=" + versions.supports("registry.particle")
                + " sound=" + versions.supports("registry.sound")
                + " potion=" + versions.supports("registry.potion"));
        lines.add("属性 attack_damage : " + resolveState(versions.attribute("attack_damage")));
        lines.add("属性 attack_speed  : " + resolveState(versions.attribute("attack_speed")));
        lines.add("属性 max_health    : " + resolveState(versions.attribute("max_health")));
        lines.add("粒子 FLAME         : " + resolveState(versions.particle("FLAME")));
        lines.add("粒子 SNOWFLAKE     : " + resolveState(versions.particle("SNOWFLAKE")));
        lines.add("音效 ENTITY_PLAYER_ATTACK_STRONG : " + resolveState(versions.sound("ENTITY_PLAYER_ATTACK_STRONG")));
        lines.add("音效 ITEM_TRIDENT_THROW          : " + resolveState(versions.sound("ITEM_TRIDENT_THROW")));
        lines.add("药水 SLOWNESS      : " + resolveState(versions.potionEffect("SLOWNESS")));
        lines.add("药水 RESISTANCE    : " + resolveState(versions.potionEffect("RESISTANCE")));

        if (player != null) {
            lines.add("--- 你（" + player.getName() + "）---");
            var profile = config.characters().profile(player.getUniqueId());
            lines.add("角色绑定: " + (profile.hasCharacter()
                    ? profile.characterId()
                    : "无（用 /taketori character <你> <角色id> 绑定）"));

            ItemStack hand = player.getInventory().getItemInMainHand();
            var identity = items.read(hand);
            lines.add("主手: " + hand.getType() + " / 是否插件武器: "
                    + (identity != null ? "是"
                    : "否（角色装备由开局发放；管理可用 /taketori character 指定角色）"));
            if (identity != null) {
                lines.add("PDC: weapon_id=" + identity.weaponId() + " character_id=" + identity.characterId()
                        + " mode=" + identity.mode() + " soulbound=" + identity.soulbound());
                var weapon = config.weapons().get(identity.weaponId());
                if (weapon == null) {
                    lines.add("!! weapon_id 在 weapons.yml 里不存在");
                } else {
                    String mode = profile.mode(weapon.id(), weapon.defaultMode());
                    lines.add("当前模式: " + mode + " / 可用模式 " + weapon.modes().keySet());
                    for (SkillSlot slot : SkillSlot.values()) {
                        var def = weapon.skill(slot, mode);
                        if (!def.isPresent()) {
                            lines.add("  " + slot.key() + ": 未绑定（走原版行为）");
                            continue;
                        }
                        double cooldown = cooldowns.remainingSeconds(
                                CooldownManager.key(player.getUniqueId(), weapon.id(), slot));
                        lines.add("  " + slot.key() + ": " + def.type()
                                + (cooldown > 0 ? "（冷却 " + SkillManager.formatSeconds(cooldown) + "s）" : "（就绪）"));
                    }
                }
            }
        }

        // ---- 多世界：谁会被接管、消息发给谁（多世界服务器排查用）----
        org.bukkit.Location lobbySpawn = lobby == null ? null : lobby.spawn();
        String lobbyWorld = lobbySpawn == null || lobbySpawn.getWorld() == null
                ? null : lobbySpawn.getWorld().getName();
        lines.add("进服送大厅: " + (config.lobbyTeleportOnJoin()
                ? "开（" + WorldScope.describeTakeover(config.lobbyTakeoverWorlds(), lobbyWorld) + "）"
                : "关（lobby.teleport-on-join: false）"));

        // ---- 多场地 / 多房间（BedWars 式匹配的核心排查项）----
        lines.add("--- 场地与房间 ---");
        var allArenas = arena == null ? java.util.Map.<String, com.taketori.kassen.paper.match.ArenaDef>of()
                : arena.all();
        if (allArenas.isEmpty()) {
            lines.add("场地: 无（/taketori arena create <id> 创建，配好后 enable 并 /taketori reload）");
        } else {
            for (var entry : allArenas.entrySet()) {
                var def = entry.getValue();
                var roomForArena = rooms == null ? null : rooms.room(def.id());
                Set<String> roomWorldNames = roomForArena == null ? Set.of() : roomForArena.roomWorlds();
                String worlds = roomForArena == null ? "（未开放，无房间）"
                        : (roomWorldNames.isEmpty() ? "（房间未开局，暂无对局世界）"
                        : String.join(",", roomWorldNames));
                lines.add("场地 " + def.id() + ": " + (def.enabled() ? "开放" : "关闭")
                        + " / " + (def.isReady() ? "就绪" : "未就绪（缺 " + def.missingHint() + "）")
                        + " / 世界 " + worlds);
            }
        }
        if (rooms != null) {
            for (var room : rooms.rooms()) {
                String live = room.isRunning() ? "进行中 剩余" + room.remainingText()
                        : room.phase().name();
                lines.add("房间 " + room.id() + ": " + live
                        + " / " + (room.isPve() ? "PVE" : "PVP")
                        + " / 等待 " + room.waitingCount() + "/" + room.maxPlayers()
                        + (room.arena().isReady() ? "" : " / !! 场地未就绪"));
            }
            lines.add("房间总数: " + rooms.rooms().size());
        }
        lines.add("消息播报: " + ("all".equalsIgnoreCase(config.broadcastScope())
                ? "全服（所有世界都能看到）"
                : "只发给消息所属世界（对局播报→对局世界，大厅播报→大厅世界）"));
        lines.add("菜单时钟: " + (config.menuClockEnabled()
                ? "开（材质 " + config.menuClockMaterial() + "，进服发放 "
                + (config.menuClockGiveOnJoin() ? "开" : "关") + "）"
                : "关"));

        if (includeConfigProblems) {
            List<String> problems = new ConfigValidator(this).validate();
            if (problems.isEmpty()) {
                lines.add("配置校验: 通过");
            } else {
                lines.add("配置校验: " + problems.size() + " 个问题");
                problems.stream().limit(10).forEach(problem -> lines.add("  - " + problem));
            }
        }
        return lines;
    }

    /** 把 q-mode 翻译成人话，避免"按 Q 没反应"时看不出配置在做什么。 */
    private String describeQMode() {
        String mode = config.qMode() == null ? "" : config.qMode().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "held-slot" -> "（只切快捷栏，不触发模式切换技能）";
            case "none" -> "（只拦截丢弃，不做任何切换）";
            default -> "（触发武器 q 槽技能：模式切换 / 装备切换）";
        };
    }

    private static String resolveState(Object value) {
        return value == null ? "解析失败（该效果不会生效）" : "OK";
    }

    /** 配置校验：把"写错了但不会崩"的问题在启动 / 重载时直接列到控制台。 */
    private void validateConfig() {
        List<String> problems = new ConfigValidator(this).validate();
        if (problems.isEmpty()) {
            getLogger().info("配置校验通过");
            return;
        }
        getLogger().warning("配置校验发现 " + problems.size() + " 个问题（技能仍可运行，但请核对这些条目）：");
        for (String problem : problems) {
            getLogger().warning("  - " + problem);
        }
    }

    private void registerSkills() {
        projectileSkill = new ProjectileSkill(this);
        registry.register(new MeleeSmashSkill(this));
        registry.register(projectileSkill);
        registry.register(new SelfBoostSkill(this));
        registry.register(new BlinkSkill(this));
        registry.register(new GrappleSkill(this));
        registry.register(new SpecialShotToggleSkill(this));
        registry.register(new ShieldGuardSkill(this));
        registry.register(new MirrorSkill(this));
        registry.register(new MirrorBurstSkill(this));
        registry.register(new ModeSwitchSkill(this));
        registry.register(new EquipSwitchSkill(this));
        registry.register(new ShockwaveSkill(this));
        registry.register(new RocketJumpSkill(this));
        registry.register(new PullSkill(this));
        registry.register(new ReflectSkill(this));
        // ---- 2.0 版新增的五个类型：易伤施加 / 破甲 / 兑现 / 场地 / 召唤 ----
        registry.register(new MarkApplySkill(this));
        registry.register(new ArmorBreakSkill(this));
        registry.register(new EchoConsumeSkill(this));
        registry.register(new DeployZoneSkill(this));
        registry.register(new SummonAllySkill(this));
    }

    /** 重载配置：失败时保留上一份可用配置，避免把服务器改坏。 */
    public boolean reloadAll(CommandSender sender) {
        try {
            config.load();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "配置重载失败", throwable);
            if (sender != null) {
                sender.sendMessage(config.messages().prefixed("command.reload-failed",
                        "reason", String.valueOf(throwable.getMessage())));
            }
            return false;
        }
        arena.load();
        if (rooms != null) {
            // 多房间：旧房间玩家回大厅、旧房间停止，再按新配置/启用场地重建（全部回到 WAITING）
            rooms.rebuild();
        }
        minionTypes.load();   // 月人种类与精英规格
        loadTags();           // 隐性标签与权重
        validateConfig();
        return true;
    }

    /**
     * 启动时清掉上一轮留下的「玩家 ↔ 角色」绑定。
     *
     * <p>绑定只代表「这一局想玩谁」：留着它，玩家下次进房会直接按上局的角色发装备，
     * 换版本改了角色数值/武器表之后就变成莫名的错配。清掉后角色菜单会强制重选
     * （等待区与补位路径都会 {@code openForced}）。隐性标签属于长期身份，不清。</p>
     */
    private void clearCharacterBindings() {
        int cleared = dataStore.clearCharacters();
        if (cleared > 0) {
            dataStore.saveAll();
            getLogger().info("已清除 " + cleared + " 条上局角色绑定：玩家进入房间后需要重新选择角色");
        }
    }

    /** 服务器热重载 / 插件启用时，把在线玩家的绑定恢复出来。 */
    private void restoreOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            // 隐性标签：不论有没有角色都恢复
            config.characters().profile(player.getUniqueId()).setTag(dataStore.tagOf(player.getUniqueId()));
            String characterId = dataStore.characterIdOf(player.getUniqueId());
            if (characterId != null && config.characters().has(characterId)) {
                config.characters().bind(player.getUniqueId(), characterId);
                // 重载会停掉所有对局，所以这里正常轮不到发装备；仍保留「对不对局中」的判断，
                // 与 bindCharacter 用同一条规则，避免以后有人把重载流程改成保留对局时漏掉。
                if (config.autoGiveOnJoin() && inRunningMatch(player)) {
                    giveCharacterWeapons(player, characterId);
                }
            }
        }
    }

    /**
     * 该玩家是否正在一局<b>已经开打</b>（玻璃笼准备 / 战斗中）的对局里。
     *
     * <p>角色装备（武器 + 开局铁甲）只在这种情况下发放。大厅与等待区选角色只记选择——
     * 否则玩家还没开打就拿着对局武器，装备还会被「进房即封存」存进背包快照，结算时又还给他。
     * 玻璃笼阶段（CAGED）必须算进来：那几秒里换角色若不补发，开局就是整局空手。</p>
     */
    public boolean inRunningMatch(Player player) {
        if (player == null) {
            return false;
        }
        RoomManager manager = rooms();
        if (manager == null) {
            return false;
        }
        var room = manager.roomOf(player);
        return room != null && room.isMatchInProgress();
    }

    public void bindCharacter(Player player, String characterId) {
        if (characterId == null) {
            config.characters().unbind(player.getUniqueId());
            dataStore.remove(player.getUniqueId());
            clearCharacterWeapons(player);
            resetCharacterAttributes(player);
            return;
        }
        // 换角色先清旧武器：否则从 4 武器角色换到 2 武器角色时，多余槽位残留旧角色武器
        clearCharacterWeapons(player);
        config.characters().bind(player.getUniqueId(), characterId);
        dataStore.setCharacterId(player.getUniqueId(), characterId);
        if (inRunningMatch(player)) {
            // 对局进行中换角色：必须立刻换装，否则手上还留着旧角色的武器
            giveCharacterWeapons(player, characterId);
        } else {
            // 大厅 / 等待区：只应用角色属性（血量上限、移速），武器与开局铁甲留到开局统一发
            var character = config.characters().get(characterId);
            if (character != null) {
                applyCharacterAttributes(player, character);
            }
        }
    }

    /**
     * 解除玩家的角色：清掉随之而来的武器与属性，<b>但保留隐性标签</b>。
     *
     * <p>与 {@code bindCharacter(player, null)} 的区别在于存储层：那条路走
     * {@code dataStore.remove(uuid)}，会把隐性标签一并删掉。对局结算这种「只该脱掉角色」的
     * 场景必须用本方法——标签是玩家资产，不该因为打完一局就没了。</p>
     */
    public void unbindCharacter(Player player) {
        if (player == null) {
            return;
        }
        config.characters().unbind(player.getUniqueId());
        dataStore.setCharacterId(player.getUniqueId(), null);
        clearCharacterWeapons(player);
        resetCharacterAttributes(player);
    }

    /** 按角色定义发放武器到预留槽位，并应用角色属性。 */
    public void giveCharacterWeapons(Player player, String characterId) {
        var character = config.characters().get(characterId);
        if (character == null) {
            return;
        }
        List<Integer> slots = config.weaponSlots();
        int index = 0;
        for (String weaponId : character.weapons()) {
            if (index >= slots.size()) {
                getLogger().warning("角色 " + characterId + " 的武器数量超过预留槽位数量，已忽略多余武器");
                break;
            }
            var weapon = config.weapons().get(weaponId);
            if (weapon == null) {
                continue;
            }
            int slot = slots.get(index);
            player.getInventory().setItem(slot, items.create(weapon, player));
            index++;
        }
        if (!slots.isEmpty() && index > 0) {
            player.getInventory().setHeldItemSlot(slots.get(0));
        }
        applyCharacterAttributes(player, character);
        giveLoadoutArmor(player);
        giveCharacterExtras(player, characterId);
    }

    /**
     * 角色专属的开局补给：夕月的弓开一局配 1 支箭。
     *
     * <p>开局流程先清空背包不存在叠加；但<b>对局中切换角色</b>会走到这里
     * （clearCharacterWeapons 只清插件武器不清箭），所以发新箭前先收掉旧的，
     * 防止反复切角色无限攒箭。</p>
     */
    private void giveCharacterExtras(Player player, String characterId) {
        if ("yuzuki".equals(characterId)) {
            player.getInventory().remove(new ItemStack(Material.ARROW));
            player.getInventory().addItem(new ItemStack(Material.ARROW, 1));
        }
    }

    /**
     * 开局护甲：把配置指定的套装<b>直接穿在身上</b>（默认全套铁甲 + 保护 II + 不可破坏）。
     *
     * <p>开局流程会先 {@code stashAndClearInventory} 封存并清空背包，所以这里的替换不会顶掉
     * 玩家自己的装备——他原来的护甲连同整背包都在快照里，结算时原样返还。</p>
     *
     * <p>护甲带 {@code unbreakable} 标记：对局装备不该被耐久打断，否则打到一半甲碎了等于
     * 白送对手优势。把 {@code loadout.armor-material} 写成 NONE 或关掉
     * {@code loadout.armor-enabled} 就完全跳过。附魔按注册名 {@code protection} 解析。</p>
     */
    private void giveLoadoutArmor(Player player) {
        if (!config.loadoutArmorEnabled()) {
            return;
        }
        String set = config.loadoutArmorMaterial();
        // Bukkit 的 setArmorContents 约定顺序固定为 [靴子, 护腿, 胸甲, 头盔]
        List<String> pieces = List.of("BOOTS", "LEGGINGS", "CHESTPLATE", "HELMET");
        ItemStack[] armor = new ItemStack[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            Material material = armorMaterial(set, pieces.get(i));
            if (material == null) {
                return;   // 材质解析失败（armorMaterial 已经打过日志）；NONE 也会走到这里
            }
            armor[i] = armorPiece(material, config.loadoutArmorProtection());
        }
        player.getInventory().setArmorContents(armor);
    }

    /** 把套装前缀拼成某个槽位的材质名（IRON + HELMET → IRON_HELMET）。 */
    private Material armorMaterial(String setPrefix, String piece) {
        if (setPrefix == null || setPrefix.isBlank()) {
            return null;
        }
        String prefix = setPrefix.trim().toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(prefix) || "AIR".equals(prefix)) {
            return null;
        }
        Material material = Material.matchMaterial(prefix + "_" + piece);
        if (material == null) {
            getLogger().warning("loadout.armor-material 无法解析: " + setPrefix
                    + "（可用 IRON / CHAINMAIL / GOLDEN / LEATHER / DIAMOND / NETHERITE）");
        }
        return material;
    }

    /** 造一件护甲：可选保护附魔（等级按附魔自身的上限截断）+ 不可破坏标记。 */
    private ItemStack armorPiece(Material material, int protectionLevel) {
        ItemStack stack = new ItemStack(material);
        if (protectionLevel > 0) {
            Enchantment protection = protectionEnchantment();
            if (protection != null) {
                stack.addUnsafeEnchantment(protection, Math.min(protectionLevel, protection.getMaxLevel()));
            }
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // 对局装备不该被耐久打断：甲碎在半场等于白送对手优势
            meta.setUnbreakable(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 保护附魔：优先用常量，取不到就按注册名查（新旧版本常量名不同）。 */
    private Enchantment protectionEnchantment() {
        try {
            return Enchantment.PROTECTION;
        } catch (Throwable ignored) {
            return Enchantment.getByKey(NamespacedKey.minecraft("protection"));
        }
    }

    private void applyCharacterAttributes(Player player, com.taketori.kassen.core.character.CharacterDef character) {
        Attribute health = versions.attribute("max_health");
        if (health != null) {
            AttributeInstance instance = player.getAttribute(health);
            if (instance != null) {
                instance.setBaseValue(character.attribute("max-health", 20.0D));
                // 上限被调低时把当前血量一并夹下来：否则大厅里会出现 20/18 这种不一致读数
                // （原版下一 tick 也会夹，但玩家选完角色当场就看到了错的值）
                if (player.getHealth() > instance.getValue()) {
                    player.setHealth(instance.getValue());
                }
            }
        }
        Attribute speed = versions.attribute("movement_speed");
        if (speed != null) {
            AttributeInstance instance = player.getAttribute(speed);
            if (instance != null) {
                instance.setBaseValue(character.attribute("movement-speed", 0.1D));
            }
        }
    }

    /**
     * 解绑角色时把被 {@code setBaseValue} 改动过的属性还原为原版默认值
     * （max_health=20、movement_speed=0.1），避免角色血量/速度永久残留、污染其他玩法。
     */
    private void resetCharacterAttributes(Player player) {
        Attribute health = versions.attribute("max_health");
        if (health != null) {
            AttributeInstance instance = player.getAttribute(health);
            if (instance != null) {
                instance.setBaseValue(20.0D);
                if (player.getHealth() > instance.getValue()) {
                    player.setHealth(instance.getValue());
                }
            }
        }
        Attribute speed = versions.attribute("movement_speed");
        if (speed != null) {
            AttributeInstance instance = player.getAttribute(speed);
            if (instance != null) {
                instance.setBaseValue(0.1D);
            }
        }
    }

    public void clearCharacterWeapons(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (items.isPluginWeapon(stack)) {
                inventory.setItem(slot, null);
            }
        }
    }

    /** 玩家退出时的清理：避免状态与冷却泄漏。 */
    public void forgetPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        cooldowns.clearPlayer(uuid);
        states.clear(uuid);
        internalDamage.remove(uuid);
        // 战国房间的按人状态（能量槽、跳跃台冷却）也一并清掉，
        // 否则中途退出玩家的条目只能等 clearAll / 房间销毁才回收
        var room = rooms().roomOf(player);
        if (room != null && room.isSengoku()) {
            room.energy().forget(uuid);
            room.jumpPads().forget(uuid);
        }
        if (skills != null) {
            skills.cooldownBars().clear(uuid);   // 清掉屏幕上残留的冷却条
        }
        if (damageNumbers != null) {
            damageNumbers.clear(uuid);   // 未汇报完的伤害数字聚合
        }
        config.characters().forget(uuid);
        if (editor != null) {
            editor.forget(uuid);   // 未完成的武器编辑会话
        }
        if (arena != null) {
            // 管理员的选中场地与选区角点只在会话内有效：退出即清，避免 UUID 映射无限增长
            arena.clearSelected(uuid);
            arena.clearSelection(uuid);
        }
    }

    public void markInternalDamage(UUID uuid) {
        if (uuid != null) {
            internalDamage.add(uuid);
        }
    }

    public void unmarkInternalDamage(UUID uuid) {
        if (uuid != null) {
            internalDamage.remove(uuid);
        }
    }

    public boolean isInternalDamage(UUID uuid) {
        return uuid != null && internalDamage.contains(uuid);
    }

    /** /taketori debug 用的状态摘要。 */
    public String describe(Player player) {
        var profile = config.characters().profile(player.getUniqueId());
        StringBuilder builder = new StringBuilder();
        builder.append("character=").append(profile.hasCharacter() ? profile.characterId() : "none");

        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = items.read(hand);
        if (identity == null) {
            builder.append(" weapon=none");
        } else {
            var weapon = config.weapons().get(identity.weaponId());
            builder.append(" weapon=").append(identity.weaponId());
            builder.append(" mode=").append(profile.mode(identity.weaponId(),
                    weapon == null ? "?" : weapon.defaultMode()));
            if (weapon != null) {
                for (SkillSlot slot : SkillSlot.values()) {
                    var def = weapon.skill(slot, profile.mode(identity.weaponId(), weapon.defaultMode()));
                    if (def.isPresent()) {
                        String key = CooldownManager.key(player.getUniqueId(), weapon.id(), slot);
                        builder.append(' ').append(slot.key()).append('=').append(def.type());
                        if (!cooldowns.isReady(key)) {
                            builder.append("(cd ").append(SkillManager.formatSeconds(cooldowns.remainingSeconds(key))).append(')');
                        }
                    }
                }
            }
            builder.append(" specialShot=").append(profile.specialShot());
        }
        var defense = states.defense(player.getUniqueId());
        builder.append(" defense=").append(defense == null ? "none" : defense.source() + "("
                + SkillManager.formatSeconds(defense.remainingSeconds()) + "s)");
        builder.append(" cooldownEntries=").append(cooldowns.size());
        return builder.toString();
    }

    /**
     * 插件版本（来自 plugin.yml，而 plugin.yml 的版本由 gradle.properties 生成）。
     * 状态输出与日志都用它，便于确认服务器上跑的是哪个构建。
     */
    @SuppressWarnings("deprecation")
    public String pluginVersion() {
        return getDescription().getVersion();
    }

    // ---------------------------------------------------------------- 玩法层访问器

    public ArenaManager arena() {
        return arena;
    }

    /** 多房间注册表（BedWars 式匹配；玩家→房间唯一权威映射）。 */
    public RoomManager rooms() {
        return rooms;
    }

    /** 菜单时钟（右键打开玩家菜单的道具）。 */
    public MenuClock menuClock() {
        return menuClock;
    }

    /** 对局功能壳（房间内右键打开「退出对局 / 投降」GUI 的鹦鹉螺壳）。 */
    public com.taketori.kassen.paper.item.RoomShell roomShell() {
        if (roomShell == null) {
            roomShell = new com.taketori.kassen.paper.item.RoomShell(this);
        }
        return roomShell;
    }

    /** 对局功能面板（鹦鹉螺壳打开的 GUI）。 */
    public com.taketori.kassen.paper.match.room.RoomShellMenu roomShellMenu() {
        if (roomShellMenu == null) {
            roomShellMenu = new com.taketori.kassen.paper.match.room.RoomShellMenu(this);
        }
        return roomShellMenu;
    }

    /** PVE 设置快照（大波次 / 精英缩放 / 据点 / 难度档）。 */
    public com.taketori.kassen.core.match.PveSettings pveSettings() {
        return config.pveSettings();
    }

    /** 输入层（/taketori keys 回放最近的按键事件）。 */
    public InputListener input() {
        return input;
    }

    /** 月人种类与精英规格。 */
    public MinionTypes minionTypes() {
        return minionTypes;
    }

    public StatsTracker stats() {
        return stats;
    }

    public SpectatorManager spectator() {
        return spectator;
    }

    public LobbyManager lobby() {
        return lobby;
    }

    public CharacterMenu characterMenu() {
        return characterMenu;
    }

    /** 玩家入口菜单（匹配 / 队伍选择 / 角色选择 / 排行榜）。 */
    public PlayerMenu playerMenu() {
        return playerMenu;
    }

    /** 房间列表 GUI（等待房加入 / 游戏房旁观）。 */
    public com.taketori.kassen.paper.lobby.RoomListMenu roomListMenu() {
        return roomListMenu;
    }

    /** 创建房间配置 GUI（选模板 + 选模式）。 */
    public com.taketori.kassen.paper.lobby.RoomCreateMenu roomCreateMenu() {
        return roomCreateMenu;
    }

    /** 总计排行榜（跨局累计）。 */
    public StatsMenu statsMenu() {
        return statsMenu;
    }

    /** 管理员菜单（把常用管理指令映射成按钮）。 */
    public AdminMenu adminMenu() {
        return adminMenu;
    }

    /** 战国 3v3 管理菜单（{@code /taketori sengoku menu}）。 */
    public com.taketori.kassen.paper.command.SengokuMenu sengokuMenu() {
        return sengokuMenu;
    }

    /** 隐性标签设置界面（管理员）。 */
    public TagMenu tagMenu() {
        return tagMenu;
    }

    /** 隐性标签与权重（config.yml 的 tags 段）。 */
    public TagManager tags() {
        return tagManager;
    }

    /**
     * 设置玩家的隐性标签（内存档案 + 持久化）。
     * 标签是"隐性"的：不会告知玩家本人，也不影响战斗数值。
     *
     * @param tag 为空表示清除标签
     */
    public void setTag(UUID uuid, String tag) {
        if (uuid == null) {
            return;
        }
        String normalized = tag == null || tag.isBlank() ? null : tag;
        config.characters().profile(uuid).setTag(normalized);
        dataStore.setTag(uuid, normalized);
    }

    /** 读取 config.yml 的 tags 段（隐性标签与权重）。 */
    private void loadTags() {
        tagManager.clear();
        var section = getConfig().getConfigurationSection("tags");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                var def = section.getConfigurationSection(id);
                if (def == null) {
                    continue;
                }
                tagManager.register(id, def.getString("display", id), def.getInt("weight", 0));
            }
        }
        if (tagManager.size() == 0) {
            tagManager.register(TagManager.DEFAULT_TAG, "<gray>普通</gray>", 0);
        }
        getLogger().info("已载入 " + tagManager.size() + " 个隐性标签");
    }

    /** 选区锄（划场地用具）：物品工厂 + 选区行为 + 边框可视化。 */
    public SetupWandService setupWand() {
        return setupWand;
    }

    /** 武器数据编辑 GUI。 */
    public WeaponEditor editor() {
        return editor;
    }

    /** 派对（组队）管理。 */
    public com.taketori.kassen.paper.party.PartyManager party() {
        return party;
    }

    public com.taketori.kassen.paper.party.PartyMenu partyMenu() {
        return partyMenu;
    }

    /** 队伍商店商人 NPC（onEnable 之后非 null；start()/stop() 已在启停里调用）。 */
    public ShopNpcManager shopNpc() {
        return shopNpc;
    }

    /** 队伍商店界面（右键商人打开）。 */
    public ShopMenu shopMenu() {
        return shopMenu;
    }

    /** 断线重连管理。 */
    public com.taketori.kassen.paper.match.RejoinManager rejoin() {
        return rejoin;
    }

    /** 划场地一条龙会话管理。 */
    public com.taketori.kassen.paper.setup.ArenaSetupSession arenaSetup() {
        return arenaSetup;
    }

    public ConfigManager config() {
        return config;
    }

    public VersionAdapter versions() {
        return versions;
    }

    public ItemFactory items() {
        return items;
    }

    public Fx fx() {
        return fx;
    }

    /** 伤害数字（动作栏聚合汇报）。 */
    public com.taketori.kassen.paper.effect.DamageNumbers damageNumbers() {
        return damageNumbers;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public CombatStates states() {
        return states;
    }

    public SkillRegistry registry() {
        return registry;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public SkillManager skills() {
        return skills;
    }

    public ProjectileSkill projectileSkill() {
        return projectileSkill;
    }

    public PlayerDataStore dataStore() {
        return dataStore;
    }
}
