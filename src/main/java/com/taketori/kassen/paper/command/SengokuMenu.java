package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 战国 3v3 管理菜单：把 {@code /taketori sengoku} 那组命令映射成按钮。
 *
 * <p>分两类按钮（与 {@link AdminMenu} 同一套做法，维护时不用学第二套心智）：</p>
 * <ul>
 *   <li><b>直接执行</b>（{@link #run}）：无参数或参数固定的，点击即按管理员身份执行，
 *       等价于自己手敲；</li>
 *   <li><b>提示补参</b>（{@link #suggest}）：需要编号或可选参数的，点击把指令填进聊天框，
 *       补完参数回车。</li>
 * </ul>
 *
 * <p>两类都靠按钮上的 <b>PDC 标记</b>区分（而不是去解析 Lore 文本）——Lore 是给人看的，
 * 随时会改文案；把它当控制流用，改一次措辞就会悄悄点错按钮。</p>
 *
 * <p>划区按钮都在「当前选区 / 当前位置」上生效，所以标题里带选区状态——
 * 否则管理员点完按钮不知道它到底划到哪儿去了。</p>
 *
 * <p>打开方式：{@code /taketori sengoku menu}，或在管理员菜单里点「战国 3v3」。</p>
 */
public final class SengokuMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 菜单持有者：避免用标题匹配识别界面。 */
    private static final class Holder implements InventoryHolder {
        /** 是否是删除页（决定"返回"按钮回哪一页）。 */
        private final boolean deletePage;
        private Inventory inventory;

        Holder(boolean deletePage) {
            this.deletePage = deletePage;
        }

        void bind(Inventory value) {
            this.inventory = value;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;
    /** 直接执行型按钮：值为要执行的指令（不含前导斜杠）。 */
    private final NamespacedKey runKey;
    /** 提示补参型按钮：值为要填入聊天框的指令。 */
    private final NamespacedKey suggestKey;

    public SengokuMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.runKey = new NamespacedKey(plugin, "sengoku_menu_run");
        this.suggestKey = new NamespacedKey(plugin, "sengoku_menu_suggest");
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
        Holder holder = new Holder(false);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>月影合战 <dark_gray>· <white>战国 3v3"));
        holder.bind(inventory);

        inventory.setItem(4, button(Material.NETHER_STAR, "<gold>战国 3v3 管理",
                roomLine(player),
                "<dark_gray>全局模式：<white>" + plugin.config().matchMode().key(),
                "<gray>划区按钮作用在<white>当前选区 / 当前位置</white>上",
                "<dark_gray>选区：<white>" + plugin.arena().selectionStatus(player.getUniqueId())));

        // ---- 对局控制 ----
        inventory.setItem(10, run(Material.LIME_DYE, "<green>强制开局",
                "<gray>等价 <white>/taketori sengoku start",
                "taketori sengoku start"));
        inventory.setItem(11, run(Material.YELLOW_DYE, "<yellow>暂停计时",
                "<gray>暂停期间小局计时停走，继续时不吃掉这段时间",
                "taketori sengoku pause"));
        inventory.setItem(12, run(Material.LIME_CONCRETE, "<green>继续计时",
                "<dark_gray>/taketori sengoku resume",
                "taketori sengoku resume"));
        inventory.setItem(13, suggest(Material.RED_DYE, "<red>强制结束本小局",
                "<gray>不补参数 = 判平局重开",
                "<gray>也可写 <white>endround red</white> / <white>endround blue</white>",
                "taketori sengoku endround "));

        // ---- 划区：参数固定，点一下直接划 ----
        inventory.setItem(19, run(Material.RED_CONCRETE, "<red>划红队天守阁",
                "<gray>用当前选区作为区域",
                "taketori sengoku setkeep red"));
        inventory.setItem(20, run(Material.BLUE_CONCRETE, "<blue>划蓝队天守阁",
                "<gray>用当前选区作为区域",
                "taketori sengoku setkeep blue"));
        inventory.setItem(21, run(Material.RED_STAINED_GLASS, "<red>红队天守阁门前点",
                "<gray>用你站的位置（击破器与跳跃台的生成处）",
                "taketori sengoku setkeepdoor red"));
        inventory.setItem(22, run(Material.BLUE_STAINED_GLASS, "<blue>蓝队天守阁门前点",
                "<gray>用你站的位置",
                "taketori sengoku setkeepdoor blue"));
        inventory.setItem(23, run(Material.HONEY_BLOCK, "<yellow>红队跳跃台",
                "<gray>用你站的位置（占领箭楼后在此激活）",
                "taketori sengoku setjumppad red"));
        inventory.setItem(24, run(Material.SLIME_BLOCK, "<yellow>蓝队跳跃台",
                "<gray>用你站的位置",
                "taketori sengoku setjumppad blue"));
        inventory.setItem(25, run(Material.ZOMBIE_HEAD, "<green>划中地小兵区",
                "<gray>用当前选区；可多次划，自动编号",
                "taketori sengoku setmid"));

        // ---- 划区：需要序号，点击填入指令 ----
        inventory.setItem(28, suggest(Material.STONE_BRICKS, "<white>划箭楼占领区",
                "<gray>用当前选区；序号从 1 开始，上下路各一",
                "taketori sengoku settower 1"));
        inventory.setItem(29, suggest(Material.BELL, "<gold>设铜钟位置",
                "<yellow>对着铜钟方块执行（6 格内看着它）",
                "<dark_gray>存的是方块坐标，不是你的站位",
                "taketori sengoku setbell 1"));
        inventory.setItem(30, suggest(Material.HUSK_SPAWN_EGG, "<white>设守卫刷新点",
                "<gray>用你站的位置；与箭楼同一序号",
                "taketori sengoku setguard 1"));

        // ---- 选区与检查 ----
        inventory.setItem(31, run(Material.GOLDEN_HOE, "<white>清空我的选区",
                "<dark_gray>清掉 pos1 / pos2，重新划",
                "taketori arena clearselection"));
        inventory.setItem(32, run(Material.KNOWLEDGE_BOOK, "<yellow>检查点位是否齐全",
                "<gray>列出还缺哪几项",
                "taketori sengoku check"));

        // ---- 状态 ----
        inventory.setItem(33, run(Material.COMPASS, "<aqua>箭楼归属与读条",
                "<dark_gray>/taketori sengoku towers", "taketori sengoku towers"));
        inventory.setItem(34, run(Material.CLOCK, "<aqua>比分与各人能量",
                "<dark_gray>/taketori sengoku score", "taketori sengoku score"));

        // ---- 模式切换 ----
        SengokuMode current = plugin.config().matchMode();
        inventory.setItem(37, run(Material.IRON_SWORD,
                (current == SengokuMode.PVP ? "<green>" : "<gray>") + "全局 → PVP",
                "<gray>红蓝对抗积分赛",
                "<dark_gray>写入 config.yml，只影响之后新建的房间",
                "taketori sengoku mode pvp"));
        inventory.setItem(38, run(Material.ROTTEN_FLESH,
                (current == SengokuMode.PVE ? "<green>" : "<gray>") + "全局 → PVE",
                "<gray>所有人同一队打月人",
                "<dark_gray>写入 config.yml，只影响之后新建的房间",
                "taketori sengoku mode pve"));
        inventory.setItem(39, run(Material.CROSSBOW,
                (current == SengokuMode.SENGOKU_3V3 ? "<green>" : "<gray>") + "全局 → 战国 3v3",
                "<gray>三局两胜：清守卫 → 敲钟占领 → 击破器攻陷敌方天守阁",
                "<dark_gray>写入 config.yml，只影响之后新建的房间",
                "taketori sengoku mode sengoku_3v3"));
        inventory.setItem(40, suggest(Material.COMMAND_BLOCK, "<yellow>只切当前房间",
                "<gray>已经开着的房间用这条（等待中才能切）",
                "taketori match mode sengoku_3v3 "));

        inventory.setItem(45, run(Material.COMMAND_BLOCK, "<white>热重载配置",
                "<dark_gray>/taketori reload", "taketori reload"));
        inventory.setItem(41, button(Material.TNT, "<red>删除已划点位",
                "<gray>删天守阁 / 门前点 / 箭楼 / 铜钟 / 守卫 / 中地 / 跳跃台",
                "<gray>点进子页后<white>点条目即删除</white>",
                "<dark_gray>等价命令：/taketori sengoku delkeep | deltower | …"));
        inventory.setItem(49, run(Material.PAPER, "<white>刷新本页",
                "<dark_gray>选区与房间状态会重新读取", "taketori sengoku menu"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));

        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 删除页

    /**
     * 删除页：只列出<b>当前真的划了</b>的点位。
     *
     * <p>不像主菜单那样铺满固定按钮——列一堆"本来就没有"的条目，管理员反而找不到
     * 自己要删的那一个；而且删完刷新一次，列表会自然缩短。</p>
     */
    public void openDelete(Player player) {
        if (player == null || !player.isOnline() || !player.hasPermission("taketori.admin")) {
            return;
        }
        Holder holder = new Holder(true);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>月影合战 <dark_gray>· <red>删除点位"));
        holder.bind(inventory);

        ArenaDef def = plugin.arena().selected(player.getUniqueId());
        if (def == null) {
            inventory.setItem(22, button(Material.BARRIER, "<red>还没有选中场地",
                    "<gray>先 <white>/taketori arena setup &lt;id&gt;", ""));
            inventory.setItem(49, run(Material.ARROW, "<white>返回", "", "taketori sengoku menu"));
            inventory.setItem(53, button(Material.BARRIER, "<red>关闭", ""));
            player.openInventory(inventory);
            return;
        }
        var map = def.sengoku();
        inventory.setItem(4, button(Material.NETHER_STAR, "<red>删除 " + def.id() + " 的点位",
                "<gray>只列出<white>当前已划</white>的点位",
                "<dark_gray>当前：" + map.describe()));

        int slot = 9;
        for (TeamId team : TeamId.values()) {
            if (map.keep(team) != null) {
                slot = place(inventory, slot, Material.RED_CONCRETE,
                        "删除 " + team.display() + " 天守阁",
                        "taketori sengoku delkeep " + team.key());
            }
            if (map.keepDoor(team) != null) {
                slot = place(inventory, slot, Material.RED_STAINED_GLASS,
                        "删除 " + team.display() + " 天守阁门前点",
                        "taketori sengoku delkeepdoor " + team.key());
            }
            if (map.jumpPad(team) != null) {
                slot = place(inventory, slot, Material.HONEY_BLOCK,
                        "删除 " + team.display() + " 跳跃台",
                        "taketori sengoku deljumppad " + team.key());
            }
        }
        for (var entry : map.towers().entrySet()) {
            int index = entry.getKey();
            slot = place(inventory, slot, Material.STONE_BRICKS,
                    "删除箭楼 #" + index + "（占领区 + 铜钟 + 守卫点）",
                    "taketori sengoku deltower " + index);
        }
        for (var entry : map.midMinionRegions().entrySet()) {
            slot = place(inventory, slot, Material.ZOMBIE_HEAD,
                    "删除中地小兵区 #" + entry.getKey(),
                    "taketori sengoku delmid " + entry.getKey());
        }
        if (slot == 9) {
            inventory.setItem(22, button(Material.LIME_DYE, "<green>本场地还没有划任何点位",
                    "<gray>回主菜单去划"));
        }

        inventory.setItem(49, run(Material.ARROW, "<white>返回主菜单", "", "taketori sengoku menu"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    /** 在删除页放一个"点击即删"的按钮。 */
    private int place(Inventory inventory, int slot, Material material, String name, String command) {
        if (slot >= 45) {
            return slot;   // 45 往后留给底部按钮
        }
        inventory.setItem(slot, run(material, name, "<yellow>▶ 点击删除", command));
        return slot + 1;
    }

    private String roomLine(Player player) {
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            return "<dark_gray>你不在房间里 —— 运维按钮需要进房后使用";
        }
        if (!room.isSengoku()) {
            return "<yellow>当前房间不是战国模式 <dark_gray>（"
                    + (room.isPve() ? "PVE" : "PVP") + "，可用下方按钮切换）";
        }
        var session = room.sengoku();
        return "<gray>房间 <white>" + room.display() + "</white> <dark_gray>｜ 第 <white>"
                + Math.max(1, session.currentRound()) + "</white> 小局 ｜ 比分 <white>"
                + session.display() + "</white>"
                + (session.isPaused() ? " <yellow>已暂停" : "");
    }

    // ---------------------------------------------------------------- 点击

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
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (slot == 49) {
            // 主菜单是"刷新"，删除页是"返回主菜单"，两者都重新打开主菜单
            open(player);
            return;
        }
        if (slot == 41) {
            openDelete(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        var meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String toRun = meta.getPersistentDataContainer().get(runKey, PersistentDataType.STRING);
        if (toRun != null) {
            player.closeInventory();
            if (plugin.config().debug()) {
                plugin.getLogger().info("[sengoku-menu] " + player.getName() + " -> /" + toRun);
            }
            player.performCommand(toRun);
            // 在删除页里删完回到删除页，方便连续删（主菜单的按钮不受影响）
            if (holder.deletePage && toRun.contains("sengoku del")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> openDelete(player));
            }
            return;
        }
        String toFill = meta.getPersistentDataContainer().get(suggestKey, PersistentDataType.STRING);
        if (toFill != null) {
            player.closeInventory();
            // 只给一条可点击的填充指令，不替玩家决定补什么参数
            player.sendMessage(MINI.deserialize("<gray>点这里填入指令："
                    + "<click:suggest_command:'" + toFill + "'><white>/" + toFill
                    + "</white></click> <dark_gray>补完参数回车"));
        }
    }

    // ---------------------------------------------------------------- 按钮

    /** 直接执行型：最后一行为要执行的指令。 */
    private ItemStack run(Material material, String name, String... lines) {
        String command = lines[lines.length - 1];
        List<String> lore = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            lore.add(lines[i]);
        }
        lore.add("<yellow>▶ 点击执行");
        return build(material, name, lore, runKey, command);
    }

    /** 提示补参型：点击把该指令填进聊天框。 */
    private ItemStack suggest(Material material, String name, String... lines) {
        String command = lines[lines.length - 1];
        List<String> lore = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            lore.add(lines[i]);
        }
        lore.add("<yellow>▶ 点击填入指令，补完回车");
        return build(material, name, lore, suggestKey, command);
    }

    /** 纯展示按钮（无动作）。 */
    private ItemStack button(Material material, String name, String... lines) {
        return build(material, name, List.of(lines), null, null);
    }

    private ItemStack build(Material material, String name, List<String> lore,
                            NamespacedKey key, String action) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> components = new ArrayList<>();
            for (String line : lore) {
                components.add(MINI.deserialize(line));
            }
            meta.lore(components);
            if (key != null && action != null) {
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, action);
            }
        });
        return item;
    }
}
