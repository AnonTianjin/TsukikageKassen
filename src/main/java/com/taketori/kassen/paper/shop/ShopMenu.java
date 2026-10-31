package com.taketori.kassen.paper.shop;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.ShopRules;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 队伍商店界面（9×3 箱子）与它的全部事件面：右键商人打开、点按钮购买、挡住对商人的伤害。
 *
 * <p><b>槽位</b>：11 = 本队可买的道具（红队拆弹钳 / 蓝队地雷，未分队时是拆弹钳），
 * 15 = 另一件道具或状态纸，22 = 关闭。判定认 {@link Holder} 不认标题文本，
 * 与 {@code RoomShellMenu} 同一套做法——玩家把界面拖走或改名都不影响识别。</p>
 *
 * <p><b>可买性问内核要</b>：余额、队伍、限购、对局状态全部由
 * {@link GameRoom#clipperDenyReason(Player)} / {@link GameRoom#landMineDenyReason(Player)}
 * 裁决，界面只把裁决结果翻译成颜色与一行提示。早先界面按「背包里有几把」自己算一遍，
 * 内核按「买过几次」算另一遍，结果是死一次之后界面亮着可买、点了却被拒——同一套
 * {@link ShopRules} 判定，界面与闸门不可能再漂。</p>
 *
 * <p><b>文案</b>：全部走 {@code config().messages()}（键见计划 §3.6 的 {@code shop.*}），
 * 不在代码里硬编码中文——文案调整不该需要重新编译。</p>
 */
public final class ShopMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final int SIZE = 27;

    /** 主道具位：红队是拆弹钳，蓝队是地雷。 */
    private static final int SLOT_PRIMARY = 11;
    /** 副道具位：未分队时放地雷，分队后放状态纸。 */
    private static final int SLOT_SECONDARY = 15;
    /** 关闭按钮。 */
    private static final int SLOT_CLOSE = 22;

    private final TaketoriPlugin plugin;

    public ShopMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 一次打开 = 一个 {@link Holder}，只记「这个界面属于哪一队」。
     *
     * <p>不缓存玩家/余额/对局状态：这些都是会变的东西，缓存了就得维护刷新，
     * 而每次 {@link #render} 都从房间实时读取反而更简单也更准。</p>
     */
    private static final class Holder implements InventoryHolder {

        /** 打开它的商人属于哪一队；{@code null} = 未分队（/taketori shop 打开）。 */
        private final TeamId team;
        private Inventory inventory;

        private Holder(TeamId team) {
            this.team = team;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    // ---------------------------------------------------------------- 打开与绘制

    /** 打开未分队的商店（两件道具都列出来，仅供查看）。 */
    public void open(Player player) {
        open(player, null);
    }

    /**
     * 打开某队的商店。
     *
     * @param team 商人所属队伍；{@code null} 表示未分队视图
     */
    public void open(Player player, TeamId team) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Holder holder = new Holder(team);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.config().messages().get("shop.title", "team", team == null ? "道具" : team.display()));
        holder.bind(inventory);
        render(holder, player);
        player.openInventory(inventory);
    }

    /** 重画：余额、可买性、限购提示全部按当前状态重算。 */
    private void render(Holder holder, Player player) {
        Inventory inventory = holder.inventory;
        if (inventory == null) {
            return;
        }
        inventory.clear();

        GameRoom room = plugin.rooms().roomOf(player);
        boolean running = room != null && room.isRunning();
        int score = room == null ? 0 : room.playerScore(player.getUniqueId());
        // 可买性一律问内核：界面不再自己数背包、自己比上限，否则两套口径迟早漂开。
        ShopRules.DenyReason clipperDeny = running
                ? room.clipperDenyReason(player) : ShopRules.DenyReason.WRONG_TEAM;
        ShopRules.DenyReason mineDeny = running
                ? room.landMineDenyReason(player) : ShopRules.DenyReason.WRONG_TEAM;

        if (holder.team == TeamId.BLUE) {
            inventory.setItem(SLOT_PRIMARY, mineItem(running, score, mineDeny));
        } else {
            inventory.setItem(SLOT_PRIMARY, clipperItem(running, score, clipperDeny));
        }
        inventory.setItem(SLOT_SECONDARY, holder.team == null
                ? mineItem(running, score, mineDeny)
                : statusItem(running, score));
        inventory.setItem(SLOT_CLOSE, closeButton());
    }

    /** 拆弹钳：红队专属，价格 / 限购 / 拆弹耗时都来自配置。 */
    private ItemStack clipperItem(boolean running, int score, ShopRules.DenyReason deny) {
        int cost = plugin.config().defuseClipperCost();
        int max = plugin.config().defuseClipperMaxPerPlayer();
        boolean usable = running && deny == ShopRules.DenyReason.OK;

        List<Component> lore = new ArrayList<>();
        lore.addAll(loreLines("shop.clipper.lore",
                "clipper-seconds", plugin.config().bombDefuseSecondsClipper(),
                "bare-seconds", plugin.config().bombDefuseSecondsBare()));
        lore.addAll(loreLines("shop.clipper.cost", "cost", cost, "max", max));
        lore.add(stateLine(running, deny, score, cost, max));
        return item(usable ? Material.SHEARS : Material.GRAY_DYE,
                plugin.config().messages().get("shop.clipper.name"), lore);
    }

    /** 地雷：蓝队专属，杀伤 / 触发半径与限购都来自配置。 */
    private ItemStack mineItem(boolean running, int score, ShopRules.DenyReason deny) {
        int cost = plugin.config().landMineCost();
        int max = plugin.config().landMineMaxPerTeam();
        boolean usable = running && deny == ShopRules.DenyReason.OK;

        List<Component> lore = new ArrayList<>();
        lore.addAll(loreLines("shop.mine.lore",
                "kill-radius", plugin.config().landMineKillRadius(),
                "trigger-radius", plugin.config().landMineTriggerRadius()));
        lore.addAll(loreLines("shop.mine.cost", "cost", cost, "max", max));
        lore.add(stateLine(running, deny, score, cost, max));
        return item(usable ? Material.TRIPWIRE_HOOK : Material.GRAY_DYE,
                plugin.config().messages().get("shop.mine.name"), lore);
    }

    /** 状态纸：显示余额与对局状态，买不了的时候这里是最先被看到的一行。 */
    private ItemStack statusItem(boolean running, int score) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.config().messages().get("shop.hint"));
        if (!running) {
            lore.add(plugin.config().messages().get("shop.not-running"));
        }
        return item(Material.PAPER,
                plugin.config().messages().get("shop.balance", "score", score), lore);
    }

    /** 关闭按钮。 */
    private ItemStack closeButton() {
        return item(Material.BARRIER,
                plugin.config().messages().get("shop.close"),
                List.of(plugin.config().messages().get("shop.hint")));
    }

    /**
     * 一件道具的「为什么不能买」提示行。
     *
     * <p>直接按内核给的 {@link ShopRules.DenyReason} 翻译，顺序自然与购买闸门一致：
     * 先看对局、再看队伍、再看上限、最后看积分——否则玩家会看到「积分不足」，
     * 却其实是站错了队，白攒一局分。</p>
     */
    private Component stateLine(boolean running, ShopRules.DenyReason deny,
                                int score, int cost, int limit) {
        if (!running) {
            return plugin.config().messages().get("shop.not-running");
        }
        return switch (deny) {
            case WRONG_TEAM -> plugin.config().messages().get("shop.wrong-team");
            case AT_LIMIT -> plugin.config().messages().get("shop.limit", "max", limit);
            case NOT_ENOUGH_SCORE -> plugin.config().messages().get("shop.insufficient",
                    "score", score, "cost", cost);
            case OK -> plugin.config().messages().get("shop.hint");
        };
    }

    // ---------------------------------------------------------------- 物品小工具

    private static ItemStack item(Material material, Component name, List<Component> lore) {
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

    /**
     * 取一段可能写成多行的文案，按行拆成 Lore 组件。
     *
     * <p>messages.yml 里描述一长串效果时习惯写成 YAML 多行（{@code \n}），
     * 直接塞进一个 lore 元素在部分客户端会挤成一行，所以这里先替换占位符再按行切分。</p>
     */
    private List<Component> loreLines(String key, Object... placeholders) {
        String text = plugin.config().messages().plain(key, placeholders);
        List<Component> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(MINI.deserialize(line));
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------- 事件

    /**
     * 右键商人打开界面（只认主手，避免副手也触发一次导致界面闪两下）。
     *
     * <p><b>为什么接的是 {@link PlayerInteractEntityEvent} 而不是「位置精确」的那个</b>：
     * 村民的原版交互走 {@code mobInteract}，服务端只会派发普通实体交互事件；
     * 只监听 {@link PlayerInteractAtEntityEvent} 的话，右键村民根本不会进到这里，
     * 原版商人界面照开（没配交易 → 一张空表）。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        handleNpcClick(event);
    }

    /**
     * 位置精确的右键（盔甲架、展示框一类走这个事件）。
     *
     * <p>和上面那个一起接不会开两次：{@link #handleNpcClick} 发现界面已经开着时只取消。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onNpcInteractAt(PlayerInteractAtEntityEvent event) {
        handleNpcClick(event);
    }

    private void handleNpcClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ShopNpcManager npcManager = plugin.shopNpc();
        if (npcManager == null) {
            return;
        }
        TeamId team = npcManager.teamOfNpc(event.getRightClicked());
        if (team == null) {
            return;
        }
        // 取消 = 挡住原版村民交易界面；这一步必须在 open() 之前，否则原版界面会先弹出来。
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            return;   // 已经开着商店：只取消，不重开（两个事件都可能到）
        }
        open(player, team);
    }

    /** 商人不吃伤害：与 {@code setInvulnerable(true)} 是双保险。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent event) {
        ShopNpcManager npcManager = plugin.shopNpc();
        if (npcManager != null && npcManager.isShopNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 拖拽一律取消：商店界面里没有可放置的位置。 */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /**
     * 点击分发。
     *
     * <p>整张界面都取消（包括玩家自己背包那半边），否则玩家可以把道具拖进背包、
     * 或者把背包里的东西丢进商店格子——两者都会在界面关闭时变成凭空消失或凭空出现。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= SIZE) {
            return;   // 玩家自己背包里的点击：只取消，不处理
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot != SLOT_PRIMARY && slot != SLOT_SECONDARY) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            // 对局在界面开着的时候结束了：先安全关掉，再告诉玩家为什么点不动
            player.closeInventory();
            plugin.fx().actionBar(player, plugin.config().messages().get("shop.not-running"));
            return;
        }
        if (slot == SLOT_PRIMARY) {
            if (holder.team == TeamId.BLUE) {
                buyMine(player);
            } else {
                buyClipper(player);
            }
        } else if (holder.team == null) {
            // 未分队视图的副位是地雷；分队后副位是状态纸，点了不做事
            buyMine(player);
        }
    }

    /** 退出登录时若正开着商店，先关掉，别让 Holder 被一个已离线的玩家界面钉住。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            player.closeInventory();
        }
    }

    // ---------------------------------------------------------------- 购买

    private void buyClipper(Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            plugin.fx().actionBar(player, plugin.config().messages().get("shop.not-running"));
            return;
        }
        if (room.buyDefuseClipper(player)) {
            refresh(player);
        }
    }

    private void buyMine(Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            plugin.fx().actionBar(player, plugin.config().messages().get("shop.not-running"));
            return;
        }
        if (room.buyLandMine(player)) {
            refresh(player);
        }
    }

    /** 买成功后原地重画（不重开界面：重开会把光标上的物品与滚动位置一起丢掉）。 */
    private void refresh(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) {
            render(holder, player);
        }
    }
}
