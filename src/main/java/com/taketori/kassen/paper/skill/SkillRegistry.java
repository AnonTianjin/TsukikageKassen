package com.taketori.kassen.paper.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 技能类型 → 实现 的注册表。 */
public final class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public void register(Skill skill) {
        if (skill != null && skill.type() != null && !skill.type().isBlank()) {
            skills.put(skill.type().toLowerCase(java.util.Locale.ROOT), skill);
        }
    }

    public Skill get(String type) {
        return type == null ? null : skills.get(type.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean has(String type) {
        return get(type) != null;
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(skills.keySet());
    }

    public int size() {
        return skills.size();
    }
}
