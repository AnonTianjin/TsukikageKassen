package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.skill.SkillTargets;
import com.taketori.kassen.paper.skill.impl.ProjectileSkill;
import com.taketori.kassen.paper.state.CombatStates;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗结算层。
 *
 * <p>职责：近战改写、易伤标记、镜面反射、弹体原版伤害取消、防御反弹、摔落免疫，
 * 以及弓的特殊射击与三连射。</p>
 *
 * <p><b>弓的两个机制是解耦的</b>（踩过的坑）：</p>
 * <ul>
 *   <li>特殊射击：需要 shift-right 槽绑定了技能<b>且</b>开关已开；</li>
 *   <li>三连射：只取决于当前模式是不是 VOLLEY，<b>与 shift-right 是否绑定无关</b>。
 *       早先写成"shift-right 未绑定就 return"，会把三连射一起吃掉。</li>
 * </ul>
 */
public final class CombatListener implements Listener {

    private static final int VOLLEY_EXTRA_ARROWS = 2;

    private final TaketoriPlugin plugin;
    /** 无箭射击限频：两次补射之间的最小间隔（毫秒）。 */
    private static final long NO_ARROW_SHOT_INTERVAL_MILLIS = 250L;
    /** 镜面弹回的速度上限（约等于满蓄弓速）：防止双镜面往复对弹时速度连乘失控。 */
    private static final double MAX_REFLECT_SPEED = 3.0D;
    private final java.util.Map<java.util.UUID, Long> lastNoArrowShot = new java.util.HashMap<>();

    public CombatListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        debug(String.format("%s 近战命中 %s（原版伤害 %.2f）", player.getName(), event.getEntityType(), event.getDamage()));

        if (plugin.isInternalDamage(player.getUniqueId())) {
            debug("└ 这是插件技能自己造成的伤害 → 放行，不改写");
            return;
        }

        if (event.getEntity() instanceof Player victim) {
            reflectMelee(victim, player, event);
        }

