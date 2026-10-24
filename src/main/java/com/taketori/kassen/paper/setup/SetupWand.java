package com.taketori.kassen.paper.setup;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.item.PDCKeys;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * 选区锄：划场地用的管理员工具，默认绑定在<b>下界合金锄</b>上（材料可在 config.yml 里换）。
 *
 * <p>身份同样只认 PDC（和武器一致的原则）：显示名与 Lore 改掉也照样能识别，
 * 反之别人拿一把普通下界合金锄不会被当成工具。</p>
 */
public final class SetupWand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public SetupWand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().setupWandEnabled();
    }

    /** 配置里声明的材料；解析失败时回退到下界合金锄并告警。 */
    public Material material() {
        String name = plugin.config().setupWandMaterial();
        Material material = name == null ? null : Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("setup-wand.material 无效（" + name + "），已回退为 NETHERITE_HOE");
            return Material.NETHERITE_HOE;
        }
        return material;
    }

    /** 生成一把选区锄。 */
    public ItemStack create() {
        Material material = material();
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<gold>选区锄</gold> <dark_gray>· 划场地用"));
            meta.lore(List.of(
                    MINI.deserialize("<gray>左键点方块 <dark_gray>→ <white>角点 1"),
                    MINI.deserialize("<gray>右键点方块 <dark_gray>→ <white>角点 2"),
                    MINI.deserialize("<gray>潜行 + 左键 <dark_gray>→ <white>清空选区"),
                    MINI.deserialize("<gray>潜行 + 右键 <dark_gray>→ <white>查看选区信息"),
                    MINI.deserialize(""),
                    MINI.deserialize("<dark_gray>选好后用指令绑定："),
                    MINI.deserialize("<dark_gray>/taketori arena setminion"),
                    MINI.deserialize("<dark_gray>/taketori arena setbase <red|blue> [编号]"),
                    MINI.deserialize("<dark_gray>/taketori arena setspawn <red|blue>")));
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
            meta.getPersistentDataContainer().set(PDCKeys.setupWand(), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 物品是不是选区锄。 */
    public boolean isWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte flag = meta.getPersistentDataContainer().get(PDCKeys.setupWand(), PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** 玩家主手是不是选区锄。 */
    public boolean holdingWand(Player player) {
        return player != null && isWand(player.getInventory().getItemInMainHand());
    }

    // ---------------------------------------------------------------- 道具点工具

    /** 道具点工具键的缓存：NamespacedKey 不可变，没必要每次判定都新建。 */
    private volatile org.bukkit.NamespacedKey cachedLootKey;

    /**
     * 道具刷新点工具的 PDC 键。
     * 用独立的键区分两种工具，避免"拿着道具点工具却在划基地"这类误操作。
     */
    private org.bukkit.NamespacedKey lootKey() {
        org.bukkit.NamespacedKey key = cachedLootKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedLootKey == null) {
                cachedLootKey = new org.bukkit.NamespacedKey(plugin, "loot_wand");
            }
            return cachedLootKey;
        }
    }

    /** 道具点工具的材料（配置 setup-wand.loot-material，默认结构空位 —— 冷门且不会被误用）。 */
    public Material lootMaterial() {
        String name = plugin.getConfig().getString("setup-wand.loot-material", "STRUCTURE_VOID");
        Material material = name == null ? null : Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("setup-wand.loot-material 无效（" + name + "），已回退为 STRUCTURE_VOID");
            return Material.STRUCTURE_VOID;
        }
        return material;
    }

    /** 生成一把道具点工具。 */
    public ItemStack createLoot() {
        ItemStack stack = new ItemStack(lootMaterial());
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<aqua>道具点工具</aqua> <dark_gray>· 划道具刷新点"));
            meta.lore(List.of(
                    MINI.deserialize("<gray>左键点方块 <dark_gray>→ <white>角点 1"),
                    MINI.deserialize("<gray>右键点方块 <dark_gray>→ <white>角点 2"),
                    MINI.deserialize("<gray>潜行 + 左键 <dark_gray>→ <white>清空选区"),
                    MINI.deserialize(""),
                    MINI.deserialize("<dark_gray>选好后执行："),
                    MINI.deserialize("<dark_gray>/taketori arena setloot <编号>")));
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
            meta.getPersistentDataContainer().set(lootKey(), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 物品是不是道具点工具。 */
    public boolean isLootWand(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte flag = meta.getPersistentDataContainer().get(lootKey(), PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /**
     * 主手拿的是"任意一种选区工具"（选区锄或道具点工具）。
     * 两种工具共用同一份选区数据，所以要绑什么由随后执行的指令决定。
     */
    public boolean holdingAny(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        return isWand(hand) || isLootWand(hand);
    }

    /** 发一把道具点工具（已有则提示）。 */
    public void giveLoot(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isLootWand(stack)) {
                player.sendMessage(MINI.deserialize("<gray>你背包里已经有道具点工具了（<white>"
                        + lootMaterial().name().toLowerCase(Locale.ROOT) + "</white>）。"));
                return;
            }
        }
        player.getInventory().addItem(createLoot());
        player.sendMessage(MINI.deserialize("<green>已获得道具点工具 <dark_gray>（"
                + lootMaterial().name().toLowerCase(Locale.ROOT)
                + "）：左键 = 角点 1，右键 = 角点 2，之后执行 <white>/taketori arena setloot <编号>"));
    }

    /** 发一把给玩家（已有则提示，不重复发）。 */
    public void give(Player player) {
        if (!enabled()) {
            player.sendMessage(MINI.deserialize("<red>选区锄已在 config.yml 里关闭（setup-wand.enabled: false）。"));
            return;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isWand(stack)) {
                player.sendMessage(MINI.deserialize("<gray>你背包里已经有一把选区锄了（<white>"
                        + material().name().toLowerCase(Locale.ROOT) + "</white>）。"));
                player.getInventory().setHeldItemSlot(firstWandSlot(player));
                return;
            }
        }
        player.getInventory().addItem(create());
        player.sendMessage(MINI.deserialize("<green>已获得选区锄 <dark_gray>（"
                + material().name().toLowerCase(Locale.ROOT) + "）：左键 = 角点 1，右键 = 角点 2，潜行 + 右键 = 查看选区。"));
    }

    private int firstWandSlot(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < Math.min(9, contents.length); slot++) {
            if (isWand(contents[slot])) {
                return slot;
            }
        }
        return player.getInventory().getHeldItemSlot();
    }
}
