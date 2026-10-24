package com.taketori.kassen.core.match.sengoku;

import com.taketori.kassen.core.match.TeamId;

import java.util.EnumMap;
import java.util.Map;

/**
 * 小局比分与整场胜负判定。纯逻辑，可离线单测。
 *
 * <p>刻意与 {@link SengokuRules} 分开：「打了几局、谁赢了」是<b>状态</b>，规则是<b>配置</b>。
 * 小局重置要清空整个战场（箭楼归属、击破器、小兵、位置），但比分必须跨局保留——
 * 两者放在一个类里最容易在重置时被一起清掉。</p>
 */
public final class SengokuScore {

    private final Map<TeamId, Integer> wins = new EnumMap<>(TeamId.class);
    private int playedRounds;

    public SengokuScore() {
        reset();
    }

    /**
     * 记一个小局结果。
     *
     * @return 该结果是否被采纳。平局返回 {@code false}——不加胜场、<b>也不计局数</b>，
     *         因为平局的设计语义是"本局重开"，序号不该往后走。
     */
    public boolean record(RoundResult result) {
        if (result == null || result.isDraw()) {
            return false;
        }
        wins.merge(result.winner(), 1, Integer::sum);
        playedRounds++;
        return true;
    }

    public int wins(TeamId team) {
        return team == null ? 0 : wins.getOrDefault(team, 0);
    }

    /** 已打完的小局数（平局不计入）。 */
    public int playedRounds() {
        return playedRounds;
    }

    /** 下一个要打的小局序号（从 1 起）。 */
    public int nextRoundNumber() {
        return playedRounds + 1;
    }

    /**
     * 整场胜者；还没分出胜负返回 {@code null}。
     *
     * @param bestOf 赛制，内部会用 {@link SengokuRules#normalizeBestOf} 规整
     */
    public TeamId matchWinner(int bestOf) {
        int needed = SengokuRules.normalizeBestOf(bestOf) / 2 + 1;
        for (TeamId team : TeamId.values()) {
            if (wins(team) >= needed) {
                return team;
            }
        }
        return null;
    }

    /**
     * 整场是否结束：已有人达标，<b>或</b>已经打满最大局数。
     *
     * <p>后半句是防呆——万一配置让最大局数达不到 {@code winsNeeded}（理论上规整后不会），
     * 也要能收尾，不能卡在"永远差一局"。</p>
     */
    public boolean isFinished(int bestOf) {
        return matchWinner(bestOf) != null || playedRounds >= SengokuRules.normalizeBestOf(bestOf);
    }

    /** 形如 {@code "1-0"} 的比分串（红-蓝），用于 BossBar 与播报。 */
    public String display() {
        return wins(TeamId.RED) + "-" + wins(TeamId.BLUE);
    }

    public void reset() {
        for (TeamId team : TeamId.values()) {
            wins.put(team, 0);
        }
        playedRounds = 0;
    }

    @Override
    public String toString() {
        return "SengokuScore[" + display() + ", played=" + playedRounds + "]";
    }
}
