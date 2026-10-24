package com.taketori.kassen.core.skill;

/**
 * 统一输入槽位（对应设计稿的按键规范）。
 * key 与 weapons.yml 中的字段名一致，改 YAML 不需要改代码。
 */
public enum SkillSlot {

    LEFT("left"),
    RIGHT("right"),
    SHIFT_RIGHT("shift-right"),
    Q("q");

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
