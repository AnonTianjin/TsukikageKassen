"""从 characters.yml + weapons.yml 生成 docs/characters.md（角色技能表）。

用法（仓库根目录）：
    python tools/gen-character-table.py

只读配置、只写一个文件，不参与构建。改了角色/武器数值后重跑一次即可。
"""

import io
import os
import re
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
OUT = os.path.join(ROOT, "docs", "characters.md")

TYPE_CN = {
    "armor_break": "破甲",
    "blink": "闪现",
    "deploy_zone": "场地",
    "echo_consume": "兑现",
    "equip_switch": "换装",
    "grapple": "钩索",
    "mark_apply": "标记",
    "melee_smash": "近战横扫",
    "mirror_burst": "镜爆",
    "mirror_skill": "镜面",
    "mode_switch": "模式切换",
    "projectile": "投掷物",
    "pull": "牵引",
    "reflect": "反制",
    "rocket_jump": "火箭跳",
    "self_boost": "自我强化",
    "shield_guard": "盾防",
    "shockwave": "冲击波",
    "special_shot_toggle": "特射开关",
    "summon_ally": "召唤",
}

KEY_CN = {
    "left": "左键",
    "right": "右键",
    "shift-right": "Shift+右键",
    "q": "Q",
    "passive": "被动",
}

SKIP_PARAM = {"particle", "sound", "hit-particle", "hit-sound", "display", "name"}

# 本文件是内部设定资料，按 .name-revert/map.tsv 用 IP 原名列出角色与武器；
# 游戏内（characters.yml / weapons.yml）与对外文档仍使用规避版名称。
IP_CHAR = {
    "月读": "辉夜",
    "将军": "帝",
    "疾风": "彩叶",
    "夕月": "乃依",
    "盾胁": "雷",
    "月巫": "八千代",
    "铁卫": "宅公",
    "影丸": "真实",
    "吉野": "芦花",
}

IP_WEAPON = {
    "陨星锤": "火箭锤",
    "镇魂铃": "月铃",
    "大太刀": "帝之大太刀",
    "疾风之剑": "彩叶之剑",
    "夕月之弓": "乃依之弓",
    "夕月之匕": "乃依之匕",
    "冰棘鱼": "冰冻旗鱼",
    "铁牙": "忠犬之牙",
    "铁锁": "忠犬之锁",
    "碎岩牙": "铁碎牙",
    "影铳": "真实之铳",
    "影镜": "真实之镜",
    "吉野之苇": "芦花之苇",
    "云丝": "绫䌷之丝",
}


IP_LORE = [
    ("月宫之主", "月之公主"),
    ("月宫巫女", "月见之巫女"),
    ("守夜之姿", "忠犬本色"),
]


def ip_text(text):
    """lore 这类自由文本：先换武器名，再换称谓。"""
    for old, new in IP_WEAPON.items():
        text = text.replace(old, new)
    for old, new in IP_LORE:
        text = text.replace(old, new)
    return text


def ip_char(name):
    return IP_CHAR.get(name, name)


def ip_weapon(name):
    return IP_WEAPON.get(name, name)


TAG = re.compile(r"<[^>]+>")
SECTION = re.compile(r"§.")


def plain(text):
    """去掉 MiniMessage / 传统颜色码，只留纯文本。"""
    if not isinstance(text, str):
        return ""
    return SECTION.sub("", TAG.sub("", text)).strip()


def fmt_num(value):
    if isinstance(value, bool):
        return "是" if value else "否"
    if isinstance(value, float):
        return ("%g" % value)
    return str(value)


def skills_of(block):
    out = []
    if not isinstance(block, dict):
        return out
    for key in ("left", "right", "shift-right", "q", "passive"):
        skill = block.get(key)
        if not isinstance(skill, dict):
            continue
        params = skill.get("params") or {}
        out.append({
            "key": key,
            "type": skill.get("type") or "",
            "name": plain(params.get("display") or skill.get("name") or ""),
            "cd": skill.get("cooldown", 0),
            "params": {k: v for k, v in params.items() if k not in SKIP_PARAM},
        })
    return out


def cd_text(cd):
    try:
        value = float(cd)
    except (TypeError, ValueError):
        return "—"
    return "—" if value <= 0 else ("%gs" % value)


def params_text(params):
    if not params:
        return "—"
    return " · ".join("%s=%s" % (k, fmt_num(v)) for k, v in params.items())


