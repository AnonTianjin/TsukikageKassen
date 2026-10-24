package com.taketori.kassen.core.weapon;

import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一把武器的完整定义（载体、属性、模式、四个按键槽位）。
 * 纯数据，无 Bukkit 依赖。
 */
public final class WeaponDef {

    /** 一个模式：可以有自己的一套槽位定义，整段替换武器级同名槽位。 */
    public static final class ModeDef {

        private final String id;
        private final String display;
        private final Map<SkillSlot, SkillDef> skills;

        public ModeDef(String id, String display, Map<SkillSlot, SkillDef> skills) {
            this.id = id;
            this.display = display;
            this.skills = skills == null ? Collections.emptyMap() : new LinkedHashMap<>(skills);
        }

        public String id() {
            return id;
        }

        public String display() {
            return display;
        }

        public Map<SkillSlot, SkillDef> skills() {
            return Collections.unmodifiableMap(skills);
        }
    }

    private final String id;
    private final String characterId;
    private final String display;
    private final String materialName;
    private final int modelData;
    private final List<String> lore;
    private final Map<String, Double> attributes;
    private final String defaultMode;
    private final Map<String, ModeDef> modes;
    private final Map<SkillSlot, SkillDef> skills;
    /** 命中附加效果（weapons.yml 的 hit-effects 段），例如夕月的箭随机挂负面效果。 */
    private final Map<String, Object> hitEffects;

    public WeaponDef(String id,
                     String characterId,
                     String display,
                     String materialName,
                     int modelData,
                     List<String> lore,
                     Map<String, Double> attributes,
                     String defaultMode,
                     Map<String, ModeDef> modes,
                     Map<SkillSlot, SkillDef> skills) {
        this(id, characterId, display, materialName, modelData, lore, attributes, defaultMode, modes, skills,
                Collections.emptyMap());
    }

    public WeaponDef(String id,
                     String characterId,
                     String display,
                     String materialName,
                     int modelData,
                     List<String> lore,
                     Map<String, Double> attributes,
                     String defaultMode,
                     Map<String, ModeDef> modes,
                     Map<SkillSlot, SkillDef> skills,
                     Map<String, Object> hitEffects) {
        this.id = id;
        this.characterId = characterId;
        this.display = display;
        this.materialName = materialName;
        this.modelData = modelData;
        this.lore = lore == null ? List.of() : List.copyOf(lore);
        this.attributes = attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes);
        this.defaultMode = defaultMode == null ? "" : defaultMode;
        this.modes = modes == null ? Collections.emptyMap() : new LinkedHashMap<>(modes);
        this.skills = skills == null ? Collections.emptyMap() : new LinkedHashMap<>(skills);
        this.hitEffects = hitEffects == null ? Collections.emptyMap() : new LinkedHashMap<>(hitEffects);
    }

    public String id() {
        return id;
    }

    public String characterId() {
        return characterId;
    }

    public String display() {
        return display;
    }

    public String materialName() {
        return materialName;
    }

    public int modelData() {
        return modelData;
    }

    public List<String> lore() {
        return lore;
    }

    public Map<String, Double> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public double attribute(String key, double fallback) {
        Double value = attributes.get(key);
        return value == null ? fallback : value;
    }

    public String defaultMode() {
        return defaultMode;
    }

    public Map<String, ModeDef> modes() {
        return Collections.unmodifiableMap(modes);
    }

    public ModeDef mode(String modeId) {
        return modes.get(modeId);
    }

    public String modeDisplay(String modeId) {
        ModeDef mode = modes.get(modeId);
        return mode == null || mode.display() == null ? modeId : mode.display();
    }

    public Map<SkillSlot, SkillDef> baseSkills() {
        return Collections.unmodifiableMap(skills);
    }

    /**
     * 解析某个按键槽在当前模式下实际生效的技能。
     * 模式内的定义整段替换武器级定义，避免"数值叠加"这种难以排查的行为。
     */
    public SkillDef skill(SkillSlot slot, String modeId) {
        if (slot == null) {
            return SkillDef.none();
        }
        ModeDef mode = modeId == null ? null : modes.get(modeId);
        if (mode != null) {
            SkillDef override = mode.skills().get(slot);
            if (override != null && override.isPresent()) {
                return override;
            }
        }
        SkillDef base = skills.get(slot);
        return base == null ? SkillDef.none() : base;
    }

    /** Q 键切换模式时的下一个模式（按 YAML 声明顺序循环）。 */
    public String nextMode(String currentMode) {
        if (modes.isEmpty()) {
            return currentMode;
        }
        List<String> ids = List.copyOf(modes.keySet());
        int index = ids.indexOf(currentMode);
        if (index < 0) {
            return ids.get(0);
        }
        return ids.get((index + 1) % ids.size());
    }

    /** 该武器是否声明了多个模式（只有多模式时 Q 才做模式切换）。 */
    public boolean hasModes() {
        return modes.size() > 1;
    }

    // ---------------------------------------------------------------- 命中附加效果

    /** 原始 hit-effects 段（只读）。 */
    public Map<String, Object> hitEffects() {
        return Collections.unmodifiableMap(hitEffects);
    }

    public boolean hasHitEffects() {
        return !hitEffects.isEmpty();
    }

    public String hitEffectStr(String key, String fallback) {
        Object value = hitEffects.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public double hitEffectDbl(String key, double fallback) {
        Object value = hitEffects.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int hitEffectInt(String key, int fallback) {
        return (int) Math.round(hitEffectDbl(key, fallback));
    }

    /** 列表型取值；同时接受 YAML 列表与逗号分隔的字符串，方便手写。 */
    public List<String> hitEffectList(String key) {
        Object value = hitEffects.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            List<String> result = new java.util.ArrayList<>();
            for (String item : text.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "WeaponDef{" + id + ", material=" + materialName + ", modes=" + modes.keySet() + '}';
    }
}
