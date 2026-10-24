package com.taketori.kassen.paper.effect;

import com.taketori.kassen.core.skill.SkillSlot;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能冷却条：屏幕上方那种进度条（骑马时马血条的位置与样式）。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>每个槽位一条</b>：左键 / 右键 / Shift+右键 / Q 各一条，颜色区分，
 *       只有进入冷却时才出现，冷却结束自动消失。</li>
 *   <li><b>用 Adventure 的 BossBar</b>：不需要玩家对象持有引用，注销时清零不会残留；
 *       同一玩家的多条会自动纵向排列。</li>
 *   <li><b>标题显示技能名与剩余秒数，进度条表示剩余比例</b>（满 → 空）。</li>
 * </ul>
 *
 * <p>刷新由主类的计时器驱动（默认每 5 tick），本类不做任何调度。</p>
 */
public final class CooldownBars {

    private record Entry(BossBar bar, long untilMillis, double totalSeconds, String skillName, boolean ready) {
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    // 内层同样用 ConcurrentHashMap：外层并发容器内放普通 EnumMap 时，
    // 复合操作（get/put/遍历）无同步保证，跨线程调用存在竞态。
    private final Map<UUID, Map<SkillSlot, Entry>> bars = new ConcurrentHashMap<>();
    /** 就绪提示回调（SkillManager 注入：播一声短促 ready 音；null = 不提示）。 */
    private final java.util.function.Consumer<Player> readyCue;

    public CooldownBars(java.util.function.Consumer<Player> readyCue) {
        this.readyCue = readyCue;
    }

    /** 技能进入冷却时调用；同一槽位重复调用只会刷新计时。 */
    public void show(Player player, SkillSlot slot, String skillName, double cooldownSeconds) {
        if (player == null || slot == null || cooldownSeconds <= 0.0D) {
            return;
        }
        long until = System.currentTimeMillis() + (long) (cooldownSeconds * 1000.0D);
        Map<SkillSlot, Entry> playerBars = bars.computeIfAbsent(
                player.getUniqueId(), key -> new ConcurrentHashMap<>());

        Entry existing = playerBars.get(slot);
        if (existing == null) {
            BossBar bar = BossBar.bossBar(title(skillName, cooldownSeconds), 1.0F, colorOf(slot), BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            playerBars.put(slot, new Entry(bar, until, cooldownSeconds, skillName, false));
        } else {
            playerBars.put(slot, new Entry(existing.bar(), until, cooldownSeconds, skillName, false));
        }
    }

    /** 由全局计时器调用（建议每 5 tick 一次）。 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(bars.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            Map<SkillSlot, Entry> playerBars = bars.get(uuid);
            if (player == null || !player.isOnline() || playerBars == null) {
                clear(uuid);
                continue;
            }
            for (SkillSlot slot : new ArrayList<>(playerBars.keySet())) {
                Entry entry = playerBars.get(slot);
                if (entry == null) {
                    continue;   // 防御：并发路径刚被 clear 摘掉时不要 NPE
                }
                double remaining = (entry.untilMillis() - now) / 1000.0D;
                if (remaining <= 0.05D) {
                    if (!entry.ready()) {
                        // 就绪瞬间：提示音 + 进度条闪绿一拍（"就绪！"），下一 tick 再移除
                        if (readyCue != null) {
                            readyCue.accept(player);
                        }
                        entry.bar().color(BossBar.Color.GREEN);
                        entry.bar().progress(1.0F);
                        entry.bar().name(MINI.deserialize("<green>✔ 就绪"));
                        playerBars.put(slot, new Entry(entry.bar(), entry.untilMillis(),
                                entry.totalSeconds(), entry.skillName(), true));
                        continue;
                    }
                    player.hideBossBar(entry.bar());
                    playerBars.remove(slot);
                    continue;
                }
                float progress = (float) Math.max(0.0D, Math.min(1.0D, remaining / entry.totalSeconds()));
                entry.bar().progress(progress);
                entry.bar().name(title(entry.skillName(), remaining));
            }
            if (playerBars.isEmpty()) {
                bars.remove(uuid);
            }
        }
    }

    /** 玩家退出 / 插件卸载时清理，避免残留。 */
    public void clear(UUID uuid) {
        Map<SkillSlot, Entry> playerBars = bars.remove(uuid);
        if (playerBars == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        for (Entry entry : playerBars.values()) {
            player.hideBossBar(entry.bar());
        }
    }

    public void clearAll() {
        for (UUID uuid : new ArrayList<>(bars.keySet())) {
            clear(uuid);
        }
    }

    private Component title(String skillName, double seconds) {
        String name = (skillName == null || skillName.isBlank()) ? "技能" : skillName;
        return MINI.deserialize("<white>" + name + "</white> <gray>" + String.format("%.1f", seconds) + "s");
    }

    private BossBar.Color colorOf(SkillSlot slot) {
        return switch (slot) {
            case LEFT -> BossBar.Color.BLUE;
            case RIGHT -> BossBar.Color.GREEN;
            case SHIFT_RIGHT -> BossBar.Color.YELLOW;
            case Q -> BossBar.Color.PURPLE;
        };
    }
}
