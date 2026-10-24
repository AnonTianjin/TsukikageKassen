package com.taketori.kassen.paper.skill;

import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import org.bukkit.entity.Player;

import com.taketori.kassen.TaketoriPlugin;

/**
 * 一次技能调用的上下文：谁、拿什么、按下哪个键、当前什么模式、数值是多少。
 *
 * <p>技能实现只依赖这个上下文，不自己去读 PDC、不自己判冷却——那些都在
 * InputManager / SkillManager 里完成，保证"输入 → 身份 → 技能"的单一通路。</p>
 */
public final class SkillContext {

    private final TaketoriPlugin plugin;
    private final Player player;
    private final WeaponDef weapon;
    private final SkillSlot slot;
    private final SkillDef def;
    private final String mode;

    public SkillContext(TaketoriPlugin plugin, Player player, WeaponDef weapon, SkillSlot slot, SkillDef def, String mode) {
        this.plugin = plugin;
        this.player = player;
        this.weapon = weapon;
        this.slot = slot;
        this.def = def;
        this.mode = mode;
    }

    public TaketoriPlugin plugin() {
        return plugin;
    }

    public Player player() {
        return player;
    }

    public WeaponDef weapon() {
        return weapon;
    }

    public SkillSlot slot() {
        return slot;
    }

    public SkillDef def() {
        return def;
    }

    public String mode() {
        return mode;
    }

    /** 技能显示名：优先取 params.display，否则用类型名。 */
    public String display() {
        return def.str("display", def.type());
    }

    /** 参数是否在配置里显式写了（用于"没写就走原版行为"这类判断）。 */
    public boolean has(String key) {
        return def.has(key);
    }

    public double dbl(String key, double fallback) {
        return def.dbl(key, fallback);
    }

    public int integer(String key, int fallback) {
        return def.integer(key, fallback);
    }

    public boolean bool(String key, boolean fallback) {
        return def.bool(key, fallback);
    }

    public String str(String key, String fallback) {
        return def.str(key, fallback);
    }
}
