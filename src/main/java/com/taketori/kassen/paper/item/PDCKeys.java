package com.taketori.kassen.paper.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PDC 键定义。身份识别的唯一依据（设计稿「识别原则」：
 * 绝不依赖显示名或 Lore，玩家改名改 Lore 后插件仍要能识别）。
 *
 * <p>除了武器身份，这里也存放"由技能发射的弹体/箭矢"的参数——弹体必须在飞行途中
 * 带着伤害、半径、衰减、点燃、定身、易伤标记等数值，否则命中时无从知道该结算什么。</p>
 */
public final class PDCKeys {

    /** 物品数据版本，将来做物品迁移（ItemMigrator）时靠它判断。 */
    public static final int CURRENT_DATA_VERSION = 1;

    private static NamespacedKey characterId;
    private static NamespacedKey weaponId;
    private static NamespacedKey mode;
    private static NamespacedKey instanceId;
    private static NamespacedKey owner;
    private static NamespacedKey soulbound;
    private static NamespacedKey dataVersion;
    private static NamespacedKey setupWand;
    /** 菜单时钟：右键打开玩家菜单的道具（与武器无关，人人可用）。 */
    private static NamespacedKey menuClock;

    /**
     * 对局功能壳（鹦鹉螺壳）：右键打开「退出对局 / 投降」GUI。
     *
     * <p>只在房间内发放（进房即给，结算随背包快照一起还原而消失）。与菜单时钟同样
     * 只认 PDC，不认显示名 / Lore，玩家改名不影响识别。</p>
     */
    private static NamespacedKey roomShell;

    private static NamespacedKey projDamage;
    private static NamespacedKey projRadius;
    private static NamespacedKey projIgnite;
    private static NamespacedKey projIgniteTicks;
    private static NamespacedKey projSlowDuration;
    private static NamespacedKey projSlowAmplifier;
    private static NamespacedKey projKnockback;
    private static NamespacedKey projMinFalloff;
    private static NamespacedKey projHitSound;
    private static NamespacedKey projHitParticle;
    private static NamespacedKey projShooter;
    private static NamespacedKey projFreezeTicks;

    private static NamespacedKey arrowMultiplier;
    private static NamespacedKey arrowMarkBonus;
    private static NamespacedKey arrowMarkTicks;
    private static NamespacedKey arrowHitRadius;
    private static NamespacedKey arrowHitRatio;
    private static NamespacedKey arrowDebuffs;
    private static NamespacedKey arrowDebuffTicks;
    private static NamespacedKey arrowDebuffAmplifier;
    private static NamespacedKey arrowDebuffChance;

    /** 召唤物归属（UUID 字符串）：用于友伤拦截、同队判定、结算不计分与房间清理。 */
    private static NamespacedKey summonedOwner;

    /** 弹体的兑现倍率：命中时目标身上有前置状态则放大伤害。 */
    private static NamespacedKey projEchoBonus;
    /** 弹体命中时给目标挂的易伤标记强度 / 时长。 */
    private static NamespacedKey projMarkBonus;
    private static NamespacedKey projMarkTicks;
    /** 弹体命中时给目标挂的破甲强度。 */
    private static NamespacedKey projArmorPierce;
    /** 弹体命中时挂的破甲时长（tick）——与 mark-ticks 分开，两者可以独立配。 */
    private static NamespacedKey projArmorPierceTicks;

    /**
     * 箭楼守卫所属的箭楼序号（整数）。
     *
     * <p>守卫靠它反查自己属于哪座箭楼——需求要求"清空守卫才能占领"，
     * 所以必须能随时统计某座箭楼还剩几只守卫；用 PDC 而不是维护一张
     * {@code Map<实体, 序号>} 是为了让守卫跨区块卸载/重载后仍然认得出自己。</p>
     */
    private static NamespacedKey towerGuardIndex;

    /**
     * 大将击破器的归属队伍（red / blue）。
     *
     * <p>与武器的 {@code soulbound} 是两套语义：那边按<b>个人</b>归属（谁绑的谁能用），
     * 这边按<b>队伍</b>归属——队友都能捡、敌方捡不走。所以不能复用武器身份，
     * 否则击破器会被当成"某个人的武器"。</p>
     */
    private static NamespacedKey sengokuBreakerTeam;

    /**
     * 中地小兵标记（字节 1）。
     *
     * <p>用来把它与月人、箭楼守卫、召唤物区分开：只有它才给能量，
     * 而且它不该参与"击杀月人"的统计。</p>
     */
    private static NamespacedKey midMinion;

    /**
     * 拆弹钳标记（字节 1）。
     *
     * <p>商店买的剪刀与普通剪刀外观一致，只能靠这个键认出来：拆弹读条要按它
     * 决定用 {@code bomb.defuse-seconds-clipper} 还是 {@code bomb.defuse-seconds-bare}，
     * 回合结束的清理也靠它把商店货收走。</p>
     */
    private static NamespacedKey defuseClipper;

    /**
     * 地雷标记（字节 1）。
     *
     * <p>埋在地上的绊线钩靠它认身份——只有带这个键的绊线钩才会被当成地雷去检测触发，
     * 玩家自己摆的绊线钩不受影响。</p>
     */
    private static NamespacedKey landMine;

