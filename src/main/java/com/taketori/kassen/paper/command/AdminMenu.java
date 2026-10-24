package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.lobby.LobbyAction;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员菜单：把常用管理指令映射成按钮（对局控制 / 场地 / 大厅 / 维护），外加一个"删除已划区域"子页。
 *
 * <p>两类按钮：</p>
 * <ul>
 *   <li><b>直接执行</b>：无参数指令（start / force / stop / status / mode / list / reload / doctor / debug），
 *       点击后按管理员身份执行，等价于自己手敲；</li>
 *   <li><b>提示补参</b>：需要参数的指令（分基地、绑告示牌…）点击后给一条可点击的建议指令
 *       （<code>suggest_command</code>），补完参数回车即可。</li>
 * </ul>
 *
 * <p><b>删除页</b>（{@code Page.DELETE}）用"格子 → 目标"映射表记录每个按钮删的是什么，
 * 而不是用坐标反推编号 —— 基地编号允许跳号（例如只配了 #1 与 #5），反推会删错东西。</p>
 *
 * <p>打开方式：<code>/taketori admin</code>（需要 taketori.admin 权限），或在玩家菜单里点「管理员菜单」。</p>
 */
public final class AdminMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 菜单页码。 */
    private enum Page {
        MAIN,
        ROOMS,
        ROOM,
        DELETE
    }

    /** 删除页一个格子对应要删的东西。 */
    private record DeleteTarget(String kind, TeamId team, int index) {
    }

    /** 菜单持有者：避免用标题匹配识别界面，并记住当前是哪一页与删除页的目标映射。 */
    private static final class Holder implements InventoryHolder {

        private final Page page;
        private final Map<Integer, DeleteTarget> targets = new HashMap<>();
        /** 房间选择页：槽位 → 场地/房间 id。 */
        private final Map<Integer, String> roomSlots = new HashMap<>();
        /** 房间控制页：当前操作的房间 id。 */
        private String roomId;
        private Inventory inventory;

        Holder(Page page) {
            this.page = page;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        Map<Integer, DeleteTarget> targets() {
            return targets;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;

    public AdminMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 主菜单

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.sendMessage(MINI.deserialize("<red>需要 taketori.admin 权限。"));
            return;
        }
        Holder holder = new Holder(Page.MAIN);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>月影合战 <dark_gray>· <white>管理员菜单"));
        holder.bind(inventory);

        inventory.setItem(4, button(Material.NETHER_STAR, "<gold>管理员菜单",
                "<gray>点按钮 = 执行对应指令",
                "<gray>需要参数的会给出可点击的建议指令",
                "<dark_gray>房间：<white>" + plugin.rooms().rooms().size() + " 个"
                        + " <dark_gray>进行中：<white>" + plugin.rooms().rooms().stream()
                        .filter(GameRoom::isRunning).count() + " 个"));

        // ---- 对局控制：先选房间（BedWars 式多房间：不能再对"当前唯一对局"直接下手）----
        inventory.setItem(10, button(Material.CHEST, "<green>房间管理",
                "<gray>开局 / 强制开局 / 结束 / 切模式 / 查看状态",
                "<yellow>先选择要操作的房间，再执行",
                "<dark_gray>等价命令：/taketori match start|stop|mode ... [场地id]"));

        // ---- 场地 ----
        inventory.setItem(19, run(Material.NETHERITE_HOE, "<yellow>领选区锄",
                "<dark_gray>/taketori arena wand", "taketori arena wand"));
        inventory.setItem(20, run(Material.MAP, "<white>场地列表",
                "<dark_gray>/taketori arena list", "taketori arena list"));
        inventory.setItem(21, suggest(Material.RED_BANNER, "<white>划基地 / 出生点",
                "<dark_gray>点一下填入指令，补完坐标参数回车",
                "<gray>例如 /taketori arena setbase red 1",
                "/taketori arena setbase red 1"));
        inventory.setItem(22, suggest(Material.ROTTEN_FLESH, "<white>划月人刷新区",
                "<gray>先用锄头点两个角，再执行",
                "/taketori arena setminion"));
        inventory.setItem(23, suggest(Material.CHEST, "<white>划道具刷新点",
                "<dark_gray>先用「道具点工具」点两个角，再执行",
                "/taketori arena setloot 1"));
        inventory.setItem(24, run(Material.NAME_TAG, "<light_purple>隐性标签",
                "<dark_gray>给玩家设置权重标签（同队角色冲突时高者优先）",
                "<gray>权重在 config.yml 的 tags 段配置",
                "taketori tags"));
        inventory.setItem(25, button(Material.TNT, "<red>删除已划区域",
                "<gray>基地 / 月人刷新区 / 道具点 / PVE 据点",
                "<gray>点进子页后<white>点条目即删除</white>",
                "<dark_gray>等价命令：/taketori arena delbase | delminion | delloot | deloutpost"));
        inventory.setItem(26, run(Material.GOLDEN_HOE, "<white>清空我的选区",
                "<dark_gray>清掉 pos1 / pos2，重新划",
                "taketori arena clearselection"));

        // ---- 玩法专属菜单 ----
        inventory.setItem(34, run(Material.CROSSBOW, "<gold>战国 3v3",
                "<gray>划区（天守阁 / 箭楼 / 铜钟 / 守卫 / 中地 / 跳跃台）",
                "<gray>与运维（开局 / 暂停 / 结束本局 / 比分）",
                "<gray>还有全局模式切换（PVP / PVE / 战国）",
                "<dark_gray>等价命令：/taketori sengoku menu",
                "taketori sengoku menu"));

        // ---- 大厅与维护 ----
        inventory.setItem(28, run(Material.OAK_SIGN, "<white>大厅配置",
                "<dark_gray>/taketori lobby list", "taketori lobby list"));
        inventory.setItem(29, suggest(Material.BIRCH_SIGN, "<white>绑定告示牌",
                "<gray>准星对准牌子后执行；动作：",
                "<gray>" + LobbyAction.keys(),
                "/taketori lobby addsign join"));
        inventory.setItem(30, run(Material.ANVIL, "<yellow>武器数据编辑",
                "<dark_gray>/taketori editor", "taketori editor"));
        inventory.setItem(31, run(Material.COMMAND_BLOCK, "<white>热重载配置",
                "<dark_gray>/taketori reload", "taketori reload"));
        inventory.setItem(32, run(Material.KNOWLEDGE_BOOK, "<white>自检",
                "<dark_gray>/taketori doctor（识别链路与名字解析）", "taketori doctor"));
        inventory.setItem(33, run(Material.GLOWSTONE_DUST, "<white>切换调试日志",
                "<dark_gray>/taketori debug on|off",
                "taketori debug " + (plugin.config().debug() ? "off" : "on")));

        inventory.setItem(49, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 房间选择页 / 房间控制页

    /** 房间选择：列出全部房间（阶段/模式/人数），点一个进它的控制页。 */
    public void openRooms(Player player) {
        if (player == null || !player.isOnline() || !player.hasPermission("taketori.admin")) {
            return;
        }
        Holder holder = new Holder(Page.ROOMS);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>选择要管理的房间"));
        holder.bind(inventory);

        inventory.setItem(4, button(Material.CHEST, "<green>房间管理",
                "<gray>每个场地对应一个独立房间，互不影响",
                "<yellow>点房间图标进入它的控制页"));

        int slot = 9;
        for (GameRoom room : plugin.rooms().rooms()) {
            if (slot >= 45) {
                break;
            }
            Material material = switch (room.phase()) {
                case WAITING -> Material.LIME_WOOL;
                case STARTING -> Material.YELLOW_WOOL;
                case CAGED -> Material.MAGENTA_WOOL;
                case PLAYING -> Material.RED_WOOL;
                case ENDING -> Material.GRAY_WOOL;
            };
            boolean live = room.phase() != GameRoom.Phase.WAITING && room.phase() != GameRoom.Phase.STARTING;
            int count = live ? room.onlineParticipantCount() : room.waitingCount();
            holder.roomSlots.put(slot, room.id());
            inventory.setItem(slot, button(material, "<white>" + room.display() + " <dark_gray>[" + room.id() + "]",
                    "<gray>阶段：<white>" + adminPhaseText(room),
                    "<gray>模式：<white>" + (room.isPve() ? "PVE" : "PVP")
                            + " <gray>人数 <white>" + count + "/" + room.maxPlayers(),
                    room.arena().isReady() ? "<yellow>点击管理该房间"
                            : "<red>场地未就绪：" + room.arena().missingHint()));
            slot++;
        }
        if (holder.roomSlots.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "<red>还没有启用场地的房间",
                    "<gray>/taketori arena create <id> 后再 enable，或 /taketori reload"));
        }

        inventory.setItem(49, button(Material.ARROW, "<yellow>返回管理员菜单", "<gray>回到上一页"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    /** 单个房间的控制页：所有指令都带该房 id，绝不误伤别的房间。 */
    public void openRoomControl(Player player, String roomId) {
        if (player == null || !player.isOnline() || !player.hasPermission("taketori.admin")) {
            return;
        }
        GameRoom room = plugin.rooms().room(roomId);
        if (room == null) {
            openRooms(player);
            return;
        }
        Holder holder = new Holder(Page.ROOM);
        holder.roomId = roomId;
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>房间控制 <dark_gray>· <white>" + room.display()));
        holder.bind(inventory);

        inventory.setItem(4, button(Material.CHEST, "<gold>" + room.display() + " <dark_gray>[" + roomId + "]",
                "<gray>阶段：<white>" + adminPhaseText(room),
                "<gray>模式：<white>" + (room.isPve() ? "PVE" : "PVP"),
                "<gray>等待/在场：<white>" + room.waitingCount() + " / " + room.onlineParticipantCount()
                        + " <dark_gray>（上限 " + room.maxPlayers() + "）",
                "<red>本页所有操作只作用于这个房间"));

        inventory.setItem(10, run(Material.LIME_DYE, "<green>开始对局",
                "<dark_gray>/taketori match start " + roomId,
                "<gray>等待区人数达标时开局（人数不足会拒绝）",
                "taketori match start " + roomId));
        inventory.setItem(11, run(Material.LIME_CONCRETE, "<green>强制开局",
                "<dark_gray>/taketori match force " + roomId,
                "<gray>等待区有几人就按几人开局（单人测试用）",
                "taketori match force " + roomId));
        inventory.setItem(12, run(Material.RED_CONCRETE, "<red><bold>结束该房间",
                "<dark_gray>/taketori match stop " + roomId + " 管理员通过菜单结束",
                "<gray>立刻结算并把该房的人送回大厅，不影响其他房间",
                "taketori match stop " + roomId + " 管理员通过菜单结束"));
        inventory.setItem(13, run(Material.BOOK, "<white>该房间状态",
                "<dark_gray>/taketori match status（查看全部房间）", "taketori match status"));
        inventory.setItem(14, run(Material.IRON_SWORD, "<aqua>切为 PVE",
                "<dark_gray>/taketori match mode pve " + roomId,
                "<gray>仅等待中的房间可切；只改本房",
                "taketori match mode pve " + roomId));
        inventory.setItem(15, run(Material.SHIELD, "<red>切为 PVP",
                "<dark_gray>/taketori match mode pvp " + roomId,
                "<gray>仅等待中的房间可切；只改本房",
                "taketori match mode pvp " + roomId));
        inventory.setItem(16, run(Material.CROSSBOW, "<gold>切为战国 3v3",
                "<dark_gray>/taketori match mode sengoku_3v3 " + roomId,
                "<gray>仅等待中的房间可切；只改本房",
                "taketori match mode sengoku_3v3 " + roomId));

        inventory.setItem(49, button(Material.ARROW, "<yellow>返回房间列表", "<gray>回到上一页"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    private String adminPhaseText(GameRoom room) {
        return switch (room.phase()) {
            case WAITING -> "等待中 " + room.waitingCount() + "/" + room.maxPlayers();
            case STARTING -> "倒计时 " + room.countdownSeconds() + "s";
            case CAGED -> "开局准备中";
            case PLAYING -> "游戏中（剩余 " + room.remainingText() + "）";
            case ENDING -> "结算中";
        };
    }

    // ---------------------------------------------------------------- 删除页

    /** 删除页：动态列出已配置的基地 / 刷新区 / 道具点 / 据点，点条目即删除。 */
    public void openDelete(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.sendMessage(MINI.deserialize("<red>需要 taketori.admin 权限。"));
            return;
        }
        // 删除页只作用于管理员当前选中的场地（与 /taketori arena 各子命令的作用域一致）
        ArenaDef def = plugin.arena().selected(player.getUniqueId());
        if (def == null) {
            player.sendMessage(MINI.deserialize("<red>还没有选中场地：<gray>先用 <white>/taketori arena select <场地id>"
                    + "</white> 选中，或用 <white>/taketori arena list</white> 查看现有场地。"));
            return;
        }
        Holder holder = new Holder(Page.DELETE);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>删除区域 <dark_gray>· <white>场地 " + def.id()));
        holder.bind(inventory);

        ArenaManager arena = plugin.arena();
        inventory.setItem(4, button(Material.BARRIER, "<red>删除场地 " + def.id() + " 已划定的区域",
                "<gray>点某一条就<white>立刻删除</white>它（不可撤销）",
                "<gray>删除后 <white>/taketori arena list</white> 立即反映",
                "<dark_gray>基地删多了会导致「无法开局」，补划回来即可"));

        fillBases(inventory, holder, def, TeamId.RED, 0);
        fillBases(inventory, holder, def, TeamId.BLUE, 9);

        int minionFilled = fillRegions(inventory, holder, def.minionRegions(), 18,
                "月人刷新区", Material.ROTTEN_FLESH, "minion", "delminion");
        int lootFilled = fillRegions(inventory, holder, def.lootRegions(), 27,
                "道具刷新点", Material.CHEST, "loot", "delloot");
        if (minionFilled == 0) {
            inventory.setItem(18, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>月人刷新区",
                    "<dark_gray>还没配置"));
        }
        if (lootFilled == 0) {
            inventory.setItem(27, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>道具刷新点",
                    "<dark_gray>还没配置"));
        }

        if (def.hasOutpost()) {
            int slot = 36;
            holder.targets().put(slot, new DeleteTarget("outpost", null, 0));
            var outpostPoint = def.outpost();
            inventory.setItem(slot, button(Material.SNOWBALL, "<white>PVE 据点",
                    "<gray>位置：" + (outpostPoint == null ? "（未设置）"
                            : outpostPoint.worldName() + " " + String.format("%.0f,%.0f,%.0f",
                            outpostPoint.x(), outpostPoint.y(), outpostPoint.z())),
                    "<red>点击删除据点位置",
                    "<dark_gray>删除后 PVE 会回落到第一个月人刷新区中心"));
        } else {
            inventory.setItem(36, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>PVE 据点",
                    "<dark_gray>未设置"));
        }

        int selectionSlot = 38;
        holder.targets().put(selectionSlot, new DeleteTarget("selection", null, 0));
        inventory.setItem(selectionSlot, button(Material.GOLDEN_HOE, "<white>清空我的选区",
                "<gray>当前：" + arena.selectionStatus(player.getUniqueId()),
                "<yellow>点击清空（重新划区域用）"));

        inventory.setItem(49, button(Material.ARROW, "<yellow>返回管理员菜单", "<gray>回到上一页"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    /** 一行 9 格铺开某队的基地 #1..#9（未配置的用灰玻璃占位，不可点）。 */
    private void fillBases(Inventory inventory, Holder holder, ArenaDef def, TeamId team, int startSlot) {
        for (int index = 1; index <= 9; index++) {
            int slot = startSlot + index - 1;
            CuboidRegion region = def.base(team, index);
            if (region == null) {
                inventory.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<dark_gray>" + team.display() + " 基地 #" + index, "<dark_gray>未配置"));
                continue;
            }
            holder.targets().put(slot, new DeleteTarget("base", team, index));
            inventory.setItem(slot, button(Material.RED_BANNER, "<white>" + team.display() + " 基地 #" + index,
                    "<gray>" + region.describe(),
                    "<red>点击删除",
                    "<dark_gray>等价命令：/taketori arena delbase " + team.key() + " " + index));
        }
    }

    /**
     * 按"实际存在的编号"铺开区域按钮（不是 1..9 顺序猜）。
     *
     * @return 实际铺了几个
     */
    private int fillRegions(Inventory inventory, Holder holder, Map<Integer, CuboidRegion> regions,
                            int startSlot, String label, Material material, String kind, String command) {
        int filled = 0;
        // 超过 9 个时只铺前 8 个：最后一格留给提示纸片。若铺满 9 个再用纸片覆盖第 9 格，
        // 点击映射还留着——点"还有 N 个未列出"会按映射误删第 9 个区域。
        boolean overflow = regions.size() > 9;
        int capacity = overflow ? 8 : 9;
        for (Map.Entry<Integer, CuboidRegion> entry : regions.entrySet()) {
            if (filled >= capacity) {
                break;   // 一行只有 9 格；超出部分用命令删除
            }
            int slot = startSlot + filled;
            holder.targets().put(slot, new DeleteTarget(kind, null, entry.getKey()));
            inventory.setItem(slot, button(material, "<white>" + label + " #" + entry.getKey(),
                    "<gray>" + entry.getValue().describe(),
                    "<red>点击删除",
                    "<dark_gray>等价命令：/taketori arena " + command + " " + entry.getKey()));
            filled++;
        }
        if (overflow) {
            int slot = startSlot + 8;
            holder.targets().remove(slot);   // 提示纸片不是删除按钮，点击映射必须清掉
            inventory.setItem(slot, button(Material.PAPER, "<yellow>还有 " + (regions.size() - filled) + " 个未列出",
                    "<gray>" + label + " 超过 9 个时只显示前 9 个",
                    "<gray>其余请用命令删除：<white>/taketori arena " + command + " <编号>"));
            filled = 8;
        }
        // 空位补一层黑玻璃，避免看起来像"还能点"
        for (int slot = startSlot + filled; slot < startSlot + 9; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, button(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>—"));
            }
        }
        return filled;
    }

    // ---------------------------------------------------------------- 按钮工厂

    /**
     * 直接执行型按钮：最后一行的文字被当作要执行的指令，其余行进 Lore。
     * 例如 {@code run(Material.BOOK, "对局状态", "<dark_gray>/taketori match status", "taketori match status")}。
     */
    private ItemStack run(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lore = new ArrayList<>();
            for (int i = 0; i < lines.length - 1; i++) {
                lore.add(MINI.deserialize(lines[i]));
            }
            lore.add(MINI.deserialize("<yellow>▶ 点击执行"));
            meta.lore(lore);
        });
        return item;
    }

    /** 提示补参型按钮（多行说明 + 建议指令）。 */
    private ItemStack suggest(Material material, String name, String... lines) {
        return button(material, name, lines);
    }

    /** 普通按钮：所有行都进 Lore。 */
    private ItemStack button(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lore = new ArrayList<>();
            for (String line : lines) {
                lore.add(MINI.deserialize(line));
            }
            meta.lore(lore);
        });
        return item;
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();

        if (holder.page == Page.ROOMS) {
            if (slot == 49) {
                open(player);
                return;
            }
            if (slot == 53) {
                player.closeInventory();
                return;
            }
            String roomId = holder.roomSlots.get(slot);
            if (roomId != null) {
                openRoomControl(player, roomId);
            }
            return;
        }

        if (holder.page == Page.ROOM) {
            if (slot == 49) {
                openRooms(player);
                return;
            }
            if (slot == 53) {
                player.closeInventory();
                return;
            }
            String id = holder.roomId;
            switch (slot) {
                case 10 -> execute(player, "taketori match start " + id);
                case 11 -> execute(player, "taketori match force " + id);
                case 12 -> execute(player, "taketori match stop " + id + " 管理员通过菜单结束");
                case 13 -> execute(player, "taketori match status");
                case 14 -> execute(player, "taketori match mode pve " + id);
                case 15 -> execute(player, "taketori match mode pvp " + id);
                case 16 -> execute(player, "taketori match mode sengoku_3v3 " + id);
                default -> {
                }
            }
            return;
        }

        if (holder.page == Page.DELETE) {
            if (slot == 49) {
                open(player);
                return;
            }
            if (slot == 53) {
                player.closeInventory();
                return;
            }
            DeleteTarget target = holder.targets().get(slot);
            if (target != null) {
                handleDelete(player, target);
            }
            return;
        }

        switch (slot) {
            case 10 -> openRooms(player);
            case 19 -> execute(player, "taketori arena wand");
            case 20 -> execute(player, "taketori arena list");
            case 21 -> suggest(player, "/taketori arena setbase red 1");
            case 22 -> suggest(player, "/taketori arena setminion");
            case 23 -> suggest(player, "/taketori arena setloot 1");
            case 24 -> execute(player, "taketori tags");
            case 25 -> openDelete(player);
            case 26 -> execute(player, "taketori arena clearselection");
            case 28 -> execute(player, "taketori lobby list");
            case 29 -> suggest(player, "/taketori lobby addsign join");
            case 30 -> execute(player, "taketori editor");
            case 31 -> execute(player, "taketori reload");
            case 32 -> execute(player, "taketori doctor");
            case 33 -> execute(player, "taketori debug " + (plugin.config().debug() ? "off" : "on"));
            case 34 -> execute(player, "taketori sengoku menu");
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    /** 删除页点击：按映射表执行删除，然后刷新本页（看到结果）。 */
    private void handleDelete(Player player, DeleteTarget target) {
        ArenaManager arena = plugin.arena();
        ArenaDef def = arena.selected(player.getUniqueId());
        if (def == null) {
            player.sendMessage(MINI.deserialize("<red>选中的场地已不存在，请重新 <white>/taketori arena select</white>。"));
            return;
        }
        switch (target.kind()) {
            case "base" -> {
                if (!def.clearBase(target.team(), target.index())) {
                    player.sendMessage(MINI.deserialize("<red>该基地已经不在了（页面可能过期，已刷新）。"));
                } else {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除 " + target.team().display()
                            + " 基地 #" + target.index() + "。 <gray>补划：<white>/taketori arena setbase "
                            + target.team().key() + " " + target.index()));
                }
            }
            case "minion" -> {
                if (def.clearMinionRegion(target.index())) {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除月人刷新区 #" + target.index()
                            + "。 <gray>剩余 <white>" + def.minionRegionCount() + "</white> 个"));
                }
            }
            case "loot" -> {
                if (def.clearLootRegion(target.index())) {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除道具刷新点 #" + target.index()
                            + "。 <gray>剩余 <white>" + def.lootRegionCount() + "</white> 个"));
                }
            }
            case "outpost" -> {
                def.clearOutpost();
                arena.save();
                player.sendMessage(MINI.deserialize("<green>已删除 PVE 据点位置。"
                        + " <gray>PVE 时会回落到第一个月人刷新区的中心"));
            }
            case "selection" -> {
                arena.clearSelection(player.getUniqueId());
                player.sendMessage(MINI.deserialize("<green>已清空你的选区。"));
            }
            default -> {
                return;
            }
        }
        openDelete(player);
    }

    /** 按管理员身份执行一条指令（等价于自己手敲）。 */
    private void execute(Player player, String command) {
        player.closeInventory();
        if (plugin.config().debug()) {
            plugin.getLogger().info("[admin-menu] " + player.getName() + " → /" + command);
        }
        Bukkit.dispatchCommand(player, command);
    }

    /** 给一条可点击的建议指令，补完参数回车即可执行。 */
    private void suggest(Player player, String command) {
        player.closeInventory();
        player.sendMessage(MINI.deserialize("<gray>点这里填入指令：<click:suggest_command:'" + command
                + "'><yellow><u>" + command + "</u></yellow></click> <dark_gray>（补完参数后回车执行）"));
    }

    private String describe(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "（世界不存在）";
        }
        return String.format("%s %.0f,%.0f,%.0f", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }
}
