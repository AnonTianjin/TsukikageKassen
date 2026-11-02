# 月影合战 · TsukikageKassen

[![Build](https://github.com/AnonTianjin/TsukikageKassen/actions/workflows/build.yml/badge.svg)](https://github.com/AnonTianjin/TsukikageKassen/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/AnonTianjin/TsukikageKassen)](https://github.com/AnonTianjin/TsukikageKassen/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

一个 Minecraft 服务端战斗插件。核心是一层用原版事件重绑按键的技能系统。

角色、武器与技能全部由配置文件驱动，改数值不用重新编译。在技能层之上叠了四种玩法：3v3 积分赛、拆包战、PVE 月人入侵、战国 3v3（三局两胜）。配套大厅、动态房间匹配（月宫制）、观战和跨局战绩。武器之间可以打出「挂标记 → 兑现」的组合技。

| 项 | 说明 |
| --- | --- |
| 服务端 | Paper 26.1.2 |
| Java | 25 |
| 客户端 | 原版即可，不需要资源包或 mod |
| 额外依赖 | 无 |
| 许可 | MIT |

---

# 给服主与玩家

## 一、安装

1. 从 [Releases](https://github.com/AnonTianjin/TsukikageKassen/releases/latest) 下载
   最新版本的 `TsukikageKassen-<版本>.jar`，放进服务端 `plugins/` 目录，重启服务器。
2. 首次启动会在 `plugins/TsukikageKassen/` 生成 `config.yml`、`weapons.yml`、`characters.yml`、`messages.yml`、五份 `sengoku*.yml` 与模板目录 `moonmaps/`。
3. 控制台出现下面这行即加载成功（`v` 后面是实际版本号）：

```
TsukikageKassen v<版本> 已启用：<武器数> 把武器 / <角色数> 个角色 / <技能类型数> 种技能类型（适配层 default）
```

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `taketori.play` | 所有人 | 玩家指令：菜单、选角色、加入队列、观战、技能查询 |
| `taketori.admin` | OP | 管理指令：场地、大厅、分队、开局、发武器、编辑器、重载、调试 |

> 插件的 `api-version` 声明为 `26.1.2`，服务端低于该版本会拒绝加载。

装好之后的四种玩法，玩家只需要记住：大厅点告示牌「加入」或「降临月宫」建房，等倒计时归零就能开打。

## 二、十步设置

从装好插件到能开一局，按顺序做完这十步即可（除第 1 步准备模板外，全部在游戏内执行）。
第 2~6 步由划场地会话（`/taketori arena setup`）串起来：一次进入编辑世界，之后每划完一项
会自动刷新「还缺什么」的清单。区域类型、划点指令与 `arenas.yml` 的存储结构见
[区域配置](docs/regions.md)，模板世界的复制 / 导入 / 回收见[多世界](docs/multi-world.md)。

### 1. 准备月面模板

模板是 `plugins/TsukikageKassen/moonmaps/<模板名>/` 下的一个世界文件夹（`level.dat`、`region/`、
`entities/`、`data/` 任一存在即算世界；从 Paper 26.1 的 `dimensions/minecraft/<世界名>` 里搬
过来的也一样，缺 `level.dat` 的旧图插件会用主世界那份补齐），三种来源任选其一：

a. 已有地图：把世界文件夹整个复制进 `moonmaps/tsukuyomi/`（`moonmaps/` 目录首启自动创建）。

b. 用服务器里已有的世界：不必手动搬文件夹，直接导入：

```
/taketori moonmap import <世界名> <场地id>
```

世界正加载着也没关系，会先自动存盘再复制；导入完即可直接 `arena setup` 划定。

c. 手头没有地图、只想先试一局：生成一张空白平坦模板：

```
/taketori moonmap create <模板名>
```

生成后会自动注册成场地定义，接着 `arena setup` 进去摆刷新区和基地即可。

### 2. 开始划场地

```
/taketori arena setup tsukuyomi       # 建场地（缺则建）+ 选中 + 载入编辑世界 + 列出还缺什么
/taketori arena wand               # 领选区锄
```

`arena setup <场地id> [模板名]` 会把模板复制成编辑世界 `k_tpl_<模板名>` 并把你传送进去。
模板名与场地 id 不同名时写全（`/taketori arena setup tsukuyomi mymap`），收尾时会把
`moonmaps/mymap/` 自动对齐改名为 `moonmaps/tsukuyomi/`。

### 3. 划月人刷新区

用锄头左键点区域一角、右键点对角，然后：

```
/taketori arena setminion
```

可配多个刷新区（`setminion [编号] [normal|mixed]`）：普通月人在全部刷新区之间轮转均分；
标签 `normal` = 只刷普通月人，`mixed` = 普通 + 精英（默认，也可直接改 arenas.yml 的
`minion-regions.<编号>.kind`）。精英月人与 PVE 大波次只在 mixed 区刷新。

### 4. 划双方基地

每个基地都用锄头点两个对角，然后：

```
/taketori arena setbase red 1
/taketori arena setbase red 2
/taketori arena setbase red 3
/taketori arena setbase blue 1
/taketori arena setbase blue 2
/taketori arena setbase blue 3
```

每队数量由 `base.count-per-team` 决定（默认 3，可写 1~16 或 `auto` 表示以实际划定为定）；编号可省略，会自动接下一个空位。建议每个基地 5×5 以上。

### 5. 设双方出生点与等待出生点

站到位置上执行（`setspawn` 用的是你的站位，不是选区）：

```
/taketori arena setspawn red
/taketori arena setspawn blue
/taketori arena setwait         # 中立等待出生点：匹配后玩家在此集结倒计时（未设置不能开局）
```

`setwait` 多一条规则：手上还留着选区时，它会把整片选区设成「等待区」（加入者在区域内
随机分布），而不是单点。想设单点就先 `/taketori arena clearselection` 清掉选区再执行；
两种情况都不会漏掉「等待出生点」这个必设项。

### 6. 收尾保存

```
/taketori arena setup done
```

先校验必设项（没齐会把「还缺什么」再列一遍，会话保留可继续补），再写回模板世界
（失败自动回滚）；**写回真正落盘之后**才对齐模板文件夹名、保存 `arenas.yml` 并回报
「已完成」，所以看到「已完成」就代表磁盘上已是新图。

中途放弃用 `/taketori arena setup cancel`，它会清掉会话，编辑世界仍加载着。
放弃路径不会自动对齐模板名：

- 想直接保存当前编辑成果：`/taketori moonmap unload <模板名>`，它写回 `moonmaps/<模板名>/`；
- 场地 id 与模板名不同名时别走 cancel：重新 `/taketori arena setup <场地id> <模板名>`
  接着改，最后仍用 `setup done` 收尾，改名才会发生。

用 `/taketori moonmap list` 确认模板显示就绪·开放（`arena list` 同样可见）。

**万一写回失败**：插件会把编辑现场转存到 `plugins/TsukikageKassen/moonmap-recover/<模板名>/`，
并提示你重新 `arena setup`，重进时会自动从那里恢复，不用重划。
若提示「编辑副本仍留在 `k_tpl_<模板名>`」（转存也失败），请**不要重载或重启**
（服务器会把根目录下的编辑副本当残留清掉）：先把它改名去掉 `k_tpl_` 前缀
（例如 `<模板名>_recover`），再手动整理进 `moonmaps/<模板名>/`。

### 7. 设大厅

站到大厅出生点执行，再用锄头划出大厅范围：

```
/taketori lobby setspawn
/taketori lobby pos1        # 站在大厅一角
/taketori lobby pos2        # 走到对角
/taketori lobby setregion
```

### 8. 摆告示牌

放好牌子，准星对着牌子执行（6 格内）：

```
/taketori lobby addsign join        # 快速加入（自动进入等待人数最多的房间）
/taketori lobby addsign create      # 降临月宫（创建房间）
/taketori lobby addstatus tsukuyomi    # 实时房间状态牌（每秒刷新，点击加入/旁观/创建）
/taketori lobby addsign rooms       # 房间列表（选房加入 / 旁观进行中的房间）
/taketori lobby addsign leave       # 退房回大厅 / 退出观战
/taketori lobby addsign spectate    # 旁观
/taketori lobby addsign character   # 选角色
/taketori lobby addsign menu        # 玩家菜单
/taketori lobby addsign ranks       # 排行榜
```

可绑动作：`join` / `leave` / `rooms` / `create` / `spectate` / `character` / `character:<角色id>` / `menu` / `ranks` / `lobby`。
实时状态牌（`addstatus <模板id>`）每秒刷新该模板当前房间的状态与人数，
点击直接加入 / 旁观（有缺口先补位）/ 无房时创建；牌子被破坏会自动从列表摘除。

### 9. 检查配置

```
/taketori arena list
/taketori lobby list
```

`arena list` 里每个启用场地要显示就绪·开放（未就绪会列出还缺什么：出生点 / 基地 / 刷新区 / 等待点），`lobby list` 要显示是否可用：是。

### 10. 开一局

玩家有三种进场方式：大厅点「加入对局」告示牌（或菜单匹配按钮）快速加入；点「降临月宫」创建新房间（异步复制模板世界，几秒后自动进入）；打开房间列表选房。快速加入的三级回退：等待人数最多的房间 → 缺人正在打的对局补位 → 用默认模板自动建房。

等待人数达到 `waiting.min-players`（默认 2）后房间自动开始倒计时（默认 90 秒；人数过半场（3v3 的第 4 人起）压缩到 30 秒；满员切 5 秒；有人退出人数不足则取消），归零后分队进出生点玻璃笼、解笼开战；结算后在线者自动回大厅并返还封存状态，房间世界随即删除。

对局中队友掉线：槽位释放可被补位；`room.understaffed-grace-seconds`（默认 60 秒）内无人补位则缺人队判负、本场提前结束。

掉线与「代理下切到别的服」在服务端是同一个退出事件，走同一套处理：背包封存进离线暂存，
`room.rejoin-seconds`（默认 300 秒）内回来自动回原房原队；超时后槽位释放给补位，
此后回来只有**原队伍还没满员**才能重新加入，满员会被拒绝（背包照常返还）。落后方也可用 `/taketori surrender` 发起投降表决（半数以上在线队友同意即结束）。

管理员也可以手动控制（可带房间 id 只作用于指定房间）：

```
/taketori match start [房间id]     # 对目标房间开局（双方各至少 1 人）
/taketori match force [房间id]     # 人数不够也开（只按该房等待区现有的人分队）
/taketori match stop [房间id]      # 结束指定房间（不影响其他并发房间；等待/倒计时阶段则直接解散房间，房内玩家回大厅）
/taketori match status             # 逐房间查看阶段 / 人数 / 比分 / 剩余时间
```

### 11. PVE 与战国（可选）

```
/taketori arena setoutpost          # 划 PVE 保卫据点（选区中心，或你站的位置）
/taketori arena setbomb             # 划 CS 式包点（站在那个方块上执行；delbomb 取消）
/taketori match mode pve [场地id]   # 把房间切到 PVE（仅等待中的房间可切，只改本房间）
/taketori pve difficulty hard       # 切难度（easy / normal / hard）
```

战国 3v3 的点位划分（天守阁 / 箭楼 / 铜钟 / 守卫 / 中地 / 跳跃台）用 `/taketori sengoku`，
详见[玩法说明](#33-四种对局模式)与[指令参考](#四指令参考)。

## 三、玩法说明

### 3.1 按键

每把武器只有**三个技能位**，外加一个不占键位的**被动**：

| 按键 | 作用 |
| --- | --- |
| 左键 | 近战武器：**原版普攻**（伤害由 `attributes.attack-damage` 决定），命中时结算被动；弓：主动技能「瞄准射击」 |
| 右键 | 一个主动技能（冷却 1~45 秒）；弓 / 三叉戟留空 = 原版拉弓 / 原版投掷 |
| 双击潜行 | 移动技能（加速 / 位移 / 跳跃），成功触发时附带 2 秒跳跃提升 V |
| 被动 | 不占按键：常驻属性（移速 / 抗性 / 跳跃）+ 命中触发（易伤 / 破甲 / 减速 / 吸血 / 附加伤害） |
| Q | 仅战国模式能量满时释放必杀；武器不再有模式切换 |
| 1 / 2 | 切换快捷栏武器（有两件武器的角色） |
| F | 交换副手（已被插件接管，不会真的换副手，也不会触发技能） |

第三槽固定为**双击潜行**（300 毫秒内两次开始潜行），没有其它触发方式可选：
`input.shift-right-trigger` 只为兼容旧配置保留，旧写法（`f` / `sneak-q` / `double-right` / `sneak-right` / `all`）
会按双击潜行处理并在启动时点名。

按了没反应时：先用 `/taketori keys` 看按键有没有传到服务端，再用 `/taketori sneak`（旧名 `/taketori f`）手动触发同一个技能。

### 3.2 角色与武器

| 角色 | id | 血量 | 移速 | 武器 |
| --- | --- | --- | --- | --- |
| 月读 | `tsukuyomi` | 20 | 0.10 | 陨星锤（钻石锹）、镇魂铃（紫水晶碎片） |
| 将军 | `shogun` | 24 | 0.10 | 金棒（下界合金斧）、大太刀（钻石剑） |
| 疾风 | `hayate` | 20 | 0.11 | 剑（下界合金剑）、钢丝（钓鱼竿）、苦无（铁锹） |
| 夕月 | `yuzuki` | 18 | 0.10 | 弓、短匕（铁剑） |
| 盾胁 | `tatewaki` | 26 | 0.095 | 大盾（盾牌）、壁垒令旗（白色旗帜） |
| 月巫 | `mikoto` | 22 | 0.10 | 月镜（玻璃板）、冰棘鱼（三叉戟） |
| 铁卫 | `kurogane` | 24 | 0.098 | 铁牙（骨头）、铁锁（拴绳）、碎岩牙（金锄） |
| 影丸 | `kage` | 20 | 0.105 | 影铳（弩）、影镜（望远镜） |
| 吉野 | `yoshino` | 19 | 0.11 | 吉野之苇（甘蔗）、云丝（线） |

- 在等待区选中角色即完成绑定；武器与保护 II 铁甲在开局时才发放，铁甲是直接穿上的并带
  不可破坏标记（`loadout` 段可配）。大厅与等待区不会拿到对局装备；对局进行中改角色（含管理员
  `/taketori character`）会立即换装。你原来的护甲连整背包在进房时被封存，结算原样返还。
- 夕月的弓没有箭也能射：背包里没有箭时右键即发，走与普通射击相同的强化 / 减益 / 三连射规则。
- 同一个队伍里不允许出现相同角色；不同队之间可以有相同角色。
- 一局结束后参赛者的角色会被清空（含掉线者），下一局重新选择；隐性标签不受影响。
- 每把武器的三个技能与被动分别是什么，用 `/taketori skills` 看手里的武器即可。

每把武器的参数键、数值取向与角色间的连携设计见[武器设计](docs/weapons.md)。

武器分组（`/taketori give <玩家> <武器id>` 里的 id）：

| 分组 | 武器 id |
| --- | --- |
| 月读 | `tsukuyomi_hammer`、`tsukuyomi_bell` |
| 将军 | `shogun_konbo`、`shogun_odachi` |
| 疾风 | `hayate_sword`、`hayate_wire`、`hayate_kunai` |
| 夕月 | `yuzuki_bow`、`yuzuki_dagger` |
| 盾胁 | `tatewaki_shield`、`tatewaki_banner` |
| 月巫 | `moon_mirror`、`frozen_swordfish` |
| 铁卫 | `kurogane_fang`、`kurogane_collar`、`kurogane_iron_fang` |
| 影丸 | `kage_gun`、`kage_lens` |
| 吉野 | `yoshino_reed`、`yoshino_thread` |

战斗层的公共机制（四种玩法通用）：

- 四种状态机制：易伤标记（被标者受到的所有伤害提高）、破甲（独立乘区、硬上限 30%）、
  兑现（带 `echo-bonus` 的技能打在带状态的目标上放大伤害，动作栏报「兑现！」）、
  场地与召唤（领域类周期结算；召唤物带归属，队友打不到、击杀不计分）；
- 组合技：每个角色各有 1 组连携（如月读「镇魂铃挂易伤 → 陨星锤兑现」），全部由四种机制自然涌现，不需要额外配置；
- 打击反馈：动作栏伤害数字汇总、冷却就绪提示音、连击可视化、重技能 title 演出；
- 头顶名牌：名字前显示所选角色（`[角色名]`），整条名牌按当前所属队伍染成红 / 蓝，
  远处一眼分清敌我；分队、换队、换角色与复活后自动刷新；
- 敌方目标罗盘：对局中在快捷栏**倒数第二格**发一枚指南针（第 9 格留给功能壳），
  指针与 Lore 每秒指向最近的敌方基地；拆包战优先指包点、战国指敌方天守阁；PVE 不发放；
- **载体护栏**：手持插件武器时不挖方块、不放置、不丢弃、不换副手；绑定武器不能入箱、不会被他人捡走。

### 3.3 四种对局模式

| 模式 | 切换 | 节奏 |
| --- | --- | --- |
| 3v3 积分赛（PVP） | 默认 | 红队先到 `match.score-to-win`（500）/ 蓝队先到 `match.defender-score-to-win`（600），或时限到时比分高者胜（**不含包点相位**） |
| PVE 月人入侵 | `/taketori match mode pve [房间id]` | 全员同一队守据点，撑过五大波次 |
| 拆包战（bomb） | `/taketori match mode bomb [房间id]` | 红蓝对抗 + CS 式包点：自由时段 → 拆弹窗口 → 抢基地；场地必须划包点 |
| 战国 3v3（三局两胜） | `/taketori match mode sengoku_3v3 [房间id]` | 每小局攻陷敌方天守阁者胜，先赢两小局赢整场 |

据点保卫、大波次与包点攻防的判定细节见[PVP / PVE 模式](docs/pvp-pve.md)。
包点现在是独立模式：场地划了包点也不会让纯 PVP 变味，要打拆包就切到 `bomb`。

3v3 积分赛

| 项目 | 设定 |
| --- | --- |
| 击杀月人 / 玩家 / 拆基地 | `+3` / `+10` / `+50` |
| 击杀回血 | 击杀敌方玩家回复 3 颗心（`combat.kill-heal`，满血不回） |
| 基地 | 双方各 3 个（数量可配），开局 60 秒保护期内不能占点 |
| 占点 | 站进对方基地区域持续 20 秒；区域内没敌人时进度按 `base.decay-per-second` 衰减 |
| 月人 | 每 9 秒一批（铁甲僵尸 / 骷髅按权重随机），场上最多 15 个；多刷新区之间按区轮转均等分布 |
| 精英月人 | 每 5 波一批（钻甲 + 药水 buff），只在 mixed 标签刷新区出现 |
| 复活 | 死亡 5 秒后回己方出生点，死亡不掉落物品 |
| 基地标记 | 双方基地用队伍颜色粒子持续标出（12 格光柱 + 四角立柱 + 顶部十字，远处看柱、近处看框），被拆后标记立即消失 |

得分不对称：红队（进攻方）目标分 `match.score-to-win`（默认 500），其得分按
`match.attacker-score-multiplier`（默认 0.6667）折算；蓝队（防守方）目标分
`match.defender-score-to-win`（默认 600）。时限到时仍未分胜负则发起加时投票，
未通过判防守方（蓝队）守住。

PVE 月人入侵：所有人同一队、不占点、玩家之间默认无伤害。

- 保卫据点：开局放出无敌雪傀儡，月人靠近就按「在场月人数 × 每秒伤害」扣耐久，归零对局结束（可配成只播报）；
- 五大波次：每约 60 秒一波精英潮，默认 5 波、每波 8 名精英；
- 三档难度（`/taketori pve difficulty <easy|normal|hard>`）：hard 档据点耐久 460 / 每月人每秒 11 伤 / 每波 10 精英；normal 默认；
- 精英随人数变强：每多 1 名参战玩家，精英药水等级整体 +1，叠加难度档后封顶 6 级。

战国 3v3（三局两胜）：独立模式，与 PVP / PVE 互不影响。

- 赛制：每小局先攻陷敌方天守阁者胜，先赢两小局者赢整场（赛制与局时长可配）；
  超时按箭楼占领数判定，持平则本小局重开，「龟缩到时间结束」不是有效战术；
- **天守阁**：永久不可直接破坏（拦截破坏与爆炸），唯一胜利通道是击破器；
- 箭楼（上下路各一）：清掉牛鬼（尸壳）与虾兵蟹将（卫道士）→ 敲钟 → 站进占领区读条；
  双方同时读条时互锁（两张进度都停滞），可反复易手，每次易手后守卫重刷；
- 大将击破器：占领箭楼后在敌方门前生成；拾取携带 → 进敌方天守阁范围自动读条 →
  读满本局获胜。不可破坏 / 不可丢弃 / 不可被敌方捡走 / 不可入箱，被消耗后再占领会自动补齐；
- 跳跃台：占领箭楼后在己方门前激活，站上去直达最近的己方已占领箭楼，阵亡复活后也能用；
- 中地小兵：场地中央持续生成、允许被绕过；击杀攒能量，能量满按 Q 释放必杀技。

场地点位用 `/taketori sengoku setkeep / settower / setbell …` 划定，`/taketori sengoku check`
检查是否齐全；赛制、点位与五份配置的完整说明见[战国 3v3](docs/sengoku-3v3.md)，
配置项汇总见[配置项](#五配置项)。

### 3.4 队伍商店

对局中两队各有自己的商人 NPC 与 27 格商店界面，花的是局内个人积分（击杀 / 占点攒的），
买完直接进背包：

| 商品 | 队伍 | 价格 | 上限 | 效果 |
| --- | --- | --- | --- | --- |
| 拆弹钳 | 红队 | `shop.defuse-clipper.cost`（30） | 每人 2 把 | 背包里带着它拆包，读条从 10 秒缩到 6 秒；死亡即清空 |
| 地雷 | 蓝队 | `shop.land-mine.cost`（40） | 每队 2 枚 | 埋成石质压力板，踩到即死，不破坏方块 |

- 打开：右键商人 NPC，或 `/taketori shop`（等价 `/taketori buy`），界面按你的队伍摆放商品；
- 快捷购买：`/taketori shop clipper`（别名 `剪刀` / `拆弹钳`）、`/taketori shop mine`（别名 `地雷`）；
- 商人默认是村民：无敌、无 AI、静音、不推动、不受伤，每 10 秒自愈检查一次（被杀或区块重载后自动补回）；
- 大厅商人的位置：`shop.npc.<red|blue>.world` 留空 = 大厅出生点所在世界，坐标全 0 = 大厅出生点坐标；管理员站到位置上执行 `/taketori shop setnpc <red|blue>` 即可把该队商人挪过来（坐标写回 config.yml）；
- 对局房间内也有商人：2.4.1 起每局开局按红 / 蓝出生点各生成一名（红队 z+2、蓝队 z-2），对局结束或房间回收时移除；它们固定用场地出生点，不读 `shop.npc` 坐标，和大厅商人互不影响；
- 不在进行中的对局里也能打开界面，但买不了（会提示「你不在进行中的对局中」）。

### 3.5 拆包战（bomb）与加时赛

包点是**独立模式**（`match.mode: bomb` 或 `/taketori match mode bomb`），纯 PVP 不再接管它。
一局分三个阶段：

| 阶段 | 时长（默认） | 发生什么 |
| --- | --- | --- |
| 自由时段 | `bomb.activation-delay-seconds`（40 秒） | 双方在出生点准备，包点还是原方块 |
| 拆弹窗口 | `bomb.defuse-time-limit-seconds`（110 秒） | 包点变 TNT；红队站进 4.5 格内左键读条，右键取消 |
| 抢基地 | 到分出胜负 | 超时后蓝队守住，基地开放，继续按占点 / 互杀 / 清月人累计 |

- 空手 10 秒（`defuse-seconds-bare`），背包里带着拆弹钳 6 秒（`defuse-seconds-clipper`）；
- 离开包点、死亡、掉线、换队都会中断，`bomb.reset-seconds`（7 秒）内没人接手则进度归零（只提醒红队）；
- 拆开 → 红队直接获胜；110 秒没拆完 → 蓝队 +200（`blue-keep-score`）、红队 +50（`red-consolation-score`）；
- 拆包战要求场地先划包点（`/taketori arena setbomb`），没划会在开局时被拦下；PVP / PVE / 战国三个模式一律不进入这套相位，基地按 `base.capture-delay-seconds` 开放；
- 记分板显示 `包点: 未激活 / 拆除中 Ns / 蓝队守住`。

时间到双方都没到目标分时进入加时赛：

- 队伍比分清零（个人分与击杀保留），30 秒内投票：`/taketori overtime`（等价 `/taketori match overtime`）；
- 任一队全员同意即开打；某队已无人时，一人投票即通过；窗口到期没通过则按「防守方守住」结算（蓝队胜）；
- 加时 `overtime.duration-seconds`（60 秒）纯 PVP，每击杀一名敌人记 `overtime.kill-score`（默认 1）个人头，
  记分板显示「加时人头: 红 N : 蓝 M」，结束人头多者胜，相同则平局；
- 关掉 `overtime.enabled` 后时间到直接结算，不再投票。

### 3.6 房间与匹配（月宫制）

- 动态房间：房间由玩家按需创建，异步复制模板世界为专属世界，结算后自动删除回收；
  服务器最多同时 `room.max-rooms`（默认 8）个房间，每人限建 1 个；
- 等待与倒计时：进房即封存自带状态（背包 / 血量 / 药水 / 游戏模式），等待区倒计时三档；
- 观战：聊天栏给出可点击的「退出观战」按钮并定期重发；阵亡自动旁观，
  屏幕上方 BossBar 显示复活倒计时，结束回己方出生点复活；
- 跨局战绩：总积分 / 胜场 / 击杀 / 拆家等按 UUID 写入 `data/stats.yml`，`/taketori ranks` 图形查看；
- 基岩版（Floodgate）：不需要额外配置。Floodgate 的 UUID 由 XUID 派生、跨重启稳定，重连会话 / 队伍 / 封存背包 / 战绩全部照常；只有显示名默认去掉 `username-prefix`（`floodgate` 段可改），没装 Floodgate 时这段静默不生效；
- 菜单时钟：进服发放的道具，右键打开玩家菜单（匹配 / 队伍 / 角色 / 排行榜），丢不掉。

### 3.7 多世界兼容

给 Multiverse 之类的多世界服务器用，两项都默认收窄、可一键恢复旧行为：

- 进服送大厅只接管白名单世界（默认仅大厅出生点所在的世界，其它世界位置不变）；
- 消息播报只发给消息所属世界（对局播报 → 对局世界，大厅播报 → 大厅世界）。

作用域判定、跨世界房间的资源回收与常见排错见[多世界](docs/multi-world.md)。

## 四、指令参考

所有指令统一使用全名 `/taketori`，没有缩写别名。

### 玩家指令（`taketori.play`）

```
/taketori menu                     打开玩家菜单（匹配 / 队伍 / 角色 / 排行榜）
/taketori play                     同 menu（快捷入口）
/taketori ranks [榜单]             总计排行榜 GUI
/taketori stats [数量]             聊天栏版排行榜（跨局累计）
/taketori character <角色id|none>  选择 / 取消自己的角色
/taketori skills                   查看手里武器三个技能槽 + 被动的技能与冷却
/taketori mode                     兼容旧命令：2.0 起已移除武器模式切换（Q 槽下线），仅回提示
/taketori f                        手动触发第三槽（sneak）技能（同 /taketori sneak）
/taketori keys                     按键诊断：回放最近收到的原始输入事件
/taketori lobby join|leave|spectate 快速加入房间 / 退房回大厅 / 旁观
/taketori leave                    退出观战、离开等待房间（对局中参赛者不能中途退出）
/taketori room create [模板id] [pvp|pve|bomb|sengoku_3v3]  创建房间（可指定模式）
/taketori room delete|list         删除自己的等待房 / 房间列表
/taketori surrender                发起/确认本队投降（对局中）
/taketori shop [clipper|mine]      打开队伍商店 / 快捷购买拆弹钳（红队）/ 地雷（蓝队）
/taketori buy                      同 shop（打开商店界面）
/taketori overtime                 同意加时赛投票（等价 /taketori match overtime）
/taketori doctor                   自检：识别链路、名字解析、配置校验、多世界范围
```

### 管理员指令（`taketori.admin`）

战国 3v3（独立模式；先 `arena setup` 选中场地，区域类用 `pos1`/`pos2` 选区，点类站在位置上）

```
/taketori sengoku setkeep <red|blue>       天守阁区域（不可被直接破坏，只能被击破器攻陷）
/taketori sengoku setkeepdoor <red|blue>   天守阁门前点：击破器与跳跃台的生成位置
/taketori sengoku settower <序号>          箭楼占领区（上下路各一）
/taketori sengoku setbell <序号>           箭楼铜钟位置（右键它触发占领读条）
/taketori sengoku setguard <序号>          箭楼守卫刷新点（牛鬼与虾兵蟹将）
/taketori sengoku setmid [序号]            中地小兵刷新区
/taketori sengoku setjumppad <red|blue>    跳跃台位置
/taketori sengoku check                    检查本场地的战国点位是否齐全
/taketori sengoku delkeep <red|blue>       清除天守阁区域
/taketori sengoku delkeepdoor <red|blue>   清除天守阁门前点
/taketori sengoku deltower <序号>          清除整座箭楼（占领区 + 铜钟 + 守卫点）
/taketori sengoku delbell <序号>           只清铜钟
/taketori sengoku delguard <序号>          只清守卫刷新点
/taketori sengoku delmid <序号>            清除中地小兵刷新区
/taketori sengoku deljumppad <red|blue>    清除跳跃台
/taketori sengoku delall confirm           清空本场地的全部战国点位
/taketori sengoku start                    强制开局
/taketori sengoku pause | resume           暂停 / 继续小局计时（暂停不吃局内时间）
/taketori sengoku endround [red|blue]      强制结束本小局（不指定则判平局重开）
/taketori sengoku towers                   查看箭楼归属、守卫数、双方读条进度
/taketori sengoku breaker [red|blue]       查看击破器位置与自己当前的读条进度
/taketori sengoku jumppad [red|blue]       查看跳跃台状态与位置
/taketori sengoku score                    查看比分、剩余时间、各人能量
/taketori sengoku mode [pvp|pve|bomb|sengoku_3v3]  查看 / 切换全局默认模式（写回 config.yml）
/taketori sengoku menu                     打开图形化面板（上面这些的按钮版）
```

切模式有两种粒度：

```
/taketori sengoku mode sengoku_3v3     全局默认（写回 config.yml，只影响之后新建的房间）
/taketori match mode sengoku_3v3 [房间id]  只切一个房间（等待中才能切，不影响别人）
```

图形化面板：`/taketori admin` → 「战国 3v3」，或直接 `/taketori sengoku menu`。

场地（多场地：`set*` / `del*` 作用于当前选中的场地，先用 `create` / `select` 选中）

```
/taketori arena setup <id> [模板名]              划场地一条龙：建/选场地 + 载入编辑世界 + 列出剩余清单
/taketori arena setup done                       校验就绪 → 写回模板世界 → 保存 arenas.yml
/taketori arena setup cancel                     放弃会话（编辑世界保留，可 moonmap unload 手动保存）
/taketori arena create <id>                      新建场地并自动选中
/taketori arena select <id>                      切换当前操作的场地
/taketori arena enable | disable <id>            开放 / 关闭场地（关闭后匹配不再选中）
/taketori arena delete <id>                      删除场地（房间运行中会被拦截）
/taketori arena wand                             领选区锄（左键 = 角点 1，右键 = 角点 2，潜行+左键 = 清空）
/taketori arena pos1 | pos2                      用当前位置设置选区角点
/taketori arena setminion [编号] [normal|mixed]  选区设为月人刷新区（normal 只刷普通，mixed 普通+精英）
/taketori arena setbase <red|blue> [编号]        选区设为某队基地（编号可省略）
/taketori arena setspawn <red|blue>              当前位置设为某队出生点
/taketori arena setwait                          当前位置设为中立等待出生点（未设置不能开局）
/taketori arena setloot [编号]                   选区设为道具刷新点
/taketori arena lootwand                         领道具点工具
/taketori arena setoutpost                       选区中心（或当前位置）设为 PVE 保卫据点
/taketori arena setbomb                          当前位置设为 CS 式包点（delbomb 取消）
/taketori arena delbase <red|blue> <编号>        删除某个基地
/taketori arena delminion <编号>                 删除某个月人刷新区
/taketori arena delloot <编号>                   删除某个道具刷新点
/taketori arena deloutpost                       清除据点位置
/taketori arena clearselection                   清空你的选区
/taketori arena list                             查看全部场地的启用与就绪情况
```

月面模板（`moonmaps/`）

```
/taketori moonmap list                            列出模板与 arenas.yml 定义状态
/taketori moonmap create <模板名>                 从零生成一张平坦模板世界并注册为场地
/taketori moonmap import <世界名> <场地id>        把服务器已有世界导入为模板（不必手动搬文件夹）
/taketori moonmap load <模板名>                   复制模板为编辑世界 k_tpl_<名> 并加载
/taketori moonmap unload <模板名>                 保存编辑世界写回 moonmaps 并卸载
```

大厅

```
/taketori lobby setspawn                         当前位置设为大厅出生点
/taketori lobby pos1 | pos2 | setregion          划大厅范围（用于大厅保护与识别）
/taketori lobby addsign <动作>                   准星对准告示牌绑定动作
/taketori lobby removesign                       移除准星所指告示牌的绑定
/taketori lobby list                             查看大厅配置与告示牌
```

对局（多房间：不写房间 id 时取「你所在房间 → default → 第一个房间」）

```
/taketori match start [房间id]                   对指定房间开局（双方各至少 1 人）
/taketori match force [房间id]                   人数不够也开（只按该房等待区的人分队）
/taketori match stop [房间id] [原因]             结束指定房间（不影响其他并发房间）；等待/倒计时阶段则直接解散房间，房内玩家回大厅
/taketori match status                           逐房间列出阶段 / 比分 / 剩余 / 基地 / 场上月人
/taketori match mode <pvp|pve|bomb|sengoku_3v3> [房间id]  切换指定房间模式（仅等待中可切，不写全局配置）
/taketori pve                                    查看 PVE 设置（难度 / 波次 / 据点耐久）
/taketori pve difficulty <easy|normal|hard>      切换 PVE 难度（下一局生效）
/taketori team <玩家> <red|blue|none>            手动分队（作用于目标玩家所在房间）
/taketori character <玩家> <角色id|none>         指定角色（同队不能重复）
/taketori match overtime                          同意所在房间的加时赛投票（30 秒窗口内）
/taketori shop setnpc <red|blue>                 把该队商人 NPC 挪到当前位置（写回 config.yml）
```

维护

```
/taketori editor [武器id]                        武器数据编辑 GUI
/taketori give <玩家> <武器id>                   发放武器
/taketori debug [on|off]                         调试日志开关
/taketori migrate uuid <旧UUID|旧名字> <新UUID> [confirm]  UUID 迁移：搬角色/标签/战绩，不带 confirm 只预演
/taketori qmode <drop|held-slot|none>            运行时切换 Q 键行为
/taketori reload                                 热重载全部配置
/taketori admin                                  管理员菜单（含删除区域等按钮）
```

管理工具一览：选区锄（默认下界合金锄，粒子描边、重叠提示）、道具点工具（默认结构空位）、
武器数据编辑 GUI（三层菜单、写回保留注释）、管理员菜单、角色分配（同队不重复，隐性标签权重裁决）、
`/taketori doctor` 自检与 `/taketori keys` 按键回放。

## 五、配置项

改完 `config.yml` 执行 `/taketori reload` 生效（模板世界文件夹在 `moonmaps/<模板名>/`，与场地定义 `arenas.yml`、大厅 `lobby.yml` 一样不受 reload 影响）。

| 段 | 关键项 | 默认 |
| --- | --- | --- |
| `match` | `mode` / `score-to-win` / `defender-score-to-win` / `attacker-score-multiplier` / `time-limit-minutes` / `team-size` / `respawn-delay-seconds` / `keep-inventory` | pvp / 500 / 600 / 0.6667 / 20 / 3 / 5 / true |
| `waiting` | `min-players` / `countdown-seconds` / `half-countdown-seconds` / `full-countdown-seconds` / `cage-hold-seconds` / `end-delay-seconds` / `cage-material` / `pve-full-players` / `void-y-offset` / `protect` | 2 / 90 / 30 / 5 / 3 / 5 / GLASS / 0（取 team-size）/ -10 / true |
| `room` | `max-rooms` / `max-rooms-per-player` / `world-prefix` / `default-template` / `empty-dispose-seconds` / `understaffed-grace-seconds` / `rejoin-seconds` | 8 / 1 / kassen_ / tsukuyomi / 60 / 60 / 300 |
| `room-shell` | `enabled` / `material` | true / NAUTILUS_SHELL |
| `party` | `max-size` | 3 |
| `scoring` | `minion-kill` / `player-kill` / `base-capture` | 3 / 10 / 50 |
| `combat` | `kill-heal` / `minion-kill-heal` / `melee-charge-gate` / `friendly-fire-protection` / `third-slot-buff` / `spawn-buff` / `match-saturation` | 6.0 / 0.0 / 0.9 / auto / 2 秒跳跃提升 V / 10 秒抗性 II + 速度 I + 回血 I（出生保护） / true（对局内自动饱和） |
| `loadout` | `armor-enabled` / `armor-material` / `armor-protection` | true / IRON / 2（保护 II） |
| `minion` | `health` / `iron-armor` / `interval-seconds` / `per-spawn` / `max-alive` / `types` / `elite` / `normal.*` / `mixed.*` | 40 / true / 9 / 3 / 15 / 僵尸骷髅权重 / 每 5 波 / normal 与 mixed 两类刷新区各自的独立循环（enabled + interval-seconds，未配置回落全局间隔） |
| `pve` | `difficulty` / `big-waves` / `elite-scaling` / `outpost` | normal / 5 波·8 精英·60 秒 / 每多 1 人 +1 级 / 三档数值 |
| `base` | `count-per-team` / `capture-seconds` / `capture-delay-seconds` / `decay-per-second` / `multi-player-bonus` | 3（或 auto）/ 20 / 60 / 0.5 / false |
| `lobby` | `auto-start-players` / `teleport-on-join` / `takeover-worlds` / `protect` / `return-after-match` / `return-to-entry` | 6 / true / `[]`（仅大厅世界）/ true / true / true |
| `bomb` | `activation-delay-seconds` / `defuse-time-limit-seconds` / `defuse-seconds-bare` / `defuse-seconds-clipper` / `reset-seconds` / `blue-keep-score` / `red-consolation-score` | 40 / 110 / 10 / 6 / 7 / 200 / 50（没有包点的场地不生效） |
| `shop` | `npc.enabled` / `npc.red.*` / `npc.blue.*` / `defuse-clipper.cost` / `defuse-clipper.max-per-player` / `land-mine.cost` / `land-mine.max-per-team` / `land-mine.kill-radius` / `land-mine.trigger-radius` | true / 商人名与坐标 / 30 / 2 / 40 / 2 / 5 / 3 |
| `overtime` | `enabled` / `duration-seconds` / `kill-score` | true / 60 / 1 |
| `worlds` | `broadcast-scope` | world（只发给消息所属世界；`all` = 全服） |
| `menu-clock` | `enabled` / `material` / `give-on-join` / `name` / `lore` | true / CLOCK / true / … |
| `input` | `q-mode` / `weapon-slots` / `shift-right-trigger` | drop / [0,1,2,3] / double-sneak |
| `items` | `soulbound` / `auto-give-on-join` / `allow-drop` | true / false / false |
| `feedback` | `actionbar` / `particles` / `sounds` / `cooldown-display` | true / true / true / bossbar |
| `setup-wand` | `enabled` / `material` / `loot-material` / `outline-particles` / `give-on-join` | true / NETHERITE_HOE / STRUCTURE_VOID / true / false |
| `loot` | `enabled` / `interval-seconds` / `max-drops` / `despawn-seconds` / `items` | true / 30 / 6 / 180 / 道具池 |
| `tags` | `default` / `vip` / `staff` 的权重 | 0 / 10 / 100 |
| `floodgate` | `username-prefix` / `strip-prefix` | `.` / true（装了 Floodgate 时去掉基岩名前缀；不装不生效） |

### 数据存储（yaml / sqlite / mysql）

玩家档案（角色绑定 + 隐性标签）与跨局战绩走同一套接口 `DataStore`，后端在 `config.yml` 的 `storage` 段切换：

| 后端 | 落地位置 | 适用场景 |
| --- | --- | --- |
| `yaml`（默认） | `data/players.yml` + `data/stats.yml` | 单机服，零依赖 |
| `sqlite` | `data/kassen.db`（单文件） | 单机服，想要更好的读写与外部查询 |
| `mysql` | MySQL / MariaDB 库，表 `{table-prefix}players` / `{table-prefix}stats` | 群组服、多服共库 |

```yaml
storage:
  type: mysql          # yaml | sqlite | mysql
  table-prefix: kassen_
  sqlite:
    file: data/kassen.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: tsukikage
    username: root
    password: ''
    use-ssl: false
    timeout-seconds: 5
```

- **驱动按需加载**：不写进 `plugin.yml` 的 `libraries`（那样下载失败会连带整个插件加载失败）。
  Paper 26.1 自带 `sqlite-jdbc` 与 `mysql-connector-j`，通常开箱即用；如果用的是精简构建或换平台，
  把 `sqlite-jdbc-*.jar` / `mysql-connector-j-*.jar` 放进 `plugins/TsukikageKassen/drivers/` 再重启，
  插件会从那里加载（`DriverShim` 方式注册给 `DriverManager`）。用 YAML 则完全不需要驱动。
- **失败自动回退**：驱动缺失、库连不上、账号没权限时，控制台记一条 SEVERE 并回退 YAML，插件照常运行；
  启动日志会打印「数据后端：…」，一眼能看出实际用的是哪个。
- **切换只在启用时生效**：`/taketori reload` 不会热切数据库，换后端请重启服务器。
- **UUID 迁移**：离线服切正版 / 现代转发后，用
  `/taketori migrate uuid <旧UUID|旧名字> <新UUID> confirm` 把角色、标签与战绩搬到新键；
  旧键写玩家名时按离线 UUID 规则计算，不带 `confirm` 只盘点不写入。
  旧行搬完即删，重复执行不会把分数翻倍；每次执行追加一行到 `data/migrations.log`。
- 两套 SQL 表结构一致：`players(uuid, character_id, tag_id)`、`stats(uuid, stat_key, stat_value)`；
  统计键名沿用 `stats.yml` 的字段名（`total-score` / `kills` / …），所以 YAML 与 SQL 之间可以手工搬迁。
- 两张表都以玩家 **UUID** 为键，昵称只用于排行榜显示：改名不会把战绩裂成两条，重名也不会并成一条。
  老版本按玩家名存的战绩（`stats(player_name, …)`）在启动时自动改名为 `<前缀>stats_legacy`，
  并按本地 usercache 尽力迁到 UUID；查不到 UUID 的行留在 legacy 表里，不会丢。

### 数值文件（插件不会覆盖）

- `weapons.yml`：全部武器数值与技能参数。`attack-damage` / `attack-speed` 写的是最终值；`modes.<MODE>.skills` 会整段替换武器级同名槽位；写错的参数键会在启动时被配置校验点名。插件升级后如果控制台提示「你的 weapons.yml 是模板 v…」，说明手上这份是旧模板：备份后删掉它，`/taketori reload` 会重新生成当前模板。`config-version` 由插件写入，不用手改。参数键与数值取向见[武器设计](docs/weapons.md)。
- `characters.yml`：角色血量、移速、武器列表与描述。
- 战国 3v3 的五份配置（改完 `/taketori reload` 即生效）：
  `sengoku.yml`（赛制 / 局时长 / 超时判定 / 天守阁）、`sengoku-towers.yml`（箭楼与守卫）、
  `sengoku-siege.yml`（击破器与跳跃台）、`sengoku-minions.yml`（中地小兵）、
  `sengoku-energy.yml`（能量与必杀）。点位写在 `arenas.yml` 的 `arenas.<id>.sengoku` 段，
  由 `/taketori sengoku setkeep …` 等命令写入。
- `messages.yml`：所有提示文案（新键会自动回填默认值）。
- 战斗数值全部外置、**没有硬编码**。（唯一例外是场地技能的参数在**施法瞬间快照**，热重载从下一次施法开始生效。）

---

# 给开发者

## 六、架构总览

插件分成两个严格分层：`core/` 是纯 Java（不 import 任何 Bukkit 类，构建护栏强制检查），
承载全部规则与数值判定；`paper/` 是 Bukkit 适配层，把 core 的规则接到事件、实体与调度器上。
这样设计的目的：规则可以被离线断言覆盖（见[构建与测试](#七构建与测试)），且将来换服务端平台时规则层可整体复用。
各条链路的调用顺序与状态迁移（进场、开局、结算、重连、投降等）见[方法流程](METHOD-FLOWS.md)。

```
请求（按键 / 伤害 / 指令）
   │ paper 层：监听器、命令、GUI、调度
   ▼
core 层：SkillDefinition / MatchRules / SengokuRules / TowerContest …（纯规则，无 Bukkit）
   │
   ▼
paper 层执行：技能实现、实体、粒子、记分板、世界管理
```

关键子系统与入口：

| 子系统 | 位置 | 职责 |
| --- | --- | --- |
| 技能战斗层 | `paper/skill/impl/` | 一个实现类对应 `weapons.yml` 的一个 `type`，`SkillTargets` 统一选目标（友伤保护 / 范围 / 视线锥 / 前置状态） |
| 房间生命周期 | `paper/match/room/`（`GameRoom` / `RoomManager`） | `Phase = WAITING → STARTING → CAGED → PLAYING → ENDING` 的状态机，模板世界复制 / 回收，每秒 `tickAll()` 驱动 |
| 战国 3v3 | `core/match/sengoku/` + `paper/match/sengoku/` | core 放赛制规整、超时判定、箭楼互锁等纯规则；paper 放 `SengokuSession` 编排与 `TowerManager` / `SiegeBreakerManager` 等管理器。`GameRoom.Phase` 语义不变，多局制完全由 `SengokuSession` 承担，对 PVP / PVE 零影响 |
| 版本适配 | `version/`（`VersionAdapter`） | 属性 / 粒子 / 音效 / 药水名字的注册表解析，高版本改名不生效只告警不崩服 |
| 配置 | `config/` | 加载、校验（写错的参数键启动即点名）、迁移（`config-version`） |
| 持久化 | `data/` | YAML 存档：角色绑定、隐性标签、跨局战绩 |

几处值得知道的取舍：

- 箭楼争夺的互锁规则、击破器护栏判定都抽在 core 层，就是为了能用离线断言穷举组合；
- 天守阁保护不维护「本局是否已攻陷」状态：读条完成立即结束小局，`isRunning` 自然变 false；
- 跳跃台默认 TP 而非弹射：不受地形影响、必然抵达。

## 七、构建与测试

### Gradle（有网络时）

```bash
./gradlew build          # Windows: gradlew.bat build；产物在 build/libs/
```

`build.gradle.kts` 内置三道护栏，任一违反即构建失败：`checkCorePurity`（`core/` 层出现
Bukkit / NMS 引用）、`checkParamKeys`（技能读取的参数键必须在校验表里登记）、
`offlineCheck`（`tools/` 下全部离线断言）。

推送到 `main` 后 CI 跑的就是同一条 `./gradlew build`，不再额外手写编译命令。产物 jar 不进仓库：
构建结果上传成 Actions artifact（保留 14 天），正式发布的包由 Release 工作流挂到 GitHub Release
资产上，需要成品直接去 Release 下载。工作流里的 action 按 commit SHA 固定，由 dependabot 每周提 PR 更新。
每个版本的变更正文与产物校验值见[发版说明](docs/releases/)。

### 起服冒烟（可选）

不想等 CI、想在本机确认「插件真的能加载」时，用同一套脚本起一个真实服务器：

```bash
./gradlew build
bash tools/smoke-test.sh
```

脚本会下载 Paper 26.1.2（build 74，带 sha256 校验）、用平坦世界起服、装入 `build/libs` 里的 jar，
等 `Done (` 与插件启用日志，再用 mineflayer 假人连进去执行 `/taketori doctor` 断言有响应；
跑完自动关服。CNB 上对应 `.cnb.yml` 的 `web_trigger` 流水线（页面手动触发），
构建环境见 `image/Dockerfile`（JDK 25 + Node）。

想连技能一起验，用同一套环境跑全武器回归：

```bash
bash tools/skill-smoke.sh
```

假人（离线 UUID 预置 OP）会逐把领取 20 把武器，对 NoAI 木桩僵尸做四件事：
右键 → 检查 `right` 是否进入冷却（弓 / 三叉戟改为检查「保持原版」）、双击潜行 → 检查 `sneak` 冷却、
左键普攻 12 下 → 检查木桩掉血与减速是否挂上（易伤 / 破甲 / 吸血看伤害与日志）、
三叉戟额外验证投掷后忠诚回手。共 61 项断言，跑完自动关服。

### 发版

1. 改 `gradle.properties` 的 `version=`，同时写一份 `docs/releases/<版本>.md`，Release 正文取自这里。
2. 提交推送后打 tag：`git tag v<版本> && git push origin v<版本>`。
3. Release 工作流会先核对 tag 与 `version=` 是否一致、重跑三道护栏，再把
   `TsukikageKassen-<版本>.jar` 挂到 Release 上。对不上就直接失败，避免发出名不副实的包。

### 离线脚本（无网络、无 Gradle 时）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1 -LibsDirs "<依赖 jar 目录>"
```

只需要 JDK 25 与一份 paper-api 26.1.2 jar（再加 adventure 系列的 api / key / minimessage）。
脚本会跑同样的护栏、编译全部源文件、打包到 `build/dist/TsukikageKassen-<版本>.jar`
（与 Gradle 的 `build/libs/TsukikageKassen-<版本>.jar` 同名同内容）；
版本号唯一来源是 `gradle.properties` 的 `version=`（`plugin.yml` 占位符、jar 文件名、启动日志都由它派生）。

### 离线断言（无需启动服务器）

`tools/` 下每个带 `main` 的类都是一份独立断言，清单登记在 `build.gradle.kts` 的 `offlineTools` 里，
`./gradlew build` 会逐个执行（也可单跑 `./gradlew offlineCheck`）：

| 断言 | 覆盖 |
| --- | --- |
| `SengokuRoundTest` | 赛制规整 / 超时判定 / 平局语义 / 模式解析 / 能量规则 |
| `TowerContestTest` | 箭楼互锁规则的各种组合 |
| `WorldScopeTest` | 多世界作用域的接管与消息判定 |
| `BaseArgParserTest` | `/match` 参数解析的容错 |
| `MinionRegionFilterTest` | normal / mixed 两类刷新区不串区 |
| `LegacyConfigFallbackTest` | 老配置缺键时只补默认值、不删自定义段 |
| `WeaponYamlEditorTest` | `weapons.yml` 编辑器的路径解析与注释保留 |
| `WeaponYamlCheck` | 核对 weapons.yml / characters.yml 的材质、粒子、音效、药水、弹体、属性、技能类型与参数键 |
| `MiniMessageClickTest` | 可点击文案（`click` / `hover`）的渲染 |
| `BombShopRulesTest` | 炸弹相位参数、商店购买规则（队伍 / 积分 / 上限）与加时赛判定 |
| `CooldownManagerTest` | 冷却键结构、到期判定与过期清理（注入假时钟，不 sleep） |
| `PassiveRulesTest` | 武器被动参数的解析与越界钳制 |

新增一个带 `main` 的测试类后，务必在 `offlineTools` 里补一行，漏登记不会报错，
只会静默不执行（`compileOfflineTools` 仍会把它编译通过）。

### 面向开发的扩展点

- 加一把武器 / 一个技能：在 `weapons.yml` 里写数值，技能槽引用某个 `type` 并填参数键
  （全部键在启动校验表里，写错会点名）；不需要写 Java。要新技能类型才需要新增 `paper/skill/impl/` 实现类。
- 加一个角色：`characters.yml` 加段即可（血量 / 移速 / 武器列表），同队不重复的约束自动生效。
- 加一种对局模式：参照战国 3v3 的分层，纯规则进 `core/match/`（可离线断言），
  编排与管理器进 `paper/match/`，挂到 `GameRoom` 上但不改 `Phase` 语义。
- 适配新版服务端：`version/DefaultVersionAdapter` 的注册表里补新名字即可，不改调用点。

## 八、目录结构

```
src/main/java/com/taketori/kassen/
├─ core/        纯 Java 逻辑（角色 / 武器 / 技能定义、对局规则、战国规则、世界范围判定），禁止引用 Bukkit
├─ paper/       Bukkit 适配（监听器、技能实现、GUI、命令、房间与大厅管理、战国管理器）
│  ├─ skill/impl/   技能实现，一个类对应 weapons.yml 里的一个 type
│  ├─ match/room/   GameRoom 状态机与 RoomManager（世界复制 / 回收）
│  ├─ match/sengoku/  战国 3v3 编排（SengokuSession、箭楼 / 击破器 / 跳跃台 / 小兵 / 能量）
│  └─ command/      TaketoriCommand / MatchCommand / AdminMenu / SengokuCommand
├─ version/     版本差异收口（属性 / 粒子 / 音效 / 药水的注册表解析）
├─ config/      配置加载、校验与迁移
└─ data/        持久化（YAML 存档：角色绑定、隐性标签、跨局战绩）
src/main/resources/
├─ plugin.yml / config.yml / weapons.yml / characters.yml / messages.yml
└─ sengoku.yml / sengoku-towers.yml / sengoku-siege.yml / sengoku-minions.yml / sengoku-energy.yml
tools/          离线测试与校验器（无需启动服务器），清单见 build.gradle.kts 的 offlineTools
docs/           细分文档（区域配置 / 模式判定 / 商店与炸弹 / 战国 3v3 / 武器设计 / 多世界），releases/ 存发版说明
METHOD-FLOWS.md 逐条对局的调用链与状态迁移
build-offline.ps1  无网络环境的构建脚本
```

上面树里最后三行是文档与脚本入口：`docs/` 下是 [区域配置](docs/regions.md)、
[PVP / PVE 模式](docs/pvp-pve.md)、[队伍商店与炸弹](docs/shop-and-bomb.md)、
[战国 3v3](docs/sengoku-3v3.md)、
[武器设计](docs/weapons.md)、[多世界](docs/multi-world.md)，发版说明在 [docs/releases/](docs/releases/)；
[METHOD-FLOWS.md](METHOD-FLOWS.md) 逐条记对局的调用链与状态迁移，`build-offline.ps1` 是离线构建脚本。

---

## 九、许可

[MIT](LICENSE) © 2026 AnonTianjin
