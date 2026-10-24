package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

/**
 * 召唤守夜犬（2.0 新增）：在施法者前方生成一只受控生物，存活一段时间并自动攻击附近敌人。
 *
 * <p>它是"牵制与视野"而不是伤害来源——45 秒冷却、12 秒存活，等效输出增量约 0.8 DPS。
 * 召唤物带 {@code summoned_owner} / {@code summoned_expire} 两个 PDC 标记，
 * 用于三件事：队友与召唤者不能伤害它、击杀它不计分（防刷分）、房间销毁时统一回收。</p>
 *
 * <p>同一玩家同时只保留一只：再次施法先把旧的移除。参数全部来自 weapons.yml，可热重载。</p>
 */
public final class SummonAllySkill implements Skill {

    /** 数量硬上限：再多就不是"召唤一只狗"，而是拉一支小队了。 */
    private static final int MAX_COUNT = 2;
    /** 只允许这些类型（中立 / 可驯服），避免召出会自己乱炸的怪。 */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "WOLF", "CAT", "PARROT", "SNOW_GOLEM", "IRON_GOLEM");

    private final TaketoriPlugin plugin;
    /**
     * 每个 owner 的"到期回收"任务句柄。
     *
     * <p>必须持有并在重召时取消：否则 t=0 召唤、t=6s 重召时，<b>第一个</b>任务会在 t=12s
     * 把新那只也一起删掉（实际存活只有 6 秒）。</p>
     */
    private final java.util.Map<UUID, org.bukkit.scheduler.BukkitTask> expiryTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SummonAllySkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "summon_ally";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        String rawType = context.str("entity", "WOLF").trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED.contains(rawType)) {
            plugin.getLogger().warning("summon_ally 的 entity 不在允许清单里：" + rawType);
            plugin.fx().actionBar(player, plugin.config().messages().get("skill.no-target"));
            return SkillResult.FAILED;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(rawType);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("summon_ally 无法解析实体类型：" + rawType);
            return SkillResult.FAILED;
        }

        UUID owner = player.getUniqueId();
        removeOwned(owner);   // 同一玩家只保留本次召唤的这一批

        int count = Math.max(1, Math.min(MAX_COUNT, context.integer("count", 1)));
        double health = Math.max(1.0D, context.dbl("health", 12.0D));
        double damage = context.dbl("damage", 3.0D);
        int durationTicks = Math.max(20, context.integer("duration-ticks", 240));
        int speedAmplifier = Math.max(0, context.integer("speed-amplifier", 1));
        double spawnDistance = Math.max(0.0D, context.dbl("spawn-distance", 2.0D));
        String name = context.str("name", "守夜犬");

        Location origin = player.getLocation().clone()
                .add(player.getEyeLocation().getDirection().setY(0).normalize().multiply(spawnDistance));

        PotionEffectType speed = plugin.versions().potionEffect("SPEED");
        PotionEffectType strength = plugin.versions().potionEffect("STRENGTH");
        if (strength == null) {
            strength = plugin.versions().potionEffect("INCREASE_DAMAGE");
        }

        for (int i = 0; i < count; i++) {
            Entity spawned = player.getWorld().spawnEntity(origin, type);
            if (!(spawned instanceof LivingEntity ally)) {
                spawned.remove();
                continue;
            }
            var pdc = ally.getPersistentDataContainer();
            pdc.set(PDCKeys.summonedOwner(), PersistentDataType.STRING, owner.toString());

            if (ally instanceof Tameable tameable) {
                tameable.setTamed(true);
                tameable.setOwner(player);
            }
            ally.customName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(name));
            ally.setCustomNameVisible(true);
            ally.setRemoveWhenFarAway(true);
            ally.setMaximumAir(Integer.MAX_VALUE);
            if (ally.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
                ally.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(health);
            }
            ally.setHealth(Math.min(health, ally.getMaxHealth()));
            // 原版狼的攻击力不可直接改，用力量药水等级换算
            if (strength != null) {
                int amplifier = Math.max(0, (int) Math.round(damage / 3.0D) - 1);
                ally.addPotionEffect(new PotionEffect(strength, durationTicks, amplifier, false, false, true));
            }
            if (speed != null) {
                ally.addPotionEffect(new PotionEffect(speed, durationTicks, speedAmplifier, false, false, true));
            }
            if (ally instanceof Mob mob) {
                LivingEntity target = nearestEnemy(player, origin);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
        }

        // 到期自动回收（不依赖实体自身的存活判断，房间清场时也会兜底）
        expiryTasks.put(owner, plugin.scheduler().runLater(() -> removeOwned(owner), durationTicks));

        plugin.fx().particle(context.str("particle", "CLOUD"), origin, 20, 0.4D);
        plugin.fx().sound(context.str("sound", "ENTITY_WOLF_GROWL"), origin, 1.0F, 1.0F);
        plugin.fx().actionBar(player, plugin.config().messages().get("skill.summon-ready",
                "seconds", durationTicks / 20));
        return SkillResult.SUCCESS;
    }

    /** 移除该玩家名下的全部召唤物（再次施法 / 到期 / 房间清理共用）。 */
    public void removeOwned(UUID owner) {
        if (owner == null) {
            return;
        }
        org.bukkit.scheduler.BukkitTask pending = expiryTasks.remove(owner);
        if (pending != null) {
            pending.cancel();
        }
        String key = owner.toString();
        // 只扫"主人在的世界 + 其房间世界"：全服所有世界×全部实体的主线程扫描，
        // 实体多时有卡顿尖峰。召唤物只会刷在主人所在的动态房间世界里，其他世界不必扫。
        java.util.LinkedHashSet<org.bukkit.World> worlds = new java.util.LinkedHashSet<>();
        Player ownerPlayer = plugin.getServer().getPlayer(owner);
        var ownerRoom = ownerPlayer == null ? null : plugin.rooms().roomOf(ownerPlayer);
        if (ownerRoom != null && ownerRoom.world() != null) {
            worlds.add(ownerRoom.world());
        }
        if (ownerPlayer != null && ownerPlayer.isOnline()) {
            worlds.add(ownerPlayer.getWorld());
        }
        if (worlds.isEmpty()) {
            // 主人离线且无房间（兜底路径，如插件卸载）：才退回全量扫描
            worlds.addAll(plugin.getServer().getWorlds());
        }
        for (org.bukkit.World world : worlds) {
            for (Entity entity : world.getEntities()) {
                String tagged = entity.getPersistentDataContainer()
                        .get(PDCKeys.summonedOwner(), PersistentDataType.STRING);
                if (key.equals(tagged)) {
                    entity.remove();
                }
            }
        }
    }

    /** 离召唤点最近的敌方生物（用作初始目标）。 */
    private LivingEntity nearestEnemy(Player owner, Location origin) {
        java.util.List<LivingEntity> candidates = SkillTargets.enemiesInRadius(plugin, owner, origin, 16.0D);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distance = candidate.getLocation().distanceSquared(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
