package com.taketori.kassen.core.weapon;

import com.taketori.kassen.core.skill.PassiveDef;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一把武器的完整定义（载体、属性、三个技能槽、被动、附魔）。
 * 纯数据，无 Bukkit 依赖。
 *
 * <p>2.0 起不再有"模式"：一把武器一套技能。被动独立成段，不占槽位。</p>
 */
public final class WeaponDef {

    private final String id;
    private final String characterId;
    private final String display;
    private final String materialName;
    private final int modelData;
    private final List<String> lore;
    private final Map<String, Double> attributes;
    private final Map<SkillSlot, SkillDef> skills;
    private final PassiveDef passive;
    /** 物品附魔（weapons.yml 的 enchantments 段），例如三叉戟的忠诚 III。 */
    private final Map<String, Integer> enchantments;
    /** 命中附加效果（旧字段，箭矢随机减益仍在使用）。 */
    private final Map<String, Object> hitEffects;

    public WeaponDef(String id,
                     String characterId,
                     String display,
                     String materialName,
                     int modelData,
                     List<String> lore,
                     Map<String, Double> attributes,
                     Map<SkillSlot, SkillDef> skills,
                     PassiveDef passive,
                     Map<String, Integer> enchantments,
                     Map<String, Object> hitEffects) {
        this.id = id;
        this.characterId = characterId;
        this.display = display;
        this.materialName = materialName;
        this.modelData = modelData;
        this.lore = lore == null ? List.of() : List.copyOf(lore);
        this.attributes = attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes);
        this.skills = skills == null ? Collections.emptyMap() : new LinkedHashMap<>(skills);
        this.passive = passive == null ? PassiveDef.none() : passive;
        this.enchantments = enchantments == null ? Collections.emptyMap() : new LinkedHashMap<>(enchantments);
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

    /** 武器被动（没有配置时返回 {@link PassiveDef#none()}）。 */
    public PassiveDef passive() {
        return passive;
    }

    public boolean hasPassive() {
        return passive.isPresent();
    }

    public Map<SkillSlot, SkillDef> skills() {
        return Collections.unmodifiableMap(skills);
    }

    /**
     * 取某个按键槽绑定的技能。
     *
     * <p>没有绑定就是 {@link SkillDef#none()}——调用方据此决定"放行原版行为"，
     * 这也是弓/三叉戟右键能走原版射击的前提。</p>
     */
    public SkillDef skill(SkillSlot slot) {
        if (slot == null) {
            return SkillDef.none();
        }
        SkillDef def = skills.get(slot);
        return def == null ? SkillDef.none() : def;
    }

    /** 物品附魔（键为原版附魔 id，如 {@code loyalty}）。 */
    public Map<String, Integer> enchantments() {
        return Collections.unmodifiableMap(enchantments);
    }

    public boolean hasEnchantments() {
        return !enchantments.isEmpty();
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
        return "WeaponDef{" + id + ", material=" + materialName + ", slots=" + skills.keySet() + '}';
    }
}
