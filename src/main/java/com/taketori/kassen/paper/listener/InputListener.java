package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.skill.ThirdSlotTrigger;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一输入层：把原版动作翻译成技能槽位。
 *
 * <p>核心规则（计划 §3.4 的载体护栏）：</p>
 * <ul>
 *   <li>手里拿着插件武器时，<b>永远不参与方块交互</b>（左键不挖方块、右键不放置、不驯服实体）。</li>
 *   <li>某个槽位<b>绑定了技能</b>就取消原版行为并派发技能；<b>没有绑定就放行原版</b>——
 *       于是弓的原版蓄力、盾的原版举盾天然不受影响。</li>
 *   <li>左键的"打实体"与"空挥"是两个事件，用 tick 窗口去重；右键同理。</li>
 * </ul>
 *
 * <p><b>第三槽（F 键那一槽）的触发方式是可配置的</b>，见 {@link ThirdSlotTrigger} 与
 * <code>config.yml</code> 的 <code>input.shift-right-trigger</code>：默认<b>双击潜行键</b>，
 * 因为原版一定会为潜行发出事件，不像 F 键（会被别的插件吞掉）和潜行+右键
 * （对着方块时原版根本不发事件）那样有坑。</p>
 *
 * <p><b>调试</b>：开启 debug 后这里会打印<b>收到的每一个交互事件</b>，包括
 * "主手不是插件武器 → 忽略"这种被跳过的分支；另外 <code>/taketori keys</code>
 * 会回放最近收到的原始输入事件，用来判断"按键到底有没有传到服务端"。</p>
 */
public final class InputListener implements Listener {

    /** 判定"双击"的时间窗：小于下限认为是同一次点击的重复事件，大于上限不算连击。 */
    private static final long DOUBLE_TAP_MIN_MILLIS = 100L;
    private static final long DOUBLE_TAP_MAX_MILLIS = 300L;

    /** 每个玩家保留多少条输入事件记录（/taketori keys）。 */
    private static final int TRACE_LIMIT = 14;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TaketoriPlugin plugin;

    /** 上一次右键的时间（双击右键判定）。 */
    private final Map<UUID, Long> lastRightClick = new ConcurrentHashMap<>();
    /** 上一次"开始潜行"的时间（双击潜行判定）。 */
    private final Map<UUID, Long> lastSneakStart = new ConcurrentHashMap<>();
    /** 本次右键已经派发过的 tick（挡住"实体交互 + 使用物品"两条路径重复派发）。 */
    private final Map<UUID, Long> lastRightDispatch = new ConcurrentHashMap<>();
    /** 最近的输入事件（诊断用）。 */
    private final Map<UUID, Deque<String>> traces = new ConcurrentHashMap<>();

    public InputListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 左键 / 右键

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // 副手交互不参与派发，避免与"主手武器"判定混淆
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemFactory.Identity identity = plugin.items().read(hand);
        Action action = event.getAction();
        trace(player, "交互 " + action + (player.isSneaking() ? "（潜行中）" : ""));

        if (identity == null) {
            debug(player, action, hand, null, "主手不是插件武器 → 忽略（若你确定拿的是插件武器，说明 PDC 读不到，请用 /taketori doctor）");
            return;
        }

