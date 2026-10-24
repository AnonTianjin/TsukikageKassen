import com.taketori.kassen.paper.editor.WeaponYamlEditor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 离线回归：weapons.yml 的保留注释文本编辑器。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/paper/editor/WeaponYamlEditor.java tools/WeaponYamlEditorTest.java
 * java -cp build/test-classes WeaponYamlEditorTest
 * </pre>
 */
public class WeaponYamlEditorTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src", "main", "resources", "weapons.yml");
        Path work = Path.of("build", "test-tmp", "weapons.yml");
        Files.createDirectories(work.getParent());
        Files.copy(source, work, StandardCopyOption.REPLACE_EXISTING);

        List<String> before = Files.readAllLines(source, StandardCharsets.UTF_8);
        long commentsBefore = before.stream().filter(line -> line.trim().startsWith("#")).count();

        WeaponYamlEditor editor = new WeaponYamlEditor(work.toFile());

        // 1) 读现有值
        expect("读 damage", "10.0", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "damage")));
        expect("读 combo-cap", "20.0", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "combo-cap")));
        expect("读带引号的字符串", "'剑击'", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "display")));
        expect("读列表值", "[ SLOWNESS, WEAKNESS, POISON, BLINDNESS, HUNGER, NAUSEA ]",
                editor.currentValue(List.of("yuzuki_bow", "hit-effects", "arrow-debuffs")));
        expect("不存在的路径返回 null", null, editor.currentValue(List.of("no_such", "a", "b")));

        // 2) 改现有值
        result("改 damage", true, editor.set(List.of("hayate_sword", "skills", "left", "params", "damage"), "12.5", null));
        expect("改后读回", "12.5", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "damage")));

        result("改带行内注释的行", true,
                editor.set(List.of("hayate_sword", "skills", "left", "params", "combo-window"), "40", null));
        String comboLine = findLine(work, "combo-window:");
        check("行内注释被保留", comboLine != null && comboLine.contains("#"), comboLine);
        expect("带注释行改后读回", "40", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "combo-window")));

        result("改字符串值", true,
                editor.set(List.of("hayate_sword", "skills", "left", "params", "display"), "剑击改", null));
        expect("字符串自动加引号", "'剑击改'", editor.currentValue(List.of("hayate_sword", "skills", "left", "params", "display")));

        // 3) 模式级路径（tsukuyomi_hammer 的 HAMMER / ROCKET 各有一套 left）
        result("改模式级参数", true, editor.set(
                List.of("tsukuyomi_hammer", "modes", "HAMMER", "skills", "left", "params", "damage"), "18", null));
        expect("模式级读回", "18",
                editor.currentValue(List.of("tsukuyomi_hammer", "modes", "HAMMER", "skills", "left", "params", "damage")));
        expect("另一个模式不受影响", "10.0",
                editor.currentValue(List.of("tsukuyomi_hammer", "modes", "ROCKET", "skills", "left", "params", "damage")));

        // 4) 顶层带 weapons 前缀的路径也能用
        expect("带 weapons 前缀读取", "18",
                editor.currentValue(List.of("weapons", "tsukuyomi_hammer", "modes", "HAMMER", "skills", "left", "params", "damage")));

        // 5) 新增键：插到 hit-effects 块末尾、缩进与同级一致
        result("新增键", true,
                editor.set(List.of("yuzuki_bow", "hit-effects", "arrow-debuff-test"), "0.5", "测试插入"));
        expect("新增键读回", "0.5", editor.currentValue(List.of("yuzuki_bow", "hit-effects", "arrow-debuff-test")));
        expect("新增后原键仍在（arrow-debuff-chance）", "1.0",
                editor.currentValue(List.of("yuzuki_bow", "hit-effects", "arrow-debuff-chance")));
        String inserted = findLine(work, "arrow-debuff-test:");
        check("新增行缩进正确（与 hit-effects 子项一致）",
                inserted != null && inserted.startsWith("      arrow-debuff-test:"), inserted);

        // 6) 新增到不存在的父块 → 失败
        result("父块不存在应失败", false,
                editor.set(List.of("no_such_weapon", "skills", "left", "params", "damage"), "1", null));

        // 7) 注释与行数：只允许 +1 注释行 +1 数据行
        List<String> after = Files.readAllLines(work, StandardCharsets.UTF_8);
        long commentsAfter = after.stream().filter(line -> line.trim().startsWith("#")).count();
        check("注释行数只 +1（原注释未丢）", commentsAfter == commentsBefore + 1,
                commentsBefore + " → " + commentsAfter);
        check("总行数 +2", after.size() == before.size() + 2, before.size() + " → " + after.size());

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String findLine(Path file, String contains) throws Exception {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.contains(contains)) {
                return line;
            }
        }
        return null;
    }

    private static void expect(String name, String expected, String actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name, ok, "期望 " + expected + "，实际 " + actual);
    }

    private static void result(String name, boolean expectedOk, WeaponYamlEditor.Result result) {
        check(name + "（" + result.message() + "）", result.ok() == expectedOk,
                "期望 ok=" + expectedOk + "，实际 ok=" + result.ok());
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[OK]   " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }
}
