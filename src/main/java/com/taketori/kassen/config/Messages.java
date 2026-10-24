package com.taketori.kassen.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * 文案管理。占位符使用 <code>{name}</code> 形式，先替换再交给 MiniMessage 解析，
 * 避免与 MiniMessage 的尖括号标签语法冲突。
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> raw = new HashMap<>();
    private String prefix = "";

    public void load(FileConfiguration config) {
        raw.clear();
        for (String key : config.getKeys(true)) {
            Object value = config.get(key);
            if (value instanceof String text && !config.isConfigurationSection(key)) {
                raw.put(key, text);
            }
        }
        // 老服务器的 messages.yml 不会自动包含新增的键；若只读用户文件，
        // 新文案会渲染成 "skill.grapple-miss" 这种原始键名。这里用内置默认值补齐。
        org.bukkit.configuration.ConfigurationSection defaults = config.getDefaults();
        if (defaults != null) {
            for (String key : defaults.getKeys(true)) {
                if (raw.containsKey(key) || defaults.isConfigurationSection(key)) {
                    continue;
                }
                Object value = defaults.get(key);
                if (value instanceof String text) {
                    raw.put(key, text);
                }
            }
        }
        prefix = raw.getOrDefault("prefix", "");
    }

    public String raw(String key) {
        return raw.getOrDefault(key, key);
    }

    /** 渲染成组件，支持任意数量 key/value 占位符。 */
    public Component get(String key, Object... placeholders) {
        return render(raw(key), placeholders);
    }

    /** 带统一前缀的组件。 */
    public Component prefixed(String key, Object... placeholders) {
        return render(prefix, new Object[0]).append(render(raw(key), placeholders));
    }

    private Component render(String template, Object... placeholders) {
        String text = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            String value = String.valueOf(placeholders[i + 1]);
            text = text.replace("{" + name + "}", value);
        }
        return MINI.deserialize(text);
    }

    /** 用于日志输出的纯文本形式。 */
    public String plain(String key, Object... placeholders) {
        String text = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + String.valueOf(placeholders[i]) + "}", String.valueOf(placeholders[i + 1]));
        }
        return text;
    }
}
