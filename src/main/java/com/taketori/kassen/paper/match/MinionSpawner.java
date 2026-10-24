package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 月人刷新器：按配置的种类与权重生成月人，并按波次投放精英。
 *
 * <p>要点：</p>
 * <ul>
 *   <li><b>多种类</b>：<code>minion.types</code> 里写实体名与权重（例如 <code>ZOMBIE: 3</code> /
 *       <code>SKELETON: 2</code>），每次刷新按权重随机抽一种；</li>
 *   <li><b>两类刷新区两条独立循环</b>：normal 标签区与 mixed 标签区各自拥有独立的计时器
 *       （<code>minion.normal.*</code> / <code>minion.mixed.*</code>，可分别启用与配间隔），
 *       互不共享计数与生命周期；mixed 循环还负责精英波（每 N 波出精英）；</li>
 *   <li><b>波次</b>：mixed 循环每刷新一批算一波；每 <code>minion.elite.every-waves</code> 波出一批精英
 *       （钻甲 + 药水 buff + 可配攻击力），并全服播报；</li>
 *   <li><b>上限</b>：普通月人与精英各有独立上限，达到就不再刷，避免拖垮服务器；</li>
 *   <li>关闭白天燃烧：僵尸与骷髅白天不会自己烧死；</li>
 *   <li>对局结束或插件卸载时会清掉场上剩余月人。</li>
 * </ul>
 */
public final class MinionSpawner {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 所属房间：阶段/规则/场地/播报全部按房间取，不再访问全局单局。 */
    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private final Set<UUID> minions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> elites = ConcurrentHashMap.newKeySet();
    /** normal 标签区的独立循环：只在这些区之间轮转刷普通月人。 */
    private BukkitTask normalTask;
    /** mixed 标签区的独立循环：普通月人 + 精英波，与 normal 循环互不影响。 */
    private BukkitTask mixedTask;
    /** mixed 循环波次：每刷新一批 +1，用于"每 N 波出精英"（记分板显示的就是它）。 */
    private int mixedWave;
    /** PVE 大波次：独立于上述两条循环的节奏（默认 5 波、约 1 分钟一波、每波 8 精英）。 */
    private BukkitTask bigWaveTask;
    private int bigWave;
    /** 轮转游标：normal 循环只按 normal 标签区顺序依次取区（均等分布）。 */
    private int normalRegionCursor;
    /** 轮转游标：mixed 循环与精英月人只按 mixed 标签区顺序依次取区（均等分布）。 */
    private int mixedRegionCursor;

