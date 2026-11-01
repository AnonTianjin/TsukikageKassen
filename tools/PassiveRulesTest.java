import com.taketori.kassen.core.skill.PassiveDef;
import com.taketori.kassen.core.skill.SkillDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线回归：武器被动的参数解析与钳制。
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/core/skill/*.java \
 *   tools/PassiveRulesTest.java
 * java -cp build/test-classes PassiveRulesTest
 * </pre>
 */
public class PassiveRulesTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        emptyAndDefaults();
        parsing();
        clamping();
        triggers();

        System.out.println("PassiveRulesTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void emptyAndDefaults() {
        expectBool("空技能 → 没有被动", false, PassiveDef.of(null).isPresent());
        expectBool("type 为空 → 没有被动", false, PassiveDef.of(new SkillDef("", 0.0D, Map.of())).isPresent());
        expectBool("只有 display 不算生效", false,
                PassiveDef.of(passive(Map.of("display", "空被动"))).isPresent());
        expectStr("默认 on-hit 是 NONE", "NONE",
                PassiveDef.of(passive(Map.of("display", "x"))).onHit().name());
        expectDouble("默认移速为 0", 0.0D, PassiveDef.of(passive(Map.of("display", "x"))).speed());
        expectBool("被动类型常量", true, "passive".equals(PassiveDef.TYPE));
    }

    private static void parsing() {
        PassiveDef mark = PassiveDef.of(passive(Map.of(
                "display", "穿心",
                "on-hit", "mark",
                "chance", 0.25,
                "mark-bonus", 0.12,
                "ticks", 80)));
        expectBool("易伤被动生效", true, mark.isPresent());
        expectStr("on-hit 解析为 MARK", "MARK", mark.onHit().name());
        expectDouble("易伤强度", 0.12D, mark.markBonus());
        expectInt("持续 tick", 80, mark.ticks());

        PassiveDef slow = PassiveDef.of(passive(Map.of(
                "on-hit", "slow",
                "slow-amplifier", 1,
                "slow-duration", 60,
                "ticks", 20)));
        expectInt("减速等级", 1, slow.slowAmplifier());
        expectInt("独立减速时长优先于 ticks", 60, slow.effectiveSlowTicks());

        PassiveDef slowFallback = PassiveDef.of(passive(Map.of("on-hit", "slow", "ticks", 40)));
        expectInt("没写 slow-duration 时回落到 ticks", 40, slowFallback.effectiveSlowTicks());

        expectStr("中文别名：破甲", "ARMOR_BREAK",
                PassiveDef.of(passive(Map.of("on-hit", "破甲"))).onHit().name());
        expectStr("中文别名：吸血", "LIFESTEAL",
                PassiveDef.of(passive(Map.of("on-hit", "吸血"))).onHit().name());
        expectDouble("吸血比例", 0.2D,
                PassiveDef.of(passive(Map.of("on-hit", "lifesteal", "lifesteal", 0.2D))).lifestealRatio());

        PassiveDef trident = PassiveDef.of(passive(Map.of(
                "on-hit", "slow",
                "chance", 0.25,
                "throw-chance", 1.0,
                "bonus-damage", 2.0,
                "particle", "SNOWFLAKE",
                "sound", "BLOCK_GLASS_BREAK")));
        expectDouble("投掷必定触发", 1.0D, trident.throwChance());
        expectBool("有附加伤害", true, trident.hasBonusDamage());
        expectStr("粒子名保留", "SNOWFLAKE", trident.particle());
    }

    private static void clamping() {
        PassiveDef wild = PassiveDef.of(passive(Map.of(
                "on-hit", "mark",
                "chance", 5.0,
                "throw-chance", -2.0,
                "mark-bonus", 3.0,
                "pierce", 4.0,
                "lifesteal", 9.0,
                "resistance", 99,
                "jump", -5,
                "speed", 2.0,
                "bonus-damage", -3.0)));
        expectDouble("chance 上限 1", 1.0D, wild.chance());
        expectDouble("throw-chance 下限 0", 0.0D, wild.throwChance());
        expectDouble("mark-bonus 上限 1", 1.0D, wild.markBonus());
        expectDouble("pierce 上限 1", 1.0D, wild.pierce());
        expectDouble("lifesteal 上限 1", 1.0D, wild.lifestealRatio());
        expectInt("药水等级上限 4", 4, wild.resistance());
        expectInt("负数等级钳到 0", 0, wild.jump());
        expectDouble("移速上限 1", 1.0D, wild.speed());
        expectBool("负的附加伤害等于没有", false, wild.hasBonusDamage());

        PassiveDef unknown = PassiveDef.of(passive(Map.of("on-hit", "不存在的效果", "chance", 0.5)));
        expectStr("未知 on-hit 回落 NONE", "NONE", unknown.onHit().name());
        expectBool("未知效果不算生效", false, unknown.isPresent());
    }

    private static void triggers() {
        PassiveDef meleeOnly = PassiveDef.of(passive(Map.of("on-hit", "mark", "chance", 0.2)));
        expectBool("近战可触发", true, meleeOnly.triggersOnMelee());
        expectBool("未写 throw-chance 时回落到 chance", true, meleeOnly.triggersOnProjectile());
        expectDouble("throw-chance 回落到 chance", 0.2D, meleeOnly.throwChance());

        PassiveDef rangedOnly = PassiveDef.of(passive(Map.of(
                "on-hit", "mark", "chance", 0.0, "throw-chance", 0.3)));
        expectBool("近战概率 0 → 不触发", false, rangedOnly.triggersOnMelee());
        expectBool("远程可触发", true, rangedOnly.triggersOnProjectile());

        PassiveDef alwaysOn = PassiveDef.of(passive(Map.of("speed", 0.05, "chance", 0.0)));
        expectBool("只有常驻属性也算生效", true, alwaysOn.isPresent());
        expectBool("没有 on-hit → 不触发近战效果", false, alwaysOn.triggersOnMelee());
    }

    private static SkillDef passive(Map<String, Object> params) {
        return new SkillDef(PassiveDef.TYPE, 0.0D, new LinkedHashMap<>(params));
    }

    // ── 断言助手 ──────────────────────────────────────────────────────

    private static void expectBool(String name, boolean expected, boolean actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectInt(String name, int expected, int actual) {
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
