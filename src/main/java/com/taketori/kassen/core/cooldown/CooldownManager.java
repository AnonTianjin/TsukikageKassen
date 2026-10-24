package com.taketori.kassen.core.cooldown;

import com.taketori.kassen.core.skill.SkillSlot;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件自身的冷却管理（不使用 Bukkit 的物品冷却显示，避免污染原版表现）。
 *
 * <p>键 = 玩家 UUID + 武器 ID + 槽位，所以"锤击冷却"与"火箭弹冷却"互不影响，
 * 切换模式后各自独立计时也符合预期。</p>
 */
public final class CooldownManager {

    private final Map<String, Long> expiries = new ConcurrentHashMap<>();

    public static String key(UUID uuid, String weaponId, SkillSlot slot) {
        return uuid + "|" + weaponId + "|" + (slot == null ? "?" : slot.key());
    }

    public boolean isReady(String key) {
        Long until = expiries.get(key);
        return until == null || until <= System.currentTimeMillis();
    }

    public long remainingMillis(String key) {
        Long until = expiries.get(key);
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public double remainingSeconds(String key) {
        return remainingMillis(key) / 1000.0D;
    }

    public void set(String key, double seconds) {
        if (seconds <= 0.0D) {
            expiries.remove(key);
            return;
        }
        expiries.put(key, System.currentTimeMillis() + (long) (seconds * 1000.0D));
    }

    public void clear(String key) {
        expiries.remove(key);
    }

    /** 玩家退出 / 换世界时清理，避免状态泄漏。 */
    public void clearPlayer(UUID uuid) {
        String prefix = uuid + "|";
        Iterator<Map.Entry<String, Long>> iterator = expiries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().startsWith(prefix)) {
                iterator.remove();
            }
        }
    }

    public void clearAll() {
        expiries.clear();
    }

    public int size() {
        return expiries.size();
    }
}
