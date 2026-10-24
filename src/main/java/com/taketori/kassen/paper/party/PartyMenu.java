package com.taketori.kassen.paper.party;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 派对图形界面（箱子 GUI）：
 * <ul>
 *   <li>主页：成员头颅（房主带 ★；房主点他人头颅踢出）、邀请玩家、
 *       房主解散、成员退出（房主退出 = 转让）、返回玩家菜单；</li>
 *   <li>邀请页：列出所有在线且无派对的玩家，点头颅即邀请，支持翻页。</li>
 * </ul>
 *
 * <p>行为全部委托 {@link PartyManager}，GUI 只负责展示与路由，规则不两处实现。</p>
 */
public final class PartyMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** 邀请页每页玩家数（slots 0..44）。 */
    private static final int INVITE_PAGE_SIZE = 45;

    /** 页面标识（invitePage=true 为邀请页）+ 邀请页页码（主页恒 0）。 */
    private static final class Holder implements InventoryHolder {
        private final boolean invitePage;
        private final int page;
        private Inventory inventory;

        private Holder(boolean invitePage, int page) {
            this.invitePage = invitePage;
            this.page = page;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;

    public PartyMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 主页

    /** 打开派对主页。 */
    public void open(Player player) {
        openMain(player);
    }

    private void openMain(Player player) {
        Holder holder = new Holder(false, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>派对管理"));
        holder.bind(inventory);

        PartyManager.Party party = plugin.party().partyOf(player.getUniqueId());
        if (party == null) {
            // 无派对：引导 + 一键进入邀请页（发出第一个邀请时自动创建派对）。
            // 按钮刻意放在第四行 slot 40，避开第二行的成员头颅槽位区（18-26）
            inventory.setItem(4, button(Material.PAPER, "<yellow>你还没有派对",
                    "<gray>和好友组队后整队进入同一房间，开局整组同队。"));
            inventory.setItem(40, button(Material.PLAYER_HEAD, "<green>邀请好友组建派对",
                    "<yellow>点击查看可邀请的在线玩家"));
            inventory.setItem(53, button(Material.ARROW, "<gray>返回玩家菜单"));
            player.openInventory(inventory);
            return;
        }

        boolean leader = party.leader().equals(player.getUniqueId());
        inventory.setItem(4, button(Material.PAPER, "<white>派对 <dark_gray>("
                        + party.members().size() + "/" + plugin.party().maxSize() + " 人)",
                "<gray>房主：<white>" + name(party.leader()),
                "<dark_gray>快速匹配会整队进同一房间"));

        // 邀请玩家（成员点击由 PartyManager.invite 的房主校验兜底拒绝）
        inventory.setItem(2, button(Material.EMERALD, "<green>邀请玩家",
                "<gray>查看在线玩家并发出邀请（60 秒有效）",
                leader ? "<yellow>点击打开玩家列表" : "<red>只有房主可以邀请"));

        // 成员头颅：第三行（27-35）按容量居中排布。
        // 刻意避开第二行的 slot 22（玩家菜单的关闭按钮习惯位），防止头颅与功能按钮槽位冲突
        int cap = Math.min(9, plugin.party().maxSize());
        int start = 27 + (9 - cap) / 2;
        int index = 0;
        for (UUID memberId : party.members()) {
            inventory.setItem(start + index, memberHead(player, memberId, party, leader));
            index++;
        }

        if (leader) {
            inventory.setItem(48, button(Material.BARRIER, "<red>解散派对",
                    "<gray>移除全部成员并解散", "<dark_gray>点击即解散"));
        }
        inventory.setItem(50, button(Material.OAK_DOOR,
                leader ? "<yellow>退出派对（房主转让）" : "<yellow>退出派对",
                leader ? "<gray>房主职位转给最早加入的成员" : "<gray>离开当前派对",
                "<dark_gray>点击退出"));
        inventory.setItem(53, button(Material.ARROW, "<gray>返回玩家菜单"));

        player.openInventory(inventory);
    }

    /** 成员头颅：房主显示 ★；房主视角的他人附「点击踢出」，自己标注（你）。 */
    private ItemStack memberHead(Player viewer, UUID memberId,
                                 PartyManager.Party party, boolean viewerLeader) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(memberId));
            boolean isLeader = party.leader().equals(memberId);
            boolean self = memberId.equals(viewer.getUniqueId());
            meta.displayName(MINI.deserialize((isLeader ? "<gold>★ " : "<white>") + name(memberId)));
            List<String> lore = new ArrayList<>();
            lore.add(isLeader ? "<gray>房主" : "<gray>成员");
            if (self) {
                lore.add("<dark_gray>（你）");
            } else if (viewerLeader) {
                lore.add("<red>点击踢出派对");
            }
            meta.lore(lore.stream().map(MINI::deserialize).toList());
        });
        return head;
    }

    // ---------------------------------------------------------------- 邀请页

    private void openInvite(Player player, int page) {
        Holder holder = new Holder(true, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>邀请玩家 <dark_gray>· <gray>第 " + (page + 1) + " 页"));
        holder.bind(inventory);

        List<UUID> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            if (!id.equals(player.getUniqueId()) && plugin.party().partyOf(id) == null) {
                candidates.add(id);
            }
        }

        int from = page * INVITE_PAGE_SIZE;
        if (candidates.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "<red>暂无可邀请玩家",
                    "<gray>在线玩家要么在别的派对里，要么服务器只有你"));
        } else if (from < candidates.size()) {
            int to = Math.min(candidates.size(), from + INVITE_PAGE_SIZE);
            int slot = 0;
            for (int idx = from; idx < to; idx++) {
                inventory.setItem(slot++, candidateHead(candidates.get(idx)));
            }
        }

        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "<yellow>上一页"));
        }
        if (from + INVITE_PAGE_SIZE < candidates.size()) {
            inventory.setItem(53, button(Material.ARROW, "<yellow>下一页"));
        }
        inventory.setItem(49, button(Material.OAK_DOOR, "<gray>返回派对主页"));

        player.openInventory(inventory);
    }

    /** 邀请页 {@code page}（0 起）是否还有候选玩家（下一页按钮的边界判定用）。 */
    private boolean hasInviteCandidates(Player player, int page) {
        int from = page * INVITE_PAGE_SIZE;
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(player.getUniqueId())
                    && plugin.party().partyOf(online.getUniqueId()) == null
                    && ++count > from) {
                return true;
            }
        }
        return false;
    }

    private ItemStack candidateHead(UUID id) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            meta.displayName(MINI.deserialize("<white>" + name(id)));
            meta.lore(List.of(MINI.deserialize("<green>点击邀请加入派对")));
        });
        return head;
    }

    // ---------------------------------------------------------------- 点击路由

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.isOnline()) {
            return;
        }
        // 只处理上层菜单点击；玩家背包区点击仅取消、不路由
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        int slot = event.getSlot();
        if (holder.invitePage) {
            switch (slot) {
                case 45 -> {
                    if (holder.page > 0) {
                        openInvite(player, holder.page - 1);
                    }
                }
                case 53 -> {
                    // 下一页确实存在才翻：空槽 53 被点不该翻进空页（上一页有 page>0 守卫，这里对称）
                    if (hasInviteCandidates(player, holder.page + 1)) {
                        openInvite(player, holder.page + 1);
                    }
                }
                case 49 -> openMain(player);
                default -> {
                    if (slot < INVITE_PAGE_SIZE) {
                        UUID target = ownerOf(event.getCurrentItem());
                        Player targetPlayer = target == null ? null : Bukkit.getPlayer(target);
                        if (targetPlayer != null
                                && plugin.party().invite(player, targetPlayer)) {
                            openInvite(player, holder.page);   // 成功后刷新（该玩家离开候选列表）
                        }
                    }
                }
            }
            return;
        }

        switch (slot) {
            case 2 -> openInvite(player, 0);
            case 40 -> {
                // 无派对时的组建入口（有派对时此槽位为空）
                if (plugin.party().partyOf(player.getUniqueId()) == null) {
                    openInvite(player, 0);
                }
            }
            case 48 -> {
                PartyManager.Party party = plugin.party().partyOf(player.getUniqueId());
                if (party != null && party.leader().equals(player.getUniqueId())) {
                    plugin.party().disband(player.getUniqueId());
                    openMain(player);
                }
            }
            case 50 -> {
                plugin.party().leave(player.getUniqueId());
                openMain(player);
            }
            case 53 -> {
                player.closeInventory();
                plugin.playerMenu().open(player);
            }
            default -> {
                // 成员头颅区：房主点他人踢出；其他人点头颅无效
                PartyManager.Party party = plugin.party().partyOf(player.getUniqueId());
                if (party == null || !party.leader().equals(player.getUniqueId())) {
                    return;
                }
                UUID target = ownerOf(event.getCurrentItem());
                if (target != null && !target.equals(player.getUniqueId())
                        && party.members().contains(target)) {
                    plugin.party().kick(player, target);
                    openMain(player);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 从头颅物品反查所属玩家 id（非头颅 / 无属主返回 null）。 */
    private static UUID ownerOf(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) {
            return null;
        }
        if (item.getItemMeta() instanceof SkullMeta skull && skull.getOwningPlayer() != null) {
            return skull.getOwningPlayer().getUniqueId();
        }
        return null;
    }

    /** 显示名：在线取在线名；离线用缓存名（UUID 重载不做网络查询），再不行用 id 前缀。 */
    private static String name(UUID id) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        String text = offline.getName();
        return text != null ? text : id.toString().substring(0, 8);
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
