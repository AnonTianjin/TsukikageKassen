package com.taketori.kassen.core.match.sengoku;

/**
 * 箭楼规则（来自 {@code sengoku-towers.yml}）。纯数据，方便单测与热重载。
 *
 * @param count                地图上有几座箭楼（上下路各一 → 2）
 * @param captureMode          {@code channel} = 敲钟后读条；{@code instant} = 敲钟即占领
 * @param captureSeconds       读条时长（秒）；{@code captureMode=instant} 时忽略
 * @param decayPerSecond       无人读条时进度每秒衰减多少（0 = 不衰减，读一半跑掉也算数）
 * @param contestLock          双方同时读条时是否互相锁死（需求第 9 条：双方都不推进）
 * @param guardRespawnSeconds  守卫被清空后多少秒重新刷新（防止"刚清完立刻又刷"导致无法占领）
 * @param bellMaterial         铜钟方块材质（用于识别交互目标）
 * @param bellSound            敲钟音效
 * @param bellParticle         占领过程中的粒子
 */
public record TowerRules(int count,
                         CaptureMode captureMode,
                         double captureSeconds,
                         double decayPerSecond,
                         boolean contestLock,
                         int guardRespawnSeconds,
                         GuardSpec oxDemon,
                         GuardSpec shrimpCrab,
                         String bellMaterial,
                         String bellSound,
                         String bellParticle) {

    /** 占领触发方式。 */
    public enum CaptureMode {

        /** 敲钟后读条 {@code captureSeconds} 秒，中途可被打断。 */
        CHANNEL,
        /** 敲钟即占领（时长视为 0），用于快速对局或调试。 */
        INSTANT
    }

    /**
     * 箭楼守卫规格。
     *
     * @param entity         原版实体名（牛鬼 {@code HUSK} / 虾兵蟹将 {@code VINDICATOR}）
     * @param display        显示名（MiniMessage）
     * @param count          刷新数量
     * @param health         血量上限
     * @param damage         攻击力（写进属性；实体不支持时静默忽略）
     * @param speedAmplifier 速度药水等级（0 = I 级）
     * @param patrolRadius   巡逻半径（格）
     * @param aggroRadius    仇恨半径（格）
     */
    public record GuardSpec(String entity,
                            String display,
                            int count,
                            double health,
                            double damage,
                            int speedAmplifier,
                            double patrolRadius,
                            double aggroRadius) {

        /** 中型头目「牛鬼」：单体、血厚、伤害高。 */
        public static GuardSpec oxDemonDefaults() {
            return new GuardSpec("HUSK", "<red>牛鬼</red>", 1, 120.0D, 12.0D, 0, 12.0D, 16.0D);
        }

        /** 「虾兵蟹将」：多只、围绕箭楼巡逻。 */
        public static GuardSpec shrimpCrabDefaults() {
            return new GuardSpec("VINDICATOR", "<gray>虾兵蟹将</gray>", 8, 40.0D, 6.0D, 0, 12.0D, 16.0D);
        }
    }

    /** 默认：2 座箭楼、读条 5 秒、衰减 0.5/秒、互锁开、守卫 20 秒重刷。 */
    public static TowerRules defaults() {
        return new TowerRules(2, CaptureMode.CHANNEL, 5.0D, 0.5D, true, 20,
                GuardSpec.oxDemonDefaults(), GuardSpec.shrimpCrabDefaults(),
                "BELL", "BLOCK_BELL_USE", "END_ROD");
    }

    /** 实际读条时长：瞬时模式返回 0。 */
    public double effectiveCaptureSeconds() {
        return captureMode == CaptureMode.INSTANT ? 0.0D : Math.max(0.0D, captureSeconds);
    }

    /** 是否需要在读条期间累积进度（瞬时模式直接完成）。 */
    public boolean isChanneled() {
        return captureMode == CaptureMode.CHANNEL && effectiveCaptureSeconds() > 0.0D;
    }

    /** 规整箭楼数量到 1..{@code SengokuMapDef.MAX_TOWERS} 的范围（配置写错不该崩）。 */
    public int safeCount() {
        return Math.max(1, Math.min(8, count));
    }
}
