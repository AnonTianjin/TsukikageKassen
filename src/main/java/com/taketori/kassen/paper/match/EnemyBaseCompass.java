package com.taketori.kassen.paper.match;

import com.taketori.kassen.core.match.BombPhase;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 敌方目标罗盘：对局中放在快捷栏倒数第二格，指针与 Lore 每秒指向「最近的敌方基地」。
 *
 * <p><b>为什么不用磁石</b>：磁石是方块，得在每张地图上埋一个，还要处理「拆掉磁石后罗盘失灵」；
 * {@link Player#setCompassTarget(Location)} 是玩家级指针，随房间世界走、随对局重置，正好合适。</p>
 *
 * <p><b>目标优先级</b>：</p>
 * <ol>
 *   <li>拆包战（bomb）：包点 —— 这一模式的主线就是围绕包点打的，先给包点再谈基地；</li>
 *   <li>战国：敌方天守阁（该模式没有基地区域）；</li>
 *   <li>PVP / 拆包战：最近的、尚未被拆除的敌方基地。</li>
 * </ol>
 *
 * <p><b>发放策略</b>：每秒补一次，格子空着才写、已经有别的物品就不动——避免把玩家捡到的
 * 战利品顶掉。PVE 没有攻防目标，这个模式里只负责把罗盘清掉。</p>
 */
public final class EnemyBaseCompass {

    /**
     * 快捷栏倒数第二格（0-based）：索引 7。
     *
     * <p>第 9 格（索引 8）被对局功能壳占着，武器占 0~3，所以倒数第二格是唯一互不打架的位置。</p>
     */
    public static final int SLOT_HOTBAR = 7;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameRoom room;

    /** 上一次写入 Lore 的「目标 + 距离」；没变就不重造物品（省得每秒刷一次背包包）。 */
    private final Map<UUID, String> shown = new HashMap<>();

    public EnemyBaseCompass(GameRoom room) {
        this.room = room;
    }

    /** 对局收尾：只清缓存，物品本身随背包快照还原而消失。 */
    public void stop() {
        shown.clear();
    }

    /** 由房间每秒调用：补发罗盘 + 刷新指针。 */
    public void tick() {
        if (!room.isRunning()) {
            return;
        }
        for (UUID uuid : room.teamsView().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlayerInventory inventory = player.getInventory();
            ItemStack current = inventory.getItem(SLOT_HOTBAR);
            if (room.isPve()) {
                // PVE 没有攻防目标：切到其它模式后残留的罗盘也要收走
                if (isCompass(current)) {
                    inventory.setItem(SLOT_HOTBAR, null);
                }
                shown.remove(uuid);
                continue;
            }
            Target target = resolve(player);
            if (target == null) {
                if (isCompass(current)) {
                    inventory.setItem(SLOT_HOTBAR, null);
                }
                shown.remove(uuid);
                continue;
            }
            player.setCompassTarget(target.location());
            int distance = (int) Math.round(Math.sqrt(
                    target.location().distanceSquared(player.getLocation())));
            String key = target.label() + "|" + distance;
            if (key.equals(shown.get(uuid)) && isCompass(current)) {
                continue;
            }
            // 格子被别人占着就不动它：宁可这一秒不显示，也不抹掉玩家捡到的东西
            if (current != null && current.getType() != Material.AIR && !isCompass(current)) {
                shown.put(uuid, key);   // 记下已提示的距离，等格子空出来再补
                continue;
            }
            inventory.setItem(SLOT_HOTBAR, create(target.label(), distance));
            shown.put(uuid, key);
        }
    }

    /** 目标：包点（拆包战）> 敌方天守阁（战国）> 最近的未拆敌方基地。 */
    private Target resolve(Player player) {
        // 包点只在「自由时段 / 拆弹窗口」是指路目标；超时进入抢基地阶段后，
        // 包点方块已经还原，再把指针钉在原地只会误导人，改回指最近的敌方基地。
        if (room.isBombMode() && room.bombPhase() != BombPhase.BASE_FIGHT) {
            var bomb = room.arena() == null ? null : room.arena().bomb();
            Location location = bomb == null ? null : bomb.toBukkitLocation();
            if (location != null) {
                return new Target("包点", location);
            }
        }
        TeamId team = room.teamOf(player.getUniqueId());
        if (team == null) {
            return null;
        }
        TeamId enemy = team.opposite();
        if (room.isSengoku()) {
            Location keep = room.keep().keepCenter(enemy);
            return keep == null ? null : new Target(enemy.display() + " 天守阁", keep);
        }
        Location best = null;
        String bestLabel = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Integer, CuboidRegion> entry : room.arena().bases(enemy).entrySet()) {
            if (room.baseCapture().isCaptured(enemy, entry.getKey())) {
                continue;   // 已拆除的基地不再作为目标
            }
            Location center = entry.getValue().center();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            double distance = center.distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = center;
                bestLabel = enemy.display() + " 基地 #" + entry.getKey();
            }
        }
        return best == null ? null : new Target(bestLabel, best);
    }

    /** 造一枚罗盘；Lore 里带上当前目标与直线距离。 */
    private ItemStack create(String label, int distance) {
        ItemStack stack = new ItemStack(Material.COMPASS);
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<aqua><bold>敌方目标罗盘</bold>"));
            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<gray>指向：<white>" + label));
            lore.add(MINI.deserialize("<gray>距离：<white>" + distance + "</white> 格"));
            lore.add(MINI.deserialize("<dark_gray>手持时指针随时指向最近目标"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PDCKeys.enemyCompass(),
                    PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 只认 PDC：改名 / 换 Lore 都不影响识别。 */
    public boolean isCompass(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(PDCKeys.enemyCompass(), PersistentDataType.BYTE);
    }

    /** 目标点：显示名 + 世界坐标。 */
    private record Target(String label, Location location) {
    }
}
