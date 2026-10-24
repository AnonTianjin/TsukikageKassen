package com.taketori.kassen.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

/**
 * 调度出口（计划 §5.6）：所有延时/循环任务都从这里走。
 *
 * <p>本期只实现 Bukkit 主线程调度（Folia 暂不考虑），但把调度收在一个类里，
 * 将来若要接 Folia，只需要在这里分流到 EntityScheduler，不必翻遍技能代码。</p>
 *
 * <p><b>内存</b>：过去这里把每个任务句柄放进集会后只增不减——一次性任务执行完不出列、
 * 被调用方自行 cancel 的周期任务句柄也留着，长期运行集合随死亡/开菜单/技能使用无限增长，
 * 还会连带钉住闭包捕获的 Player / BossBar / Projectile 等对象。现在：</p>
 * <ul>
 *   <li>一次性任务（{@link #run} / {@link #runLater}）执行完成后自动出列；</li>
 *   <li>看门任务定期剔除 {@code isCancelled()} 的句柄（覆盖被调用方自行 cancel 的周期任务、
 *       执行前被取消的一次性任务、以及"首次执行即自取消"的单次任务）；</li>
 *   <li>正在运行的周期任务保留在集合里（它们本来就该被 {@link #cancelAll} 兜底取消）。</li>
 * </ul>
 */
public final class SchedulerAdapter {

    /** 看门任务的清扫周期：1200 tick = 1 分钟，开销可忽略（只遍历一个小集合）。 */
    private static final long PRUNE_PERIOD_TICKS = 1200L;

    private final Plugin plugin;
    private final Set<BukkitTask> tasks = new HashSet<>();

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        // 看门任务自身也登记进集合（cancelAll 兜底取消它；它自己永不自取消，不会被剔掉）
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::pruneCancelled,
                PRUNE_PERIOD_TICKS, PRUNE_PERIOD_TICKS));
    }

    /** 下一 tick 执行。 */
    public void run(Runnable task) {
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = track(Bukkit.getScheduler().runTask(plugin, () -> runAndRelease(handle, task)));
    }

    /**
     * 延迟执行（tick）。返回任务句柄：执行完自动出列；调用方如需提前取消可持有它。
     */
    public BukkitTask runLater(Runnable task, long delayTicks) {
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = track(Bukkit.getScheduler().runTaskLater(plugin,
                () -> runAndRelease(handle, task), Math.max(0L, delayTicks)));
        return handle[0];
    }

    /**
     * 周期性执行并返回任务句柄，供调用方自行取消。
     * "持续拉拽"这类效果需要按条件（目标已到身边 / 失效）提前结束，不能只靠 tick 计数。
     * 句柄由看门任务在它被取消后剔除。
     */
    public BukkitTask runTimerTask(Runnable task, long delayTicks, long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimer(plugin, task,
                Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
    }

    /** 与实体绑定的延迟任务（未来 Folia 分流的入口）。 */
    public void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        runLater(() -> {
            if (entity != null && entity.isValid()) {
                task.run();
            }
        }, delayTicks);
    }

    /** 当前在线主线程执行（事件回调里可直接调用）。 */
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            run(task);
        }
    }

    /** 一次性任务的包装：无论正常结束还是抛异常，执行完都把句柄移出集合。 */
    private void runAndRelease(BukkitTask[] handle, Runnable task) {
        try {
            task.run();
        } finally {
            tasks.remove(handle[0]);
        }
    }

    /** 剔除已取消的任务句柄（看门任务；周期任务被调用方 cancel 后由这里清出集合）。 */
    private void pruneCancelled() {
        tasks.removeIf(BukkitTask::isCancelled);
    }

    private BukkitTask track(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public void cancelAll() {
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件卸载阶段忽略
            }
        }
        tasks.clear();
    }
}
