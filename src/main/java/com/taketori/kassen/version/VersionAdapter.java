package com.taketori.kassen.version;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

/**
 * 版本适配契约（计划 §5.6 第 2 条）。
 *
 * <p>所有"名字易变"的东西都必须经过这里：属性、粒子、音效、药水效果。
 * 技能代码只拿到解析后的对象，不关心它来自哪个版本、叫什么名字。</p>
 *
 * <p>原则：<b>能力探测优先于版本号比较</b>，且任何地方都不出现
 * {@code if (version >= x)} 这样的分支。</p>
 */
public interface VersionAdapter {

    String name();

    /** 例如 "attack_damage" —— 同时兼容 1.21.1 的 generic.attack_damage 与更高版本的 attack_damage。 */
    Attribute attribute(String configuredName);

    /** 例如 "SNOWFLAKE" / "sweep_attack"。 */
    Particle particle(String configuredName);

    /** 例如 "ENTITY_FIREWORK_ROCKET_LAUNCH" 或 "entity.firework_rocket.launch"。 */
    Sound sound(String configuredName);

    /** 例如 "SLOWNESS" / "RESISTANCE"。 */
    PotionEffectType potionEffect(String configuredName);

    /** 能力探测，供上层做降级处理。 */
    boolean supports(String feature);
}
