package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowman;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * PVE 的保卫据点：一个取消 AI 的雪傀儡，血量完全由插件管理。
 *
 * <p>为什么不用"真的让怪去打它"：雪傀儡对僵尸、骷髅都不是仇恨目标，
 * 靠原版 AI 永远拆不掉。所以这里反过来做——据点定在原地不动，
 * 每秒统计半径内的月人数量，按 {@code damage-per-second × 月人数} 扣血。</p>
 *
 * <p>据点本体设为无敌（invulnerable），玩家打不动它，只剩月人能拆：
 * 血量、进度条、播报全部走这里，避免出现"被队友一箭射没了"的情况。</p>
 */
public final class OutpostManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** 血量显示的刷新间隔（tick）；扣血判定也是这个节奏（1 秒一次）。 */
    private static final long TICK_PERIOD = 20L;

    /** 所属房间：场地/阶段/播报/刷怪归属全部按房间取。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;

    private PveSettings settings;
    private UUID entityId;
    private double health;
    private double maxHealth;
    private BukkitTask task;
    /** 每秒扣血的平滑值：多人同时拆时按月人数放大。 */
    private double lastDamagePerSecond;

    public OutpostManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    /** 是否正在运行（据点已经放出来且没被拆掉）。 */
    public boolean isActive() {
        return entityId != null;
    }

    public double health() {
        return Math.max(0.0D, health);
    }

    public double maxHealth() {
        return maxHealth;
    }

    /** 血量比例（0~1），记分板画条用。 */
    public double healthRatio() {
        return maxHealth <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, health / maxHealth));
    }

    /** 当前每秒被拆掉多少血（给记分板/状态命令显示压力）。 */
    public double damagePerSecond() {
        return lastDamagePerSecond;
    }

    /** 据点位置（雪傀儡脚下）；没放出来时返回 null。 */
    public Location location() {
        Entity entity = entity();
        return entity == null ? null : entity.getLocation();
    }

    /**
     * 放出据点并开始计时。
     *
     * @return 失败原因；成功返回 null
     */
    public String start(PveSettings pveSettings) {
        stop();
        if (pveSettings == null || !pveSettings.outpostEnabled()) {
            return "配置里关掉了 pve.outpost.enabled";
        }
        ArenaDef.Point spotPoint = room.arena().outpost();
        Location spot = spotPoint == null ? null : spotPoint.toBukkitLocation();
        if (spot == null || spot.getWorld() == null) {
            return "没有可用位置：先用 /taketori arena setoutpost 划定据点，或设置月人刷新区";
        }
        World world = spot.getWorld();
        Entity spawned;
        try {
            spawned = world.spawnEntity(spot, snowGolemType());
        } catch (Throwable throwable) {
            return "生成据点失败：" + throwable.getMessage();
        }
        if (!(spawned instanceof Snowman snowman)) {
            spawned.remove();
            return "生成据点失败：服务器返回的不是雪傀儡";
        }

        this.settings = pveSettings;
        this.maxHealth = pveSettings.outpostHealth();
        this.health = this.maxHealth;
        this.lastDamagePerSecond = 0.0D;
        this.entityId = snowman.getUniqueId();

        snowman.setAI(false);              // 取消移动 AI：据点原地不动
        snowman.setInvulnerable(true);     // 只有月人能拆（血量由插件结算）
        snowman.setPersistent(false);
        snowman.setRemoveWhenFarAway(false);
        snowman.setSilent(false);
        snowman.setCustomNameVisible(true);
        updateName();

        this.task = plugin.scheduler().runTimerTask(this::tick, TICK_PERIOD, TICK_PERIOD);
        room.broadcast(pveSettings.outpostName() + " <gray>已就位！"
                + "<white>守住它</white> <dark_gray>(" + (int) maxHealth + " 点耐久，"
                + settings.difficultyDisplay() + " 难度)");
        if (plugin.config().debug()) {
            plugin.getLogger().info("[pve] 据点已生成于 " + spot.getBlockX() + "," + spot.getBlockY()
                    + "," + spot.getBlockZ() + "，耐久 " + maxHealth);
        }
        return null;
    }

    /** 结束对局 / 插件卸载时清理。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Entity entity = entity();
        if (entity != null) {
            entity.remove();
        }
        entityId = null;
        health = 0.0D;
        maxHealth = 0.0D;
        lastDamagePerSecond = 0.0D;
    }

    /** 每秒一次：按半径内的月人数扣血。 */
    private void tick() {
        if (!room.isRunning()) {
            stop();
            return;
        }
        Entity entity = entity();
        if (entity == null) {
            entityId = null;
            stop();
            return;
        }
        if (settings == null) {
            return;
        }

        int attackers = countAttackers(entity.getLocation());
        double damage = attackers * settings.outpostDamagePerSecond();
        lastDamagePerSecond = damage;
        if (damage > 0.0D) {
            health -= damage;
            // 表现：被拆时冒烟、掉灰，让"据点正在被打"肉眼可见
            plugin.fx().particle("SMOKE", entity.getLocation().add(0.0D, 1.0D, 0.0D), 8, 0.4D);
            plugin.fx().sound("BLOCK_ANVIL_LAND", entity.getLocation(), 0.35F, 1.6F);
            if (plugin.config().debug()) {
                plugin.getLogger().info("[pve] 据点被 " + attackers + " 个月人拆：-" + String.format(Locale.ROOT, "%.1f", damage)
                        + " → " + String.format(Locale.ROOT, "%.1f", health) + "/" + maxHealth);
            }
        }
        updateName();

        if (health <= 0.0D) {
            destroy();
        }
    }

    /** 半径内的月人数量（精英也算，且按 1 个算）。 */
    private int countAttackers(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return 0;
        }
        double radius = settings == null ? 6.0D : settings.outpostRadius();
        Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);
        int count = 0;
        for (Entity entity : nearby) {
            if (entity instanceof LivingEntity && room.minions().isTracked(entity)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 雪傀儡的实体类型名跨版本不同：MC 1.20.5+ 起是 {@code SNOW_GOLEM}，
     * 更早的版本叫 {@code SNOWMAN}，所以两个都试。
     */
    private EntityType snowGolemType() {
        try {
            return EntityType.valueOf("SNOW_GOLEM");
        } catch (IllegalArgumentException ignored) {
            return EntityType.valueOf("SNOWMAN");
        }
    }

    private void destroy() {
        Location spot = location();
        String name = settings == null ? "据点" : settings.outpostName();
        stop();
        room.broadcast(name + " <dark_red><bold>已被月人拆毁！");
        if (spot != null) {
            plugin.fx().particle("EXPLOSION", spot, 12, 1.2D);
            plugin.fx().sound("ENTITY_GENERIC_EXPLODE", spot, 1.0F, 0.8F);
        }
        if (settings != null && settings.endMatchOnOutpostDestroyed() && room.isRunning()) {
            room.stop("据点被拆毁");
        }
    }

    private void updateName() {
        Entity entity = entity();
        if (entity == null || settings == null) {
            return;
        }
        int current = (int) Math.ceil(Math.max(0.0D, health));
        String color = healthRatio() > 0.5D ? "<green>" : healthRatio() > 0.2D ? "<yellow>" : "<red>";
        entity.customName(MINI.deserialize(settings.outpostName() + " " + color
                + current + "<dark_gray>/<gray>" + (int) maxHealth));
    }

    private Entity entity() {
        return entityId == null ? null : plugin.getServer().getEntity(entityId);
    }
}
