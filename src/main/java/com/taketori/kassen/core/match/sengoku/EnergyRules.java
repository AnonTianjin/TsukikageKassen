package com.taketori.kassen.core.match.sengoku;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 能量槽与必杀技规则（来自 {@code sengoku-energy.yml}）。纯数据。
 *
 * <p>来源是击杀中地小兵（需求第 15 条："击败小兵可以积攒能量槽，能量槽满后可释放必杀技"），
 * 释放方式是<b>占用现有 Q 槽</b>——不新增按键，也就没有按键冲突风险。</p>
 *
 * @param max              能量上限（达到即为"满"）
 * @param perMinion        击杀一只普通中地小兵回多少能量
 * @param perEliteMinion   击杀精英回多少（战国模式目前没有精英，留给将来）
 * @param display          能量显示方式
 * @param ultimateSlot     释放必杀占用的输入槽；当前只支持 {@code q}
 * @param defaultUltimate  未单独配置的角色用这一套
 * @param perCharacter     逐角色覆盖；key 是角色 id
 */
public record EnergyRules(int max,
                          int perMinion,
                          int perEliteMinion,
                          Display display,
                          String ultimateSlot,
                          UltimateSpec defaultUltimate,
                          Map<String, UltimateSpec> perCharacter) {

    /** 能量显示方式。 */
    public enum Display {
        /** 动作栏（默认：不占 BossBar，BossBar 留给小局比分）。 */
        ACTIONBAR,
        /** 单独一条 BossBar（需要与小局比分 BossBar 区分，实现上要多一条）。 */
        BOSSBAR,
        /** 不显示，只靠"满了"时的提示。 */
        NONE
    }

    /**
     * 一套必杀技效果。
     *
     * <p>当前实现的是"范围伤害 + 击退 + 可选减速"这一族语义（与 {@code shockwave} 同构）。
     * {@code type} 字段先保留给将来接入技能注册表，现在只用于日志与将来的分派。</p>
     *
     * @param type          效果类型名（前向兼容用）
     * @param damage        中心伤害
     * @param radius        作用半径（格）
     * @param knockback     水平击退强度
     * @param launch        垂直抬升
     * @param slowDuration  附加减速时长（tick，0 = 不减速）
     * @param slowAmplifier 减速等级（0 = I 级）
     * @param particle      施放粒子
     * @param sound         施放音效
     */
    public record UltimateSpec(String type,
                               double damage,
                               double radius,
                               double knockback,
                               double launch,
                               int slowDuration,
                               int slowAmplifier,
                               String particle,
                               String sound) {

        public static UltimateSpec defaults() {
            return new UltimateSpec("shockwave", 14.0D, 5.0D, 0.8D, 0.4D, 0, 0,
                    "EXPLOSION", "ENTITY_GENERIC_EXPLODE");
        }
    }

    /** 默认：上限 100、每只小兵 8 点、动作栏显示、占用 Q 槽、全员同一套必杀。 */
    public static EnergyRules defaults() {
        return new EnergyRules(100, 8, 20, Display.ACTIONBAR, "q",
                UltimateSpec.defaults(), Map.of());
    }

    /** 能量上限，至少 1（写成 0 会导致永远攒不满）。 */
    public int safeMax() {
        return Math.max(1, max);
    }

    /** 单次回能，至少 0。 */
    public int safePerMinion() {
        return Math.max(0, perMinion);
    }

    public int safePerEliteMinion() {
        return Math.max(0, perEliteMinion);
    }

    /** 是不是"占用 Q 槽"（当前唯一支持的取值）。 */
    public boolean usesQSlot() {
        return ultimateSlot == null || ultimateSlot.isBlank()
                || "q".equalsIgnoreCase(ultimateSlot.trim());
    }

    /**
     * 取某个角色该用哪套必杀：优先逐角色覆盖，否则用默认。
     *
     * <p>用户确认"先给全员一个统一默认，逐角色覆盖留位置"，所以这里的分支是刻意留的。</p>
     */
    public UltimateSpec ultimateFor(String characterId) {
        UltimateSpec fallback = defaultUltimate == null ? UltimateSpec.defaults() : defaultUltimate;
        if (characterId == null || perCharacter == null || perCharacter.isEmpty()) {
            return fallback;
        }
        String key = characterId.trim().toLowerCase(Locale.ROOT);
        UltimateSpec spec = perCharacter.get(key);
        return spec == null ? fallback : spec;
    }

    /** 能量是否已满。 */
    public boolean isFull(int current) {
        return current >= safeMax();
    }

    /** 把能量夹到 [0, max]。 */
    public int clamp(int value) {
        return Math.max(0, Math.min(safeMax(), value));
    }

    /** 便于构造逐角色覆盖表（保持插入顺序，日志可读）。 */
    public static Map<String, UltimateSpec> newCharacterMap() {
        return new LinkedHashMap<>();
    }
}