def main():
    chars = yaml.safe_load(io.open(os.path.join(RES, "characters.yml"), encoding="utf-8"))["characters"]
    weapons = yaml.safe_load(io.open(os.path.join(RES, "weapons.yml"), encoding="utf-8"))["weapons"]

    lines = []
    add = lines.append

    add("# 角色与技能表")
    add("")
    add("九名角色的定位、武器与全部技能数值。阵容取自《超辉夜姬！》（超かぐや姫!）——")
    add("**本文件是内部设定资料，按 IP 原名列出**；游戏内与 README 等对外文档使用规避版名称。")
    add("角色 id（`tsukuyomi` / `shogun` / …）保持稳定，改名只动显示名与 lore。")
    add("")
    add("> 本文件由 `tools/gen-character-table.py` 从 `characters.yml` + `weapons.yml` 生成，")
    add("> 改完数值重跑一次即可，不要手改。")
    add("")
    add("**数值基准**：全套铁甲减伤 15/(15+8) ≈ 65.2%，即实际伤害 ≈ 配置伤害 × 0.35；")
    add("想「对铁甲 N 击击杀」（20 血）→ 配置伤害 ≈ 20 / N × 2.87。")
    add("")

    # ---------------- 总览
    add("## 总览")
    add("")
    add("| 角色 | id | 血量 | 移速 | 武器 |")
    add("| --- | --- | ---: | ---: | --- |")
    for cid, c in chars.items():
        attrs = c.get("attributes") or {}
        wnames = "、".join(ip_weapon(plain(weapons[w].get("display"))) for w in c["weapons"])
        add("| **%s** | `%s` | %s | %s | %s |" % (
            ip_char(plain(c.get("display"))), cid,
            fmt_num(attrs.get("max-health", 20.0)), fmt_num(attrs.get("movement-speed", 0.1)),
            wnames))
    add("")

    # ---------------- 逐角色
    for cid, c in chars.items():
        attrs = c.get("attributes") or {}
        add("## %s · `%s`" % (ip_char(plain(c.get("display"))), cid))
        add("")
        add("血量 **%s** ／ 移速 **%s** ／ 武器 **%d** 把" % (
            fmt_num(attrs.get("max-health", 20.0)), fmt_num(attrs.get("movement-speed", 0.1)),
            len(c["weapons"])))
        add("")
        for line in c.get("lore") or []:
            add("> %s" % ip_text(plain(line)))
        add("")
        for wid in c["weapons"]:
            w = weapons[wid]
            add("### %s · `%s`" % (ip_weapon(plain(w.get("display"))), wid))
            add("")
            meta = "材质 `%s`" % w.get("material")
            default_mode = w.get("default-mode")
            modes = w.get("modes") or {}
            if default_mode:
                meta += " ／ 默认模式 **%s**" % plain((modes.get(default_mode) or {}).get("display") or default_mode)
            add(meta)
            add("")
            add("| 模式 | 按键 | 技能 | 类型 | 冷却 | 关键数值 |")
            add("| --- | --- | --- | --- | ---: | --- |")
            rows = 0
            if modes:
                mode_keys = set()
                for m in modes.values():
                    mode_keys.update(skill["key"] for skill in skills_of(m.get("skills")))
                for mname, m in modes.items():
                    label = plain(m.get("display") or mname)
                    if mname == default_mode:
                        label += "（默认）"
                    for skill in skills_of(m.get("skills")):
                        add("| %s | %s | %s | %s | %s | %s |" % (
                            label, KEY_CN.get(skill["key"], skill["key"]),
                            skill["name"] or "—",
                            TYPE_CN.get(skill["type"], skill["type"]),
                            cd_text(skill["cd"]), params_text(skill["params"])))
                        rows += 1
                # 武器级 skills 只给"各模式都没写"的按键兜底，单独标出来
                for skill in skills_of(w.get("skills")):
                    if skill["key"] in mode_keys:
                        continue
                    add("| 通用（各模式） | %s | %s | %s | %s | %s |" % (
                        KEY_CN.get(skill["key"], skill["key"]),
                        skill["name"] or "—",
                        TYPE_CN.get(skill["type"], skill["type"]),
                        cd_text(skill["cd"]), params_text(skill["params"])))
                    rows += 1
            else:
                for skill in skills_of(w.get("skills")):
                    add("| 通用 | %s | %s | %s | %s | %s |" % (
                        KEY_CN.get(skill["key"], skill["key"]),
                        skill["name"] or "—",
                        TYPE_CN.get(skill["type"], skill["type"]),
                        cd_text(skill["cd"]), params_text(skill["params"])))
                    rows += 1
            if rows == 0:
                add("| — | — | 该武器没有独立技能 | — | — | — |")
            add("")

    # ---------------- 类型索引
    add("## 技能类型索引")
    add("")
    add("| 类型 id | 中文 | 说明 |")
    add("| --- | --- | --- |")
    desc = {
        "shockwave": "360° 冲击并向上击飞",
        "rocket_jump": "用爆炸把自己送出去",
        "melee_smash": "扇形近战，连击可叠伤",
        "projectile": "发射投掷物，命中结算范围伤害",
        "pull": "把敌人拉向自己",
        "grapple": "钩索位移",
        "reflect": "把飞行物弹回发射者",
        "mark_apply": "给目标挂易伤标记，全队受益",
        "armor_break": "削防，破甲后由队友兑现",
        "deploy_zone": "静态领域，周期结算",
        "summon_ally": "召唤单位，到期回收",
        "self_boost": "位移类自我强化",
        "blink": "短距闪现",
        "shield_guard": "举盾减伤",
        "mirror_burst": "镜面爆发范围控制",
        "mirror_skill": "镜面反射",
        "mode_switch": "切换武器模式",
        "equip_switch": "切换到另一件装备",
        "special_shot_toggle": "切换特殊射击",
        "echo_consume": "兑现标记 / 破甲",
    }
    used = set()
    for c in chars.values():
        for wid in c["weapons"]:
            w = weapons[wid]
            for skill in skills_of(w.get("skills")):
                used.add(skill["type"])
            for m in (w.get("modes") or {}).values():
                for skill in skills_of(m.get("skills")):
                    used.add(skill["type"])
    for tid in sorted(used):
        add("| `%s` | %s | %s |" % (tid, TYPE_CN.get(tid, tid), desc.get(tid, "")))
    add("")

    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("wrote %s (%d lines)" % (OUT, len(lines)))


if __name__ == "__main__":
    main()
