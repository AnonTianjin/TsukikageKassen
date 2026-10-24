package com.taketori.kassen.paper.effect;

import com.taketori.kassen.config.ConfigManager;
import com.taketori.kassen.version.VersionAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * 表现层：粒子与音效。
 *
 * <p>本期只用原版粒子与原版音效（不做资源包），所以技能之间的区分完全依赖
 * 粒子形态、音效与颜色组合（计划 §3.6）。名字全部经 {@link VersionAdapter} 解析，
 * 配置里写的是"人可读的名字"，不是版本相关的常量。</p>
 */
public final class Fx {

    private final ConfigManager config;
    private final VersionAdapter versions;

    public Fx(ConfigManager config, VersionAdapter versions) {
        this.config = config;
        this.versions = versions;
    }

    public void particle(String name, Location location, int count, double spread) {
        if (!config.particles() || name == null || name.isBlank() || location == null || location.getWorld() == null) {
            return;
        }
        Particle particle = versions.particle(name);
        if (particle == null) {
            return;
        }
        location.getWorld().spawnParticle(particle, location, Math.max(1, count), spread, spread, spread, 0.0D);
    }

    /** 带速度参数的粒子（用于推进/拖尾）。 */
    public void particle(String name, Location location, int count, double spread, double extra) {
        if (!config.particles() || name == null || name.isBlank() || location == null || location.getWorld() == null) {
            return;
        }
        Particle particle = versions.particle(name);
        if (particle == null) {
            return;
        }
        location.getWorld().spawnParticle(particle, location, Math.max(1, count), spread, spread, spread, extra);
    }

    public void sound(String name, Location location, float volume, float pitch) {
        if (!config.sounds() || name == null || name.isBlank() || location == null || location.getWorld() == null) {
            return;
        }
        Sound sound = versions.sound(name);
        if (sound == null) {
            return;
        }
        location.getWorld().playSound(location, sound, volume, pitch);
    }

    public void sound(String name, Player player, float volume, float pitch) {
        if (player == null) {
            return;
        }
        sound(name, player.getLocation(), volume, pitch);
    }

    public void actionBar(Player player, Component component) {
        if (player == null || component == null || !config.actionbar()) {
            return;
        }
        player.sendActionBar(component);
    }

    /**
     * 重技能施法演出：低沉的施法音 + 施法者屏幕 title 一闪。
     *
     * <p>与瞬发右键技能的"轻快短促"刻意区分——大范围技能（冲击波/镜光爆发这类）
     * 用更重的音色和 title 霸屏来传达"这一下有分量"。</p>
     */
    public void castHeavy(Player caster, String skillName) {
        if (caster == null || skillName == null || skillName.isBlank()) {
            return;
        }
        caster.showTitle(Title.title(
                MiniMessage.miniMessage().deserialize("<gold><bold>" + skillName + "</bold></gold>"),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMillis(200))));
        sound("ENTITY_EVOKER_CAST_SPELL", caster, 1.1F, 0.65F);
    }
}
