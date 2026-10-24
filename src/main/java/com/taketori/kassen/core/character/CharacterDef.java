package com.taketori.kassen.core.character;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色定义：能拿哪些武器 + 基础属性。纯数据，无 Bukkit 依赖。
 */
public final class CharacterDef {

    private final String id;
    private final String display;
    private final List<String> weapons;
    private final Map<String, Double> attributes;
    private final List<String> lore;

    public CharacterDef(String id,
                        String display,
                        List<String> weapons,
                        Map<String, Double> attributes,
                        List<String> lore) {
        this.id = id;
        this.display = display;
        this.weapons = weapons == null ? List.of() : List.copyOf(weapons);
        this.attributes = attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes);
        this.lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public List<String> weapons() {
        return weapons;
    }

    public Map<String, Double> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public double attribute(String key, double fallback) {
        Double value = attributes.get(key);
        return value == null ? fallback : value;
    }

    public List<String> lore() {
        return lore;
    }

    public boolean hasWeapon(String weaponId) {
        return weaponId != null && weapons.contains(weaponId);
    }

    @Override
    public String toString() {
        return "CharacterDef{" + id + ", weapons=" + weapons + '}';
    }
}
