import com.taketori.kassen.core.worlds.WorldScope;

import java.util.List;
import java.util.Set;

/**
 * 离线回归：多世界判定（{@link WorldScope}）。
 *
 * <p>不依赖 Bukkit，直接用 javac 编译后运行：</p>
 * <pre>
 * javac -encoding UTF-8 -cp build/classes -d build/check tools/WorldScopeTest.java
 * java -cp "build/check;build/classes" WorldScopeTest
 * </pre>
 */
public final class WorldScopeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("-- allowsTakeover（进服送大厅的接管范围）--");
        check("空名单 + 玩家在大厅世界 → 接管", WorldScope.allowsTakeover(List.of(), "world", "world"), true);
        check("空名单 + 玩家在别的世界 → 不接管", WorldScope.allowsTakeover(List.of(), "world", "world_nether"), false);
        check("空名单 + 没配大厅出生点 → 不接管", WorldScope.allowsTakeover(List.of(), null, "world"), false);
        check("通配符 → 所有世界都接管", WorldScope.allowsTakeover(List.of("*"), "world", "somewhere_else"), true);
        check("白名单命中 → 接管", WorldScope.allowsTakeover(List.of("world_nether"), "world", "world_nether"), true);
        check("白名单不命中 → 不接管", WorldScope.allowsTakeover(List.of("world_nether"), "world", "world"), false);
        check("白名单大小写/空白容错", WorldScope.allowsTakeover(List.of("  World_Nether "), "world", "world_nether"), true);
        check("玩家世界为 null → 不接管", WorldScope.allowsTakeover(List.of("*"), "world", null), false);
        check("玩家世界为空白 → 不接管", WorldScope.allowsTakeover(List.of("*"), "world", "   "), false);
        check("白名单里有 null 项不影响其它项", WorldScope.allowsTakeover(
                java.util.Arrays.asList(null, "world"), "world", "world"), true);

        System.out.println("-- shouldReceive（消息播报范围）--");
        Set<String> matchWorlds = Set.of("arena_world");
        check("scope=all → 人人都收", WorldScope.shouldReceive("all", matchWorlds, "anywhere"), true);
        check("scope=ALL（大写）→ 人人都收", WorldScope.shouldReceive("ALL", matchWorlds, "anywhere"), true);
        check("scope=world + 在对局世界 → 收", WorldScope.shouldReceive("world", matchWorlds, "arena_world"), true);
        check("scope=world + 大小写不同 → 收", WorldScope.shouldReceive("world", Set.of("Arena_World"), "arena_world"), true);
        check("scope=world + 不在对局世界 → 不收", WorldScope.shouldReceive("world", matchWorlds, "lobby_world"), false);
        check("scope=world + 玩家世界缺失 → 不收", WorldScope.shouldReceive("world", matchWorlds, null), false);
        check("没记录到消息世界 → 不静默丢，按全服收", WorldScope.shouldReceive("world", Set.of(), "lobby_world"), true);
        check("scope 为空 → 按 world 处理", WorldScope.shouldReceive(null, matchWorlds, "lobby_world"), false);

        System.out.println("-- 展示文案 --");
        check("通配符说明", WorldScope.describeTakeover(List.of("*"), "world").contains("所有世界"), true);
        check("白名单说明", WorldScope.describeTakeover(List.of("a", "b"), "world").contains("a"), true);
        check("空名单 + 有大厅世界 → 指向大厅世界", WorldScope.describeTakeover(List.of(), "world").contains("world"), true);
        check("空名单 + 无大厅世界 → 说明实际不接管",
                WorldScope.describeTakeover(List.of(), null).contains("不接管"), true);
        check("normalize 去重", WorldScope.normalize(List.of("A", "a", " b ")).size() == 2, true);

        System.out.println();
        System.out.println("WorldScopeTest: " + passed + " passed, " + failed + " failed");
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
}
