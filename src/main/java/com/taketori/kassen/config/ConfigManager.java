package com.taketori.kassen.config;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.CharacterDef;
import com.taketori.kassen.core.character.CharacterManager;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.sengoku.EnergyRules;
import com.taketori.kassen.core.match.sengoku.MidMinionRules;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.core.match.sengoku.SengokuRules;
import com.taketori.kassen.core.match.sengoku.SiegeRules;
import com.taketori.kassen.core.match.sengoku.TowerRules;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.skill.ThirdSlotTrigger;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.core.weapon.WeaponManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 配置加载：config.yml / weapons.yml / characters.yml / messages.yml。
 *
 * <p>这里的职责只有一件事——<b>把 YAML 变成 core 层的数据对象</b>。
 * 任何数值都不写死在代码里（计划 §4.3），改武器数值 = 改 weapons.yml。</p>
 */
public final class ConfigManager {

    /**
     * 内置 weapons.yml 的模板版本（改动默认数值结构时 +1）。
     *
     * <p>插件不会覆盖玩家的 weapons.yml，所以这里只用于"提示玩家模板已更新"——
     * 否则会出现"插件升级了但技能还是老样子"的困惑。</p>
     */
    public static final int WEAPON_TEMPLATE_VERSION = 9;

    private final TaketoriPlugin plugin;
    private final WeaponManager weapons = new WeaponManager();
    private final CharacterManager characters = new CharacterManager();
    private final Messages messages = new Messages();

    /**
     * 战国 3v3 的对局骨架规则（{@code sengoku.yml}）。
     *
     * <p>缺文件或写坏时退回 {@link SengokuRules#defaults()}：战国模式仍能按默认值跑起来，
     * 而 PVP / PVE 两条线根本不读它。</p>
     */
    private SengokuRules sengokuRules = SengokuRules.defaults();

    /** 战国模式的箭楼规则（{@code sengoku-towers.yml}）。 */
    private TowerRules towerRules = TowerRules.defaults();

    /** 战国模式的击破器与跳跃台规则（{@code sengoku-siege.yml}）。 */
    private SiegeRules siegeRules = SiegeRules.defaults();

    /** 战国模式的中地小兵规则（{@code sengoku-minions.yml}）。 */
    private MidMinionRules midMinionRules = MidMinionRules.defaults();

    /** 战国模式的能量槽与必杀技规则（{@code sengoku-energy.yml}）。 */
    private EnergyRules energyRules = EnergyRules.defaults();

    private int configVersion = 1;
    private boolean debug;
    private boolean actionbar = true;
    private boolean particles = true;
    private boolean sounds = true;
    private boolean soulbound = true;
    private String cooldownDisplay = "bossbar";
    private boolean allowDrop;
    private boolean autoGiveOnJoin;
    // 字段默认值必须与 load() 的回退默认值（"drop"）一致，
    // 避免在首次 load 前读到该字段的代码看到相互矛盾的初值。
    private String qMode = "drop";
    private List<Integer> weaponSlots = List.of(0, 1, 2, 3);

    public ConfigManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 加载全部配置。失败时抛出异常，由主类决定是否保留上一份可用配置。 */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // 先跑配置迁移：插件只在配置文件不存在时写默认值，
        // 老服务器的 config.yml 会一直停留在旧默认行为上（Q 键失效就是这么来的）。
        new ConfigMigrator(plugin).migrate();

        var cfg = plugin.getConfig();
        configVersion = cfg.getInt("config-version", 1);
        debug = cfg.getBoolean("debug", false);
        actionbar = cfg.getBoolean("feedback.actionbar", true);
        particles = cfg.getBoolean("feedback.particles", true);
        sounds = cfg.getBoolean("feedback.sounds", true);
        cooldownDisplay = cfg.getString("feedback.cooldown-display", "bossbar");
        soulbound = cfg.getBoolean("items.soulbound", true);
        allowDrop = cfg.getBoolean("items.allow-drop", false);
        autoGiveOnJoin = cfg.getBoolean("items.auto-give-on-join", false);
        // 回退默认值必须与 config.yml 模板保持一致：这里曾经是 held-slot，
        // 于是当配置文件里缺少该键时，Q 会退化成"只切快捷栏"，与文档描述不符。
        qMode = cfg.getString("input.q-mode", "drop");
        weaponSlots = List.copyOf(cfg.getIntegerList("input.weapon-slots"));
        if (weaponSlots.isEmpty()) {
            weaponSlots = List.of(0, 1, 2, 3);
        }

        // messages.yml 回填默认键（新文案在旧文件里不存在时会显示成 "skill.xxx" 键名）；
        // weapons / characters 不回填，避免改动用户的数值文件
        messages.load(loadYaml("messages.yml", true));
        // 战国模式的对局骨架：不覆盖玩家文件，缺失/写坏都退回默认值
        parseSengoku(loadYaml("sengoku.yml", false));
        parseTowers(loadYaml("sengoku-towers.yml", false));
        parseSiege(loadYaml("sengoku-siege.yml", false));
        parseMidMinions(loadYaml("sengoku-minions.yml", false));
        parseEnergy(loadYaml("sengoku-energy.yml", false));

        YamlConfiguration weaponYaml = loadYaml("weapons.yml", false);
        int templateVersion = weaponYaml.getInt("config-version", 1);
        if (templateVersion < WEAPON_TEMPLATE_VERSION) {
            plugin.getLogger().warning("你的 weapons.yml 是模板 v" + templateVersion
                    + "，插件内置模板已是 v" + WEAPON_TEMPLATE_VERSION + "。");
            plugin.getLogger().warning("  插件不会覆盖你的数值文件 → 新机制与新默认值不会自动生效。");
            plugin.getLogger().warning("  同步方式：备份后删除 plugins/TsukikageKassen/weapons.yml，再执行 /taketori reload。");
        }

