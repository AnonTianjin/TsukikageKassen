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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * 冲击波：以自身为圆心的 360° 范围伤害 + <b>向上击飞</b>，可选减速或定身。
 *
 * <p>与 {@code melee_smash} 的区别是机制不是数值：近战打"前方扇形"，这个打"脚下整圈"，
 * 并把敌人打上天——用来开团、拆阵型、把贴脸的人赶走。</p>
 *
 * <p><b>命中 0 个目标时会有明确提示</b>：否则玩家只看到粒子音效，会误以为"技能没效果"
 * （尤其是在高空或空旷处测试时）。</p>
 */
public final class ShockwaveSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ShockwaveSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "shockwave";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        if (player.getAttackCooldown() < context.dbl("min-charge", 0.9D)) {
            return SkillResult.NO_TARGET;
        }
        // 重技能施法演出（D13）：title + 低沉施法音，与瞬发右键技能的"轻快"区分
        plugin.fx().castHeavy(player, "冲击波");

        double damage = context.dbl("damage", 10.0D);
        double radius = context.dbl("radius", 4.0D);
        double knockback = context.dbl("knockback", 0.6D);
        double launch = context.dbl("launch", 0.45D);
        double minFalloff = Math.max(0.0D, Math.min(1.0D, context.dbl("min-falloff", 0.5D)));
        int slowDuration = context.integer("slow-duration", 0);
        int freezeTicks = context.integer("freeze-ticks", 0);
        // 2.0：冲击波也能挂状态——月读重锤给易伤、盾胁的震旗给破甲（此前白名单收了但没人读）
        double markBonus = context.dbl("mark-bonus", 0.0D);
        int markTicks = context.integer("mark-ticks", 80);
        double armorPierce = context.dbl("armor-pierce", 0.0D);
        int debuffTicks = context.integer("debuff-ticks", 100);
        int debuffStacks = context.integer("debuff-stacks", 1);

        Location center = player.getLocation();
        int hit = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(player) || living.isDead()) {
                continue;
            }
            if (SkillTargets.isFilteredTarget(plugin, player, living)) {
                continue;
            }
            double distance = living.getLocation().distance(center);
            if (distance > radius) {
                continue;
            }
            double ratio = Math.max(minFalloff, 1.0D - distance / (radius + 0.5D));
            living.damage(damage * ratio, player);
            plugin.damageNumbers().hit(player, living, damage * ratio);   // 伤害数字（A1）

            Vector push = living.getLocation().toVector().subtract(center.toVector());
            push.setY(0.0D);
            if (push.lengthSquared() > 0.0001D) {
                push.normalize().multiply(knockback);
            }
            push.setY(launch);
            living.setVelocity(living.getVelocity().add(push));

            if (slowDuration > 0) {
                PotionEffectType slow = plugin.versions().potionEffect(context.str("slow-type", "SLOWNESS"));
                if (slow != null) {
                    living.addPotionEffect(new PotionEffect(slow, slowDuration,
                            Math.max(0, context.integer("slow-amplifier", 0)), false, true, true));
                }
            }
            if (freezeTicks > 0) {
                applyFreeze(living, freezeTicks);
            }
            if (markBonus > 0.0D) {
                plugin.states().mark(living.getUniqueId(), markTicks, markBonus);
            }
            if (armorPierce > 0.0D) {
                plugin.states().armorBreak(living.getUniqueId(), debuffTicks, armorPierce, debuffStacks);
            }
            hit++;
        }

        drawRing(center, radius, context.str("particle", "EXPLOSION"));
        plugin.fx().sound(context.str("sound", "BLOCK_ANVIL_LAND"), center, 1.2F, 0.8F);
        if (hit == 0) {
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.shockwave-none",
                    "radius", String.format("%.1f", radius)));
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format(
                    "[combat] shockwave 半径 %.1f 命中 %d 个目标（击飞 %.2f / 减速 %d tick / 定身 %d tick）",
                    radius, hit, launch, slowDuration, freezeTicks));
        }
        return SkillResult.SUCCESS;
    }

    /** 定身：缓慢拉满 + 负跳跃（纯原版药水效果，跨版本安全）。 */
    private void applyFreeze(LivingEntity target, int ticks) {
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        if (slow != null) {
            target.addPotionEffect(new PotionEffect(slow, ticks, 250, false, true, true));
        }
        PotionEffectType jump = plugin.versions().potionEffect("JUMP_BOOST");
        if (jump != null) {
            target.addPotionEffect(new PotionEffect(jump, ticks, 128, false, false, true));
        }
    }

    private void drawRing(Location center, double radius, String particleName) {
        int points = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.2D, Math.sin(angle) * radius);
            plugin.fx().particle(particleName, point, 1, 0.05D);
        }
    }
}