    /**
     * 地雷的埋设队伍（red / blue）。
     *
     * <p>触发判定要区分敌我：自己队埋的雷不该炸自己人，所以得把埋雷时的队伍记在实体上。
     * 存 {@code TeamId.key()} 而不是枚举名，是为了和其它队伍标记保持同一套字符串。</p>
     */
    private static NamespacedKey landMineOwner;

    /**
     * 商人 NPC 的归属队伍（red / blue）。
     *
     * <p>商人 NPC 是带名字的实体，重开一局时按这个键把旧 NPC 清掉再生成新的，
     * 避免一局一局叠出一排商人。值同样是 {@code TeamId.key()}。</p>
     */
    private static NamespacedKey shopNpc;

    private PDCKeys() {
    }

    public static void init(Plugin plugin) {
        characterId = new NamespacedKey(plugin, "character_id");
        weaponId = new NamespacedKey(plugin, "weapon_id");
        mode = new NamespacedKey(plugin, "mode");
        instanceId = new NamespacedKey(plugin, "instance_id");
        owner = new NamespacedKey(plugin, "owner");
        soulbound = new NamespacedKey(plugin, "soulbound");
        dataVersion = new NamespacedKey(plugin, "data_version");
        setupWand = new NamespacedKey(plugin, "setup_wand");
        menuClock = new NamespacedKey(plugin, "menu_clock");
        roomShell = new NamespacedKey(plugin, "room_shell");

        projDamage = new NamespacedKey(plugin, "proj_damage");
        projRadius = new NamespacedKey(plugin, "proj_radius");
        projIgnite = new NamespacedKey(plugin, "proj_ignite");
        projIgniteTicks = new NamespacedKey(plugin, "proj_ignite_ticks");
        projSlowDuration = new NamespacedKey(plugin, "proj_slow_duration");
        projSlowAmplifier = new NamespacedKey(plugin, "proj_slow_amplifier");
        projKnockback = new NamespacedKey(plugin, "proj_knockback");
        projMinFalloff = new NamespacedKey(plugin, "proj_min_falloff");
        projHitSound = new NamespacedKey(plugin, "proj_hit_sound");
        projHitParticle = new NamespacedKey(plugin, "proj_hit_particle");
        projShooter = new NamespacedKey(plugin, "proj_shooter");
        projFreezeTicks = new NamespacedKey(plugin, "proj_freeze_ticks");

        arrowMultiplier = new NamespacedKey(plugin, "arrow_multiplier");
        arrowMarkBonus = new NamespacedKey(plugin, "arrow_mark_bonus");
        arrowMarkTicks = new NamespacedKey(plugin, "arrow_mark_ticks");
        arrowHitRadius = new NamespacedKey(plugin, "arrow_hit_radius");
        arrowHitRatio = new NamespacedKey(plugin, "arrow_hit_ratio");

        arrowDebuffs = new NamespacedKey(plugin, "arrow_debuffs");
        arrowDebuffTicks = new NamespacedKey(plugin, "arrow_debuff_ticks");
        arrowDebuffAmplifier = new NamespacedKey(plugin, "arrow_debuff_amplifier");
        arrowDebuffChance = new NamespacedKey(plugin, "arrow_debuff_chance");

        summonedOwner = new NamespacedKey(plugin, "summoned_owner");
        towerGuardIndex = new NamespacedKey(plugin, "tower_guard_index");
        sengokuBreakerTeam = new NamespacedKey(plugin, "sengoku_breaker_team");
        midMinion = new NamespacedKey(plugin, "mid_minion");
        defuseClipper = new NamespacedKey(plugin, "defuse_clipper");
        landMine = new NamespacedKey(plugin, "land_mine");
        landMineOwner = new NamespacedKey(plugin, "land_mine_owner");
        shopNpc = new NamespacedKey(plugin, "shop_npc");

        projEchoBonus = new NamespacedKey(plugin, "proj_echo_bonus");
        projMarkBonus = new NamespacedKey(plugin, "proj_mark_bonus");
        projMarkTicks = new NamespacedKey(plugin, "proj_mark_ticks");
        projArmorPierce = new NamespacedKey(plugin, "proj_armor_pierce");
        projArmorPierceTicks = new NamespacedKey(plugin, "proj_armor_pierce_ticks");
    }

    public static NamespacedKey characterId() {
        return characterId;
    }

    public static NamespacedKey weaponId() {
        return weaponId;
    }

    /** 展示用冗余：mode 的真源在 PlayerProfile（计划 §3.3）。 */
    public static NamespacedKey mode() {
        return mode;
    }

    public static NamespacedKey instanceId() {
        return instanceId;
    }

    public static NamespacedKey owner() {
        return owner;
    }

    public static NamespacedKey soulbound() {
        return soulbound;
    }

    public static NamespacedKey dataVersion() {
        return dataVersion;
    }

    /** 选区锄标记：管理员划场地用的工具，不属于武器（有它才不会被当普通锄头）。 */
    public static NamespacedKey setupWand() {
        return setupWand;
    }

