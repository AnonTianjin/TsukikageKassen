package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.lobby.LobbyAction;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 大厅交互：告示牌点击 + 大厅保护。
 *
 * <p><b>告示牌只指向界面</b>：可绑定的动作与各自含义见 {@link LobbyAction}——
 * 点击后打开对应的 GUI（玩家菜单 / 角色菜单 / 排行榜），具体动作由界面里的按钮执行；
 * 只有 {@code character:<角色id>} 这类参数化动作仍然直接绑定角色（它本身就是"就选这个角色"）。</p>
 *
 * <p>本监听器用 <b>{@code ignoreCancelled = false}</b>：领地、保护类插件有可能先取消掉方块交互，
 * 沿用 {@code ignoreCancelled = true} 的话大厅告示牌会"点了完全没反应"且日志里什么都没有
 * （与当初第三槽 F 键收不到事件是同一类坑）。这里先拿到事件，确认是插件自己注册的告示牌后才取消原版行为。</p>
 */
public final class LobbyListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public LobbyListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        // 实时状态牌：每秒刷新的房间牌，点击直接加入 / 旁观 / 创建
        var statusSign = plugin.lobby().statusSignAt(block);
        if (statusSign != null) {
            event.setCancelled(true);
            plugin.lobby().clickStatusSign(player, statusSign.templateId());
            return;
        }
        LobbySign sign = plugin.lobby().signAt(block);
        if (sign == null) {
            return;   // 不是插件注册的告示牌：完全不放干预
        }
        // 是插件自己的告示牌 → 吃掉这次交互
        event.setCancelled(true);


        LobbyAction action = LobbyAction.of(sign.action());
        if (action == null) {
            player.sendMessage(MINI.deserialize("<red>这张告示牌绑定的动作无法识别：<white>" + sign.action()));
            player.sendMessage(MINI.deserialize("<gray>可用动作：<white>" + LobbyAction.usageKeys()));
            return;
        }

        // character:<角色id>：直接绑定（参数化动作，不走界面）
        if (action == LobbyAction.CHARACTER_ID) {
            bindCharacter(player, sign.action());
            return;
        }

        switch (action) {
            // ---- 直接执行的匹配动作：点牌即走，不经过菜单 ----
            case JOIN -> plugin.lobby().quickJoin(player);
            case CREATE -> plugin.lobby().createRoom(player, null);
            case LEAVE -> leaveViaSign(player);
            // ---- 指向界面：告示牌只负责把入口指到 GUI ----
            case SPECTATE, LOBBY, MENU -> openMenuFor(player, action);
            case ROOMS -> plugin.roomListMenu().open(player);
            case CHARACTER -> plugin.characterMenu().open(player);
            case RANKS -> plugin.statsMenu().open(player);
            default -> player.sendMessage(MINI.deserialize("<red>暂不支持的告示牌动作：<white>" + action.key()));
        }
    }

    /**
     * leave 告示牌：观众先退观众；等待区退房回大厅 / 对局中拒绝，
     * 全部逻辑在 LobbyManager.returnToLobby 统一处理。
     */
    private void leaveViaSign(Player player) {
        plugin.lobby().returnToLobby(player);
    }

    /** 打开玩家菜单，并用 actionBar 告诉玩家该点哪个按钮。 */
    private void openMenuFor(Player player, LobbyAction action) {
        plugin.playerMenu().open(player);
        String hint = switch (action) {
            case SPECTATE -> "点「旁观 / 退出观战」进入观众";
            case LOBBY -> "点「回大厅」传送回大厅";
            default -> "在菜单里选择要做的操作";
        };
        plugin.fx().actionBar(player, MINI.deserialize("<gray>" + hint));
    }

    /** 直接绑定角色（character:&lt;角色id&gt; 告示牌）。 */
    private void bindCharacter(Player player, String rawAction) {
        String characterId = LobbyAction.characterIdOf(rawAction);
        var character = characterId == null ? null : plugin.config().characters().get(characterId);
        if (character == null) {
            player.sendMessage(MINI.deserialize("<red>该角色不存在：" + characterId));
            return;
        }
        plugin.bindCharacter(player, characterId);
        player.sendMessage(MINI.deserialize("<green>已选择角色 " + character.display()));
    }

    /** 大厅保护：大厅内不掉血（默认开启，可在 config.yml 关闭）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.config().lobbyProtect()) {
            return;
        }
        if (event.getEntity() instanceof Player player && plugin.lobby().isInLobby(player)) {
            event.setCancelled(true);
        }
    }
}
