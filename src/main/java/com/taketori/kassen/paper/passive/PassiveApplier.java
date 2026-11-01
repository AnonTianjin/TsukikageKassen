package com.taketori.kassen.paper.passive;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.skill.PassiveDef;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被动的<b>常驻属性</b>施加与清理。
 *
 * <p>只在「换手 / 进服 / 退服 / 副手交换 / 角色解绑」这几个时刻刷新一次，<b>不做 tick 扫描</b>。
 * 每次刷新都先清掉本插件自己加的东西，再按当前主手武器重新施加，因此：</p>
 * <ul>
 *   <li>换武器不会叠 buff；</li>
 *   <li>记录在案的效果可逆，不会和其它插件的同名药水互相污染。</li>
 * </ul>
 *
 * <p>命中类效果（易伤 / 破甲 / 减速 / 吸血）在 {@link PassiveEffects} 里结算。</p>
 */
public final class PassiveApplier implements Listener {

    private final TaketoriPlugin plugin;
    private final NamespacedKey speedKey;
    /** 本插件给每个玩家加过的药水效果类型（清理时只删这些）。 */
    private final Map<UUID, Set<PotionEffectType>> applied = new ConcurrentHashMap<>();

    public PassiveApplier(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, "passive_speed");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
        applied.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        // 交换在本 tick 末才真正生效，延后 1 tick 再按最终主手刷新
        plugin.scheduler().runLater(() -> {
            if (event.getPlayer().isOnline()) {
                refresh(event.getPlayer());
            }
        }, 1L);
    }

    /** 按当前主手武器重新施加常驻属性；没有武器就等于纯清理。 */
    public void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        clear(player);
        ItemFactory.Identity identity = plugin.items().read(player.getInventory().getItemInMainHand());
        if (identity == null) {
            return;
        }
        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null || !weapon.hasPassive()) {
            return;
        }
        PassiveDef passive = weapon.passive();

        Attribute speedAttribute = plugin.versions().attribute("movement_speed");
        if (speedAttribute != null && passive.speed() > 0.0D) {
            AttributeInstance instance = player.getAttribute(speedAttribute);
            if (instance != null) {
                instance.addModifier(new AttributeModifier(speedKey, passive.speed(),
                        AttributeModifier.Operation.ADD_SCALAR));
            }
        }
        if (passive.resistance() > 0) {
            applyPotion(player, "resistance", passive.resistance() - 1);
        }
        if (passive.jump() > 0) {
            applyPotion(player, "jump_boost", passive.jump() - 1);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[passive] " + player.getName() + " 常驻属性刷新："
                    + passive.display() + "（移速 +" + Math.round(passive.speed() * 100.0D) + "%）");
        }
    }

    /** 清掉本插件施加的常驻属性与药水。 */
    public void clear(Player player) {
        if (player == null) {
            return;
        }
        Attribute speedAttribute = plugin.versions().attribute("movement_speed");
        if (speedAttribute != null) {
            AttributeInstance instance = player.getAttribute(speedAttribute);
            if (instance != null) {
                List<AttributeModifier> toRemove = new ArrayList<>();
                for (AttributeModifier modifier : instance.getModifiers()) {
                    if (speedKey.equals(modifier.getKey())) {
                        toRemove.add(modifier);
                    }
                }
                for (AttributeModifier modifier : toRemove) {
                    instance.removeModifier(modifier);
                }
            }
        }
        Set<PotionEffectType> effects = applied.remove(player.getUniqueId());
        if (effects != null) {
            for (PotionEffectType type : effects) {
                player.removePotionEffect(type);
            }
        }
    }

    private void applyPotion(Player player, String name, int amplifier) {
        PotionEffectType type = plugin.versions().potionEffect(name);
        if (type == null) {
            return;
        }
        player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION,
                Math.max(0, amplifier), false, false, false));
        applied.computeIfAbsent(player.getUniqueId(), key -> ConcurrentHashMap.newKeySet()).add(type);
    }
}
