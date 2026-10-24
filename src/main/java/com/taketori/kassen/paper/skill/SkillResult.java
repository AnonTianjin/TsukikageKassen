package com.taketori.kassen.paper.skill;

/** 技能执行结果，用于冷却结算与调试追踪。 */
public enum SkillResult {

    /** 正常执行，按配置进入冷却。 */
    SUCCESS,
    /** 冷却中，未执行。 */
    ON_COOLDOWN,
    /** 主手不是插件武器。 */
    NOT_A_WEAPON,
    /** 该武器/模式在这个槽位没有绑定技能。 */
    NOT_BOUND,
    /** 技能类型未注册（配置写错）。 */
    UNKNOWN_TYPE,
    /** 条件不满足（例如钢丝没有目标），不进入冷却。 */
    NO_TARGET,
    /** 执行出错。 */
    FAILED;

    public boolean consumesCooldown() {
        return this == SUCCESS;
    }
}
