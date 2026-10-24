package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 对局内道具刷新器：在"道具刷新点"（用道具点工具划定、<code>/taketori arena setloot &lt;编号&gt;</code> 绑定）
 * 定时刷出道具，玩家捡走即得。
 *
 * <p>刷新池写在 <code>config.yml</code> 的 <code>loot</code> 段，每项要么是原版物品
 * （<code>material</code> + <code>amount</code>），要么是插件武器（<code>weapon: 武器id</code>），
 * 用 <code>weight</code> 控制权重：</p>
 *
 * <pre>
 * loot:
 *   enabled: true
 *   interval-seconds: 30
 *   max-drops: 6          # 场上同时存在的道具上限（所有刷新点合计）
 *   despawn-seconds: 180  # 超过这个时间还没被捡走就消失（0 = 不消失）
 *   items:
 *     - material: GOLDEN_APPLE
 *       amount: 2
 *       weight: 3
 *     - weapon: frozen_swordfish
 *       weight: 1
 * </pre>
 *
 * <p>道具是自己刷在地面上的（<code>dropItemNaturally</code>），没有额外的保护期；
 * 对局结束或插件卸载时会清掉本插件刷出的所有道具。</p>
 */
public final class LootSpawner {

    /** 刷新池里的一项。 */
    private record Entry(ItemStack stack, int weight, String label) {
    }

    /** 所属房间：场地刷新区与对局阶段按房间取。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;
    /** 本插件刷出的掉落物（对局结束时清理）。 */
    private final Set<UUID> drops = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private boolean enabled;
    private int intervalSeconds;
    private int maxDrops;
    private int despawnSeconds;
    private List<Entry> pool = List.of();

    public LootSpawner(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    /** 重读配置（启动与 /taketori reload）。 */
    public void load() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("loot.enabled", true);
        intervalSeconds = Math.max(5, cfg.getInt("loot.interval-seconds", 30));
        maxDrops = Math.max(0, cfg.getInt("loot.max-drops", 6));
        despawnSeconds = Math.max(0, cfg.getInt("loot.despawn-seconds", 180));

        List<Entry> parsed = new ArrayList<>();
        for (var raw : cfg.getMapList("loot.items")) {
            int weight = raw.get("weight") instanceof Number number ? Math.max(1, number.intValue()) : 1;
            Object weaponId = raw.get("weapon");
            if (weaponId != null) {
                var weapon = plugin.config().weapons().get(String.valueOf(weaponId));
                if (weapon == null) {
                    plugin.getLogger().warning("loot.items 里的武器不存在：" + weaponId);
                    continue;
                }
                parsed.add(new Entry(plugin.items().create(weapon, null), weight, weapon.id()));
                continue;
            }
            Object materialName = raw.get("material");
            if (materialName == null) {
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(materialName).trim().toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("loot.items 里的 material 无效：" + materialName);
                continue;
            }
            int amount = raw.get("amount") instanceof Number number ? Math.max(1, number.intValue()) : 1;
            parsed.add(new Entry(new ItemStack(material, amount), weight,
                    material.name().toLowerCase(Locale.ROOT) + "×" + amount));
        }
        pool = List.copyOf(parsed);

        if (plugin.config().debug()) {
            plugin.getLogger().info("[loot] 配置载入：enabled=" + enabled + " 间隔 " + intervalSeconds
                    + "s 上限 " + maxDrops + " 池 " + pool.size() + " 项");
        }
    }

    /** 对局开始时调用。 */
    public void start() {
        stop();
        if (!enabled || pool.isEmpty() || room.arena().lootRegionCount() == 0) {
            return;
        }
        long interval = intervalSeconds * 20L;
        task = plugin.scheduler().runTimerTask(this::tick, interval, interval);
    }

    /** 对局结束或插件卸载时调用：停任务并清掉刷出的道具。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAll();
    }

    public int dropCount() {
        int alive = 0;
        for (UUID uuid : new ArrayList<>(drops)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                drops.remove(uuid);
                continue;
            }
            alive++;
        }
        return alive;
    }

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        int slots = maxDrops - dropCount();
        if (slots <= 0) {
            return;
        }
        List<CuboidRegion> regions = new ArrayList<>(room.arena().lootRegions().values());
        if (regions.isEmpty()) {
            return;
        }
        // 每个刷新点尝试刷一个，直到用完额度
        for (CuboidRegion region : regions) {
            if (slots <= 0) {
                break;
            }
            if (spawnOne(region)) {
                slots--;
            }
        }
    }

    private boolean spawnOne(CuboidRegion region) {
        World world = region.world();
        if (world == null) {
            return false;
        }
        Location location = region.randomLocation();
        if (location == null) {
            return false;
        }
        Entry entry = randomEntry();
        if (entry == null) {
            return false;
        }
        Item dropped = world.dropItemNaturally(location, entry.stack().clone());
        dropped.setPickupDelay(10);
        // 原版掉落物寿命固定 6000 tick（300 秒）：超过 300 秒的配置无法实现，
        // 旧写法算出负数再钳为 1，道具几乎立刻消失。这里显式钳制并警告。
        if (despawnSeconds > 0) {
            if (despawnSeconds > 300) {
                plugin.getLogger().warning("[loot] despawn-seconds=" + despawnSeconds
                        + " 超过原版掉落物寿命上限 300 秒，已按 300 秒处理");
            }
            int seconds = Math.min(despawnSeconds, 300);
            dropped.setTicksLived(Math.max(1, 6000 - seconds * 20));
        }
        drops.add(dropped.getUniqueId());
        plugin.fx().particle("FLAME", location.clone().add(0.0D, 0.5D, 0.0D), 8, 0.3D);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[loot] 在 " + region.describe() + " 刷出 " + entry.label());
        }
        return true;
    }

    private Entry randomEntry() {
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Entry entry : pool) {
            total += entry.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (Entry entry : pool) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private void removeAll() {
        for (UUID uuid : new ArrayList<>(drops)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        drops.clear();
    }
}
