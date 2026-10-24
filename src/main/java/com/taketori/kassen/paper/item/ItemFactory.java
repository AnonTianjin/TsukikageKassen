package com.taketori.kassen.paper.item;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.version.VersionAdapter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 武器物品工厂：把 {@link WeaponDef} 变成带 PDC 身份的原版物品，或从物品反读身份。
 *
 * <p>身份只认 PDC，不认显示名与 Lore（设计稿「识别原则」）。</p>
 *
 * <p><b>属性语义</b>：weapons.yml 里写的 attack-damage / attack-speed 是
 * <b>最终值</b>。实现方式是读该 Material 的原版默认属性，算出差值再用 ADD_NUMBER 追加，
 * 所以你写 7.0，玩家面板上就是 7.0，不需要自己心算原版基础值。</p>
 */
public final class ItemFactory {

    /** 从物品上读出的身份信息。 */
    public record Identity(String characterId, String weaponId, String mode, String instanceId, String ownerId) {

        public boolean soulbound() {
            return ownerId != null && !ownerId.isBlank();
        }
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final VersionAdapter versions;

    public ItemFactory(TaketoriPlugin plugin, VersionAdapter versions) {
        this.plugin = plugin;
        this.versions = versions;
    }

    /** 生成一把武器物品；owner 为空表示不绑定。 */
    public ItemStack create(WeaponDef def, Player owner) {
        Material material = Material.matchMaterial(def.materialName());
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("武器 " + def.id() + " 的 material 无效: " + def.materialName() + "，已回退为 STICK");
            material = Material.STICK;
        }
        final Material resolved = material;
        ItemStack stack = new ItemStack(resolved);
        boolean soulbound = plugin.config().soulbound() && owner != null;

        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(def.display()));
            if (!def.lore().isEmpty()) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String line : def.lore()) {
                    lore.add(MINI.deserialize(line));
                }
                meta.lore(lore);
            }
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
            // 本期不做资源包，model-data 保持 0；将来接资源包时直接填这里即可
            if (def.modelData() > 0) {
                meta.setCustomModelData(def.modelData());
            }
            applyAttribute(meta, resolved, "attack_damage", "weapon_damage", def.attribute("attack-damage", Double.NaN));
            applyAttribute(meta, resolved, "attack_speed", "weapon_speed", def.attribute("attack-speed", Double.NaN));
            applyAttribute(meta, resolved, "movement_speed", "weapon_movement", def.attribute("movement-speed", Double.NaN));

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(PDCKeys.characterId(), PersistentDataType.STRING, def.characterId() == null ? "" : def.characterId());
            pdc.set(PDCKeys.weaponId(), PersistentDataType.STRING, def.id());
            pdc.set(PDCKeys.mode(), PersistentDataType.STRING, def.defaultMode() == null ? "" : def.defaultMode());
            pdc.set(PDCKeys.instanceId(), PersistentDataType.STRING, UUID.randomUUID().toString());
            pdc.set(PDCKeys.dataVersion(), PersistentDataType.INTEGER, PDCKeys.CURRENT_DATA_VERSION);
            if (soulbound) {
                pdc.set(PDCKeys.owner(), PersistentDataType.STRING, owner.getUniqueId().toString());
                pdc.set(PDCKeys.soulbound(), PersistentDataType.BYTE, (byte) 1);
            }
        });
        return stack;
    }

    /**
     * 把配置里的最终值换算成需要追加的差值。
     * 例如钻石锹原版攻击力 4.5、配置写 7.0 → 追加 +2.5，玩家实际就是 7.0。
     */
    private void applyAttribute(ItemMeta meta, Material material, String attributeName, String keyName, double target) {
        if (Double.isNaN(target)) {
            return;
        }
        Attribute attribute = versions.attribute(attributeName);
        if (attribute == null) {
            plugin.getLogger().warning("当前服务端无法解析属性 " + attributeName + "，已跳过（版本适配层会记录该能力不可用）");
            return;
        }
        double delta = target - baseValueOf(material, attribute);
        if (Math.abs(delta) < 0.001D) {
            return;
        }
        meta.addAttributeModifier(attribute, new AttributeModifier(
                new NamespacedKey(plugin, keyName),
                delta,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
    }

    /** 读该 Material 在主手上的原版默认属性值；取不到时按 0 处理（即"纯加成"回退）。 */
    private double baseValueOf(Material material, Attribute attribute) {
        try {
            var modifiers = material.getDefaultAttributeModifiers(EquipmentSlot.HAND);
            for (var entry : modifiers.entries()) {
                if (entry.getKey() != null && entry.getKey().equals(attribute)
                        && entry.getValue().getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                    return entry.getValue().getAmount();
                }
            }
        } catch (Throwable ignored) {
            // 该方法在个别版本签名不同 → 回退为"配置值即追加值"，不影响功能
        }
        return 0.0D;
    }

    /** 读取物品身份；不是插件武器时返回 null。 */
    public Identity read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String weaponId = pdc.get(PDCKeys.weaponId(), PersistentDataType.STRING);
        if (weaponId == null || weaponId.isBlank()) {
            return null;
        }
        String characterId = pdc.get(PDCKeys.characterId(), PersistentDataType.STRING);
        String mode = pdc.get(PDCKeys.mode(), PersistentDataType.STRING);
        String instanceId = pdc.get(PDCKeys.instanceId(), PersistentDataType.STRING);
        String ownerId = pdc.get(PDCKeys.owner(), PersistentDataType.STRING);
        return new Identity(characterId, weaponId, mode, instanceId, ownerId);
    }

    public boolean isPluginWeapon(ItemStack stack) {
        return read(stack) != null;
    }

    /** 把当前模式写回物品（仅展示用冗余，真源在 PlayerProfile）。 */
    public void writeMode(ItemStack stack, String mode) {
        if (stack == null || stack.getType().isAir() || mode == null) {
            return;
        }
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(PDCKeys.mode(), PersistentDataType.STRING, mode));
    }

    /** 物品是否属于该玩家（未绑定物品视为公共可用）。 */
    public boolean usableBy(ItemStack stack, Player player) {
        Identity identity = read(stack);
        if (identity == null) {
            return false;
        }
        if (!identity.soulbound()) {
            return true;
        }
        return identity.ownerId().equals(player.getUniqueId().toString());
    }
}