    public MinionSpawner(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    /** 当前波次（mixed 循环计数，记分板显示）。 */
    public int wave() {
        return mixedWave;
    }

    /** 已经放过几个大波次（PVE）。 */
    public int bigWave() {
        return bigWave;
    }

    public void start() {
        stop();
        mixedWave = 0;
        normalRegionCursor = 0;
        mixedRegionCursor = 0;
        MatchRules rules = room.rules();

        // normal 区独立循环：只在 normal 标签区刷普通月人；关闭或没有该类型区域时整条循环不启动
        if (rules.minionNormalEnabled() && !room.arena().normalMinionRegionList().isEmpty()) {
            long normalInterval = Math.max(20L, rules.minionNormalIntervalSeconds() * 20L);
            normalTask = plugin.scheduler().runTimerTask(this::normalTick, normalInterval, normalInterval);
        }
        // mixed 区独立循环：普通月人 + 精英波，与 normal 循环互不影响
        if (rules.minionMixedEnabled() && !room.arena().mixedMinionRegionList().isEmpty()) {
            long mixedInterval = Math.max(20L, rules.minionMixedIntervalSeconds() * 20L);
            mixedTask = plugin.scheduler().runTimerTask(this::mixedTick, mixedInterval, mixedInterval);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room " + room.id() + "] 月人刷新启动：normal 循环 "
                    + (normalTask != null ? "每 " + rules.minionNormalIntervalSeconds() + " 秒" : "未启动")
                    + "，mixed 循环 "
                    + (mixedTask != null ? "每 " + rules.minionMixedIntervalSeconds() + " 秒" : "未启动")
                    + "，每批 " + rules.minionPerSpawn() + " 只，上限 " + rules.minionMaxAlive()
                    + "；精英每 " + plugin.minionTypes().elite().everyWaves() + " 波");
        }
        // 全部刷新区都是 normal 标签时，精英（精英波 / PVE 大波次）没有可用的刷新区
        if (room.arena().minionRegionCount() > 0 && room.arena().mixedMinionRegionCount() == 0) {
            plugin.getLogger().warning("[room " + room.id() + "] 所有月人刷新区标签都是 normal："
                    + "精英月人与 PVE 大波次不会刷新（把 arenas.yml 里对应刷新区的 kind 改为 mixed 后 /taketori reload）");
        }
        startBigWaves();
    }

    /**
     * PVE 大波次：每 {@code interval-seconds} 秒刷一波 {@code elites-per-wave} 个精英，
     * 一共 {@code count} 波。只在 PVE 模式且配置开启时生效。
     */
    private void startBigWaves() {
        if (!room.isPve()) {
            return;
        }
        PveSettings pve = plugin.pveSettings();
        if (!pve.bigWavesEnabled() || pve.bigWaveCount() <= 0) {
            return;
        }
        bigWave = 0;
        long delay = Math.max(1L, pve.bigWaveStartDelaySeconds() * 20L);
        long period = Math.max(100L, pve.bigWaveIntervalSeconds() * 20L);
        bigWaveTask = plugin.scheduler().runTimerTask(this::bigWaveTick, delay, period);
        room.broadcast("<dark_red>月人入侵：<white>共 " + pve.bigWaveCount() + " 个大波次</white>"
                + " <gray>（每波 " + pve.elitesPerWave() + " 名精英，间隔约 "
                + pve.bigWaveIntervalSeconds() + " 秒，难度 " + pve.difficultyDisplay() + "）");
    }

    private void bigWaveTick() {
        if (!room.isRunning() || !room.isPve()) {
            cancelBigWaves();
            return;
        }
        PveSettings pve = plugin.pveSettings();
        if (!pve.bigWavesEnabled() || bigWave >= pve.bigWaveCount()) {
            boolean finished = bigWave > 0;
            cancelBigWaves();
            if (finished && pve.announce()) {
                room.broadcast("<gold>全部 " + bigWave + " 个大波次已打完<gray>："
                        + "剩下的月人清完就安全了。");
            }
            return;
        }
        if (room.arena().minionRegionCount() == 0) {
            return;
        }

        // 每波都是"新的 8 个"：先清掉上一波残留的精英，避免越滚越多拖垮服务器
        removeElites();

        bigWave++;
        MinionTypes.Elite elite = plugin.minionTypes().elite();
        for (int i = 0; i < pve.elitesPerWave(); i++) {
            // 大波次精英同样只在 mixed 标签区之间轮转均分
            CuboidRegion region = nextMixedRegion();
            if (region != null && region.world() != null) {
                spawnElite(region, elite);
            }
        }
        if (pve.announce()) {
            room.broadcast("<dark_red>第 <white>" + bigWave + "<dark_red>/<white>"
                    + pve.bigWaveCount() + "<dark_red> 大波次：<bold>" + pve.elitesPerWave()
                    + " 名精英月人</bold>涌来！<gray>（精英强化 +" + pve.eliteBuffBonusFor(playerCount())
                    + " 级，注意集火）");
        }
        room.scoreboard().updateAll();
    }

    private void cancelBigWaves() {
        if (bigWaveTask != null) {
            bigWaveTask.cancel();
            bigWaveTask = null;
        }
    }

    /** 本房间参战玩家数（PVE 时所有人都在红队）。 */
    private int playerCount() {
        return Math.max(1, room.teamPlayers(TeamId.RED).size());
    }

    public void stop() {
        if (normalTask != null) {
            normalTask.cancel();
            normalTask = null;
        }
        if (mixedTask != null) {
            mixedTask.cancel();
            mixedTask = null;
        }
        cancelBigWaves();
        mixedWave = 0;
        bigWave = 0;
        removeAll();
    }

    /** 场上存活的月人数量（顺带清理已失效的引用）。 */
    public int aliveCount() {
        int alive = 0;
        for (UUID uuid : new ArrayList<>(minions)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                minions.remove(uuid);
                elites.remove(uuid);
                continue;
            }
            alive++;
        }
        return alive;
    }

    /** 场上存活的精英数量。 */
    public int eliteCount() {
        int alive = 0;
        for (UUID uuid : new ArrayList<>(elites)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                elites.remove(uuid);
                minions.remove(uuid);
                continue;
            }
            alive++;
        }
        return alive;
    }

    public boolean isMinion(Entity entity) {
        return entity != null && minions.contains(entity.getUniqueId());
    }

    /** 实体是否归本刷怪器（普通或精英月人，死亡归属路由用）。 */
    public boolean isTracked(Entity entity) {
        return entity != null
                && (minions.contains(entity.getUniqueId()) || elites.contains(entity.getUniqueId()));
    }

    /** 是不是精英月人（掉落与提示可以区分）。 */
    public boolean isElite(Entity entity) {
        return entity != null && elites.contains(entity.getUniqueId());
    }

    public void onMinionDeath(Entity entity) {
        if (entity != null) {
            minions.remove(entity.getUniqueId());
            elites.remove(entity.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- 刷新

    /**
     * normal 区循环：只从 normal 标签区轮转刷普通月人。
     * 与 mixed 循环没有共享的计数或游标——这条循环停了、慢了、间隔改了，都不影响 mixed 区。
     */
    private void normalTick() {
        if (!room.isRunning()) {
            return;
        }
        List<CuboidRegion> regions = room.arena().normalMinionRegionList();
        if (regions.isEmpty()) {
            return;
        }
        spawnRegularMinions(regions, () -> regions.get(Math.floorMod(normalRegionCursor++, regions.size())));
    }

    /**
     * mixed 区循环：从 mixed 标签区轮转刷普通月人，并用<b>自己的</b>波次计数驱动精英波。
     * 精英仍然只落在 mixed 区——与 normal 循环的节奏完全无关。
     */
    private void mixedTick() {
        if (!room.isRunning()) {
            return;
        }
        List<CuboidRegion> regions = room.arena().mixedMinionRegionList();
        if (regions.isEmpty()) {
            return;
        }
        mixedWave++;
        spawnRegularMinions(regions, () -> regions.get(Math.floorMod(mixedRegionCursor++, regions.size())));

        // 精英波：不受普通上限限制，只受自己的上限约束
        MinionTypes.Elite elite = plugin.minionTypes().elite();
        if (elite.isEliteWave(mixedWave)) {
            int slots = elite.maxAlive() - eliteCount();
            int count = Math.min(Math.max(0, slots), elite.count());
            for (int i = 0; i < count; i++) {
                CuboidRegion region = nextMixedRegion();
                if (region != null && region.world() != null) {
                    spawnElite(region, elite);
                }
            }
            if (count > 0) {
                room.broadcast("<dark_red>第 " + mixedWave + " 波：<bold>精英月人</bold>出现！"
                        + "</dark_red> <gray>（" + armorName(elite.armor()) + " + 药水强化，注意集火）");
            }
        }
        room.scoreboard().updateAll();
    }

    /** 按预算刷一批普通月人：上限对两条循环共用（场上普通月人总量不超标），取区由调用方给。 */
    private void spawnRegularMinions(List<CuboidRegion> regions, java.util.function.Supplier<CuboidRegion> nextRegion) {
        MatchRules rules = room.rules();
        int budget = rules.minionMaxAlive() - aliveCount();
        if (budget > 0) {
            int count = Math.min(budget, Math.max(1, rules.minionPerSpawn()));
            for (int i = 0; i < count; i++) {
                CuboidRegion region = nextRegion.get();
                if (region != null && region.world() != null) {
                    spawnNormal(region, rules);
                }
            }
        }
    }

    /** mixed 循环里的精英 / 普通月人选区：只在 mixed 标签区之间轮转取区。 */
    private CuboidRegion nextMixedRegion() {
        List<CuboidRegion> regions = room.arena().mixedMinionRegionList();
        if (regions.isEmpty()) {
            return null;
        }
        return regions.get(Math.floorMod(mixedRegionCursor++, regions.size()));
    }

    private void spawnNormal(CuboidRegion region, MatchRules rules) {
        Location location = region.randomLocation();
        if (location == null) {
            return;
        }
        MinionTypes.Kind kind = plugin.minionTypes().randomKind();
        Entity spawned = spawn(region, location, kind.type());
        if (!(spawned instanceof Monster monster)) {
            if (spawned != null) {
                spawned.remove();
            }
            return;
        }
        prepare(monster);
        monster.customName(MINI.deserialize("<red>月人</red>"));
        monster.setCustomNameVisible(true);
        applyHealth(monster, rules.minionHealth());
        if (rules.minionIronArmor()) {
            equip(monster, "IRON");
        }
        minions.add(monster.getUniqueId());
    }

    private void spawnElite(CuboidRegion region, MinionTypes.Elite elite) {
        Location location = region.randomLocation();
        if (location == null) {
            return;
        }
        Entity spawned = spawn(region, location, plugin.minionTypes().randomEliteType());
        if (!(spawned instanceof Monster monster)) {
            if (spawned != null) {
                spawned.remove();
            }
            return;
        }
        prepare(monster);
        monster.customName(MINI.deserialize(elite.display()));
        monster.setCustomNameVisible(true);
        applyHealth(monster, elite.health());
        equip(monster, elite.armor());
        applyAttack(monster, elite.attackDamage());
        // 精英强化：PVE 时按难度档 + 参战人数线性叠加（每多 1 人 +1 级，封顶）
        PveSettings pve = plugin.pveSettings();
        int bonus = room.isPve() ? pve.eliteBuffBonusFor(playerCount()) : 0;
        applyBuffs(monster, elite.buffs(), bonus);
        minions.add(monster.getUniqueId());
        elites.add(monster.getUniqueId());

        plugin.fx().particle("FLAME", location.add(0.0D, 1.0D, 0.0D), 30, 0.6D);
        plugin.fx().sound("ENTITY_GENERIC_EXPLODE", location, 0.7F, 1.4F);
    }

    private Entity spawn(CuboidRegion region, Location location, EntityType type) {
        World world = region.world();
        if (world == null) {
            return null;
        }
        try {
            return world.spawnEntity(location, type);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("生成月人失败（" + type + "）：" + throwable.getMessage());
            return null;
        }
    }

    /** 通用设置：不持久化、远离即清除、白天不燃烧。 */
    private void prepare(Monster monster) {
        monster.setPersistent(false);
        monster.setRemoveWhenFarAway(true);
        try {
            if (monster instanceof Zombie zombie) {
                zombie.setShouldBurnInDay(false);
            } else if (monster instanceof AbstractSkeleton skeleton) {
                skeleton.setShouldBurnInDay(false);
            }
        } catch (Throwable ignored) {
            // 个别版本没有这个方法：忽略即可（白天被烧只是体验问题）
        }
    }

    private void applyHealth(Monster monster, double health) {
        Attribute maxHealth = plugin.versions().attribute("max_health");
        if (maxHealth != null) {
            AttributeInstance instance = monster.getAttribute(maxHealth);
            if (instance != null) {
                instance.setBaseValue(health);
            }
        }
        monster.setHealth(Math.max(1.0D, Math.min(health, monster.getMaxHealth())));
    }

    private void applyAttack(Monster monster, double damage) {
        if (damage <= 0.0D) {
            return;
        }
        Attribute attack = plugin.versions().attribute("attack_damage");
        if (attack == null) {
            return;
        }
        AttributeInstance instance = monster.getAttribute(attack);
        if (instance != null) {
            instance.setBaseValue(damage);
        }
    }

    /** 给精英加药水增益；{@code bonus} 是难度档 + 人数带来的额外等级（线性叠加）。 */
    private void applyBuffs(Monster monster, List<MinionTypes.Buff> buffs, int bonus) {
        for (MinionTypes.Buff buff : buffs) {
            PotionEffectType type = plugin.versions().potionEffect(buff.typeName());
            if (type == null) {
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[match] 精英 buff 名字无法解析：" + buff.typeName());
                }
                continue;
            }
            int amplifier = Math.max(0, buff.amplifier() + bonus);
            monster.addPotionEffect(new PotionEffect(type, buff.durationTicks(), amplifier,
                    false, true, true));
        }
    }

    /** 清掉场上所有精英（大波次更替时用：每波都是新的 8 个）。 */
    private void removeElites() {
        for (UUID uuid : new ArrayList<>(elites)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
            minions.remove(uuid);
        }
        elites.clear();
    }

    /** 套装：IRON / DIAMOND / NETHERITE / NONE。 */
    private void equip(Monster monster, String armorTag) {
        String tag = armorTag == null ? "NONE" : armorTag.trim().toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(tag) || tag.isEmpty()) {
            return;
        }
        Material helmet;
        Material chest;
        Material legs;
        Material boots;
        switch (tag) {
            case "IRON" -> {
                helmet = Material.IRON_HELMET;
                chest = Material.IRON_CHESTPLATE;
                legs = Material.IRON_LEGGINGS;
                boots = Material.IRON_BOOTS;
            }
            case "DIAMOND" -> {
                helmet = Material.DIAMOND_HELMET;
                chest = Material.DIAMOND_CHESTPLATE;
                legs = Material.DIAMOND_LEGGINGS;
                boots = Material.DIAMOND_BOOTS;
            }
            case "NETHERITE" -> {
                helmet = Material.NETHERITE_HELMET;
                chest = Material.NETHERITE_CHESTPLATE;
                legs = Material.NETHERITE_LEGGINGS;
                boots = Material.NETHERITE_BOOTS;
            }
            default -> {
                plugin.getLogger().warning("minion 的 armor 取值不认识：" + armorTag + "（可用 IRON/DIAMOND/NETHERITE/NONE）");
                return;
            }
        }
        EntityEquipment equipment = monster.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setHelmet(new ItemStack(helmet));
        equipment.setChestplate(new ItemStack(chest));
        equipment.setLeggings(new ItemStack(legs));
        equipment.setBoots(new ItemStack(boots));
        // 不掉装备，避免刷装备
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
    }

    private String armorName(String armorTag) {
        String tag = armorTag == null ? "NONE" : armorTag.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (tag) {
            case "IRON" -> "铁甲";
            case "DIAMOND" -> "钻甲";
            case "NETHERITE" -> "下界合金甲";
            default -> "无甲";
        };
    }

    private void removeAll() {
        for (UUID uuid : new ArrayList<>(minions)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        minions.clear();
        elites.clear();
    }
}
