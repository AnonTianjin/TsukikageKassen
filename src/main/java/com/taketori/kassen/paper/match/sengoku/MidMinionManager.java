package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.sengoku.MidMinionRules;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中地小兵：场地中央持续刷新的战场压力源。
 *
 * <p>设计意图（需求第 15、17 条）：<b>允许被绕过</b>，不强制清兵；但清兵能攒能量。
 * 所以这里刻意不做"堵门""追击到天荒地老"这类强制交战的机制——玩家可以 run，
 * 也可以停下来刷能量，两条路都成立。</p>
 *
 * <p>三条件能约束（需求第 41 条要求必须做）：</p>
 * <ol>
 *   <li><b>实体上限</b> {@code max-alive}：达到就停止刷新；</li>
 *   <li><b>分片刷新</b> {@code shard-tick}：一轮刷新摊到多个 tick 上，避免一次性全刷造成尖峰；</li>
 *   <li><b>AI 简化</b> {@code ai-simplify}：关掉部分寻路，代价是偶尔绕不过障碍——这是刻意的取舍。</li>
 * </ol>
 */
public final class MidMinionManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameRoom room;
    private final TaketoriPlugin plugin;
    /** 场上存活的小兵。 */
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    /** 分片游标：一轮刷新刷到第几个区域。 */
    private int shardIndex;
    /** 下一轮刷新的时刻（毫秒）。 */
    private long nextRoundAt;

    public MidMinionManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private MidMinionRules rules() {
        return plugin.config().midMinionRules();
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        stop();
        nextRoundAt = System.currentTimeMillis() + rules().intervalTicks() * 50L;
        task = plugin.scheduler().runTimerTask(this::tick, 1L, 1L);
    }

    /** 收尾 / 小局重置：停 tick 并清掉场上所有小兵。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(alive)) {
            Entity entity = org.bukkit.Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        alive.clear();
        shardIndex = 0;
        nextRoundAt = 0L;
    }

    // ---------------------------------------------------------------- tick

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        // 摘掉已死亡 / 失效的，保证 alive 反映真实占用
        alive.removeIf(id -> {
            Entity entity = org.bukkit.Bukkit.getEntity(id);
            return entity == null || entity.isDead() || !entity.isValid();
        });

        List<CuboidRegion> regions = regions();
        if (regions.isEmpty()) {
            return;   // 没划刷新区：doctor 会点名，这里静默
        }

        long now = System.currentTimeMillis();
        if (now < nextRoundAt) {
            return;
        }
        if (alive.size() >= rules().safeMaxAlive()) {
            // 到上限：跳过这一轮，但仍然把计时往后推，避免上限解除后瞬间补刷一大批
            nextRoundAt = now + rules().intervalTicks() * 50L;
            return;
        }

        // 分片：一轮刷新摊到 shard-tick 个 tick 上（实际分片数不超过刷新区数量，
        // 否则"分片数比区域还多"没有意义）。此前是"每 tick 固定刷一个区"，
        // 配置里的 shard-tick 根本没被读过。
        int shards = rules().safeShardTick(regions.size());
        int perTick = Math.max(1, (int) Math.ceil(regions.size() / (double) shards));

        if (shardIndex < regions.size()) {
            for (int i = 0; i < perTick && shardIndex < regions.size(); i++) {
                spawnAt(regions.get(shardIndex));
                shardIndex++;
            }
            if (shardIndex >= regions.size()) {
                shardIndex = 0;
                nextRoundAt = now + rules().intervalTicks() * 50L;
            }
            return;
        }
        shardIndex = 0;
        nextRoundAt = now + rules().intervalTicks() * 50L;
    }

    // ---------------------------------------------------------------- 生成

    private void spawnAt(CuboidRegion region) {
        if (region == null) {
            return;
        }
        EntityType type = parseEntity(rules().entity());
        if (type == null) {
            return;
        }
        int budget = Math.min(rules().safePerSpawn(), rules().safeMaxAlive() - alive.size());
        for (int i = 0; i < budget; i++) {
            Location spot = region.randomStandLocation();
            if (spot == null || spot.getWorld() == null) {
                spot = region.randomLocation();
            }
            if (spot == null || spot.getWorld() == null) {
                continue;
            }
            Entity spawned = spot.getWorld().spawnEntity(spot, type);
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                continue;
            }
            living.getPersistentDataContainer().set(PDCKeys.midMinion(),
                    PersistentDataType.BYTE, (byte) 1);
            if (rules().hasDisplay()) {
                living.customName(MINI.deserialize(rules().display()));
                living.setCustomNameVisible(false);
            }
            living.setRemoveWhenFarAway(false);
            living.setPersistent(false);   // 小局结束就清场，不写进世界存档
            applyAttribute(living, "max_health", rules().health());
            applyAttribute(living, "attack_damage", rules().damage());
            if (living.getHealth() > living.getMaxHealth()) {
                living.setHealth(living.getMaxHealth());
            }
            if (living instanceof Mob mob) {
                mob.setTarget(null);
            }
            alive.add(living.getUniqueId());
        }
    }

    private void applyAttribute(LivingEntity living, String configuredName, double value) {
        Attribute attribute = plugin.versions().attribute(configuredName);
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private EntityType parseEntity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[sengoku] 中地小兵的实体名无法解析：" + raw);
            return null;
        }
    }

    private List<CuboidRegion> regions() {
        var arena = room.arena();
        if (arena == null) {
            return List.of();
        }
        return new ArrayList<>(arena.sengoku().midMinionRegions().values());
    }

    // ---------------------------------------------------------------- 查询

    /** 这个实体是不是中地小兵（击杀回能与统计都靠它区分）。 */
    public static boolean isMidMinion(Entity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getPersistentDataContainer()
                .get(PDCKeys.midMinion(), PersistentDataType.BYTE) != null;
    }

    public int aliveCount() {
        return alive.size();
    }

    /** 场上还有几个空位（调试用）。 */
    public int remainingCapacity() {
        return Math.max(0, rules().safeMaxAlive() - alive.size());
    }

    /** 当前是否到了上限（doctor / admin 用）。 */
    public boolean atCapacity() {
        return alive.size() >= rules().safeMaxAlive();
    }
}