        WeaponManager parsedWeapons = new WeaponManager();
        parseWeapons(weaponYaml, parsedWeapons);

        CharacterManager parsedCharacters = new CharacterManager();
        parseCharacters(loadYaml("characters.yml", false), parsedCharacters);

        // 校验：角色引用的武器必须存在，否则加载期就报错，不留到运行时
        List<String> dangling = danglingWeapons(parsedCharacters, parsedWeapons);
        if (!dangling.isEmpty()) {
            // 角色与武器键在 1.7.0 更名后整体作废，旧服务器升级上来的表现就是
            // 「每一处武器引用都悬空」。这种情况改用 jar 内置的出厂默认值，
            // 别让插件直接启用失败；只是手改配置写错一两个键时仍然照常抛错。
            if (dangling.size() < refCount(parsedCharacters)) {
                throw new IllegalStateException("characters.yml 引用了不存在的武器: " + String.join(", ", dangling));
            }
            YamlConfiguration bundledCharsYaml = loadBundledYaml("characters.yml");
            CharacterManager bundledCharacters = new CharacterManager();
            if (bundledCharsYaml != null) {
                parseCharacters(bundledCharsYaml, bundledCharacters);
            }
            List<String> bundledDangling = danglingWeapons(bundledCharacters, parsedWeapons);
            if (bundledCharacters.size() == 0 || !bundledDangling.isEmpty()) {
                // 角色表换成出厂默认后仍然对不上 → weapons.yml 也是旧版，一起换掉。
                YamlConfiguration bundledWeaponsYaml = loadBundledYaml("weapons.yml");
                WeaponManager bundledWeapons = new WeaponManager();
                if (bundledWeaponsYaml != null) {
                    parseWeapons(bundledWeaponsYaml, bundledWeapons);
                }
                bundledDangling = danglingWeapons(bundledCharacters, bundledWeapons);
                if (bundledCharacters.size() == 0 || !bundledDangling.isEmpty()) {
                    // 连出厂默认都自相矛盾，只可能是打包出错，照旧把问题暴露出来。
                    throw new IllegalStateException("characters.yml 引用了不存在的武器: "
                            + String.join(", ", dangling));
                }
                parsedWeapons = bundledWeapons;
                plugin.getLogger().warning("weapons.yml 的武器键已全部作废（疑似旧版本文件），本次改用插件内置的默认武器表。");
            }
            parsedCharacters = bundledCharacters;
            plugin.getLogger().warning("characters.yml 的角色键已全部作废（疑似旧版本文件），本次改用插件内置的默认角色表。");
            plugin.getLogger().warning("  这两个文件插件不会覆盖，请备份后删除 plugins/" + plugin.getName()
                    + "/ 下的 characters.yml 与 weapons.yml，再执行 /taketori reload 让插件重新生成。");
        }

