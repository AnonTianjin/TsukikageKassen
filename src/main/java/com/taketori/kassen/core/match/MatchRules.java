package com.taketori.kassen.core.match;

/**
 * 对局规则参数（来自 matches.yml）。纯数据，方便单测与热重载。
 *
 * @param scoreToWin                先达到多少分获胜
 * @param timeLimitSeconds          时限（秒），到点按分高者胜
 * @param respawnDelaySeconds       死亡后多少秒自动复活
 * @param minionKillScore           击杀小怪得分
 * @param playerKillScore           击杀对方玩家得分
 * @param baseCaptureScore          拆除对方基地得分
 * @param minionHealth              小怪血量
 * @param minionIronArmor           小怪是否穿全套铁甲
 * @param minionIntervalSeconds     刷新间隔（秒）——两类区域循环未单独配置时的回落值
 * @param minionNormalEnabled       normal 标签刷新区的独立循环是否启用
 * @param minionNormalIntervalSeconds normal 区循环间隔（秒），未配置回落 minionIntervalSeconds
 * @param minionMixedEnabled        mixed 标签刷新区的独立循环是否启用（精英波也走这条循环）
 * @param minionMixedIntervalSeconds  mixed 区循环间隔（秒），未配置回落 minionIntervalSeconds
 * @param minionPerSpawn            每次刷新几只
 * @param minionMaxAlive            场上小怪上限（达到上限就停止刷新）
 * @param baseCaptureSeconds        拆除基地需要连续占点多少秒
 * @param baseCaptureDelaySeconds   开局后多少秒内禁止占点（保护期）
 * @param baseDecayPerSecond        无人占点时进度每秒衰减多少（0 = 不衰减）
 * @param baseMultiPlayerBonus      多人同时占点是否加速
 * @param keepInventory             死亡是否保留物品
 * @param playerKillHeal            击杀敌方玩家回复的生命值（单位"点"，2 点 = 1 颗心；0 = 关闭）
 * @param minionKillHeal            击杀小兵回复的生命值（默认 0 = 不回血）
 * @param pve                       true = PVE 模式：所有人同一队打月人，玩家之间无伤害、不占点
 * @param friendlyFire              友伤保护策略：auto（PVP 开 / PVE 关，默认）/ on（一直开）/ off（一直关）
 */
public record MatchRules(int scoreToWin,
                         int timeLimitSeconds,
                         int respawnDelaySeconds,
                         int minionKillScore,
                         int playerKillScore,
                         int baseCaptureScore,
                         int minionHealth,
                         boolean minionIronArmor,
                         int minionIntervalSeconds,
                         boolean minionNormalEnabled,
                         int minionNormalIntervalSeconds,
                         boolean minionMixedEnabled,
                         int minionMixedIntervalSeconds,
                         int minionPerSpawn,
                         int minionMaxAlive,
                         double baseCaptureSeconds,
                         double baseCaptureDelaySeconds,
                         double baseDecayPerSecond,
                         boolean baseMultiPlayerBonus,
                         boolean keepInventory,
                         double playerKillHeal,
                         double minionKillHeal,
                         boolean pve,
                         String friendlyFire) {

    /**
     * 默认值：600 分获胜、20 分钟、5 秒复活、3/10/50 分、
     * 40 血铁甲僵尸（每 9 秒 3 只、上限 15）、基地读条 10 秒、开局 60 秒保护期、
     * 击杀敌人回血 3 颗心（6 点）、默认 PVP 模式、友伤保护 auto（PVP 开 / PVE 关）。
     */
    public static MatchRules defaults() {
        return new MatchRules(600, 20 * 60, 5,
                3, 10, 50,
                40, true, 9, true, 9, true, 9, 3, 15,
                10.0D, 60.0D, 0.5D, true, true,
                6.0D, 0.0D, false, "auto");
    }

    public long timeLimitMillis() {
        return timeLimitSeconds * 1000L;
    }
}
