package com.taketori.kassen.core.skill;

/**
 * 统一输入槽位（三槽模型）。
 *
 * <p>2.0 起每把武器只保留三个技能位：</p>
 * <ul>
 *   <li>{@link #LEFT} —— 左键。近战武器留空 = 原版普攻（被动写在武器的 {@code passive} 段）；
 *       弓没有近战普攻，这一位放主动技能。</li>
 *   <li>{@link #RIGHT} —— 右键。留空 = 原版行为（弓拉弓、三叉戟投掷、盾格挡）。</li>
 *   <li>{@link #SNEAK} —— <b>双击潜行</b>，只允许移动类技能（加速 / 位移 / 跳跃）。</li>
 * </ul>
 *
 * <p>旧的 {@code shift-right} 与 {@code q} 槽已下线：前者改为双击潜行，后者随多模式一起移除。</p>
 */
public enum SkillSlot {

    LEFT("left"),
    RIGHT("right"),
    SNEAK("sneak");

    private final String key;

    SkillSlot(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static SkillSlot byKey(String key) {
        for (SkillSlot slot : values()) {
            if (slot.key.equalsIgnoreCase(key)) {
                return slot;
            }
        }
        return null;
    }
}
