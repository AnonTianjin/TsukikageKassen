package com.taketori.kassen.core.match.sengoku;

import java.util.List;
import java.util.Locale;

/**
 * 大将击破器与跳跃台的规则（来自 {@code sengoku-siege.yml}）。纯数据。
 *
 * <p>两者放同一份配置是因为它们<b>由同一个事件触发</b>：己方占领任一箭楼后，
 * 敌方天守阁门前出现击破器、己方天守阁门前出现跳跃台。拆开会让"占领后发生了什么"
 * 散在两个文件里。</p>
 *
 * @param material              击破器的物品材质
 * @param display               显示名（MiniMessage）
 * @param glow                  是否发光（掉落物在草地上也能一眼看到）
 * @param spawnDistanceFromKeep 生成点距天守阁门前点的距离（格）
 * @param pickRadius            拾取判定半径（格）
 * @param armTimeSeconds        携带者进入敌方天守阁范围后，读满多少秒算攻陷
 * @param onePerTeam            每队同时最多存在一个击破器
 * @param respawnOnRecapture    重新占领箭楼时是否自动补齐被消耗掉的击破器
 * @param allowedCharacters     允许操作击破器的角色 id；<b>空列表 = 所有职业</b>
 * @param indestructible        击破器不可被破坏（火 / 爆炸 / 岩浆 / 仙人掌）
 * @param undroppable           击破器不可被丢弃
 * @param jumpPad               跳跃台规格
 */
public record SiegeRules(String material,
                         String display,
                         boolean glow,
                         double spawnDistanceFromKeep,
                         double pickRadius,
                         double armTimeSeconds,
                         boolean onePerTeam,
                         boolean respawnOnRecapture,
                         List<String> allowedCharacters,
                         boolean indestructible,
                         boolean undroppable,
                         JumpPadSpec jumpPad) {

    /**
     * 跳跃台规格。
     *
     * @param mode                {@code teleport}（默认，最稳）或 {@code launch}（弹射）
     * @param target              {@code nearest-captured-tower} 或 {@code specified}
     * @param specifiedTower       {@code target=specified} 时的箭楼序号
     * @param cooldownSeconds     同一玩家的使用冷却（防连点）
     * @param power               {@code launch} 模式的前推力
     * @param upward              {@code launch} 模式的抬升力
     * @param fallImmunityTicks   落地免摔窗口（tick）
     * @param particle            传送 / 弹射粒子
     * @param sound               传送 / 弹射音效
     * @param resistanceTicks     落地抗性时长（tick），给刚复活的人一点缓冲
     */
    public record JumpPadSpec(String mode,
                              String target,
                              int specifiedTower,
                              int cooldownSeconds,
                              double power,
                              double upward,
                              int fallImmunityTicks,
                              String particle,
                              String sound,
                              int resistanceTicks) {

        public boolean isLaunch() {
            return "launch".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        public boolean isNearest() {
            return !"specified".equalsIgnoreCase(target == null ? "" : target.trim());
        }
    }

    /** 默认：下界之星、8 秒读条、每队一个、重占领补齐、全职业可用、不可破坏不可丢弃。 */
    public static SiegeRules defaults() {
        return new SiegeRules("NETHER_STAR", "<gold>大将击破器</gold>", true,
                3.0D, 2.0D, 8.0D, true, true, List.of(), true, true,
                new JumpPadSpec("teleport", "nearest-captured-tower", 1, 3,
                        2.2D, 1.0D, 100, "END_ROD", "ENTITY_ENDERMAN_TELEPORT", 40));
    }

    /** 需要读条多少秒（负数兜底成 0，等价于"碰到即攻陷"）。 */
    public double effectiveArmSeconds() {
        return Math.max(0.0D, armTimeSeconds);
    }

    /**
     * 某个角色能不能操作击破器。
     *
     * <p>空列表 = 所有职业（用户明确要求全部职业可用）；列表非空时按角色 id 精确匹配。</p>
     */
    public boolean allowsCharacter(String characterId) {
        if (allowedCharacters == null || allowedCharacters.isEmpty()) {
            return true;
        }
        if (characterId == null) {
            return false;
        }
        String wanted = characterId.trim().toLowerCase(Locale.ROOT);
        for (String allowed : allowedCharacters) {
            if (allowed != null && allowed.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}
