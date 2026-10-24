package com.taketori.kassen.core.match.sengoku;

import com.taketori.kassen.core.match.TeamId;

/**
 * 箭楼争夺的单 tick 纯判定。无 Bukkit 依赖，可离线单测。
 *
 * <p>抽出来的理由：这是整套模式里<b>最容易写错</b>的一条规则（需求第 9 条：
 * "双方同时进行占领时双方队伍均不进行占领，只有当只有一方队伍玩家进行占领才读秒"），
 * 放在 {@code TowerCaptureManager} 里就只能靠起服实测；抽到 core 层后可以逐种组合断言。</p>
 *
 * <p>进度模型是<b>双方各自读秒</b>（而不是共享一个进度条）：所以"同时在场"表现为
 * 两张进度都停住，而不是清零。</p>
 *
 * @param redInside   本 tick 是否有红队玩家在占领区内
 * @param blueInside  本 tick 是否有蓝队玩家在占领区内
 * @param contestLock 配置是否开启互锁
 */
public record TowerContest(boolean redInside, boolean blueInside, boolean contestLock) {

    /** 双方同时在场且开启互锁 → 谁都不推进。 */
    public boolean isLocked() {
        return contestLock && redInside && blueInside;
    }

    /** 当前唯一能推进的队伍；无人、或锁死时返回 {@code null}。 */
    public TeamId pushing() {
        if (isLocked()) {
            return null;
        }
        if (redInside) {
            return TeamId.RED;
        }
        if (blueInside) {
            return TeamId.BLUE;
        }
        return null;
    }

    /** 是否双方都不在场（进度该衰减）。 */
    public boolean isEmpty() {
        return !redInside && !blueInside;
    }

    /**
     * 计算某个队伍这一 tick 之后的新进度（秒）。
     *
     * <p>判据是"<b>自己</b>是否在场"，而不是"谁被选为唯一推进方"——否则互锁关闭时
     * 双方同场会变成只有一队推进，与"关掉互锁 = 允许同时读条"的配置意图相反。</p>
     *
     * @param team           要计算哪一队
     * @param current        该队当前进度
     * @param captureSeconds 读满所需的秒数
     * @param decayPerSecond 无人时每秒衰减多少（0 = 不衰减）
     * @return 新进度，已夹在 {@code [0, captureSeconds]}
     */
    public double advanceFor(TeamId team, double current, double captureSeconds, double decayPerSecond) {
        double capped = Math.max(0.0D, Math.min(current, captureSeconds));
        if (isLocked()) {
            return capped;   // 双方同时在场 + 开启互锁：原样停住，不清零
        }
        if (isPresent(team)) {
            return Math.min(captureSeconds, capped + 1.0D);
        }
        if (isEmpty()) {
            // 无人：按配置衰减（0 = 读一半跑掉也算数）
            return Math.max(0.0D, capped - Math.max(0.0D, decayPerSecond));
        }
        // 对面有人在推、我不在：我的进度保持不动。
        // 刻意不跟着衰减——否则"两个人轮流进去"会让读条永远推不动，那不是需求要的攻防。
        return capped;
    }

    /** 某一队这一刻是否有人站在占领区内。 */
    public boolean isPresent(TeamId team) {
        if (team == TeamId.RED) {
            return redInside;
        }
        if (team == TeamId.BLUE) {
            return blueInside;
        }
        return false;
    }

    /** 该队这一 tick 是否读满。 */
    public boolean isComplete(TeamId team, double current, double captureSeconds) {
        return current >= captureSeconds && captureSeconds > 0.0D;
    }
}
