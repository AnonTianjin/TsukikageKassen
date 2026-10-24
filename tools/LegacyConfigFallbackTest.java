import com.taketori.kassen.config.ConfigManager;
import com.taketori.kassen.core.character.CharacterDef;
import com.taketori.kassen.core.character.CharacterManager;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.core.weapon.WeaponManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 加载期「旧版配置」判定的离线断言。
 *
 * <p>1.7.0 把角色与武器整体改名后，老服务器上的 characters.yml 里每一处武器引用
 * 都会悬空。ConfigManager 的规则是：
 * <ul>
 *   <li>悬空条目数 == 武器引用总数 → 判定为旧版文件，回退到 jar 内置默认值；</li>
 *   <li>悬空条目数 &lt; 武器引用总数 → 判定为手改配置写错，照常抛异常。</li>
 * </ul>
 * 这两条的分界错了，要么老服务器升级后启用失败，要么手写错键被静默吞掉。
 * 判定用的两个辅助方法是 private static，这里用反射直接验证它们。
 */
public final class LegacyConfigFallbackTest {

    private static int passed;
    private static int failed;

    private static Method danglingWeapons;
    private static Method refCount;

    public static void main(String[] args) {
        try {
            danglingWeapons = ConfigManager.class.getDeclaredMethod(
                    "danglingWeapons", CharacterManager.class, WeaponManager.class);
            danglingWeapons.setAccessible(true);
            refCount = ConfigManager.class.getDeclaredMethod("refCount", CharacterManager.class);
            refCount.setAccessible(true);
        } catch (NoSuchMethodException ex) {
            System.out.println("[FAIL] 找不到判定用的辅助方法，ConfigManager 可能已改名：" + ex.getMessage());
            System.out.println("failed=1 passed=0");
            return;
        }

        // 新版武器表只装一把，用来区分「全部悬空」和「个别写错」。
        WeaponManager weapons = new WeaponManager();
        weapons.register(weapon("tsukuyomi_hammer"));

        // 一、旧版角色表：两处引用全部悬空 → 应判定为旧版文件，触发回退。
        CharacterManager legacy = new CharacterManager();
        legacy.register(character("old_char", List.of("old_hammer", "old_bell")));
        int legacyDangling = dangling(legacy, weapons);
        int legacyRefs = refs(legacy);
        check("旧版角色表：两处引用全部悬空", legacyDangling == 2 && legacyRefs == 2);
        check("旧版角色表：满足回退条件（悬空数 >= 引用总数）", legacyDangling >= legacyRefs);

        // 二、手改写错一个键：只悬空一处 → 不满足回退，应照常报错。
        CharacterManager typo = new CharacterManager();
        typo.register(character("tsukuyomi", List.of("tsukuyomi_hammer", "typo_weapon")));
        int typoDangling = dangling(typo, weapons);
        int typoRefs = refs(typo);
        check("手写错键：只悬空一处", typoDangling == 1 && typoRefs == 2);
        check("手写错键：不满足回退条件（悬空数 < 引用总数）", typoDangling < typoRefs);

        // 三、配置正确时不应该有任何悬空。
        CharacterManager clean = new CharacterManager();
        clean.register(character("tsukuyomi", List.of("tsukuyomi_hammer")));
        check("配置正确：零悬空", dangling(clean, weapons) == 0);
        check("配置正确：引用总数为 1", refs(clean) == 1);

        // 四、空角色表不能触发回退（引用总数 0，>= 比较会成立，必须靠 refCount 区分）。
        CharacterManager empty = new CharacterManager();
        check("空角色表：零悬空", dangling(empty, weapons) == 0);
        check("空角色表：引用总数为 0", refs(empty) == 0);

        // 五、多角色累加，确认 refCount 不是只看第一个角色。
        CharacterManager multi = new CharacterManager();
        multi.register(character("a", List.of("tsukuyomi_hammer")));
        multi.register(character("b", List.of("x", "y", "z")));
        check("多角色：引用总数为 4", refs(multi) == 4);
        check("多角色：悬空 3 处", dangling(multi, weapons) == 3);
        check("多角色：3 < 4，不会误判成旧版文件", dangling(multi, weapons) < refs(multi));

        System.out.println("LegacyConfigFallbackTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static WeaponDef weapon(String id) {
        return new WeaponDef(id, "tsukuyomi", id, "STICK", 0, null, null, null, null, null);
    }

    private static CharacterDef character(String id, List<String> weapons) {
        return new CharacterDef(id, id, weapons, Map.of(), List.of());
    }

    private static int dangling(CharacterManager characters, WeaponManager weapons) {
        try {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) danglingWeapons.invoke(null, characters, weapons);
            return result.size();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int refs(CharacterManager characters) {
        try {
            return (Integer) refCount.invoke(null, characters);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[OK]   " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
