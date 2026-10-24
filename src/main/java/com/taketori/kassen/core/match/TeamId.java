package com.taketori.kassen.core.match;

import java.util.Locale;

/**
 * 对局队伍。纯数据，无 Bukkit 依赖。
 */
public enum TeamId {

    RED("红队", "<red>"),
    BLUE("蓝队", "<blue>");

    private final String display;
    private final String colorTag;

    TeamId(String display, String colorTag) {
        this.display = display;
        this.colorTag = colorTag;
    }

    public String display() {
        return display;
    }

    /** MiniMessage 颜色标签，用于播报与记分板。 */
    public String colorTag() {
        return colorTag;
    }

    public TeamId opposite() {
        return this == RED ? BLUE : RED;
    }

    public static TeamId byName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "red", "r", "红", "红队" -> RED;
            case "blue", "b", "蓝", "蓝队" -> BLUE;
            default -> null;
        };
    }

    /** "RED"/"BLUE"，配置文件里的键名。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
