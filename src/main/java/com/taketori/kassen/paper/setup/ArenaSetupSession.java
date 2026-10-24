package com.taketori.kassen.paper.setup;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.ArenaDef;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 划场地一条龙会话（对齐 BedWars SetupSession 的"单入口"体验）。
 *
 * <p>过去划一个场地要跨三个概念：{@code arena create} 建定义、手动把世界文件夹
 * 放进 {@code moonmaps/}、{@code moonmap load} 进编辑世界——哪一步名字对不上都不行。
 * 现在一个入口串起来：</p>
 * <ol>
 *   <li>{@code /taketori arena setup <id> [模板名]}：建场地（缺则建）+ 选中 +
 *       载入编辑世界 + 输出还缺什么（checklist）；</li>
 *   <li>会话期间每划完一项自动刷新剩余清单；</li>
 *   <li>{@code /taketori arena setup done}：校验就绪 → 写回模板世界 → 保存 arenas.yml；</li>
 *   <li>{@code /taketori arena setup cancel}：放弃会话（编辑世界保留，可手动 unload）。</li>
 * </ol>
 *
 * <p>会话是<b>每管理员一份、不持久化</b>：服务器重启或插件重载后需要重新 setup
 * （编辑世界与 arenas.yml 的数据都还在，重进会话继续即可）。</p>
 */
public final class ArenaSetupSession {

    /** 一条会话：场地 id + 模板名（缺省等于场地 id）。 */
    public record Session(String arenaId, String templateName) {
    }

    private final TaketoriPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ArenaSetupSession(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public Session sessionOf(UUID admin) {
        return admin == null ? null : sessions.get(admin);
    }

    /**
     * 开始会话：建/选场地并载入模板编辑世界。
     * 返回 null 表示已开始（或已在会话中——此时刷新提示）。
     */
    public Session start(Player admin, String id, String templateName) {
        if (admin == null || id == null || id.isBlank()) {
            return null;
        }
        UUID uuid = admin.getUniqueId();
        if (sessions.containsKey(uuid)) {
            Session existing = sessions.get(uuid);
            admin.sendMessage(plugin.config().messages().get("setup.already-active",
                    "id", existing.arenaId()));
            // 编辑世界可能已经不在了（写回失败转存、手动 moonmap unload、reload 清理）：
            // 这里重新把它拉起来。否则 done() 会一直以「世界未加载」拒绝他，管理员就卡在
            // 会话里出不去（start 被 already-active 挡住、done 又不肯收尾）。
            plugin.rooms().loadMoonmap(admin, existing.templateName());
            sendChecklist(admin);
            return null;
        }
        var arenaManager = plugin.arena();
        ArenaDef def = arenaManager.get(id);
        if (def == null) {
            def = arenaManager.create(id);
            if (def == null) {
                admin.sendMessage(plugin.config().messages().get("room.arena-bad-id"));
                return null;
            }
            arenaManager.save();
        }
        if (!arenaManager.select(uuid, id)) {
            admin.sendMessage(plugin.config().messages().get("room.arena-not-found", "id", id));
            return null;
        }
        String template = templateName == null || templateName.isBlank() ? id : templateName;
        // 与 RoomManager.validatedMoonmapName 同一口径的入口校验：
        // 目录探测虽然只是 isDirectory 不存在穿越风险，但 session 里存的模板名
        // 会以未校验形态流向 checkTemplateWorld 等处，入口先拦一道保持一致
        if (!template.matches("[A-Za-z0-9_-]{1,64}")) {
            admin.sendMessage(plugin.config().messages().get("setup.no-template",
                    "template", template));
            return null;
        }
        File templateDir = new File(new File(plugin.getDataFolder(), "moonmaps"), template);
        if (!templateDir.isDirectory() || !new File(templateDir, "level.dat").isFile()) {
            admin.sendMessage(plugin.config().messages().get("setup.no-template",
                    "template", template));
            return null;
        }
        Session session = new Session(id, template);
        sessions.put(uuid, session);
        plugin.rooms().loadMoonmap(admin, template);
        sendChecklist(admin);
        return session;
    }

    /**
     * 完成：校验就绪 → 写回模板世界 → **写回真正落盘后**才对齐模板名、保存 arenas.yml 并回报。
     * 返回 false 表示没完成（原因已提示，会话保留，可修好后重试）。
     */
    public boolean done(Player admin) {
        if (admin == null) {
            return false;
        }
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            admin.sendMessage(plugin.config().messages().get("setup.none"));
            return false;
        }
        ArenaDef def = plugin.arena().get(session.arenaId());
        if (def == null || !def.isReady()) {
            admin.sendMessage(plugin.config().messages().get("setup.not-ready",
                    "id", session.arenaId(),
                    "missing", def == null ? "场地已被删除" : def.missingHint()));
            if (def == null) {
                // 场地被删了就永远收不了尾，得给出唯一的逃生门
                admin.sendMessage(plugin.config().messages().get("setup.arena-gone"));
            }
            sendChecklist(admin);
            return false;
        }
        // 写回的前提：编辑世界必须在。unloadMoonmap 在「世界未加载 / 正忙」时会直接早退、
        // 什么都不写回，这里若不拦住就会报出假成功。
        String worldName = "k_tpl_" + session.templateName();
        if (org.bukkit.Bukkit.getWorld(worldName) == null) {
            admin.sendMessage(plugin.config().messages().get("setup.world-not-loaded",
                    "id", session.arenaId(), "world", worldName));
            return false;
        }
        // 改名与「已完成」都必须等写回落盘：unloadMoonmap 是异步的，返回后立刻改名会与它
        // 抢 moonmaps/<模板名>，导致新图落在旧名下、或改出半截模板（见 unloadMoonmap 注释）。
        plugin.rooms().unloadMoonmap(admin, session.templateName(), () -> {
            sessions.remove(admin.getUniqueId());
            plugin.arena().save();
            String alignIssue = alignTemplateFolder(session.templateName(), session.arenaId());
            if (alignIssue == null) {
                admin.sendMessage(plugin.config().messages().get("setup.done", "id", session.arenaId()));
            } else {
                // 不能先说「已完成、可以开局了」再补警告：房间按场地 id 找模板，这时它读到的
                // 是另一份地图，必须先把这个事实说清楚。
                admin.sendMessage(plugin.config().messages().get("setup.align-skipped",
                        "id", session.arenaId(), "template", session.templateName(),
                        "reason", alignIssue));
            }
        });
        return true;
    }

