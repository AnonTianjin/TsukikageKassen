package com.taketori.kassen.core.cooldown;

import com.taketori.kassen.core.skill.SkillSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 插件自身的冷却管理（不使用 Bukkit 的物品冷却显示，避免污染原版表现）。
 *
 * <p>键 = 玩家 UUID + 武器 ID + 槽位，所以"锤击冷却"与"火箭弹冷却"互不影响，
 * 切换模式后各自独立计时也符合预期。</p>
 *
 * <p>结构是<b>两层表</b>（玩家 → 该玩家的各槽位到点时间），而不是把所有条目拍平成一张
 * "拼字符串 → 到点时间"的表。原因有两条：一是不必为每次查询拼接 {@code uuid|weapon|slot}
 * 字符串（冷却条与派发都在热路径上）；二是玩家退出时 {@link #clearPlayer} 可以整张内层表丢弃，
 * 不用再按前缀扫全表。</p>
 *
 * <p>时钟可注入（{@link #CooldownManager(LongSupplier)}），因此过期、清理这些时序行为
 * 能用 {@code tools/CooldownManagerTest.java} 离线断言，不需要 sleep 等真实时间。</p>
 */
public final class CooldownManager {

    /**
     * 冷却键：玩家 + 武器 + 槽位。
     *
     * <p>做成 record 而不是拼字符串：相等性由字段决定，不会出现
     * {@code "a|b|c"} 这类拼接歧义，也不产生临时字符串。</p>
     *
     * @param uuid     玩家；{@code null} 时所有查询都按"没有冷却"处理（调用方不必判空）
     * @param weaponId 武器 id；{@code null} 归一成空串
     * @param slot     槽位；{@code null} 表示"该武器不分槽位"，与具体槽位不互相影响
     */
    public record Key(UUID uuid, String weaponId, SkillSlot slot) {

        public Key {
            weaponId = weaponId == null ? "" : weaponId;
        }

        /** 保留旧的 {@code uuid|weapon|slot} 文本形式，便于日志与 doctor 输出排查。 */
        @Override
        public String toString() {
            return (uuid == null ? "?" : uuid.toString())
                    + "|" + weaponId
                    + "|" + (slot == null ? "?" : slot.key());
        }
    }

    private final Map<UUID, Map<Key, Long>> expiries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CooldownManager() {
        this(System::currentTimeMillis);
    }

    /**
     * 用指定时钟构造。
     *
     * @param clock 毫秒时钟（离线断言注入假时钟用）；{@code null} 回落系统时钟
     */
    public CooldownManager(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** 构造冷却键。{@code uuid} 为 {@code null} 时该键永远视为就绪。 */
    public static Key key(UUID uuid, String weaponId, SkillSlot slot) {
        return new Key(uuid, weaponId, slot);
    }

    public boolean isReady(Key key) {
        return remainingMillis(key) <= 0L;
    }

    /**
     * 剩余毫秒数。
     *
     * <p>顺带把读到的过期条目删掉：冷却条每 5 tick 就会查一次在场的全部槽位，
     * 借这条热路径回收过期条目，比另起一个清理任务更省事。</p>
     */
    public long remainingMillis(Key key) {
        if (key == null || key.uuid() == null) {
            return 0L;
        }
        Map<Key, Long> playerCooldowns = expiries.get(key.uuid());
        if (playerCooldowns == null) {
            return 0L;
        }
        Long until = playerCooldowns.get(key);
        if (until == null) {
            return 0L;
        }
        long now = clock.getAsLong();
        if (until <= now) {
            // 条件删除：期间被 set 成新的到点时间时不会误删
            playerCooldowns.remove(key, until);
            return 0L;
        }
        return until - now;
    }

    public double remainingSeconds(Key key) {
        return remainingMillis(key) / 1000.0D;
    }

    /** 设置冷却；{@code seconds <= 0} 等价于清除该槽位的冷却。 */
    public void set(Key key, double seconds) {
        if (key == null || key.uuid() == null) {
            return;
        }
        if (seconds <= 0.0D) {
            clear(key);
            return;
        }
        expiries.computeIfAbsent(key.uuid(), id -> new ConcurrentHashMap<>())
                .put(key, clock.getAsLong() + (long) (seconds * 1000.0D));
    }

    public void clear(Key key) {
        if (key == null || key.uuid() == null) {
            return;
        }
        Map<Key, Long> playerCooldowns = expiries.get(key.uuid());
        if (playerCooldowns != null) {
            playerCooldowns.remove(key);
        }
    }

    /** 玩家退出 / 换世界时清理，避免状态泄漏（整张内层表丢弃，O(1)）。 */
    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            expiries.remove(uuid);
        }
    }

    public void clearAll() {
        expiries.clear();
    }

    /**
     * 未过期的条目数。
     *
     * <p>只数仍然生效的冷却：doctor 用它判断"冷却表有没有泄漏"，
     * 把一堆早已过期的残留条目算进去会让这个数字失去意义。</p>
     */
    public int size() {
        long now = clock.getAsLong();
        int count = 0;
        for (Map<Key, Long> playerCooldowns : expiries.values()) {
            for (Long until : playerCooldowns.values()) {
                if (until != null && until > now) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 清掉所有已过期的条目，返回清理条数。
     *
     * <p>日常靠 {@link #remainingMillis} 顺带回收即可；这条用于插件卸载与
     * 长时间无人查询的玩家（例如一直挂在大厅、从不放技能的人）。</p>
     */
    public int pruneExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Map<Key, Long> playerCooldowns : expiries.values()) {
            for (Map.Entry<Key, Long> entry : playerCooldowns.entrySet()) {
                Long until = entry.getValue();
                if (until != null && until <= now && playerCooldowns.remove(entry.getKey(), until)) {
                    removed++;
                }
            }
        }
        return removed;
    }
}
