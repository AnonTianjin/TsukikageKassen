package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.StatsTracker.Row;
import com.taketori.kassen.paper.match.StatsTracker.Stat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 总计排行榜（跨局累计，Hypixel 风格）：
 * 顶部一排分类按钮切换榜单，中间是排名列表（带头像），底部翻页。
 *
 * <p>数据来自 {@link StatsTracker}（<code>data/stats.yml</code>），包含总积分、对局数、胜场、
 * 击杀、月人击杀、拆家、死亡、单局最高分。点某一行会在聊天栏展开该玩家的全部统计。</p>
 */
public final class StatsMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 每页显示多少名。 */
    private static final int PAGE_SIZE = 36;

    private static final Map<Stat, Material> ICONS = new EnumMap<>(Stat.class);

    static {
        ICONS.put(Stat.SCORE, Material.NETHER_STAR);
        ICONS.put(Stat.KILLS, Material.IRON_SWORD);
        ICONS.put(Stat.MINION_KILLS, Material.ROTTEN_FLESH);
        ICONS.put(Stat.BASE_CAPTURES, Material.TNT);
        ICONS.put(Stat.DEATHS, Material.SKELETON_SKULL);
        ICONS.put(Stat.MATCHES, Material.PAPER);
        ICONS.put(Stat.WINS, Material.GOLDEN_APPLE);
        ICONS.put(Stat.BEST_SCORE, Material.EXPERIENCE_BOTTLE);
    }

    /** 菜单持有者：把"当前榜单 + 页码"随菜单实例带走，避免标题匹配串页。 */
    private static final class Holder implements InventoryHolder {

        private final Stat stat;
        private final int page;
        private Inventory inventory;

        Holder(Stat stat, int page) {
            this.stat = stat;
            this.page = page;
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

    public StatsMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 打开排行榜；stat 为要看的榜单，page 从 1 开始。 */
    public void open(Player player) {
        open(player, Stat.SCORE, 1);
    }

    public void open(Player player, Stat stat, int page) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Stat[] all = plugin.stats().stats();
        int total = plugin.stats().rankedCount(stat);
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));

        Holder holder = new Holder(stat, current);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>总计排行榜 <dark_gray>· <white>" + stat.display()));
        holder.bind(inventory);

        for (int i = 0; i < all.length && i < 8; i++) {
            Stat each = all[i];
            boolean selected = each == stat;
            ItemStack item = new ItemStack(ICONS.getOrDefault(each, Material.PAPER));
            int ranked = plugin.stats().rankedCount(each);
            item.editMeta(meta -> {
                meta.displayName(MINI.deserialize((selected ? "<green>▶ " : "<gray>") + each.display()));
                meta.lore(List.of(
                        MINI.deserialize("<dark_gray>上榜人数：<white>" + ranked),
                        MINI.deserialize(selected ? "<green>当前榜单" : "<yellow>点击切换")));
            });
            inventory.setItem(i, item);
        }

        List<Row> rows = plugin.stats().top(stat, PAGE_SIZE, (current - 1) * PAGE_SIZE);
        int slot = 9;
        for (Row row : rows) {
            inventory.setItem(slot++, entryItem(row, stat));
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "<gray>还没有记录",
                    "<dark_gray>打完一局之后这里就会出现数据"));
        }

        inventory.setItem(8, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        if (current > 1) {
            inventory.setItem(45, button(Material.ARROW, "<yellow>← 上一页",
                    "<dark_gray>第 " + (current - 1) + " / " + pages + " 页"));
        }
        inventory.setItem(49, button(Material.BOOK, "<white>" + stat.display(),
                "<dark_gray>第 <white>" + current + "</white> / " + pages + " 页",
                "<dark_gray>上榜人数：<white>" + total,
                "<dark_gray>全部统计都是跨局累计"));
        if (current < pages) {
            inventory.setItem(53, button(Material.ARROW, "<yellow>下一页 →",
                    "<dark_gray>第 " + (current + 1) + " / " + pages + " 页"));
        }

        player.openInventory(inventory);
    }

    private ItemStack entryItem(Row row, Stat stat) {
        Material material = switch (row.rank()) {
            case 1 -> Material.GOLDEN_HELMET;
            case 2 -> Material.IRON_HELMET;
            case 3 -> Material.CHAINMAIL_HELMET;
            default -> Material.PLAYER_HEAD;
        };
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<yellow>#" + row.rank() + " <white>" + row.name()));
            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<gray>" + stat.display() + "：<white>" + row.value()));
            lore.add(Component.empty());
            for (Stat each : plugin.stats().stats()) {
                if (each == stat) {
                    continue;
                }
                lore.add(MINI.deserialize("<dark_gray>" + each.display() + "：<white>"
                        + plugin.stats().valueOf(row.name(), each)));
            }
            lore.add(Component.empty());
            lore.add(MINI.deserialize("<yellow>点击在聊天栏查看详情"));
            meta.lore(lore);
            if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull) {
                // 只用非阻塞的本地缓存查询：Bukkit.getOfflinePlayer(String) 在 usercache
                // 缺失该名字时会发起阻塞式 Web 查询，直接冻结主线程
                org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(row.name());
                if (cached != null) {
                    skull.setOwningPlayer(cached);
                }
            }
        });
        return item;
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

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        Stat[] all = plugin.stats().stats();

        if (slot >= 0 && slot < 8 && slot < all.length) {
            open(player, all[slot], 1);
            return;
        }
        if (slot == 8) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            open(player, holder.stat, holder.page - 1);
            return;
        }
        if (slot == 53) {
            open(player, holder.stat, holder.page + 1);
            return;
        }
        if (slot >= 9 && slot < 9 + PAGE_SIZE) {
            int offset = (holder.page - 1) * PAGE_SIZE + (slot - 9);
            List<Row> rows = plugin.stats().top(holder.stat, 1, offset);
            if (!rows.isEmpty()) {
                sendDetail(player, rows.get(0));
            }
        }
    }

    private void sendDetail(Player player, Row row) {
        player.sendMessage(MINI.deserialize("<dark_gray>────────── <white>" + row.name() + " <dark_gray>──────────"));
        for (Stat stat : plugin.stats().stats()) {
            player.sendMessage(MINI.deserialize("<gray>" + stat.display() + "：<white>"
                    + plugin.stats().valueOf(row.name(), stat)));
        }
    }
}
