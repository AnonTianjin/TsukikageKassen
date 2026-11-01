package com.taketori.kassen.core.character;

import java.util.UUID;

/**
 * 玩家运行时档案：角色绑定、隐性标签、挥击去重窗口。
 *
 * <p>2.0 起武器不再有"模式"，原先存在这里的 per-weapon mode 状态一并移除；
 * 需要跨事件判断的东西只剩「同一次挥击是否已经结算过」。</p>
 */
public final class PlayerProfile {

    private final UUID uuid;
    private String characterId;
    /** 隐性标签 id（权重见 config.yml 的 tags 段）；只影响同队角色冲突时的优先级。 */
    private String tag;
    private boolean specialShot;
    private long lastSwingTick = Long.MIN_VALUE;
    private String lastSwingWeapon;

    public PlayerProfile(UUID uuid, String characterId) {
        this.uuid = uuid;
        this.characterId = characterId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String characterId() {
        return characterId;
    }

    public void setCharacterId(String characterId) {
        this.characterId = characterId;
    }

    public boolean hasCharacter() {
        return characterId != null && !characterId.isBlank();
    }

    /** 隐性标签 id（可能为 null = 未设置）。 */
    public String tag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag == null || tag.isBlank() ? null : tag;
    }

    public boolean specialShot() {
        return specialShot;
    }

    public boolean toggleSpecialShot() {
        specialShot = !specialShot;
        return specialShot;
    }

    public void setSpecialShot(boolean value) {
        this.specialShot = value;
    }

    /**
     * 左键的"打实体"与"空挥"是两个不同事件（计划 §3.1），用 tick 窗口去重，
     * 避免同一次挥击被结算两次伤害。
     */
    public boolean markSwing(long tick, String weaponId) {
        // 差值必须为非负才参与去重：动态房间制下每次对局都是新世界，
        // gameTime 可能回落到模板基线，负差值会把上一局的旧时间戳误判成"刚挥过"
        boolean duplicate = lastSwingWeapon != null
                && lastSwingWeapon.equals(weaponId)
                && tick >= lastSwingTick
                && tick - lastSwingTick <= 2L;
        lastSwingTick = tick;
        lastSwingWeapon = weaponId;
        return duplicate;
    }

    public void clearCombatState() {
        specialShot = false;
        lastSwingTick = Long.MIN_VALUE;
        lastSwingWeapon = null;
    }
}
