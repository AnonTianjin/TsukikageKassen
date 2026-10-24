package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.TagManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 隐性标签设置界面（管理员）：先选玩家，再选标签。
 *
 * <p>标签本身只影响"同一个队伍里抢同一个角色"时的优先顺序（权重高者优先），
 * 不影响战斗数值，也不对玩家公开显示。权重在 <code>config.yml</code> 的 <code>tags</code> 段配置。</p>
 *
 * <p>入口：管理员菜单里的「隐性标签」按钮，或 <code>/taketori tags</code>；
 * 也可以用指令 <code>/taketori tag &lt;玩家&gt; &lt;标签&gt;</code> 直接设置。</p>
 */
public final class TagMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 菜单持有者：target 为 null 表示"选玩家"页，否则是"选标签"页。 */
    private static final class Holder implements InventoryHolder {

        private final UUID target;
        /** 玩家列表页里"第 N 格是谁"；点击时按它取人，避免重新拉列表导致错位。 */
        private final List<UUID> listed = new ArrayList<>();
        private Inventory inventory;

        Holder(UUID target) {
            this.target = target;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;

    public TagMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 选玩家

    public void openPlayers(Player admin) {
        if (admin == null || !admin.isOnline()) {
            return;
        }
        Holder holder = new Holder(null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>隐性标签 <dark_gray>· <white>选择玩家"));
        holder.bind(inventory);

        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot, head(online));
            holder.listed.add(online.getUniqueId());
            slot++;
        }
        inventory.setItem(49, info());
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        admin.openInventory(inventory);
    }

    private ItemStack head(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        String tag = plugin.dataStore().tagOf(player.getUniqueId());
        int weight = plugin.tags().weightOf(tag);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<white>" + player.getName()));
            meta.lore(List.of(
                    MINI.deserialize("<gray>角色：<white>" + characterOf(player.getUniqueId())),
                    MINI.deserialize("<gray>隐性标签：<white>" + plugin.tags().displayOf(tag)
                            + " <dark_gray>(" + (tag == null ? "未设置" : tag) + ")"),
                    MINI.deserialize("<gray>权重：<white>" + weight),
                    Component.empty(),
                    MINI.deserialize("<yellow>▶ 点击设置他的标签")));
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(player);
            }
        });
        return item;
    }

    // ---------------------------------------------------------------- 选标签

    public void openTags(Player admin, UUID target) {
        Holder holder = new Holder(target);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MINI.deserialize("<dark_gray>隐性标签 <dark_gray>· <white>" + nameOf(target)));
        holder.bind(inventory);

        String current = plugin.dataStore().tagOf(target);
        int slot = 0;
        for (TagManager.TagDef def : plugin.tags().all()) {
            if (slot >= 18) {
                break;
            }
            boolean selected = def.id().equalsIgnoreCase(current == null ? "" : current);
            ItemStack item = new ItemStack(selected ? Material.LIME_DYE : Material.PAPER);
            item.editMeta(meta -> {
                meta.displayName(MINI.deserialize((selected ? "<green>▶ " : "<white>") + def.display()
                        + " <dark_gray>(" + def.id() + ")"));
                meta.lore(List.of(
                        MINI.deserialize("<gray>权重：<white>" + def.weight()),
                        MINI.deserialize("<dark_gray>同队角色冲突时，权重高者优先"),
                        MINI.deserialize(selected ? "<green>当前标签" : "<yellow>点击设为该标签")));
            });
            inventory.setItem(slot++, item);
        }

        inventory.setItem(22, button(Material.BARRIER, "<red>清除标签",
                "<dark_gray>把这个玩家的标签设为空（权重 0）"));
        inventory.setItem(26, button(Material.ARROW, "<yellow>返回", "<gray>回到玩家列表"));
        admin.openInventory(inventory);
    }

    private ItemStack info() {
        return button(Material.NAME_TAG, "<light_purple>隐性标签",
                "<gray>只影响<white>同队角色冲突</white>时的优先顺序",
                "<gray>权重在 <white>config.yml</white> 的 <white>tags</white> 段配置",
                "<dark_gray>同一个队伍里不允许出现相同角色",
                "<dark_gray>不同队伍之间可以有相同角色");
    }

    // ---------------------------------------------------------------- 点击

    /** 拖拽同样不允许在本菜单分发物品（与点击取消保持一致）。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin)) {
            return;
        }
        if (!admin.hasPermission("taketori.admin")) {
            admin.closeInventory();
            return;
        }
        int slot = event.getRawSlot();

        if (holder.target == null) {
            if (slot == 53) {
                admin.closeInventory();
                return;
            }
            if (slot >= 0 && slot < holder.listed.size()) {
                openTags(admin, holder.listed.get(slot));
            }
            return;
        }

        if (slot == 26) {
            openPlayers(admin);
            return;
        }
        if (slot == 22) {
            apply(admin, holder.target, null);
            openTags(admin, holder.target);
            return;
        }
        if (slot >= 0 && slot < 18) {
            List<TagManager.TagDef> defs = new ArrayList<>(plugin.tags().all());
            if (slot < defs.size()) {
                apply(admin, holder.target, defs.get(slot).id());
                openTags(admin, holder.target);
            }
        }
    }

    /** 写入标签：内存档案 + 持久化，并给管理员回执。 */
    private void apply(Player admin, UUID target, String tagId) {
        String name = nameOf(target);
        if (tagId == null) {
            plugin.setTag(target, null);
            admin.sendMessage(MINI.deserialize("<yellow>已清除 " + name + " 的隐性标签（权重 0）。"));
            return;
        }
        if (!plugin.tags().has(tagId)) {
            admin.sendMessage(MINI.deserialize("<red>标签不存在：" + tagId));
            return;
        }
        plugin.setTag(target, tagId);
        admin.sendMessage(MINI.deserialize("<green>已把 " + name + " 的标签设为 <white>"
                + plugin.tags().displayOf(tagId) + "</white> <dark_gray>（权重 "
                + plugin.tags().weightOf(tagId) + "）"));
    }

    private String characterOf(UUID uuid) {
        var profile = plugin.config().characters().profileOrNull(uuid);
        return profile == null || !profile.hasCharacter() ? "未选择" : profile.characterId();
    }

    private String nameOf(UUID uuid) {
        Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(MINI.deserialize(line));
            }
            meta.lore(lines);
        });
        return item;
    }
}
