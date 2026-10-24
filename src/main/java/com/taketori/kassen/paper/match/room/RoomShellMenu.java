package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 对局功能面板（鹦鹉螺壳 GUI）：9×3 双按钮小箱 —— 左「退出对局」、右「队伍投降」。
 *
 * <p><b>状态归属</b>：一次打开 = 一个 {@link Session}（{@link InventoryHolder}），
 * 点击判定认 holder 不认标题文本，和 {@code RoomListMenu} 同一套做法。会话里还存着
 * 「退出二次确认」的到期时间与刷新任务，关界面即取消，不留残留。</p>
 *
 * <p><b>给后续开发留的口子</b></p>
 * <ul>
 *   <li>按钮槽位是常量（{@link #SLOT_LEAVE} / {@link #SLOT_SURRENDER} / {@link #SLOT_INFO}），
 *       加按钮只加常量与分支；</li>
 *   <li>{@link #render} 每次全量重画，票数、阶段、可用性都从房间实时读取，
 *       新增一行信息不需要改刷新逻辑；</li>
 *   <li>确认超时时长见 {@link #CONFIRM_WINDOW_TICKS}，想加密码 / 理由输入等
 *       再挂监听器即可，不必动本类的其余部分。</li>
 * </ul>
 */
public final class RoomShellMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final int SIZE = 27;
    /** 退出对局按钮（左）。 */
    private static final int SLOT_LEAVE = 11;
    /** 队伍投降按钮（右）。 */
    private static final int SLOT_SURRENDER = 15;
    /** 中间信息位：显示当前阶段 / 本队投降票数。 */
    private static final int SLOT_INFO = 13;

    /** 二次确认的有效时长（毫秒）：超时后按钮自己弹回初始状态。 */
    private static final long CONFIRM_WINDOW_MILLIS = 5_000L;
    private static final long REFRESH_PERIOD_TICKS = 20L;

    private final TaketoriPlugin plugin;

    public RoomShellMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 一次打开对应一个会话：持有界面、二次确认到期时间与刷新任务。 */
    private static final class Session implements InventoryHolder {

        private final UUID viewer;
        private Inventory inventory;
        private BukkitTask refreshTask;
        /** 退出确认的到期时间戳（0 = 未进入确认态）。 */
        private long leaveConfirmUntilMillis;

        private Session(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------------------------------------------------------------- 打开与刷新

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Session session = new Session(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                plugin.config().messages().get("room-shell.title"));
        session.inventory = inventory;
        render(session, player);
        player.openInventory(inventory);

        session.refreshTask = plugin.scheduler().runTimerTask(() -> {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer == null || !viewer.isOnline()
                    || !isViewing(viewer, session)) {
                cancel(session);
                return;
            }
            // 确认超时自动弹回，避免玩家忘了自己处在「再点一次就退出」的状态
            if (session.leaveConfirmUntilMillis > 0
                    && System.currentTimeMillis() > session.leaveConfirmUntilMillis) {
                session.leaveConfirmUntilMillis = 0;
            }
            render(session, viewer);
        }, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
    }

    private boolean isViewing(Player viewer, Session session) {
        return viewer.getOpenInventory().getTopInventory().getHolder() instanceof Session open
                && open == session;
    }

    private void cancel(Session session) {
        if (session.refreshTask != null) {
            session.refreshTask.cancel();
            session.refreshTask = null;
        }
    }

    // ---------------------------------------------------------------- 绘制

    private void render(Session session, Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        Inventory inv = session.inventory;
        inv.clear();

        // 背景：留出按钮位，其余填灰色玻璃板，视觉上把两个按钮隔开
        ItemStack filler = simple(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler);
        }

        boolean inRoom = room != null;
        boolean inMatch = inRoom && (room.phase() == GameRoom.Phase.CAGED
                || room.phase() == GameRoom.Phase.PLAYING);
        boolean waiting = inRoom && (room.phase() == GameRoom.Phase.WAITING
                || room.phase() == GameRoom.Phase.STARTING);
        TeamId team = inRoom ? room.teamOf(player.getUniqueId()) : null;

        inv.setItem(SLOT_LEAVE, leaveButton(session, waiting, inMatch));
        inv.setItem(SLOT_SURRENDER, surrenderButton(room, team));
        inv.setItem(SLOT_INFO, infoButton(room, team, waiting, inMatch));
    }

    /** 退出按钮：等待期是「退出房间」，对局中需二次确认后变为「确认退出」。 */
    private ItemStack leaveButton(Session session, boolean waiting, boolean inMatch) {
        if (!waiting && !inMatch) {
            // 结算阶段：不需要手动退出，稍候会自动回大厅
            return named(Material.BARRIER,
                    plugin.config().messages().get("room-shell.leave-unavailable"),
                    List.of(plugin.config().messages().get("room-shell.leave-unavailable-lore")));
        }
        boolean confirming = session.leaveConfirmUntilMillis > 0
                && System.currentTimeMillis() <= session.leaveConfirmUntilMillis;
        if (!confirming) {
            return named(Material.LIME_DYE,
                    plugin.config().messages().get(waiting
                            ? "room-shell.leave-room" : "room-shell.leave-match"),
                    List.of(plugin.config().messages().get(waiting
                            ? "room-shell.leave-room-lore" : "room-shell.leave-match-lore")));
        }
        return named(Material.RED_TERRACOTTA,
                plugin.config().messages().get("room-shell.leave-confirm"),
                List.of(plugin.config().messages().get("room-shell.leave-confirm-lore")));
    }

    /**
     * 投降按钮：只在<b>正式战斗</b>中可用，并实时显示本队票数。
     *
     * <p>可用条件必须与 {@link GameRoom#surrender} 的受理条件一致（{@code phase == PLAYING}）：
     * CAGED（玻璃笼无敌冻结）阶段 {@code surrender} 会静默 return，若这里仍渲染成可用红底，
     * 玩家点了没反应只会以为界面坏了。</p>
     */
    private ItemStack surrenderButton(GameRoom room, TeamId team) {
        if (room == null || team == null || room.phase() != GameRoom.Phase.PLAYING) {
            return named(Material.GRAY_DYE,
                    plugin.config().messages().get("room-shell.surrender-unavailable"),
                    List.of(plugin.config().messages().get("room-shell.surrender-unavailable-lore")));
        }
        GameRoom.SurrenderStatus status = room.surrenderStatus(team);
        List<Component> lore = new ArrayList<>();
        if (status == null) {
            lore.add(plugin.config().messages().get("room-shell.surrender-lore"));
        } else {
            lore.add(plugin.config().messages().get("room-shell.surrender-progress",
                    "confirmed", status.confirmed(),
                    "need", status.need(),
                    "seconds", status.remainingSeconds()));
        }
        return named(Material.RED_BANNER,
                plugin.config().messages().get("room-shell.surrender"), lore);
    }

    /** 中间信息位：阶段 + 本队投降票数（无表决时不显示票数行）。 */
    private ItemStack infoButton(GameRoom room, TeamId team, boolean waiting, boolean inMatch) {
        List<Component> lore = new ArrayList<>();
        if (room == null) {
            lore.add(plugin.config().messages().get("room-shell.info-outside"));
        } else {
            lore.add(plugin.config().messages().get("room-shell.info-phase",
                    "phase", room.phase().name()));
            if (team != null) {
                lore.add(plugin.config().messages().get("room-shell.info-team",
                        "team", team.display()));
                GameRoom.SurrenderStatus status = room.surrenderStatus(team);
                if (status != null && inMatch) {
                    lore.add(plugin.config().messages().get("room-shell.surrender-progress",
                            "confirmed", status.confirmed(),
                            "need", status.need(),
                            "seconds", status.remainingSeconds()));
                }
            }
        }
        Material icon = waiting ? Material.CLOCK : (inMatch ? Material.SHIELD : Material.PAPER);
        return named(icon, plugin.config().messages().get("room-shell.info"), lore);
    }

    // ---------------------------------------------------------------- 点击

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!session.viewer.equals(player.getUniqueId())) {
            return;   // 只认打开这个界面的玩家
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_LEAVE) {
            handleLeaveClick(session, player);
        } else if (slot == SLOT_SURRENDER) {
            handleSurrenderClick(player);
        }
        render(session, player);
    }

    /** 退出：等待期直接退房；对局中需二次确认。 */
    private void handleLeaveClick(Session session, Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            return;
        }
        boolean waiting = room.phase() == GameRoom.Phase.WAITING
                || room.phase() == GameRoom.Phase.STARTING;
        if (waiting) {
            player.closeInventory();
            plugin.lobby().returnToLobby(player);
            return;
        }
        boolean inMatch = room.phase() == GameRoom.Phase.CAGED
                || room.phase() == GameRoom.Phase.PLAYING;
        if (!inMatch) {
            return;
        }
        boolean confirming = session.leaveConfirmUntilMillis > 0
                && System.currentTimeMillis() <= session.leaveConfirmUntilMillis;
        if (!confirming) {
            // 第一下：进入确认态，等玩家再点一次
            session.leaveConfirmUntilMillis = System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS;
            return;
        }
        session.leaveConfirmUntilMillis = 0;
        player.closeInventory();
        plugin.lobby().leaveMatch(player);
    }

    /** 投降：与 /taketori surrender 完全同义（首次发起、之后记票）。 */
    private void handleSurrenderClick(Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || room.teamOf(player.getUniqueId()) == null) {
            return;
        }
        room.surrender(player);
    }

    // ---------------------------------------------------------------- 物品小工具

    private static ItemStack simple(Material material, Component name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            List<Component> clean = new ArrayList<>();
            for (Component line : lore) {
                if (line != null) {
                    clean.add(line);
                }
            }
            if (!clean.isEmpty()) {
                meta.lore(clean);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ---------------------------------------------------------------- 关闭清理

    /**
     * 关闭界面立即取消刷新任务。
     *
     * <p>刷新闭包里的 {@code isViewing} 检查也能在下一 tick 兜底取消，但那是"最多晚一拍"；
     * 显式监听关闭事件让取消变成确定性的，不再依赖下一次 tick 到来。</p>
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            cancel(session);
        }
    }
}
