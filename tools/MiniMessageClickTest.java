import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 离线验证：观众退出按钮的 MiniMessage 文本确实能解析出 run_command 点击事件。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -cp "&lt;adventure jars&gt;" -d build/test-classes tools/MiniMessageClickTest.java
 * java -cp "&lt;adventure jars&gt;;build/test-classes" MiniMessageClickTest
 * </pre>
 *
 * <p>和 SpectatorManager 里的 EXIT_BUTTON 保持一致；改那边时记得同步这里。</p>
 */
public class MiniMessageClickTest {

    private static final String EXIT_BUTTON = "<click:run_command:'/taketori leave'>"
            + "<hover:show_text:'<gray>点击退出观战，回到大厅'>"
            + "<yellow><bold>[ 退出观战 ]</bold></yellow></hover></click>";

    private static int failed;

    public static void main(String[] args) {
        String line = "<gray>已进入观众视角。 <gray>想退出就点 " + EXIT_BUTTON
                + " <dark_gray>（也可以输入 /taketori leave）";
        Component component = MiniMessage.miniMessage().deserialize(line);

        ClickEvent click = findClick(component);
        if (click == null) {
            failed++;
            System.out.println("[FAIL] 解析结果里没有 clickEvent —— 按钮点不动");
        } else {
            System.out.println("[OK] click action=" + click.action() + " value=" + click.value());
            if (click.action() != ClickEvent.Action.RUN_COMMAND
                    || !"/taketori leave".equals(click.value())) {
                failed++;
                System.out.println("[FAIL] 点击事件不是预期的 /taketori leave");
            }
        }

        String plain = flatten(component);
        System.out.println("[OK] plain=" + plain);
        if (!plain.contains("退出观战")) {
            failed++;
            System.out.println("[FAIL] 按钮文字丢失");
        }
        if (plain.contains("<click") || plain.contains("</click")) {
            failed++;
            System.out.println("[FAIL] 标签没有被解析掉（原样漏到了聊天栏）");
        }

        System.out.println("failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static ClickEvent findClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = findClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String flatten(Component component) {
        StringBuilder builder = new StringBuilder();
        if (component instanceof TextComponent text) {
            builder.append(text.content());
        }
        for (Component child : component.children()) {
            builder.append(flatten(child));
        }
        return builder.toString();
    }
}
