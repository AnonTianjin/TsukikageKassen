package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.passive.PassiveEffects;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 被动的<b>命中触发</b>：近战与箭矢/投掷命中都从这里进 {@link PassiveEffects}。
 *
 * <p>挂在 {@code MONITOR} 且<b>从不取消事件</b>：原版伤害已经结算完，这里只加附加效果，
 * 所以近战武器的左键普攻能原样保留。</p>
 *
 * <p>投掷物在发射时把武器 id 写进 PDC（{@link #onLaunch}）：三叉戟脱手后主手是空的，
 * 命中时再读物品已经读不到，只能靠发射瞬间的快照。</p>
 */
public final class PassiveListener implements Listener {

    private final TaketoriPlugin plugin;
    private final PassiveEffects effects;

    public PassiveListener(TaketoriPlugin plugin, PassiveEffects effects) {
        this.plugin = plugin;
        this.effects = effects;
    }

    /** 三叉戟投掷：把来源武器记到弹体上。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }
        if (!(trident.getShooter() instanceof Player shooter)) {
            return;
        }
        ItemStack stack = trident.getItemStack();
        ItemFactory.Identity identity = plugin.items().read(stack);
        if (identity == null) {
            return;
        }
        trident.getPersistentDataContainer().set(PDCKeys.projWeaponId(),
                PersistentDataType.STRING, identity.weaponId());
        if (plugin.config().debug()) {
            plugin.getLogger().info("[passive] " + shooter.getName() + " 投掷 " + identity.weaponId()
                    + " → 已记录来源武器");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Player attacker) {
            if (attacker.equals(victim) || plugin.isInternalDamage(attacker.getUniqueId())) {
                return;
            }
            WeaponDef weapon = pluginWeaponOf(attacker.getInventory().getItemInMainHand());
            if (weapon == null) {
                return;
            }
            effects.melee(attacker, weapon, victim, event.getFinalDamage());
            return;
        }
        if (!(damager instanceof Projectile projectile)) {
            return;
        }
        if (!(projectile.getShooter() instanceof Player attacker)) {
            return;
        }
        if (attacker.equals(victim) || plugin.isInternalDamage(attacker.getUniqueId())) {
            return;
        }
        WeaponDef weapon = weaponOfProjectile(projectile, attacker);
        if (weapon == null) {
            return;
        }
        effects.projectile(attacker, weapon, victim, event.getFinalDamage());
    }

    /** 优先用发射瞬间记下的武器 id；没有就退回主手（例如箭还插在身上时又换回弓）。 */
    private WeaponDef weaponOfProjectile(Projectile projectile, Player attacker) {
        String weaponId = projectile.getPersistentDataContainer()
                .get(PDCKeys.projWeaponId(), PersistentDataType.STRING);
        if (weaponId != null && !weaponId.isBlank()) {
            WeaponDef weapon = plugin.config().weapons().get(weaponId);
            if (weapon != null) {
                return weapon;
            }
        }
        return pluginWeaponOf(attacker.getInventory().getItemInMainHand());
    }

    private WeaponDef pluginWeaponOf(ItemStack stack) {
        ItemFactory.Identity identity = plugin.items().read(stack);
        if (identity == null) {
            return null;
        }
        return plugin.config().weapons().get(identity.weaponId());
    }
}
