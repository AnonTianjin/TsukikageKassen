package com.taketori.kassen.paper.integration;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Floodgate（基岩版）适配：只处理<b>显示名</b>与身份识别，不碰任何数据键。
 *
 * <p>本插件的重连会话、队伍、封存背包、战绩全部以 UUID 为键，Floodgate 的 UUID 由 XUID
 * 派生、跨重启与跨服稳定，所以基岩玩家天然可用。唯一需要适配的是名字：Bukkit 侧基岩玩家
 * 通常带 Floodgate 的 {@code username-prefix}（默认 {@code .}），广播与排行榜会显示
 * {@code .Steve} 这种形式。</p>
 *
 * <p><b>为什么用反射</b>：Floodgate 是可选依赖。直接编译期引用 {@code floodgate-api}
 * 会让没装 Floodgate 的服务器在类加载阶段就 {@code NoClassDefFoundError}；
 * 反射只在 {@code floodgate} 插件已启用时才尝试，失败就整体退化为原始行为。</p>
 */
public final class BedrockSupport {

    private final TaketoriPlugin plugin;
    private boolean available;
    private Object api;
    private Method isFloodgatePlayer;
    private Method getPlayer;
    private Method getUsername;

    public BedrockSupport(TaketoriPlugin plugin) {
        this.plugin = plugin;
        init();
    }

    /**
     * 探测 Floodgate：没装就直接返回；装了但 API 版本对不上只记一条警告，
     * 不做任何降级之外的假设。整个方法不抛异常。
     */
    private void init() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                return;
            }
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return;
            }
            isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            getPlayer = apiClass.getMethod("getPlayer", UUID.class);
            Class<?> playerClass = Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer");
            getUsername = playerClass.getMethod("getUsername");
            available = true;
            plugin.getLogger().info("已接入 Floodgate：基岩版玩家显示名按原名 / 去前缀处理");
        } catch (Throwable throwable) {
            available = false;
            api = null;
            plugin.getLogger().log(Level.WARNING,
                    "检测到 floodgate 但 API 调用失败，基岩名适配关闭（不影响其它功能）", throwable);
        }
    }

    public boolean available() {
        return available;
    }

    /** 是否基岩版（Floodgate）玩家；没装 Floodgate 一律 false。 */
    public boolean isBedrock(UUID uuid) {
        if (!available || uuid == null) {
            return false;
        }
        try {
            Object result = isFloodgatePlayer.invoke(api, uuid);
            return result instanceof Boolean bedrock && bedrock;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Floodgate 记录的原名（不带前缀）；拿不到返回 {@code null}。 */
    public String bedrockName(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }
        try {
            Object player = getPlayer.invoke(api, uuid);
            if (player == null) {
                return null;
            }
            Object name = getUsername.invoke(player);
            return name instanceof String text && !text.isBlank() ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 统一的显示名入口：Floodgate 原名 → 传入的名字 → 本地 usercache → UUID 前 8 位。
     *
     * <p>只有基岩玩家才按 {@code floodgate} 段去前缀，正版 / 离线 Java 玩家原样返回，
     * 避免把名字本来就以 {@code .} 开头的离线账号误伤。</p>
     */
    public String displayName(UUID uuid, String fallback) {
        if (uuid == null) {
            return fallback == null || fallback.isBlank() ? "-" : fallback;
        }
        String name = fallback;
        if (name == null || name.isBlank()) {
            name = bedrockName(uuid);
        }
        if (name == null || name.isBlank()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            String cached = offline.getName();
            name = cached == null ? uuid.toString().substring(0, 8) : cached;
        }
        return isBedrock(uuid) ? strip(name) : name;
    }

    /** 按配置的前缀剥掉基岩名前缀；关闭开关或前缀为空时原样返回。 */
    public String strip(String name) {
        if (name == null) {
            return "";
        }
        if (!plugin.config().floodgateStripPrefix()) {
            return name;
        }
        String prefix = plugin.config().floodgatePrefix();
        if (prefix == null || prefix.isEmpty()) {
            return name;
        }
        if (name.length() > prefix.length() && name.startsWith(prefix)) {
            return name.substring(prefix.length());
        }
        return name;
    }
}
