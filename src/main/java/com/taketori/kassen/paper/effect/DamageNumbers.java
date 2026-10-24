package com.taketori.kassen.paper.effect;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伤害数字（动作栏汇报）：把攻击者造成的伤害聚合后一次性汇报。
 *
 * <p>为什么不逐次刷：一次范围技能会命中多个目标、强化箭有自己的结算路径——
 * 逐次发动作栏会与冷却文字/提示互相顶掉。这里按攻击者聚合，最后一次命中后
 * 10 tick 汇总发送一次"⚔ 总伤害 ×段数"。仅对玩家攻击者生效，自我伤害不记。</p>
 */
public final class DamageNumbers {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** 聚合窗口（tick）：窗口内的所有命中合并成一条汇报。 */
    private static final long FLUSH_DELAY_TICKS = 10L;

    private static final class Pending {
        double total;
        int hits;
        BukkitTask flushTask;
    }

    private final TaketoriPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public DamageNumbers(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 记录一次命中（对玩家攻击者生效；自我伤害/非玩家来源由调用方过滤）。 */
    public void hit(Player attacker, LivingEntity victim, double amount) {
        if (attacker == null || !attacker.isOnline() || victim == null || amount <= 0.0D) {
            return;
        }
        Pending entry = pending.computeIfAbsent(attacker.getUniqueId(), key -> new Pending());
        entry.total += amount;
        entry.hits++;
        if (entry.flushTask == null) {
            entry.flushTask = plugin.scheduler().runLater(() -> flush(attacker.getUniqueId()), FLUSH_DELAY_TICKS);
        }
    }

    private void flush(UUID uuid) {
        Pending entry = pending.remove(uuid);
        if (entry == null || entry.hits <= 0) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendActionBar(MINI.deserialize("<gold>⚔ <white>"
                + String.format("%.1f", entry.total) + "</white> <gray>伤害 <dark_gray>×" + entry.hits + " 段"));
    }

    /** 玩家退出时清掉未汇报的聚合（任务一并取消）。 */
    public void clear(UUID uuid) {
        Pending entry = pending.remove(uuid);
        if (entry != null && entry.flushTask != null) {
            entry.flushTask.cancel();
        }
    }

    public void clearAll() {
        for (UUID uuid : pending.keySet().toArray(new UUID[0])) {
            clear(uuid);
        }
    }
}
