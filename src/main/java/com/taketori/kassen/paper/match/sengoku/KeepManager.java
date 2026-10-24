package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Location;

/**
 * 天守阁：<b>永久不可直接破坏</b>，只能被击破器攻陷。
 *
 * <p>按需求，天守阁从来不能被直接打掉——所以"无敌"不是某个阶段的状态，而是它的性质：
 * 玩家与爆炸都改不动它，唯一的胜利通道是 {@link SiegeBreakerManager 击破器读条}。</p>
 *
 * <p><b>刻意不修改地形</b>：攻陷是逻辑判定，天守阁的建筑本体不会被拆。每小局都要重置战场，
 * 真把建筑拆了下一局就回不来——这也是本类只做"拦截"、不做"摧毁"的原因。</p>
 *
 * <p>天守阁是<b>纯方块结构</b>，因此只需要拦方块破坏与爆炸两类事件，
 * 不涉及实体或方块实体。</p>
 */
public final class KeepManager {

    private final GameRoom room;
    private final TaketoriPlugin plugin;

    public KeepManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    /**
     * 这个方块位置是否落在任一队的天守阁区域内。
     *
     * <p>用位置判断而不是"谁在打"，是因为要拦的事件粒度不同：{@code BlockBreakEvent} 给的是方块，
     * 爆炸给的是方块列表，但两者的判定条件都是"在不在天守阁里"。</p>
     */
    public boolean isProtectedBlock(Location location) {
        if (location == null || !protectionEnabled()) {
            return false;
        }
        var map = room.arena() == null ? null : room.arena().sengoku();
        if (map == null) {
            return false;
        }
        for (TeamId team : TeamId.values()) {
            CuboidRegion keep = map.keep(team);
            if (keep != null && keep.contains(location)) {
                return true;
            }
        }
        return false;
    }

    /** 天守阁当前是否处于无敌（不可攻陷）。用于提示文案与胜负判定的守卫。 */
    public boolean isInvulnerable() {
        return plugin.config().sengokuRules().keepInvulnerable() && protectionEnabled();
    }

    /**
     * 保护是否启用：配置开启 + 房间在跑。
     *
     * <p>{@code keep.invulnerable} 与 {@code keep.protect-blocks} 两个键都要看：
     * 前者是"天守阁本身不可攻陷"的总开关，后者是"要不要拦方块破坏与爆炸"。
     * 关掉总开关就等于允许直接砸掉天守阁（用于调试或另类规则）。</p>
     *
     * <p>不需要额外的"本局是否已被攻陷"判断——击破器读条完成会<b>立即结束本小局</b>，
     * 那时 {@code isRunning()} 已经变 false，保护自然失效。</p>
     */
    private boolean protectionEnabled() {
        if (!plugin.config().sengokuRules().keepInvulnerable()) {
            return false;
        }
        if (!plugin.config().sengokuRules().protectKeepBlocks()) {
            return false;
        }
        return room.isRunning();
    }

    /** 某队天守阁的位置（给读条判定、播报与调试用）；没配置返回 null。 */
    public Location keepCenter(TeamId team) {
        var map = room.arena() == null ? null : room.arena().sengoku();
        if (map == null) {
            return null;
        }
        CuboidRegion keep = map.keep(team);
        return keep == null ? null : keep.center();
    }

    /** 某队天守阁门前点（击破器与跳跃台的生成位置）。 */
    public Location keepDoor(TeamId team) {
        var map = room.arena() == null ? null : room.arena().sengoku();
        if (map == null) {
            return null;
        }
        var point = map.keepDoor(team);
        return point == null ? null : point.toBukkitLocation();
    }
}
