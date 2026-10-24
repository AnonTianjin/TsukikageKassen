package com.taketori.kassen.core.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 大厅告示牌可绑定的动作（唯一来源）。
 *
 * <p>过去动作名在四个地方各写一遍：{@code LobbySign} 的注释、{@code /taketori lobby addsign} 的用法提示、
 * 管理员菜单的按钮说明、tab 补全 —— 于是"实际支持了但提示里没有"这种不一致反复出现
 * （菜单与排行榜就漏了很久）。现在所有展示、补全与文档都从这一个枚举派生。</p>
 *
 * <p>点击告示牌的行为分两类：</p>
 * <ul>
 *   <li><b>打开界面</b>：{@link #JOIN} / {@link #LEAVE} / {@link #SPECTATE} / {@link #LOBBY} / {@link #MENU}
 *       打开玩家菜单，{@link #CHARACTER} 打开角色菜单，{@link #RANKS} 打开排行榜；
 *       具体动作由界面里的按钮执行 —— 告示牌只负责把入口指到 GUI。</li>
 *   <li><b>直接执行</b>：{@link #CHARACTER_ID}（<code>character:&lt;角色id&gt;</code>）本身就是
 *       "就选这个角色"，仍然直接绑定，不再多一步点界面。</li>
 * </ul>
 */
public enum LobbyAction {

    JOIN("join", "快速加入对局（无房时自动创建新房间并加入）"),
    LEAVE("leave", "离开等待房间回大厅 / 退出观战"),
    SPECTATE("spectate", "旁观当前对局（观众模式）"),
    CHARACTER("character", "打开角色选择菜单"),
    CHARACTER_ID("character:", "直接选择指定角色（character:<角色id>）"),
    MENU("menu", "打开玩家菜单"),
    ROOMS("rooms", "打开房间列表（加入等待房间 / 旁观进行中房间）"),
    CREATE("create", "创建房间（降临月宫：复制模板世界并进入）"),
    RANKS("ranks", "打开总计排行榜"),
    LOBBY("lobby", "传送回大厅");

    /** 参数化动作的前缀。 */
    public static final String CHARACTER_PREFIX = "character:";

    private final String key;
    private final String description;

    LobbyAction(String key, String description) {
        this.key = key;
        this.description = description;
    }

    /** 配置里写的动作名。 */
    public String key() {
        return key;
    }

    /** 中文说明（提示、列表、文档用）。 */
    public String description() {
        return description;
    }

    /** 是否是带参数的动作（只有 character:&lt;角色id&gt;）。 */
    public boolean parameterized() {
        return this == CHARACTER_ID;
    }

    /** 点击后是否打开图形界面（只有 character:&lt;角色id&gt; 是直接执行）。 */
    public boolean opensGui() {
        return this != CHARACTER_ID;
    }

    /** 解析告示牌动作；不认识（或 character: 后面没写角色 id）时返回 null。 */
    public static LobbyAction of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith(CHARACTER_PREFIX)) {
            return text.length() > CHARACTER_PREFIX.length() ? CHARACTER_ID : null;
        }
        for (LobbyAction action : values()) {
            if (!action.parameterized() && action.key.equals(text)) {
                return action;
            }
        }
        return null;
    }

    /** 取出 character:&lt;角色id&gt; 里的角色 id；不是这个动作时返回 null。 */
    public static String characterIdOf(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (!text.startsWith(CHARACTER_PREFIX)) {
            return null;
        }
        String id = text.substring(CHARACTER_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }

    /** "/taketori lobby addsign &lt;动作&gt;" 里的那一串。 */
    public static String usageKeys() {
        StringBuilder builder = new StringBuilder();
        for (LobbyAction action : values()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(action == CHARACTER_ID ? "character:<角色id>" : action.key);
        }
        return builder.toString();
    }

    /** tab 补全候选（含 character: 前缀形式，方便直接补角色 id）。 */
    public static List<String> tabCompletions() {
        List<String> keys = new ArrayList<>();
        for (LobbyAction action : values()) {
            keys.add(action.key);
        }
        return keys;
    }

    /** 一行中文清单（管理员菜单按钮说明用）。 */
    public static String describeAll() {
        StringBuilder builder = new StringBuilder();
        for (LobbyAction action : values()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(action.key).append(" —— ").append(action.description);
        }
        return builder.toString();
    }

    /** 逗号分隔的动作名（日志与自检用）。 */
    public static String keys() {
        StringBuilder builder = new StringBuilder();
        for (LobbyAction action : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(action.key);
        }
        return builder.toString();
    }
}
