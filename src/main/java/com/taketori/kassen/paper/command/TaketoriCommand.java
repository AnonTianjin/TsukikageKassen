package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.data.UuidMigration;
import com.taketori.kassen.core.lobby.LobbyAction;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.skill.ThirdSlotTrigger;
import com.taketori.kassen.paper.match.ArenaManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import com.taketori.kassen.core.character.TagManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /taketori 命令组。
 *
 * <p>排查用：</p>
 * <ul>
 *   <li>{@code /taketori debug on|off} —— 运行时开关调试日志（本次运行有效）</li>
 *   <li>{@code /taketori doctor} —— 自检：识别、绑定、Q 键行为、名字解析能力</li>
 *   <li>{@code /taketori mode} —— 手动触发当前武器的 Q 槽技能（模式/装备切换），
 *       Q 键万一被别的插件吞掉时用它验证</li>
 *   <li>{@code /taketori qmode <drop|held-slot|none>} —— 运行时切换 Q 键行为，不用改文件</li>
 * </ul>
 */
public final class TaketoriCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of(
            "reload", "give", "character", "debug", "doctor", "mode", "qmode", "skills",
            "match", "team", "arena", "lobby", "room", "moonmap", "stats", "play", "leave", "surrender",
            "editor", "menu", "ranks", "admin", "tag", "tags", "f", "sneak", "pve", "keys", "party", "rejoin",
            "shop", "buy", "overtime", "migrate");

    private static final List<String> Q_MODES = List.of("drop", "held-slot", "none");

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public TaketoriCommand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.config().messages().prefixed("command.unknown-sub"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!require(sender, "taketori.admin")) {
                    return true;
                }
                if (plugin.reloadAll(sender)) {
                    sender.sendMessage(plugin.config().messages().prefixed("command.reloaded",
                            "weapons", plugin.config().weapons().size(),
                            "characters", plugin.config().characters().size()));
                }
            }
            case "give" -> handleGive(sender, args);
            case "character" -> handleCharacter(sender, args);
            case "debug" -> handleDebug(sender, args);
            case "doctor" -> handleDoctor(sender);
            case "mode" -> handleMode(sender);
            case "qmode" -> handleQMode(sender, args);
            case "skills" -> handleSkills(sender);
            // 玩法层子命令交给 MatchCommand（同包，无需 import）
            //   pve = PVE 的难度 / 大波次 / 据点状态与切换
            //   room = 动态房间（create / delete / list）
            //   moonmap = 月面模板管理（create / import / list / load / unload）
            case "match", "team", "arena", "lobby", "stats", "pve", "room", "moonmap" ->
                    new MatchCommand(plugin).handle(sender, args);
            // 队伍商店与加时投票：玩家常用，给个短别名，内部转成 match 子命令。
            // 参数前面插一个 "match"，这样 /taketori shop clipper 与
            // /taketori match shop clipper 走同一套解析。
            case "shop", "buy" -> new MatchCommand(plugin)
                    .handle(sender, prependMatch(args));
            case "overtime" -> new MatchCommand(plugin)
                    .handle(sender, prependMatch(args));
            // 玩家自助退出：退出观战 / 退出队列（聊天栏的「退出观战」按钮执行的就是它）
            case "leave" -> new MatchCommand(plugin).handleLeave(sender);
            // 战国 3v3：划区（setkeep / settower / …）与运维（start / pause / towers / …）
            case "sengoku" -> new SengokuCommand(plugin).handle(sender, args);
            // 玩家发起/确认本队投降（对局中；半数以上在线队友同意即结束）
            case "surrender" -> new MatchCommand(plugin).handleSurrender(sender);
            // 武器数据编辑 GUI（管理员）
            case "editor" -> handleEditor(sender, args);
            // 玩家菜单（匹配 / 队伍选择 / 角色选择 / 排行榜）与总计排行榜 GUI
            case "menu" -> handleMenu(sender);
            case "ranks" -> handleRanks(sender, args);
            // 管理员菜单（把常用管理指令映射成按钮）
            case "admin" -> handleAdmin(sender);
            // 隐性标签：设置 / 查看（权重决定同队角色冲突时的优先级）
            case "tag" -> handleTag(sender, args);
            case "tags" -> handleTags(sender);
            // 手动触发第三槽（移动技能）：sneak 是 2.0 的新名字，f 保留给旧习惯与脚本
            case "f", "sneak" -> handleThirdSlot(sender);
            // 按键诊断：回放最近收到的原始输入事件（判断"按键到底有没有传到服务端"）
            case "keys" -> handleKeys(sender);
            case "play" -> handlePlay(sender);
            // 派对组队：invite/accept/kick/leave/list（整队匹配、开局整组同队）
            case "party" -> handleParty(sender, args);
            // 断线重连：对局中掉线时限内回到原房原队（上线时自动尝试，这条是手动兜底）
            case "rejoin" -> handleRejoin(sender);
            // UUID 迁移：离线键 → 正版键（先 dry-run，加 confirm 才真正执行）
            case "migrate" -> handleMigrate(sender, args);
            default -> sender.sendMessage(plugin.config().messages().prefixed("command.unknown-sub"));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.config().messages().prefixed("command.usage-give"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-not-found", "name", args[1]));
            return;
        }
        var weapon = plugin.config().weapons().get(args[2]);
        if (weapon == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.weapon-not-found", "id", args[2]));
            return;
        }
        target.getInventory().addItem(plugin.items().create(weapon, target));
        sender.sendMessage(plugin.config().messages().prefixed("command.given",
                "player", target.getName(), "weapon", weapon.display()));
    }

    private void handleCharacter(CommandSender sender, String[] args) {
        // 玩家自助：/taketori character <角色id|none>（README 里一直是玩家指令，但此前只实现了管理员形态，
        // 玩家敲这条只会拿到「用法：/taketori character <player> <characterId>」）
        if (args.length == 2 && sender instanceof Player self) {
            if (!require(sender, "taketori.play")) {
                return;
            }
            applyCharacter(sender, self, args[1]);
            return;
        }
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.config().messages().prefixed("command.usage-character"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-not-found", "name", args[1]));
            return;
        }
        applyCharacter(sender, target, args[2]);
    }

    /** 绑定 / 解除角色：玩家自助与管理员指定共用同一套同队冲突裁决与提示。 */
    private void applyCharacter(CommandSender sender, Player target, String characterId) {
        if ("none".equalsIgnoreCase(characterId)) {
            plugin.bindCharacter(target, null);
            sender.sendMessage(plugin.config().messages().prefixed("command.character-cleared", "player", target.getName()));
            return;
        }
        var character = plugin.config().characters().get(characterId);
        if (character == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.character-not-found", "id", characterId));
            return;
        }
        // 同队不允许出现相同角色：管理员设置时同样按隐性标签权重裁决，范围是目标玩家所在房间
        var room = plugin.rooms().roomOf(target);
        var decision = room != null ? room.requestRole(target, characterId)
                : new com.taketori.kassen.paper.match.room.GameRoom.RoleDecision(true, null, null);
        if (!decision.granted()) {
            sender.sendMessage(MINI.deserialize("<red>无法设置：" + decision.reason()));
            return;
        }
        plugin.bindCharacter(target, characterId);
        sender.sendMessage(plugin.config().messages().prefixed("command.character-set",
                "player", target.getName(), "character", character.display()));
        if (decision.displacedSomeone()) {
            sender.sendMessage(MINI.deserialize("<yellow>已顶替 " + decision.displaced()
                    + "（其隐性标签权重较低，角色已被解除）。"));
        }
    }

    /**
     * /taketori tag [玩家] [标签|none] —— 设置隐性标签；不带参数时列出所有标签与权重。
     */
    private void handleTag(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(MINI.deserialize("<gold>隐性标签（同一队伍抢同一角色时，权重高者优先）："));
            for (TagManager.TagDef def : plugin.tags().all()) {
                sender.sendMessage(MINI.deserialize("<gray>  " + def.id() + " → " + def.display()
                        + " <dark_gray>权重 " + def.weight()));
            }
            sender.sendMessage(MINI.deserialize("<gray>用法：<white>/taketori tag <玩家> <标签|none>"));
            sender.sendMessage(MINI.deserialize("<gray>图形界面：<white>/taketori tags"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-not-found", "name", args[1]));
            return;
        }
        String tagId = args[2];
        if ("none".equalsIgnoreCase(tagId) || "clear".equalsIgnoreCase(tagId)) {
            plugin.setTag(target.getUniqueId(), null);
            sender.sendMessage(MINI.deserialize("<yellow>已清除 " + target.getName() + " 的隐性标签（权重 0）。"));
            return;
        }
        if (!plugin.tags().has(tagId)) {
            sender.sendMessage(MINI.deserialize("<red>没有这个标签：" + tagId + "（用 /taketori tag 查看可用标签）"));
            return;
        }
        plugin.setTag(target.getUniqueId(), tagId);
        sender.sendMessage(MINI.deserialize("<green>已把 " + target.getName() + " 的标签设为 "
                + plugin.tags().displayOf(tagId) + " <dark_gray>（权重 " + plugin.tags().weightOf(tagId) + "）"));
    }

    /** /taketori tags —— 打开隐性标签设置界面（选玩家 → 选标签）。 */
    private void handleTags(CommandSender sender) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        plugin.tagMenu().openPlayers(player);
    }

    /**
     * /taketori f —— 手动触发当前武器的第三槽技能（原 Shift+右键 / F 键）。
     *
     * <p>用途有两个：F 键被别的插件吞掉时的兜底；以及快速确认"第三槽技能本身是否正常"
     * （能触发说明技能没坏，坏的只是按键路径）。</p>
     */
    private void handleThirdSlot(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>该指令需要玩家执行。"));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = plugin.items().read(hand);
        if (identity == null) {
            sender.sendMessage(MINI.deserialize("<red>主手不是插件武器（角色装备由开局发放；管理可用 /taketori give 直接发放）。"));
            return;
        }
        var weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            sender.sendMessage(MINI.deserialize("<red>weapon_id 在 weapons.yml 里不存在：" + identity.weaponId()));
            return;
        }
        var def = weapon.skill(SkillSlot.SNEAK);
        if (!def.isPresent()) {
            sender.sendMessage(MINI.deserialize("<yellow>当前武器 <white>" + weapon.id()
                    + "</white> 的第三槽（双击潜行）没有绑定技能。"));
            return;
        }
        plugin.skills().dispatch(player, hand, SkillSlot.SNEAK, true);
        sender.sendMessage(MINI.deserialize("<gray>已手动触发第三槽：<white>" + def.type()
                + "</white> <dark_gray>(触发方式：" + ThirdSlotTrigger.display()
                + "，用 /taketori keys 看按键是否真的传到了服务端)"));
    }

    /**
     * /taketori keys —— 按键诊断：回放最近收到的原始输入事件。
     *
     * <p>用来回答"我按了键但技能没出来"到底断在哪一环：</p>
     * <ul>
     *   <li>列表里<b>连对应记录都没有</b> → 那个按键根本没传到服务端（被别的插件或客户端吞了），
     *       换一种触发方式（见 config.yml 的 <code>input.shift-right-trigger</code>）；</li>
     *   <li>有记录但没放出技能 → 记录里会写明原因（主手不是插件武器 / 槽位未绑定 / 冷却中）。</li>
     * </ul>
     */
    private void handleKeys(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MINI.deserialize("<red>请在游戏内执行（要看的是你自己的按键记录）。"));
            return;
        }
        String raw = plugin.config().shiftRightTrigger();
        sender.sendMessage(MINI.deserialize("<gold>===== 按键诊断 ====="));
        sender.sendMessage(MINI.deserialize("<gray>第三槽触发方式：<white>" + ThirdSlotTrigger.display()
                + "</white> <dark_gray>(config.yml: " + raw + ")"));
        List<String> traces = plugin.input() == null ? List.of() : plugin.input().tracesOf(player.getUniqueId());
        if (traces.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<yellow>还没有收到任何按键事件。"));
        } else {
            sender.sendMessage(MINI.deserialize("<gray>最近收到的输入（新的在下面）："));
            for (String line : traces) {
                sender.sendMessage(MINI.deserialize("<dark_gray>  " + line));
            }
        }
        sender.sendMessage(MINI.deserialize("<dark_gray>依次按一遍你想用的键（潜行 / 右键 / Q / F），再执行一次本指令："
                + "列表里没有对应记录 = 那个键没传到服务端，换一种触发方式。"));
    }

    /**
     * /taketori debug [on|off]
     * 不带参数 = 查看当前状态；带参数 = 运行时开关（不落盘，重启后回到 config.yml 的值）。
     */
    private void handleDebug(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        var messages = plugin.config().messages();
        if (args.length >= 2) {
            String toggle = args[1].toLowerCase(Locale.ROOT);
            if ("on".equals(toggle) || "true".equals(toggle)) {
                plugin.config().setDebug(true);
                sender.sendMessage(messages.prefixed("command.debug-runtime-on"));
                sender.sendMessage(messages.prefixed("command.debug-persist"));
                return;
            }
            if ("off".equals(toggle) || "false".equals(toggle)) {
                plugin.config().setDebug(false);
                sender.sendMessage(messages.prefixed("command.debug-runtime-off"));
                return;
            }
        }
        if (!plugin.config().debug()) {
            sender.sendMessage(messages.prefixed("command.debug-locked"));
            return;
        }
        if (sender instanceof Player player) {
            sender.sendMessage(messages.prefixed("command.debug-header", "info", plugin.describe(player)));
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(messages.prefixed("command.debug-header",
                        "info", player.getName() + " " + plugin.describe(player)));
            }
        }
    }

    /** /taketori doctor：任何人都可以用，普通玩家只看自己那一部分。 */
    private void handleDoctor(CommandSender sender) {
        if (!require(sender, "taketori.play")) {
            return;
        }
        var messages = plugin.config().messages();
        Player player = sender instanceof Player p ? p : null;
        boolean includeProblems = sender.hasPermission("taketori.admin");
        sender.sendMessage(messages.get("command.doctor-header"));
        for (String line : plugin.doctorReport(player, includeProblems)) {
            sender.sendMessage(messages.get("command.doctor-line", "line", line));
        }
        sender.sendMessage(messages.get("command.doctor-footer"));
    }

    /**
     * /taketori mode：手动触发当前手持武器的 Q 槽技能。
     * 用途：Q 键依赖"丢弃物品"事件，若被其他插件吞掉，用这条命令可以照常切换并确认技能本身没问题。
     */
    private void handleMode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        if (!require(sender, "taketori.play")) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = plugin.items().read(hand);
        if (identity == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.mode-not-weapon"));
            return;
        }
        // 2.0：武器不再有模式，Q 槽下线；保留了命令名，执行时给出明确说明
        sender.sendMessage(MINI.deserialize("<yellow>2.0 已移除武器模式切换（Q 槽下线）。"
                + "每把武器固定三个技能：左键 / 右键 / 双击潜行。"));
    }

    /** /taketori qmode <drop|held-slot|none>：运行时切换 Q 键行为。 */
    private void handleQMode(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        var messages = plugin.config().messages();
        if (args.length < 2) {
            sender.sendMessage(messages.prefixed("command.qmode-usage", "current", plugin.config().qMode()));
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!Q_MODES.contains(mode)) {
            sender.sendMessage(messages.prefixed("command.qmode-invalid"));
            return;
        }
        plugin.config().setQMode(mode);
        sender.sendMessage(messages.prefixed("command.qmode-set", "mode", mode));
        sender.sendMessage(messages.prefixed("command.qmode-persist"));
    }

    /** /taketori skills：列出当前手持武器的四个技能与冷却状态。 */
    private void handleSkills(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        if (!require(sender, "taketori.play")) {
            return;
        }
        var messages = plugin.config().messages();
        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = plugin.items().read(hand);
        if (identity == null) {
            sender.sendMessage(messages.prefixed("command.skills-empty"));
            return;
        }
        var weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            sender.sendMessage(messages.prefixed("command.weapon-not-found", "id", identity.weaponId()));
            return;
        }
        sender.sendMessage(messages.get("command.skills-header"));
        sender.sendMessage(messages.get("command.skills-line",
                "slot", "武器",
                "name", weapon.display(),
                "state", weapon.hasPassive()
                        ? "<gray>被动 <white>" + weapon.passive().display()
                        : "<dark_gray>无被动"));

        for (SkillSlot slot : SkillSlot.values()) {
            var def = weapon.skill(slot);
            String name;
            String state;
            if (!def.isPresent()) {
                name = "—";
                state = "<dark_gray>未绑定（走原版行为）";
            } else {
                name = def.str("display", def.type());
                double remaining = plugin.skills().remainingSeconds(player, weapon.id(), slot);
                state = remaining > 0.0D
                        ? "<red>" + plugin.skills().progressBar(remaining, def.cooldownSeconds()) + " "
                                + com.taketori.kassen.paper.skill.SkillManager.formatSeconds(remaining) + "s"
                        : "<green>就绪 <dark_gray>(" + def.type() + ")";
            }
            sender.sendMessage(messages.get("command.skills-line",
                    "slot", slot.key(), "name", name, "state", state));
        }
        sender.sendMessage(messages.get("command.skills-footer"));
    }

    private void handlePlay(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        var profile = plugin.config().characters().profile(player.getUniqueId());
        if (!profile.hasCharacter()) {
            sender.sendMessage(plugin.config().messages().prefixed("input.no-character"));
            return;
        }
        var character = plugin.config().characters().get(profile.characterId());
        sender.sendMessage(plugin.config().messages().prefixed("command.play-hint",
                "character", character == null ? profile.characterId() : character.display(),
                "weapons", character == null ? "-" : String.join(", ", character.weapons())));
    }

    /**
     * /taketori party <invite <玩家>|accept [房主]|kick <玩家>|leave|list> —— 派对组队。
     * 组队后快速匹配整队进同一房间，开局分队时整组同队（人数差 ≤1 的前提下尽量不拆散）。
     * 无参数 / list：查看当前派对状态。
     */
    private void handleParty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        var party = plugin.party();
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "invite" -> {
                if (args.length < 3) {
                    sender.sendMessage(MINI.deserialize("<red>用法：<white>/taketori party invite <玩家>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    sender.sendMessage(plugin.config().messages().prefixed(
                            "command.player-not-found", "name", args[2]));
                    return;
                }
                party.invite(player, target);
            }
            case "accept" -> {
                UUID leaderId;
                if (args.length >= 3) {
                    Player leader = Bukkit.getPlayerExact(args[2]);
                    if (leader == null) {
                        sender.sendMessage(plugin.config().messages().prefixed(
                                "command.player-not-found", "name", args[2]));
                        return;
                    }
                    leaderId = leader.getUniqueId();
                } else {
                    // 不带房主名：接受最近一个有效邀请
                    leaderId = party.pendingInviteLeader(player.getUniqueId());
                    if (leaderId == null) {
                        sender.sendMessage(plugin.config().messages().prefixed("party.no-invite"));
                        return;
                    }
                }
                party.accept(player, leaderId);
            }
            case "kick" -> {
                if (args.length < 3) {
                    sender.sendMessage(MINI.deserialize("<red>用法：<white>/taketori party kick <玩家>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                party.kick(player, target == null ? null : target.getUniqueId());
            }
            case "leave" -> party.leave(player.getUniqueId());
            case "list" -> {
                var current = party.partyOf(player.getUniqueId());
                if (current == null) {
                    sender.sendMessage(plugin.config().messages().prefixed("party.list-none"));
                    return;
                }
                StringBuilder names = new StringBuilder();
                for (UUID memberId : current.members()) {
                    if (!names.isEmpty()) {
                        names.append("<gray>, </gray>");
                    }
                    if (current.leader().equals(memberId)) {
                        names.append("<gold>★</gold>");
                    }
                    names.append(playerName(memberId));
                }
                sender.sendMessage(plugin.config().messages().prefixed("party.list",
                        "leader", playerName(current.leader()), "members", names.toString(),
                        "size", current.members().size(), "max", party.maxSize()));
            }
            default -> sender.sendMessage(MINI.deserialize(
                    "<red>用法：<white>/taketori party <invite|accept|kick|leave|list>"));
        }
    }

    /** /taketori rejoin —— 手动触发断线重连（上线时已自动尝试；这条用于自动恢复没生效时的兜底）。 */
    private void handleRejoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        if (!plugin.rejoin().tryRejoin(player)) {
            sender.sendMessage(plugin.config().messages().prefixed("rejoin.no-session"));
        }
    }

    /**
     * /taketori migrate uuid &lt;旧UUID|旧名字&gt; &lt;新UUID&gt; [confirm] —— 一次性 UUID 迁移。
     *
     * <p>旧键写名字时按 Bukkit 的离线规则（{@code OfflinePlayer:<name>} 的 MD5 v3）算出来，
     * 正好就是离线服里那个「名字派生」的 UUID；新键必须是 UUID，正版 UUID 可以用
     * {@code https://api.mojang.com/users/profiles/minecraft/<name>} 查（返回的 32 位可直接粘）。</p>
     *
     * <p>不带 {@code confirm} 只做盘点（dry-run），一个字节都不写。</p>
     */
    private void handleMigrate(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 4 || !"uuid".equalsIgnoreCase(args[1])) {
            sender.sendMessage(MINI.deserialize(
                    "<red>用法：<white>/taketori migrate uuid <旧UUID|旧名字> <新UUID> [confirm]"));
            sender.sendMessage(MINI.deserialize("<gray>旧键可写玩家名（按离线 UUID 计算）；新键用正版 UUID，"
                    + "可从 <white>api.mojang.com/users/profiles/minecraft/&lt;名字&gt;</white> 取。"));
            return;
        }
        UUID oldId = resolveOldId(args[2]);
        if (oldId == null) {
            sender.sendMessage(MINI.deserialize("<red>无法解析旧 UUID 或旧名字：<white>" + args[2]));
            return;
        }
        UUID newId = parseUuid(args[3]);
        if (newId == null) {
            sender.sendMessage(MINI.deserialize("<red>新 UUID 不合法：<white>" + args[3]
                    + "<gray>（需要 32 位十六进制，带不带中划线都行）"));
            return;
        }
        if (newId.equals(oldId)) {
            sender.sendMessage(MINI.deserialize("<yellow>新旧 UUID 相同，无需迁移。"));
            return;
        }

        // 迁移要读「已经落盘」的数据：先把内存里的档案与战绩刷下去
        plugin.dataStore().saveAll();
        plugin.stats().save();

        UuidMigration migration = new UuidMigration(plugin);
        UuidMigration.Plan plan = migration.plan(oldId, newId);
        if (plan.empty()) {
            sender.sendMessage(MINI.deserialize("<yellow>旧键 <white>" + oldId
                    + "</white> 上没有角色绑定、标签或战绩，无需迁移。"));
            return;
        }

        if (args.length < 5 || !"confirm".equalsIgnoreCase(args[4])) {
            sender.sendMessage(MINI.deserialize("<gold>===== UUID 迁移预演（未写入）====="));
            sender.sendMessage(MINI.deserialize("<gray>旧键：<white>" + oldId
                    + (plan.oldName() == null ? "" : " <dark_gray>(" + plan.oldName() + ")")));
            sender.sendMessage(MINI.deserialize("<gray>新键：<white>" + newId));
            sender.sendMessage(MINI.deserialize("<gray>角色：<white>"
                    + (plan.characterId() == null ? "无" : plan.characterId())
                    + " <gray>标签：<white>" + (plan.tagId() == null ? "无" : plan.tagId())));
            sender.sendMessage(MINI.deserialize("<gray>战绩：<white>" + plan.stats().size()
                    + " 项，合计 " + plan.statTotal()));
            sender.sendMessage(MINI.deserialize("<yellow>确认无误后执行：<white>/taketori migrate uuid "
                    + args[2] + " " + newId + " confirm"));
            return;
        }

        UuidMigration.Result result = migration.apply(plan, sender.getName());
        // 内存里还挂着旧键：重新载入，排行榜与角色查询立刻切到新键
        plugin.stats().load();
        sender.sendMessage(MINI.deserialize("<green>迁移完成：<white>"
                + (result.characterMoved() ? "角色已搬 " : "")
                + (result.tagMoved() ? "标签已搬 " : "")
                + "战绩 " + result.statKeys() + " 项（合计 " + result.statTotal() + "）"));
        sender.sendMessage(MINI.deserialize("<gray>旧键已清空；审计记录写入 <white>data/migrations.log</white>。"
                + "<gray>若该玩家在线，请让他重连一次。"));
    }

    /** 旧键：UUID 直接解析；名字按 Bukkit 离线规则（{@code OfflinePlayer:name} 的 MD5 v3）计算。 */
    private static UUID resolveOldId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        UUID parsed = parseUuid(raw);
        if (parsed != null) {
            return parsed;
        }
        String name = raw.trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return null;   // 基岩版前缀名等非标准名不能靠名字反推，直接写 UUID
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** 32 位十六进制（可带中划线）；Mojang 接口返回的无横线形式也接受。 */
    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().replace("-", "");
        if (text.length() != 32) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(text.substring(0, 16), 16),
                    Long.parseUnsignedLong(text.substring(16), 16));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 成员显示名：派对成员断线即被移出，通常都在线；离线兜底显示 id 前缀。 */
    private String playerName(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null ? player.getName() : id.toString().substring(0, 8);
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(plugin.config().messages().prefixed("command.no-permission"));
        return false;
    }

    /** /taketori editor [武器id] —— 打开武器数据编辑 GUI（改数值 → 写回 weapons.yml → 自动 reload）。 */
    private void handleEditor(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        if (args.length >= 2) {
            plugin.editor().openWeapon(player, args[1].toLowerCase(Locale.ROOT));
        } else {
            plugin.editor().open(player);
        }
    }

    /** /taketori menu —— 打开玩家菜单（匹配 / 队伍选择 / 角色选择 / 排行榜）。 */
    private void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        plugin.playerMenu().open(player);
    }

    /** /taketori ranks [类别] —— 打开总计排行榜 GUI。 */
    private void handleRanks(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        plugin.statsMenu().open(player, com.taketori.kassen.paper.match.StatsTracker.parse(
                args.length > 1 ? args[1] : "score"), 1);
    }

    /** /taketori admin —— 管理员菜单（对局控制 / 场地 / 大厅 / 维护）。 */
    private void handleAdmin(CommandSender sender) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        plugin.adminMenu().open(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : SUB_COMMANDS) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("editor")) {
            for (String id : plugin.config().weapons().ids()) {
                if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("shop") || args[0].equalsIgnoreCase("buy"))) {
            return startsWith(List.of("clipper", "mine", "setnpc"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("shop")
                && args[1].equalsIgnoreCase("setnpc")) {
            return startsWith(List.of("red", "blue"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ranks")) {
            return startsWith(List.of("score", "kills", "minion", "base", "deaths", "matches", "wins", "best"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            for (String option : List.of("on", "off")) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("qmode")) {
            for (String option : Q_MODES) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("character"))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(player.getName());
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String id : plugin.config().weapons().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("character")) {
            for (String id : plugin.config().characters().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            result.add("none");
            return result;
        }
        // 玩法层补全：避免手打错参数（"队伍或编号无效"最常见的来源）
        if (args[0].equalsIgnoreCase("match")) {
            if (args.length == 2) {
                return startsWith(List.of("start", "force", "stop", "status", "mode"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("mode")) {
                return startsWith(List.of("pvp", "pve"), args[2]);
            }
            // start 的第三个参数可以是 force 或场地 id；force|stop 第三参、mode 第四参补场地 id
            boolean roomIdHere = (args.length == 3
                            && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("force")
                            || args[1].equalsIgnoreCase("stop")))
                    || (args.length == 4 && args[1].equalsIgnoreCase("mode"));
            if (roomIdHere) {
                int index = args.length - 1;
                if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                    result.addAll(startsWith(List.of("force"), args[2]));
                }
                for (var room : plugin.rooms().rooms()) {
                    if (room.id().startsWith(args[index].toLowerCase(Locale.ROOT))) {
                        result.add(room.id());
                    }
                }
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("team")) {
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        result.add(player.getName());
                    }
                }
            } else if (args.length == 3) {
                return startsWith(List.of("red", "blue", "none"), args[2]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length == 2) {
                return startsWith(List.of("setup", "pos1", "pos2", "wand",
                        "setminion", "delminion", "setbase", "delbase", "setspawn",
                        "setloot", "delloot", "setoutpost", "deloutpost", "setwait",
                        "create", "select", "delete", "enable", "disable", "list", "status"), args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("setbase")
                    || args[1].equalsIgnoreCase("delbase")
                    || args[1].equalsIgnoreCase("setspawn"))) {
                return startsWith(List.of("red", "blue"), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("setup")) {
                List<String> options = new ArrayList<>(List.of("done", "cancel"));
                options.addAll(plugin.arena().all().keySet());
                return startsWith(options, args[2]);
            }
            // 按场地 id 操作的子命令补全已有场地
            if (args.length == 3 && List.of("select", "delete", "remove", "enable", "disable")
                    .contains(args[1].toLowerCase(Locale.ROOT))) {
                return startsWith(new ArrayList<>(plugin.arena().all().keySet()), args[2]);
            }
            if (args.length == 4 && (args[1].equalsIgnoreCase("setbase")
                    || args[1].equalsIgnoreCase("delbase"))) {
                // 补全的编号范围跟着当前选中场地的 base.count-per-team 走（auto 时按实际编号顺延）；
                // 没选场地（控制台/未执行 arena select）时按固定上界
                int ceiling = com.taketori.kassen.paper.match.ArenaDef.BASES_PER_TEAM;
                if (sender instanceof Player arenaPlayer) {
                    var selected = plugin.arena().selected(arenaPlayer.getUniqueId());
                    if (selected != null) {
                        ceiling = selected.baseIndexCeiling();
                    }
                }
                List<String> numbers = new ArrayList<>();
                for (int i = 1; i <= ceiling; i++) {
                    numbers.add(Integer.toString(i));
                }
                return startsWith(numbers, args[3]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("room")) {
            if (args.length == 2) {
                return startsWith(List.of("create", "delete", "list"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
                return startsWith(new ArrayList<>(plugin.arena().all().keySet()), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                List<String> ids = new ArrayList<>();
                for (var room : plugin.rooms().rooms()) {
                    ids.add(room.id());
                }
                return startsWith(ids, args[2]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("moonmap")) {
            if (args.length == 2) {
                return startsWith(List.of("create", "import", "list", "load", "unload"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("import")) {
                // 补全服务器世界容器里的世界文件夹
                List<String> worlds = new ArrayList<>();
                File[] children = Bukkit.getWorldContainer().listFiles(File::isDirectory);
                if (children != null) {
                    for (File child : children) {
                        if (new File(child, "level.dat").isFile()) {
                            worlds.add(child.getName());
                        }
                    }
                }
                return startsWith(worlds, args[2]);
            }
            if (args.length == 3) {
                List<String> names = new ArrayList<>();
                File moonmaps = new File(plugin.getDataFolder(), "moonmaps");
                File[] children = moonmaps.listFiles(File::isDirectory);
                if (children != null) {
                    for (File child : children) {
                        names.add(child.getName());
                    }
                }
                return startsWith(names, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("import")) {
                return startsWith(new ArrayList<>(plugin.arena().all().keySet()), args[3]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("lobby")) {
            if (args.length == 2) {
                return startsWith(List.of("setspawn", "pos1", "pos2", "setregion", "addsign", "addstatus",
                        "removesign", "list", "join", "leave", "spectate"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("addsign")) {
                return startsWith(LobbyAction.tabCompletions(), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("addstatus")) {
                return startsWith(new ArrayList<>(plugin.arena().all().keySet()), args[2]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("party")) {
            if (args.length == 2) {
                return startsWith(List.of("invite", "accept", "kick", "leave", "list"), args[1]);
            }
            if (args.length == 3 && List.of("invite", "kick", "accept")
                    .contains(args[1].toLowerCase(Locale.ROOT))) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                        result.add(player.getName());
                    }
                }
            }
            return result;
        }
        return result;
    }

    /**
     * 把 {@code /taketori shop …} 改写成 {@code /taketori match shop …}。
     *
     * <p>参数约定与 Bukkit 一致：{@code args[0]} 是子命令、不含指令名本身。
     * 所以这里只是在最前面插一个 {@code "match"}，其余原样后移。</p>
     */
    private static String[] prependMatch(String[] args) {
        String[] rewritten = new String[args.length + 1];
        rewritten[0] = "match";
        System.arraycopy(args, 0, rewritten, 1, args.length);
        return rewritten;
    }

    /** 前缀过滤的补全辅助（大小写不敏感）。 */
    private List<String> startsWith(List<String> options, String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                result.add(option);
            }
        }
        return result;
    }
}
