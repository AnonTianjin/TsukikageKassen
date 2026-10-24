package com.taketori.kassen.core.character;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 隐性标签注册表：每个标签带一个权重，用来决定"同一个队伍里抢同一个角色时谁优先"。
 *
 * <p><b>隐性</b>的意思是这个标签只影响分配规则，不对玩家做任何公开标记、也不影响战斗数值。
 * 权重越高越优先：同队出现角色冲突时，权重高的人拿到角色，权重低的人需要换一个；
 * 权重相同则保持"先到先得"（先选的人保住）。</p>
 *
 * <p>纯数据、零 Bukkit 依赖；<code>config.yml</code> 的 <code>tags</code> 段由 paper 层解析后注册进来。</p>
 */
public final class TagManager {

    /** 配置里没有 tags 段时使用的默认标签（权重 0）。 */
    public static final String DEFAULT_TAG = "default";

    /** 一个标签：id + 显示名 + 权重。 */
    public record TagDef(String id, String display, int weight) {
    }

    private final Map<String, TagDef> definitions = new LinkedHashMap<>();

    public void clear() {
        definitions.clear();
    }

    public void register(String id, String display, int weight) {
        if (id == null || id.isBlank()) {
            return;
        }
        String name = display == null || display.isBlank() ? id : display;
        definitions.put(id.toLowerCase(java.util.Locale.ROOT), new TagDef(id, name, weight));
    }

    public TagDef get(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public Collection<TagDef> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    public int size() {
        return definitions.size();
    }

    /** 权重；未知标签按 0 处理（等于没有任何优先权，不会因为写错标签名就报错）。 */
    public int weightOf(String id) {
        TagDef def = get(id);
        return def == null ? 0 : def.weight();
    }

    /** 标签显示名；未知标签直接回显 id，没有标签时返回"无"。 */
    public String displayOf(String id) {
        if (id == null || id.isBlank()) {
            return "无";
        }
        TagDef def = get(id);
        return def == null ? id : def.display();
    }

    /** 配置里定义的第一个标签，作为新玩家的默认值；没有配置时返回 DEFAULT_TAG。 */
    public String fallbackTag() {
        return definitions.isEmpty() ? DEFAULT_TAG : definitions.keySet().iterator().next();
    }
}
