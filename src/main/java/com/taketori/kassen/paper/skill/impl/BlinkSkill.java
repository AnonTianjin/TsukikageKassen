package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 快速位移：疾风钢丝装备的 Shift+右键。
 *
 * <p>沿视线水平瞬移，并优先寻找"脚可站、头可容、脚下有支撑"的落点。
 * 若前方是悬空或水面（找不到支撑），<b>退化为保持原高度的水平位移</b>——
 * 否则在平台边缘、船上、水面上按位移会完全没有反应。</p>
 */
public final class BlinkSkill implements Skill {

    private final TaketoriPlugin plugin;

    public BlinkSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "blink";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        double distance = context.dbl("distance", 6.0D);
        double minDistance = context.dbl("min-distance", 1.5D);
        World world = player.getWorld();
        Location origin = player.getLocation();

        Vector horizontal = origin.getDirection().clone().setY(0);
        if (horizontal.lengthSquared() < 0.0001D) {
            return SkillResult.NO_TARGET;
        }
        horizontal.normalize();

        // ① 优先找一个真正能站住的落点
        Location destination = null;
        for (double travelled = distance; travelled >= minDistance; travelled -= 0.5D) {
            Location probe = origin.clone().add(horizontal.clone().multiply(travelled));
            Location safe = findSafeSpot(world, probe);
            if (safe != null) {
                destination = safe;
                break;
            }
        }

        // ② 找不到支撑（悬空/水面/平台边缘）→ 保持原高度的水平位移，尽量别让技能"没反应"
        if (destination == null) {
            for (double travelled = distance; travelled >= minDistance; travelled -= 0.5D) {
                Location probe = origin.clone().add(horizontal.clone().multiply(travelled));
                probe.setY(origin.getY());
                if (isPassable(world, probe)) {
                    destination = probe;
                    break;
                }
            }
        }

        if (destination == null) {
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.no-target"));
            return SkillResult.NO_TARGET;
        }

        destination.setYaw(origin.getYaw());
        destination.setPitch(origin.getPitch());
        plugin.fx().particle(context.str("particle", "END_ROD"), origin, 20, 0.3D);
        player.teleport(destination);
        player.setFallDistance(0.0F);
        plugin.fx().particle(context.str("particle", "END_ROD"), destination, 20, 0.3D);
        plugin.fx().particle("CLOUD", destination, 8, 0.25D);   // 落点扬尘（B7 到站反馈）
        plugin.fx().sound(context.str("sound", "ENTITY_ENDERMAN_TELEPORT"), destination, 0.8F, 1.2F);
        // 2.0：落点余威（reserve-*）——寒冰滑步留下冰环、影步落地有伤害
        applyReserve(context, player, destination);
        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format("[combat] blink 瞬移到 %.1f %.1f %.1f",
                    destination.getX(), destination.getY(), destination.getZ()));
        }
        return SkillResult.SUCCESS;
    }

    /** 在 (x,z) 处从起点向下找脚可站、头可容、脚下有支撑的位置；找不到返回 null。 */
    private Location findSafeSpot(World world, Location probe) {
        int startY = Math.min(world.getMaxHeight() - 2, probe.getBlockY() + 1);
        int minY = world.getMinHeight() + 1;
        for (int y = startY; y >= Math.max(minY, startY - 12); y--) {
            Block feet = world.getBlockAt(probe.getBlockX(), y, probe.getBlockZ());
            Block head = world.getBlockAt(probe.getBlockX(), y + 1, probe.getBlockZ());
            Block below = world.getBlockAt(probe.getBlockX(), y - 1, probe.getBlockZ());
            boolean feetFree = feet.isPassable() && feet.getType() != Material.LAVA;
            boolean headFree = head.isPassable();
            boolean support = below.getType().isSolid();
            if (feetFree && headFree && support) {
                return new Location(world, probe.getBlockX() + 0.5D, y, probe.getBlockZ() + 0.5D);
            }
        }
        return null;
    }

    /** 脚与头两格是否可通行（用于"保持原高度"的退化路径）。 */
    private boolean isPassable(World world, Location location) {
        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Block head = world.getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        return feet.isPassable() && head.isPassable() && feet.getType() != Material.LAVA;
    }

    /**
     * 落点余威：传送完成后对落点半径内的敌人结算伤害与减速。
     *
     * <p>{@code reserve-damage} 与 {@code reserve-slow-duration} 都为 0 时整个跳过——
     * 老配置（只写 distance 的纯位移）行为不变。</p>
     */
    private void applyReserve(SkillContext context, Player player, Location destination) {
        double damage = context.dbl("reserve-damage", 0.0D);
        int slowDuration = context.integer("reserve-slow-duration", 0);
        if (damage <= 0.0D && slowDuration <= 0) {
            return;
        }
        double radius = Math.max(0.5D, context.dbl("reserve-radius", 2.5D));
        List<LivingEntity> targets = SkillTargets.enemiesInRadius(plugin, player, destination, radius);
        for (LivingEntity target : targets) {
            if (damage > 0.0D) {
                target.damage(damage, player);
                plugin.damageNumbers().hit(player, target, damage);
            }
            if (slowDuration > 0) {
                PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
                if (slow != null) {
                    target.addPotionEffect(new PotionEffect(slow, slowDuration,
                            Math.max(0, context.integer("reserve-slow-amplifier", 0)), false, true, true));
                }
            }
        }
        plugin.fx().particle(context.str("reserve-particle", "CLOUD"), destination, 20, radius * 0.4D);
    }
}
