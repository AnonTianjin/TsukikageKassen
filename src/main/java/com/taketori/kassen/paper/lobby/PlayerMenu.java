package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家入口菜单：把"匹配 / 队伍选择 / 角色选择"做成图形入口。
 *
 * <p>原有的告示牌（join / spectate / character …）与指令全部保留，这里是多给一条更直观的路径：
 * 玩家执行 <code>/taketori menu</code>（或点大厅里绑定 <code>menu</code> 动作的告示牌）即可打开。</p>
 *
 * <p>对应关系：</p>
 * <ul>
 *   <li><b>匹配</b> —— 等价于点「加入对局」告示牌（{@code LobbyManager#queue}），再点一次退出队列；</li>
 *   <li><b>队伍选择</b> —— 玩家自助选红队 / 蓝队（等价于管理员执行 {@code /taketori team}）；</li>
 *   <li><b>角色选择</b> —— 打开现成的 {@link CharacterMenu}；</li>
 *   <li><b>排行榜</b> —— 打开总计排行榜。</li>
 * </ul>
 */
public final class PlayerMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private enum Page {
        ENTRY,
        TEAM
    }

    /** 菜单持有者：记录当前是哪一页，避免用标题匹配。 */
    private static final class Holder implements InventoryHolder {

        private final Page page;
        private Inventory inventory;

        Holder(Page page) {
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
    /**
     * 被强制选队伍的等待者：选完角色后强制弹出队伍页，关闭页面而仍未选队伍时
     * 自动重开（choose 成功 / 离开房间时解除）。
     */
    private final Set<UUID> teamForced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PlayerMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 强制打开队伍页（CharacterMenu 选完角色后调用）。 */
    public void openTeamForced(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        teamForced.add(player.getUniqueId());
        openTeam(player);
    }

    /** 解除某玩家的选队伍强制（离开房间 / 退房）。 */
    public void cancelTeamForce(UUID uuid) {
        if (uuid != null) {
            teamForced.remove(uuid);
        }
    }

    /** 清空全部选队伍强制（reload / 停服）。 */
    public void clearTeamForced() {
        teamForced.clear();
    }

    // ---------------------------------------------------------------- 入口页

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Holder holder = new Holder(Page.ENTRY);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MINI.deserialize("<dark_gray>月影合战 <dark_gray>· <white>玩家菜单"));
        holder.bind(inventory);

        var joinedRoom = plugin.rooms().roomOf(player);
        boolean inWaitingRoom = joinedRoom != null
                && (joinedRoom.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || joinedRoom.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING);
        boolean inLiveRoom = joinedRoom != null && !inWaitingRoom;
        TeamId team = joinedRoom == null ? null : joinedRoom.teamOf(player.getUniqueId());
        var profile = plugin.config().characters().profile(player.getUniqueId());
        String character = profile.hasCharacter() ? profile.characterId() : "未选择";

        inventory.setItem(10, button(inWaitingRoom ? Material.RED_DYE
                        : (inLiveRoom ? Material.TNT : Material.NETHER_STAR),
                inWaitingRoom ? "<yellow>离开等待区（回大厅）"
                        : (inLiveRoom ? "<red>发起/确认投降表决" : "<green>降临月宫（创建/快速加入）"),
                inWaitingRoom
                        ? "<gray>房间 <white>" + joinedRoom.display()
                                + " <gray>等待中 <white>(" + joinedRoom.waitingCount()
                                + "/" + joinedRoom.maxPlayers() + ")"
                        : (inLiveRoom
                                ? "<gray>对本局不满？点击发起本队投降表决"
                                : "<gray>没有房间时自动用默认模板创建新世界并进入"),
                inWaitingRoom ? "<dark_gray>再点一次即退出房间、返回大厅"
                        : (inLiveRoom ? "<dark_gray>30 秒内半数以上在线队友同意即结束"
                                : "<dark_gray>有等待房则直接加入人最多的那个"),
                "<dark_gray>与点「加入对局」告示牌等价"));

        inventory.setItem(12, button(Material.SHIELD, "<white>队伍选择",
                "<gray>当前队伍：<white>" + (team == null ? "未分队" : team.display()),
                joinedRoom != null && joinedRoom.isPve()
                        ? "<dark_gray>该房 PVE 模式下所有人同一队"
                        : "<yellow>点击选择红队 / 蓝队",
                "<dark_gray>开局瞬间按等待名单自动均衡分队"));

        // 告示牌"旁观"与"回大厅"两个动作现在都指向本菜单，对应的按钮就在这里
        boolean audience = plugin.spectator().isAudience(player);
        inventory.setItem(11, button(audience ? Material.ENDER_EYE : Material.GLASS,
                audience ? "<yellow>退出观战" : "<gray>旁观对局",
                audience ? "<gray>你正在以观众身份观战"
                        : "<gray>以观众身份观看当前对局（不计分、不参与战局）",
                audience ? "<yellow>点击退出观战并回到大厅"
                        : "<yellow>点击进入观众模式",
                "<dark_gray>与点「旁观」告示牌 / /taketori leave 等价"));

        inventory.setItem(13, button(Material.ENDER_PEARL, "<white>回大厅",
                "<gray>传送回大厅出生点",
                "<dark_gray>与点「回大厅」告示牌等价"));

        // 角色选择：游戏外（大厅）禁止，只允许在等待区内打开（进房时本来就会强制弹出）
        if (inWaitingRoom) {
            inventory.setItem(14, button(Material.NETHER_STAR, "<white>角色选择",
                    "<gray>当前角色：<white>" + character,
                    "<yellow>点击打开角色菜单",
                    "<dark_gray>选完即绑定，装备开局发放"));
        } else {
            inventory.setItem(14, button(Material.GRAY_DYE, "<dark_gray>角色选择",
                    "<red>游戏外不可选择角色",
                    "<gray>进入等待区时会强制弹出角色菜单，必须选完才能继续等待开局"));
        }

        inventory.setItem(15, button(Material.FILLED_MAP, "<white>房间列表",
                "<gray>查看所有房间：模式 / 阶段 / 人数 / 倒计时",
                "<yellow>点击打开列表",
                "<dark_gray>等待房可加入，游戏房可旁观",
                "<dark_gray>与点「房间列表」告示牌等价"));

        inventory.setItem(16, button(Material.GOLD_INGOT, "<white>总计排行榜",
                "<gray>总积分 / 击杀 / 拆家 / 对局数…",
                "<yellow>点击查看（跨局累计）"));

        // 派对入口：无派对显示邀请组队；有派对显示房主与人数
        var party = plugin.party().partyOf(player.getUniqueId());
        if (party == null) {
            inventory.setItem(24, button(Material.PLAYER_HEAD, "<white>派对组队",
                    "<gray>邀请好友整队进同一房间，开局整组同队",
                    "<yellow>点击打开派对管理"));
        } else {
            Player leaderPlayer = Bukkit.getPlayer(party.leader());
            String leaderName = leaderPlayer != null
                    ? leaderPlayer.getName() : party.leader().toString().substring(0, 8);
            inventory.setItem(24, button(Material.PLAYER_HEAD,
                    "<gold>派对 <dark_gray>(" + party.members().size() + "/"
                            + plugin.party().maxSize() + " 人)",
                    "<gray>房主：<white>" + leaderName,
                    "<yellow>点击查看成员 / 邀请 / 退出"));
        }

        inventory.setItem(22, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));

        // 管理员额外看到一个入口（普通玩家看不到，点空位也不会执行任何东西）
        if (player.hasPermission("taketori.admin")) {
            inventory.setItem(20, button(Material.COMMAND_BLOCK, "<red>管理员菜单",
                    "<gray>对局控制 / 场地 / 大厅 / 维护",
                    "<gray>含一键<white>强制结束对局</white>",
                    "<yellow>点击打开"));
        }

        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 队伍页

    public void openTeam(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Holder holder = new Holder(Page.TEAM);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MINI.deserialize("<dark_gray>队伍选择"));
        holder.bind(inventory);

        var joinedRoom = plugin.rooms().roomOf(player);
        TeamId current = joinedRoom == null ? null : joinedRoom.teamOf(player.getUniqueId());
        boolean pve = joinedRoom != null && joinedRoom.isPve();
        int maxPerTeam = Math.max(1, plugin.config().matchTeamSize());

        inventory.setItem(11, teamButton(player, joinedRoom, TeamId.RED, Material.RED_WOOL, current, pve, maxPerTeam));
        inventory.setItem(15, teamButton(player, joinedRoom, TeamId.BLUE, Material.BLUE_WOOL, current, pve, maxPerTeam));

        var waitingRoom = plugin.rooms().roomOf(player);
        String roomState = waitingRoom == null ? "未加入"
                : waitingRoom.display() + " 等待中（你选择的队伍即开局队伍）";
        inventory.setItem(13, button(Material.PAPER, "<white>当前状态",
                "<gray>房间：<white>" + roomState,
                "<gray>模式：<white>" + (pve ? "PVE（所有人同队）" : "PVP（红队 vs 蓝队）"),
                "<dark_gray>开局不再自动分队：只能加入人数不超过对方的队伍",
                "<dark_gray>每队上限 " + maxPerTeam + " 人"));

        inventory.setItem(22, button(Material.ARROW, "<yellow>返回", "<gray>回到玩家菜单"));
        player.openInventory(inventory);
    }

    private ItemStack teamButton(Player viewer, com.taketori.kassen.paper.match.room.GameRoom room, TeamId team,
                                 Material material, TeamId current, boolean pve, int maxPerTeam) {
        int size = room == null ? 0 : room.teamPlayers(team).size();
        boolean mine = current == team;
        // 平衡/满员判定（与 GameRoom.chooseTeam 同一规则）：不可选的队伍灰色显示
        boolean available = room != null && room.canChooseTeam(viewer, team);
        boolean blocked = !pve && !mine && !available;
        ItemStack item = new ItemStack(pve || blocked ? Material.GRAY_DYE : material);
        List<Component> lore = new ArrayList<>();
        lore.add(MINI.deserialize("<gray>当前人数：<white>" + size + " / " + maxPerTeam));
        if (pve) {
            lore.add(MINI.deserialize("<dark_gray>PVE 模式下所有人都在同一队"));
            lore.add(MINI.deserialize("<yellow>点击仍然可以加入（会并到同一队）"));
        } else if (mine) {
            lore.add(MINI.deserialize("<green>你已在这个队伍"));
        } else if (blocked) {
            TeamId other = team == TeamId.RED ? TeamId.BLUE : TeamId.RED;
            if (size >= maxPerTeam) {
                lore.add(MINI.deserialize("<red>该队已满"));
            } else {
                lore.add(MINI.deserialize("<red>该队人数多于" + other.display() + "，为保持平衡不可选"));
                lore.add(MINI.deserialize("<gray>请选择" + other.display()));
            }
        } else {
            lore.add(MINI.deserialize("<yellow>点击加入 " + team.display()));
        }
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(team.colorTag() + "<bold>" + team.display() + "</bold></color>"
                    + (mine ? " <green>（当前）" : "")));
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
        int slot = event.getRawSlot();
        if (holder.page == Page.ENTRY) {
            switch (slot) {
                case 10 -> toggleQueue(player);
                case 11 -> toggleAudience(player);
                case 12 -> openTeam(player);
                case 13 -> {
                    player.closeInventory();
                    plugin.lobby().returnToLobby(player);
                }
                case 14 -> {
                    // 只有等待区内才允许打开；大厅点击（灰色按钮）无反应
                    var roomHere = plugin.rooms().roomOf(player);
                    if (roomHere != null
                            && (roomHere.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                            || roomHere.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING)) {
                        player.closeInventory();
                        plugin.characterMenu().open(player);
                    }
                }
                case 15 -> {
                    player.closeInventory();
                    plugin.roomListMenu().open(player);
                }
                case 16 -> {
                    player.closeInventory();
                    plugin.statsMenu().open(player);
                }
                case 20 -> {
                    if (player.hasPermission("taketori.admin")) {
                        plugin.adminMenu().open(player);
                    }
                }
                case 24 -> {
                    player.closeInventory();
                    plugin.partyMenu().open(player);
                }
                case 22 -> player.closeInventory();
                default -> {
                }
            }
            return;
        }
        switch (slot) {
            case 11 -> choose(player, TeamId.RED);
            case 15 -> choose(player, TeamId.BLUE);
            case 22 -> open(player);
            default -> {
            }
        }
    }

    /** 匹配按钮：无房→快速加入（无房自动建房）；等待房→退房回大厅；对局中→发起/确认投降表决。 */
    private void toggleQueue(Player player) {
        var room = plugin.rooms().roomOf(player);
        if (room != null && (room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING)) {
            player.closeInventory();
            plugin.lobby().returnToLobby(player);
            return;
        }
        if (room != null) {
            // 对局中：匹配按钮变成投降入口（发起/确认本队表决）
            if (room.isRunning() && room.teamOf(player.getUniqueId()) != null) {
                room.surrender(player);
            } else {
                player.sendMessage(MINI.deserialize("<gray>本局正在准备或结算中，暂不能投降。"));
            }
            return;
        }
        player.closeInventory();
        plugin.lobby().quickJoin(player);
    }

    /** 旁观 / 退出观战：自动选一个进行中的房间（与房间列表里选具体房间等价）。 */
    private void toggleAudience(Player player) {
        if (plugin.spectator().isAudience(player)) {
            player.closeInventory();
            plugin.spectator().leaveAudience(player);
            return;
        }
        var live = plugin.rooms().rooms().stream()
                .filter(room -> room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.CAGED
                        || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.PLAYING)
                .findFirst().orElse(null);
        if (live == null) {
            player.sendMessage(MINI.deserialize("<red>当前没有进行中的对局，无法旁观。"));
            return;
        }
        // 参赛者不许把自己切成观众：切了就回不了战场（计分/退场/重连全被拒）
        var mine = plugin.rooms().roomOf(player);
        if (mine != null && mine.teamOf(player.getUniqueId()) != null) {
            player.sendMessage(MINI.deserialize("<gray>你正在对局中，不能切换为旁观。"
                    + "要结束整局请找管理员执行 <white>/taketori match stop"));
            return;
        }
        player.closeInventory();
        plugin.spectator().enterAudience(player, live.spectatorViewPoint(), live);
        player.sendMessage(plugin.config().messages().get("room.spectating", "room", live.display()));
    }

    private void choose(Player player, TeamId team) {
        var room = plugin.rooms().roomOf(player);
        if (room == null) {
            player.sendMessage(MINI.deserialize("<red>你还没加入房间：先点「快速加入对局」或打开「房间列表」。"));
            return;
        }
        String error = room.chooseTeam(player, team);
        if (error != null) {
            player.sendMessage(MINI.deserialize("<red>" + error));
            return;
        }
        // 已选队伍：解除强制，再刷新页面（先解除再打开，避免重开触发关闭判定）
        teamForced.remove(player.getUniqueId());
        openTeam(player);
    }

    /**
     * 强制者关掉队伍页：仍在等待区且未选队伍 → 下一 tick 重开；已选/已离开则解除。
     */
    @EventHandler
    public void onTeamClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.page != Page.TEAM) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!teamForced.contains(uuid)) {
            return;
        }
        var room = plugin.rooms().roomOf(player);
        boolean waiting = room != null
                && (room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING);
        if (!waiting || room.teamOf(uuid) != null) {
            teamForced.remove(uuid);
            return;
        }
        plugin.scheduler().runLater(() -> {
            if (!teamForced.contains(uuid) || !player.isOnline()) {
                return;
            }
            var latest = plugin.rooms().roomOf(player);
            if (latest == null || latest.teamOf(uuid) != null) {
                teamForced.remove(uuid);
                return;
            }
            if (latest.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                    || latest.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING) {
                player.sendMessage(MINI.deserialize("<gold>必须选择队伍后才能继续等待开局<gray>（关闭菜单会再次弹出）"));
                openTeam(player);
            } else {
                teamForced.remove(uuid);
            }
        }, 2L);
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