        weapons.clear();
        for (WeaponDef def : parsedWeapons.all()) {
            weapons.register(def);
        }
        characters.clearDefinitions();
        for (CharacterDef def : parsedCharacters.all()) {
            characters.register(def);
        }
    }

    /** 列出角色表里所有指向不存在武器的引用，用于加载期校验。 */
    private static List<String> danglingWeapons(CharacterManager characters, WeaponManager weapons) {
        List<String> dangling = new ArrayList<>();
        for (CharacterDef def : characters.all()) {
            for (String weaponId : def.weapons()) {
                if (!weapons.has(weaponId)) {
                    dangling.add(def.id() + " -> " + weaponId);
                }
            }
        }
        return dangling;
    }

    /** 角色表里声明的武器引用总数，用来判断「是全部悬空还是个别写错」。 */
    private static int refCount(CharacterManager characters) {
        int total = 0;
        for (CharacterDef def : characters.all()) {
            total += def.weapons().size();
        }
        return total;
    }

    /**
     * 只读 jar 内置的原始配置，不与用户文件合并。
     * 回退到出厂默认值时必须走这里——{@link #loadYaml} 会把用户文件叠在默认值之上，
     * 旧版本遗留的键仍然在册，拿不到干净的默认表。
     */
    private YamlConfiguration loadBundledYaml(String resourceName) {
        try (var stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "读取内置默认配置失败: " + resourceName, ex);
            return null;
        }
    }

    private YamlConfiguration loadYaml(String resourceName, boolean backfillDefaults) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // 与 jar 内默认值合并：新增字段在升级后自动补齐（不改动用户已改的值）
        try (var stream = plugin.getResource(resourceName)) {
            if (stream != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "读取内置默认配置失败: " + resourceName, ex);
        }
        if (backfillDefaults) {
            // 把新增的键写回用户文件：否则文件里看不到新键，用户也不知道有哪些能改
            yaml.options().copyDefaults(true);
            try {
                yaml.save(file);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "回填默认配置失败: " + resourceName, ex);
            }
        }
        return yaml;
    }

    private void parseWeapons(YamlConfiguration yaml, WeaponManager target) {
        ConfigurationSection root = yaml.getConfigurationSection("weapons");
        if (root == null) {
            plugin.getLogger().warning("weapons.yml 里没有 weapons: 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String material = section.getString("material", "STICK");
            Map<String, Double> attributes = toDoubleMap(section.getConfigurationSection("attributes"));

            Map<String, WeaponDef.ModeDef> modes = new LinkedHashMap<>();
            ConfigurationSection modeSection = section.getConfigurationSection("modes");
            if (modeSection != null) {
                for (String modeId : modeSection.getKeys(false)) {
                    ConfigurationSection mode = modeSection.getConfigurationSection(modeId);
                    if (mode == null) {
                        continue;
                    }
                    modes.put(modeId.toUpperCase(java.util.Locale.ROOT), new WeaponDef.ModeDef(
                            modeId.toUpperCase(java.util.Locale.ROOT),
                            mode.getString("display", modeId),
                            parseSkills(mode.getConfigurationSection("skills"))));
                }
            }

            String defaultMode = section.getString("default-mode", modes.isEmpty() ? "" : modes.keySet().iterator().next());
            if (defaultMode != null) {
                defaultMode = defaultMode.toUpperCase(java.util.Locale.ROOT);
            }

            // 命中附加效果（hit-effects）：夕月的箭随机挂负面效果等
            Map<String, Object> hitEffects = new LinkedHashMap<>();
            ConfigurationSection hitSection = section.getConfigurationSection("hit-effects");
            if (hitSection != null) {
                hitEffects.putAll(hitSection.getValues(false));
            }

            WeaponDef def = new WeaponDef(
                    id,
                    section.getString("character", ""),
                    section.getString("display", id),
                    material,
                    section.getInt("model-data", 0),
                    section.getStringList("lore"),
                    attributes,
                    defaultMode,
                    modes,
                    parseSkills(section.getConfigurationSection("skills")),
                    hitEffects);
            target.register(def);
        }
    }

    private Map<SkillSlot, SkillDef> parseSkills(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<SkillSlot, SkillDef> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            SkillSlot slot = SkillSlot.byKey(key);
            ConfigurationSection skill = section.getConfigurationSection(key);
            if (slot == null || skill == null) {
                if (slot == null) {
                    plugin.getLogger().warning("weapons.yml 出现未知按键槽位: " + key);
                }
                continue;
            }
            String type = skill.getString("type", "");
            double cooldown = skill.getDouble("cooldown", 0.0D);
            Map<String, Object> params = new LinkedHashMap<>();
            ConfigurationSection paramSection = skill.getConfigurationSection("params");
            if (paramSection != null) {
                params.putAll(paramSection.getValues(false));
            }
            result.put(slot, new SkillDef(type, cooldown, params));
        }
        return result;
    }

    private void parseCharacters(YamlConfiguration yaml, CharacterManager target) {
        ConfigurationSection root = yaml.getConfigurationSection("characters");
        if (root == null) {
            plugin.getLogger().warning("characters.yml 里没有 characters: 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            target.register(new CharacterDef(
                    id,
                    section.getString("display", id),
                    section.getStringList("weapons"),
                    toDoubleMap(section.getConfigurationSection("attributes")),
                    section.getStringList("lore")));
        }
    }

    private Map<String, Double> toDoubleMap(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object raw = section.get(key);
            if (raw instanceof Number number) {
                result.put(key, number.doubleValue());
            } else if (raw instanceof String text) {
                try {
                    result.put(key, Double.parseDouble(text.trim()));
                } catch (NumberFormatException ex) {
                    // 旧实现用 getDouble 直接把写错的值静默转成 0.0——武器/角色属性归零却不报错，
                    // 表现为"属性没生效"且无从排查。这里点名告警，但照旧按 0 处理以免整个加载失败。
                    plugin.getLogger().warning("数值属性 " + key + " 写成了无法解析的内容: \""
                            + text + "\"（已按 0 处理）");
                    result.put(key, 0.0D);
                }
            } else {
                plugin.getLogger().warning("数值属性 " + key + " 的类型无法解析: " + raw + "（已按 0 处理）");
                result.put(key, 0.0D);
            }
        }
        return result;
    }

    /**
     * 解析 {@code sengoku.yml} 的对局骨架段。
     *
     * <p>逐项回退：整份文件缺失、某个段缺失、某个键写坏，都只影响那一项，其余照常读——
     * 与武器配置"写错一个键不影响其它武器"的口径一致。</p>
     */
    private void parseSengoku(YamlConfiguration yaml) {
        SengokuRules fallback = SengokuRules.defaults();
        if (yaml == null) {
            sengokuRules = fallback;
            return;
        }
        ConfigurationSection rounds = yaml.getConfigurationSection("rounds");
        ConfigurationSection keep = yaml.getConfigurationSection("keep");
        SengokuRules.TimeoutWinner timeout = fallback.timeoutWinner();
        if (rounds != null) {
            String raw = rounds.getString("timeout-winner");
            if (raw != null) {
                timeout = "draw".equalsIgnoreCase(raw.trim())
                        ? SengokuRules.TimeoutWinner.DRAW
                        : SengokuRules.TimeoutWinner.TOWER_COUNT;
            }
        }
        sengokuRules = new SengokuRules(
                rounds == null ? fallback.bestOf()
                        : rounds.getInt("best-of", fallback.bestOf()),
                rounds == null ? fallback.timeLimitMinutes()
                        : rounds.getInt("time-limit-minutes", fallback.timeLimitMinutes()),
                timeout,
                keep == null ? fallback.keepInvulnerable()
                        : keep.getBoolean("invulnerable", fallback.keepInvulnerable()),
                keep == null ? fallback.keepArmRadius()
                        : keep.getDouble("arm-radius", fallback.keepArmRadius()),
                keep == null ? fallback.protectKeepBlocks()
                        : keep.getBoolean("protect-blocks", fallback.protectKeepBlocks()));
    }

    /** 战国模式的对局骨架规则（{@code sengoku.yml}）。 */
    public SengokuRules sengokuRules() {
        return sengokuRules;
    }

    /** 解析 {@code sengoku-towers.yml}；逐项回退，与 sengoku.yml 同口径。 */
    private void parseTowers(YamlConfiguration yaml) {
        TowerRules fallback = TowerRules.defaults();
        if (yaml == null) {
            towerRules = fallback;
            return;
        }
        ConfigurationSection towers = yaml.getConfigurationSection("towers");
        ConfigurationSection guards = yaml.getConfigurationSection("guards");
        ConfigurationSection bell = yaml.getConfigurationSection("bell");
        TowerRules.CaptureMode mode = fallback.captureMode();
        if (towers != null) {
            String raw = towers.getString("capture-mode");
            if (raw != null) {
                mode = "instant".equalsIgnoreCase(raw.trim())
                        ? TowerRules.CaptureMode.INSTANT : TowerRules.CaptureMode.CHANNEL;
            }
        }
        towerRules = new TowerRules(
                towers == null ? fallback.count() : towers.getInt("count", fallback.count()),
                mode,
                towers == null ? fallback.captureSeconds()
                        : towers.getDouble("capture-seconds", fallback.captureSeconds()),
                towers == null ? fallback.decayPerSecond()
                        : towers.getDouble("decay-per-second", fallback.decayPerSecond()),
                towers == null ? fallback.contestLock()
                        : towers.getBoolean("contest-lock", fallback.contestLock()),
                towers == null ? fallback.guardRespawnSeconds()
                        : towers.getInt("guard-respawn-seconds", fallback.guardRespawnSeconds()),
                parseGuard(guards == null ? null : guards.getConfigurationSection("ox-demon"),
                        fallback.oxDemon()),
                parseGuard(guards == null ? null : guards.getConfigurationSection("shrimp-crab"),
                        fallback.shrimpCrab()),
                bell == null ? fallback.bellMaterial() : bell.getString("material", fallback.bellMaterial()),
                bell == null ? fallback.bellSound() : bell.getString("sound", fallback.bellSound()),
                bell == null ? fallback.bellParticle() : bell.getString("particle", fallback.bellParticle()));
    }

    /** 单个守卫规格的解析；整段缺失时用默认值。 */
    private TowerRules.GuardSpec parseGuard(ConfigurationSection section, TowerRules.GuardSpec fallback) {
        if (section == null) {
            return fallback;
        }
        return new TowerRules.GuardSpec(
                section.getString("entity", fallback.entity()),
                section.getString("display", fallback.display()),
                section.getInt("count", fallback.count()),
                section.getDouble("health", fallback.health()),
                section.getDouble("damage", fallback.damage()),
                section.getInt("speed-amplifier", fallback.speedAmplifier()),
                section.getDouble("patrol-radius", fallback.patrolRadius()),
                section.getDouble("aggro-radius", fallback.aggroRadius()));
    }

    /** 战国模式的箭楼规则（{@code sengoku-towers.yml}）。 */
    public TowerRules towerRules() {
        return towerRules;
    }

    /** 解析 {@code sengoku-siege.yml}；逐项回退，与前两份同口径。 */
    private void parseSiege(YamlConfiguration yaml) {
        SiegeRules fallback = SiegeRules.defaults();
        if (yaml == null) {
            siegeRules = fallback;
            return;
        }
        ConfigurationSection breaker = yaml.getConfigurationSection("siege-breaker");
        ConfigurationSection pad = yaml.getConfigurationSection("jump-pad");
        ConfigurationSection launch = pad == null ? null : pad.getConfigurationSection("launch");
        SiegeRules.JumpPadSpec padFallback = fallback.jumpPad();
        SiegeRules.JumpPadSpec padSpec = new SiegeRules.JumpPadSpec(
                pad == null ? padFallback.mode() : pad.getString("mode", padFallback.mode()),
                pad == null ? padFallback.target() : pad.getString("target", padFallback.target()),
                pad == null ? padFallback.specifiedTower()
                        : pad.getInt("specified-tower", padFallback.specifiedTower()),
                pad == null ? padFallback.cooldownSeconds()
                        : pad.getInt("cooldown-seconds", padFallback.cooldownSeconds()),
                launch == null ? padFallback.power() : launch.getDouble("power", padFallback.power()),
                launch == null ? padFallback.upward() : launch.getDouble("upward", padFallback.upward()),
                pad == null ? padFallback.fallImmunityTicks()
                        : pad.getInt("fall-immunity-ticks", padFallback.fallImmunityTicks()),
                pad == null ? padFallback.particle() : pad.getString("particle", padFallback.particle()),
                pad == null ? padFallback.sound() : pad.getString("sound", padFallback.sound()),
                pad == null ? padFallback.resistanceTicks()
                        : pad.getInt("resistance-ticks", padFallback.resistanceTicks()));
        siegeRules = new SiegeRules(
                breaker == null ? fallback.material() : breaker.getString("material", fallback.material()),
                breaker == null ? fallback.display() : breaker.getString("name", fallback.display()),
                breaker == null ? fallback.glow() : breaker.getBoolean("glow", fallback.glow()),
                breaker == null ? fallback.spawnDistanceFromKeep()
                        : breaker.getDouble("spawn-distance-from-keep", fallback.spawnDistanceFromKeep()),
                breaker == null ? fallback.pickRadius()
                        : breaker.getDouble("pick-radius", fallback.pickRadius()),
                breaker == null ? fallback.armTimeSeconds()
                        : breaker.getDouble("arm-time-seconds", fallback.armTimeSeconds()),
                breaker == null ? fallback.onePerTeam()
                        : breaker.getBoolean("one-per-team", fallback.onePerTeam()),
                breaker == null ? fallback.respawnOnRecapture()
                        : breaker.getBoolean("respawn-on-recapture", fallback.respawnOnRecapture()),
                breaker == null ? fallback.allowedCharacters()
                        : breaker.getStringList("allowed-characters"),
                breaker == null ? fallback.indestructible()
                        : breaker.getBoolean("indestructible", fallback.indestructible()),
                breaker == null ? fallback.undroppable()
                        : breaker.getBoolean("undroppable", fallback.undroppable()),
                padSpec);
    }

    /** 战国模式的击破器与跳跃台规则（{@code sengoku-siege.yml}）。 */
    public SiegeRules siegeRules() {
        return siegeRules;
    }

    /** 解析 {@code sengoku-minions.yml}；键全部在根层，逐项回退。 */
    private void parseMidMinions(YamlConfiguration yaml) {
        MidMinionRules fallback = MidMinionRules.defaults();
        if (yaml == null) {
            midMinionRules = fallback;
            return;
        }
        midMinionRules = new MidMinionRules(
                yaml.getString("entity", fallback.entity()),
                yaml.getString("display", fallback.display()),
                yaml.getInt("interval-seconds", fallback.intervalSeconds()),
                yaml.getInt("per-spawn", fallback.perSpawn()),
                yaml.getInt("max-alive", fallback.maxAlive()),
                yaml.getInt("shard-tick", fallback.shardTick()),
                yaml.getDouble("health", fallback.health()),
                yaml.getDouble("damage", fallback.damage()));
    }

    /** 战国模式的中地小兵规则（{@code sengoku-minions.yml}）。 */
    public MidMinionRules midMinionRules() {
        return midMinionRules;
    }

    /** 解析 {@code sengoku-energy.yml}，含逐角色覆盖表。 */
    private void parseEnergy(YamlConfiguration yaml) {
        EnergyRules fallback = EnergyRules.defaults();
        if (yaml == null) {
            energyRules = fallback;
            return;
        }
        ConfigurationSection ultimate = yaml.getConfigurationSection("ultimate");
        EnergyRules.UltimateSpec defaultSpec = parseUltimate(
                ultimate == null ? null : ultimate.getConfigurationSection("default"),
                fallback.defaultUltimate());
        // 逐角色覆盖：键是角色 id，值缺哪项就继承 default（这样只改一个数字也能写得下）
        Map<String, EnergyRules.UltimateSpec> perCharacter = EnergyRules.newCharacterMap();
        if (ultimate != null) {
            for (String key : ultimate.getKeys(false)) {
                if ("default".equalsIgnoreCase(key)) {
                    continue;
                }
                perCharacter.put(key.trim().toLowerCase(java.util.Locale.ROOT),
                        parseUltimate(ultimate.getConfigurationSection(key), defaultSpec));
            }
        }
        energyRules = new EnergyRules(
                yaml.getInt("max", fallback.max()),
                yaml.getInt("per-minion", fallback.perMinion()),
                yaml.getInt("per-elite-minion", fallback.perEliteMinion()),
                parseEnergyDisplay(yaml.getString("display"), fallback.display()),
                yaml.getString("ultimate-slot", fallback.ultimateSlot()),
                defaultSpec, perCharacter);
    }

    private EnergyRules.UltimateSpec parseUltimate(ConfigurationSection section,
                                                   EnergyRules.UltimateSpec fallback) {
        if (section == null) {
            return fallback;
        }
        return new EnergyRules.UltimateSpec(
                section.getString("type", fallback.type()),
                section.getDouble("damage", fallback.damage()),
                section.getDouble("radius", fallback.radius()),
                section.getDouble("knockback", fallback.knockback()),
                section.getDouble("launch", fallback.launch()),
                section.getInt("slow-duration", fallback.slowDuration()),
                section.getInt("slow-amplifier", fallback.slowAmplifier()),
                section.getString("particle", fallback.particle()),
                section.getString("sound", fallback.sound()));
    }

    private EnergyRules.Display parseEnergyDisplay(String raw, EnergyRules.Display fallback) {
        if (raw == null) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bossbar" -> EnergyRules.Display.BOSSBAR;
            case "none" -> EnergyRules.Display.NONE;
            case "actionbar" -> EnergyRules.Display.ACTIONBAR;
            default -> fallback;
        };
    }

    /** 战国模式的能量槽与必杀技规则（{@code sengoku-energy.yml}）。 */
    public EnergyRules energyRules() {
        return energyRules;
    }

    /** 当前对局模式（config.yml 的 {@code match.mode}），认不出回退 PVP。 */
    public SengokuMode matchMode() {
        return SengokuMode.parse(plugin.getConfig().getString("match.mode", "pvp"), SengokuMode.PVP);
    }

    /**
     * 写回<b>全局默认</b>模式（config.yml 的 {@code match.mode}）。
     *
     * <p>只影响之后<b>新建</b>的房间——模式在房间创建时快照成字段，
     * 已经在跑的对局不会因为一次切换而变异。要改单个房间用
     * {@code /taketori match mode <模式> [房间id]}。</p>
     */
    public void setMatchMode(SengokuMode mode) {
        plugin.getConfig().set("match.mode", mode.key());
        plugin.saveConfig();
    }

    public WeaponManager weapons() {
        return weapons;
    }

    public CharacterManager characters() {
        return characters;
    }

    public Messages messages() {
        return messages;
    }

    public int configVersion() {
        return configVersion;
    }

    public boolean debug() {
        return debug;
    }

    public void setDebug(boolean value) {
        this.debug = value;
    }

    /** 运行时切换 Q 键行为（不落盘；永久生效需要改 config.yml 后 reload）。 */
    public void setQMode(String value) {
        if (value != null && !value.isBlank()) {
            this.qMode = value;
        }
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean particles() {
        return particles;
    }

    public boolean sounds() {
        return sounds;
    }

    /** 冷却条是否用屏幕上方进度条（骑马时马血条那种位置）。 */
    public boolean cooldownBossBar() {
        return "bossbar".equalsIgnoreCase(cooldownDisplay) || "both".equalsIgnoreCase(cooldownDisplay);
    }

    /** 冷却提示是否用底部文字加进度字符。 */
    public boolean cooldownActionBar() {
        return "actionbar".equalsIgnoreCase(cooldownDisplay) || "both".equalsIgnoreCase(cooldownDisplay);
    }

    // ---- 大厅与队伍（低频读取，直接取配置，不再维护第二份缓存）----

    /** 每队人数上限，用于随机分队的满员判定。 */
    public int matchTeamSize() {
        return Math.max(1, plugin.getConfig().getInt("match.team-size", 3));
    }

    /** 队列人数达到多少自动开局（0 = 只允许手动开局）。 */
    public int lobbyAutoStartPlayers() {
        return plugin.getConfig().getInt("lobby.auto-start-players", 6);
    }

    /** 大厅内是否禁止掉血。 */
    public boolean lobbyProtect() {
        return plugin.getConfig().getBoolean("lobby.protect", true);
    }

    /** 对局结束后是否把参赛者送回大厅。 */
    public boolean lobbyReturnAfterMatch() {
        return plugin.getConfig().getBoolean("lobby.return-after-match", true);
    }

    /**
     * 回程落点：true = 回到进房间前所在的位置，false = 回到大厅出生点。
     *
     * <p>多世界服务器上「回原位置」才有意义（玩家可能从资源世界被拽进大厅）；
     * 单世界服务器关掉它可以让所有人统一落在大厅出生点，避免对局结束后
     * 有人被送回刷怪塔顶上。原位置所在世界已被卸载时一律回落到大厅出生点。</p>
     */
    public boolean lobbyReturnToEntry() {
        return plugin.getConfig().getBoolean("lobby.return-to-entry", true);
    }

    // ---- CS 式包点 ----

    /** 拆包需要的点击次数（至少 1 下）。 */
    public int bombClicks() {
        return Math.max(1, plugin.getConfig().getInt("bomb.defuse-clicks", 10));
    }

    /** 拆包进度中断多少秒后复位（至少 1 秒）。 */
    public int bombResetSeconds() {
        return Math.max(1, plugin.getConfig().getInt("bomb.reset-seconds", 7));
    }

    /** 玩家进服是否自动传送到大厅。 */
    public boolean lobbyTeleportOnJoin() {
        return plugin.getConfig().getBoolean("lobby.teleport-on-join", true);
    }

    // ---- 房间等待区（BedWars 式匹配）----

    /** 等待人数达到多少开始倒计时（至少 1 人）。 */
    public int waitingMinPlayers() {
        return Math.max(1, plugin.getConfig().getInt("waiting.min-players", 2));
    }

    /** 达到最低人数后的倒计时秒数（至少 1 秒）。 */
    public int waitingCountdownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("waiting.countdown-seconds", 90));
    }

    /** 房间满员后的短倒计时秒数（0 也允许：满员立即开局）。 */
    public int waitingFullCountdownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("waiting.full-countdown-seconds", 5));
    }

    /** 人数过半场后的半挡倒计时秒数（0 = 关闭过半压缩）。 */
    public int waitingHalfCountdownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("waiting.half-countdown-seconds", 30));
    }

    /** 开局后出生点玻璃笼保护秒数（0 = 不用笼子，立即开战）。 */
    public int waitingCageHoldSeconds() {
        return Math.max(0, plugin.getConfig().getInt("waiting.cage-hold-seconds", 3));
    }

    /** 对局结束后在房间停留多少秒再统一回大厅。 */
    public int waitingEndDelaySeconds() {
        return Math.max(0, plugin.getConfig().getInt("waiting.end-delay-seconds", 5));
    }

    /** 玻璃笼方块材质（原版 Material 名，非法值由建笼方回落到 GLASS）。 */
    public String waitingCageMaterial() {
        return plugin.getConfig().getString("waiting.cage-material", "GLASS");
    }

    /**
     * PVE 房间满员人数：配置 {@code <=0} 时回落为每队人数上限（match.team-size）。
     */
    public int waitingPveFullPlayers() {
        int configured = plugin.getConfig().getInt("waiting.pve-full-players", 0);
        return configured > 0 ? configured : matchTeamSize();
    }

    /** 虚空拉回判定偏移：等待者 Y 低于 waitSpawn.y + 该值时拉回（通常为负数）。 */
    public int waitingVoidYOffset() {
        return plugin.getConfig().getInt("waiting.void-y-offset", -10);
    }

    /** 等待区是否启用完全保护。 */
    public boolean waitingProtect() {
        return plugin.getConfig().getBoolean("waiting.protect", true);
    }

    // ---- 动态房间（月宫制）----

    /** 同时存在的房间数上限（含创建中的），超过时拒绝创建。 */
    public int roomMaxRooms() {
        return Math.max(1, plugin.getConfig().getInt("room.max-rooms", 8));
    }

    /** 每个玩家同时拥有的房间数上限（防单人/小号占满全部额度）。 */
    public int roomMaxRoomsPerPlayer() {
        return Math.max(1, plugin.getConfig().getInt("room.max-rooms-per-player", 1));
    }

    /**
     * 近战蓄力门控（0~1）：攻击冷却蓄力低于该值时保留原版轻击、不触发左键技能
     * （0 = 关闭门控，任何蓄力都触发技能）。
     */
    public double meleeChargeGate() {
        return Math.max(0.0D, Math.min(1.0D, plugin.getConfig().getDouble("combat.melee-charge-gate", 0.9D)));
    }

    /** 对局期间是否给参赛者自动续 Saturation（默认开；关闭后对局内饥饿照常消耗）。 */
    public boolean matchSaturation() {
        return plugin.getConfig().getBoolean("combat.match-saturation", true);
    }

    /** 房间世界名前缀（房间世界 = 前缀 + 自增序号）。 */
    public String roomWorldPrefix() {
        return plugin.getConfig().getString("room.world-prefix", "kassen_");
    }

    /** 默认模板名（快速加入无房自动建房时优先使用）。 */
    public String roomDefaultTemplate() {
        return plugin.getConfig().getString("room.default-template", "tsukuyomi");
    }

    /** 等待房全员离线多少秒后自动解散回收世界（下限 10 秒）。 */
    public int roomEmptyDisposeSeconds() {
        return Math.max(10, plugin.getConfig().getInt("room.empty-dispose-seconds", 60));
    }

    /** 对局缺人宽限期（秒）：超时无人补位缺人队判负（0 = 禁用自动判负）。 */
    public int roomUnderstaffedGraceSeconds() {
        return Math.max(0, plugin.getConfig().getInt("room.understaffed-grace-seconds", 60));
    }

    /** 断线重连时限（秒）：对局中掉线者在时限内重连回原房原队（0 = 关闭重连）。 */
    public int roomRejoinSeconds() {
        return Math.max(0, plugin.getConfig().getInt("room.rejoin-seconds", 300));
    }

    /** 派对人数上限（含房主；3v3 模式下 3 人即满队）。 */
    public int partyMaxSize() {
        return Math.max(2, plugin.getConfig().getInt("party.max-size", 3));
    }

    public boolean soulbound() {
        return soulbound;
    }

    public boolean allowDrop() {
        return allowDrop;
    }

    public boolean autoGiveOnJoin() {
        return autoGiveOnJoin;
    }

    public String qMode() {
        return qMode;
    }

    // ---- 输入与出生增益 ----

    /**
     * 第三槽技能的触发键原始值（可多选，见 {@link ThirdSlotTrigger}）。
     * 默认 {@code double-sneak}（双击潜行）：F 键在部分服务器会收不到事件，
     * 潜行 + 右键则会被原版"对着方块"的分支吞掉，双击潜行不受这两点影响。
     */
    public String shiftRightTrigger() {
        return plugin.getConfig().getString("input.shift-right-trigger", "double-sneak");
    }

    /** 第三槽技能的触发方式集合（解析后的结果）。 */
    public java.util.Set<ThirdSlotTrigger> thirdSlotTriggers() {
        return ThirdSlotTrigger.parse(shiftRightTrigger());
    }

    /** 开局 / 复活的出生增益持续秒数（0 = 关闭）。 */
    public int spawnBuffSeconds() {
        return Math.max(0, plugin.getConfig().getInt("combat.spawn-buff.duration-seconds", 10));
    }

    /** 出生增益的效果列表（每项含 type / amplifier）。 */
    public List<Map<?, ?>> spawnBuffEffects() {
        return plugin.getConfig().getMapList("combat.spawn-buff.effects");
    }

    /** 第三槽技能触发成功时是否附带额外增益（默认 2 秒跳跃提升 V）。 */
    public boolean thirdSlotBuffEnabled() {
        return plugin.getConfig().getBoolean("combat.third-slot-buff.enabled", true);
    }

    /** 第三槽附加增益的药水效果名（原版名，例如 JUMP_BOOST）。 */
    public String thirdSlotBuffType() {
        return plugin.getConfig().getString("combat.third-slot-buff.type", "JUMP_BOOST");
    }

    /** 第三槽附加增益的等级（0 = I 级；4 = V 级）。 */
    public int thirdSlotBuffAmplifier() {
        return Math.max(0, plugin.getConfig().getInt("combat.third-slot-buff.amplifier", 4));
    }

    /** 第三槽附加增益的持续 tick（40 = 2 秒）。 */
    public int thirdSlotBuffTicks() {
        return Math.max(0, plugin.getConfig().getInt("combat.third-slot-buff.ticks", 40));
    }

    // ---- 开局装备（护甲）----

    /** 绑定角色 / 补发武器时是否给玩家穿一套护甲。 */
    public boolean loadoutArmorEnabled() {
        return plugin.getConfig().getBoolean("loadout.armor-enabled", true);
    }

    /** 开局护甲的套装前缀（IRON → IRON_HELMET / IRON_CHESTPLATE …）。 */
    public String loadoutArmorMaterial() {
        return plugin.getConfig().getString("loadout.armor-material", "IRON");
    }

    /** 开局护甲的保护附魔等级（0 = 不附魔）。 */
    public int loadoutArmorProtection() {
        return Math.max(0, plugin.getConfig().getInt("loadout.armor-protection", 2));
    }

    // ---- 管理用具：选区锄 ----

    /** 选区锄是否可用（关掉后这把锄头就是普通锄头）。 */
    public boolean setupWandEnabled() {
        return plugin.getConfig().getBoolean("setup-wand.enabled", true);
    }

    /** 选区锄绑定的物品名（默认下界合金锄）。 */
    public String setupWandMaterial() {
        return plugin.getConfig().getString("setup-wand.material", "NETHERITE_HOE");
    }

    /** 手持选区锄时是否用粒子描出选区边框。 */
    public boolean setupWandOutline() {
        return plugin.getConfig().getBoolean("setup-wand.outline-particles", true);
    }

    /** 有管理权限的玩家进服时是否自动发一把选区锄。 */
    public boolean setupWandGiveOnJoin() {
        return plugin.getConfig().getBoolean("setup-wand.give-on-join", false);
    }

    public List<Integer> weaponSlots() {
        return weaponSlots;
    }

    // ---- PVE：月人入侵 ----

    /**
     * PVE 设置快照（大波次 / 精英随人数变强 / 保卫据点）。
     *
     * <p>难度档的数值写在 {@code pve.outpost.difficulties.<档位>} 下，
     * 取不到时回落到 {@link PveSettings#defaults()} 的同一份默认值。</p>
     */
    public PveSettings pveSettings() {
        var cfg = plugin.getConfig();
        PveSettings fallback = PveSettings.defaults();
        String difficulty = PveSettings.normalizeDifficulty(cfg.getString("pve.difficulty", PveSettings.NORMAL));
        String tier = "pve.outpost.difficulties." + difficulty + ".";
        return new PveSettings(
                difficulty,
                cfg.getBoolean("pve.big-waves.enabled", fallback.bigWavesEnabled()),
                Math.max(0, cfg.getInt("pve.big-waves.count", fallback.bigWaveCount())),
                Math.max(5, cfg.getInt("pve.big-waves.interval-seconds", fallback.bigWaveIntervalSeconds())),
                Math.max(0, cfg.getInt("pve.big-waves.start-delay-seconds", fallback.bigWaveStartDelaySeconds())),
                Math.max(0, cfg.getInt(tier + "elites-per-wave",
                        cfg.getInt("pve.big-waves.elites-per-wave", fallback.elitesPerWave()))),
                cfg.getBoolean("pve.big-waves.announce", fallback.announce()),
                cfg.getBoolean("pve.elite-scaling.enabled", fallback.scalingEnabled()),
                Math.max(0, cfg.getInt("pve.elite-scaling.buffs-per-player", fallback.buffsPerPlayer())),
                Math.max(0, cfg.getInt("pve.elite-scaling.max-buff-amplifier", fallback.maxBuffAmplifier())),
                cfg.getBoolean("pve.outpost.enabled", fallback.outpostEnabled()),
                cfg.getString("pve.outpost.name", fallback.outpostName()),
                Math.max(1.0D, cfg.getDouble("pve.outpost.radius", fallback.outpostRadius())),
                cfg.getBoolean("pve.outpost.end-match-on-destroyed", fallback.endMatchOnOutpostDestroyed()),
                Math.max(1.0D, cfg.getDouble(tier + "health", fallback.outpostHealth())),
                Math.max(0.0D, cfg.getDouble(tier + "damage-per-second", fallback.outpostDamagePerSecond())),
                Math.max(0, cfg.getInt(tier + "elite-buff-bonus", fallback.eliteBuffBonus())));
    }

    /** 写回难度档（对局未开始时用；下一局生效）。 */
    public void setPveDifficulty(String difficulty) {
        plugin.getConfig().set("pve.difficulty", PveSettings.normalizeDifficulty(difficulty));
        plugin.saveConfig();
    }

    /**
     * 每队基地数量的配置值：正整数（例如 {@code 3}）或 {@code auto}。
     *
     * <p>{@code auto} 表示不设上限、以实际划定的基地为准；解析与实际校验在
     * {@code ArenaManager} 里做（那里才知道当前配了几个）。</p>
     */
    public String baseCountPerTeam() {
        return plugin.getConfig().getString("base.count-per-team", "3");
    }

    // ---- 多世界兼容 ----

    /**
     * 消息播报范围：{@code world}（只发给"消息所属世界"的玩家，默认）
     * 或 {@code all}（全服，多世界下所有世界都能看到，旧行为）。
     */
    public String broadcastScope() {
        return plugin.getConfig().getString("worlds.broadcast-scope", "world");
    }

    /**
     * 进服"送大厅"的世界白名单。
     *
     * <p>空列表 = 只接管大厅出生点所在的世界（默认）；含 {@code *} = 所有世界（旧行为）；
     * 也可以显式列出世界名。判定逻辑见 {@code WorldScope#allowsTakeover}。</p>
     */
    public List<String> lobbyTakeoverWorlds() {
        return plugin.getConfig().getStringList("lobby.takeover-worlds");
    }

    // ---- 菜单时钟（右键打开玩家菜单的道具）----

    /** 菜单时钟是否启用。 */
    public boolean menuClockEnabled() {
        return plugin.getConfig().getBoolean("menu-clock.enabled", true);
    }

    /** 菜单时钟的材质名（原版 Material）。 */
    public String menuClockMaterial() {
        return plugin.getConfig().getString("menu-clock.material", "CLOCK");
    }

    /** 菜单时钟的显示名（MiniMessage）。 */
    public String menuClockName() {
        return plugin.getConfig().getString("menu-clock.name", "<aqua>菜单时钟</aqua>");
    }

    /** 菜单时钟的 Lore；没配时给一行默认说明。 */
    public List<String> menuClockLore() {
        List<String> lore = plugin.getConfig().getStringList("menu-clock.lore");
        return lore.isEmpty() ? List.of("<gray>右键打开玩家菜单", "<dark_gray>与 /taketori menu 等价") : lore;
    }

    /** 进服是否自动发一个菜单时钟。 */
    public boolean menuClockGiveOnJoin() {
        return plugin.getConfig().getBoolean("menu-clock.give-on-join", true);
    }

    // ---- 对局功能壳（房间内右键打开「退出对局 / 投降」GUI 的鹦鹉螺壳）----

    /** 功能壳是否启用；关掉后房间内不再发壳，退出/投降只能走指令。 */
    public boolean roomShellEnabled() {
        return plugin.getConfig().getBoolean("room-shell.enabled", true);
    }

    /** 功能壳的材质（原版 Material）；写错时回退到 NAUTILUS_SHELL 并点名。 */
    public org.bukkit.Material roomShellMaterial() {
        String name = plugin.getConfig().getString("room-shell.material", "NAUTILUS_SHELL");
        org.bukkit.Material material = name == null || name.isBlank()
                ? null : org.bukkit.Material.matchMaterial(name.trim().toUpperCase(java.util.Locale.ROOT));
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning("room-shell.material 无法解析：" + name + " → 回退到 NAUTILUS_SHELL");
            return org.bukkit.Material.NAUTILUS_SHELL;
        }
        return material;
    }

    /** 功能壳的显示名（MiniMessage）。 */
    public String roomShellName() {
        return plugin.getConfig().getString("room-shell.name", "<aqua>对局面板 <white>鹦鹉螺壳");
    }

    /** 功能壳的 Lore；没配时给默认说明。 */
    public List<String> roomShellLore() {
        List<String> lore = plugin.getConfig().getStringList("room-shell.lore");
        return lore.isEmpty()
                ? List.of("<gray>右键打开：退出对局 / 队伍投降", "<dark_gray>仅在本局房间内可用")
                : lore;
    }

    /** 离开对局是否保留重连窗口（窗口内可用 /taketori rejoin 回到原队）。 */
    public boolean roomLeaveRejoinWindow() {
        return plugin.getConfig().getBoolean("room-shell.leave.rejoin-window", true);
    }
}
