package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 房间列表 GUI（BedWars 式 room browser）：大厅玩家一眼看全所有并发房间。
 *
 * <p>每个房间一个图标，实时显示模式 / 阶段 / 倒计时或对局剩余时间 / 人数：
 * <ul>
 *   <li>等待、倒计时中的房间：点击加入（满员置灰，走 {@code room.room-full} 文案）；</li>
 *   <li>玻璃笼、游戏中的房间：点击以观众身份旁观该房间（传送点取该房间观战点）；</li>
 *   <li>结算中：仅展示，点击给"正在结算"提示；场地未就绪：显示缺什么，不可点。</li>
 * </ul>
 * 打开期间每 2 秒自动重绘；底部有快速加入 / 立即刷新 / 回大厅三个按钮。
 * 点击归属用 {@link Session}（InventoryHolder）判定，不依赖标题文本。</p>
 *
 * <p>每次打开生成一个独立 {@link Session}，周期刷新任务挂在会话上，
 * 关闭界面 / 玩家掉线 / 切到别的界面都会取消任务，不留残留。</p>
 */
public final class RoomListMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final int SIZE = 54;
    /** 房间图标占用 0-44；底部与侧边的固定按钮。 */
    private static final int SLOT_QUICK_JOIN = 48;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_LOBBY = 50;
    private static final int SLOT_CREATE = 45;
    private static final int SLOT_DELETE = 46;
    private static final long REFRESH_PERIOD_TICKS = 40L;

    private final TaketoriPlugin plugin;

    public RoomListMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 一次打开对应一个会话：持有界面、槽位→房间 id 映射与刷新任务。 */
    private static final class Session implements InventoryHolder {

        private final UUID viewer;
        private final Map<Integer, String> roomSlots = new HashMap<>();
        private Inventory inventory;
        private BukkitTask refreshTask;

        private Session(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------------------------------------------------------------- 打开 / 刷新

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Session session = new Session(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                plugin.config().messages().get("room.list-title"));
        session.inventory = inventory;
        render(session);
        player.openInventory(inventory);

        session.refreshTask = plugin.scheduler().runTimerTask(() -> {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer == null || !viewer.isOnline()
                    || !(viewer.getOpenInventory().getTopInventory().getHolder() instanceof Session open)
                    || open != session) {
                // 玩家掉线 / 已关闭 / 被别的界面顶替：结束本会话的刷新
                cancel(session);
                return;
            }
            render(session);
        }, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
    }

    /** 用 RoomManager 的实时数据重绘全部图标（不清任务，刷新中可反复调用）。 */
    private void render(Session session) {
        session.roomSlots.clear();
        Inventory inventory = session.inventory;
        inventory.clear();

        int slot = 0;
        boolean anyRoom = false;
        for (GameRoom room : plugin.rooms().rooms()) {
            anyRoom = true;
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot, roomIcon(room));
            session.roomSlots.put(slot, room.id());
            slot++;
        }
        if (!anyRoom) {
            // 无房间：只启用创建——中央给提示，加入/旁观无从谈起
            inventory.setItem(22, item(Material.GRASS_BLOCK,
                    MINI.deserialize("<gold>月宫空空如也"),
                    MINI.deserialize("<gray>还没有任何房间。"),
                    MINI.deserialize("<yellow>点下方「降临月宫」创建第一场合战！")));
        }

        inventory.setItem(SLOT_CREATE, item(Material.NETHER_STAR,
                MINI.deserialize("<gold>降临月宫（创建房间）"),
                MINI.deserialize("<gray>从月宫复制一份新的合战世界"),
                MINI.deserialize("<gray>创建后你会直接进入等待区"),
                MINI.deserialize("<dark_gray>也可用 /taketori room create [模板]")));
        Player viewer = Bukkit.getPlayer(session.viewer);
        GameRoom mine = viewer == null ? null : plugin.rooms().roomOf(viewer);
        boolean canDelete = mine != null
                && mine.phase() == GameRoom.Phase.WAITING
                && (viewer.getUniqueId().equals(mine.creatorId()) || viewer.hasPermission("taketori.admin"));
        inventory.setItem(SLOT_DELETE, item(canDelete ? Material.TNT : Material.GRAY_DYE,
                MINI.deserialize("<red>删除房间"),
                canDelete
                        ? MINI.deserialize("<gray>删除你所在的等待中房间 <white>" + mine.display())
                        : MINI.deserialize("<gray>仅房主或管理员可删除"),
                MINI.deserialize("<gray>仅等待中的房间可删除"),
                MINI.deserialize("<dark_gray>删除后世界立即回收，房内玩家回大厅")));
        inventory.setItem(SLOT_QUICK_JOIN, item(Material.LIME_DYE,
                msg("room.list-quick-join"),
                msg("room.list-quick-join-lore")));
        inventory.setItem(SLOT_REFRESH, item(Material.CLOCK,
                MINI.deserialize("<yellow>立即刷新"),
                MINI.deserialize("<gray>列表每 2 秒自动刷新"),
                MINI.deserialize("<dark_gray>人数与倒计时以服务器实时数据为准")));
        inventory.setItem(SLOT_LOBBY, item(Material.ENDER_PEARL,
                msg("room.list-leave"),
                MINI.deserialize("<gray>关闭列表并传送回大厅出生点")));
    }

    private void cancel(Session session) {
        if (session.refreshTask != null) {
            session.refreshTask.cancel();
            session.refreshTask = null;
        }
    }

    // ---------------------------------------------------------------- 图标

    private ItemStack roomIcon(GameRoom room) {
        boolean ready = room.arena().isReady();
        GameRoom.Phase phase = room.phase();
        boolean waiting = phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING;
        boolean full = waiting && room.waitingCount() >= room.maxPlayers();

        Material material;
        if (!ready) {
            material = Material.BARRIER;
        } else if (phase == GameRoom.Phase.WAITING) {
            material = full ? Material.GRAY_DYE : Material.LIME_WOOL;
        } else if (phase == GameRoom.Phase.STARTING) {
            material = full ? Material.GRAY_DYE : Material.YELLOW_WOOL;
        } else if (phase == GameRoom.Phase.CAGED) {
            material = Material.MAGENTA_WOOL;
        } else if (phase == GameRoom.Phase.PLAYING) {
            material = Material.RED_WOOL;
        } else {
            material = Material.GRAY_WOOL;
        }

        List<Component> lore = new ArrayList<>();
        lore.add(msg(room.isPve() ? "room.list-mode-pve" : "room.list-mode-pvp"));
        if (!ready) {
            lore.add(msg("room.list-not-ready", "missing", room.arena().missingHint()));
            lore.add(MINI.deserialize("<dark_gray>请管理员补齐场地配置"));
        } else {
            int count = waiting ? room.waitingCount() : room.onlineParticipantCount();
            lore.add(msg("room.list-players", "count", count, "max", room.maxPlayers()));
            lore.add(statusLine(room));
            Component hint = clickLine(room, full);
            if (hint != null) {
                lore.add(Component.empty());
                lore.add(hint);
            }
        }

        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<white><bold>" + room.display() + "</bold></white>"));
            meta.lore(lore);
        });
        return item;
    }

    private Component statusLine(GameRoom room) {
        return switch (room.phase()) {
            case WAITING -> msg("room.list-status-waiting");
            case STARTING -> msg("room.list-status-starting", "seconds", room.countdownSeconds());
            case CAGED -> msg("room.list-status-caged");
            case PLAYING -> msg("room.list-status-playing", "time", room.remainingText());
            case ENDING -> msg("room.list-status-ending");
        };
    }

    /** 图标最下方的点击提示；不可点（满员/结算）时返回满员文案或 null。 */
    private Component clickLine(GameRoom room, boolean full) {
        return switch (room.phase()) {
            case WAITING, STARTING -> full
                    ? msg("room.list-full")
                    : msg("room.list-click-join");
            case CAGED, PLAYING -> msg("room.list-click-spectate");
            case ENDING -> null;
        };
    }

    // ---------------------------------------------------------------- 点击

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        String roomId = session.roomSlots.get(rawSlot);
        if (roomId != null) {
            clickRoom(player, roomId);
            return;
        }
        // 只有点顶部界面的固定槽位才生效，点自己背包无动作
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        switch (rawSlot) {
            case SLOT_CREATE -> {
                player.closeInventory();
                plugin.roomCreateMenu().open(player);
            }
            case SLOT_DELETE -> {
                GameRoom mine = plugin.rooms().roomOf(player);
                if (mine == null) {
                    player.sendMessage(MINI.deserialize("<gray>你当前不在任何房间里。"));
                    return;
                }
                player.closeInventory();
                plugin.lobby().deleteRoom(player, mine.id());
            }
            case SLOT_QUICK_JOIN -> {
                player.closeInventory();
                plugin.lobby().quickJoin(player);
            }
            case SLOT_REFRESH -> render(session);
            case SLOT_LOBBY -> {
                player.closeInventory();
                plugin.lobby().returnToLobby(player);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            cancel(session);
        }
    }

    /** 点房间图标：等待/倒计时→加入；笼内/游戏中→旁观；结算中→提示。 */
    private void clickRoom(Player player, String roomId) {
        GameRoom room = plugin.rooms().room(roomId);
        if (room == null) {
            return;
        }
        if (!room.arena().isReady()) {
            player.sendMessage(msg("room.list-not-ready", "missing", room.arena().missingHint()));
            return;
        }
        GameRoom.Phase phase = room.phase();
        if (phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING) {
            if (!room.isJoinable() && plugin.rooms().roomOf(player) != room) {
                player.sendMessage(msg("room.room-full", "room", room.display()));
                return;
            }
            // 加入/换房的文案与传送全部走 LobbyManager 统一入口
            player.closeInventory();
            plugin.lobby().joinRoom(player, roomId);
            return;
        }
        if (phase == GameRoom.Phase.CAGED || phase == GameRoom.Phase.PLAYING) {
            // 有缺口的对局优先补位（掉线不再 3v2 打到底）；否则以观众身份旁观
            if (phase == GameRoom.Phase.PLAYING && room.reinforcementTeam() != null
                    && plugin.rooms().roomOf(player) == null) {
                player.closeInventory();
                plugin.lobby().joinRoom(player, roomId);
                return;
            }
            GameRoom current = plugin.rooms().roomOf(player);
            if (current != null && !plugin.spectator().isSpectator(player)) {
                player.sendMessage(MINI.deserialize("<red>你已经在房间 <white>" + current.display()
                        + "</white> 中参赛，不能同时旁观别的房间。"));
                return;
            }
            player.closeInventory();
            plugin.spectator().enterAudience(player, room.spectatorViewPoint(), room);
            player.sendMessage(msg("room.spectating", "room", room.display()));
            return;
        }
        player.sendMessage(MINI.deserialize("<gray>该房间正在结算，很快会回到等待状态，稍后再试。"));
    }

    // ---------------------------------------------------------------- 小工具

    private Component msg(String key, Object... placeholders) {
        return plugin.config().messages().get(key, placeholders);
    }

    private ItemStack item(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            if (lore.length > 0) {
                meta.lore(List.of(lore));
            }
        });
        return item;
    }
}
