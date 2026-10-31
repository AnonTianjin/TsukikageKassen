import com.taketori.kassen.core.match.BombPhase;
import com.taketori.kassen.core.match.ShopRules;
import com.taketori.kassen.core.match.ShopRules.DenyReason;
import com.taketori.kassen.core.match.TeamId;

/**
 * 离线回归：包点阶段与队伍商店的购买规则（{@link BombPhase} / {@link ShopRules}）。
 *
 * <p>商店的判定顺序（队伍 → 上限 → 积分）直接决定玩家看到哪一句提示，
 * 扣分后的余额又必须永不为负——这两件事都不依赖 Bukkit，所以能在离线断言里跑。</p>
 *
 * <p>不依赖 Bukkit，直接用 javac 编译后运行：</p>
 * <pre>
 * javac -encoding UTF-8 -cp build/classes -d build/check tools/BombShopRulesTest.java
 * java -cp "build/check;build/classes" BombShopRulesTest
 * </pre>
 */
public final class BombShopRulesTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("-- ShopRules：队伍限制（钳 → 红队 / 地雷 → 蓝队）--");
        check("红队买拆弹钳 → 可以", ShopRules.clipperDenyReason(TeamId.RED, 0, 2, 100, 30), DenyReason.OK);
        check("蓝队买拆弹钳 → 队伍不对", ShopRules.clipperDenyReason(TeamId.BLUE, 0, 2, 100, 30), DenyReason.WRONG_TEAM);
        check("未分队买拆弹钳 → 队伍不对", ShopRules.clipperDenyReason(null, 0, 2, 100, 30), DenyReason.WRONG_TEAM);
        check("蓝队买地雷 → 可以", ShopRules.landMineDenyReason(TeamId.BLUE, 0, 2, 100, 40), DenyReason.OK);
        check("红队买地雷 → 队伍不对", ShopRules.landMineDenyReason(TeamId.RED, 0, 2, 100, 40), DenyReason.WRONG_TEAM);
        check("未分队买地雷 → 队伍不对", ShopRules.landMineDenyReason(null, 0, 2, 100, 40), DenyReason.WRONG_TEAM);
        check("钳子专属队伍常量 = 红队", ShopRules.CLIPPER_TEAM, TeamId.RED);
        check("地雷专属队伍常量 = 蓝队", ShopRules.LAND_MINE_TEAM, TeamId.BLUE);

        System.out.println("-- ShopRules：持有 / 埋设上限 --");
        check("钳子 1/2 → 可以", ShopRules.clipperDenyReason(TeamId.RED, 1, 2, 100, 30), DenyReason.OK);
        check("钳子 2/2 → 到顶", ShopRules.clipperDenyReason(TeamId.RED, 2, 2, 100, 30), DenyReason.AT_LIMIT);
        check("钳子 3/2 → 到顶", ShopRules.clipperDenyReason(TeamId.RED, 3, 2, 100, 30), DenyReason.AT_LIMIT);
        check("钳子上限 1 时 1 把 → 到顶", ShopRules.clipperDenyReason(TeamId.RED, 1, 1, 100, 30), DenyReason.AT_LIMIT);
        check("地雷 1/2 → 可以", ShopRules.landMineDenyReason(TeamId.BLUE, 1, 2, 100, 40), DenyReason.OK);
        check("地雷 2/2 → 到顶", ShopRules.landMineDenyReason(TeamId.BLUE, 2, 2, 100, 40), DenyReason.AT_LIMIT);
        check("地雷 5/2 → 到顶", ShopRules.landMineDenyReason(TeamId.BLUE, 5, 2, 100, 40), DenyReason.AT_LIMIT);
        check("上限优先于积分：满额 + 0 分 → 报到顶而不是积分不足",
                ShopRules.clipperDenyReason(TeamId.RED, 2, 2, 0, 30), DenyReason.AT_LIMIT);
        check("队伍优先于上限：蓝队 + 满额 → 报队伍不对",
                ShopRules.clipperDenyReason(TeamId.BLUE, 2, 2, 0, 30), DenyReason.WRONG_TEAM);

        System.out.println("-- ShopRules：积分（不足 / 恰好够 / 免费）--");
        check("钳子差 1 分 → 积分不足", ShopRules.clipperDenyReason(TeamId.RED, 0, 2, 29, 30), DenyReason.NOT_ENOUGH_SCORE);
        check("钳子 0 分 → 积分不足", ShopRules.clipperDenyReason(TeamId.RED, 0, 2, 0, 30), DenyReason.NOT_ENOUGH_SCORE);
        check("钳子恰好够（30/30）→ 可以", ShopRules.clipperDenyReason(TeamId.RED, 0, 2, 30, 30), DenyReason.OK);
        check("地雷差 1 分 → 积分不足", ShopRules.landMineDenyReason(TeamId.BLUE, 0, 2, 39, 40), DenyReason.NOT_ENOUGH_SCORE);
        check("地雷恰好够（40/40）→ 可以", ShopRules.landMineDenyReason(TeamId.BLUE, 0, 2, 40, 40), DenyReason.OK);
        check("免费（0 分 0 价）→ 可以", ShopRules.clipperDenyReason(TeamId.RED, 0, 2, 0, 0), DenyReason.OK);
        check("canBuyClipper 布尔简写：能买", ShopRules.canBuyClipper(TeamId.RED, 0, 2, 30, 30), true);
        check("canBuyClipper 布尔简写：队伍不对", ShopRules.canBuyClipper(TeamId.BLUE, 0, 2, 30, 30), false);
        check("canBuyClipper 布尔简写：满额", ShopRules.canBuyClipper(TeamId.RED, 2, 2, 1000, 30), false);
        check("canBuyLandMine 布尔简写：能买", ShopRules.canBuyLandMine(TeamId.BLUE, 0, 2, 40, 40), true);
        check("canBuyLandMine 布尔简写：到顶", ShopRules.canBuyLandMine(TeamId.BLUE, 2, 2, 100, 40), false);
        check("canBuyLandMine 布尔简写：积分不足", ShopRules.canBuyLandMine(TeamId.BLUE, 0, 2, 10, 40), false);

        System.out.println("-- ShopRules.scoreAfter：扣分不下溢 --");
        check("100 - 30 = 70", ShopRules.scoreAfter(100, 30), 70);
        check("恰好够 → 0", ShopRules.scoreAfter(30, 30), 0);
        check("不够也钳到 0（不下溢）", ShopRules.scoreAfter(10, 30), 0);
        check("0 分买免费货 → 0", ShopRules.scoreAfter(0, 0), 0);
        check("0 价不扣分", ShopRules.scoreAfter(200, 0), 200);

        System.out.println("-- BombPhase：只有拆弹时段算包点活着 --");
        check("自由时段 → 包点未激活", BombPhase.FREE_TIME.bombActive(), false);
        check("拆弹时段 → 包点激活", BombPhase.DEFUSE.bombActive(), true);
        check("抢基地 → 包点已结束", BombPhase.BASE_FIGHT.bombActive(), false);
        check("阶段只有三个", BombPhase.values().length, 3);
        check("按名解析 DEFUSE", BombPhase.valueOf("DEFUSE"), BombPhase.DEFUSE);

        System.out.println();
        System.out.println("BombShopRulesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean actual, boolean expected) {
        if (actual == expected) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void check(String name, int actual, int expected) {
        if (actual == expected) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void check(String name, Object actual, Object expected) {
        if (actual == expected) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "：期望 " + expected + "，实际 " + actual);
        }
    }
}
