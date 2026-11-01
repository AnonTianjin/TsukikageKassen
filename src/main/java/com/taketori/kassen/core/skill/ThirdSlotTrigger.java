package com.taketori.kassen.core.skill;

import java.util.Locale;
import java.util.Set;

/**
 * 第三槽（{@link SkillSlot#SNEAK}）的触发方式：<b>只保留双击潜行</b>。
 *
 * <p>2.0 之前这一槽可配置成 F / 潜行+Q / 双击右键 / 潜行+右键，每种都有自己的坑
 * （右键会被右键槽先吃掉、F 会被其它插件吞掉、潜行+右键在原版根本收不到事件）。
 * 现在统一成双击潜行：潜行是独立按键，原版一定会发出 {@code PlayerToggleSneakEvent}，
 * 且要求潜行键为"按住"模式（默认），正常按住潜行不会误触发。</p>
 *
 * <p>旧配置里的写法不再支持，但<b>不阻止启动</b>：加载时点名提示一次，然后按双击潜行处理，
 * 避免服务器因为一条历史配置直接起不来。</p>
 */
public final class ThirdSlotTrigger {

    /** 唯一的触发方式：300 毫秒内连续两次潜行。 */
    public static final String DOUBLE_SNEAK = "double-sneak";

    /** 已下线的旧写法（用于加载时点名）。 */
    private static final Set<String> REMOVED = Set.of(
            "sneak-right", "double-right", "f", "sneak-q", "both", "all", "*");

    private ThirdSlotTrigger() {
    }

    /** 配置值是否就是双击潜行（空值按默认处理）。 */
    public static boolean isDefault(String raw) {
        return raw == null || raw.isBlank() || DOUBLE_SNEAK.equalsIgnoreCase(raw.trim());
    }

    /** 配置值是否是已下线的旧写法（加载时点名用）。 */
    public static boolean isRemoved(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (REMOVED.contains(text)) {
            return true;
        }
        // "f,sneak-right" 这类多值写法：只要含有任一旧键就算旧写法
        for (String token : text.split("[,+\\s]+")) {
            if (REMOVED.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 命令行回显：现在只有一种触发方式。 */
    public static String display() {
        return "双击潜行键";
    }
}
