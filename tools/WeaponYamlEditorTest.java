import com.taketori.kassen.paper.editor.WeaponYamlEditor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 离线回归：weapons.yml 的保留注释文本编辑器。
 *
 * <p>2.0 起配置结构是「顶层 passive + skills 三段」，这里按新结构验证读写：
 * 既能改已有键（保留行内注释），也能在已有块里安全新增键。</p>
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

        // 1) 读现有值（右键主动技能 + 顶层被动）
        List<String> damagePath = List.of("hayate_sword", "skills", "right", "params", "damage");
        expect("读 damage", "10.0", editor.currentValue(damagePath));
        expect("读 display", "'剑气'",
                editor.currentValue(List.of("hayate_sword", "skills", "right", "params", "display")));
        expect("读被动数值", "0.20",
                editor.currentValue(List.of("hayate_sword", "passive", "params", "chance")));
        expect("不存在的路径返回 null", null, editor.currentValue(List.of("no_such", "a", "b")));

        // 2) 改现有值
        result("改 damage", true, editor.set(damagePath, "12.5", null));
        expect("改后读回", "12.5", editor.currentValue(damagePath));

        // 3) 带行内注释的行：改值但注释必须保留
        List<String> ticksPath = List.of("tsukuyomi_hammer", "passive", "params", "ticks");
        result("改带行内注释的行", true, editor.set(ticksPath, "80", null));
        String ticksLine = findLine(work, "# 破甲持续 3 秒");
        check("行内注释被保留", ticksLine != null && ticksLine.contains("#"), ticksLine);
        expect("带注释行改后读回", "80", editor.currentValue(ticksPath));

        // 4) 字符串值自动加引号
        result("改字符串值", true,
                editor.set(List.of("hayate_sword", "skills", "right", "params", "display"), "剑气改", null));
        expect("字符串自动加引号", "'剑气改'",
                editor.currentValue(List.of("hayate_sword", "skills", "right", "params", "display")));

        // 5) 带 weapons 前缀的路径也能用
        expect("带 weapons 前缀读取", "12.5",
                editor.currentValue(List.of("weapons", "hayate_sword", "skills", "right", "params", "damage")));

        // 6) 新增键：插到 passive.params 块末尾、缩进与同级一致
        List<String> newPath = List.of("tsukuyomi_hammer", "passive", "params", "slow-duration");
        result("新增键", true, editor.set(newPath, "40", "测试插入"));
        expect("新增键读回", "40", editor.currentValue(newPath));
        expect("新增后原键仍在（ticks）", "80", editor.currentValue(ticksPath));
        String inserted = findLine(work, "slow-duration:");
        check("新增行缩进正确（与 passive 子项一致）",
                inserted != null && inserted.startsWith("        slow-duration:"), inserted);

        // 7) 新增到不存在的父块 → 失败
        result("父块不存在应失败", false,
                editor.set(List.of("no_such_weapon", "skills", "right", "params", "damage"), "1", null));

        // 8) 注释与行数：只允许 +1 注释行 +1 数据行
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
