package com.taketori.kassen.core.match;

/**
 * 一局对局里「包点」所处的阶段。纯数据，无 Bukkit 依赖。
 *
 * <p>一局的节奏是：自由时段（双方准备，包点还是原方块）→ 拆弹时段（包点变成 TNT，
 * 红队读条拆弹）→ 抢基地（无论拆没拆开，双方抢点按分数判胜负）。
 * 把阶段收成枚举而不是几个 boolean，是为了让房间只判断一次"现在该干什么"，
 * 而不是在 tick 里到处散落 {@code if (bombPlaced && !defused && ...)}。</p>
 */
public enum BombPhase {

    /** 自由时段：包点还没激活，双方在出生点准备。 */
    FREE_TIME,
    /** 拆弹时段：包点已变成 TNT，红队读条拆弹，蓝队守包。 */
    DEFUSE,
    /** 抢基地：包点阶段已结束，双方抢点按分数判胜负。 */
    BASE_FIGHT;

    /**
     * 此刻包点是否「活着」（TNT 存在、可以拆）。
     *
     * <p>只有拆弹时段算活着：自由时段 TNT 还没出现，抢基地阶段炸弹已经结算掉了。
     * 房间用它决定要不要在 tick 里推进读条、要不要给蓝队发守包提示。</p>
     */
    public boolean bombActive() {
        return this == DEFUSE;
    }
}
