package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * 位移实现的公共部分。
 *
 * <p>三种路径：</p>
 * <ul>
 *   <li>{@code potion}（默认）：<b>纯原版药水</b>，不写位置也不写速度。
 *       速度 {@code SPEED} 负责"推进"，跳跃提升 {@code JUMP_BOOST} 负责"跳高"，
 *       漂浮 {@code LEVITATION} 负责<b>自动上升</b>（等级拉高后上升速度可观）。
 *       全部走原版机制，<b>不受位置纠正与反作弊影响</b>。</li>
 *   <li>{@code teleport}：逐 tick 传送。位移最确定，但观感偏硬，且部分服务器的
 *       反作弊/领地插件会拦截瞬移。</li>
 *   <li>{@code velocity}：调 {@code setVelocity}。观感平滑，但速度可能被位置纠正立刻清除
 *       （实测 1.38 格/tick 只走出 1.18 格）。</li>
 * </ul>
 *
 * <p>关于原版药水的边界：能自动上升（LEVITATION）、能加速（SPEED）、能跳更高（JUMP_BOOST）、
 * 能缓降（SLOW_FALLING），但<b>没有能实现"自动水平位移"的药水效果</b>——
 * 所以水平推进在 potion 模式下表现为"跑得更快"，而不是"被推出去"。</p>
 */
final class BoostSupport {

    /**
     * 一次药水增强的完整参数。
     *
     * @param speedAmplifier        速度等级，&lt; 0 表示不给
     * @param jumpAmplifier         跳跃提升等级，&lt; 0 表示不给
     * @param ticks                 效果持续 tick
     * @param slowFalling           是否附加缓降
     * @param levitation            是否用漂浮实现自动上升
     * @param levitationAmplifier   漂浮等级（每级约 +0.05 格/tick 的上升速度）
     * @param levitationTicks       漂浮持续 tick（决定上升高度）
     */
    record PotionBoost(int speedAmplifier,
                       int jumpAmplifier,
                       int ticks,
                       boolean slowFalling,
                       boolean levitation,
                       int levitationAmplifier,
                       int levitationTicks) {
    }

    private BoostSupport() {
    }

    /** 逐 tick 传送式位移；velocity 的每个分量就是"每 tick 前进的格数"。 */
    static void teleportBoost(TaketoriPlugin plugin, Player player, Vector velocity, int ticks) {
        final BukkitTask[] holder = new BukkitTask[1];
        final int[] elapsed = {0};
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            if (elapsed[0]++ >= ticks || !player.isOnline() || player.isDead()) {
                cancel(holder[0]);
                return;
            }
            Location from = player.getLocation();
            Location to = from.clone().add(velocity);
            if (!isPassable(to)) {
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[combat] boost(teleport) 前方不可通行，提前结束");
                }
                cancel(holder[0]);
                return;
            }
            to.setYaw(from.getYaw());
            to.setPitch(from.getPitch());
            player.teleport(to);
            player.setFallDistance(0.0F);
        }, 0L, 1L);
    }

    /**
     * 药水式机动：速度 + 跳跃提升 +（可选）缓降 +（可选）漂浮上升。
     * 全部是原版药水效果，跨版本安全，也不会被位置纠正抵消。
     */
    static void applyPotionBoost(TaketoriPlugin plugin, Player player, PotionBoost boost) {
        if (boost == null) {
            return;
        }
        if (boost.ticks() > 0) {
            if (boost.speedAmplifier() >= 0) {
                apply(plugin, player, "SPEED", boost.ticks(), boost.speedAmplifier(), true);
            }
            if (boost.jumpAmplifier() >= 0) {
                apply(plugin, player, "JUMP_BOOST", boost.ticks(), boost.jumpAmplifier(), true);
            }
            if (boost.slowFalling()) {
                apply(plugin, player, "SLOW_FALLING", boost.ticks(), 0, false);
            }
        }
        if (boost.levitation() && boost.levitationTicks() > 0) {
            // 漂浮：每级约 +0.05 格/tick 的自动上升速度
            apply(plugin, player, "LEVITATION", boost.levitationTicks(), boost.levitationAmplifier(), true);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format(
                    "[combat] 药水增强：速度 %d / 跳跃 %d / %d tick；漂浮 %s(%d 级 %d tick)；缓降=%s",
                    boost.speedAmplifier(), boost.jumpAmplifier(), boost.ticks(),
                    boost.levitation(), boost.levitationAmplifier(), boost.levitationTicks(), boost.slowFalling()));
        }
    }

    private static void apply(TaketoriPlugin plugin, Player player, String effectName,
                              int ticks, int amplifier, boolean ambientParticles) {
        PotionEffectType type = plugin.versions().potionEffect(effectName);
        if (type != null) {
            player.addPotionEffect(new PotionEffect(type, ticks, Math.max(0, amplifier),
                    false, ambientParticles, true));
        }
    }

    /** 脚与头两格是否可通行、且不是岩浆。 */
    static boolean isPassable(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        return feet.isPassable() && head.isPassable() && feet.getType() != Material.LAVA;
    }

    /** 1 秒后报告实际位移（多数情况下含"你自己跑动"的距离），用于判断手感。 */
    static void reportDisplacement(TaketoriPlugin plugin, Player player, Location start, double expected, String mode) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.scheduler().runLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            double moved = player.getLocation().distance(start);
            plugin.getLogger().info(String.format("[combat] 实际位移 %.2f 格（预期约 %.1f 格，模式 %s）",
                    moved, expected, mode));
            if ("velocity".equals(mode) && expected > 3.0D && moved < expected * 0.35D) {
                plugin.getLogger().warning("[combat] 位移远低于预期：设好的速度很可能被位置纠正清除了。");
                plugin.getLogger().warning("[combat]   解决：把该技能参数 boost-mode 改为 potion（药水机动）。");
            }
        }, 20L);
    }

    /** 预期位移的粗略估算：水平初速 × 维持次数。 */
    static double estimateDisplacement(Vector velocity, int ticks) {
        double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        return horizontal * Math.max(1, ticks);
    }

    static void cancel(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件卸载阶段忽略
            }
        }
    }

    static String format(Vector vector) {
        return String.format("(%.2f, %.2f, %.2f)", vector.getX(), vector.getY(), vector.getZ());
    }

    /**
     * 落地反馈（B7）：起飞后每 4 tick 检测一次，落地瞬间在脚下爆一圈尘土 + 软垫音效。
     * 必须先离开过地面才算"落地"，防止原地站着误触发；任务最长存活 15 秒自灭。
     */
    static void landingBurst(TaketoriPlugin plugin, Player player) {
        final BukkitTask[] holder = new BukkitTask[1];
        final int[] ticks = {0};
        final boolean[] airborne = {false};
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            if (ticks[0]++ > 300 || !player.isOnline() || player.isDead()) {
                cancel(holder[0]);
                return;
            }
            if (!player.isOnGround()) {
                airborne[0] = true;
                return;
            }
            if (!airborne[0]) {
                return;
            }
            cancel(holder[0]);
            plugin.fx().particle("CLOUD", player.getLocation(), 10, 0.25D);
            plugin.fx().sound("BLOCK_SNOW_BREAK", player, 0.7F, 1.1F);
        }, 4L, 4L);
    }
}
