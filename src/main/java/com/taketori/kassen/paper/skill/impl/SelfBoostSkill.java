package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * 自身推进：月读的喷射推进、疾风的高速突进与强化机动、盾胁的冲锋。
 *
 * <p><b>默认走纯药水（{@code boost-mode: potion}）</b>：不写位置也不写速度，
 * 全部交给原版机制——</p>
 * <ul>
 *   <li>{@code SPEED}：跑得更快（"推进"的持续感）</li>
 *   <li>{@code JUMP_BOOST}：跳得更高</li>
 *   <li>{@code LEVITATION}：<b>自动上升</b>（有向上分量的技能会启用，等级与时长可配）</li>
 * </ul>
 *
 * <p>备选路径：{@code teleport}（逐 tick 传送，位移最确定但观感硬、可能被反作弊拦）与
 * {@code velocity}（平滑但速度可能被位置纠正清除）。</p>
 */
public final class SelfBoostSkill implements Skill {

    private final TaketoriPlugin plugin;

    public SelfBoostSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "self_boost";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double power = context.dbl("power", 1.1D);
        double upward = context.dbl("upward", 0.25D);
        double minLift = context.dbl("min-lift", 0.42D);
        int boostTicks = Math.max(1, context.integer("boost-ticks", 4));
        double keepRatio = context.dbl("boost-keep-ratio", 0.7D);
        // 默认纯药水：原版机制，不受位置纠正与反作弊影响
        String mode = context.str("boost-mode", "potion").toLowerCase(Locale.ROOT);

        // 药水参数
        int potionTicks = context.integer("potion-ticks", 60);
        int potionSpeed = context.integer("potion-speed", 2);
        int potionJump = context.integer("potion-jump", 2);
        boolean potionSlowFalling = context.bool("potion-slow-falling", false);
        // 有向上分量时才启用漂浮（纯水平推进不需要自动上升）
        boolean potionLevitation = context.bool("potion-levitation", upward > 0.0D);
        int potionLevitationAmplifier = context.integer("potion-levitation-amplifier", 3);
        int potionLevitationTicks = context.integer("potion-levitation-ticks", 8);

        boolean wasFlying = player.isFlying();
        if (wasFlying && context.bool("cancel-flight", true)) {
            player.setFlying(false);
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.boost-defly"));
        }

        Vector look = player.getEyeLocation().getDirection().normalize();
        Vector base = look.multiply(power);
        base.setY(Math.max(minLift, look.getY() * power * 0.6D + upward));

        Location start = player.getLocation().clone();
        Vector before = player.getVelocity().clone();

        BoostSupport.PotionBoost boost = new BoostSupport.PotionBoost(
                potionSpeed, potionJump, potionTicks, potionSlowFalling,
                potionLevitation, potionLevitationAmplifier, potionLevitationTicks);

        switch (mode) {
            case "teleport" -> {
                BoostSupport.teleportBoost(plugin, player, base, boostTicks);
                if (context.bool("potion-boost", false)) {
                    BoostSupport.applyPotionBoost(plugin, player, boost);
                }
            }
            case "velocity" -> {
                player.setVelocity(base);
                player.setFallDistance(0.0F);
                if (boostTicks > 1) {
                    final BukkitTask[] holder = new BukkitTask[1];
                    final int[] elapsed = {0};
                    holder[0] = plugin.scheduler().runTimerTask(() -> {
                        if (elapsed[0]++ >= boostTicks - 1 || !player.isOnline() || player.isDead()) {
                            BoostSupport.cancel(holder[0]);
                            return;
                        }
                        Vector keep = base.clone().multiply(keepRatio);
                        keep.setY(Math.max(0.0D, base.getY() * keepRatio));
                        player.setVelocity(player.getVelocity().multiply(0.3D).add(keep));
                        player.setFallDistance(0.0F);
                    }, 1L, 1L);
                }
                if (context.bool("potion-boost", false)) {
                    BoostSupport.applyPotionBoost(plugin, player, boost);
                }
            }
            default -> {
                // potion：纯药水机动
                BoostSupport.applyPotionBoost(plugin, player, boost);
            }
        }

        // 漂浮模式下玩家会被抬升，落地需要保护；其余模式也一并给窗口，避免推进后摔伤
        int immunityTicks = context.integer("fall-immunity-ticks",
                potionLevitation ? potionLevitationTicks + 60 : 70);
        if (immunityTicks > 0) {
            plugin.states().setFallImmunity(player.getUniqueId(), immunityTicks);
        }

        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format(
                    "[combat] self_boost 模式=%s 原速度%s → 目标%s → 服务端当前%s（曾飞行=%s 免摔=%d tick）",
                    mode, BoostSupport.format(before), BoostSupport.format(base),
                    BoostSupport.format(player.getVelocity()), wasFlying, immunityTicks));
        }
        BoostSupport.reportDisplacement(plugin, player, start,
                BoostSupport.estimateDisplacement(base, boostTicks), mode);

        plugin.fx().sound(context.str("sound", "ENTITY_FIREWORK_ROCKET_SHOOT"), player, 1.0F, 1.0F);
        plugin.fx().particle(context.str("particle", "CLOUD"), player.getLocation(), 16, 0.35D, 0.05D);
        return SkillResult.SUCCESS;
    }
}
