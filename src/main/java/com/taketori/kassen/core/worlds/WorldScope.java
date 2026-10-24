package com.taketori.kassen.core.worlds;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多世界兼容的判定：大厅"接管哪些世界"与"消息播报给哪些世界"。
 *
 * <p>纯逻辑，不引 Bukkit —— 可以离线单测（见 {@code tools/WorldScopeTest.java}），
 * 也避免把世界规则的判断散落在各个监听器里。</p>
 */
public final class WorldScope {

    /** 表示"所有世界"的通配符。 */
    public static final String WILDCARD = "*";

    private WorldScope() {
    }

    /**
     * 玩家进服时是否应该被"接管"（自动送到大厅）。
     *
     * <ul>
     *   <li>列表含 {@code *} → 所有世界都接管（旧行为）；</li>
     *   <li>列表非空 → 只有玩家当前所在世界在列表里才接管；</li>
     *   <li>列表为空（默认）→ <b>只接管大厅出生点所在的世界</b>，别的世界一概不碰。</li>
     * </ul>
     *
     * 任一参数缺失（没配大厅出生点、玩家世界读不到）→ 不接管。
     */
    public static boolean allowsTakeover(List<String> configured, String lobbyWorld, String playerWorld) {
        if (playerWorld == null || playerWorld.isBlank()) {
            return false;
        }
        Set<String> allowed = normalize(configured);
        if (allowed.contains(WILDCARD)) {
            return true;
        }
        if (!allowed.isEmpty()) {
            return allowed.contains(playerWorld.trim().toLowerCase(Locale.ROOT));
        }
        return lobbyWorld != null && !lobbyWorld.isBlank()
                && lobbyWorld.trim().toLowerCase(Locale.ROOT).equals(playerWorld.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 广播消息时，位于 {@code playerWorld} 的玩家是否应该收到。
     *
     * @param scope         配置值：{@code all} = 全服（旧行为）；其它值 = 只发给消息所属世界
     * @param messageWorlds 这条消息所属的世界集合（例如对局世界、大厅世界）
     * @param playerWorld   接收者所在的世界
     */
    public static boolean shouldReceive(String scope, Set<String> messageWorlds, String playerWorld) {
        if (scope != null && "all".equalsIgnoreCase(scope.trim())) {
            return true;
        }
        if (playerWorld == null || playerWorld.isBlank()) {
            return false;
        }
        if (messageWorlds == null || messageWorlds.isEmpty()) {
            // 没记录到消息所属世界（场地还没配）→ 不静默丢消息，按全服处理
            return true;
        }
        for (String world : messageWorlds) {
            if (world != null && world.trim().equalsIgnoreCase(playerWorld.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 把配置里的世界名列表规范化（去空白、转小写、去重，保留顺序）。 */
    public static Set<String> normalize(List<String> configured) {
        Set<String> result = new LinkedHashSet<>();
        if (configured == null) {
            return result;
        }
        for (String raw : configured) {
            if (raw == null) {
                continue;
            }
            String text = raw.trim().toLowerCase(Locale.ROOT);
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    /** 一行中文说明（/taketori doctor 与命令回显用）。 */
    public static String describeTakeover(List<String> configured, String lobbyWorld) {
        Set<String> allowed = normalize(configured);
        if (allowed.contains(WILDCARD)) {
            return "所有世界（takeover-worlds 里写了 *）";
        }
        if (!allowed.isEmpty()) {
            return "白名单：" + String.join(", ", allowed);
        }
        if (lobbyWorld == null || lobbyWorld.isBlank()) {
            return "仅大厅世界（但大厅出生点还没设置 → 实际不接管任何世界）";
        }
        return "仅大厅世界：" + lobbyWorld;
    }
}
