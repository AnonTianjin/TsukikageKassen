package com.taketori.kassen.paper.lobby;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 大厅里的交互告示牌：记录坐标与要执行的动作。
 *
 * <p>可绑定的动作见 {@link com.taketori.kassen.core.lobby.LobbyAction} —— 那里是唯一来源：
 * 用法提示、tab 补全、{@code /taketori lobby list} 与管理员菜单的说明都从它派生，
 * 避免再出现"实际支持了但提示里没写"的不一致。</p>
 */
public record LobbySign(String world, int x, int y, int z, String action) {

    public LobbySign(String world, int x, int y, int z, String action) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action == null ? "join" : action.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean matches(Block block) {
        return block != null
                && block.getWorld() != null
                && block.getWorld().getName().equals(world)
                && block.getX() == x
                && block.getY() == y
                && block.getZ() == z;
    }

    public String describe() {
        return world + " " + x + "," + y + "," + z + " → " + action;
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("action", action);
    }

    public static LobbySign read(ConfigurationSection section) {
        if (section == null || !section.isString("world") || !section.isString("action")) {
            return null;
        }
        return new LobbySign(
                section.getString("world"),
                section.getInt("x"),
                section.getInt("y"),
                section.getInt("z"),
                section.getString("action"));
    }
}
