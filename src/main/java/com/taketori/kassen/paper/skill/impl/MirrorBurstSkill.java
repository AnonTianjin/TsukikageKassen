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
 * 镜光爆发（月镜 Shift+右键）：以自身为中心的范围压制——减速 + 致盲 + 少量伤害。
 */
public final class MirrorBurstSkill implements Skill {

    private final TaketoriPlugin plugin;

    public MirrorBurstSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "mirror_burst";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double radius = context.dbl("radius", 5.0D);
        double damage = context.dbl("damage", 4.0D);
        // 重技能施法演出（D13）：title + 低沉施法音，与瞬发右键技能的"轻快"区分
        plugin.fx().castHeavy(player, "镜光爆发");
        // 键名是 slow-duration（不是 duration-ticks）——必须与 weapons.yml、ConfigValidator 一致，
        // 否则玩家改配置不会生效而且没有任何报错
        int slowDuration = context.integer("slow-duration", 80);
        int slowAmplifier = Math.max(0, context.integer("slow-amplifier", 1));
        int blindnessTicks = context.integer("blindness-ticks", 40);
        // 2.0：兑现——对已被冰冻 / 减速 / 标记 / 破甲的目标追加伤害（与旗鱼组成"冰封后镜爆"）
        double echoBonus = context.dbl("echo-bonus", 1.0D);

        Location center = player.getLocation();
        int affected = 0;
        for (Entity entity : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(player) || living.isDead()) {
                continue;
            }
            if (SkillTargets.isFilteredTarget(plugin, player, living)) {
                continue;
            }
            // getNearbyEntities 是立方体包围盒，角落目标最远可达 radius 的 √3 倍——
            // 必须再做球形距离裁剪，否则范围外的角落目标也被命中（与 ShockwaveSkill 同口径）
            if (living.getLocation().distance(center) > radius) {
                continue;
            }
            affected++;
            double applied = damage;
            if (echoBonus > 1.0D && SkillTargets.hasConsumableState(plugin, living)) {
                applied = damage * echoBonus;
                plugin.fx().actionBar(player, plugin.config().messages().get("skill.echo-consume",
                        "bonus", Math.round(echoBonus * 100.0D)));
            }
            if (applied > 0.0D) {
                living.damage(applied, player);
                plugin.damageNumbers().hit(player, living, applied);   // 伤害数字（A1）
            }
            apply(living, "SLOWNESS", slowDuration, slowAmplifier);
            apply(living, "BLINDNESS", blindnessTicks, 0);
        }

        drawRing(center, radius, context.str("particle", "END_ROD"));
        plugin.fx().sound(context.str("sound", "BLOCK_GLASS_BREAK"), center, 1.2F, 0.7F);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[debug] mirror_burst 影响目标数: " + affected);
        }
        return SkillResult.SUCCESS;
    }

    private void apply(LivingEntity target, String effectName, int durationTicks, int amplifier) {
        if (durationTicks <= 0) {
            return;
        }
        PotionEffectType type = plugin.versions().potionEffect(effectName);
        if (type != null) {
            target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, true, true));
        }
    }

    private void drawRing(Location center, double radius, String particleName) {
        int points = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius);
            plugin.fx().particle(particleName, point, 1, 0.05D);
        }
    }

    /** 保留给将来的位移类镜面效果（例如镜面反射把自己弹开）。 */
    @SuppressWarnings("unused")
    private void push(Player player, double power) {
        Vector velocity = player.getLocation().getDirection().normalize().multiply(-power);
        velocity.setY(0.3D);
        player.setVelocity(velocity);
    }
}
