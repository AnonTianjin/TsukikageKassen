package com.taketori.kassen.core.character;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色注册表 + 玩家档案管理。纯内存，持久化交给 data 层。
 */
public final class CharacterManager {

    private final Map<String, CharacterDef> definitions = new LinkedHashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public void clearDefinitions() {
        definitions.clear();
    }

    public void register(CharacterDef def) {
        if (def != null && def.id() != null && !def.id().isBlank()) {
            definitions.put(def.id(), def);
        }
    }

    public CharacterDef get(String id) {
        return id == null ? null : definitions.get(id);
    }

    public boolean has(String id) {
        return id != null && definitions.containsKey(id);
    }

    public Collection<CharacterDef> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public java.util.Set<String> ids() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    public int size() {
        return definitions.size();
    }

    public PlayerProfile profile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, id -> new PlayerProfile(id, null));
    }

    public PlayerProfile profileOrNull(UUID uuid) {
        return profiles.get(uuid);
    }

    public void bind(UUID uuid, String characterId) {
        profile(uuid).setCharacterId(characterId);
    }

    /** 解绑角色并清空该玩家的模式/状态。 */
    public void unbind(UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null) {
            profile.setCharacterId(null);
            profile.clearCombatState();
        }
    }

    public void forget(UUID uuid) {
        profiles.remove(uuid);
    }
}
