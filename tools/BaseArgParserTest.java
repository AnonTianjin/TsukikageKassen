import com.taketori.kassen.core.match.BaseArgParser;
import com.taketori.kassen.core.match.TeamId;

/**
 * 离线回归：基地划定指令的参数解析（不依赖 Bukkit，可直接用 javac/java 跑）。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/core/match/TeamId.java \
 *   src/main/java/com/taketori/kassen/core/match/BaseArgParser.java \
 *   tools/BaseArgParserTest.java
 * java -cp build/test-classes BaseArgParserTest
 * </pre>
 */
public class BaseArgParserTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        expect("标准写法 red 1", "red", "1", TeamId.RED, 1);
        expect("标准写法 blue 3", "blue", "3", TeamId.BLUE, 3);
        expect("顺序写反 1 red", "1", "red", TeamId.RED, 1);
        expect("顺序写反 3 blue", "3", "blue", TeamId.BLUE, 3);
        expect("省略编号 red", "red", null, TeamId.RED, BaseArgParser.NO_INDEX);
        expect("空编号 red \"\"", "red", "", TeamId.RED, BaseArgParser.NO_INDEX);
        expect("中文队伍 红 2", "红", "2", TeamId.RED, 2);
        expect("中文队伍 蓝队 3", "蓝队", "3", TeamId.BLUE, 3);
        expect("全角数字 red ２", "red", "２", TeamId.RED, 2);
        expect("全角数字带空格 red \" ２ \"", "red", " ２ ", TeamId.RED, 2);
        expect("大写缩写 R 1", "R", "1", TeamId.RED, 1);
        expect("超范围编号 red 4", "red", "4", TeamId.RED, 4);
        expect("编号为 0 red 0", "red", "0", TeamId.RED, 0);
        expect("队伍无效 purple 1", "purple", "1", null, 1);
        expect("编号无效 red x", "red", "x", TeamId.RED, BaseArgParser.NO_INDEX);
        expect("全错 foo bar", "foo", "bar", null, BaseArgParser.NO_INDEX);
        expect("缺队伍 null 1", null, "1", null, 1);

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void expect(String name, String first, String second, TeamId team, int index) {
        BaseArgParser.Result result = BaseArgParser.parse(first, second);
        boolean ok = result.team() == team && result.index() == index;
        if (ok) {
            passed++;
            System.out.println("[OK]   " + name + " -> " + describe(result));
        } else {
            failed++;
            System.out.println("[FAIL] " + name + " -> expected " + team + "/" + index
                    + " but got " + describe(result));
        }
    }

    private static String describe(BaseArgParser.Result result) {
        return (result.team() == null ? "null" : result.team().name()) + "/" + result.index();
    }
}
