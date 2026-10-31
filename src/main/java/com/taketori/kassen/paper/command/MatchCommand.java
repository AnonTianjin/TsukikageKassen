package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.lobby.LobbyAction;
import com.taketori.kassen.core.match.BaseArgParser;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.StatsTracker;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 玩法层指令：对局控制、分队、场地划定、历史战绩。
 *
 * <pre>
 * /taketori match start|stop [原因]|status
 * /taketori team &lt;玩家&gt; &lt;red|blue|none&gt;
 * /taketori arena pos1|pos2                     —— 用当前位置设置选区两个角点
 * /taketori arena setminion                     —— 把选区设为小怪刷新区
 * /taketori arena setbase &lt;red|blue&gt; [1-3]    —— 把选区设为某队第 N 个基地（编号可省略）
 * /taketori arena setspawn &lt;red|blue&gt;           —— 用当前位置设置某队出生点
 * /taketori arena list                          —— 查看已配置的场地
 * /taketori stats [数量]                         —— 跨局历史排行榜
 * </pre>
 *
 * <p>队伍与编号都做了宽容解析：<code>setbase 1 red</code>（写反）、<code>setbase red</code>（省略编号）、
 * <code>setbase 红 2</code>（中文队伍）、全角数字都能用；参数确实不对时才报错，并且会回显收到的是什么。</p>
 */
