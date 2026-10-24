package com.taketori.kassen.core.match.sengoku;

import java.util.Locale;

/**
 * 对局模式标识。纯数据，无 Bukkit 依赖。
 *
 * <p>现有 {@code config.yml} 的 {@code match.mode} 一直是字符串（{@code pvp} / {@code pve}）。
 * 这里把三种模式收成一个枚举，新增的战国模式与它们<b>并存</b>：房间按模式分流，
 * PVP / PVE 的既有流程一行都不用改。</p>
 */
public enum SengokuMode {

    /** 3v3 积分赛（既有）。 */
    PVP("pvp"),
    /** PVE 月人入侵（既有）。 */
    PVE("pve"),
    /** 战国 3v3：三局两胜，箭楼 → 击破器 → 天守阁。 */
    SENGOKU_3V3("sengoku_3v3");

    private final String key;

    SengokuMode(String key) {
        this.key = key;
    }

    /** 配置文件里的键名。 */
    public String key() {
        return key;
    }

    public boolean isSengoku() {
        return this == SENGOKU_3V3;
    }

    /** 是否是多局制（目前只有战国模式；PVP / PVE 仍是单局）。 */
    public boolean isMultiRound() {
        return this == SENGOKU_3V3;
    }

    /**
     * 宽松解析：认不出就返回 {@code fallback}。
     *
     * <p>配置写错不该崩服——这条与插件其它地方（属性 / 粒子 / 药水名解析）的口径一致。</p>
     */
    public static SengokuMode parse(String raw, SengokuMode fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "pvp" -> PVP;
            case "pve" -> PVE;
            case "sengoku_3v3", "sengoku", "war_3v3", "war_3v3_best_of_3", "best_of_3" -> SENGOKU_3V3;
            default -> fallback;
        };
    }
}
