package com.taketori.kassen.core.match;

/**
 * 队伍商店的购买规则。纯函数，无 Bukkit 依赖。
 *
 * <p>这些规则<b>同时被 GameRoom 与离线断言使用</b>：房间负责扣分、发物品、刷提示，
 * 这里只回答"能不能买、不能买是因为什么、买完还剩多少分"。之所以拆出来，
 * 是因为判定顺序（先看队伍 → 再看上限 → 最后看积分）直接决定了玩家看到哪句提示，
 * 写在房间的 tick 里就没人敢改，也没法离线断言。</p>
 *
 * <p>数值（价格、上限）不在这里写死，全部由调用方从 {@code config.yml} 读出来传进来。</p>
 */
public final class ShopRules {

    /** 不能购买的原因；{@link #OK} 表示可以买。 */
    public enum DenyReason {
        /** 可以买。 */
        OK,
        /** 这件商品不卖给这个队伍。 */
        WRONG_TEAM,
        /** 已达持有 / 埋设上限。 */
        AT_LIMIT,
        /** 积分不够。 */
        NOT_ENOUGH_SCORE
    }

    /** 拆弹钳只有进攻方（红队）能买。 */
    public static final TeamId CLIPPER_TEAM = TeamId.RED;

    /** 地雷只有防守方（蓝队）能买。 */
    public static final TeamId LAND_MINE_TEAM = TeamId.BLUE;

    private ShopRules() {
    }

    /**
     * 拆弹钳的拒绝原因。
     *
     * <p>判定顺序固定为「队伍 → 上限 → 积分」：玩家同时踩中两条时，
     * 先告诉他的应该是"你根本买不了这件东西"，而不是"你钱不够"。</p>
     *
     * @param team         购买者所在队伍（未分队传 {@code null}）
     * @param held         已经持有几把（含背包里的全部）
     * @param maxPerPlayer 每人上限（来自 {@code shop.defuse-clipper.max-per-player}）
     * @param score        当前积分
     * @param cost         单价（来自 {@code shop.defuse-clipper.cost}）
     */
    public static DenyReason clipperDenyReason(TeamId team, int held, int maxPerPlayer, int score, int cost) {
        if (team != CLIPPER_TEAM) {
            return DenyReason.WRONG_TEAM;
        }
        if (held >= maxPerPlayer) {
            return DenyReason.AT_LIMIT;
        }
        if (score < cost) {
            return DenyReason.NOT_ENOUGH_SCORE;
        }
        return DenyReason.OK;
    }

    /** 拆弹钳能不能买（{@link #clipperDenyReason} 的布尔简写）。 */
    public static boolean canBuyClipper(TeamId team, int held, int maxPerPlayer, int score, int cost) {
        return clipperDenyReason(team, held, maxPerPlayer, score, cost) == DenyReason.OK;
    }

    /**
     * 地雷的拒绝原因。
     *
     * <p>地雷的上限是<b>全队共享</b>的（防止一队埋满地图），所以传进来的是
     * 本队已买个数，而不是这个人买了几个。</p>
     *
     * @param team        购买者所在队伍（未分队传 {@code null}）
     * @param bought      本队已买几个
     * @param maxPerTeam  每队上限（来自 {@code shop.land-mine.max-per-team}）
     * @param score       当前积分
     * @param cost        单价（来自 {@code shop.land-mine.cost}）
     */
    public static DenyReason landMineDenyReason(TeamId team, int bought, int maxPerTeam, int score, int cost) {
        if (team != LAND_MINE_TEAM) {
            return DenyReason.WRONG_TEAM;
        }
        if (bought >= maxPerTeam) {
            return DenyReason.AT_LIMIT;
        }
        if (score < cost) {
            return DenyReason.NOT_ENOUGH_SCORE;
        }
        return DenyReason.OK;
    }

    /** 地雷能不能买（{@link #landMineDenyReason} 的布尔简写）。 */
    public static boolean canBuyLandMine(TeamId team, int bought, int maxPerTeam, int score, int cost) {
        return landMineDenyReason(team, bought, maxPerTeam, score, cost) == DenyReason.OK;
    }

    /**
     * 买完之后的积分，永不为负。
     *
     * <p>负分在计分板上没法显示、还会让"再买一次"变成加分，所以这里直接钳到 0。
     * {@code cost} 由 {@code ConfigManager} 保证非负，这里不再重复钳制。</p>
     */
    public static int scoreAfter(int score, int cost) {
        return Math.max(0, score - cost);
    }
}
