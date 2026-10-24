package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 钢丝牵引（双向）。
 *
 * <p>为什么是"双向 + 持续施力"：</p>
 * <ul>
 *   <li><b>双向</b>：视线内有敌人就把敌人拉过来；没有敌人就钩向方块、把自己拉过去。
 *       只做单向会出现"对着空气/墙按右键什么都没发生"的观感。</li>
 *   <li><b>持续施力</b>：只给一次速度的话，站在地上的目标会被地面摩擦立刻吃掉水平速度，
 *       看起来"人没动"。所以这里在 <code>duration</code> 秒内每 tick 施力，
 *       并给一点点向上分量让目标离地（离地后速度才不会被地面摩擦抹掉）。</li>
 * </ul>
 *
 * <p>参数：<code>mode</code>（auto / target / self）、<code>range</code>、<code>angle</code>、
 * <code>power</code>（每 tick 速度）、<code>duration</code>（秒）、<code>damage</code>（默认 0，纯控制）。</p>
 */
public final class PullSkill implements Skill {

    private final TaketoriPlugin plugin;

    public PullSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "pull";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        String mode = context.str("mode", "auto").toLowerCase(java.util.Locale.ROOT);
        double range = context.dbl("range", 14.0D);
        double power = context.dbl("power", 0.55D);
        double durationSeconds = context.dbl("duration", 0.4D);
        double halfAngle = Math.toRadians(context.dbl("angle", 50.0D));
        double damage = context.dbl("damage", 0.0D);
        String particle = context.str("particle", "END_ROD");

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        int ticks = Math.max(1, (int) Math.round(durationSeconds * 20.0D));