        // 插件武器永不破坏方块
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            debug(player, action, hand, identity, "插件武器不破坏方块 → 已取消");
            return;
        }

        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            debug(player, action, hand, identity, "weapon_id=" + identity.weaponId() + " 在 weapons.yml 里不存在 → 忽略");
            return;
        }
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());

        switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                // 顺序很重要：先判断"这次右键是不是在触发第三槽"（双击右键 / 潜行右键），
                // 是就直接派发第三槽，不再走 right —— 这正是"潜行右键被右键槽先吃掉"的修法。
                if (rightClickTriggersThirdSlot(player)) {
                    event.setCancelled(true);
                    debug(player, action, hand, identity, "右键被识别为第三槽触发 → 派发 shift-right");
                    dispatchThirdSlot(player, hand, "右键（第三槽触发）");
                    return;
                }
                if (!claimRightClick(player)) {
                    debug(player, action, hand, identity, "同一次右键已由实体交互处理 → 去重跳过");
                    return;
                }
                SkillDef def = weapon.skill(SkillSlot.RIGHT, mode);
                if (!def.isPresent()) {
                    debug(player, action, hand, identity, "槽位 right 未绑定技能 → 放行原版行为");
                    return;
                }
                event.setCancelled(true);
                debug(player, action, hand, identity, "派发 right → " + def.type());
                plugin.skills().dispatch(player, hand, SkillSlot.RIGHT, true);
            }
            case LEFT_CLICK_AIR -> {
                SkillDef def = weapon.skill(SkillSlot.LEFT, mode);
                if (!def.isPresent()) {
                    debug(player, action, hand, identity, "槽位 left 未绑定技能 → 放行原版行为");
                    return;
                }
                // 与"左键命中实体"共享同一次挥击窗口
                if (profile.markSwing(player.getWorld().getGameTime(), weapon.id())) {
                    debug(player, action, hand, identity, "同一 tick 窗口内已由命中事件处理 → 去重跳过");
                    return;
                }
                debug(player, action, hand, identity, "派发 left → " + def.type());
                plugin.skills().dispatch(player, hand, SkillSlot.LEFT, true);
            }
            default -> {
                // LEFT_CLICK_BLOCK 已在上方处理
            }
        }
    }

    /**
     * 右键<b>实体</b>（对着玩家 / 怪物按右键）。
     *
     * <p>原版这条路径<b>不会</b>触发 {@link PlayerInteractEvent}，所以过去"对着敌人右键"
     * 是放不出右键技能的 —— 只能对着空气或方块放。这里把它接上，并顺手取消原版实体交互
     * （否则手持骨头右键狼会当场驯服、拿着钓竿右键会抛钩）。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemFactory.Identity identity = plugin.items().read(hand);
        trace(player, "右键实体 " + event.getRightClicked().getType()
                + (player.isSneaking() ? "（潜行中）" : ""));
        if (identity == null) {
            return;
        }
        // 手持插件武器时不参与原版实体交互
        event.setCancelled(true);

        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            return;
        }
        if (rightClickTriggersThirdSlot(player)) {
            debug(player, Action.RIGHT_CLICK_AIR, hand, identity, "右键实体被识别为第三槽触发 → 派发 shift-right");
            dispatchThirdSlot(player, hand, "右键实体（第三槽触发）");
            return;
        }
        if (!claimRightClick(player)) {
            debug(player, Action.RIGHT_CLICK_AIR, hand, identity, "同一次右键已派发过 → 去重跳过");
            return;
        }
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        SkillDef def = weapon.skill(SkillSlot.RIGHT, mode);
        if (!def.isPresent()) {
            debug(player, Action.RIGHT_CLICK_AIR, hand, identity, "槽位 right 未绑定技能 → 不派发");
            return;
        }
        debug(player, Action.RIGHT_CLICK_AIR, hand, identity, "派发 right（右键实体）→ " + def.type());
        plugin.skills().dispatch(player, hand, SkillSlot.RIGHT, true);
    }

    /**
     * 这次右键是不是"第三槽"的触发动作。
     *
     * <p>两种判定，都会顺手更新计时：</p>
     * <ul>
     *   <li>{@code sneak-right}：潜行状态下右键（原版对着方块时不发事件，所以只有右键空气可靠）；</li>
     *   <li>{@code double-right}：与上一次右键相隔 100~300 毫秒 —— 第二次右键改派第三槽，
     *       于是"先被右键占用"这件事从根上不会发生。</li>
     * </ul>
     */
    private boolean rightClickTriggersThirdSlot(Player player) {
        Set<ThirdSlotTrigger> triggers = triggers();
        if (triggers.contains(ThirdSlotTrigger.SNEAK_RIGHT) && player.isSneaking()) {
            lastRightClick.remove(player.getUniqueId());
            return true;
        }
        if (!triggers.contains(ThirdSlotTrigger.DOUBLE_RIGHT)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastRightClick.put(player.getUniqueId(), now);
        if (last == null) {
            return false;
        }
        long gap = now - last;
        if (gap >= DOUBLE_TAP_MIN_MILLIS && gap <= DOUBLE_TAP_MAX_MILLIS) {
            lastRightClick.remove(player.getUniqueId());
            return true;
        }
        return false;
    }

    /** 同一次右键只派发一次（实体交互与"使用物品"可能先后到达）。 */
    private boolean claimRightClick(Player player) {
        long tick = player.getWorld().getGameTime();
        Long last = lastRightDispatch.put(player.getUniqueId(), tick);
        return last == null || last != tick;
    }

    // ---------------------------------------------------------------- 潜行键（双击 = 第三槽）

    /**
     * 双击潜行键 → 第三槽技能。
     *
     * <p>潜行是独立按键，原版<b>一定</b>会发出这个事件，所以它比 F 键与潜行+右键都可靠。
     * 只有 300 毫秒内的两次"开始潜行"才算双击，正常按住潜行不会误触发。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        trace(player, "潜行切换 → " + (event.isSneaking() ? "开始潜行" : "结束潜行"));
        if (!triggers().contains(ThirdSlotTrigger.DOUBLE_SNEAK) || !event.isSneaking()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSneakStart.put(player.getUniqueId(), now);
        if (last == null) {
            // 首按提示（C9）：把"双击潜行"的输入窗口可视化，不再怀疑第一下有没有被记录。
            // 只在手持插件武器时提示，平时蹲墙角不刷屏。
            if (plugin.items().read(player.getInventory().getItemInMainHand()) != null) {
                plugin.fx().actionBar(player, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<dark_gray>再按一次潜行 → 第三槽技能"));
            }
            return;
        }
        long gap = now - last;
        if (gap < DOUBLE_TAP_MIN_MILLIS || gap > DOUBLE_TAP_MAX_MILLIS) {
            return;
        }
        lastSneakStart.remove(player.getUniqueId());
        dispatchThirdSlot(player, player.getInventory().getItemInMainHand(), "双击潜行");
    }

    /** 玩家退出时清掉按键状态，避免 UUID 泄漏。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastRightClick.remove(uuid);
        lastSneakStart.remove(uuid);
        lastRightDispatch.remove(uuid);
        traces.remove(uuid);
    }

    // ---------------------------------------------------------------- Q 键

    /** Q 键：原版是"丢弃物品"，这里统一拦截并按配置转成模式切换 / 第三槽技能。 */
    // ignoreCancelled = false：别的插件即使先取消了丢弃事件，Q 键也要照常工作
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack dropped = event.getItemDrop().getItemStack();
        ItemFactory.Identity identity = plugin.items().read(dropped);
        trace(player, "丢弃键 Q" + (player.isSneaking() ? "（潜行中）" : "")
                + (identity == null ? "" : " weapon=" + identity.weaponId()));
        if (identity == null) {
            logDrop(player, dropped, null, "丢弃的物品不是插件武器 → 不处理（Q 只在你手持插件武器时才接管）");
            return;
        }

        if (plugin.config().allowDrop()) {
            logDrop(player, dropped, identity, "allow-drop=true → 允许丢弃，不触发 Q 技能");
            return;
        }
        event.setCancelled(true);

        // 战国模式：能量满时 Q 优先释放必杀技。
        // 必须在潜行-Q 第三槽与 q-mode 分支【之前】——否则配了 sneak-q 触发时，
        // 满能量玩家的"潜行+Q"永远出第三槽而不是必杀，配成 none / held-slot 时必杀会被整个吃掉。
        // 能量没满时 tryUltimate 直接返回 false，Q 完全走原有逻辑。
        // ultimate-slot 配成 q 以外的值时本接管点整体不生效（当前唯一支持的触发键就是 Q）。
        var sengokuRoom = plugin.rooms().roomOf(player);
        if (sengokuRoom != null && sengokuRoom.isSengoku()
                && plugin.config().energyRules().usesQSlot()
                && sengokuRoom.energy().tryUltimate(player)) {
            return;
        }

        // 潜行 + Q → 第三槽技能（q 槽留给模式切换，用潜行区分）
        if (triggers().contains(ThirdSlotTrigger.SNEAK_Q) && player.isSneaking()) {
            logDrop(player, dropped, identity, "潜行 + Q → 派发第三槽技能");
            plugin.scheduler().runLater(() -> {
                if (!player.isOnline()) {
                    return;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                ItemStack source = plugin.items().read(hand) != null ? hand : dropped;
                dispatchThirdSlot(player, source, "潜行 + Q");
            }, 1L);
            return;
        }

        String qMode = plugin.config().qMode();
        logDrop(player, dropped, identity, "Q 键（丢弃已被拦截）q-mode=" + qMode);
        if ("none".equalsIgnoreCase(qMode)) {
            return;
        }
        if ("held-slot".equalsIgnoreCase(qMode)) {
            // 同样延后：此刻主手是空的，切槽扫描会漏掉被丢的那一格
            plugin.scheduler().runLater(() -> switchToNextWeapon(player), 1L);
            return;
        }

        // 关键：PlayerDropItemEvent 触发时物品已经从物品栏移除（取消丢弃后会在本 tick 末恢复），
        // 所以此刻 getItemInMainHand() 已经不是这把武器 —— 直接拿它派发只会得到
        // "主手物品没有 weapon_id"。延后 1 tick 执行，届时主手已恢复；
        // 万一没恢复就退回事件里的物品快照（身份一定是正确的）。
        plugin.scheduler().runLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            ItemStack source = plugin.items().read(hand) != null ? hand : dropped;
            plugin.skills().dispatch(player, source, SkillSlot.Q, true);
        }, 1L);
    }

    /**
     * Q 键专用日志：动作名写成 DROP。
     * 复用交互日志会把动作显示成 RIGHT_CLICK_AIR，看起来像是右键触发的，容易误判。
     */
    private void logDrop(Player player, ItemStack stack, ItemFactory.Identity identity, String note) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.getLogger().info(String.format("[input] %s DROP 手持=%s%s → %s",
                player.getName(),
                stack == null ? "?" : stack.getType(),
                identity == null ? "" : " weapon=" + identity.weaponId(),
                note));
    }

    // ---------------------------------------------------------------- F 键

    /**
     * F 键（交换副手）：可选地触发第三槽技能，同时永远不允许把插件武器换到副手
     * （副手会形成第二条技能通路）。
     *
     * <p>用 <b>LOWEST 优先级 + ignoreCancelled=false</b>，只要事件到达就一定拿到；
     * 但<b>有些服务器/插件会让这个事件根本不到达</b>（表现：按 F 毫无反应，
     * 连 debug 日志都没有）。所以默认触发方式已经换成双击潜行，
     * F 键要显式写进 <code>input.shift-right-trigger</code> 才生效。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        boolean mainIsWeapon = plugin.items().isPluginWeapon(event.getMainHandItem());
        boolean offIsWeapon = plugin.items().isPluginWeapon(event.getOffHandItem());
        trace(player, "F 键（交换副手）主手武器=" + mainIsWeapon + " 副手武器=" + offIsWeapon);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[input] " + player.getName() + " F 键事件到达（主手武器="
                    + mainIsWeapon + " 副手武器=" + offIsWeapon + " 触发方式="
                    + ThirdSlotTrigger.join(triggers()) + "）");
        }
        if (!mainIsWeapon && !offIsWeapon) {
            return;
        }
        event.setCancelled(true);

        if (!triggers().contains(ThirdSlotTrigger.F)) {
            if (plugin.config().debug()) {
                plugin.getLogger().info("[input] " + player.getName()
                        + " F 键只拦截副手交换（第三槽触发方式里没有 f）");
            }
            return;
        }
        dispatchThirdSlot(player, event.getMainHandItem(), "F 键");
    }

    // ---------------------------------------------------------------- 公共派发

    /**
     * 派发第三槽技能。所有触发方式（双击潜行 / 潜行 + Q / 双击右键 / F / 潜行右键）
     * 都走这里，保证"取消原版行为 + 冷却 + 附加增益"的行为完全一致。
     */
    private void dispatchThirdSlot(Player player, ItemStack hand, String via) {
        ItemFactory.Identity identity = plugin.items().read(hand);
        if (identity == null) {
            if (plugin.config().debug()) {
                plugin.getLogger().info("[input] " + player.getName() + " " + via
                        + "：主手不是插件武器 → 不派发第三槽");
            }
            return;
        }
        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            return;
        }
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        SkillDef def = weapon.skill(SkillSlot.SHIFT_RIGHT, mode);
        if (!def.isPresent()) {
            if (plugin.config().debug()) {
                plugin.getLogger().info("[input] " + player.getName() + " " + via
                        + "：shift-right 槽未绑定技能 → 不派发");
            }
            return;
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[input] " + player.getName() + " " + via
                    + " 派发 shift-right → " + def.type());
        }
        plugin.skills().dispatch(player, hand, SkillSlot.SHIFT_RIGHT, true);
    }

    private Set<ThirdSlotTrigger> triggers() {
        return plugin.config().thirdSlotTriggers();
    }

    /** 把选中槽切到下一件本角色武器（q-mode: held-slot）。 */
    private void switchToNextWeapon(Player player) {
        PlayerInventory inventory = player.getInventory();
        int current = inventory.getHeldItemSlot();
        for (int offset = 1; offset <= 9; offset++) {
            int slot = (current + offset) % 9;
            ItemStack stack = inventory.getItem(slot);
            ItemFactory.Identity identity = plugin.items().read(stack);
            if (identity != null && plugin.items().usableBy(stack, player)) {
                inventory.setHeldItemSlot(slot);
                WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
                if (weapon != null) {
                    plugin.fx().actionBar(player, plugin.config().messages().get("input.equip-switched",
                            "weapon", weapon.display()));
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 按键诊断

    /** 记录一条输入事件（/taketori keys 回放用）。 */
    private void trace(Player player, String note) {
        Deque<String> deque = traces.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(LocalTime.now().format(CLOCK) + "  " + note);
            while (deque.size() > TRACE_LIMIT) {
                deque.pollFirst();
            }
        }
    }

    /** 最近收到的输入事件（新的在最后）；没有记录时返回空列表。 */
    public List<String> tracesOf(UUID uuid) {
        Deque<String> deque = traces.get(uuid);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    private void debug(Player player, Action action, ItemStack hand, ItemFactory.Identity identity, String note) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.getLogger().info(String.format("[input] %s %s 主手=%s%s → %s",
                player.getName(),
                action,
                hand == null ? "?" : hand.getType(),
                identity == null ? "" : " weapon=" + identity.weaponId(),
                note));
    }
}