    /**
     * 模板名与场地 id 不一致时（{@code setup <id> <模板名>}），把 {@code moonmaps/<模板名>}
     * 改名为 {@code moonmaps/<id>}——创建房间时按场地 id 找模板文件夹，不改名就永远
     * "模板缺失"。目标已存在时不动（避免覆盖），由调用方提示管理员。
     *
     * @return {@code null} = 已对齐（或本来就同名/没有源目录）；否则是没对齐的原因
     */
    private String alignTemplateFolder(String templateName, String arenaId) {
        if (templateName.equals(arenaId)) {
            return null;
        }
        File source = new File(new File(plugin.getDataFolder(), "moonmaps"), templateName);
        File target = new File(new File(plugin.getDataFolder(), "moonmaps"), arenaId);
        if (!source.isDirectory()) {
            return null;
        }
        if (target.exists()) {
            plugin.getLogger().warning("[setup] moonmaps/" + templateName + " 想改名为 moonmaps/"
                    + arenaId + "，但目标已存在，未改动（房间复制会读取已存在的 " + arenaId + "）");
            return "moonmaps/" + arenaId + " 已存在";
        }
        if (source.renameTo(target)) {
            plugin.getLogger().info("[setup] 模板文件夹已对齐场地 id：moonmaps/" + templateName
                    + " → moonmaps/" + arenaId);
            return null;
        }
        plugin.getLogger().warning("[setup] 模板文件夹改名失败：moonmaps/" + templateName
                + " → moonmaps/" + arenaId + "（请手动改名，否则按 id 建房会提示模板缺失）");
        return "改名失败（可能有文件被占用）";
    }

    /** 取消会话：只清状态（编辑世界仍加载着，可手动 moonmap unload 保存）。 */
    public void cancel(Player admin) {
        if (admin == null) {
            return;
        }
        Session session = sessions.remove(admin.getUniqueId());
        if (session == null) {
            admin.sendMessage(plugin.config().messages().get("setup.none"));
            return;
        }
        admin.sendMessage(plugin.config().messages().get("setup.cancelled", "id", session.arenaId()));
    }

    /** 输出剩余必设项清单（会话内 / 每次划完一项后）。 */
    public void sendChecklist(Player admin) {
        if (admin == null || !admin.isOnline()) {
            return;
        }
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) {
            return;
        }
        ArenaDef def = plugin.arena().get(session.arenaId());
        if (def == null) {
            return;
        }
        admin.sendMessage(plugin.config().messages().get("setup.checklist-header",
                "id", def.id(), "world", "k_tpl_" + session.templateName()));
        String missing = def.missingHint();
        admin.sendMessage(plugin.config().messages().get("setup.checklist-missing",
                "missing", missing));
        if (!"无".equals(missing)) {
            admin.sendMessage(plugin.config().messages().get("setup.checklist-howto"));
        }
    }

    /** 会话里的管理员集合（调试/遍历用）。 */
    public java.util.Set<UUID> activeAdmins() {
        return java.util.Set.copyOf(sessions.keySet());
    }

    /** 插件卸载：清空会话状态（编辑世界交给 unloadMoonmap 既有流程处理）。 */
    public void clearAll() {
        sessions.clear();
    }
}
