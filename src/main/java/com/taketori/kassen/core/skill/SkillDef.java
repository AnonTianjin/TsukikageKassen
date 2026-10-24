package com.taketori.kassen.core.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个技能槽的配置：类型 + 冷却 + 参数。
 *
 * <p>纯数据对象，<b>不含任何 Bukkit 依赖</b>——技能的行为由 paper 层的实现类决定，
 * 这里只描述"是什么、多久一次、数值多少"。</p>
 */
public final class SkillDef {

    private static final SkillDef NONE = new SkillDef("", 0.0D, Collections.emptyMap());

    private final String type;
    private final double cooldownSeconds;
    private final Map<String, Object> params;

    public SkillDef(String type, double cooldownSeconds, Map<String, Object> params) {
        this.type = type == null ? "" : type;
        this.cooldownSeconds = Math.max(0.0D, cooldownSeconds);
        this.params = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);
    }

    /** 空技能：该槽位没有绑定任何技能。 */
    public static SkillDef none() {
        return NONE;
    }

    public boolean isPresent() {
        return !type.isEmpty();
    }

    public String type() {
        return type;
    }

    public double cooldownSeconds() {
        return cooldownSeconds;
    }

    public Map<String, Object> params() {
        return Collections.unmodifiableMap(params);
    }

    public boolean has(String key) {
        return params.get(key) != null;
    }

    public String str(String key, String fallback) {
        Object value = params.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public double dbl(String key, double fallback) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int integer(String key, int fallback) {
        return (int) Math.round(dbl(key, fallback));
    }

    public boolean bool(String key, boolean fallback) {
        Object value = params.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    /**
     * 覆盖一个数值参数，返回新的技能定义（不改动原对象）。
     * 供模式覆盖等场景使用。
     */
    public SkillDef withParam(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.put(key, value);
        return new SkillDef(type, cooldownSeconds, copy);
    }

    @Override
    public String toString() {
        return "SkillDef{" + type + ", cd=" + cooldownSeconds + ", params=" + params + '}';
    }
}
