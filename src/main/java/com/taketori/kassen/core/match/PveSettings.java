package com.taketori.kassen.core.match;

import java.util.List;
import java.util.Locale;

/**
 * PVE 模式的设置快照（来自 config.yml 的 pve 段）。纯数据，方便单测与热重载。
 *
 * <p>难度档决定据点的血量、月人拆据点的速度、每波精英数量与精英的额外 buff 等级；
 * 大波次与"精英随人数变强"是模式级开关，与难度档无关。</p>
 *
 * @param difficulty                 难度档：easy / normal / hard
 * @param bigWavesEnabled            是否启用大波次
 * @param bigWaveCount               一共几个大波次
 * @param bigWaveIntervalSeconds     大波次间隔（秒）
 * @param bigWaveStartDelaySeconds   开局多少秒后第一波
 * @param elitesPerWave              每波精英数量（难度档覆盖后的结果）
 * @param announce                   大波次是否全服播报
 * @param scalingEnabled             精英是否随人数变强
 * @param buffsPerPlayer             每多一名玩家，精英药水等级 +1 级
 * @param maxBuffAmplifier           精英药水等级上限
 * @param outpostEnabled             是否启用保卫据点
 * @param outpostName                据点的显示名（MiniMessage）
 * @param outpostRadius              月人进入多远开始拆据点
 * @param endMatchOnOutpostDestroyed 据点被拆掉是否直接结束对局
 * @param outpostHealth              据点血量
 * @param outpostDamagePerSecond     一个月人每秒对据点造成的伤害
 * @param eliteBuffBonus             难度档给精英的额外 buff 等级
 */
public record PveSettings(String difficulty,
                          boolean bigWavesEnabled,
                          int bigWaveCount,
                          int bigWaveIntervalSeconds,
                          int bigWaveStartDelaySeconds,
                          int elitesPerWave,
                          boolean announce,
                          boolean scalingEnabled,
                          int buffsPerPlayer,
                          int maxBuffAmplifier,
                          boolean outpostEnabled,
                          String outpostName,
                          double outpostRadius,
                          boolean endMatchOnOutpostDestroyed,
                          double outpostHealth,
                          double outpostDamagePerSecond,
                          int eliteBuffBonus) {

    public static final String EASY = "easy";
    public static final String NORMAL = "normal";
    public static final String HARD = "hard";

    /** 可选难度档（顺序即建议由易到难）。 */
    public static List<String> difficulties() {
        return List.of(EASY, NORMAL, HARD);
    }

    /** 宽容解析难度名：不认识的一律当 normal（中英文都认）。 */
    public static String normalizeDifficulty(String raw) {
        if (raw == null) {
            return NORMAL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "easy", "e", "简单" -> EASY;
            case "hard", "h", "困难" -> HARD;
            default -> NORMAL;
        };
    }

    public String difficultyDisplay() {
        return switch (difficulty) {
            case EASY -> "简单";
            case HARD -> "困难";
            default -> "普通";
        };
    }

    /** 默认值：normal 难度、5 个大波次（约 1 分钟一波、每波 8 精英）、据点 320 血。 */
    public static PveSettings defaults() {
        return new PveSettings(NORMAL, true, 5, 60, 30, 8, true,
                true, 1, 6,
                true, "<aqua>月见据点</aqua>", 6.0D, true, 320.0D, 7.0D, 1);
    }

    /**
     * 本局精英 buff 的等级加成：难度档的固定加成 + 每多一名玩家加 {@code buffsPerPlayer} 级，
     * 上限为 {@code maxBuffAmplifier}。人数为 1 时只有难度档加成。
     */
    public int eliteBuffBonusFor(int playerCount) {
        int bonus = eliteBuffBonus;
        if (scalingEnabled && playerCount > 1) {
            bonus += (playerCount - 1) * buffsPerPlayer;
        }
        return Math.max(0, Math.min(bonus, maxBuffAmplifier));
    }
}
