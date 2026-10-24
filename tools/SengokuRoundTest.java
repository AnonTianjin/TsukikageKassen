import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.EnergyRules;
import com.taketori.kassen.core.match.sengoku.RoundResult;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.core.match.sengoku.SengokuRules;
import com.taketori.kassen.core.match.sengoku.SengokuScore;

/**
 * 离线回归：战国 3v3 的小局赛制与胜负判定（纯逻辑，不依赖 Bukkit）。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/core/match/TeamId.java \
 *   src/main/java/com/taketori/kassen/core/match/sengoku/*.java \
 *   tools/SengokuRoundTest.java
 * java -cp build/test-classes SengokuRoundTest
 * </pre>
 */
public class SengokuRoundTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ── bestOf 规整：配置写成偶数 / 0 / 过大都不能让赛制卡死 ──────────────
        expectInt("bestOf 0 → 1", 1, SengokuRules.normalizeBestOf(0));
        expectInt("bestOf 1 → 1", 1, SengokuRules.normalizeBestOf(1));
        expectInt("bestOf 2 → 3（偶数加一）", 3, SengokuRules.normalizeBestOf(2));
        expectInt("bestOf 3 → 3", 3, SengokuRules.normalizeBestOf(3));
        expectInt("bestOf 4 → 5", 5, SengokuRules.normalizeBestOf(4));
        expectInt("bestOf 5 → 5", 5, SengokuRules.normalizeBestOf(5));
        expectInt("bestOf 8 → 9", 9, SengokuRules.normalizeBestOf(8));
        expectInt("bestOf 9 → 9", 9, SengokuRules.normalizeBestOf(9));
        expectInt("bestOf 99 → 9（封顶）", 9, SengokuRules.normalizeBestOf(99));
        expectInt("bestOf -3 → 1", 1, SengokuRules.normalizeBestOf(-3));

        // ── 赢下整场需要几局 ────────────────────────────────────────────
        expectInt("三局两胜需要 2 胜", 2, new SengokuRules(3, 8, SengokuRules.TimeoutWinner.TOWER_COUNT,
                true, 4.0D, true).winsNeeded());
        expectInt("五局三胜需要 3 胜", 3, new SengokuRules(5, 8, SengokuRules.TimeoutWinner.TOWER_COUNT,
                true, 4.0D, true).winsNeeded());
        expectInt("单局制需要 1 胜", 1, new SengokuRules(1, 8, SengokuRules.TimeoutWinner.TOWER_COUNT,
                true, 4.0D, true).winsNeeded());
        expectInt("偶数 2 被规整成 3 后需要 2 胜", 2, new SengokuRules(2, 8, SengokuRules.TimeoutWinner.TOWER_COUNT,
                true, 4.0D, true).winsNeeded());

        // ── 时限换算（含非法值兜底） ─────────────────────────────────────
        expectLong("8 分钟 = 480 秒", 480L, SengokuRules.defaults().timeLimitSeconds());
        expectLong("8 分钟 = 480000 毫秒", 480_000L, SengokuRules.defaults().timeLimitMillis());
        expectLong("0 分钟兜底成 1 分钟", 60L, new SengokuRules(3, 0, SengokuRules.TimeoutWinner.DRAW,
                true, 4.0D, true).timeLimitSeconds());
        expectLong("-5 分钟兜底成 1 分钟", 60L, new SengokuRules(3, -5, SengokuRules.TimeoutWinner.DRAW,
                true, 4.0D, true).timeLimitSeconds());

        // ── 三局两胜的完整流程 ──────────────────────────────────────────
        SengokuScore score = new SengokuScore();
        expectTeam("开局无人获胜", null, score.matchWinner(3));
        expectInt("开局下一局序号是 1", 1, score.nextRoundNumber());
        expectStr("开局比分", "0-0", score.display());

        expectBool("记入红队一胜被采纳", true,
                score.record(new RoundResult(TeamId.RED, RoundResult.Reason.BREAKER_ARMED, 300)));
        expectInt("红队 1 胜", 1, score.wins(TeamId.RED));
        expectTeam("1-0 时整场未定", null, score.matchWinner(3));

        expectBool("记入蓝队一胜被采纳", true,
                score.record(new RoundResult(TeamId.BLUE, RoundResult.Reason.TIMEOUT_TOWER_COUNT, 480)));
        expectStr("打完两局比分 1-1", "1-1", score.display());
        expectTeam("1-1 时整场未定", null, score.matchWinner(3));
        expectBool("1-1 时整场未结束", false, score.isFinished(3));

        expectBool("记入红队第二胜被采纳", true,
                score.record(new RoundResult(TeamId.RED, RoundResult.Reason.BREAKER_ARMED, 200)));
        expectTeam("红队先到 2 胜赢得整场", TeamId.RED, score.matchWinner(3));
        expectStr("最终比分 2-1", "2-1", score.display());
        expectBool("整场已结束", true, score.isFinished(3));
        expectInt("共打了 3 局", 3, score.playedRounds());

        // ── 平局：不加胜场，也不推进局数（设计语义是"本局重开"） ──────────
        SengokuScore drawScore = new SengokuScore();
        drawScore.record(new RoundResult(TeamId.RED, RoundResult.Reason.BREAKER_ARMED, 100));
        expectBool("平局结果不被采纳", false,
                drawScore.record(RoundResult.draw(RoundResult.Reason.DRAW, 480)));
        expectInt("平局后红队仍是 1 胜", 1, drawScore.wins(TeamId.RED));
        expectInt("平局不计入已打局数", 1, drawScore.playedRounds());
        expectInt("平局后下一局序号仍是 2", 2, drawScore.nextRoundNumber());
        expectTeam("平局不影响整场判定", null, drawScore.matchWinner(3));
        expectBool("平局不算整场结束", false, drawScore.isFinished(3));

        // ── 五局三胜 ───────────────────────────────────────────────────
        SengokuScore bo5 = new SengokuScore();
        for (int i = 0; i < 3; i++) {
            bo5.record(new RoundResult(TeamId.BLUE, RoundResult.Reason.BREAKER_ARMED, 100));
        }
        expectTeam("五局三胜里蓝队拿 3 局即胜", TeamId.BLUE, bo5.matchWinner(5));
        expectTeam("同一份比分按三局两胜看也早已分出胜负", TeamId.BLUE, bo5.matchWinner(3));

        // ── 超时判定 ───────────────────────────────────────────────────
        RoundResult redLeads = RoundResult.fromTimeout(SengokuRules.TimeoutWinner.TOWER_COUNT, 2, 1, 480);
        expectTeam("超时·箭楼 2:1 → 红队胜", TeamId.RED, redLeads.winner());
        expectStr("超时原因正确", RoundResult.Reason.TIMEOUT_TOWER_COUNT.display(), redLeads.reason().display());
        expectBool("超时判定结果自洽", true, redLeads.isConsistent());

        RoundResult blueLeads = RoundResult.fromTimeout(SengokuRules.TimeoutWinner.TOWER_COUNT, 0, 2, 480);
        expectTeam("超时·箭楼 0:2 → 蓝队胜", TeamId.BLUE, blueLeads.winner());

        RoundResult tied = RoundResult.fromTimeout(SengokuRules.TimeoutWinner.TOWER_COUNT, 1, 1, 480);
        expectBool("超时·箭楼持平 → 平局", true, tied.isDraw());
        expectBool("平局结果自洽", true, tied.isConsistent());

        RoundResult forcedDraw = RoundResult.fromTimeout(SengokuRules.TimeoutWinner.DRAW, 2, 0, 480);
        expectBool("判定方式为 DRAW 时无视箭楼数判平", true, forcedDraw.isDraw());

        RoundResult zeroZero = RoundResult.fromTimeout(SengokuRules.TimeoutWinner.TOWER_COUNT, 0, 0, 480);
        expectBool("双方都是 0 座箭楼 → 平局", true, zeroZero.isDraw());

        // ── 模式解析（配置写错不该崩服） ────────────────────────────────
        expectMode("pvp", SengokuMode.PVP, "pvp");
        expectMode("PVE 大写", SengokuMode.PVE, "PVE");
        expectMode("sengoku_3v3", SengokuMode.SENGOKU_3V3, "sengoku_3v3");
        expectMode("war-3v3 连字符", SengokuMode.SENGOKU_3V3, "war-3v3");
        expectMode("带空格 SENGOKU ", SengokuMode.SENGOKU_3V3, " sengoku ");
        expectMode("认不出 → 回退 PVP", SengokuMode.PVP, "nonsense");
        expectMode("null → 回退 PVE", SengokuMode.PVE, null);
        expectBool("SENGOKU_3V3 是多局制", true, SengokuMode.SENGOKU_3V3.isMultiRound());
        expectBool("PVP 不是多局制", false, SengokuMode.PVP.isMultiRound());
        expectBool("PVE 不是战国模式", false, SengokuMode.PVE.isSengoku());

        // ── 能量规则（sengoku-energy.yml 的纯逻辑） ─────────────────────
        EnergyRules energy = EnergyRules.defaults();
        expectInt("默认能量上限 100", 100, energy.safeMax());
        expectInt("默认每只小兵 8 点", 8, energy.safePerMinion());
        expectBool("99 点不算满", false, energy.isFull(99));
        expectBool("100 点算满", true, energy.isFull(100));
        expectBool("超过上限也算满", true, energy.isFull(150));
        expectInt("clamp 夹住上限", 100, energy.clamp(150));
        expectInt("clamp 夹住下限", 0, energy.clamp(-20));
        expectBool("默认占用 Q 槽", true, energy.usesQSlot());

        EnergyRules badEnergy = new EnergyRules(0, 0, 0, EnergyRules.Display.NONE, "q",
                EnergyRules.UltimateSpec.defaults(), java.util.Map.of());
        expectInt("上限写成 0 兜底成 1", 1, badEnergy.safeMax());
        expectInt("每只小兵写成负数兜底成 0", 0, badEnergy.safePerMinion());
        expectBool("上限为 1 时 1 点即满", true, badEnergy.isFull(1));

        // 逐角色覆盖：配了的用自己那套，没配的落回默认
        EnergyRules.UltimateSpec tsukuyomiSpec = new EnergyRules.UltimateSpec(
                "shockwave", 18.0D, 6.0D, 0.9D, 0.5D, 40, 1, "EXPLOSION", "ENTITY_GENERIC_EXPLODE");
        EnergyRules withOverride = new EnergyRules(100, 8, 20, EnergyRules.Display.ACTIONBAR, "q",
                EnergyRules.UltimateSpec.defaults(), java.util.Map.of("tsukuyomi", tsukuyomiSpec));
        expectDouble("配了覆盖的角色用自己那套", 18.0D,
                withOverride.ultimateFor("tsukuyomi").damage());
        expectDouble("没配的角色落回默认", 14.0D,
                withOverride.ultimateFor("shogun").damage());
        expectDouble("角色 id 大小写不敏感", 18.0D,
                withOverride.ultimateFor("TSUKUYOMI").damage());
        expectDouble("characterId 为 null 时落回默认", 14.0D,
                withOverride.ultimateFor(null).damage());

        System.out.println("SengokuRoundTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 断言助手 ──────────────────────────────────────────────────────

    private static void expectInt(String name, int expected, int actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectLong(String name, long expected, long actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectStr(String name, String expected, String actual) {
        check(name, expected, actual);
    }

    private static void expectBool(String name, boolean expected, boolean actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectDouble(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 0.000001D) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void expectTeam(String name, TeamId expected, TeamId actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectMode(String name, SengokuMode expected, String raw) {
        SengokuMode actual = SengokuMode.parse(raw, expected == SengokuMode.PVE ? SengokuMode.PVE : SengokuMode.PVP);
        check(name, String.valueOf(expected), String.valueOf(actual));
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