        // 2.0：近战武器的左键一律走原版普攻，不再取消伤害、也不再派发技能。
        // 被动（易伤 / 破甲 / 减速 / 吸血 / 附加伤害）由 PassiveListener 在 MONITOR 阶段结算，
        // 因此这里只保留"这是插件武器"的诊断日志。
        ItemFactory.Identity identity = plugin.items().read(player.getInventory().getItemInMainHand());
        if (identity == null) {
            debug("└ 主手不是插件武器 → 保留原版近战");
            return;
        }
        debug("└ 原版近战照常结算（被动由 PassiveListener 附加）weapon=" + identity.weaponId());
    }

    /**
     * 易伤标记 + 破甲：被标记 / 被破甲的目标，受到的所有来源伤害都提高。
     *
     * <p>两个乘区<b>独立相乘</b>：{@code ×(1 + markBonus) × (1 + pierce)}。写成一个监听器而不是
     * 两个同优先级监听器，是为了让结算顺序确定（同优先级的执行顺序取决于注册顺序，不可靠）。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMarkedDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        double bonus = plugin.states().markBonus(victim.getUniqueId());
        // 破甲（目标挨打更疼）与进攻窗口（攻击者打人更疼）取 max 而不是连乘：
        // 否则"自己开了壁垒 + 对面正好被破甲"会叠出双倍穿透。
        double pierce = plugin.states().armorPierce(victim.getUniqueId());
        Player attacker = attackerOf(event);
        if (attacker != null) {
            pierce = Math.max(pierce, plugin.states().offensePierce(attacker.getUniqueId()));
        }
        if (bonus <= 0.0D && pierce <= 0.0D) {
            return;
        }
        double multiplier = (1.0D + bonus) * (1.0D + pierce);
        event.setDamage(event.getDamage() * multiplier);
        debug(String.format("%s 易伤 +%.0f%% / 破甲 +%.0f%% → 本次伤害 ×%.2f",
                victim.getName(), bonus * 100.0D, pierce * 100.0D, multiplier));
    }

    /** 从伤害事件里取出"攻击者玩家"（直接近战或弹体射手），取不到返回 null。 */
    private Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * 召唤物护栏：召唤者与队友不能伤害自己人的召唤物。
     *
     * <p>击杀召唤物也<b>不计分</b>（那个在 {@code MatchListener} 里拦），否则会出现"刷狗刷分"。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSummonedDamage(EntityDamageByEntityEvent event) {
        String ownerId = event.getEntity().getPersistentDataContainer()
                .get(PDCKeys.summonedOwner(), PersistentDataType.STRING);
        if (ownerId == null) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) {
            return;
        }
        if (attacker.getUniqueId().toString().equals(ownerId)) {
            event.setCancelled(true);
            return;
        }
        Player owner;
        try {
            owner = Bukkit.getPlayer(java.util.UUID.fromString(ownerId));
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (owner != null && SkillTargets.isProtectedTeammate(plugin, owner, attacker)) {
            event.setCancelled(true);
        }
    }

    /** 镜面反射：把打到你的飞行物弹回去。对近战无效（设计取舍）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onReflectProjectile(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        CombatStates.Reflection reflection = plugin.states().reflection(victim.getUniqueId());
        if (reflection == null) {
            return;
        }
        event.setCancelled(true);
        // 弹回速度封顶：两个镜面玩家之间往复对弹时速度会按倍率连乘，不封顶会越弹越快
        Vector bounced = projectile.getVelocity().multiply(-reflection.speedMultiplier());
        if (bounced.length() > MAX_REFLECT_SPEED) {
            bounced.normalize().multiply(MAX_REFLECT_SPEED);
        }
        projectile.setVelocity(bounced);
        projectile.setShooter(victim);
        plugin.fx().particle("END_ROD", victim.getLocation().add(0.0D, 1.0D, 0.0D), 20, 0.4D);
        plugin.fx().sound("BLOCK_GLASS_BREAK", victim, 0.8F, 1.8F);
        debug(victim.getName() + " 的镜面把 " + projectile.getType() + " 弹了回去");
    }

    /**
     * 镜面反制（近战）：反射窗口内被近战打中时，按 {@code reflect-ratio} 把伤害反弹给攻击者。
     *
     * <p>没有这一条时，月镜的镜面模式一被贴脸就完全失效（它只反飞行物）。反弹用内部伤害
     * 标记包住，避免双方都开镜面时无限对弹。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReflectMelee(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof Player attacker)) {
            return;   // 只处理"玩家近战打玩家"；飞行物走 onReflectProjectile
        }
        // 防重入：victim 正在被"内部反射伤害"命中时（上一帧反射调用了 victim.damage 触发本事件），
        // 不得再反射回去。否则双方都开反射窗口时，A↔B 的反射伤害会同步嵌套递归，直至主线程栈溢出。
        // 标记对象取 victim（反射持有者 = 新反射伤害事件的 damager），与 onMeleeHit 的
        // isInternalDamage(damager) 判定对齐，反射伤害事件因此不会被 onMeleeHit 误取消或误派 LEFT 技能。
        if (plugin.isInternalDamage(victim.getUniqueId())) {
            return;
        }
        CombatStates.Reflection reflection = plugin.states().reflection(victim.getUniqueId());
        if (reflection == null || reflection.reflectRatio() <= 0.0D) {
            return;
        }
        double back = event.getDamage() * reflection.reflectRatio();
        if (back <= 0.0D) {
            return;
        }
        plugin.markInternalDamage(victim.getUniqueId());
        try {
            attacker.damage(back, victim);
        } finally {
            plugin.unmarkInternalDamage(victim.getUniqueId());
        }
        plugin.fx().particle("ENCHANTED_HIT", attacker.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.3D);
        debug(String.format("%s 的镜面把 %.1f 点近战伤害反弹给 %s",
                victim.getName(), back, attacker.getName()));
    }

    /** 插件弹体的原版撞击/爆炸伤害一律取消，只由命中监听器结算一次。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSkillProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        ProjectileSkill skill = plugin.projectileSkill();
        if (skill != null && skill.isSkillProjectile(projectile)) {
            event.setCancelled(true);
            debug("取消插件弹体（" + projectile.getType() + "）的原版伤害，改由命中结算");
        }
    }

    /** 推进技能的落地保护：命中摔落伤害时直接免掉（而不是给缓降药水）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.states().isFallImmune(player.getUniqueId())) {
            event.setCancelled(true);
            debug(player.getName() + " 的摔落伤害被免伤窗口取消");
            return;
        }
        // 正式对局（玻璃笼 + 战斗中）摔落伤害一律取消；对局外玩家保持原版
        if (plugin.inRunningMatch(player)) {
            event.setCancelled(true);
            debug(player.getName() + " 的摔落伤害被对局豁免取消");
        }
    }

    private void reflectMelee(Player victim, Player attacker, EntityDamageByEntityEvent event) {
        CombatStates.Defense defense = plugin.states().defense(victim.getUniqueId());
        if (defense == null || defense.reflectRatio() <= 0.0D) {
            return;
        }
        double reflected = event.getDamage() * Math.min(1.0D, defense.reflectRatio());
        if (reflected <= 0.0D) {
            return;
        }
        debug(String.format("%s 处于防御窗口（%s）→ 反弹 %.2f 给 %s",
                victim.getName(), defense.source(), reflected, attacker.getName()));
        plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.3D);
        plugin.fx().sound("BLOCK_ANVIL_LAND", victim, 0.6F, 1.6F);
        plugin.markInternalDamage(victim.getUniqueId());
        try {
            attacker.damage(reflected, victim);
        } finally {
            plugin.unmarkInternalDamage(victim.getUniqueId());
        }
    }

    /** 弓：特殊射击强化（含易伤标记与命中范围伤害）+ VOLLEY 模式连发。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        WeaponDef weapon = pluginWeaponOf(event.getBow());
        if (weapon == null) {
            return;
        }
        tagProjectile(event.getProjectile(), weapon);
    }

    /**
     * 没有箭也能射（夕月的弓靠它实现「无箭射击」）。
     *
     * <p>原版在背包里没有箭时<b>根本不会发射</b>——连 {@link EntityShootBowEvent} 都不触发，
     * 所以只能在右键这一刻接管：手持插件弓 + 手里没箭 → 手动放一支箭，并走与普通射击
     * 完全相同的标记与三连射逻辑；有箭时什么都不做，照旧交给原版。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBowWithoutArrow(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;   // 创造模式原版就能射，不必我们操心
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BOW) {
            return;
        }
        WeaponDef weapon = pluginWeaponOf(hand);
        if (weapon == null) {
            return;   // 只给插件弓这个待遇，普通弓照原版
        }
        if (hasAnyArrow(player)) {
            return;   // 有箭：走原版 EntityShootBowEvent
        }
        // 限频：原版拉弓有蓄力时间，连点右键不该变成满威力机关枪
        long now = System.currentTimeMillis();
        Long last = lastNoArrowShot.get(player.getUniqueId());
        if (last != null && now - last < NO_ARROW_SHOT_INTERVAL_MILLIS) {
            return;
        }
        lastNoArrowShot.put(player.getUniqueId(), now);
        // 按满蓄力箭的速度补射（原版满蓄约 3.0），方向取视线
        Arrow arrow = player.launchProjectile(Arrow.class,
                player.getEyeLocation().getDirection().multiply(3.0D));
        tagProjectile(arrow, weapon);
        debug("└ 无箭射击：背包里没有箭，按插件弓规则补射一支");
    }

    /** 读出手里这把弓绑定的插件武器；不是插件武器返回 null。 */
    private WeaponDef pluginWeaponOf(ItemStack stack) {
        ItemFactory.Identity identity = plugin.items().read(stack);
        if (identity == null) {
            return null;
        }
        return plugin.config().weapons().get(identity.weaponId());
    }

    /** 背包（含副手）里有没有任何可用的箭。 */
    private boolean hasAnyArrow(Player player) {
        var inventory = player.getInventory();
        return inventory.contains(Material.ARROW)
                || inventory.contains(Material.SPECTRAL_ARROW)
                || inventory.contains(Material.TIPPED_ARROW);
    }

    /**
     * 把来源武器 id 与命中减益名单写到弹体上。
     *
     * <p>来源 id 是必要的：三叉戟脱手后主手为空，命中时读不到物品，被动就无从取起。</p>
     */
    private void tagProjectile(Entity projectile, WeaponDef weapon) {
        if (projectile == null || weapon == null) {
            return;
        }
        projectile.getPersistentDataContainer()
                .set(PDCKeys.projWeaponId(), PersistentDataType.STRING, weapon.id());
        tagArrowDebuffs(projectile, weapon);
    }

    /** 被强化的箭矢命中：按倍率结算伤害、打易伤标记、并对周围造成范围伤害。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) {
            return;
        }
        var pdc = arrow.getPersistentDataContainer();

        Double multiplier = pdc.get(PDCKeys.arrowMultiplier(), PersistentDataType.DOUBLE);
        double finalDamage = event.getDamage();
        if (multiplier != null && multiplier > 1.0D) {
            finalDamage = event.getDamage() * multiplier;
            event.setDamage(finalDamage);
            debug(String.format("强化箭命中 → 伤害 ×%.2f", multiplier));
            plugin.fx().particle("CRIT", event.getEntity().getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.25D);
        }
        // 伤害数字（A1）：强化箭/普通箭命中都汇报给射手
        if (arrow.getShooter() instanceof Player owner
                && event.getEntity() instanceof LivingEntity victim) {
            plugin.damageNumbers().hit(owner, victim, finalDamage);
        }

        // 命中范围伤害：让特殊射击不只是"数字变大"，而是能打到一片
        Double hitRadius = pdc.get(PDCKeys.arrowHitRadius(), PersistentDataType.DOUBLE);
        if (hitRadius != null && hitRadius > 0.0D) {
            Double ratio = pdc.get(PDCKeys.arrowHitRatio(), PersistentDataType.DOUBLE);
            double splash = finalDamage * (ratio == null ? 0.3D : ratio);
            var center = event.getEntity().getLocation();
            Entity shooter = arrow.getShooter() instanceof Entity entity ? entity : null;
            int hit = 0;
            for (Entity nearby : center.getWorld().getNearbyEntities(center, hitRadius, hitRadius, hitRadius)) {
                if (!(nearby instanceof LivingEntity living) || nearby.equals(event.getEntity()) || living.isDead()) {
                    continue;
                }
                if (shooter != null && shooter.equals(nearby)) {
                    continue;
                }
                // 受保护的队友与召唤物整个跳过：伤害会被事件层取消，但范围伤害本身不该结算
                if (shooter instanceof Player owner && SkillTargets.isFilteredTarget(plugin, owner, living)) {
                    continue;
                }
                if (shooter instanceof Player owner) {
                    plugin.markInternalDamage(owner.getUniqueId());
                }
                try {
                    if (shooter != null) {
                        living.damage(splash, shooter);
                    } else {
                        living.damage(splash);
                    }
                    hit++;
                } finally {
                    if (shooter instanceof Player owner) {
                        plugin.unmarkInternalDamage(owner.getUniqueId());
                    }
                }
            }
            plugin.fx().particle("CRIT", center, 20, hitRadius * 0.4D);
            plugin.fx().sound("ENTITY_GENERIC_EXPLODE", center, 0.7F, 1.4F);
            debug(String.format("特殊射击命中范围：半径 %.1f 内额外命中 %d 个目标（每个 %.2f 伤害）",
                    hitRadius, hit, splash));
        }

        Double markBonus = pdc.get(PDCKeys.arrowMarkBonus(), PersistentDataType.DOUBLE);
        if (markBonus != null && markBonus > 0.0D && event.getEntity() instanceof LivingEntity victim) {
            Integer ticks = pdc.get(PDCKeys.arrowMarkTicks(), PersistentDataType.INTEGER);
            plugin.states().mark(victim.getUniqueId(), ticks == null ? 100 : ticks, markBonus);
            debug(String.format("标记 %s：易伤 +%.0f%%", victim.getName(), markBonus * 100.0D));
            plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.5D, 0.0D), 14, 0.3D);
        }

        // 命中附加负面效果（夕月的箭：每次命中随机抽一种减益）
        applyArrowDebuffs(pdc, arrow, event.getEntity());
    }

    /** 把武器的 hit-effects 写进箭矢 PDC（命中时才抽签决定具体是哪种减益）。 */
    private void tagArrowDebuffs(Entity projectile, WeaponDef weapon) {
        if (projectile == null || weapon == null) {
            return;
        }
        List<String> pool = weapon.hitEffectList("arrow-debuffs");
        if (pool.isEmpty()) {
            return;
        }
        double chance = weapon.hitEffectDbl("arrow-debuff-chance", 1.0D);
        if (chance <= 0.0D) {
            return;
        }
        PersistentDataContainer pdc = projectile.getPersistentDataContainer();
        pdc.set(PDCKeys.arrowDebuffs(), PersistentDataType.STRING, String.join(",", pool));
        pdc.set(PDCKeys.arrowDebuffTicks(), PersistentDataType.INTEGER,
                Math.max(20, weapon.hitEffectInt("arrow-debuff-ticks", 80)));
        pdc.set(PDCKeys.arrowDebuffAmplifier(), PersistentDataType.INTEGER,
                Math.max(0, weapon.hitEffectInt("arrow-debuff-amplifier", 0)));
        pdc.set(PDCKeys.arrowDebuffChance(), PersistentDataType.DOUBLE, Math.min(1.0D, chance));
        debug("箭矢已带上附加效果名单：" + String.join("/", pool) + "（概率 " + chance + "）");
    }

    /**
     * 命中时从名单里随机抽一种负面效果施加。
     *
     * <p>队友免疫：命中同队玩家时不挂效果（否则夕月会不停坑队友）。
     * 效果名解析失败只记 debug 日志、不抛异常——玩家不该因为配置里写错一个药水名就看到报错。</p>
     */
    private void applyArrowDebuffs(PersistentDataContainer pdc, Arrow arrow, Entity hitEntity) {
        String list = pdc.get(PDCKeys.arrowDebuffs(), PersistentDataType.STRING);
        if (list == null || list.isBlank() || !(hitEntity instanceof LivingEntity victim) || victim.isDead()) {
            return;
        }
        Entity shooter = arrow.getShooter() instanceof Entity entity ? entity : null;
        if (victim instanceof Player other && shooter instanceof Player owner && !other.equals(owner)) {
            // 多房间：两人必须在同一房间，队友才免疫减益
            var victimRoom = plugin.rooms().roomOf(other);
            if (victimRoom != null && victimRoom == plugin.rooms().roomOf(owner)) {
                var shooterTeam = victimRoom.teamOf(owner.getUniqueId());
                var victimTeam = victimRoom.teamOf(other.getUniqueId());
                if (shooterTeam != null && shooterTeam == victimTeam) {
                    return;
                }
            }
        }
        Double chance = pdc.get(PDCKeys.arrowDebuffChance(), PersistentDataType.DOUBLE);
        if (chance != null && chance < 1.0D && ThreadLocalRandom.current().nextDouble() > chance) {
            debug("箭矢附加效果未触发（概率 " + chance + "）");
            return;
        }
        String[] pool = list.split(",");
        String picked = pool[ThreadLocalRandom.current().nextInt(pool.length)].trim();
        PotionEffectType type = plugin.versions().potionEffect(picked);
        if (type == null) {
            debug("箭矢附加效果名解析失败：" + picked + "（跳过；可用 /taketori doctor 检查名字解析）");
            return;
        }
        Integer ticks = pdc.get(PDCKeys.arrowDebuffTicks(), PersistentDataType.INTEGER);
        Integer amplifier = pdc.get(PDCKeys.arrowDebuffAmplifier(), PersistentDataType.INTEGER);
        int duration = ticks == null ? 80 : ticks;
        int level = amplifier == null ? 0 : amplifier;
        victim.addPotionEffect(new PotionEffect(type, duration, level, false, true, true));
        plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.2D, 0.0D), 10, 0.3D);
        debug("箭矢附加效果生效：" + picked + "（" + duration + " tick，等级 " + (level + 1) + "）");
    }

    private Vector rotateAroundY(Vector vector, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vector(
                vector.getX() * cos - vector.getZ() * sin,
                vector.getY(),
                vector.getX() * sin + vector.getZ() * cos);
    }

    private void debug(String message) {
        if (plugin.config().debug()) {
            plugin.getLogger().info("[combat] " + message);
        }
    }

    /** 玩家退出时清掉无箭射击的限频表，避免 UUID 泄漏（与 InputListener 的 onQuit 清理口径一致）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNoArrowShot.remove(event.getPlayer().getUniqueId());
    }
}
