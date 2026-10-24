package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 钢丝牵引：疾风钢丝装备的右键。
 *
 * <p>优先牵引视线方向上的生物；没有目标时退化为"钩向方块"，把自己拉过去。
 * 由于纯服务端没有钩爪实体动画，用手感补偿：即时牵引 + 粒子轨迹 + 音效。</p>
 */
public final class GrappleSkill implements Skill {

    private final TaketoriPlugin plugin;

    public GrappleSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "grapple";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double range = context.dbl("range", 12.0D);
        double power = context.dbl("power", 0.9D);
        double upward = context.dbl("upward", 0.15D);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity target = findEntityTarget(player, eye, direction, range);
        Location anchor;
        if (target != null) {
            anchor = target.getLocation().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        } else {
            RayTraceResult trace = player.getWorld().rayTraceBlocks(eye, direction, range, org.bukkit.FluidCollisionMode.NEVER, true);
            if (trace == null || trace.getHitPosition() == null) {
                plugin.fx().actionBar(player, plugin.config().messages().get("skill.grapple-miss"));
                return SkillResult.NO_TARGET;
            }
            anchor = trace.getHitPosition().toLocation(player.getWorld());
        }

        Vector pull = anchor.toVector().subtract(player.getLocation().toVector());
        if (pull.lengthSquared() < 0.01D) {
            return SkillResult.NO_TARGET;
        }
        double distance = pull.length();
        Vector velocity = pull.normalize().multiply(Math.min(power, distance * 0.25D));
        velocity.setY(Math.max(velocity.getY(), 0.0D) + upward);
        player.setVelocity(velocity);
        // 牵引会把玩家抛高，落地不吃摔落伤害（与 SelfBoost/RocketJump/Blink 的落地保护口径一致）；
        // 持续时间按牵引幅度给一个窗口，覆盖到落地
        plugin.states().setFallImmunity(player.getUniqueId(), (int) Math.ceil(distance / 5.0D) + 20);

        drawTrail(eye, anchor, context.str("particle", "END_ROD"));
        plugin.fx().sound(context.str("sound", "ITEM_TRIDENT_RETURN"), player, 0.9F, 1.3F);
        return SkillResult.SUCCESS;
    }

    private LivingEntity findEntityTarget(Player player, Location eye, Vector direction, double range) {
        LivingEntity best = null;
        double bestAngle = Math.toRadians(35.0D);
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(player) || living.isDead()) {
                continue;
            }
            // 不把受保护的队友/召唤物/笼内玩家当锚点，否则会把玩家拽向这些人
            if (SkillTargets.isFilteredTarget(plugin, player, living)) {
                continue;
            }
            Vector to = living.getLocation().add(0.0D, living.getHeight() * 0.5D, 0.0D)
                    .toVector().subtract(eye.toVector());
            if (to.length() > range) {
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
