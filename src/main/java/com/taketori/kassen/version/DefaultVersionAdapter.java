package com.taketori.kassen.version;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认适配实现：用"注册表 + 规范化名字匹配"来解析跨版本名字。
 *
 * <p>为什么不用编译期常量：{@code Attribute.GENERIC_ATTACK_DAMAGE} 在 1.21.1 是枚举常量，
 * 而在更高版本 Attribute 已注册表化并改名为 {@code ATTACK_DAMAGE}。直接引用常量会让源码
 * 与某个具体版本绑死，这正是设计稿要求"版本差异集中到适配层"的原因。</p>
 *
 * <p>因此这里：</p>
 * <ol>
 *   <li>先用配置名直接查注册表；</li>
 *   <li>失败则遍历注册表做<b>规范化匹配</b>（忽略大小写、点、下划线、连字符与 generic 前缀）；</li>
 *   <li>再失败则回退到枚举 valueOf（老版本仍是 enum 的场合）；</li>
 *   <li>结果全部缓存，解析只发生一次。</li>
 * </ol>
 *
 * <p>注册表字段通过反射获取，是为了让同一份 jar 在"字段名不同"的版本上也能跑——
 * 这是受控的、集中在本包内的兼容回退，不涉及 NMS 内部实现。</p>
 */
public final class DefaultVersionAdapter implements VersionAdapter {

    private static final String[] ATTRIBUTE_FIELDS = {"ATTRIBUTE", "ATTRIBUTES"};
    private static final String[] PARTICLE_FIELDS = {"PARTICLE_TYPE", "PARTICLE"};
    private static final String[] SOUND_FIELDS = {"SOUNDS", "SOUND"};
    private static final String[] POTION_FIELDS = {"POTION_EFFECT_TYPE", "POTION_EFFECT"};

    // 并发容器：resolve 可能被异步路径调用，普通 HashMap 的 check-then-put 存在竞态
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "default";
    }

    @Override
    public Attribute attribute(String configuredName) {
        return (Attribute) resolve("attribute", configuredName, Attribute.class);
    }

    @Override
    public Particle particle(String configuredName) {
        return (Particle) resolve("particle", configuredName, Particle.class);
    }

    @Override
    public Sound sound(String configuredName) {
        return (Sound) resolve("sound", configuredName, Sound.class);
    }

    @Override
    public PotionEffectType potionEffect(String configuredName) {
        return (PotionEffectType) resolve("potion", configuredName, PotionEffectType.class);
    }

    @Override
    public boolean supports(String feature) {
        if (feature == null) {
            return false;
        }
        return switch (feature.toLowerCase(Locale.ROOT)) {
            case "registry.attribute" -> registry(ATTRIBUTE_FIELDS) != null;
            case "registry.particle" -> registry(PARTICLE_FIELDS) != null;
            case "registry.sound" -> registry(SOUND_FIELDS) != null;
            case "registry.potion" -> registry(POTION_FIELDS) != null;
            default -> false;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object resolve(String kind, String configuredName, Class<?> enumType) {
        if (configuredName == null || configuredName.isBlank()) {
            return null;
        }
        String cacheKey = kind + '|' + configuredName;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        Object result = null;
        String target = normalize(configuredName);

        Registry<?> registry = registry(fieldsFor(kind));
        if (registry != null) {
            // 1) 直接命中
            NamespacedKey direct = NamespacedKey.minecraft(configuredName.toLowerCase(Locale.ROOT).replace('_', '.'));
            Object candidate = registry.get(direct);
            if (candidate != null) {
                result = candidate;
            }
            // 2) 规范化遍历匹配（处理 generic 前缀、点/下划线混用等差异）
            if (result == null) {
                for (Object entry : registry) {
                    if (entry != null && normalize(keyOf(entry)).equals(target)) {
                        result = entry;
                        break;
                    }
                }
            }
        }

        // 3) 枚举回退（老版本）
        if (result == null && enumType != null && enumType.isEnum()) {
            try {
                Method valueOf = enumType.getMethod("valueOf", String.class);
                result = valueOf.invoke(null, configuredName.toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {
                result = null;
            }
        }

        if (result == null) {
            // 缓存"解析失败"，避免每次触发技能都重新遍历
            cache.put(cacheKey, null);
            return null;
        }
        cache.put(cacheKey, result);
        return result;
    }

    private String keyOf(Object entry) {
        if (entry instanceof Keyed keyed && keyed.getKey() != null) {
            return keyed.getKey().getKey();
        }
        return String.valueOf(entry);
    }

    private String[] fieldsFor(String kind) {
        return switch (kind) {
            case "attribute" -> ATTRIBUTE_FIELDS;
            case "particle" -> PARTICLE_FIELDS;
            case "sound" -> SOUND_FIELDS;
            case "potion" -> POTION_FIELDS;
            default -> new String[0];
        };
    }

    private Registry<?> registry(String[] candidates) {
        for (String candidate : candidates) {
            Registry<?> found = registry(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Registry<?> registry(String fieldName) {
        try {
            Field field = Registry.class.getField(fieldName);
            Object value = field.get(null);
            if (value instanceof Registry<?> registry) {
                return registry;
            }
        } catch (Throwable ignored) {
            // 该版本没有这个注册表字段，继续尝试下一个候选
        }
        return null;
    }

    /**
     * 规范化：忽略大小写，去掉命名空间、"generic" 前缀以及 . _ - 分隔符。
     * 于是 "GENERIC_ATTACK_DAMAGE"、"generic.attack_damage"、"attack-damage"
     * 都会归一到 "attackdamage"，从而跨版本命中同一个属性。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toLowerCase(Locale.ROOT);
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        text = text.replace("generic", "");
        return text.replace(".", "").replace("_", "").replace("-", "").trim();
    }
}
