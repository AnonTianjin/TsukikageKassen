package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * CS 式炸弹：开局若干秒后包点变成 TNT，红队站在旁边读条拆除，蓝队守。
 *
 * <p>为什么不做成独立的「炸弹实体」：房间世界是复制出来的临时世界，实体要额外处理卸载与清理；
 * 而 TNT 是一个方块，房间销毁时随世界一起消失，天然不会残留。代价是它必须每秒自愈——
 * 玩家可以用挖掘、爆炸、活塞把方块换掉，那样这个目标就永久消失了，所以 {@link #tick()}
 * 里要把它补回 TNT。</p>
 *
 * <p>拆除是<b>读条</b>而不是连点：进度只对当前拆弹人生效（{@link #defuser}），
 * 离开包点、死亡、掉线、换队都会中断；中断后进度保留 {@code bomb.reset-seconds} 秒，
 * 超时才归零。这样「一个人拆、队友掩护」才有意义，也让拆弹钳（{@code shop.defuse-clipper}）
 * 从纯装饰变成一个能买到的真实优势：空手 10 秒、钳子 6 秒。</p>
 */
public final class BombObjective {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 拆除的有效距离（格）：远了就是「人不在包点」，进度不能再涨。 */
    private static final double DEFUSE_RANGE = 4.5D;

    private final TaketoriPlugin plugin;
    private final GameRoom room;
    /** 中断多久（毫秒）进度复位；0 = 一离开就归零。 */
    private final long resetMillis;

    /** 包点方块的位置；null = 尚未激活或已还原。 */
    private Location location;
    private Material originalType;
    private BlockData originalData;
    /** 正在拆弹的玩家；null = 当前没人拆。 */
    private UUID defuser;
    /** 已累计的拆除毫秒数（只属于 {@link #defuser}）。 */
    private long progressMillis;
    /** 上一次 tick 的时间（毫秒），用于累加进度。 */
    private long lastTickAt;
    /** 最后一次「有人在拆」的时间（毫秒），用于判定中断复位。 */
    private long lastDefuseAt;
    private boolean defused;
    /** 是否已激活（{@link #place()} 成功后才算）。 */
    private boolean armed;

    public BombObjective(TaketoriPlugin plugin, GameRoom room, int resetSeconds) {
        this.plugin = plugin;
        this.room = room;
        this.resetMillis = Math.max(0, resetSeconds) * 1000L;
    }

    /**
     * 把包点激活成 TNT。
     *
     * @return false = 本场地没设置包点（{@code /taketori arena setbomb} 未执行）或房间世界还没就绪，
     *         调用方应据此降级为纯比分对局
     */
    public boolean place() {
        ArenaDef.Point point = room.arena().bomb();
        if (point == null || room.world() == null) {
            return false;
        }
        Block block = room.world().getBlockAt(point.toLocation(room.world()));
        // 记下原方块：结算时连方块数据一起还原，否则场地模板会被永久改成 TNT
        originalType = block.getType();
        originalData = block.getBlockData().clone();
        block.setType(Material.TNT, false);
        location = block.getLocation();
        defuser = null;
        progressMillis = 0L;
        lastTickAt = 0L;
        lastDefuseAt = 0L;
        defused = false;
        armed = true;
        return true;
    }

    /** 还原包点方块。重复调用与未激活时都安全。 */
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
        defuser = null;
        progressMillis = 0L;
        armed = false;
    }

    /** 这个方块是不是包点（交互/破坏事件据此分流）。 */
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

    public boolean isDefused() {
        return defused;
    }

    public boolean isArmed() {
        return armed;
    }

    public long progressMillis() {
        return progressMillis;
    }

    /** 该玩家拆完需要多少毫秒：有拆弹钳走 {@code bomb.defuse-seconds-clipper}，否则走 bare。 */
    public long neededMillis(Player player) {
        int seconds = hasClipper(player)
                ? plugin.config().bombDefuseSecondsClipper()
                : plugin.config().bombDefuseSecondsBare();
        return Math.max(1, seconds) * 1000L;
    }

    /**
     * 玩家开始/继续拆弹。
     *
     * <p>只有红队能拆：蓝队点它不该有任何进度，也不该被当成「拆包动作」播报，
     * 所以直接给一条「你是防守方」的 actionBar 然后返回 false。</p>
     *
     * @return false = 当前不可拆（未激活、已拆完、不是红队）
     */
    public boolean startDefuse(Player player) {
        if (player == null || location == null || !armed || defused) {
            return false;
        }
        if (room.teamOf(player.getUniqueId()) != TeamId.RED) {
            plugin.fx().actionBar(player, MINI.deserialize("<blue>你是防守方，守住炸弹即可"));
            return false;
        }
        long now = System.currentTimeMillis();
        defuser = player.getUniqueId();
        lastTickAt = now;
        lastDefuseAt = now;
        return true;
    }

    /** 玩家松开/右键取消：只有当前拆弹人取消才算数，别人的取消不能打断他。 */
    public void cancelDefuse(Player player) {
        if (player == null || defuser == null) {
            return;
        }
        if (defuser.equals(player.getUniqueId())) {
            defuser = null;
            lastDefuseAt = System.currentTimeMillis();
        }
    }

    /**
     * 每秒调用一次：补回被换掉的 TNT、累加进度、判定中断复位与拆除完成。
     *
     * <p>放在每秒的房间里跑而不是排一个 1 tick 的任务：进度只需要秒级精度，
     * 每 tick 扫一遍方块纯属浪费。</p>
     */
    public void tick() {
        maintainBlock();
        if (location == null || !armed || defused) {
            return;
        }
        long now = System.currentTimeMillis();
        if (defuser == null) {
            // 没人拆：超过 reset-seconds 就把进度清掉（resetMillis=0 时立刻清）
            if (progressMillis > 0L && now - lastDefuseAt >= resetMillis) {
                progressMillis = 0L;
                notifyDefendersReset();
            }
            return;
        }
        Player player = Bukkit.getPlayer(defuser);
        if (player == null || !player.isOnline() || player.isDead()
                || room.teamOf(defuser) != TeamId.RED) {
            // 掉线 / 死亡 / 中途换队：人已经不在包点了
            defuser = null;
            return;
        }
        Location center = location.clone().add(0.5D, 0.5D, 0.5D);
        if (!player.getWorld().equals(center.getWorld())
                || player.getLocation().distanceSquared(center) > DEFUSE_RANGE * DEFUSE_RANGE) {
            defuser = null;
            return;
        }
        progressMillis += Math.max(0L, now - lastTickAt);
        lastTickAt = now;
        lastDefuseAt = now;
        long needed = neededMillis(player);
        if (progressMillis >= needed) {
            defused = true;
            broadcast("<gold><bold>炸弹已被拆除！</bold> <gray>红队获胜");
            restore();
            return;
        }
        String tool = hasClipper(player) ? "拆弹钳" : "空手";
        plugin.fx().actionBar(player, MINI.deserialize("<gold>拆除中 <white>"
                + progressText() + "</white> <dark_gray>| <gray>" + tool
                + " <dark_gray>| <gray>离开包点 " + (resetMillis / 1000L) + " 秒后复位"));
        plugin.fx().particle("SMOKE", center, 2, 0.1D);
    }

    /** TNT 被挖掉 / 被爆炸换成别的方块时补回去，否则这个目标就永久消失了。 */
    private void maintainBlock() {
        if (location == null) {
            return;
        }
        Block block = location.getBlock();
        if (block.getType() != Material.TNT) {
            block.setType(Material.TNT, false);
        }
    }

    /** 进度复位时只提醒还在拆的那一方，蓝队不需要知道红队断了几次。 */
    private void notifyDefendersReset() {
        for (UUID uuid : room.teamsView().keySet()) {
            if (room.teamOf(uuid) != TeamId.RED) {
                continue;
            }
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                member.sendMessage(MINI.deserialize("<yellow>拆除中断超过 " + (resetMillis / 1000L)
                        + " 秒，进度已复位"));
            }
        }
    }

    private void broadcast(String miniMessage) {
        for (UUID uuid : room.teamsView().keySet()) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) {
                member.sendMessage(MINI.deserialize(miniMessage));
            }
        }
    }

    /** 记分板用的一行文本，例如 {@code 4s/10s}。 */
    public String progressText() {
        return (progressMillis / 1000L) + "s/" + (currentNeededSeconds()) + "s";
    }

    private int currentNeededSeconds() {
        Player player = defuser == null ? null : Bukkit.getPlayer(defuser);
        if (player != null) {
            return (int) (neededMillis(player) / 1000L);
        }
        return Math.max(1, plugin.config().bombDefuseSecondsBare());
    }

    /**
     * 玩家身上（整包 + 副手）有没有拆弹钳。
     *
     * <p>用 PDC 标记而不是只看 {@link Material#SHEARS}：剪刀本身是普通工具，
     * 玩家自己合成的剪刀不该拥有「6 秒拆弹」的特权。</p>
     *
     * <p>扫整个背包而不是只看主手：商店的持有上限是按全背包计数的，
     * 只认主手会出现「背包里有一把、主手没拿 → 照样读满 10 秒」的口径矛盾。</p>
     */
    public static boolean hasClipper(Player player) {
        if (player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isClipper(stack)) {
                return true;
            }
        }
        return isClipper(player.getInventory().getItemInOffHand());
    }

    private static boolean isClipper(ItemStack stack) {
        if (stack == null || stack.getType() != Material.SHEARS) {
            return false;
        }
        var meta = stack.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer()
                .has(PDCKeys.defuseClipper(), PersistentDataType.BYTE);
    }
}
