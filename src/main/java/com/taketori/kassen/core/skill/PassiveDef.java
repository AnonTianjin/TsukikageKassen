package com.taketori.kassen.core.skill;

import java.util.Locale;

/**
 * 武器被动技能的类型化视图（来自 weapons.yml 的 {@code passive:} 段）。纯数据，无 Bukkit 依赖。
 *
 * <p>被动不占按键槽位：近战武器的被动挂在<b>普攻命中</b>上，弓挂在<b>箭矢命中</b>上，
 * 三叉戟两种命中都吃（投掷必定触发，近战按 {@code chance} 掷骰）。</p>
 *
 * <p>所有数值都在这里钳制一遍：配置写错只影响数值，不会让插件起不来。</p>
 *
 * @param display      显示名（MiniMessage）
 * @param speed        常驻移速加成（比例，0.05 = +5%）
 * @param resistance   常驻抗性等级（0 = 无，1 = 抗性 I）
 * @param jump         常驻跳跃提升等级（0 = 无）
 * @param onHit        命中触发效果
 * @param chance       近战命中触发概率（0..1）
 * @param throwChance  箭矢 / 投掷命中触发概率（0..1；未配置时回落到 {@code chance}）
 * @param markBonus    易伤增伤比例
 * @param pierce       破甲比例
 * @param ticks        命中效果持续 tick（易伤 / 破甲 / 减速 / 虚弱）
 * @param slowAmplifier 减速等级（0 = I 级）
 * @param slowDuration  减速持续 tick（0 时回落到 {@code ticks}）
 * @param lifestealRatio 吸血比例（0.2 = 造成伤害的 20% 回血）
 * @param bonusDamage   命中附加的额外伤害（点，2 点 = 1 颗心）
 * @param particle      命中特效粒子
 * @param sound         命中特效音效
 */
public record PassiveDef(String display,
                         double speed,
                         int resistance,
                         int jump,
                         OnHit onHit,
                         double chance,
                         double throwChance,
                         double markBonus,
                         double pierce,
                         int ticks,
                         int slowAmplifier,
                         int slowDuration,
                         double lifestealRatio,
                         double bonusDamage,
                         String particle,
                         String sound) {

    /** 命中触发的效果种类。 */
    public enum OnHit {
        NONE,
        MARK,
        ARMOR_BREAK,
        SLOW,
        LIFESTEAL,
        KNOCKBACK
    }

    /** weapons.yml 里被动段的 type 值。 */
    public static final String TYPE = "passive";

    private static final PassiveDef NONE = new PassiveDef("", 0.0D, 0, 0, OnHit.NONE,
            0.0D, 0.0D, 0.0D, 0.0D, 0, 0, 0, 0.0D, 0.0D, "", "");

    /** 没有配置被动。 */
    public static PassiveDef none() {
        return NONE;
    }

    /** 从技能的 params 解析；没有配置的键全部走安全默认值。 */
    public static PassiveDef of(SkillDef def) {
        if (def == null || !def.isPresent()) {
            return NONE;
        }
        double chance = clamp01(def.dbl("chance", 0.0D));
        double throwChance = clamp01(def.dbl("throw-chance", chance));
        int ticks = Math.max(0, def.integer("ticks", 60));
        int slowDuration = Math.max(0, def.integer("slow-duration", ticks));
        return new PassiveDef(
                def.str("display", "被动"),
                clampRange(def.dbl("speed", 0.0D), 0.0D, 1.0D),
                clampLevel(def.integer("resistance", 0)),
                clampLevel(def.integer("jump", 0)),
                parseOnHit(def.str("on-hit", "")),
                chance,
                throwChance,
                clamp01(def.dbl("mark-bonus", 0.0D)),
                clamp01(def.dbl("pierce", 0.0D)),
                ticks,
                clampLevel(def.integer("slow-amplifier", 0)),
                slowDuration,
                clamp01(def.dbl("lifesteal", 0.0D)),
                Math.max(0.0D, def.dbl("bonus-damage", 0.0D)),
                def.str("particle", ""),
                def.str("sound", ""));
    }

    /** 是否有任何实际效果（纯展示用的空段视为没有）。 */
    public boolean isPresent() {
        return speed > 0.0D || resistance > 0 || jump > 0 || onHit != OnHit.NONE;
    }

    /** 命中的额外伤害：只有配置了 {@code bonus-damage} 才生效。 */
    public boolean hasBonusDamage() {
        return bonusDamage > 0.0D;
    }

    /** 近战命中是否触发（概率判定交给调用方掷骰）。 */
    public boolean triggersOnMelee() {
        return onHit != OnHit.NONE && chance > 0.0D;
    }

    /** 箭矢 / 投掷命中是否触发。 */
    public boolean triggersOnProjectile() {
        return onHit != OnHit.NONE && throwChance > 0.0D;
    }

    /** 减速持续 tick；配置了独立的 {@code slow-duration} 就用它。 */
    public int effectiveSlowTicks() {
        return slowDuration > 0 ? slowDuration : ticks;
    }

    private static OnHit parseOnHit(String raw) {
        if (raw == null || raw.isBlank()) {
            return OnHit.NONE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "mark", "易伤" -> OnHit.MARK;
            case "armor_break", "armor-break", "破甲" -> OnHit.ARMOR_BREAK;
            case "slow", "减速" -> OnHit.SLOW;
            case "lifesteal", "吸血" -> OnHit.LIFESTEAL;
            case "knockback", "击退" -> OnHit.KNOCKBACK;
            default -> OnHit.NONE;
        };
    }

    private static double clamp01(double value) {
        return clampRange(value, 0.0D, 1.0D);
    }

    private static double clampRange(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int clampLevel(int value) {
        return Math.max(0, Math.min(4, value));
    }
}
