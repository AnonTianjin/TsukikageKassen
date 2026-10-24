package com.taketori.kassen.paper.item;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 菜单时钟：一个用 PDC 标记身份的普通物品，拿在手里右键就打开玩家菜单。
 *
 * <p>与选区锄（{@link com.taketori.kassen.paper.setup.SetupWand}）是同一套模式：
 * PDC 标记 → 监听器识别 → 拦截原版行为。区别是它发给所有玩家（默认进服自动发放），
 * 所以额外做了"丢不掉"处理，避免误丢之后没有菜单入口。</p>
 */
public final class MenuClock {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public MenuClock(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.config().menuClockEnabled();
    }

    /** 配置的材质；写错时回退到 CLOCK 并在控制台点名。 */
    public Material material() {
        String name = plugin.config().menuClockMaterial();
        if (name == null || name.isBlank()) {
            return Material.CLOCK;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning("menu-clock.material 无法解析：" + name + " → 回退到 CLOCK");
            return Material.CLOCK;
        }
        return material;
    }

    /** 造一个菜单时钟（带 PDC 标记）。 */
    public ItemStack create() {
        ItemStack stack = new ItemStack(material());
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(plugin.config().menuClockName()));
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.config().menuClockLore()) {
                lore.add(MINI.deserialize(line));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PDCKeys.menuClock(), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 靠 PDC 识别，改名 / 改 Lore / 换材质都不影响。 */
    public boolean isMenuClock(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return false;
        }
        var meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte flag = meta.getPersistentDataContainer().get(PDCKeys.menuClock(), PersistentDataType.BYTE);
        return flag != null;
    }

    /** 玩家背包里是否已经有菜单时钟。 */
    public boolean hasClock(Player player) {
        if (player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isMenuClock(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 手动发放（命令 / 管理员用），已有则提示不重复发。 */
    public void give(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!enabled()) {
            player.sendMessage(MINI.deserialize("<red>菜单时钟已在 config.yml 里关闭（menu-clock.enabled: false）。"));
            return;
        }
        if (hasClock(player)) {
            player.sendMessage(MINI.deserialize("<gray>你背包里已经有菜单时钟了（右键即可打开菜单）。"));
            return;
        }
        if (!player.getInventory().addItem(create()).isEmpty()) {
            player.sendMessage(MINI.deserialize("<yellow>背包已满，菜单时钟没能发放"
                    + "<gray>（清出 1 格后再试，或用 /taketori menu 直接打开菜单）"));
            return;
        }
        player.sendMessage(MINI.deserialize("<green>已获得菜单时钟 <dark_gray>（右键打开玩家菜单）。"));
    }

    /** 进服发放：已经有就什么都不做，也不刷提示。 */
    public void giveOnJoin(Player player) {
        if (player == null || !player.isOnline() || !enabled()) {
            return;
        }
        if (hasClock(player)) {
            return;
        }
        if (!player.getInventory().addItem(create()).isEmpty()) {
            player.sendMessage(MINI.deserialize("<yellow>背包已满，菜单时钟没能发放"
                    + "<gray>（清出 1 格后用 /taketori menu 也能打开菜单）"));
        }
    }
}