    /** 菜单时钟的标记键。 */
    public static NamespacedKey menuClock() {
        return menuClock;
    }

    /** 对局功能壳（鹦鹉螺壳）的标记键。 */
    public static NamespacedKey roomShell() {
        return roomShell;
    }

    public static NamespacedKey projDamage() {
        return projDamage;
    }

    public static NamespacedKey projRadius() {
        return projRadius;
    }

    public static NamespacedKey projIgnite() {
        return projIgnite;
    }

    /** 命中后点燃目标多少 tick（只点燃实体，不点燃地形）。 */
    public static NamespacedKey projIgniteTicks() {
        return projIgniteTicks;
    }

    public static NamespacedKey projSlowDuration() {
        return projSlowDuration;
    }

    public static NamespacedKey projSlowAmplifier() {
        return projSlowAmplifier;
    }

    public static NamespacedKey projKnockback() {
        return projKnockback;
    }

    /** 爆炸边缘保留的最低伤害比例（1.0 = 无衰减）。 */
    public static NamespacedKey projMinFalloff() {
        return projMinFalloff;
    }

    public static NamespacedKey projHitSound() {
        return projHitSound;
    }

    public static NamespacedKey projHitParticle() {
        return projHitParticle;
    }

    /** 弹体的发射者 UUID，便于命中时正确归属伤害。 */
    public static NamespacedKey projShooter() {
        return projShooter;
    }

    /** 弹体命中后定身多少 tick（0 = 不定身）。 */
    public static NamespacedKey projFreezeTicks() {
        return projFreezeTicks;
    }

    /** 特殊射击的伤害倍率，写在被强化的箭矢上。 */
    public static NamespacedKey arrowMultiplier() {
        return arrowMultiplier;
    }

    /** 命中后给目标打的"易伤标记"比例（0.25 = 受到伤害 +25%）。 */
    public static NamespacedKey arrowMarkBonus() {
        return arrowMarkBonus;
    }

    /** 易伤标记持续多少 tick。 */
    public static NamespacedKey arrowMarkTicks() {
        return arrowMarkTicks;
    }

    /** 特殊射击命中时的范围伤害半径（0 = 无范围伤害）。 */
    public static NamespacedKey arrowHitRadius() {
        return arrowHitRadius;
    }

    /** 范围伤害相对于主伤害的比例。 */
    public static NamespacedKey arrowHitRatio() {
        return arrowHitRatio;
    }

    /** 命中时要随机挑一种施加的负面效果名单（逗号分隔，写在箭矢上）。 */
    public static NamespacedKey arrowDebuffs() {
        return arrowDebuffs;
    }

    /** 负面效果持续时间（tick）。 */
    public static NamespacedKey arrowDebuffTicks() {
        return arrowDebuffTicks;
    }

    /** 负面效果等级（0 = I 级）。 */
    public static NamespacedKey arrowDebuffAmplifier() {
        return arrowDebuffAmplifier;
    }

    /** 触发概率（1.0 = 每箭必触发）。 */
    public static NamespacedKey arrowDebuffChance() {
        return arrowDebuffChance;
    }

    /** 召唤物归属（UUID 字符串）。 */
    public static NamespacedKey summonedOwner() {
        return summonedOwner;
    }

    /** 弹体的兑现倍率（>1 时命中带前置状态的目标会放大伤害）。 */
    public static NamespacedKey projEchoBonus() {
        return projEchoBonus;
    }

    /** 弹体命中时施加的易伤标记强度。 */
    public static NamespacedKey projMarkBonus() {
        return projMarkBonus;
    }

    /** 弹体命中时施加的易伤标记时长（tick）。 */
    public static NamespacedKey projMarkTicks() {
        return projMarkTicks;
    }

    /** 弹体命中时施加的破甲强度。 */
    public static NamespacedKey projArmorPierce() {
        return projArmorPierce;
    }

    /** 弹体命中时施加的破甲时长（tick）。 */
    public static NamespacedKey projArmorPierceTicks() {
        return projArmorPierceTicks;
    }

    /** 箭楼守卫所属的箭楼序号。 */
    public static NamespacedKey towerGuardIndex() {
        return towerGuardIndex;
    }

    /** 大将击破器的归属队伍。 */
    public static NamespacedKey sengokuBreakerTeam() {
        return sengokuBreakerTeam;
    }

    /** 中地小兵标记。 */
    public static NamespacedKey midMinion() {
        return midMinion;
    }

    /** 拆弹钳标记（商店买的剪刀）。 */
    public static NamespacedKey defuseClipper() {
        return defuseClipper;
    }

    /** 地雷标记（埋好的绊线钩）。 */
    public static NamespacedKey landMine() {
        return landMine;
    }

    /** 地雷的埋设队伍（{@code TeamId.key()}）。 */
    public static NamespacedKey landMineOwner() {
        return landMineOwner;
    }

    /** 商人 NPC 的归属队伍（{@code TeamId.key()}）。 */
    public static NamespacedKey shopNpc() {
        return shopNpc;
    }
}
