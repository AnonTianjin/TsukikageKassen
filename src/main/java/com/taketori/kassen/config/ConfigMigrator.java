package com.taketori.kassen.config;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置迁移（计划 §5.6 第 4 条：配置带版本号，启动时按迁移链升级）。
 *
 * <p>为什么必须有它：插件只在<b>配置文件不存在</b>时写出默认值，
 * 所以一旦改过默认行为，老服务器的 config.yml 会一直停留在旧默认值上，
 * 表现为"插件更新了但行为没变"——正是 Q 键只切快捷栏、不触发模式切换的成因。</p>
 */
public final class ConfigMigrator {

    /** 当前配置结构版本；每次改动默认值或键名都要 +1，并在下面加一段迁移。 */
    public static final int CURRENT_VERSION = 10;

    private final TaketoriPlugin plugin;

    public ConfigMigrator(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 执行迁移；返回 true 表示改动过配置（已写回文件）。 */
    public boolean migrate() {
        FileConfiguration config = plugin.getConfig();
        int version = config.getInt("config-version", 1);
        if (version >= CURRENT_VERSION) {
            return false;
        }

        List<String> notes = new ArrayList<>();

        if (version < 2) {
            // v1 → v2：Q 键的默认行为曾经是 held-slot（只切快捷栏），
            // 导致"按 Q 没反应、模式切换不生效"。改为 drop（触发 q 槽技能）。
            String qMode = config.getString("input.q-mode", "");
            if (qMode == null || qMode.isBlank() || "held-slot".equalsIgnoreCase(qMode)) {
                config.set("input.q-mode", "drop");
                notes.add("input.q-mode: " + (qMode == null || qMode.isBlank() ? "(缺失)" : qMode)
                        + " → drop（Q 现在会触发武器 q 槽声明的技能，例如月读/将军/盾胁/月镜/旗鱼的模式切换）");
            }
        }

        if (version < 3) {
            // v2 → v3：对局节奏调整。每队基地数 6 → 3（在 ArenaManager 里，不受 config 控制），
            // 刷怪 15 秒 → 9 秒、场上上限 30 → 15，并新增"开局 60 秒禁止占点"的保护期。
            // 只改写"还停在旧默认值"的项：自己调过的数值不动，避免覆盖服主的设置。
            if (config.getInt("minion.interval-seconds", 15) == 15) {
                config.set("minion.interval-seconds", 9);
                notes.add("minion.interval-seconds: 15 → 9（刷怪更快）");
            }
            if (config.getInt("minion.max-alive", 30) == 30) {
                config.set("minion.max-alive", 15);
                notes.add("minion.max-alive: 30 → 15（场上小兵最多 15 个，达到就不再刷）");
            }
            if (!config.isSet("base.capture-delay-seconds")) {
                config.set("base.capture-delay-seconds", 60);
                notes.add("base.capture-delay-seconds: 新增 60（开局 60 秒内禁止占点）");
            }
            notes.add("每队基地数量由 config.yml 的 base.count-per-team 决定（默认 3）：arena.yml 里编号超出的基地会被忽略");
        }

        if (version < 4) {
            // v3 → v4：两项新增默认行为——
            //   ① 第三槽技能（F 键）附带 2 秒跳跃提升 V
            //   ② 绑定角色时发一套保护 II 的铁甲
            // 只补缺失的键，已经自己写过的值不动。
            if (!config.isSet("combat.third-slot-buff.enabled")) {
                config.set("combat.third-slot-buff.enabled", true);
                config.set("combat.third-slot-buff.type", "JUMP_BOOST");
                config.set("combat.third-slot-buff.amplifier", 4);
                config.set("combat.third-slot-buff.ticks", 40);
                notes.add("combat.third-slot-buff: 新增（第三槽技能附带 2 秒跳跃提升 V，关掉请设 enabled: false）");
            }
            if (!config.isSet("loadout.armor-enabled")) {
                config.set("loadout.armor-enabled", true);
                config.set("loadout.armor-material", "IRON");
                config.set("loadout.armor-protection", 2);
                notes.add("loadout: 新增（开局发一套保护 II 铁甲，关掉请设 armor-enabled: false）");
            }
        }

        if (version < 5) {
            // v4 → v5：第三槽技能的默认触发方式从 F 键换成"双击潜行键"。
            // F 键（交换副手）在部分服务器/插件下事件根本不到达，表现就是"按 F 完全没反应"；
            // 潜行键是独立按键，原版一定会发出事件，且不受"对着方块右键"这类原版分支影响。
            // 只改还停留在旧默认值（f）或缺失的配置；自己写成 sneak-right / both 等的不动。
            String trigger = config.getString("input.shift-right-trigger", "");
            if (trigger == null || trigger.isBlank() || "f".equalsIgnoreCase(trigger.trim())) {
                config.set("input.shift-right-trigger", "double-sneak");
                notes.add("input.shift-right-trigger: "
                        + (trigger == null || trigger.isBlank() ? "(缺失)" : trigger)
                        + " → double-sneak（第三槽技能改为「双击潜行键」触发；"
                        + "想用回 F 就写 f，想多种方式都行就用逗号分隔，写 all 全部启用）");
            }
        }

        if (version < 6) {
            // v5 → v6：每队基地数量从代码常量改成配置项 base.count-per-team。
            // 默认值就是原来的 3，所以行为不变；想改成别的数量（或者 auto = 以实际划定为定）改这一行即可。
            if (!config.isSet("base.count-per-team")) {
                config.set("base.count-per-team", 3);
                notes.add("base.count-per-team: 新增 3（每队基地数量现在可配：正整数或 auto；"
                        + "改成 1 就是每队 1 个基地，改成 auto 则以你实际划定的为准）");
            }
        }

        if (version < 7) {
            // v6 → v7：多世界兼容（接管白名单 + 播报范围）与菜单时钟。
            // 这些键"不写就用新默认值"，这里只是把它们写进文件，方便你看到并修改。
            if (!config.isSet("worlds.broadcast-scope")) {
                config.set("worlds.broadcast-scope", "world");
                notes.add("worlds.broadcast-scope: 新增 world（多世界服务器下播报只发给消息所属世界；"
                        + "写 all 恢复全服广播）");
            }
            if (!config.isSet("lobby.takeover-worlds")) {
                config.set("lobby.takeover-worlds", new ArrayList<String>());
                notes.add("lobby.takeover-worlds: 新增空列表（进服只接管大厅所在世界；"
                        + "写 [\"*\"] 恢复「接管所有世界」的旧行为）");
            }
            if (!config.isSet("menu-clock.enabled")) {
                config.set("menu-clock.enabled", true);
                config.set("menu-clock.material", "CLOCK");
                config.set("menu-clock.give-on-join", true);
                notes.add("menu-clock: 新增（给玩家发一个右键打开玩家菜单的时钟，可关或换材质）");
            }
        }

        if (version < 8) {
            // v7 → v8：BedWars 式多房间匹配。新增 waiting 等待区段——
            // 玩家匹配后先进房间等待出生点集结，人数达标倒计时，开局瞬间分队并进出生点玻璃笼。
            // 全部只补缺失键，服主自己写过的值不动。
            if (!config.isSet("waiting.min-players")) {
                config.set("waiting.min-players", 2);
            }
            if (!config.isSet("waiting.countdown-seconds")) {
                config.set("waiting.countdown-seconds", 90);
            }
            if (!config.isSet("waiting.full-countdown-seconds")) {
                config.set("waiting.full-countdown-seconds", 5);
            }
            if (!config.isSet("waiting.half-countdown-seconds")) {
                config.set("waiting.half-countdown-seconds", 30);
            }
            if (!config.isSet("waiting.cage-hold-seconds")) {
                config.set("waiting.cage-hold-seconds", 3);
            }
            if (!config.isSet("waiting.end-delay-seconds")) {
                config.set("waiting.end-delay-seconds", 5);
            }
            if (!config.isSet("waiting.cage-material")) {
                config.set("waiting.cage-material", "GLASS");
            }
            if (!config.isSet("waiting.pve-full-players")) {
                config.set("waiting.pve-full-players", 0);
            }
            if (!config.isSet("waiting.void-y-offset")) {
                config.set("waiting.void-y-offset", -10);
            }
            if (!config.isSet("waiting.protect")) {
                config.set("waiting.protect", true);
            }
            notes.add("waiting: 新增等待区段（min-players=2 / countdown=90s / 满员 5s / "
                    + "出生点玻璃笼 3s / 结算 5s）——匹配改为 BedWars 式：大厅匹配 → 房间等待区 → 倒计时开局");
        }

        if (version < 9) {
            // v8 → v9：动态房间制（月宫）。房间改为玩家按需创建——异步复制 moonmaps/ 模板世界，
            // 结束后整场删除；不再按启用场地自动建房。全部只补缺失键，服主自己写过的值不动。
            if (!config.isSet("room.max-rooms")) {
                config.set("room.max-rooms", 8);
            }
            if (!config.isSet("room.max-rooms-per-player")) {
                config.set("room.max-rooms-per-player", 1);
            }
            if (!config.isSet("room.world-prefix")) {
                config.set("room.world-prefix", "kassen_");
            }
            if (!config.isSet("room.default-template")) {
                config.set("room.default-template", "tsukuyomi");
            }
            if (!config.isSet("room.empty-dispose-seconds")) {
                config.set("room.empty-dispose-seconds", 60);
            }
            if (!config.isSet("room.understaffed-grace-seconds")) {
                config.set("room.understaffed-grace-seconds", 60);
            }
            if (!config.isSet("combat.melee-charge-gate")) {
                config.set("combat.melee-charge-gate", 0.9);
            }
            notes.add("room: 新增动态房间段（同时上限 8 / 每人 1 房 / 世界前缀 kassen_ / 默认模板 tsukuyomi / 空房 60s 解散）"
                    + "——房间改为玩家创建：异步复制 moonmaps 模板世界，结算完成后自动删除；"
                    + "另新增 combat.melee-charge-gate 近战蓄力门控");
        }

        if (version < 10) {
            // v9 → v10：匹配重写配套。新增断线重连时限与派对（组队）人数上限，只补缺失键。
            if (!config.isSet("room.rejoin-seconds")) {
                config.set("room.rejoin-seconds", 300);
            }
            if (!config.isSet("party.max-size")) {
                config.set("party.max-size", 3);
            }
            notes.add("room.rejoin-seconds: 新增 300（对局中掉线 5 分钟内重连回原房原队，0 = 关闭）"
                    + "；party.max-size: 新增 3（派对组队：整队同房、开局整组同队）");
        }


        config.set("config-version", CURRENT_VERSION);
        plugin.saveConfig();

        plugin.getLogger().warning("检测到旧版配置，已自动迁移到 v" + CURRENT_VERSION + "：");
        for (String note : notes) {
            plugin.getLogger().warning("  - " + note);
        }
        if (notes.isEmpty()) {
            plugin.getLogger().warning("  - 仅更新 config-version（没有需要改写的行为项）");
        }
        plugin.getLogger().warning("  若你想保留旧行为，请手动改回并 reload。");
        return true;
    }
}
