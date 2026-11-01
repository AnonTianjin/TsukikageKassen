import com.taketori.kassen.core.cooldown.CooldownManager;
import com.taketori.kassen.core.skill.SkillSlot;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 离线回归：插件冷却表的键结构、过期判定与清理。
 *
 * <p>用注入的假时钟推进时间，所以"到期""过期残留"这类时序行为不需要 sleep。</p>
 *
 * <pre>
 * javac -encoding UTF-8 --release 21 -d build/test-classes \
 *   src/main/java/com/taketori/kassen/core/skill/SkillSlot.java \
 *   src/main/java/com/taketori/kassen/core/cooldown/CooldownManager.java \
 *   tools/CooldownManagerTest.java
 * java -cp build/test-classes CooldownManagerTest
 * </pre>
 */
public class CooldownManagerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        basics();
        isolation();
        expiryAndCleanup();
        keys();

        System.out.println("CooldownManagerTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── 基本读写 ──────────────────────────────────────────────────────

    private static void basics() {
        FakeClock clock = new FakeClock();
        CooldownManager manager = clock.manager();

        CooldownManager.Key hammer = CooldownManager.key(ALICE, "tsukuyomi_hammer", SkillSlot.RIGHT);
        expectBool("初始就绪", true, manager.isReady(hammer));
        expectLong("初始剩余 0", 0L, manager.remainingMillis(hammer));

        manager.set(hammer, 6.0D);
        expectBool("设置后进入冷却", false, manager.isReady(hammer));
        expectLong("剩余 6000ms", 6000L, manager.remainingMillis(hammer));
        expectDouble("剩余 6.0s", 6.0D, manager.remainingSeconds(hammer));

        clock.advance(3_000L);
        expectLong("推进 3 秒后剩 3000ms", 3000L, manager.remainingMillis(hammer));
        expectBool("未到期仍不可用", false, manager.isReady(hammer));

        clock.advance(3_000L);
        expectBool("到点即就绪（含边界）", true, manager.isReady(hammer));
        expectLong("到期后剩余 0", 0L, manager.remainingMillis(hammer));

        // 重复设置是覆盖而不是叠加
        manager.set(hammer, 4.0D);
        clock.advance(1_000L);
        manager.set(hammer, 2.0D);
        expectLong("重复设置覆盖为最新时长", 2000L, manager.remainingMillis(hammer));

        // seconds <= 0 等价于清除
        manager.set(hammer, 0.0D);
        expectBool("set(0) 清除冷却", true, manager.isReady(hammer));
        manager.set(hammer, 5.0D);
        manager.set(hammer, -3.0D);
        expectBool("set(负数) 清除冷却", true, manager.isReady(hammer));

        manager.set(hammer, 5.0D);
        manager.clear(hammer);
        expectBool("clear 立即解除", true, manager.isReady(hammer));
    }

    // ── 维度隔离：玩家 / 武器 / 槽位 ─────────────────────────────────

    private static void isolation() {
        FakeClock clock = new FakeClock();
        CooldownManager manager = clock.manager();

        CooldownManager.Key aliceLeft = CooldownManager.key(ALICE, "tsukuyomi_hammer", SkillSlot.LEFT);
        CooldownManager.Key aliceRight = CooldownManager.key(ALICE, "tsukuyomi_hammer", SkillSlot.RIGHT);
        CooldownManager.Key aliceSneak = CooldownManager.key(ALICE, "tsukuyomi_hammer", SkillSlot.SNEAK);
        CooldownManager.Key aliceOtherWeapon = CooldownManager.key(ALICE, "sakura_bow", SkillSlot.RIGHT);
        CooldownManager.Key bobRight = CooldownManager.key(BOB, "tsukuyomi_hammer", SkillSlot.RIGHT);

        manager.set(aliceRight, 6.0D);

        expectBool("槽位隔离：左键不受右键冷却影响", true, manager.isReady(aliceLeft));
        expectBool("槽位隔离：双击潜行不受右键冷却影响", true, manager.isReady(aliceSneak));
        expectBool("武器隔离：另一把武器不受影响", true, manager.isReady(aliceOtherWeapon));
        expectBool("玩家隔离：别人不受影响", true, manager.isReady(bobRight));

        manager.set(aliceLeft, 2.0D);
        manager.set(bobRight, 3.0D);
        expectLong("红蓝各自计时：ALICE 右键 6000", 6000L, manager.remainingMillis(aliceRight));
        expectLong("红蓝各自计时：ALICE 左键 2000", 2000L, manager.remainingMillis(aliceLeft));
        expectLong("红蓝各自计时：BOB 右键 3000", 3000L, manager.remainingMillis(bobRight));

        manager.clearPlayer(ALICE);
        expectBool("clearPlayer 清掉该玩家的右键", true, manager.isReady(aliceRight));
        expectBool("clearPlayer 清掉该玩家的左键", true, manager.isReady(aliceLeft));
        expectBool("clearPlayer 不动其他玩家", false, manager.isReady(bobRight));
        expectInt("clearPlayer 后只剩 1 条", 1, manager.size());

        manager.clearAll();
        expectBool("clearAll 清空全部", true, manager.isReady(bobRight));
        expectInt("clearAll 后 size 为 0", 0, manager.size());
    }

    // ── 过期条目的回收 ───────────────────────────────────────────────

    private static void expiryAndCleanup() {
        FakeClock clock = new FakeClock();
        CooldownManager manager = clock.manager();

        CooldownManager.Key a = CooldownManager.key(ALICE, "w", SkillSlot.LEFT);
        CooldownManager.Key b = CooldownManager.key(ALICE, "w", SkillSlot.RIGHT);
        CooldownManager.Key c = CooldownManager.key(BOB, "w", SkillSlot.RIGHT);

        manager.set(a, 2.0D);
        manager.set(b, 10.0D);
        manager.set(c, 2.0D);
        expectInt("三条都在有效期内", 3, manager.size());

        clock.advance(3_000L);
        expectInt("size 只统计未过期的条目", 1, manager.size());

        // 读到过期条目会顺手回收：不再需要额外清理也能把表缩小
        expectBool("读取已过期的键 → 就绪", true, manager.isReady(a));
        expectBool("再次读取 → 依旧就绪", true, manager.isReady(c));
        expectInt("读取触发惰性回收后 size 仍为 1", 1, manager.size());

        clock.advance(10_000L);
        expectInt("全部过期后 size 为 0", 0, manager.size());

        // b 从未被读取过，残留在表里；prune 负责把它真正删掉
        expectInt("pruneExpired 清掉 1 条从未被读取的残留", 1, manager.pruneExpired());
        expectInt("重复 prune 没有可清的条目", 0, manager.pruneExpired());

        // 从未被查询过的过期条目（例如挂机玩家）必须能被一次性扫掉
        CooldownManager bulk = clock.manager();
        bulk.set(CooldownManager.key(ALICE, "w", SkillSlot.LEFT), 1.0D);
        bulk.set(CooldownManager.key(ALICE, "w", SkillSlot.RIGHT), 1.0D);
        bulk.set(CooldownManager.key(BOB, "w", SkillSlot.SNEAK), 1.0D);
        clock.advance(2_000L);
        expectInt("pruneExpired 一次清掉 3 条", 3, bulk.pruneExpired());
        expectInt("清理后表为空", 0, bulk.size());

        // 清理不会影响之后重新设置
        bulk.set(CooldownManager.key(ALICE, "w", SkillSlot.LEFT), 1.0D);
        expectBool("清理后重新设置照常生效", false,
                bulk.isReady(CooldownManager.key(ALICE, "w", SkillSlot.LEFT)));
    }

    // ── 键本身 ───────────────────────────────────────────────────────

    private static void keys() {
        CooldownManager.Key left = CooldownManager.key(ALICE, "w", SkillSlot.LEFT);
        CooldownManager.Key sameLeft = CooldownManager.key(ALICE, "w", SkillSlot.LEFT);
        CooldownManager.Key right = CooldownManager.key(ALICE, "w", SkillSlot.RIGHT);

        expectBool("同参数的两个键 equals", true, left.equals(sameLeft));
        expectInt("同参数的两个键 hashCode 相同", left.hashCode(), sameLeft.hashCode());
        expectBool("不同槽位的键不相等", false, left.equals(right));

        CooldownManager.Key nullWeapon = CooldownManager.key(ALICE, null, SkillSlot.LEFT);
        expectStr("null 武器 id 归一成空串", "", nullWeapon.weaponId());
        expectBool("null 武器 id 与空串等价", true,
                nullWeapon.equals(CooldownManager.key(ALICE, "", SkillSlot.LEFT)));

        expectStr("toString 保留旧的排查格式",
                ALICE + "|tsukuyomi_hammer|right",
                CooldownManager.key(ALICE, "tsukuyomi_hammer", SkillSlot.RIGHT).toString());
        expectStr("null 槽位在 toString 里显示为 ?",
                ALICE + "|w|?", CooldownManager.key(ALICE, "w", null).toString());

        // uuid 缺失时按"没有冷却"处理，调用方不必到处判空
        FakeClock clock = new FakeClock();
        CooldownManager manager = clock.manager();
        CooldownManager.Key orphan = CooldownManager.key(null, "w", SkillSlot.LEFT);
        expectBool("uuid 为 null → 永远就绪", true, manager.isReady(orphan));
        manager.set(orphan, 5.0D);
        expectInt("uuid 为 null → set 不写入", 0, manager.size());
        manager.clear(orphan);
        expectBool("uuid 为 null → 不抛异常", true, manager.isReady(null));
    }

    // ── 断言助手 ──────────────────────────────────────────────────────

    /** 可推进的假时钟：让到期行为确定、无需 sleep。 */
    private static final class FakeClock {

        private final AtomicLong now = new AtomicLong(1_000_000L);

        CooldownManager manager() {
            return new CooldownManager(this::millis);
        }

        long millis() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static void expectBool(String name, boolean expected, boolean actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectInt(String name, int expected, int actual) {
        check(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void expectLong(String name, long expected, long actual) {
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
