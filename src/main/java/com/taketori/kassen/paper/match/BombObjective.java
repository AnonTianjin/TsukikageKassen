package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * CS 式包点：开局把一个方块换成拉杆，进攻方连点拆开即胜。
 *
 * <p>为什么不做成独立的「炸弹实体」：房间世界是复制出来的临时世界，
 * 实体要额外处理卸载与清理；而拉杆是一个方块，房间销毁时随世界一起消失，
 * 天然不会残留。代价是它必须每 tick 自愈——玩家可以用挖掘、爆炸、
 * 活塞把拉杆拆掉，那样这个目标就永久消失了，所以 {@link #tick()} 里要把它补回去。</p>
 *
 * <p>进度全队共享、谁点都算。这是刻意的：一个人连点 10 下既无聊又容易被一次偷袭清零，
 * 接力点更接近真实拆包的体验；断点续传的代价是中断超过 reset-seconds 秒进度归零。</p>
 */
public final class BombObjective {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final GameRoom room;
    /** 拆包需要的点击数（全局配置 bomb.defuse-clicks）。 */
    private final int required;
    /** 中断多久（毫秒）进度复位。 */
    private final long resetMillis;

    /** 包点方块的位置；null = 尚未埋包或已还原。 */
    private Location location;
    private Material originalType;
    private BlockData originalData;
    /** 当前累计点击次数（全队共享）。 */
    private int clicks;
    /** 最后一次点击的时间（毫秒）；0 表示这段时间没人点过。 */
    private long lastClickAt;
    private boolean defused;

    public BombObjective(TaketoriPlugin plugin, GameRoom room, int required, int resetSeconds) {
        this.plugin = plugin;
        this.room = room;
        this.required = Math.max(1, required);
        this.resetMillis = Math.max(1, resetSeconds) * 1000L;
    }

    /**
     * 把包点埋到场地坐标上。
     *
     * @return false = 本场地没设置包点（{@code /taketori arena setbomb} 未执行），调用方应据此降级为纯比分对局
     */
    public boolean place() {
        ArenaDef.Point point = room.arena().bomb();
        if (point == null || room.world() == null) {
            return false;
        }
        Block block = room.world().getBlockAt(point.toLocation(room.world()));
        // 记下原方块：结算时连方块数据一起还原，否则场地模板会被永久改成拉杆
        originalType = block.getType();
        originalData = block.getBlockData().clone();
        block.setType(Material.LEVER, false);
        location = block.getLocation();
        clicks = 0;
        lastClickAt = 0L;
        defused = false;
        return true;
    }

    /** 还原包点方块。重复调用与未埋包时都安全。 */
    public void restore() {
        if (location == null || originalType == null) {
            return;
        }
        Block block = location.getBlock();
        if (originalData != null) {
            block.setBlockData(originalData, false);
        } else {
            block.setType(originalType, false);
        }
        location = null;
    }

    /** 这个方块是不是包点（拉杆交互事件据此分流）。 */
    public boolean isBombBlock(Block block) {
        if (block == null || location == null) {
            return false;
        }
        return block.getWorld().equals(location.getWorld())
                && block.getX() == location.getBlockX()
                && block.getY() == location.getBlockY()
                && block.getZ() == location.getBlockZ();
    }

    public Location location() {
        return location;
    }

    public int clicks() {
        return clicks;
    }

    public int required() {
        return required;
    }

    public boolean isDefused() {
        return defused;
    }

    /**
     * 某个玩家点了一下拉杆。
     *
     * @return 结果；调用方按 {@link ClickResult#DEFUSED} 结束对局
     */
    public ClickResult click(Player player) {
        if (location == null || room.phase() != GameRoom.Phase.PLAYING) {
            return ClickResult.NOT_PLAYING;
        }
        if (room.teamOf(player.getUniqueId()) != TeamId.RED) {
            // 防守方点拉杆不是「拆包」，但也别让玩家以为卡了：给一条明确提示
            plugin.fx().actionBar(player, MINI.deserialize("<red>你是防守方，不能拆包——守住它就行"));
            return ClickResult.NOT_ATTACKER;
        }
        clicks++;
        lastClickAt = System.currentTimeMillis();
        plugin.fx().sound("BLOCK_STONE_BUTTON_CLICK_ON", location, 0.8F, 1.4F);
        plugin.fx().particle("SMOKE", location.clone().add(0.5D, 0.5D, 0.5D), 6, 0.2D);
        if (clicks >= required) {
            defused = true;
            for (UUID uuid : room.teamsView().keySet()) {
                Player member = Bukkit.getPlayer(uuid);
                if (member != null) {
                    member.sendMessage(MINI.deserialize("<gold><bold>包已被拆开！</bold> <gray>进攻方获胜"));
                }
            }
            return ClickResult.DEFUSED;
        }
        plugin.fx().actionBar(player, MINI.deserialize("<gold>拆包 <white>" + clicks + "/" + required
                + " <dark_gray>| <gray>中断 " + (resetMillis / 1000L) + " 秒会复位"));
        return ClickResult.PROGRESS;
    }

    /**
     * 每秒调用一次：补回被拆掉的拉杆、检查进度是否超时复位。
     *
     * <p>放在每秒的房间里跑而不是排一个 1 tick 的任务：复位判定只需要秒级精度，
     * 每 tick 扫一遍方块纯属浪费。</p>
     */
    public void tick() {
        maintainBlock();
        if (clicks <= 0 || clicks >= required) {
            return;
        }
        long idle = System.currentTimeMillis() - lastClickAt;
        if (idle < resetMillis) {
            return;
        }
        clicks = 0;
        lastClickAt = 0L;
        for (UUID uuid : room.teamsView().keySet()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || room.teamOf(uuid) != TeamId.RED) {
                continue;
            }
            member.sendMessage(MINI.deserialize("<yellow>拆包中断超过 " + (resetMillis / 1000L)
                    + " 秒，进度已复位"));
        }
    }

    /** 拉杆被挖掉 / 被爆炸换成别的方块时补回去，否则这个目标就永久消失了。 */
    private void maintainBlock() {
        if (location == null) {
            return;
        }
        Block block = location.getBlock();
        if (block.getType() != Material.LEVER) {
            block.setType(Material.LEVER, false);
        }
    }

    /** 记分板用的一行文本，例如 {@code 3/10}。 */
    public String progressText() {
        return clicks + "/" + required;
    }

    /** 点击结果。 */
    public enum ClickResult {
        NOT_BOMB,
        NOT_PLAYING,
        NOT_ATTACKER,
        PROGRESS,
        DEFUSED
    }
}
