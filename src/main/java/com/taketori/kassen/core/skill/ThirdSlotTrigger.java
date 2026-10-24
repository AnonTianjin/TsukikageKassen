package com.taketori.kassen.core.skill;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 第三槽技能（F 键那一槽）的触发方式。
 *
 * <p>为什么要有这么多选项：原版客户端不能自己绑定按键，服务端只能"接管"已有的动作。
 * 每种接管方式都有各自的坑，所以这里做成可配置、可多选，服务器按自己环境挑。</p>
 *
 * <ul>
 *   <li>{@link #DOUBLE_SNEAK}（默认）：<b>双击潜行键</b>。潜行是独立按键，原版一定会发出
 *       {@code PlayerToggleSneakEvent}，不受"对着方块右键"这类原版分支影响，是当前最可靠的一种。
 *       要求潜行键为"按住"模式（默认就是），玩家正常按住潜行不会误触发（只有 300 毫秒内两次按下才算）。</li>
 *   <li>{@link #SNEAK_Q}：<b>潜行 + Q</b>。Q 的丢弃事件在潜行时照常触发，可靠性高；
 *       代价是要同时按两个键，而 Q 本身还兼任"模式切换"。</li>
 *   <li>{@link #DOUBLE_RIGHT}：<b>双击右键</b>。不需要任何新键，而且能直接解决
 *       "潜行右键先被右键槽吃掉"的问题（第二次右键会被改派到第三槽）；缺点是连点右键时容易误触发。</li>
 *   <li>{@link #F}：原版 <b>F 键（交换副手）</b>。最直观，但部分服务器/插件会把这个动作吞掉，
 *       表现就是"按 F 完全没反应"——遇到这种情况请换用其它触发方式。</li>
 *   <li>{@link #SNEAK_RIGHT}：<b>潜行 + 右键</b>（旧行为）。注意：原版在"潜行 + 右键方块"时
 *       <b>根本不会</b>发出交互事件，所以它只有在对着空气右键时才可靠。</li>
 * </ul>
 */
public enum ThirdSlotTrigger {

    DOUBLE_SNEAK("double-sneak"),
    SNEAK_Q("sneak-q"),
    DOUBLE_RIGHT("double-right"),
    F("f"),
    SNEAK_RIGHT("sneak-right");

    private final String key;

    ThirdSlotTrigger(String key) {
        this.key = key;
    }

    /** 配置里写的名字。 */
    public String key() {
        return key;
    }

    /** 中文显示名（提示与文档用）。 */
    public String display() {
        return switch (this) {
            case DOUBLE_SNEAK -> "双击潜行键";
            case SNEAK_Q -> "潜行 + Q";
            case DOUBLE_RIGHT -> "双击右键";
            case F -> "F 键（交换副手）";
            case SNEAK_RIGHT -> "潜行 + 右键";
        };
    }

    /** 默认触发方式：双击潜行。 */
    public static Set<ThirdSlotTrigger> defaults() {
        return EnumSet.of(DOUBLE_SNEAK);
    }

    /**
     * 解析配置值，可以写多个（用逗号 / 空格 / 加号分隔），也支持两个常用简写：
     *
     * <ul>
     *   <li>{@code all}  —— 全部启用（排查按键问题时很方便）</li>
     *   <li>{@code both} —— {@code f} + {@code sneak-right}（旧版语义，保留兼容）</li>
     * </ul>
     *
     * 空值或不认识的写法回落到默认的 {@code double-sneak}，不会因为写错而完全没法放技能。
     */
    public static Set<ThirdSlotTrigger> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(text) || "*".equals(text)) {
            return EnumSet.allOf(ThirdSlotTrigger.class);
        }
        if ("both".equals(text)) {
            return EnumSet.of(F, SNEAK_RIGHT);
        }
        Set<ThirdSlotTrigger> parsed = EnumSet.noneOf(ThirdSlotTrigger.class);
        for (String token : text.split("[,+\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            for (ThirdSlotTrigger trigger : values()) {
                if (trigger.key.equals(token) || trigger.name().equalsIgnoreCase(token)) {
                    parsed.add(trigger);
                }
            }
        }
        return parsed.isEmpty() ? defaults() : parsed;
    }

    /** 解析时被忽略的写法（供配置加载时点名提示）。 */
    public static Set<String> unknownTokens(String raw) {
        Set<String> unknown = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return unknown;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(text) || "*".equals(text) || "both".equals(text)) {
            return unknown;
        }
        for (String token : text.split("[,+\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            boolean matched = false;
            for (ThirdSlotTrigger trigger : values()) {
                if (trigger.key.equals(token) || trigger.name().equalsIgnoreCase(token)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unknown.add(token);
            }
        }
        return unknown;
    }

    /** 逗号拼接的配置写法（命令回显用）。 */
    public static String join(Set<ThirdSlotTrigger> triggers) {
        StringBuilder builder = new StringBuilder();
        for (ThirdSlotTrigger trigger : triggers) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(trigger.key);
        }
        return builder.toString();
    }
}
