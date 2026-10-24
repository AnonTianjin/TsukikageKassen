package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.room.RoomCreateRequest;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建房间配置 GUI：选地图模板（moonmaps） + 选游戏模式（PVP / PVE / 战国 3v3），确认后创建。
 *
 * <p>打开方式：{@code /taketori room create}（无参数），或在房间列表 GUI 里点「降临月宫」。</p>
 *
 * <p>同时支持命令行直传参数（{@code /taketori room create <模板> [模式]}），
 * 此时不打开 GUI。</p>
 *
 * <p><b>扩展点</b>：本 GUI 的状态收拢在 {@link Session} 里（模板 + 模式），将来加
 * 观战开关、密码输入等选项时，只需在本类追加按钮与 Session 字段，
 * 创建请求 {@link RoomCreateRequest} 也一并扩展。</p>
 */
public final class RoomCreateMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SIZE = 54;

    /** 模板图标布局：三行 9 列（0-26）。 */
    private static final int TEMPLATE_START = 0;
    private static final int TEMPLATE_END = 26;

    /** 模式选择按钮槽位。 */
    private static final int SLOT_MODE_PVP = 37;
    private static final int SLOT_MODE_PVE = 38;
    private static final int SLOT_MODE_SENGOKU = 39;

    /** 固定按钮。 */
    private static final int SLOT_CREATE = 49;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    private final TaketoriPlugin plugin;

    public RoomCreateMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 会话

    /** 一次打开对应一个会话：持有界面与当前选择。 */
    private static final class Session implements InventoryHolder {

        private final List<ArenaDef> templates;
        /** 当前选中的模板索引（-1 = 未选）。 */
        private int selectedTemplate = -1;
        /** 当前选中的模式（null = 未选）。 */
        private SengokuMode selectedMode;
        private Inventory inventory;

        Session(List<ArenaDef> templates) {
            this.templates = templates;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------------------------------------------------------------- 打开

    /**
     * 打开创建房间配置 GUI。
     *
     * <p>模板列表取自启用且就绪的场地，并且 moonmaps 目录必须存在
     * （有 level.dat 的世界文件夹）。</p>
     */
    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        File moonmapsDir = new File(plugin.getDataFolder(), "moonmaps");
        List<ArenaDef> ready = new ArrayList<>();
        for (ArenaDef def : plugin.arena().enabledArenas().values()) {
            if (!def.isReady()) {
                continue;
            }
            File worldDir = new File(moonmapsDir, def.id());
            if (worldDir.isDirectory() && new File(worldDir, "level.dat").isFile()) {
                ready.add(def);
            }
        }
        if (ready.isEmpty()) {
            player.sendMessage(MINI.deserialize(
                    "<red>目前没有任何可用的地图模板。"
                            + "<gray>请先将世界文件夹放入 <white>plugins/TsukikageKassen/moonmaps/&lt;模板名&gt;/</white>"
                            + " 并用 <white>/taketori arena setup</white> 划定场地。"));
            return;
        }

        Session session = new Session(ready);
        // 默认选中第一个模板
        session.selectedTemplate = 0;
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                MINI.deserialize("<dark_red>月影合战 <dark_gray>· <white>创建房间"));
        session.bind(inventory);
        render(session);
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 渲染

    private void render(Session session) {
        Inventory inventory = session.getInventory();
        inventory.clear();

        // ---- 模板列表（0-26） ----
        int slot = TEMPLATE_START;
        for (int i = 0; i < session.templates.size() && slot <= TEMPLATE_END; i++, slot++) {
            ArenaDef def = session.templates.get(i);
            boolean selected = i == session.selectedTemplate;
            inventory.setItem(slot, templateIcon(def, selected, i));
        }
        // 剩余格子放灰玻璃
        for (; slot <= TEMPLATE_END; slot++) {
            inventory.setItem(slot, glass(Material.BLACK_STAINED_GLASS_PANE));
        }

        // ---- 模式选择（37-39） ----
        inventory.setItem(SLOT_MODE_PVP, modeIcon(SengokuMode.PVP, session.selectedMode,
                "<red>PVP <dark_gray>· <white>3v3 积分赛",
                "<gray>红蓝对抗，击杀 + 拆基地得分",
                "<gray>先到 600 分或时限内分高者胜"));
        inventory.setItem(SLOT_MODE_PVE, modeIcon(SengokuMode.PVE, session.selectedMode,
                "<blue>PVE <dark_gray>· <white>月人入侵",
                "<gray>全体同队，合作防守据点",
                "<gray>顶住大波次月人进攻"));
        inventory.setItem(SLOT_MODE_SENGOKU, modeIcon(SengokuMode.SENGOKU_3V3, session.selectedMode,
                "<gold>战国 3v3 <dark_gray>· <white>三局两胜",
                "<gray>清守卫 → 敲钟 → 占箭楼 → 击破器攻陷天守阁",
                "<gray>先赢两小局赢整场"));

        // ---- 信息行（第 4 行：28-34） ----
        String info = session.selectedTemplate >= 0
                ? "<gray>地图：<white>" + session.templates.get(session.selectedTemplate).display()
                + "</white> <dark_gray>｜</dark_gray> "
                + "<gray>模式：<white>" + (session.selectedMode != null
                        ? modeDisplay(session.selectedMode) : "<dark_gray>未选择</dark_gray>")
                : "<yellow>选择一个地图模板";
        inventory.setItem(31, item(Material.PAPER,
                MINI.deserialize("<gold>创建房间配置"),
                MINI.deserialize(info)));

        // ---- 底部按钮 ----
        boolean canCreate = session.selectedTemplate >= 0 && session.selectedMode != null;
        inventory.setItem(SLOT_CREATE, item(
                canCreate ? Material.NETHER_STAR : Material.GRAY_DYE,
                MINI.deserialize(canCreate
                        ? "<gold><bold>▶ 创建房间</bold></gold>"
                        : "<dark_gray>▶ 创建房间</dark_gray>"),
                canCreate
                        ? MINI.deserialize("<green>地图与模式已选定，点击创建")
                        : MINI.deserialize("<dark_gray>请先选择地图模板与游戏模式")));

        inventory.setItem(SLOT_BACK, item(Material.ARROW,
                MINI.deserialize("<yellow>返回房间列表"),
                MINI.deserialize("<gray>不创建，回到列表")));
        inventory.setItem(SLOT_CLOSE, item(Material.BARRIER,
                MINI.deserialize("<red>关闭"),
                MINI.deserialize("<gray>点一下关闭菜单")));
    }

    // ---------------------------------------------------------------- 图标

    private ItemStack templateIcon(ArenaDef def, boolean selected, int index) {
        Material material = selected ? Material.LIME_STAINED_GLASS_PANE
                : Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        List<Component> lore = new ArrayList<>();
        lore.add(MINI.deserialize("<gray>模板 <white>" + def.id()));
        lore.add(MINI.deserialize("<gray>世界 " + (def.worldName() != null
                ? "<white>" + def.worldName() : "<dark_gray>未绑定")));
        String readyHint = def.isReady() ? "<green>✓ 场地已就绪" : "<red>✗ 场地未就绪：" + def.missingHint();
        lore.add(MINI.deserialize(readyHint));
        if (selected) {
            lore.add(MINI.deserialize("<yellow>▶ 已选中"));
        } else {
            lore.add(MINI.deserialize("<gray>点击选择此地图"));
        }
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize((selected ? "<green><bold>" : "<white>")
                    + def.display() + "</bold>" + (selected ? "</green>" : "</white>")));
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack modeIcon(SengokuMode mode, SengokuMode current,
                               String name, String... description) {
        boolean selected = mode == current;
        Material material = switch (mode) {
            case PVP -> selected ? Material.RED_CONCRETE : Material.RED_STAINED_GLASS;
            case PVE -> selected ? Material.BLUE_CONCRETE : Material.BLUE_STAINED_GLASS;
            case SENGOKU_3V3 -> selected ? Material.CROSSBOW : Material.BOW;
        };
        List<Component> lore = new ArrayList<>();
        for (String line : description) {
            lore.add(MINI.deserialize(line));
        }
        if (selected) {
            lore.add(MINI.deserialize("<yellow>▶ 已选中"));
        } else {
            lore.add(MINI.deserialize("<gray>点击选择此模式"));
        }
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack glass(Material material) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.empty()));
        return item;
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

    private String modeDisplay(SengokuMode mode) {
        return switch (mode) {
            case PVP -> "PVP 3v3 积分赛";
            case PVE -> "PVE 月人入侵";
            case SENGOKU_3V3 -> "战国 3v3 三局两胜";
        };
    }

    // ---------------------------------------------------------------- 点击

    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        // 模板卡片
        if (slot >= TEMPLATE_START && slot <= TEMPLATE_END) {
            int index = slot - TEMPLATE_START;
            if (index < session.templates.size()) {
                session.selectedTemplate = index;
                render(session);
            }
            return;
        }

        // 模式按钮
        switch (slot) {
            case SLOT_MODE_PVP -> {
                session.selectedMode = SengokuMode.PVP;
                render(session);
            }
            case SLOT_MODE_PVE -> {
                session.selectedMode = SengokuMode.PVE;
                render(session);
            }
            case SLOT_MODE_SENGOKU -> {
                session.selectedMode = SengokuMode.SENGOKU_3V3;
                render(session);
            }
            case SLOT_CREATE -> {
                if (session.selectedTemplate < 0 || session.selectedMode == null) {
                    return;
                }
                ArenaDef def = session.templates.get(session.selectedTemplate);
                player.closeInventory();
                RoomCreateRequest request = RoomCreateRequest.of(def.id(), session.selectedMode);
                plugin.rooms().createRoom(player, request);
            }
            case SLOT_BACK -> {
                player.closeInventory();
                plugin.roomListMenu().open(player);
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // 关闭即销毁会话，无后台任务需取消
    }
}