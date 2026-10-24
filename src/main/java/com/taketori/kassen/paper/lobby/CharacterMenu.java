package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.CharacterDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 角色选择菜单：<b>只能在房间等待区内打开</b>——玩家加入等待房时尚未绑定角色，
 * 由 RoomManager 调 {@link #openForced} 强制弹出；菜单被关掉而仍未选择时会自动重开，
 * 直到选完（或离开等待房）。游戏外（大厅）的入口（告示牌 / 合战菜单按钮）一律拒绝。
 *
 * <p>菜单用箱子界面呈现，图标取该角色第一把武器的原版载体，角色 id 写在物品 PDC 上
 * （不依赖显示名，玩家改名也不影响识别）。</p>
 *
 * <p>角色数量变化时菜单会自动适配尺寸，不需要改代码。</p>
 */
public final class CharacterMenu implements Listener {

    /** 用于识别"这是我们开的菜单"，比匹配标题文本可靠。 */
    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final NamespacedKey characterKey;
    /**
     * 被强制选角色的等待者：菜单关闭后下一 tick 自动重开。
     * 离开等待房 / 已绑定角色时移除（RoomManager.leave 与点击成功两处清理）。
     */
    private final Set<UUID> forced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CharacterMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.characterKey = new NamespacedKey(plugin, "menu_character_id");
    }

    /**
     * 普通打开入口：游戏外（不在等待区）直接拒绝并解释。
     * 角色与对局绑定——在等待区选好即可，武器与开局铁甲由开局流程统一发放。
     */
    public void open(Player player) {
        if (!inWaitingRoom(player)) {
            player.sendMessage(MINI.deserialize("<red>角色只能在房间等待区选择：<gray>先通过玩家菜单点「降临月宫」进入等待区，进入时会自动弹出角色菜单。"));
            return;
        }
        show(player);
    }

    /**
     * 强制打开（RoomManager 玩家进房后调用）：登记强制标记，
     * 玩家不选就关掉菜单时 {@link #onClose} 会立刻重开。
     */
    public void openForced(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        forced.add(player.getUniqueId());
        show(player);
    }

    /** 取消某玩家的强制状态（离开等待房 / 退房）。 */
    public void cancelForce(UUID uuid) {
        if (uuid != null) {
            forced.remove(uuid);
        }
    }

    /** 清空全部强制状态（reload / 停服）。 */
    public void clearForced() {
        forced.clear();
    }

    /** 玩家当前是否处于某房等待区（WAITING/STARTING 且在等待名单内）。 */
    private boolean inWaitingRoom(Player player) {
        var room = plugin.rooms().roomOf(player);
        return room != null && room.isProtected(player.getUniqueId())
                && (room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING);
    }

    private void show(Player player) {
        List<CharacterDef> definitions = new ArrayList<>(plugin.config().characters().all());
        if (definitions.isEmpty()) {
            player.sendMessage(MINI.deserialize("<red>还没有配置任何角色。"));
            return;
        }
        int rows = Math.min(6, Math.max(1, (definitions.size() + 8) / 9));
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, rows * 9,
                MINI.deserialize("<gold>选择角色</gold> <dark_gray>（点击立即生效）"));
        holder.bind(inventory);
        for (CharacterDef def : definitions) {
            inventory.addItem(icon(def));
        }
        player.openInventory(inventory);
    }

    /**
     * 强制者关掉菜单：仍在等待区且仍未绑定角色 → 下一 tick 重开（不直接在事件里开，
     * 避免与关闭动画互相干扰）；已离开或已选上则解除强制。
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!forced.contains(uuid)) {
            return;
        }
        var profile = plugin.config().characters().profileOrNull(uuid);
        if ((profile != null && profile.hasCharacter()) || !inWaitingRoom(player)) {
            forced.remove(uuid);
            return;
        }
        plugin.scheduler().runLater(() -> {
            if (!forced.contains(uuid) || !player.isOnline()) {
                return;
            }
            var latest = plugin.config().characters().profileOrNull(uuid);
            if (latest != null && latest.hasCharacter()) {
                forced.remove(uuid);
                return;
            }
            if (inWaitingRoom(player)) {
                player.sendMessage(MINI.deserialize("<gold>必须选择角色后才能继续等待开局<gray>（关闭菜单会再次弹出）"));
                show(player);
            } else {
                forced.remove(uuid);
            }
        }, 2L);
    }

    private ItemStack icon(CharacterDef def) {
        Material material = Material.NETHER_STAR;
        if (!def.weapons().isEmpty()) {
            var weapon = plugin.config().weapons().get(def.weapons().get(0));
            if (weapon != null) {
                Material parsed = Material.matchMaterial(weapon.materialName());
                if (parsed != null && !parsed.isAir()) {
                    material = parsed;
                }
            }
        }
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(def.display()));
            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<gray>武器：<white>" + String.join("、", def.weapons())));
            def.lore().forEach(line -> lore.add(MINI.deserialize(line)));
            lore.add(Component.empty());
            lore.add(MINI.deserialize("<yellow>▶ 点击选择该角色"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(characterKey, PersistentDataType.STRING, def.id());
        });
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }
        String characterId = clicked.getItemMeta().getPersistentDataContainer()
                .get(characterKey, PersistentDataType.STRING);
        if (characterId == null) {
            return;
        }
        player.closeInventory();
        var character = plugin.config().characters().get(characterId);
        if (character == null) {
            player.sendMessage(MINI.deserialize("<red>该角色已不存在：" + characterId));
            return;
        }
        // 同队不允许出现相同角色：冲突时按隐性标签权重裁决（权重高者优先），裁决范围是所在房间
        var room = plugin.rooms().roomOf(player);
        var decision = room != null ? room.requestRole(player, characterId)
                : new com.taketori.kassen.paper.match.room.GameRoom.RoleDecision(true, null, null);
        if (!decision.granted()) {
            player.sendMessage(MINI.deserialize("<red>无法选择该角色：" + decision.reason()));
            player.sendMessage(MINI.deserialize("<gray>可以换一个角色，或让管理员调高你的隐性标签权重。"));
            return;
        }
        plugin.bindCharacter(player, characterId);
        // 已成功绑定：解除角色强制，关闭事件不再重开角色菜单
        forced.remove(player.getUniqueId());
        player.sendMessage(MINI.deserialize("<green>已选择角色 " + character.display()
                + "<gray>，开局时会连同铁甲一起发放。"));
        if (decision.displacedSomeone()) {
            player.sendMessage(MINI.deserialize("<yellow>你的权重更高，已接管该角色（"
                    + decision.displaced() + " 的角色被解除）。"));
        }
        // 入局流程下一步：PVP 且玩家还没有队伍 → 强制弹出选队伍界面
        // （PVE 不需要，开局统一红队；已有队伍说明是换角色，不重复弹）
        var currentRoom = plugin.rooms().roomOf(player);
        if (currentRoom != null && !currentRoom.isPve()
                && currentRoom.teamOf(player.getUniqueId()) == null) {
            plugin.playerMenu().openTeamForced(player);
        }
    }
}