        // ① 视线内有敌人 → 把他拉过来
        if (!"self".equals(mode)) {
            LivingEntity target = findTarget(player, eye, direction, range, halfAngle);
            if (target != null) {
                if (damage > 0.0D) {
                    target.damage(damage, player);
                    plugin.damageNumbers().hit(player, target, damage);   // 伤害数字（A1）
                }
                // 2.0：拉扯命中后的减速（decelerate-*），0 = 关闭
                int decelerateTicks = context.integer("decelerate-ticks", 0);
                if (decelerateTicks > 0) {
                    PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
                    if (slow != null) {
                        target.addPotionEffect(new PotionEffect(slow, decelerateTicks,
                                Math.max(0, context.integer("decelerate-amplifier", 0)), false, true, true));
                    }
                }
                drawTrail(eye, target.getLocation().add(0.0D, 1.0D, 0.0D), particle);
                pullTargetToPlayer(player, target, power, ticks);
                plugin.fx().sound(context.str("sound", "ITEM_TRIDENT_RETURN"), player, 0.9F, 0.8F);
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[combat] 钢丝牵引：把 " + target.getType() + " 拉向玩家（" + ticks + " tick）");
                }
                return SkillResult.SUCCESS;
            }
            if ("target".equals(mode)) {
                plugin.fx().actionBar(player, plugin.config().messages().get("skill.no-target"));
                return SkillResult.NO_TARGET;
            }
        }

        // ② 没有敌人（或 mode=self）→ 钩向方块，把自己拉过去
        RayTraceResult trace = player.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        if (trace == null || trace.getHitPosition() == null) {
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.grapple-miss"));
            return SkillResult.NO_TARGET;
        }
        Location anchor = trace.getHitPosition().toLocation(player.getWorld());
        drawTrail(eye, anchor, particle);
        pullPlayerToAnchor(player, anchor, power, ticks);
        plugin.fx().sound(context.str("sound", "ITEM_TRIDENT_RETURN"), player, 0.9F, 1.2F);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[combat] 钢丝牵引：把玩家拉向 " + anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ());
        }
        return SkillResult.SUCCESS;
    }

    /** 每 tick 给目标一个朝向玩家的速度，直到贴近或超时。 */
    private void pullTargetToPlayer(Player player, LivingEntity target, double power, int ticks) {
        final BukkitTask[] holder = new BukkitTask[1];
        final int[] elapsed = {0};
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            if (elapsed[0]++ >= ticks || !target.isValid() || target.isDead() || !player.isOnline()) {
                cancel(holder[0]);
                return;
            }
            Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector());
            pull.setY(0.0D);
            double distance = pull.length();
            if (distance < 1.5D) {
                cancel(holder[0]);
                // 到站反馈：目标被拽到面前的一下"收线"感
                plugin.fx().particle("CRIT", target.getLocation().add(0.0D, 1.0D, 0.0D), 6, 0.25D);
                plugin.fx().sound("ENTITY_ARROW_HIT_PLAYER", player, 0.5F, 1.5F);
                return;
            }
            // ease-out 收线：临近时拉力递减，最后半格是"贴上来"而不是"撞上来"
            double ease = 0.35D + 0.65D * Math.min(1.0D, distance / 8.0D);
            pull.normalize().multiply(power * ease);
            // 微抬离地：站地面的目标会被摩擦吃掉水平速度
            pull.setY(Math.max(0.12D, Math.min(0.35D, distance * 0.05D)));
            target.setVelocity(target.getVelocity().multiply(0.2D).add(pull));
        }, 0L, 1L);
    }

    /** 每 tick 给玩家一个朝向锚点的速度，直到靠近或超时。 */
    private void pullPlayerToAnchor(Player player, Location anchor, double power, int ticks) {
        final BukkitTask[] holder = new BukkitTask[1];
        final int[] elapsed = {0};
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            if (elapsed[0]++ >= ticks || !player.isOnline()) {
                cancel(holder[0]);
                return;
            }
            Vector pull = anchor.toVector().subtract(player.getLocation().toVector());
            double distance = pull.length();
            if (distance < 2.0D) {
                cancel(holder[0]);
                // 到站反馈：自己被拽到锚点时脚下扬尘
                plugin.fx().particle("CLOUD", player.getLocation(), 8, 0.2D);
                plugin.fx().sound("ENTITY_ARROW_HIT_PLAYER", player, 0.5F, 1.4F);
                return;
            }
            // ease-out 收线：临近锚点拉力递减，"荡到位"而不是"撞到位"
            double ease = 0.35D + 0.65D * Math.min(1.0D, distance / 10.0D);
            pull.normalize().multiply(power * ease);
            pull.setY(Math.max(0.15D, Math.min(0.45D, distance * 0.08D)));
            player.setVelocity(player.getVelocity().multiply(0.2D).add(pull));
            player.setFallDistance(0.0F);
        }, 0L, 1L);
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件卸载阶段忽略
            }
        }
    }

    private LivingEntity findTarget(Player player, Location eye, Vector direction, double range, double halfAngle) {
        LivingEntity best = null;
        double bestAngle = halfAngle;
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(player) || living.isDead()) {
                continue;
            }
            // 拉扯是控制效果，友伤处理器拦不住位移，必须在选目标时就按统一口径过滤
            if (SkillTargets.isFilteredTarget(plugin, player, living)) {
                continue;
            }
            Vector to = living.getLocation().add(0.0D, living.getHeight() * 0.5D, 0.0D)
                    .toVector().subtract(eye.toVector());
            if (to.length() > range || to.length() < 0.5D) {
                continue;
            }
            double angle = direction.angle(to);
            if (angle < bestAngle) {
                bestAngle = angle;
                best = living;
            }
        }
        return best;
    }

    private void drawTrail(Location from, Location to, String particleName) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 0.1D) {
            return;
        }
        step.normalize().multiply(0.6D);
        Location cursor = from.clone();
        for (double travelled = 0.0D; travelled < length; travelled += 0.6D) {
            plugin.fx().particle(particleName, cursor, 1, 0.05D);
            cursor.add(step);
        }
    }
}