public final class MatchCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public MatchCommand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean handle(CommandSender sender, String[] args) {
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "match" -> handleMatch(sender, args);
            case "team" -> handleTeam(sender, args);
            case "arena" -> handleArena(sender, args);
            case "lobby" -> handleLobby(sender, args);
            case "stats" -> handleStats(sender, args);
            case "pve" -> handlePve(sender, args);
            case "room" -> handleRoom(sender, args);
            case "moonmap" -> handleMoonmap(sender, args);
            default -> send(sender, "<red>未知子命令。");
        }
        return true;
    }

    // ---------------------------------------------------------------- room（动态房间）

    /**
     * /taketori room —— 动态房间：create [模板id]（所有玩家，创建后自动进房）、
     * delete [房间id]（房主删自己的等待房，管理员可删任意等待房）、不带参数列出全部房间。
     */
    private void handleRoom(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>该指令需要玩家执行。");
                    return;
                }
                // 无参数：打开创建房间配置 GUI（选模板 + 选模式）
                String templateId = args.length >= 3 ? args[2] : null;
                if (templateId == null) {
                    plugin.roomCreateMenu().open(player);
                    return;
                }
                SengokuMode mode = null;   // null = 沿用全局
                if (args.length >= 4) {
                    mode = SengokuMode.parse(args[3], null);
                    if (mode == null) {
                        send(sender, "<red>模式 '" + args[3] + "' 不合法。"
                                + " <gray>可用：<white>pvp</white> | <white>pve</white> | <white>sengoku_3v3</white>");
                        return;
                    }
                }
                plugin.lobby().createRoom(player, templateId, mode);
            }
            case "delete" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>该指令需要玩家执行。");
                    return;
                }
                GameRoom own = plugin.rooms().roomOf(player);
                String roomId = args.length >= 3 ? args[2] : (own == null ? null : own.id());
                if (roomId == null) {
                    send(sender, "<gray>你当前不在任何房间里；管理员可指定房间 id：<white>/taketori room delete <房间id>");
                    return;
                }
                plugin.lobby().deleteRoom(player, roomId);
            }
            default -> {
                send(sender, "<gold>===== 房间列表（" + plugin.rooms().rooms().size() + "）=====");
                send(sender, "<gray>用法：<white>/taketori room create [模板id] [pvp|pve|sengoku_3v3]</white> <gray>| <white>delete [房间id]</white>");
                if (plugin.rooms().rooms().isEmpty()) {
                    send(sender, "<gray>还没有房间：<white>/taketori room create [模板id] [模式]</white> 创建，"
                            + "或在菜单里点「降临月宫」。");
                }
                for (GameRoom room : plugin.rooms().rooms()) {
                    send(sender, "<gray>[" + room.id() + "] <white>" + room.display()
                            + " <gray>：<white>" + phaseText(room)
                            + " <gray>模式 <white>" + (room.isPve() ? "PVE" : "PVP")
                            + " <gray>人数 <white>" + (room.isRunning() ? room.onlineParticipantCount() : room.waitingCount())
                            + "/" + room.maxPlayers()
                            + (room.creatorId() != null ? " <dark_gray>房主 " + room.nameOf(room.creatorId()) : ""));
                }
            }
        }
    }

    // ---------------------------------------------------------------- moonmap（月面模板管理）

    /** /taketori moonmap create|import|list|load|unload —— 月面模板的生成、导入与编辑世界加载/保存。 */
    private void handleMoonmap(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>月面模板管理需要玩家执行（要用到选区与位置）。");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "create" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori moonmap create <模板名>（生成平坦模板世界到 moonmaps/<模板名>）");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (requireSafeTemplateName(sender, name)) {
                    plugin.rooms().createMoonmap(player, name);
                }
            }
            case "import" -> {
                if (args.length < 4) {
                    send(sender, "<gray>用法：/taketori moonmap import <世界名> <场地id>");
                    send(sender, "<dark_gray>  把服务器世界容器里的世界文件夹复制为模板 moonmaps/<场地id>"
                            + "（世界加载着也没关系，会先自动存盘再复制）。");
                    return;
                }
                if (!args[2].matches("[A-Za-z0-9_-]{1,64}")) {
                    send(sender, "<red>世界名只能用字母、数字、下划线、连字符。");
                    return;
                }
                String arenaId = args[3].toLowerCase(Locale.ROOT);
                if (requireSafeTemplateName(sender, arenaId)) {
                    plugin.rooms().importMoonmap(player, args[2], arenaId);
                }
            }
            case "load" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori moonmap load <模板名>（复制 moonmaps/<模板名> 为编辑世界 k_tpl_<模板名>）");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (requireSafeTemplateName(sender, name)) {
                    plugin.rooms().loadMoonmap(player, name);
                }
            }
            case "unload" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori moonmap unload <模板名>（保存并卸载编辑世界，写回 moonmaps/<模板名>）");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (requireSafeTemplateName(sender, name)) {
                    plugin.rooms().unloadMoonmap(player, name);
                }
            }
            default -> listMoonmaps(sender);
        }
    }

    /** 模板名会直接拼进 moonmaps/ 与世界容器路径，指令入口就拦下路径分隔符与 ..。 */
    private boolean requireSafeTemplateName(CommandSender sender, String name) {
        if (!name.matches("[a-z0-9_-]{1,32}")) {
            send(sender, "<red>模板名只能用小写字母、数字、下划线、连字符（≤32 位）。");
            return false;
        }
        return true;
    }

    /** moonmap list：逐模板输出地图文件夹与 arenas.yml 定义的对应状态（_bak 备份目录不列出）。 */
    private void listMoonmaps(CommandSender sender) {
        File dir = new File(plugin.getDataFolder(), "moonmaps");
        File[] all = dir.isDirectory() ? dir.listFiles(File::isDirectory) : null;
        List<File> children = new ArrayList<>();
        if (all != null) {
            for (File child : all) {
                if (!child.getName().endsWith("_bak")) {
                    children.add(child);
                }
            }
        }
        send(sender, "<gold>===== 月面模板（" + children.size() + "）=====");
        if (children.isEmpty()) {
            send(sender, "<gray>还没有模板：把世界文件夹放入 <white>plugins/TsukikageKassen/moonmaps/<模板名>/</white>，");
            send(sender, "<gray>用 <white>/taketori moonmap create <模板名></white> 直接生成一张平坦模板，");
            send(sender, "<gray>或把服务器已有的世界直接导入：<white>/taketori moonmap import <世界名> <模板名></white>。");
            send(sender, "<gray>然后 <white>/taketori arena setup <模板名></white> 一条龙划定"
                    + "（或 <white>moonmap load</white> 单独加载），最后 <white>setup done</white> / <white>moonmap unload</white> 保存。");
            return;
        }
        for (File child : children) {
            var def = plugin.arena().get(child.getName());
            String state = def == null ? "<dark_gray>未划定（arenas.yml 无条目）"
                    : def.enabled() ? (def.isReady() ? "<green>就绪·开放" : "<yellow>未就绪·开放") : "<dark_gray>已关闭";
            send(sender, "<gold>[" + child.getName() + "] " + state
                    + (plugin.rooms().isTemplateInUse(child.getName()) ? " <red>使用中" : ""));
            if (def != null && !def.isReady()) {
                send(sender, "<yellow>  还缺：<white>" + def.missingHint());
            }
        }
    }

    // ---------------------------------------------------------------- match

    private void handleMatch(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        // 商店与加时投票是玩家自己的操作，必须在 admin 门槛之前分流：
        // 否则普通玩家连 /taketori shop 都会被「你没有权限」挡掉。
        if ("shop".equals(action) || "buy".equals(action)) {
            handleShop(sender, args);
            return;
        }
        if ("overtime".equals(action)) {
            handleOvertimeVote(sender);
            return;
        }
        if (!require(sender, "taketori.admin")) {
            return;
        }
        switch (action) {
            case "start" -> {
                boolean force = false;
                for (int i = 2; i < args.length; i++) {
                    if (isForceFlag(args[i])) {
                        force = true;
                    }
                }
                startMatch(sender, resolveRoomArg(sender, args, 2), force);
            }
            case "force" -> startMatch(sender, resolveRoomArg(sender, args, 2), true);
            case "stop" -> stopMatch(sender, args);
            case "status" -> sendStatus(sender);
            case "mode" -> setMode(sender, args);
            case "difficulty", "diff" -> setDifficulty(sender, args);
            default -> send(sender, "<gray>用法：/taketori match start [房间id] [force] | force [房间id]"
                    + " | stop [房间id] [原因] | status | mode <pvp|pve> [房间id]"
                    + " | difficulty <easy|normal|hard>");
        }
    }

    /**
     * 队伍商店：{@code /taketori shop}（等价 {@code /taketori buy}）。
     *
     * <p>子命令：无参数 = 打开商店界面；{@code clipper|剪刀|拆弹钳} 与 {@code mine|地雷}
     * 是免界面的快捷购买；{@code setnpc <red|blue>} 把商人 NPC 挪到脚下（管理员）。</p>
     */
    private void handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该指令需要玩家执行。");
            return;
        }
        String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        if ("setnpc".equals(sub)) {
            if (!require(sender, "taketori.admin")) {
                return;
            }
            if (plugin.shopNpc() == null) {
                send(sender, "<red>商店 NPC 模块未加载。");
                return;
            }
            TeamId team = args.length > 3 ? TeamId.byName(args[3]) : null;
            if (team == null) {
                send(sender, "<red>用法：<white>/taketori shop setnpc <red|blue>");
                return;
            }
            plugin.shopNpc().setNpcLocation(team, player.getLocation());
            send(sender, "<green>已把 " + team.colorTag() + team.display()
                    + "</color> <green>的商人挪到当前位置（坐标已写入配置）。");
            send(sender, "<gray>商人只负责开店，卖什么由队伍决定：<red>红队买拆弹钳"
                    + "</red> <gray>，<blue>蓝队买地雷</blue> <gray>。");
            return;
        }
        if (sub.isEmpty()) {
            if (plugin.shopMenu() == null) {
                send(sender, "<red>商店界面未加载。");
                return;
            }
            plugin.shopMenu().open(player);
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            send(sender, "<red>你不在进行中的对局中，无法购买道具。");
            send(sender, "<gray>用法：<white>/taketori shop <clipper|mine></white>");
            return;
        }
        switch (sub) {
            case "clipper", "剪刀", "拆弹钳" -> room.buyDefuseClipper(player);
            case "mine", "地雷" -> room.buyLandMine(player);
            default -> {
                send(sender, "<red>没有这种商品：<white>" + sub + "</white><red>。");
                sender.sendMessage(plugin.config().messages().prefixed("command.usage-shop"));
            }
        }
    }

    /** 加时赛投票：{@code /taketori overtime}（等价 {@code /taketori match overtime}）。 */
    private void handleOvertimeVote(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该指令需要玩家执行。");
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            send(sender, "<red>你不在进行中的对局中。");
            return;
        }
        if (!room.isOvertimeVoteOpen()) {
            send(sender, "<gray>当前没有进行中的加时投票。");
            return;
        }
        room.voteOvertime(player);
    }

    /**
     * 在参数里找一个真实存在的场地 id 作为目标房间；没写就回落
     * 「执行者所在房 → default → 第一个房」。
     */
    private GameRoom resolveRoomArg(CommandSender sender, String[] args, int from) {
        for (int i = from; i < args.length; i++) {
            GameRoom explicit = plugin.rooms().room(args[i].toLowerCase(Locale.ROOT));
            if (explicit != null) {
                return explicit;
            }
        }
        return resolveTargetRoom(sender instanceof Player player ? player : null);
    }

    /** /taketori match stop [房间id] [原因]：第一个参数若等于某房间 id 则作用该房，否则整串当原因。 */
    private void stopMatch(CommandSender sender, String[] args) {
        GameRoom target;
        String reason;
        if (args.length > 2 && plugin.rooms().room(args[2].toLowerCase(Locale.ROOT)) != null) {
            target = plugin.rooms().room(args[2].toLowerCase(Locale.ROOT));
            reason = args.length > 3
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                    : "管理员结束";
        } else if (!(sender instanceof Player)) {
            // 控制台没有"所在房间"可回落：无显式 id 会命中第一个房间，极易误伤
            send(sender, "<red>控制台必须指定房间 id：<white>/taketori match stop <房间id> [原因]");
            return;
        } else {
            target = resolveTargetRoom((Player) sender);
            reason = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                    : "管理员结束";
        }
        if (target == null) {
            send(sender, "<red>没有可用房间。");
            return;
        }
        String display = target.display();
        if (target.stop(reason)) {
            send(sender, "<yellow>已结束房间 " + display + "。");
        } else {
            send(sender, "<gray>房间 " + display + " 正在结算中，无需重复结束。");
        }
    }

    // ---------------------------------------------------------------- pve

    /**
     * /taketori pve —— 查看 PVE 设置（难度 / 大波次 / 精英缩放 / 据点状态）。
     * /taketori pve difficulty &lt;easy|normal|hard&gt; —— 切换难度并写回 config.yml。
     */
    public void handlePve(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "difficulty", "diff", "d" -> setDifficulty(sender, args);
            default -> sendPveStatus(sender);
        }
    }

    /** 切换 PVE 难度档（写回 config.yml，下一局生效）。 */
    private void setDifficulty(CommandSender sender, String[] args) {
        PveSettings current = plugin.pveSettings();
        if (args.length < 3) {
            send(sender, "<gray>当前难度：<white>" + current.difficultyDisplay()
                    + "</white> <dark_gray>（用法：/taketori pve difficulty <easy|normal|hard>）");
            return;
        }
        String requested = args[2].trim();
        String lowered = requested.toLowerCase(Locale.ROOT);
        boolean known = PveSettings.difficulties().contains(lowered)
                || List.of("e", "h", "简单", "普通", "困难").contains(lowered);
        if (!known) {
            send(sender, "<red>难度只能是 easy / normal / hard（也可写 简单 / 普通 / 困难）。");
            return;
        }
        plugin.config().setPveDifficulty(requested);
        PveSettings pve = plugin.pveSettings();
        send(sender, "<green>PVE 难度已设为 <white>" + pve.difficultyDisplay()
                + "</white> <gray>（已写入 config.yml，下一局生效）");
        send(sender, "<gray>据点耐久 <white>" + (int) pve.outpostHealth() + "</white>，月人每秒拆 <white>"
                + pve.outpostDamagePerSecond() + "</white>，每波精英 <white>" + pve.elitesPerWave()
                + "</white>，精英额外强化 <white>+" + pve.eliteBuffBonus() + "</white> 级。");
        boolean anyRunning = plugin.rooms().rooms().stream().anyMatch(GameRoom::isRunning);
        if (anyRunning) {
            send(sender, "<yellow>进行中的房间仍在用开局时的设置，下一轮开局才会变（等待区房间不受影响）。");
        }
    }

    private void sendPveStatus(CommandSender sender) {
        PveSettings pve = plugin.pveSettings();
        send(sender, "<gold>===== PVE 设置（全局默认，各房间下一轮开局读取） =====");
        var modes = plugin.rooms().rooms().stream()
                .map(room -> room.id() + "=" + (room.isPve() ? "PVE" : "PVP"))
                .reduce((a, b) -> a + " " + b);
        send(sender, "<gray>各房间模式：<white>" + modes.orElse("无启用房间")
                + " <dark_gray>（/taketori match mode <pvp|pve> [id] 单独切）");
        send(sender, "<gray>难度：<white>" + pve.difficultyDisplay() + "</white> <dark_gray>(" + pve.difficulty()
                + "，/taketori pve difficulty 切换)");
        send(sender, "<gray>大波次：<white>"
                + (pve.bigWavesEnabled() ? pve.bigWaveCount() + " 波" : "关闭")
                + "</white> <gray>每波 <white>" + pve.elitesPerWave() + "</white> 精英，间隔 <white>"
                + pve.bigWaveIntervalSeconds() + "</white> 秒");
        send(sender, "<gray>各 PVE 房间已进行大波次：<white>"
                + plugin.rooms().rooms().stream()
                .filter(GameRoom::isPve)
                .map(room -> room.id() + "=" + room.minions().bigWave())
                .reduce((a, b) -> a + " " + b).orElse("无进行中的 PVE 房间"));
        send(sender, "<gray>精英随人数变强：<white>" + (pve.scalingEnabled()
                ? "开（每多 1 人 +" + pve.buffsPerPlayer() + " 级，上限 " + pve.maxBuffAmplifier() + " 级）"
                : "关"));
        if (!pve.outpostEnabled()) {
            send(sender, "<gray>保卫据点：<white>关闭 <dark_gray>(pve.outpost.enabled: false)");
            return;
        }
        // 逐房间列据点点位与当前血量（只列活动中的 PVE 房）
        boolean any = false;
        for (GameRoom room : plugin.rooms().rooms()) {
            if (!room.isPve() || !room.isRunning()) {
                continue;
            }
            any = true;
            var outpost = room.outpost();
            if (outpost.isActive()) {
                send(sender, "<gray>[" + room.id() + "] 据点：<white>" + (int) Math.ceil(outpost.health()) + "/"
                        + (int) outpost.maxHealth() + "</white> <gray>位置 " + describe(outpost.location())
                        + " <dark_gray>(每秒被拆 "
                        + String.format(Locale.ROOT, "%.0f", outpost.damagePerSecond()) + ")");
            } else {
                var spotPoint = room.arena().outpost();
                Location spot = spotPoint == null ? null : spotPoint.toBukkitLocation();
                send(sender, "<gray>[" + room.id() + "] 据点：<white>未放置</white> <gray>预定位置 "
                        + (spot == null ? "<red>未设置（/taketori arena setoutpost 划定）" : describe(spot)));
            }
        }
        if (!any) {
            send(sender, "<gray>保卫据点：<white>开 <dark_gray>（当前没有进行中的 PVE 房间；点位在开局时按场地配置生成）");
        }
    }

    /**
     * /taketori match mode：列出各房间模式。
     * /taketori match mode &lt;pvp|pve&gt; [房间id]：只切<b>目标房间</b>的模式（不写 id
     * 取执行者所在房→default）；仅该房 WAITING 阶段允许，不影响其他房间，也不改全局配置。
     */
    private void setMode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "<gold>===== 各房间模式 =====");
            for (GameRoom room : plugin.rooms().rooms()) {
                send(sender, "<gray>[" + room.id() + "] <white>" + room.display()
                        + " <gray>：<white>" + modeText(room)
                        + " <dark_gray>（" + phaseText(room) + "）");
            }
            send(sender, "<gray>用法：/taketori match mode <pvp|pve|sengoku_3v3> [房间id]"
                    + "（只改该房间，等待中可切）");
            send(sender, "<dark_gray>要改全局默认（之后新建的房间都用它）："
                    + "<white>/taketori sengoku mode <模式>");
            return;
        }
        SengokuMode mode = SengokuMode.parse(args[2], null);
        if (mode == null) {
            send(sender, "<red>模式只能是 <white>pvp</white> / <white>pve</white> "
                    + "/ <white>sengoku_3v3</white>。");
            return;
        }
        GameRoom target;
        if (args.length >= 4) {
            target = plugin.rooms().room(args[3].toLowerCase(Locale.ROOT));
            if (target == null) {
                msg(sender, "room.arena-not-found", "id", args[3]);
                return;
            }
        } else {
            target = resolveTargetRoom(sender instanceof Player player ? player : null);
            if (target == null) {
                send(sender, "<red>没有可用房间。");
                return;
            }
        }
        String error = target.setMode(mode.key());
        if (error != null) {
            send(sender, "<red>房间 " + target.display() + "：" + error);
            return;
        }
        send(sender, "<green>房间 <white>" + target.display() + "</white> 已切换为 <white>"
                + modeText(target) + "</white> <gray>模式（仅本房间，下一轮开局生效）。");
        switch (mode) {
            case PVE -> send(sender, "<gray>PVE：所有人同一队打月人，不占点；"
                    + "友伤保护按 <white>auto</white> 会关闭。");
            case SENGOKU_3V3 -> {
                send(sender, "<gray>战国 3v3：三局两胜，清守卫 → 敲钟占领箭楼 → "
                        + "用大将击破器攻陷敌方天守阁。");
                send(sender, "<dark_gray>开局的就绪判定只看战国点位，"
                        + "用 <white>/taketori sengoku check</white> 确认是否齐全。");
            }
            default -> send(sender, "<gray>PVP：红蓝对抗；"
                    + "友伤保护按 <white>auto</white> 会开启（同队互免伤害）。");
        }
    }

    /** 房间模式的显示名。 */
    private String modeText(GameRoom room) {
        if (room.isSengoku()) {
            return "战国 3v3";
        }
        return room.isPve() ? "PVE" : "PVP";
    }

    private boolean isForceFlag(String text) {
        return "force".equalsIgnoreCase(text) || "-f".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }

    /**
     * 开局：作用于指定房间（命令里给 id），否则作用于"执行者所在房间 → default → 第一个房"。
     * force = true 时无视双方人数校验，单人测试也能开（名单只取该房等待区的人，不再从大厅强拉）。
     */
    private void startMatch(CommandSender sender, GameRoom target, boolean force) {
        if (target == null) {
            send(sender, "<red>当前没有任何房间：让玩家在大厅点「降临月宫」创建，"
                    + "或执行 <white>/taketori room create [模板id]</white>。");
            return;
        }
        String error = target.beginMatch(force);
        if (error != null) {
            send(sender, "<red>无法开始房间 " + target.display() + "：" + error);
            if (!force) {
                send(sender, "<gray>人数不够也想开：<white>/taketori match force " + target.id() + "</white>"
                        + " <dark_gray>（等待区里有几人就按几人强制开局）");
            }
            return;
        }
        send(sender, "<green>房间 <white>" + target.display() + "</white> 对局已开始。"
                + (force ? " <gray>（强制开局）" : ""));
    }

    /** 房间阶段中文显示。 */
    private String phaseText(GameRoom room) {
        return switch (room.phase()) {
            case WAITING -> "等待中";
            case STARTING -> "倒计时 " + room.countdownSeconds() + "s";
            case CAGED -> "开局准备中";
            case PLAYING -> "游戏中";
            case ENDING -> "结算中";
        };
    }

    /**
     * 解析管理指令的目标房间：玩家优先取自己所在房间；
     * 否则取 default 场地；再不行取第一个房间。控制台 / 无场地返回 null。
     */
    private GameRoom resolveTargetRoom(Player player) {
        if (player != null) {
            GameRoom current = plugin.rooms().roomOf(player);
            if (current != null) {
                return current;
            }
        }
        GameRoom def = plugin.rooms().room(ArenaManager.DEFAULT_ARENA_ID);
        if (def != null) {
            return def;
        }
        List<GameRoom> all = plugin.rooms().rooms();
        return all.isEmpty() ? null : all.get(0);
    }

    /** /taketori match status：逐房间列出阶段 / 模式 / 人数 / 比分 / 剩余时间。 */
    private void sendStatus(CommandSender sender) {
        List<GameRoom> rooms = plugin.rooms().rooms();
        send(sender, "<gold>===== 对局状态（房间 " + rooms.size() + " 个）=====");
        if (rooms.isEmpty()) {
            send(sender, "<gray>还没有启用场地：<white>/taketori arena create <id> + enable</white>。");
        }
        int waitingTotal = 0;
        for (GameRoom room : rooms) {
            sendRoomStatus(sender, room);
            waitingTotal += room.waitingCount();
        }
        send(sender, "<gray>等待区合计：<white>" + waitingTotal + " 人"
                + " <gray>观众：<white>" + plugin.spectator().audienceCount() + " 人"
                + " <dark_gray>（开局：/taketori match start [房间id]；强制：force）");
    }

    /** 单个房间的状态块（多行）。 */
    private void sendRoomStatus(CommandSender sender, GameRoom room) {
        boolean live = room.phase() == GameRoom.Phase.CAGED
                || room.phase() == GameRoom.Phase.PLAYING
                || room.phase() == GameRoom.Phase.ENDING;
        String ready = room.arena().isReady() ? "" : " <red>场地未就绪（" + room.arena().missingHint() + "）";
        send(sender, "<gold>[" + room.id() + "] <white>" + room.display()
                + " <gray>：<white>" + phaseText(room)
                + (live ? "<gray> 剩余 <white>" + room.remainingText() : "")
                + ready);
        send(sender, "<dark_gray>  模式 <white>" + (room.isPve() ? "PVE（合作打月人）" : "PVP（红蓝对抗）")
                + "<gray> 友伤保护 <white>" + (room.isFriendlyFireProtected() ? "开" : "关")
                + "<gray> 人数 <white>" + (live ? room.onlineParticipantCount() : room.waitingCount())
                + "/" + room.maxPlayers());
        send(sender, "<dark_gray>  比分 <red>" + room.teamScore(TeamId.RED)
                + " <gray>: <blue>" + room.teamScore(TeamId.BLUE)
                + " <gray>（目标 <white>" + room.rules().scoreToWin() + "<gray>）");
        send(sender, "<dark_gray>  红队：<white>" + names(room, TeamId.RED)
                + " <dark_gray> 蓝队：<white>" + names(room, TeamId.BLUE));
        if (live) {
            for (TeamId team : TeamId.values()) {
                send(sender, "<dark_gray>  " + team.display() + " 基地：已拆 <white>"
                        + room.baseCapture().capturedCount(team) + "/"
                        + Math.max(1, room.arena().baseCount(team)));
            }
            send(sender, "<dark_gray>  场上小怪：<white>" + room.minions().aliveCount()
                    + " <gray>最后得分：<white>" + room.nameOf(room.lastScorer())
                    + " <gray>(+" + room.lastScoreAmount() + " " + room.lastScoreReason() + ")");
        }
    }

    /**
     * /taketori leave —— 玩家自助退出（聊天栏的「退出观战」按钮、鹦鹉螺壳 GUI 的「退出」也走这条链路）。
     *
     * <ul>
     *   <li>观众 → 退出观战，回到大厅；</li>
     *   <li>等待区（WAITING/STARTING）→ 退出房间、释放名额、回大厅；</li>
     *   <li>阵亡旁观 → 不动，等自动复活（否则会把对局流程搞乱）；</li>
     *   <li>CAGED/PLAYING 的参赛者 → 主动离局：释放槽位（可被补位）、不判负、保留重连窗口。</li>
     * </ul>
     */
    public void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该指令需要玩家执行。");
            return;
        }
        if (!player.hasPermission("taketori.play")) {
            send(sender, "<red>你没有 taketori.play 权限。");
            return;
        }
        // 观众退出 / 阵亡旁观提示 / 等待中退房 / 对局中主动离局 / 无房间提示，
        // 文案与播报都在 LobbyManager 统一处理（对局中离局见 LobbyManager.leaveMatch）
        plugin.lobby().returnToLobby(player);
    }

    /**
     * /taketori surrender —— 发起/确认本队投降（对局中）。
     * 首次调用发起表决，30 秒内半数以上在线队友再次执行即结束整场（判对方胜）。
     */
    public void handleSurrender(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该指令需要玩家执行。");
            return;
        }
        if (!player.hasPermission("taketori.play")) {
            send(sender, "<red>你没有 taketori.play 权限。");
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isRunning()) {
            send(sender, "<gray>你当前不在进行中的对局里。");
            return;
        }
        if (room.teamOf(player.getUniqueId()) == null) {
            send(sender, "<gray>你不在本局的队伍名单里。");
            return;
        }
        room.surrender(player);
    }

    private String names(GameRoom room, TeamId team) {
        List<Player> players = room.teamPlayers(team);
        if (players.isEmpty()) {
            return "（空）";
        }
        StringBuilder builder = new StringBuilder();
        players.forEach(player -> builder.append(builder.isEmpty() ? "" : "、").append(player.getName()));
        return builder.toString();
    }

    // ---------------------------------------------------------------- team

    /**
     * /taketori team &lt;玩家&gt; &lt;red|blue|none&gt;：作用于<b>目标玩家所在的房间</b>。
     * 队伍在开局瞬间由等待名单自动均衡分配，所以这条指令主要用于开局后的人工调整。
     */
    private void handleTeam(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            send(sender, "<gray>用法：/taketori team <玩家> <red|blue|none>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "<red>找不到玩家：" + args[1]);
            return;
        }
        GameRoom room = plugin.rooms().roomOf(target);
        if (room == null) {
            send(sender, "<red>" + target.getName() + " 当前不在任何房间里"
                    + "（让他先 /taketori lobby join 或从房间列表加入）。");
            return;
        }
        if ("none".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2])) {
            // 管理员强制移出：走 RoomManager 解除唯一归属映射，再补放对局中的队伍槽位
            // （rooms().leave 在对局中默认保留位置），最后送回大厅，避免结算时漏掉该玩家。
            plugin.rooms().leave(target.getUniqueId());
            room.leave(target.getUniqueId());
            plugin.lobby().sendToLobby(target);
            send(sender, "<yellow>" + target.getName() + " 已移出房间 " + room.display() + " 并送回大厅。");
            return;
        }
        TeamId team = TeamId.byName(args[2]);
        if (team == null) {
            send(sender, "<red>无效队伍，可选：red / blue / none");
            return;
        }
        String error = room.chooseTeam(target, team);
        if (error != null) {
            send(sender, "<red>[" + room.id() + "] " + error);
            return;
        }
        send(sender, "<green>[" + room.id() + "] " + target.getName() + " 加入 " + team.display() + "。");
        send(target, "<gold>你被分到 " + team.display() + " <gray>（房间 " + room.display() + "，记分板已显示双方比分）");
    }

    // ---------------------------------------------------------------- arena

    private void handleArena(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>场地划定需要玩家执行（要用到你的位置）。");
            return;
        }
        if (args.length < 2) {
            sendArenaUsage(sender);
            return;
        }
        ArenaManager arenaManager = plugin.arena();
        String sub = args[1].toLowerCase(Locale.ROOT);

        // ---- 不需要先选中场地的管理子命令 ----
        switch (sub) {
            case "create" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena create <模板id>");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (!ArenaManager.isValidId(id)) {
                    msg(sender, "room.arena-bad-id");
                    return;
                }
                if (arenaManager.exists(id)) {
                    msg(sender, "room.arena-exists", "id", id);
                    return;
                }
                arenaManager.create(id);
                arenaManager.select(player.getUniqueId(), id);
                arenaManager.save();
                msg(sender, "room.arena-created", "id", id);
                return;
            }
            case "select", "use" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena select <模板id>");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (!arenaManager.select(player.getUniqueId(), id)) {
                    msg(sender, "room.arena-not-found", "id", id);
                    return;
                }
                msg(sender, "room.arena-selected", "id", id);
                return;
            }
            case "delete", "remove" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena delete <模板id>");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (!arenaManager.exists(id)) {
                    msg(sender, "room.arena-not-found", "id", id);
                    return;
                }
                // 模板删除拦截：该模板正被某个房间使用时不允许删（房间持有独立克隆，但避免混乱）
                if (plugin.rooms() != null && plugin.rooms().isTemplateInUse(id)) {
                    msg(sender, "room.arena-delete-busy", "id", id);
                    return;
                }
                arenaManager.clearSelected(player.getUniqueId());
                arenaManager.delete(id);
                arenaManager.save();
                msg(sender, "room.arena-deleted", "id", id);
                return;
            }
            case "enable" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena enable <模板id>");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                com.taketori.kassen.paper.match.ArenaDef def = arenaManager.get(id);
                if (def == null) {
                    msg(sender, "room.arena-not-found", "id", id);
                    return;
                }
                def.setEnabled(true);
                arenaManager.save();
                msg(sender, "room.arena-enabled", "id", id);
                return;
            }
            case "disable" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena disable <模板id>");
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                com.taketori.kassen.paper.match.ArenaDef def = arenaManager.get(id);
                if (def == null) {
                    msg(sender, "room.arena-not-found", "id", id);
                    return;
                }
                def.setEnabled(false);
                arenaManager.save();
                msg(sender, "room.arena-disabled", "id", id);
                return;
            }
            case "list", "ls" -> {
                listArenas(sender);
                return;
            }
            case "pos1" -> {
                arenaManager.setPos1(player.getUniqueId(), player.getLocation());
                send(sender, "<green>角点 1 已设置：" + describe(player.getLocation()));
                return;
            }
            case "pos2" -> {
                arenaManager.setPos2(player.getUniqueId(), player.getLocation());
                send(sender, "<green>角点 2 已设置：" + describe(player.getLocation()));
                return;
            }
            case "clearselection" -> {
                arenaManager.clearSelection(player.getUniqueId());
                send(sender, "<green>已清空你的选区。"
                        + " <gray>（" + arenaManager.selectionStatus(player.getUniqueId()) + "）");
                return;
            }
            case "wand" -> {
                plugin.setupWand().wand().give(player);
                return;
            }
            case "lootwand" -> {
                plugin.setupWand().wand().giveLoot(player);
                return;
            }
            case "setup" -> {
                // 划场地一条龙：setup <id> [模板名] 开始；setup done / setup cancel 收尾或放弃
                if (args.length >= 3) {
                    String second = args[2].toLowerCase(Locale.ROOT);
                    if ("done".equals(second)) {
                        plugin.arenaSetup().done(player);
                        return;
                    }
                    if ("cancel".equals(second)) {
                        plugin.arenaSetup().cancel(player);
                        return;
                    }
                    plugin.arenaSetup().start(player, second,
                            args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : null);
                    return;
                }
                if (plugin.arenaSetup().sessionOf(player.getUniqueId()) != null) {
                    // 无参数：会话中 → 刷新剩余清单
                    plugin.arenaSetup().sendChecklist(player);
                    send(sender, "<gray>划完所有必设项后执行 <white>/taketori arena setup done</white> 收尾"
                            + "（写回模板世界并保存）；中途放弃用 <white>/taketori arena setup cancel</white>。");
                } else {
                    send(sender, "<gray>用法：<white>/taketori arena setup <场地id> [模板名]</white> "
                            + "<dark_gray>—— 建/选场地 + 载入模板世界 + 输出剩余清单，一条龙划完场地。");
                    send(sender, "<dark_gray>  模板名缺省等于场地 id；模板世界文件夹放在 plugins/TsukikageKassen/moonmaps/ 下"
                            + "（服务器已有世界可用 <white>/taketori moonmap import <世界名> <场地id></white> 导入）。");
                }
                return;
            }
            default -> {
                // 下面所有写场地数据的子命令都需要先选中场地
            }
        }

        // ---- 需要选中场地的数据子命令 ----
        com.taketori.kassen.paper.match.ArenaDef def = arenaManager.selected(player.getUniqueId());
        if (def == null) {
            msg(sender, "room.arena-not-selected");
            return;
        }
        switch (sub) {
            case "setminion" -> {
                CuboidRegion selection = arenaManager.selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整：<white>" + arenaManager.selectionStatus(player.getUniqueId()));
                    send(sender, "<gray>站在一角 → <white>/taketori arena pos1</white>；走到对角 → <white>/taketori arena pos2</white>；再执行本条指令。");
                    return;
                }
                if (!checkTemplateWorld(sender, selection.worldName(), def)) {
                    return;
                }
                // 参数宽容：setminion / setminion 2 / setminion mixed / setminion 2 mixed 都可用
                int index = def.nextFreeMinionIndex();
                String kind = null;
                if (args.length >= 3) {
                    if (isRegionKind(args[2])) {
                        kind = args[2];
                    } else {
                        index = BaseArgParser.parseIndex(args[2]);
                        if (args.length >= 4) {
                            kind = args[3];
                        }
                    }
                }
                if (index <= 0) {
                    send(sender, "<red>编号无效，或已经配满 32 个刷新区（可显式指定编号覆盖）。");
                    return;
                }
                if (kind != null && !isRegionKind(kind)) {
                    send(sender, "<red>刷新区标签只能是 <white>normal</white>（只刷普通月人）或 <white>mixed</white>（普通+精英）。");
                    return;
                }
                def.setMinionRegion(index, selection, kind);
                arenaManager.save();
                arenaManager.clearSelection(player.getUniqueId());
                send(sender, "<green>[" + def.id() + "] 月人刷新区 #" + index + "（" + def.minionRegionKind(index)
                        + "）已设置：" + selection.describe());
                send(sender, "<dark_gray>当前共 " + def.minionRegionCount() + " 个刷新区（mixed "
                        + def.mixedMinionRegionCount() + " 个）；普通月人在全部区之间轮转均分，精英只在 mixed 区刷新。"
                        + "标签可在 arenas.yml 的 minion-regions.<编号>.kind 修改。");
                plugin.arenaSetup().sendChecklist(player);
            }
            case "setloot" -> {
                CuboidRegion selection = arenaManager.selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整：<white>" + arenaManager.selectionStatus(player.getUniqueId()));
                    send(sender, "<gray>用「道具点工具」点两个角，或 pos1 / pos2 之后再执行。");
                    return;
                }
                if (!checkTemplateWorld(sender, selection.worldName(), def)) {
                    return;
                }
                int index = args.length >= 3 ? BaseArgParser.parseIndex(args[2]) : def.nextFreeLootIndex();
                if (index <= 0) {
                    send(sender, "<red>编号无效，或已经配满 32 个道具点（可显式指定编号覆盖）。");
                    return;
                }
                def.setLootRegion(index, selection);
                arenaManager.save();
                arenaManager.clearSelection(player.getUniqueId());
                send(sender, "<green>[" + def.id() + "] 道具刷新点 #" + index + " 已设置：" + selection.describe());
                send(sender, "<dark_gray>当前共 " + def.lootRegionCount()
                        + " 个道具点；刷新池在 config.yml 的 loot 段（可填原版物品或插件武器）。");
                plugin.arenaSetup().sendChecklist(player);
            }
            case "setoutpost", "setpost", "setoutpostpos" -> {
                CuboidRegion selection = arenaManager.selection(player.getUniqueId());
                Location spot = selection == null ? player.getLocation() : selection.center();
                if (!checkTemplateWorld(sender, spot.getWorld() == null ? "?" : spot.getWorld().getName(), def)) {
                    return;
                }
                def.setOutpost(spot);
                arenaManager.save();
                arenaManager.clearSelection(player.getUniqueId());
                send(sender, "<green>[" + def.id() + "] PVE 据点位置已设为：" + describe(spot)
                        + (selection == null ? " <dark_gray>(没选区，直接取你站的位置)" : " <dark_gray>(选区中心)"));
                send(sender, "<gray>据点是一个取消移动 AI 的雪傀儡，月人进入 <white>"
                        + (int) plugin.pveSettings().outpostRadius()
                        + "</white> 格内就会拆它；耐久与难度在 config.yml 的 <white>pve</white> 段。");
            }
            case "deloutpost", "delpost" -> {
                def.clearOutpost();
                arenaManager.save();
                send(sender, "<green>已清除 [" + def.id() + "] 的据点位置。");
                send(sender, "<gray>PVE 时会回落到第一个月人刷新区的中心。");
            }
            // ---- 删除已划定的区域（管理员菜单「删除已划区域」里的按钮执行的就是这几条）----
            case "delbase" -> {
                if (args.length < 4) {
                    send(sender, "<gray>用法：/taketori arena delbase <red|blue> <编号>");
                    send(sender, "<dark_gray>  也可以直接在 /taketori admin 的「删除已划区域」里点按钮删。");
                    return;
                }
                BaseArgParser.Result parsed = BaseArgParser.parse(args[2], args[3]);
                TeamId team = parsed.team();
                int index = parsed.index();
                if (team == null || index <= 0) {
                    send(sender, "<red>参数无效：<gray>队伍写 <white>red</white> / <white>blue</white>"
                            + "（也接受 红 / 蓝），编号写正整数（全角数字会自动转换）。");
                    return;
                }
                if (!def.clearBase(team, index)) {
                    send(sender, "<red>[" + def.id() + "] " + team.display() + " 没有编号 #" + index + " 的基地"
                            + " <dark_gray>（用 /taketori arena list 看现有编号）");
                    return;
                }
                arenaManager.save();
                send(sender, "<green>已删除 [" + def.id() + "] " + team.display() + " 基地 #" + index + "。");
                send(sender, "<gray>别忘了补划：<white>/taketori arena setbase " + team.key()
                        + " " + index + "</white>（基地数量不足时无法开局）。");
            }
            case "delminion" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena delminion <编号>");
                    return;
                }
                int index = BaseArgParser.parseIndex(args[2]);
                if (index <= 0) {
                    send(sender, "<red>编号无效：要写正整数（全角数字会自动转换）。");
                    return;
                }
                if (!def.clearMinionRegion(index)) {
                    send(sender, "<red>[" + def.id() + "] 没有编号 #" + index + " 的月人刷新区。");
                    return;
                }
                arenaManager.save();
                send(sender, "<green>已删除 [" + def.id() + "] 月人刷新区 #" + index + "。"
                        + " <gray>当前还剩 <white>" + def.minionRegionCount() + "</white> 个。");
            }
            case "delloot" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena delloot <编号>");
                    return;
                }
                int index = BaseArgParser.parseIndex(args[2]);
                if (index <= 0) {
                    send(sender, "<red>编号无效：要写正整数（全角数字会自动转换）。");
                    return;
                }
                if (!def.clearLootRegion(index)) {
                    send(sender, "<red>[" + def.id() + "] 没有编号 #" + index + " 的道具刷新点。");
                    return;
                }
                arenaManager.save();
                send(sender, "<green>已删除 [" + def.id() + "] 道具刷新点 #" + index + "。"
                        + " <gray>当前还剩 <white>" + def.lootRegionCount() + "</white> 个。");
            }
            case "setbase" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena setbase <red|blue> [编号 1-" + def.baseIndexCeiling()
                            + "]");
                    send(sender, "<dark_gray>  省略编号时会自动用该队下一个空位；队伍可写 red / blue，也可写 红 / 蓝。");
                    send(sender, "<dark_gray>  每队基地数量在 config.yml 的 <white>base.count-per-team</white>"
                            + "（正整数或 auto，当前：" + (def.baseLimit() == 0 ? "auto" : def.baseLimit()) + "）。");
                    return;
                }
                String first = args[2];
                String second = args.length >= 4 ? args[3] : null;

                // 宽容解析（纯逻辑在 core/match/BaseArgParser，tools/BaseArgParserTest.java 可离线回归）：
                // 顺序写反、省略编号、中文队伍名、全角数字都能识别
                BaseArgParser.Result parsed = BaseArgParser.parse(first, second);
                TeamId team = parsed.team();
                int index = parsed.index();
                if (team == null) {
                    send(sender, "<red>队伍无效：<white>"
                            + (second == null ? first : first + " " + second)
                            + "</white> <gray>—— 队伍要写 <white>red</white> 或 <white>blue</white>（也接受 红 / 蓝）。");
                    send(sender, "<gray>正确写法：<white>/taketori arena setbase red 1");
                    return;
                }
                if (second != null && index <= 0) {
                    send(sender, "<red>编号无效：<white>" + second + "</white> <gray>—— 编号要写 <white>1~"
                            + def.baseIndexCeiling() + "</white> 的数字（全角数字会自动转换）。");
                    return;
                }
                if (index <= 0) {
                    index = def.nextFreeBaseIndex(team);
                    if (index <= 0) {
                        send(sender, "<red>" + team.display() + " 的 " + def.baseLimit()
                                + " 个基地都已配置；要覆盖请写明编号，例如 <white>/taketori arena setbase "
                                + team.key() + " 1");
                        return;
                    }
                    send(sender, "<gray>未指定编号，自动使用 " + team.display() + " 基地 #" + index + "。");
                }
                if (index > def.baseIndexCeiling()) {
                    send(sender, "<red>编号 " + index + " 超出范围：当前每队可用 <white>"
                            + def.baseIndexCeiling() + "</white> 个编号（1~"
                            + def.baseIndexCeiling() + "）。");
                    send(sender, "<gray>要配更多基地，把 config.yml 的 <white>base.count-per-team</white> 调大"
                            + "（或写 <white>auto</white> 不设上限），再 /taketori reload。");
                    return;
                }
                CuboidRegion selection = arenaManager.selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整：<white>" + arenaManager.selectionStatus(player.getUniqueId()));
                    send(sender, "<gray>站在一角 → <white>/taketori arena pos1</white>；走到对角 → <white>/taketori arena pos2</white>；再执行本条指令。");
                    return;
                }
                if (!checkTemplateWorld(sender, selection.worldName(), def)) {
                    return;
                }
                def.setBase(team, index, selection);
                arenaManager.save();
                arenaManager.clearSelection(player.getUniqueId());
                send(sender, "<green>已设置 [" + def.id() + "] " + team.display() + " 基地 #" + index + "：" + selection.describe());
                send(sender, "<dark_gray>选区已清空，划下一个基地请重新 pos1 + pos2。");
                plugin.arenaSetup().sendChecklist(player);
            }
            case "setspawn" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena setspawn <red|blue>");
                    return;
                }
                TeamId team = TeamId.byName(args[2]);
                if (team == null) {
                    send(sender, "<red>队伍无效：<white>" + args[2]
                            + "</white> <gray>—— 可选 <white>red</white> / <white>blue</white>（也接受 红 / 蓝）");
                    return;
                }
                if (!checkTemplateWorld(sender, player.getWorld().getName(), def)) {
                    return;
                }
                def.setSpawn(team, player.getLocation());
                arenaManager.save();
                send(sender, "<green>已设置 [" + def.id() + "] " + team.display() + " 出生点：" + describe(player.getLocation()));
                plugin.arenaSetup().sendChecklist(player);
            }
            case "setwait", "setwaiting" -> {
                // 有选区：整个选区作为等待区（加入者在区域内随机分布）；无选区：退化为你的站位单点
                CuboidRegion selection = arenaManager.selection(player.getUniqueId());
                if (selection != null) {
                    if (!checkTemplateWorld(sender, selection.worldName(), def)) {
                        return;
                    }
                    def.setWaitRegion(selection);
                    arenaManager.save();
                    arenaManager.clearSelection(player.getUniqueId());
                    send(sender, "<green>[" + def.id() + "] 等待区区域已设置：" + selection.describe());
                    send(sender, "<dark_gray>加入房间的玩家会在区域内随机分布；清空选区后重新 setwait 可改回单点。");
                } else {
                    if (!checkTemplateWorld(sender, player.getWorld().getName(), def)) {
                        return;
                    }
                    def.setWaitSpawn(player.getLocation());
                    arenaManager.save();
                    msg(sender, "room.arena-wait-set", "id", def.id());
                }
                plugin.arenaSetup().sendChecklist(player);
            }
            case "setbomb", "setbombsite" -> {
                if (!checkTemplateWorld(sender, player.getWorld().getName(), def)) {
                    return;
                }
                // 用站位定包点：包点只需要一个方块，划选区反而多余（还要在选区里挑一个格子）
                def.setBomb(player.getLocation());
                arenaManager.save();
                send(sender, "<green>[" + def.id() + "] 包点已设置：" + describe(player.getLocation()));
                send(sender, "<dark_gray>开局 " + plugin.config().bombActivationDelaySeconds()
                        + " 秒后这个方块变成 TNT：进攻方（红）站上去读条拆除（空手 "
                        + plugin.config().bombDefuseSecondsBare() + " 秒 / 拆弹钳 "
                        + plugin.config().bombDefuseSecondsClipper() + " 秒），限时 "
                        + plugin.config().bombDefuseTimeLimitSeconds() + " 秒；防守方（蓝）守住即可。");
                plugin.arenaSetup().sendChecklist(player);
            }
            case "clearbomb", "delbomb" -> {
                if (!def.hasBomb()) {
                    send(sender, "<yellow>[" + def.id() + "] 本来就没有设置包点。");
                    return;
                }
                def.clearBomb();
                arenaManager.save();
                send(sender, "<green>[" + def.id() + "] 包点已清除，本场地的对局改回只按分数判胜负。");
            }
            default -> sendArenaUsage(sender);
        }
    }

    /** 月人刷新区标签解析（normal / mixed，大小写不敏感）。 */
    private boolean isRegionKind(String text) {
        return com.taketori.kassen.paper.match.ArenaDef.REGION_KIND_NORMAL.equalsIgnoreCase(text)
                || com.taketori.kassen.paper.match.ArenaDef.REGION_KIND_MIXED.equalsIgnoreCase(text);
    }

    /**
     * 选区/站位的世界校验：点位必须划在该场地的模板编辑世界（{@code k_tpl_<模板名>}）里。
     * 房间世界是模板整体复制出来的——把点位记到别的世界（大厅、主世界、别的模板的编辑副本），
     * 坐标会在房间里指向完全不同的地形，这类错位极难排查，所以保存前直接拦截。
     *
     * @param actualWorld 选区/站位当前所在的世界名
     * @param def         当前操作的场地
     * @return true = 校验通过；false = 已向发送者解释原因
     */
    private boolean checkTemplateWorld(CommandSender sender, String actualWorld,
                                       com.taketori.kassen.paper.match.ArenaDef def) {
        // 会话中的场地可能用了与 id 不同的模板名，以会话记录为准
        var session = plugin.arenaSetup().sessionOf(sender instanceof Player p ? p.getUniqueId() : null);
        String template = session != null && session.arenaId().equals(def.id())
                ? session.templateName() : def.id();
        String expected = com.taketori.kassen.paper.match.room.RoomManager.TEMPLATE_EDIT_PREFIX + template;
        if (expected.equals(actualWorld)) {
            return true;
        }
        send(sender, "<red>[" + def.id() + "] 拒绝保存：点位所在世界不对。当前 <white>" + actualWorld
                + "</white>，应为模板编辑世界 <white>" + expected + "</white>。");
        send(sender, "<gray>房间世界由模板整体复制而来，点位记到别的世界会在房间里指向错误地形。"
                + "先执行 <white>/taketori arena setup " + def.id() + "</white>（或 <white>/taketori moonmap load "
                + template + "</white>）载入模板世界，进入后重新划定。");
        return false;
    }

    /** arena list：逐场地输出启用状态、就绪情况与各要素数量。 */
    private void listArenas(CommandSender sender) {
        var arenas = plugin.arena().all();
        send(sender, "<gold>===== 场地列表（" + arenas.size() + "）=====");
        if (arenas.isEmpty()) {
            send(sender, "<gray>还没有场地：<white>/taketori arena create <id></white> 创建第一个。");
            return;
        }
        for (var entry : arenas.entrySet()) {
            var def = entry.getValue();
            String state = def.enabled()
                    ? (def.isReady() ? "<green>就绪·开放" : "<yellow>未就绪·开放")
                    : "<dark_gray>已关闭";
            send(sender, "<gold>[" + def.id() + "] " + state);
            send(sender, "<dark_gray>  刷新区 <white>" + def.minionRegionCount()
                    + "</white>（mixed <white>" + def.mixedMinionRegionCount() + "</white>）"
                    + " / 道具点 <white>" + def.lootRegionCount()
                    + "</white> / 基地 <red>" + def.baseCount(TeamId.RED)
                    + "</red>:<blue>" + def.baseCount(TeamId.BLUE)
                    + "</blue> / 出生点 <white>"
                    + (def.spawn(TeamId.RED) != null ? "红" : "")
                    + (def.spawn(TeamId.BLUE) != null ? "蓝" : "")
                    + "</white> / 等待点 <white>" + (def.hasWaitSpawn() ? "已设" : "未设")
                    + "</white> / 据点 <white>" + (def.hasOutpost() ? "已设" : "回落"));
            if (def.hasMinionSpawnSettings()) {
                var cfg = plugin.getConfig();
                send(sender, "<dark_gray>  本场地刷新节奏：<white>"
                        + def.minionSpawnIntervalSeconds(Math.max(1, cfg.getInt("minion.interval-seconds", 9)))
                        + "</white> 秒/波 × <white>"
                        + def.minionSpawnPerSpawn(Math.max(1, cfg.getInt("minion.per-spawn", 3)))
                        + "</white> 只，上限 <white>"
                        + def.minionSpawnMaxAlive(Math.max(1, cfg.getInt("minion.max-alive", 15)))
                        + "</white> <dark_gray>（arenas.yml minion-spawn 覆盖，未写的项用全局默认）");
            }
            if (!def.isReady()) {
                send(sender, "<yellow>  还缺：<white>" + def.missingHint());
            }
        }
        send(sender, "<gray>当前选中：<white>" + (sender instanceof Player p
                ? String.valueOf(plugin.arena().selectedId(p.getUniqueId())) : "-"));
    }

    /** 用 messages.yml 的 room.* 键发消息。 */
    private void msg(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(plugin.config().messages().get(key, placeholders));
    }

    private void sendArenaUsage(CommandSender sender) {
        send(sender, "<yellow>场地指令（多场地；set*/del* 作用于当前选中的场地）：");
        send(sender, "<gray>/taketori arena setup <id> [模板名]  <dark_gray>— 一条龙：建/选场地 + 载入模板世界 + 剩余清单（setup done 收尾 / setup cancel 放弃）");
        send(sender, "<gray>/taketori arena create <id>  <dark_gray>— 新建场地并自动选中");
        send(sender, "<gray>/taketori arena select <id>  <dark_gray>— 切换当前操作的场地");
        send(sender, "<gray>/taketori arena list  <dark_gray>— 全部场地的就绪情况");
        send(sender, "<gray>/taketori arena enable|disable|delete <id>");
        send(sender, "<gray>/taketori arena pos1 | pos2  <dark_gray>— 用当前位置设置选区角点");
        send(sender, "<gray>/taketori arena setminion [编号] [normal|mixed]  <dark_gray>— 选区设为月人刷新区（normal 只刷普通，mixed 普通+精英；月人按区轮转均分）");
        send(sender, "<gray>/taketori arena setbase <red|blue> [编号 1-3]  <dark_gray>— 编号可省略（自动用下一个空位）");
        send(sender, "<gray>/taketori arena setloot [编号]  <dark_gray>— 选区设为道具刷新点");
        send(sender, "<gray>/taketori arena setoutpost  <dark_gray>— 选区中心（或站位）设为 PVE 据点");
        send(sender, "<gray>/taketori arena setwait  <dark_gray>— 等待出生点：有选区=整片等待区（加入者随机分布），无选区=你的站位");
        send(sender, "<gray>/taketori arena setspawn <red|blue>");
        send(sender, "<gray>/taketori arena setbomb  <dark_gray>— 用站位设置包点（开局延迟后变成 TNT，红队读条拆掉即胜）");
        send(sender, "<gray>/taketori arena deloutpost | delbase <red|blue> <编号> | delminion <编号> | delloot <编号> | clearbomb");
        send(sender, "<gray>/taketori arena clearselection  <dark_gray>— 清空你当前的选区（pos1 / pos2）");
        send(sender, "<gray>/taketori arena wand | lootwand  <dark_gray>— 领选区锄 / 道具点工具");
    }

    private String describe(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "（世界不存在）";
        }
        return String.format("%s %.0f,%.0f,%.0f",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    // ---------------------------------------------------------------- lobby

    /**
     * 大厅指令。join / leave / spectate 对普通玩家开放（等价于点告示牌），
     * 其余配置类操作需要 taketori.admin。
     */
    private void handleLobby(CommandSender sender, String[] args) {
        var lobby = plugin.lobby();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";

        // 玩家自助操作
        switch (action) {
            case "join" -> {
                if (sender instanceof Player player) {
                    lobby.quickJoin(player);
                } else {
                    send(sender, "<red>该操作需要玩家执行。");
                }
                return;
            }
            case "leave" -> {
                // 与 /taketori leave 完全同义：观众退出 / 等待区退房回大厅 / 对局中拒绝
                handleLeave(sender);
                return;
            }
            case "spectate" -> {
                if (sender instanceof Player player) {
                    if (plugin.spectator().isAudience(player)) {
                        plugin.spectator().leaveAudience(player);
                        return;
                    }
                    var live = plugin.rooms().rooms().stream()
                            .filter(room -> room.phase() == GameRoom.Phase.CAGED
                                    || room.phase() == GameRoom.Phase.PLAYING)
                            .findFirst().orElse(null);
                    if (live == null) {
                        send(sender, "<red>当前没有进行中的对局，无法旁观。");
                        return;
                    }
                    plugin.spectator().enterAudience(player, live.spectatorViewPoint(), live);
                    player.sendMessage(plugin.config().messages().get("room.spectating",
                            "room", live.display()));
                } else {
                    send(sender, "<red>该操作需要玩家执行。");
                }
                return;
            }
            default -> {
                // 下面是配置类操作，继续走权限检查
            }
        }

        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该操作需要玩家执行（要用到你的位置/视线）。");
            return;
        }

        switch (action) {
            case "setspawn" -> {
                lobby.setSpawn(player.getLocation());
                lobby.save();
                send(sender, "<green>大厅出生点已设置：" + describe(player.getLocation()));
            }
            case "pos1" -> {
                plugin.arena().setPos1(player.getUniqueId(), player.getLocation());
                send(sender, "<green>选区角点 1 已设置：" + describe(player.getLocation()));
            }
            case "pos2" -> {
                plugin.arena().setPos2(player.getUniqueId(), player.getLocation());
                send(sender, "<green>选区角点 2 已设置：" + describe(player.getLocation()));
            }
            case "setregion" -> {
                CuboidRegion selection = plugin.arena().selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整（先 lobby pos1 + pos2）。");
                    return;
                }
                lobby.setRegion(selection);
                lobby.save();
                plugin.arena().clearSelection(player.getUniqueId());
                send(sender, "<green>大厅区域已设置：" + selection.describe());
            }
            case "addsign" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori lobby addsign <" + LobbyAction.usageKeys() + ">");
                    send(sender, "<gray>把准星对准告示牌（6 格内）再执行。");
                    return;
                }
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    send(sender, "<red>没有瞄准到方块（请看向要绑定的告示牌，6 格内）。");
                    return;
                }
                if (!(target.getState() instanceof org.bukkit.block.Sign)) {
                    send(sender, "<red>准星指向的不是告示牌，动作只能绑在告示牌上。");
                    return;
                }
                String signAction = args[2].toLowerCase(Locale.ROOT);
                var sign = lobby.addSignAndSave(target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ(), signAction);
                send(sender, "<green>已绑定告示牌：" + sign.describe());
            }
            case "addstatus" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori lobby addstatus <模板id>");
                    send(sender, "<gray>准星对准告示牌（6 格内）执行：绑定一张实时房间状态牌（每秒刷新）。");
                    return;
                }
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    send(sender, "<red>没有瞄准到方块（请看向要绑定的告示牌，6 格内）。");
                    return;
                }
                if (!(target.getState() instanceof org.bukkit.block.Sign signState)) {
                    send(sender, "<red>准星指向的不是告示牌。");
                    return;
                }
                String templateId = args[2].toLowerCase(Locale.ROOT);
                if (!plugin.arena().exists(templateId)) {
                    msg(sender, "room.arena-not-found", "id", templateId);
                    return;
                }
                var statusSign = lobby.addStatusSignAndSave(target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ(), templateId);
                send(sender, "<green>已绑定实时状态牌（模板 <white>" + statusSign.templateId()
                        + "</white>），文本每秒自动刷新；点击可直接加入 / 旁观 / 创建。");
            }
            case "removesign" -> {
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    send(sender, "<red>没有瞄准到方块。");
                    return;
                }
                if (lobby.removeSignAt(target)) {
                    lobby.save();
                    send(sender, "<green>已移除该告示牌的绑定。");
                } else {
                    send(sender, "<red>这个方块没有绑定告示牌动作。");
                }
            }
            case "list" -> {
                send(sender, "<gold>===== 大厅配置 =====");
                send(sender, "<gray>出生点：<white>" + (lobby.spawn() == null ? "未设置" : describe(lobby.spawn())));
                send(sender, "<gray>区域：<white>" + (lobby.region() == null ? "未设置（不限定范围）" : lobby.region().describe()));
                send(sender, "<gray>告示牌：<white>" + lobby.signs().size() + " 个");
                lobby.signs().forEach(sign -> {
                    LobbyAction signAction = LobbyAction.of(sign.action());
                    send(sender, "<dark_gray>  " + sign.describe()
                            + (signAction == null
                            ? " <red>(动作无法识别)"
                            : " <gray>（" + signAction.description() + "）"));
                });
                send(sender, "<gray>状态牌：<white>" + lobby.statusSigns().size() + " 个");
                int waitingTotal = plugin.rooms().rooms().stream()
                        .mapToInt(GameRoom::waitingCount).sum();
                send(sender, "<gray>房间等待区合计：<white>" + waitingTotal + " 人"
                        + " <dark_gray>（人数达标由房间自动倒计时开局）");
                send(sender, "<gray>是否可用：<white>" + (lobby.isConfigured() ? "是" : "否（先设置出生点）"));
            }
            default -> {
                send(sender, "<yellow>大厅指令：");
                send(sender, "<gray>/taketori lobby setspawn | pos1 | pos2 | setregion");
                send(sender, "<gray>/taketori lobby addsign &lt;动作&gt; | addstatus &lt;模板id&gt; | removesign | list");
                send(sender, "<gray>/taketori lobby join | leave | spectate  <dark_gray>— 玩家自助");
            }
        }
    }

    // ---------------------------------------------------------------- stats

    private void handleStats(CommandSender sender, String[] args) {
        // /taketori stats gui [类别] —— 打开总计排行榜 GUI（图形版）
        if (args.length > 1 && "gui".equalsIgnoreCase(args[1])) {
            if (!(sender instanceof Player player)) {
                send(sender, "<red>该操作需要玩家执行（要打开菜单）。");
                return;
            }
            plugin.statsMenu().open(player, StatsTracker.parse(args.length > 2 ? args[2] : "score"), 1);
            return;
        }
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
        }
        List<String> lines = plugin.stats().leaderboard(limit);
        send(sender, "<gold>===== 历史战绩（跨局累计）=====");
        if (lines.isEmpty()) {
            send(sender, "<gray>还没有记录。");
            return;
        }
        lines.forEach(line -> send(sender, "<gray>" + line));
        send(sender, "<dark_gray>图形版排行榜：<white>/taketori ranks");
    }

    // ---------------------------------------------------------------- util

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "<red>你没有权限执行该指令。");
        return false;
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }
}
