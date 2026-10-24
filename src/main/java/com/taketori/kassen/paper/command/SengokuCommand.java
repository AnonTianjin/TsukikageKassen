package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.RoundResult;
import com.taketori.kassen.core.match.sengoku.SengokuMode;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.match.sengoku.SengokuMapDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /taketori sengoku ...}：战国 3v3 的划区与运维命令。
 *
 * <p>分两类：</p>
 * <ul>
 *   <li><b>划区</b>（{@code setkeep} / {@code settower} / …）：消费选区锄的当前选区
 *       （区域类）或玩家站的位置（点类），写入 {@code arenas.yml} 的 {@code sengoku} 段；</li>
 *   <li><b>运维</b>（{@code start} / {@code pause} / {@code endround} / {@code towers} / …）。</li>
 * </ul>
 *
 * <p>独立成一个类而不是塞进 {@code MatchCommand}（已 1300 多行）：这一组命令只服务一个模式，
 * 混进去之后那个类会越来越难读。</p>
 */
public final class SengokuCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public SengokuCommand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender.hasPermission("taketori.admin")) {
                usage(sender);
            } else {
                send(sender, "<red>用法：<white>/taketori sengoku score</white>");
            }
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        // score 是给所有玩家看的（比分、剩余时间、各人能量），其余是管理命令
        if ("score".equals(action)) {
            showScore(sender);
            return;
        }
        if (!sender.hasPermission("taketori.admin")) {
            send(sender, "<red>没有权限。");
            return;
        }
        switch (action) {
            // ── 划区 ──────────────────────────────────────────────
            case "setkeep" -> setKeep(sender, args);
            case "setkeepdoor" -> setKeepDoor(sender, args);
            case "settower" -> setTower(sender, args);
            case "setbell" -> setBell(sender, args);
            case "setguard" -> setGuard(sender, args);
            case "setmid" -> setMidMinion(sender, args);
            case "setjumppad" -> setJumpPad(sender, args);
            // ── 划错了要能改 ──────────────────────────────────────
            case "delkeep" -> clearKeep(sender, args);
            case "delkeepdoor" -> clearKeepDoor(sender, args);
            case "deltower" -> clearTower(sender, args);
            case "delbell" -> clearBell(sender, args);
            case "delguard" -> clearGuard(sender, args);
            case "delmid" -> clearMidMinion(sender, args);
            case "deljumppad" -> clearJumpPad(sender, args);
            case "delall" -> clearAll(sender, args);
            // ── 运维 ──────────────────────────────────────────────
            case "start" -> forceStart(sender);
            case "pause" -> setPaused(sender, true);
            case "resume" -> setPaused(sender, false);
            // endround 不指定队伍时判平局重开，与超时持平的语义一致
            case "endround" -> endRound(sender, args);
            case "towers" -> showTowers(sender);
            case "breaker" -> showBreaker(sender, args);
            case "jumppad" -> showJumpPad(sender, args);
            case "check" -> checkMap(sender);
            case "mode" -> switchMode(sender, args);
            case "menu", "gui" -> openMenu(sender);
            default -> usage(sender);
        }
    }

    /**
     * {@code /taketori sengoku mode <pvp|pve|sengoku_3v3>}：切<b>全局默认</b>模式并写回 config.yml。
     *
     * <p>存在的意义是不用让管理员手动去翻 config.yml 再 reload —— 那是这条流程里最容易
     * 出错也最容易被忘掉的一步。只影响之后新建的房间（模式在房间创建时快照）。</p>
     */
    private void switchMode(CommandSender sender, String[] args) {
        SengokuMode current = plugin.config().matchMode();
        if (args.length < 3) {
            send(sender, "<gold>当前全局模式：<white>" + current.key()
                    + "</white> <dark_gray>(" + describeMode(current) + ")");
            send(sender, "<gray>用法：<white>/taketori sengoku mode <pvp|pve|sengoku_3v3>");
            send(sender, "<dark_gray>只影响之后新建的房间；改单个房间用 "
                    + "/taketori match mode <模式> [房间id]。");
            return;
        }
        SengokuMode mode = SengokuMode.parse(args[2], null);
        if (mode == null) {
            send(sender, "<red>模式只能是 <white>pvp</white> / <white>pve</white> "
                    + "/ <white>sengoku_3v3</white>。");
            return;
        }
        plugin.config().setMatchMode(mode);
        send(sender, "<green>全局模式已设为 <white>" + mode.key() + "</white> <gray>（已写入 config.yml）。");
        send(sender, "<gray>" + describeMode(mode));
        long running = plugin.rooms().rooms().stream().filter(GameRoom::isRunning).count();
        if (running > 0) {
            send(sender, "<yellow>有 " + running + " 个房间正在对局，它们仍用创建时的模式。");
        }
        if (mode.isSengoku()) {
            send(sender, "<dark_gray>别忘了点位：<white>/taketori sengoku check");
        }
    }

    private String describeMode(SengokuMode mode) {
        return switch (mode) {
            case PVE -> "所有人同一队打月人，含据点保卫与波次";
            case SENGOKU_3V3 -> "三局两胜：清守卫 → 敲钟占领箭楼 → 击破器攻陷敌方天守阁";
            default -> "红蓝对抗，积分赛";
        };
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>菜单只能由玩家打开。");
            return;
        }
        plugin.sengokuMenu().open(player);
    }

    // ---------------------------------------------------------------- 划区

    private void setKeep(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        CuboidRegion selection = requireSelection(sender, player);
        if (def == null || selection == null) {
            return;
        }
        def.sengoku().setKeep(team, selection);
        afterEdit(sender, player, def, team.display() + "天守阁", selection.describe());
    }

    private void setKeepDoor(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        Location spot = player.getLocation();
        def.sengoku().setKeepDoor(team, ArenaDef.Point.of(spot));
        afterEdit(sender, player, def, team.display() + "天守阁门前", describe(spot));
    }

    private void setTower(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_TOWERS, "箭楼");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        CuboidRegion selection = requireSelection(sender, player);
        if (def == null || selection == null) {
            return;
        }
        // 只覆盖占领区，保留已有的铜钟与守卫点（分三条命令设，允许先划区后补点）
        var map = def.sengoku();
        map.setTower(index, selection, map.bell(index), map.guardSpawn(index));
        afterEdit(sender, player, def, "箭楼 #" + index + " 占领区", selection.describe());
    }

    private void setBell(CommandSender sender, String[] args) {
        setTowerPoint(sender, args, true);
    }

    private void setGuard(CommandSender sender, String[] args) {
        setTowerPoint(sender, args, false);
    }

    /**
     * 铜钟与守卫点都是"箭楼上的一个位置"，但两者的坐标语义<b>不同</b>：
     * 守卫点是"人站的地方"，铜钟是"被右键的那个方块"。
     *
     * <p>铜钟必须存<b>方块坐标</b>——敲钟判定拿存储点与被点击方块做比较，
     * 存玩家脚坐标永远匹配不到（钟是实心方块，人只能站旁边或上面，三轴必差 1）。
     * 用 {@code getTargetBlockExact} 取注视方块，与 {@code MatchCommand} 里
     * 其它"方块精确点位"的做法一致。</p>
     */
    private void setTowerPoint(CommandSender sender, String[] args, boolean bell) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_TOWERS, bell ? "铜钟" : "守卫点");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        ArenaDef.Point point;
        if (bell) {
            org.bukkit.block.Block target = player.getTargetBlockExact(6);
            if (target == null || target.getType().isAir()) {
                send(sender, "<red>请对着铜钟方块再执行（6 格内，需要视线正对它）。");
                return;
            }
            point = ArenaDef.Point.of(target.getLocation());
        } else {
            point = ArenaDef.Point.of(player.getLocation());
        }
        var map = def.sengoku();
        if (bell) {
            map.setTower(index, map.tower(index), point, map.guardSpawn(index));
        } else {
            map.setTower(index, map.tower(index), map.bell(index), point);
        }
        afterEdit(sender, player, def, "箭楼 #" + index + (bell ? " 铜钟" : " 守卫刷新点"),
                describe(point.toBukkitLocation()));
    }

    private void setMidMinion(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        CuboidRegion selection = requireSelection(sender, player);
        if (def == null || selection == null) {
            return;
        }
        int index = def.sengoku().nextFreeMidMinionIndex();
        if (args.length >= 3) {
            int explicit = requireIndex(sender, args, 2, SengokuMapDef.MAX_MID_MINION_REGIONS, "中地小兵区");
            if (explicit < 0) {
                return;
            }
            index = explicit;
        }
        if (index <= 0) {
            send(sender, "<red>中地小兵刷新区已配满（上限 " + SengokuMapDef.MAX_MID_MINION_REGIONS + "）。");
            return;
        }
        def.sengoku().setMidMinionRegion(index, selection);
        afterEdit(sender, player, def, "中地小兵刷新区 #" + index, selection.describe());
    }

    private void setJumpPad(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        def.sengoku().setJumpPad(team, ArenaDef.Point.of(player.getLocation()));
        afterEdit(sender, player, def, team.display() + "跳跃台", describe(player.getLocation()));
    }

    /** 点位写完之后统一做的事：保存、清选区、回报、刷新缺口清单。 */
    private void afterEdit(CommandSender sender, Player player, ArenaDef def, String what, String detail) {        plugin.arena().save();
        plugin.arena().clearSelection(player.getUniqueId());
        send(sender, "<green>[" + def.id() + "] " + what + " 已设置：<white>" + detail);
        int required = plugin.config().towerRules().safeCount();
        String missing = def.sengoku().missingHint(required);
        send(sender, missing.isEmpty()
                ? "<green>战国点位已齐全，可以开局。"
                : "<gray>还缺：<white>" + missing);
    }

    // ---------------------------------------------------------------- 运维

    private void forceStart(CommandSender sender) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        String failure = room.beginMatch(true);
        send(sender, failure == null
                ? "<green>已强制开局。"
                : "<red>开局失败：<white>" + failure);
    }

    private void setPaused(CommandSender sender, boolean paused) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        // 只对进行中的小局生效：CAGED/ENDING 间隙 pause 会"看似成功"，
        // 实际下一局 onRoundStart 直接复位 paused，管理员以为暂停了其实没有
        if (!room.isRunning()) {
            send(sender, "<gray>当前没有进行中的小局，暂停/继续只在对局内有效。");
            return;
        }
        if (!room.sengoku().setPaused(paused)) {
            send(sender, "<gray>已经是" + (paused ? "暂停" : "进行") + "状态。");
            return;
        }
        room.broadcast(plugin.config().messages().plain(
                paused ? "sengoku.paused" : "sengoku.resumed"));
        send(sender, paused ? "<green>已暂停小局计时。" : "<green>已继续。");
    }

    private void endRound(CommandSender sender, String[] args) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        if (!room.isRunning()) {
            send(sender, "<red>当前不在小局进行中。");
            return;
        }
        TeamId winner = null;
        if (args.length >= 3) {
            winner = TeamId.byName(args[2]);
            if (winner == null) {
                // 静默判平会让管理员以为"判了红队胜"却看到 0-0 重开
                send(sender, "<red>队伍只能是 <white>red</white> 或 <white>blue</white>，"
                        + "收到的是 <white>" + args[2] + "</white>。不指定则判平局重开。");
                return;
            }
        }
        // 用本局用时而不是 room.elapsedSeconds()（那是整场起算的）
        long seconds = room.sengoku().roundElapsedSeconds();
        RoundResult result = winner == null
                ? RoundResult.draw(RoundResult.Reason.FORCED, seconds)
                : new RoundResult(winner, RoundResult.Reason.FORCED, seconds);
        send(sender, winner == null
                ? "<green>已强制结束本小局（平局重开）。"
                : "<green>已强制结束本小局，判 <white>" + winner.display() + "</white> 胜。");
        room.sengoku().onRoundEnd(result);
    }

    private void showTowers(CommandSender sender) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        var towers = room.towers();
        send(sender, "<gold>箭楼归属：<white>" + towers.describe());
        for (int index = 1; index <= towers.towerCount(); index++) {
            send(sender, "<gray>  #" + index
                    + " 归属 " + (towers.ownerOf(index) == null ? "中立" : towers.ownerOf(index).display())
                    + "｜守卫 " + towers.guardCount(index)
                    + "｜读条中 " + (room.towerCapture().isArmed(index) ? "是" : "否")
                    + "｜红 " + Math.round(room.towerCapture().progressOf(index, TeamId.RED))
                    + "s / 蓝 " + Math.round(room.towerCapture().progressOf(index, TeamId.BLUE)) + "s");
        }
    }

    private void showBreaker(CommandSender sender, String[] args) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        for (TeamId team : TeamId.values()) {
            if (args.length >= 3 && TeamId.byName(args[2]) != team) {
                continue;
            }
            send(sender, "<gray>" + team.display() + " 的击破器：<white>" + room.siege().describe(team));
        }
        if (sender instanceof Player player) {
            send(sender, "<gray>你的读条进度：<white>"
                    + Math.round(room.siege().progressOf(player)) + " 秒");
        }
    }

    private void showJumpPad(CommandSender sender, String[] args) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        for (TeamId team : TeamId.values()) {
            if (args.length >= 3 && TeamId.byName(args[2]) != team) {
                continue;
            }
            Location pad = room.jumpPads().padLocation(team);
            send(sender, "<gray>" + team.display() + " 跳跃台：<white>"
                    + (room.jumpPads().isActive(team) ? "已激活" : "未激活")
                    + (pad == null ? "（未配置点位）" : " @ " + describe(pad)));
        }
    }

    private void showScore(CommandSender sender) {
        GameRoom room = requireSengokuRoom(sender);
        if (room == null) {
            return;
        }
        var session = room.sengoku();
        send(sender, "<gold>第 " + Math.max(1, session.currentRound()) + " 小局"
                + "｜比分 <white>" + session.display()
                + "</white>｜" + (session.isPaused() ? "<yellow>已暂停" : "<green>进行中")
                + (session.isFinished() ? " <red>（整场已结束）" : ""));
        long left = session.remainingSeconds();
        send(sender, "<gray>本局剩余：<white>"
                + (left < 0L ? "不限时" : left / 60L + " 分 " + left % 60L + " 秒"));
        send(sender, "<gray>中地小兵：<white>" + room.midMinions().aliveCount()
                + "/" + plugin.config().midMinionRules().safeMaxAlive());
        for (TeamId team : TeamId.values()) {
            for (Player player : room.teamPlayers(team)) {
                send(sender, "<gray>  " + team.colorTag() + player.getName()
                        + "</gray> 能量 <white>" + room.energy().energyOf(player)
                        + "/" + plugin.config().energyRules().safeMax());
            }
        }
    }

    private void checkMap(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        int required = plugin.config().towerRules().safeCount();
        String missing = def.sengoku().missingHint(required);
        send(sender, "<gold>[" + def.id() + "] 战国点位：" + def.sengoku().describe());
        send(sender, missing.isEmpty()
                ? "<green>齐全，可以开局。"
                : "<red>还缺：<white>" + missing);
    }

    // ---------------------------------------------------------------- 清除

    private void clearKeep(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, team.display() + " 天守阁区域", def.sengoku().clearKeep(team));
    }

    private void clearKeepDoor(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, team.display() + " 天守阁门前点", def.sengoku().clearKeepDoor(team));
    }

    private void clearTower(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_TOWERS, "箭楼");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        // 占领区 / 铜钟 / 守卫点是一体的，一次清掉，避免留孤儿数据
        afterClear(sender, def, "箭楼 #" + index + "（占领区 + 铜钟 + 守卫点）",
                def.sengoku().clearTower(index));
    }

    private void clearBell(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_TOWERS, "铜钟");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, "箭楼 #" + index + " 的铜钟", def.sengoku().clearBell(index));
    }

    private void clearGuard(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_TOWERS, "守卫点");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, "箭楼 #" + index + " 的守卫刷新点", def.sengoku().clearGuardSpawn(index));
    }

    private void clearMidMinion(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        int index = requireIndex(sender, args, 2, SengokuMapDef.MAX_MID_MINION_REGIONS, "中地小兵区");
        if (index < 0) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, "中地小兵刷新区 #" + index, def.sengoku().clearMidMinionRegion(index));
    }

    private void clearJumpPad(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        TeamId team = requireTeam(sender, args, 2);
        if (team == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        afterClear(sender, def, team.display() + " 跳跃台", def.sengoku().clearJumpPad(team));
    }

    /** 清空本场地的全部战国点位；需要显式补 {@code confirm}。 */
    private void clearAll(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        ArenaDef def = requireArena(sender, player);
        if (def == null) {
            return;
        }
        if (args.length < 3 || !"confirm".equalsIgnoreCase(args[2])) {
            send(sender, "<red>这会清空场地 <white>" + def.id() + "</white> 的<b>全部</b>战国点位。");
            send(sender, "<gray>确认请执行：<white>/taketori sengoku delall confirm");
            send(sender, "<dark_gray>当前点位：" + def.sengoku().describe());
            return;
        }
        def.sengoku().clear();
        plugin.arena().save();
        send(sender, "<green>已清空场地 <white>" + def.id() + "</white> 的全部战国点位。");
    }

    /**
     * 清除之后的统一收尾。
     *
     * <p>「本来就没有」也要回一句——否则管理员删完看到没有任何反馈，
     * 会以为指令没生效、反复执行。</p>
     */
    private void afterClear(CommandSender sender, ArenaDef def, String what, boolean removed) {
        if (!removed) {
            send(sender, "<gray>本场地本来就没有" + what + "，无需清除。");
            return;
        }
        plugin.arena().save();
        send(sender, "<green>已清除" + what + "。");
        int required = plugin.config().towerRules().safeCount();
        String missing = def.sengoku().missingHint(required);
        send(sender, missing.isEmpty()
                ? "<green>剩余点位仍然齐全，可以开局。"
                : "<gray>现在缺：<white>" + missing);
    }

    // ---------------------------------------------------------------- 参数助手

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, "<red>这条命令只能由玩家执行（要用到位置或选区）。");
        return null;
    }

    private TeamId requireTeam(CommandSender sender, String[] args, int position) {
        if (args.length <= position) {
            send(sender, "<red>要指定队伍：<white>red</white> 或 <white>blue</white>。");
            return null;
        }
        TeamId team = TeamId.byName(args[position]);
        if (team == null) {
            send(sender, "<red>队伍只能是 <white>red</white> 或 <white>blue</white>，收到的是 <white>"
                    + args[position] + "</white>。");
        }
        return team;
    }

    private int requireIndex(CommandSender sender, String[] args, int position, int max, String what) {
        if (args.length <= position) {
            send(sender, "<red>要指定" + what + "编号（1.." + max + "）。");
            return -1;
        }
        try {
            int index = Integer.parseInt(args[position]);
            if (index < 1 || index > max) {
                send(sender, "<red>" + what + "编号要在 1.." + max + " 之间，收到 <white>" + index + "</white>。");
                return -1;
            }
            return index;
        } catch (NumberFormatException ex) {
            send(sender, "<red>" + what + "编号不是整数：<white>" + args[position] + "</white>。");
            return -1;
        }
    }

    private ArenaDef requireArena(CommandSender sender, Player player) {
        ArenaDef def = plugin.arena().selected(player.getUniqueId());
        if (def == null) {
            send(sender, "<red>还没有选中场地。先 <white>/taketori arena setup <id> [模板名]</white>。");
        }
        return def;
    }

    private CuboidRegion requireSelection(CommandSender sender, Player player) {
        CuboidRegion region = plugin.arena().selection(player.getUniqueId());
        if (region == null) {
            send(sender, "<red>选区不完整：<white>" + plugin.arena().selectionStatus(player.getUniqueId()));
            send(sender, "<gray>站在一角 → <white>/taketori arena pos1</white>；走到对角 → "
                    + "<white>/taketori arena pos2</white>；再执行本条指令。");
        }
        return region;
    }

    private GameRoom requireSengokuRoom(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>这条命令只能由玩家在战国房间里执行。");
            return null;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null || !room.isSengoku()) {
            send(sender, "<red>你不在战国 3v3 房间里。");
            return null;
        }
        return room;
    }

    private String describe(Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private void send(CommandSender sender, String miniMessage) {
        Component component = MINI.deserialize(miniMessage);
        sender.sendMessage(component);
    }

    private void usage(CommandSender sender) {
        send(sender, "<gold>/taketori sengoku <grey>—— 战国 3v3（三局两胜）");
        send(sender, "<yellow>划区<gray>（先选中场地；区域类用 pos1/pos2，点类站在位置上）：");
        for (String line : List.of(
                "setkeep <red|blue>      天守阁区域",
                "setkeepdoor <red|blue>  天守阁门前点（击破器与跳跃台的生成位置）",
                "settower <序号>         箭楼占领区",
                "setbell <序号>          箭楼铜钟位置",
                "setguard <序号>         箭楼守卫刷新点",
                "setmid [序号]           中地小兵刷新区",
                "setjumppad <red|blue>   跳跃台位置")) {
            send(sender, "<gray>  " + line);
        }
        send(sender, "<yellow>运维<gray>（在战国房间内执行）：");
        for (String line : List.of(
                "start                   强制开局",
                "pause | resume          暂停 / 继续小局计时",
                "endround [red|blue]     强制结束本小局（不指定则判平局重开）",
                "towers                  查看箭楼归属与读条",
                "breaker [red|blue]      查看击破器位置",
                "jumppad [red|blue]      查看跳跃台状态",
                "score                   查看比分与各人能量（所有玩家可用）",
                "check                   检查本场地点位是否齐全")) {
            send(sender, "<gray>  " + line);
        }
        send(sender, "<yellow>删除<gray>（画错了要能改；deltower 会连铜钟与守卫点一起清）：");
        for (String line : List.of(
                "delkeep <red|blue>      清除天守阁区域",
                "delkeepdoor <red|blue>  清除天守阁门前点",
                "deltower <序号>         清除整座箭楼（占领区 + 铜钟 + 守卫点）",
                "delbell <序号>          只清铜钟",
                "delguard <序号>         只清守卫刷新点",
                "delmid <序号>           清除中地小兵刷新区",
                "deljumppad <red|blue>   清除跳跃台",
                "delall confirm          清空本场地的全部战国点位")) {
            send(sender, "<gray>  " + line);
        }
        send(sender, "<yellow>模式<gray>（写回 config.yml，只影响之后新建的房间）：");
        for (String line : List.of(
                "mode [pvp|pve|sengoku_3v3]  查看或切换全局默认模式",
                "menu                        打开图形化面板（同一组指令的按钮版）")) {
            send(sender, "<gray>  " + line);
        }
    }
}
