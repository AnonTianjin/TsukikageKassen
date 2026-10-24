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
 * 火箭跳：把自己送上高空。
 *
 * <p><b>默认走纯药水</b>：靠 {@code LEVITATION}（漂浮）实现<b>自动上升</b> ——
 * 这是原版唯一能自动把人往上抬的效果。等级按 <code>potion-levitation-amplifier</code> 调，
 * 每级约 +0.05 格/tick；持续 <code>potion-levitation-ticks</code> 决定上升高度，
 * 结束后由免摔窗口保护落地。同时给 {@code JUMP_BOOST} 让玩家落下来后还能继续跳高。</p>
 *
 * <p>备选：{@code teleport}（逐 tick 传送上升，高度 = upward × boost-ticks，最确定）
 * 与 {@code velocity}（抛物线，可能被位置纠正清除）。</p>
 */
public final class RocketJumpSkill implements Skill {

    private final TaketoriPlugin plugin;

    public RocketJumpSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "rocket_jump";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double upward = context.dbl("upward", 0.9D);
        double backward = context.dbl("backward", 0.5D);
        double selfDamage = context.dbl("self-damage", 0.0D);
        int boostTicks = Math.max(1, context.integer("boost-ticks", 4));
        double keepRatio = context.dbl("boost-keep-ratio", 0.5D);
        // 默认纯药水（漂浮上升）
        String mode = context.str("boost-mode", "potion").toLowerCase(Locale.ROOT);

        int potionTicks = context.integer("potion-ticks", 80);
        int potionSpeed = context.integer("potion-speed", 0);
        int potionJump = context.integer("potion-jump", 3);
        boolean potionSlowFalling = context.bool("potion-slow-falling", false);
        boolean potionLevitation = context.bool("potion-levitation", true);
        int potionLevitationAmplifier = context.integer("potion-levitation-amplifier", 4);
        int potionLevitationTicks = context.integer("potion-levitation-ticks", 12);

        boolean wasFlying = player.isFlying();
        if (wasFlying && context.bool("cancel-flight", true)) {
            player.setFlying(false);
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.boost-defly"));
        }

        Vector look = player.getEyeLocation().getDirection().normalize();
        Vector velocity = look.multiply(-backward);
        velocity.setY(Math.max(context.dbl("min-lift", 0.6D), upward));

        Location start = player.getLocation().clone();
        BoostSupport.PotionBoost boost = new BoostSupport.PotionBoost(
                potionSpeed, potionJump, potionTicks, potionSlowFalling,
                potionLevitation, potionLevitationAmplifier, potionLevitationTicks);

        switch (mode) {
            case "teleport" -> {
                BoostSupport.teleportBoost(plugin, player, velocity, boostTicks);
                if (context.bool("potion-boost", false)) {
                    BoostSupport.applyPotionBoost(plugin, player, boost);
                }
            }
            case "velocity" -> {
                player.setVelocity(velocity);
                player.setFallDistance(0.0F);
                if (boostTicks > 1) {
                    final Vector keep = velocity.clone().multiply(keepRatio);
                    final int[] elapsed = {0};
                    final BukkitTask[] holder = new BukkitTask[1];
                    holder[0] = plugin.scheduler().runTimerTask(() -> {
                        if (elapsed[0]++ >= boostTicks - 1 || !player.isOnline() || player.isDead()) {
                            BoostSupport.cancel(holder[0]);
                            return;
                        }
                        keep.setY(Math.max(0.0D, keep.getY()));
                        player.setVelocity(player.getVelocity().multiply(0.3D).add(keep));
                        player.setFallDistance(0.0F);
                    }, 1L, 1L);
                }
                if (context.bool("potion-boost", false)) {
                    BoostSupport.applyPotionBoost(plugin, player, boost);
                }
            }
            default -> BoostSupport.applyPotionBoost(plugin, player, boost);
        }

        // 漂浮会把玩家抬到高空，落地必须有保护
        int immunityTicks = context.integer("fall-immunity-ticks",
                potionLevitation ? potionLevitationTicks + 80 : 80);
        if (immunityTicks > 0) {
            plugin.states().setFallImmunity(player.getUniqueId(), immunityTicks);
        }
        if (selfDamage > 0.0D) {
            // 技能执行期已带内部伤害标记，不会触发近战改写
            player.damage(selfDamage);
        }
        // 落地反馈（B7）：起飞后落地瞬间脚下扬尘 + 软垫音
        BoostSupport.landingBurst(plugin, player);

        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format(
                    "[combat] rocket_jump 模式=%s 目标速度%s（曾飞行=%s 漂浮=%s 免摔=%d tick）",
                    mode, BoostSupport.format(velocity), wasFlying, potionLevitation, immunityTicks));
        }
        BoostSupport.reportDisplacement(plugin, player, start,
                BoostSupport.estimateDisplacement(velocity, boostTicks), mode);

        plugin.fx().particle(context.str("particle", "EXPLOSION"), player.getLocation(), 12, 0.4D, 0.1D);
        plugin.fx().sound(context.str("sound", "ENTITY_GENERIC_EXPLODE"), player, 0.9F, 1.3F);
        return SkillResult.SUCCESS;
    }
}
