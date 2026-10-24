package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.skill.SkillManager;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基地占点：站在<b>对方</b>基地区域内持续读条，读满即拆除，+50 分。
 *
 * <p>按需求采用"区域占点 + 核心耐久"：</p>
 * <ul>
 *   <li>只有敌方玩家在区域内才推进进度；多人同时在场会加速（可配）；</li>
 *   <li>无人时按配置缓慢衰减，避免"碰一下就跑"也能拆掉；</li>
 *   <li>每个基地只会被拆除一次，拆除后从本轮目标中移除；</li>
 *   <li>进度用 ActionBar 显示，不占记分板。</li>
 * </ul>
 */
public final class BaseCaptureManager {

    /** 所属房间：阶段/规则/场地/队伍/计分/播报全部按房间取。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;
    /** key = 基地归属方 + 编号，例如 "red:3"。 */
    private final Map<String, Double> progress = new HashMap<>();
    private final Set<String> captured = new HashSet<>();
    private BukkitTask task;
    /** 下一次"保护期剩余时间"提示的阈值（已过秒数），用阈值而非取模，卡顿跳秒也不会漏提示。 */
    private long nextNoticeAt;

    public BaseCaptureManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    public void start() {
        stop();
        nextNoticeAt = 0L;
        task = plugin.scheduler().runTimerTask(this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        progress.clear();
        captured.clear();
        nextNoticeAt = 0L;
    }

    public double progressOf(TeamId owner, int index) {
        return progress.getOrDefault(key(owner, index), 0.0D);
    }

    public boolean isCaptured(TeamId owner, int index) {
        return captured.contains(key(owner, index));
    }

    public int capturedCount(TeamId owner) {
        // 遍历该队"实际配置了"的编号：这样 base.count-per-team 写成任意值或 auto 都能正确统计
        int count = 0;
        for (int index : room.arena().bases(owner).keySet()) {
            if (isCaptured(owner, index)) {
                count++;
            }
        }
        return count;
    }

    private String key(TeamId owner, int index) {
        return owner.key() + ":" + index;
    }

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        // 开局保护期：前 N 秒完全不推进占点进度（也不衰减）
        if (!room.isBaseCaptureOpen()) {
            long remaining = room.baseCaptureDelayRemaining();
            long elapsed = room.elapsedSeconds();
            if (remaining > 0L && elapsed >= nextNoticeAt) {
                nextNoticeAt = elapsed + 10L;
                for (TeamId team : TeamId.values()) {
                    for (Player player : room.teamPlayers(team)) {
                        if (!plugin.spectator().isSpectator(player)) {
                            room.scoreboard().actionBar(player,
                                    "<gray>基地保护期：<white>" + remaining + "</white> 秒后开放占点");
                        }
                    }
                }
            }
            return;
        }
        MatchRules rules = room.rules();
        for (TeamId owner : TeamId.values()) {
            // 只遍历实际配置的基地（base.count-per-team 可写任意数量或 auto）
            for (int index : room.arena().bases(owner).keySet()) {
                CuboidRegion region = room.arena().base(owner, index);
                if (region == null) {
                    continue;
                }
                String key = key(owner, index);
                if (captured.contains(key)) {
                    continue;
                }
                List<Player> attackers = enemiesInside(owner, region);
                double delta;
                if (attackers.isEmpty()) {
                    delta = -Math.max(0.0D, rules.baseDecayPerSecond());
                } else {
                    delta = rules.baseMultiPlayerBonus() ? attackers.size() : 1.0D;
                }
                double current = clamp(progress.getOrDefault(key, 0.0D) + delta, rules.baseCaptureSeconds());
                progress.put(key, current);

                // 进度提示
                if (!attackers.isEmpty() && rules.baseCaptureSeconds() > 0.0D) {
                    int percent = (int) Math.round(current / rules.baseCaptureSeconds() * 100.0D);
                    String bar = SkillManager.progressBar(current, rules.baseCaptureSeconds());
                    for (Player attacker : attackers) {
                        room.scoreboard().actionBar(attacker, "<red>拆除 " + owner.display()
                                + " 基地 #" + index + "</red> <gray>" + bar + " <white>" + percent + "%");
                    }
                }

                if (current >= rules.baseCaptureSeconds() && rules.baseCaptureSeconds() > 0.0D) {
                    captured.add(key);
                    Player scorer = attackers.isEmpty() ? null : attackers.get(0);
                    room.addScore(scorer,
                            scorer == null ? 0 : rules.baseCaptureScore(),
                            "拆除 " + owner.display() + " 基地 #" + index);
                    if (scorer != null) {
                        // 跨局累计：拆家数
                        plugin.stats().add(scorer.getName(), StatsTracker.Stat.BASE_CAPTURES, 1L);
                    }
                    // 上面若 scorer 为 null 则不计分（理论不会发生：进度只由在场玩家推进）
                    room.broadcast("<yellow>⚑ " + owner.display() + " 的基地 #" + index
                            + " 被 <white>" + (scorer == null ? "敌方" : scorer.getName()) + "</white> 拆除！"
                            + " <gray>(+" + rules.baseCaptureScore() + " 分)");
                    if (plugin.config().debug()) {
                        plugin.getLogger().info("[match] 基地 " + key + " 被拆除，进度 " + current);
                    }
                }
            }
        }
    }

    /** 区域内属于"敌方"的玩家（即基地归属方的对手）。 */
    private List<Player> enemiesInside(TeamId owner, CuboidRegion region) {
        List<Player> result = new java.util.ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            // 不在本房间的玩家 teamOf 为 null，已自然跳过；旁观者与死亡待复活的玩家不得参与拆基地
            // （否则尸体的观察点若落在基地区域内，会被计进 baseMultiPlayerBonus 加速，还能刷拆家统计）
            if (player.isDead() || plugin.spectator().isSpectator(player)) {
                continue;
            }
            TeamId team = room.teamOf(player.getUniqueId());
            if (team == null || team == owner) {
                continue;
            }
            if (region.contains(player.getLocation())) {
                result.add(player);
            }
        }
        return result;
    }

    private double clamp(double value, double max) {
        if (value < 0.0D) {
            return 0.0D;
        }
        return Math.min(value, Math.max(0.0001D, max));
    }
}
