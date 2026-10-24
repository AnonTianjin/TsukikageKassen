package com.taketori.kassen.core.match.sengoku;

import com.taketori.kassen.core.match.TeamId;

/**
 * 一个小局的结果。纯数据。
 *
 * @param winner          胜方；{@code null} 表示平局（双方都不加胜场，本局重开）
 * @param reason          结束原因，用于播报与复盘
 * @param durationSeconds 本小局实际用时（秒）
 */
public record RoundResult(TeamId winner, Reason reason, long durationSeconds) {

    /** 小局结束原因。 */
    public enum Reason {

        /** 击破器在敌方天守阁读条完成——唯一的主线胜利方式。 */
        BREAKER_ARMED("击破器攻陷天守阁"),
        /** 小局超时，按箭楼占领数判定。 */
        TIMEOUT_TOWER_COUNT("超时·箭楼数判定"),
        /** 小局超时且箭楼数持平 → 平局重开。 */
        DRAW("超时·箭楼数持平"),
        /** 人数不足等兜底判定。 */
        UNDERSTAFFED("人数不足"),
        /** 管理员强制结束。 */
        FORCED("管理员判定");

        private final String display;

        Reason(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public boolean isDraw() {
        return winner == null;
    }

    /** 平局（双方都不加胜场）。 */
    public static RoundResult draw(Reason reason, long durationSeconds) {
        return new RoundResult(null, reason, durationSeconds);
    }

    /**
     * 按"超时"规则判定本小局结果。纯函数，可离线单测。
     *
     * <p>箭楼数是地图控制权的唯一客观指标，且与小局主线（箭楼 → 击破器）同源，
     * 所以超时判它而不是判血量或分数。持平则平局重开——否则「龟缩 8 分钟」会变成有效战术。</p>
     *
     * @param mode            超时判定方式；{@code DRAW} 时无视箭楼数直接判平
     * @param redTowers       红队当前占领的箭楼数
     * @param blueTowers      蓝队当前占领的箭楼数
     * @param durationSeconds 本小局用时（秒）
     */
    public static RoundResult fromTimeout(SengokuRules.TimeoutWinner mode,
                                          int redTowers, int blueTowers, long durationSeconds) {
        if (mode == SengokuRules.TimeoutWinner.DRAW || redTowers == blueTowers) {
            return draw(Reason.DRAW, durationSeconds);
        }
        TeamId winner = redTowers > blueTowers ? TeamId.RED : TeamId.BLUE;
        return new RoundResult(winner, Reason.TIMEOUT_TOWER_COUNT, durationSeconds);
    }

    /** 某个原因下"没有胜者"是否合理——便于调用方断言，避免把 DRAW 当成有人赢。 */
    public boolean isConsistent() {
        return switch (reason) {
            case DRAW -> winner == null;
            default -> winner != null;
        };
    }
}
