package com.taketori.kassen.core.match.sengoku;

/**
 * 战国模式的对局骨架规则（来自 {@code sengoku.yml}）。纯数据，方便单测与热重载。
 *
 * @param bestOf                整场赛制：3 = 三局两胜、5 = 五局三胜（偶数会被规整成奇数）
 * @param timeLimitMinutes      单个小局的时限（分钟），到点按 {@code timeoutWinner} 判定
 * @param timeoutWinner         超时判定方式
 * @param keepInvulnerable      天守阁是否永久不可直接破坏（只能走击破器）
 * @param keepArmRadius         击破器读条判定在区域之外的宽容半径（格）
 * @param protectKeepBlocks     是否拦截天守阁区域的方块破坏与爆炸
 */
public record SengokuRules(int bestOf,
                           int timeLimitMinutes,
                           TimeoutWinner timeoutWinner,
                           boolean keepInvulnerable,
                           double keepArmRadius,
                           boolean protectKeepBlocks) {

    /** 小局时间耗尽时的判定方式。 */
    public enum TimeoutWinner {

        /** 箭楼占领数多者胜；持平再走 {@link RoundResult.Reason#DRAW}。 */
        TOWER_COUNT,
        /** 不看箭楼，直接判平局并重开本局。 */
        DRAW
    }

    /** 默认：三局两胜、8 分钟、超时看箭楼、天守阁无敌、外扩容差 4 格。 */
    public static SengokuRules defaults() {
        return new SengokuRules(3, 8, TimeoutWinner.TOWER_COUNT, true, 4.0D, true);
    }

    /**
     * 赢下整场需要的小局数：{@code best-of 3 → 2}、{@code 5 → 3}。
     *
     * <p>先规整 {@code bestOf} 再算，避免配置写成偶数或 0 时出现"永远打不出胜者"。</p>
     */
    public int winsNeeded() {
        return normalizeBestOf(bestOf) / 2 + 1;
    }

    /** 本场最多会打几个小局（展示"第 N 局"的上界）。 */
    public int maxRounds() {
        return normalizeBestOf(bestOf);
    }

    public long timeLimitSeconds() {
        return Math.max(1, timeLimitMinutes) * 60L;
    }

    public long timeLimitMillis() {
        return timeLimitSeconds() * 1000L;
    }

    /**
     * 把 {@code bestOf} 规整成 1..9 的奇数：{@code <=1 → 1}、偶数 → 加一、{@code >9 → 9}。
     *
     * <p>做成静态方法是因为 {@link SengokuScore} 判定胜负时也要用同一套口径——
     * 两处各写一遍迟早不一致。</p>
     */
    public static int normalizeBestOf(int raw) {
        if (raw <= 1) {
            return 1;
        }
        int value = Math.min(raw, 9);
        return value % 2 == 0 ? value + 1 : value;
    }
}
