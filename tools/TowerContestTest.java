import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.TowerContest;
import com.taketori.kassen.core.match.sengoku.TowerRules;

/**
 * 离线回归：箭楼争夺的互锁规则（需求第 9 条）与读条推进。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/core/match/TeamId.java \
 *   src/main/java/com/taketori/kassen/core/match/sengoku/*.java \
 *   tools/TowerContestTest.java
 * java -cp build/test-classes TowerContestTest
 * </pre>
 */
public class TowerContestTest {

    private static final double NEED = 5.0D;
    private static final double DECAY = 0.5D;

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ── 只有一方在场：那一方推进，另一方不动 ──────────────────────
        TowerContest onlyRed = new TowerContest(true, false, true);
        expectBool("只有红队在 → 未锁死", false, onlyRed.isLocked());
        expectTeam("只有红队在 → 推进方是红队", TeamId.RED, onlyRed.pushing());
        expectDouble("只有红队在 → 红队进度 +1", 1.0D,
                onlyRed.advanceFor(TeamId.RED, 0.0D, NEED, DECAY));
        expectDouble("只有红队在 → 蓝队进度不变", 2.0D,
                onlyRed.advanceFor(TeamId.BLUE, 2.0D, NEED, DECAY));

        TowerContest onlyBlue = new TowerContest(false, true, true);
        expectTeam("只有蓝队在 → 推进方是蓝队", TeamId.BLUE, onlyBlue.pushing());
        expectDouble("只有蓝队在 → 蓝队进度 +1", 3.0D,
                onlyBlue.advanceFor(TeamId.BLUE, 2.0D, NEED, DECAY));

        // ── 双方同时在场 + 互锁开：双方都不推进（且不清零） ────────────
        TowerContest contested = new TowerContest(true, true, true);
        expectBool("双方同时在场 → 锁死", true, contested.isLocked());
        expectTeam("锁死时没有推进方", null, contested.pushing());
        expectDouble("锁死 → 红队进度原样停住", 3.0D,
                contested.advanceFor(TeamId.RED, 3.0D, NEED, DECAY));
        expectDouble("锁死 → 蓝队进度原样停住", 2.5D,
                contested.advanceFor(TeamId.BLUE, 2.5D, NEED, DECAY));
        expectDouble("锁死 → 满进度也不会被清掉", NEED,
                contested.advanceFor(TeamId.RED, NEED, NEED, DECAY));

        // ── 双方同时在场但互锁关闭：两队各自推进 ──────────────────────
        TowerContest noLock = new TowerContest(true, true, false);
        expectBool("互锁关闭 → 不锁死", false, noLock.isLocked());
        expectDouble("互锁关闭 → 红队照样推进", 1.0D,
                noLock.advanceFor(TeamId.RED, 0.0D, NEED, DECAY));
        expectDouble("互锁关闭 → 蓝队也推进", 1.0D,
                noLock.advanceFor(TeamId.BLUE, 0.0D, NEED, DECAY));

        // ── 无人：按配置衰减，并夹到 0 ────────────────────────────────
        TowerContest empty = new TowerContest(false, false, true);
        expectBool("无人 → isEmpty", true, empty.isEmpty());
        expectTeam("无人 → 没有推进方", null, empty.pushing());
        expectDouble("无人 → 进度按 decay 衰减", 2.5D,
                empty.advanceFor(TeamId.RED, 3.0D, NEED, DECAY));
        expectDouble("无人 → 衰减不会变成负数", 0.0D,
                empty.advanceFor(TeamId.RED, 0.2D, NEED, DECAY));
        expectDouble("无人 + decay=0 → 进度保留（读一半跑掉也算数）", 2.0D,
                empty.advanceFor(TeamId.RED, 2.0D, NEED, 0.0D));

