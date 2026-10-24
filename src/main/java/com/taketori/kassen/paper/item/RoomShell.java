package com.taketori.kassen.paper.item;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 对局功能壳（鹦鹉螺壳）：房间内发给玩家、右键打开「退出对局 / 投降」GUI。
 *
 * <p><b>生命周期</b>：进房（{@code RoomManager.joinRoom}）即发放，比角色武器更早；
 * 结算时随 {@code GameRoom} 的背包快照一起还原，物品自然消失——与角色武器同一套回收点，
 * 不需要单独的清理逻辑。</p>
 *
 * <p><b>为什么集中在这个类</b>：壳的「造 / 认 / 发 / 收」四件事必须共用同一份
 * PDC 判定与同一个槽位约定，否则 GUI、监听器、发放点会各自漂移。后续要加新按钮或
 * 改壳的外观，只动 {@link #create()} 与 {@link #SLOT_HOTBAR} 即可。</p>
 *
 * <p>与 {@link MenuClock} 是同一套模式：PDC 标记 → 监听器识别 → 拦截原版行为；
 * 区别是它只在房间内存在，且认 PDC 不认显示名。</p>
 */
public final class RoomShell {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * 壳在快捷栏中的位置（0-based）。第 9 格 = index 8。
     *
     * <p>抽成常量是为了后续开发留口子：想换格子只改这里；想把壳从快捷栏挪进背包，
     * 也只需改 {@link #give} 的落点策略。</p>
     */
    public static final int SLOT_HOTBAR = 8;

    private final TaketoriPlugin plugin;

    public RoomShell(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().roomShellEnabled();
    }

    /** 造一个功能壳（带 PDC 标记）。 */
    public ItemStack create() {
        ItemStack stack = new ItemStack(plugin.config().roomShellMaterial());
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(plugin.config().roomShellName()));
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.config().roomShellLore()) {
                lore.add(MINI.deserialize(line));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PDCKeys.roomShell(), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 只认 PDC：改名 / 改 Lore / 换材质都不影响识别。 */
    public boolean isRoomShell(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return false;
        }
        var meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte flag = meta.getPersistentDataContainer().get(PDCKeys.roomShell(), PersistentDataType.BYTE);
        return flag != null;
    }

    /** 玩家是否已经拿着壳（避免重复发放，也用于判断是否要补发）。 */
    public boolean hasShell(Player player) {
        if (player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isRoomShell(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发壳到快捷栏第 9 格（{@link #SLOT_HOTBAR}）。
     *
     * <p>先清掉该格原有的壳（重复发放不叠加），再写入；该格若被别的东西占着则直接占用——
     * 开局发武器发生在封存+清包之后，这里的占用是安全的。已有壳且位置正确时不做任何事，
     * 让「进房发一次 + 开局补发一次」这条链路可以重复调用。</p>
     *
     * @return 是否实际写入了物品
     */
    public boolean give(Player player) {
        if (player == null || !player.isOnline() || !enabled()) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack existing = inv.getItem(SLOT_HOTBAR);
        if (isRoomShell(existing)) {
            return false;   // 已经在位，不重复动它
        }
        // 壳可能被挪到别的格子（理论上锁死不会发生，防御性处理）：先全局清掉旧壳再补位
        removeExisting(inv);
        inv.setItem(SLOT_HOTBAR, create());
        return true;
    }

    /** 把玩家背包里所有壳清掉（结算之外的兜底路径使用）。 */
    public void removeExisting(PlayerInventory inv) {
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isRoomShell(contents[slot])) {
                inv.setItem(slot, null);
            }
        }
    }
}
