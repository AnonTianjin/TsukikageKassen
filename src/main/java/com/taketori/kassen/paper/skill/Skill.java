package com.taketori.kassen.paper.skill;

/**
 * 技能实现契约。一个实现类对应 weapons.yml 里的一个 {@code type}。
 *
 * <p>新增技能类型 = 新增一个实现类 + 在 SkillRegistry 里注册一行，
 * 不需要改动输入层与冷却层。</p>
 */
public interface Skill {

    /** 与配置中的 type 对应，例如 "projectile"。 */
    String type();

    SkillResult execute(SkillContext context);
}