        // ── 对面在推、我不在：我的进度保持不动（不跟着衰减） ────────────
        expectDouble("红队在推时蓝队进度不动", 1.5D,
                onlyRed.advanceFor(TeamId.BLUE, 1.5D, NEED, DECAY));

        // ── 进度封顶 ─────────────────────────────────────────────────
        expectDouble("进度不会超过读满所需秒数", NEED,
                onlyRed.advanceFor(TeamId.RED, NEED, NEED, DECAY));
        expectDouble("超额进度会被夹回上限", NEED,
                onlyRed.advanceFor(TeamId.RED, 99.0D, NEED, DECAY));

        // ── 完成判定 ─────────────────────────────────────────────────
        expectBool("刚好读满算完成", true, onlyRed.isComplete(TeamId.RED, NEED, NEED));
        expectBool("差一点不算完成", false, onlyRed.isComplete(TeamId.RED, NEED - 0.1D, NEED));
        expectBool("读满所需为 0 时不算完成（避免除零式的误判）", false,
                onlyRed.isComplete(TeamId.RED, 5.0D, 0.0D));

        // ── isPresent ───────────────────────────────────────────────
        expectBool("isPresent 认红队在", true, onlyRed.isPresent(TeamId.RED));
        expectBool("isPresent 认蓝队不在", false, onlyRed.isPresent(TeamId.BLUE));
        expectBool("isPresent 对 null 安全", false, onlyRed.isPresent(null));

        // ── TowerRules 的纯逻辑 ──────────────────────────────────────
        TowerRules defaults = TowerRules.defaults();
        expectDouble("默认读条 5 秒", 5.0D, defaults.effectiveCaptureSeconds());
        expectBool("默认需要读条", true, defaults.isChanneled());
        expectInt("默认 2 座箭楼", 2, defaults.safeCount());

        TowerRules instant = new TowerRules(2, TowerRules.CaptureMode.INSTANT, 5.0D, 0.5D, true, 20,
                TowerRules.GuardSpec.oxDemonDefaults(), TowerRules.GuardSpec.shrimpCrabDefaults(),
                "BELL", "BLOCK_BELL_USE", "END_ROD");
        expectDouble("瞬时模式读条时长为 0", 0.0D, instant.effectiveCaptureSeconds());
        expectBool("瞬时模式不需要读条", false, instant.isChanneled());

        TowerRules badCount = new TowerRules(0, TowerRules.CaptureMode.CHANNEL, 5.0D, 0.5D, true, 20,
                TowerRules.GuardSpec.oxDemonDefaults(), TowerRules.GuardSpec.shrimpCrabDefaults(),
                "BELL", "BLOCK_BELL_USE", "END_ROD");
        expectInt("箭楼数量 0 兜底成 1", 1, badCount.safeCount());

        TowerRules hugeCount = new TowerRules(999, TowerRules.CaptureMode.CHANNEL, 5.0D, 0.5D, true, 20,
                TowerRules.GuardSpec.oxDemonDefaults(), TowerRules.GuardSpec.shrimpCrabDefaults(),
                "BELL", "BLOCK_BELL_USE", "END_ROD");
        expectInt("箭楼数量 999 封顶到 8", 8, hugeCount.safeCount());

        expectStr("牛鬼默认用尸壳", "HUSK", defaults.oxDemon().entity());
        expectStr("虾兵蟹将默认用卫道士", "VINDICATOR", defaults.shrimpCrab().entity());
        expectInt("牛鬼默认 1 只", 1, defaults.oxDemon().count());
        expectInt("虾兵蟹将默认 8 只", 8, defaults.shrimpCrab().count());

        System.out.println("TowerContestTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 断言助手 ──────────────────────────────────────────────────────

    private static void expectBool(String name, boolean expected, boolean actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectInt(String name, int expected, int actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectTeam(String name, TeamId expected, TeamId actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectStr(String name, String expected, String actual) {
        check(name, expected, actual);
    }

    private static void expectDouble(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 0.000001D) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }
}
