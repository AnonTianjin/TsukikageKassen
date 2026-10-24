package com.taketori.kassen.core.weapon;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 武器注册表。纯内存，配置重载时整体替换。 */
public final class WeaponManager {

    private final Map<String, WeaponDef> weapons = new LinkedHashMap<>();

    public void clear() {
        weapons.clear();
    }

    public void register(WeaponDef def) {
        if (def != null && def.id() != null && !def.id().isBlank()) {
            weapons.put(def.id(), def);
        }
    }

    public WeaponDef get(String id) {
        return id == null ? null : weapons.get(id);
    }

    public boolean has(String id) {
        return id != null && weapons.containsKey(id);
    }

    public Collection<WeaponDef> all() {
        return Collections.unmodifiableCollection(weapons.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(weapons.keySet());
    }

    public int size() {
        return weapons.size();
    }

    /** 某角色拥有的武器（按 weapons.yml 之外的声明顺序由角色定义给出）。 */
    public java.util.List<WeaponDef> ofCharacter(String characterId) {
        java.util.List<WeaponDef> result = new java.util.ArrayList<>();
        for (WeaponDef def : weapons.values()) {
            if (def.characterId() != null && def.characterId().equalsIgnoreCase(characterId)) {
                result.add(def);
            }
        }
        return result;
    }
}
