# 月影合战 · TsukikageKassen

[![Build](https://github.com/AnonTianjin/TsukikageKassen/actions/workflows/build.yml/badge.svg)](https://github.com/AnonTianjin/TsukikageKassen/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/AnonTianjin/TsukikageKassen)](https://github.com/AnonTianjin/TsukikageKassen/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

一个 Minecraft 服务端战斗插件。核心是一层用原版事件重绑按键的技能系统。

9 名角色、20 把武器、20 种技能。在技能层之上叠了三种玩法：3v3 积分赛、PVE 月人入侵、战国 3v3（三局两胜）。配套大厅、动态房间匹配（月宫制）、观战和跨局战绩。武器之间可以打出「挂标记 → 兑现」的组合技。

| 项 | 说明 |
| --- | --- |
| 服务端 | Paper 1.21.4 |
| Java | 21 |
| 客户端 | 原版即可，不需要资源包或 mod |
| 额外依赖 | 无 |
| 许可 | MIT |

---

# 给服主与玩家

## 一、安装

1. 从 [Releases](https://github.com/AnonTianjin/TsukikageKassen/releases/latest) 下载
   `tsukikage-kassen-1.7.0.jar`，放进服务端 `plugins/` 目录，重启服务器。
2. 首次启动会在 `plugins/TsukikageKassen/` 生成 `config.yml`、`weapons.yml`、`characters.yml`、`messages.yml`、五份 `sengoku*.yml` 与模板目录 `moonmaps/`。
3. 控制台出现下面这行即加载成功：

```
TsukikageKassen v1.7.0 已启用：20 把武器 / 9 个角色 / 20 种技能类型（适配层 default）
```

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `taketori.play` | 所有人 | 玩家指令：菜单、选角色、加入队列、观战、技能查询 |
| `taketori.admin` | OP | 管理指令：场地、大厅、分队、开局、发武器、编辑器、重载、调试 |

> 插件的 `api-version` 声明为 `1.21`，服务端低于该版本会拒绝加载。

装好之后的三种玩法，玩家只需要记住：大厅点告示牌「加入」或「降临月宫」建房，等倒计时归零就能开打。

## 二、十步设置

从装好插件到能开一局，按顺序做完这十步即可（除第 1 步准备模板外，全部在游戏内执行）。
第 2~6 步由**划场地会话**（`/taketori arena setup`）串起来：一次进入编辑世界，之后每划完一项
会自动刷新「还缺什么」的清单。

### 1. 准备月面模板

模板是 `plugins/TsukikageKassen/moonmaps/<模板名>/` 下的一个世界文件夹（必须含 `level.dat`），
三种来源任选其一：

**a. 已有地图**：把世界文件夹整个复制进 `moonmaps/tsukuyomi/`（`moonmaps/` 目录首启自动创建）。

**b. 用服务器里已有的世界**：不必手动搬文件夹，直接导入：

```
/taketori moonmap import <世界名> <场地id>
```

世界正加载着也没关系，会先自动存盘再复制；导入完即可直接 `arena setup` 划定。

**c. 手头没有地图、只想先试一局**：生成一张空白平坦模板：

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

用锄头**左键点区域一角**、**右键点对角**，然后：

```
/taketori arena setminion
```

可配多个刷新区（`setminion [编号] [normal|mixed]`）：普通月人在**全部刷新区之间轮转均分**；
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

站到位置上执行（`setspawn` 用的是**你的站位**，不是选区）：

```
/taketori arena setspawn red
/taketori arena setspawn blue
/taketori arena setwait         # 中立等待出生点：匹配后玩家在此集结倒计时（未设置不能开局）
```

`setwait` 多一条规则：**手上还留着选区时**，它会把整片选区设成「等待区」（加入者在区域内
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
放弃路径**不会**自动对齐模板名：

- 想直接保存当前编辑成果：`/taketori moonmap unload <模板名>`，它写回 `moonmaps/<模板名>/`；
- 场地 id 与模板名**不同名**时别走 cancel：重新 `/taketori arena setup <场地id> <模板名>`
  接着改，最后仍用 `setup done` 收尾，改名才会发生。

用 `/taketori moonmap list` 确认模板显示 **就绪·开放**（`arena list` 同样可见）。

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
**实时状态牌**（`addstatus <模板id>`）每秒刷新该模板当前房间的状态与人数，
点击直接加入 / 旁观（有缺口先补位）/ 无房时创建；牌子被破坏会自动从列表摘除。

### 9. 检查配置

```
/taketori arena list
/taketori lobby list
```

`arena list` 里每个启用场地要显示 **就绪·开放**（未就绪会列出还缺什么：出生点 / 基地 / 刷新区 / 等待点），`lobby list` 要显示 **是否可用：是**。

### 10. 开一局

玩家有三种进场方式：大厅点「加入对局」告示牌（或菜单匹配按钮）快速加入；点「降临月宫」创建新房间（异步复制模板世界，几秒后自动进入）；打开房间列表选房。快速加入的三级回退：等待人数最多的房间 → 缺人正在打的对局补位 → 用默认模板自动建房。

等待人数达到 `waiting.min-players`（默认 2）后房间自动开始倒计时（默认 **90 秒**；人数过半场（3v3 的第 4 人起）压缩到 **30 秒**；满员切 5 秒；有人退出人数不足则取消），归零后分队进出生点玻璃笼、解笼开战；结算后在线者自动回大厅并返还封存状态，房间世界随即删除。

对局中队友掉线：槽位释放可被补位；`room.understaffed-grace-seconds`（默认 60 秒）内无人补位则缺人队判负、本场提前结束。落后方也可用 `/taketori surrender` 发起投降表决（半数以上在线队友同意即结束）。

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
/taketori match mode pve [场地id]   # 把房间切到 PVE（仅等待中的房间可切，只改本房间）
/taketori pve difficulty hard       # 切难度（easy / normal / hard）
```

战国 3v3 的点位划分（天守阁 / 箭楼 / 铜钟 / 守卫 / 中地 / 跳跃台）用 `/taketori sengoku`，
详见[玩法说明](#34-战国-3v3三局两胜)与[指令参考](#四指令参考)。

## 三、玩法说明

### 3.1 按键

| 按键 | 作用 |
| --- | --- |
| 左键 | 第一槽技能（近战 / 射击类主输出），受武器攻击冷却限制，连点无效 |
| 右键 | 第二槽技能（核心技能，冷却 1~20 秒） |
| **第三槽键** | 位移 / 控制类技能；默认**双击潜行键**，成功触发时附带 2 秒跳跃提升 V |
| Q | 切换该武器自己的模式（疾风为切换装备）；战国模式能量满时释放必杀技 |
| 1 / 2 | 切换快捷栏武器（有两件武器的角色） |
| F | 交换副手（已被插件接管，不会真的换副手） |

第三槽的触发方式在 `input.shift-right-trigger` 配置，可多选（逗号分隔），写 `all` 全部启用：

| 写法 | 怎么按 | 说明 |
| --- | --- | --- |
| `double-sneak`（默认） | 双击潜行键 | 最可靠：潜行是独立按键，原版一定会发出事件；300 毫秒内两次才算 |
| `sneak-q` | 潜行 + Q | 可靠，但要同时按两个键 |
| `double-right` | 双击右键 | 不需要新键；第二次右键改派给第三槽 |
| `f` | F 键 | 部分服务器 / 插件会吞掉这个事件，按了没反应就换别的 |
| `sneak-right` | 潜行 + 右键 | 对着方块时原版不发事件，只有右键空气可靠 |

按了没反应时：先用 `/taketori keys` 看按键有没有传到服务端，再用 `/taketori f` 手动触发同一个技能。
近战左键受蓄力门控（`combat.melee-charge-gate`，默认 0.9）：蓄力不足只结算原版轻击，满蓄才触发技能。

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

- 在等待区选中角色即完成绑定；**武器与保护 II 铁甲在开局时才发放**，铁甲是**直接穿上**的并带
  **不可破坏**标记（`loadout` 段可配）。大厅与等待区不会拿到对局装备；对局进行中改角色（含管理员
  `/taketori character`）会立即换装。你原来的护甲连整背包在进房时被封存，结算原样返还。
- 夕月的弓**没有箭也能射**：背包里没有箭时右键即发，走与普通射击相同的强化 / 减益 / 三连射规则。
- 同一个队伍里不允许出现相同角色；不同队之间可以有相同角色。
- 一局结束后参赛者的角色会被**清空**（含掉线者），下一局重新选择；隐性标签不受影响。
- 每把武器的四个槽位分别绑了什么，用 `/taketori skills` 看手里的武器即可。

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

**战斗层的公共机制**（三种玩法通用）：

- **四种状态机制**：易伤标记（被标者受到的所有伤害提高）、破甲（独立乘区、硬上限 30%）、
  **兑现**（带 `echo-bonus` 的技能打在带状态的目标上放大伤害，动作栏报「兑现！」）、
  **场地与召唤**（领域类周期结算；召唤物带归属，队友打不到、击杀不计分）；
- **组合技**：9 个角色各有 1 组连携（如月读「镇魂铃挂易伤 → 陨星锤兑现」），全部由四种机制自然涌现，不需要额外配置；
- **打击反馈**：动作栏伤害数字汇总、冷却就绪提示音、连击可视化、重技能 title 演出；
- **载体护栏**：手持插件武器时不挖方块、不放置、不丢弃、不换副手；绑定武器不能入箱、不会被他人捡走。

### 3.3 三种对局模式

| 模式 | 切换 | 节奏 |
| --- | --- | --- |
| **3v3 积分赛（PVP）** | 默认 | 先到 600 分或 20 分钟到时比分高者胜 |
| **PVE 月人入侵** | `/taketori match mode pve [房间id]` | 全员同一队守据点，撑过五大波次 |
| **战国 3v3（三局两胜）** | `/taketori match mode sengoku_3v3 [房间id]` | 每小局攻陷敌方天守阁者胜，先赢两小局赢整场 |

**3v3 积分赛**

| 项目 | 设定 |
| --- | --- |
| 击杀月人 / 玩家 / 拆基地 | `+3` / `+10` / `+50` |
| 击杀回血 | 击杀敌方玩家回复 3 颗心（`combat.kill-heal`，满血不回） |
| 基地 | 双方各 3 个（数量可配），开局 60 秒保护期内不能占点 |
| 占点 | 站进对方基地区域持续 10 秒；区域内没敌人时进度按 `base.decay-per-second` 衰减 |
| 月人 | 每 9 秒一批（铁甲僵尸 / 骷髅按权重随机），场上最多 15 个；多刷新区之间**按区轮转均等分布** |
| 精英月人 | 每 5 波一批（钻甲 + 药水 buff），只在 mixed 标签刷新区出现 |
| 复活 | 死亡 5 秒后回己方出生点，死亡不掉落物品 |
| 基地标记 | 双方基地用**队伍颜色**粒子持续标出（远处看柱、近处看框），被拆后标记立即消失 |

**PVE 月人入侵**：所有人同一队、不占点、玩家之间默认无伤害。

- **保卫据点**：开局放出无敌雪傀儡，月人靠近就按「在场月人数 × 每秒伤害」扣耐久，归零对局结束（可配成只播报）；
- **五大波次**：每约 60 秒一波精英潮，默认 5 波、每波 8 名精英；
- **三档难度**（`/taketori pve difficulty <easy|normal|hard>`）：hard 档据点耐久 460 / 每月人每秒 11 伤 / 每波 10 精英；normal 默认；
- **精英随人数变强**：每多 1 名参战玩家，精英药水等级整体 +1，叠加难度档后封顶 6 级。

**战国 3v3（三局两胜）**：独立模式，与 PVP / PVE 互不影响。

- **赛制**：每小局先攻陷敌方天守阁者胜，先赢两小局者赢整场（赛制与局时长可配）；
  超时按箭楼占领数判定，持平则本小局重开，「龟缩到时间结束」不是有效战术；
- **天守阁**：永久不可直接破坏（拦截破坏与爆炸），唯一胜利通道是击破器；
- **箭楼**（上下路各一）：清掉牛鬼（尸壳）与虾兵蟹将（卫道士）→ 敲钟 → 站进占领区读条；
  **双方同时读条时互锁**（两张进度都停滞），可反复易手，每次易手后守卫重刷；
- **大将击破器**：占领箭楼后在**敌方**门前生成；拾取携带 → 进敌方天守阁范围自动读条 →
  读满本局获胜。不可破坏 / 不可丢弃 / 不可被敌方捡走 / 不可入箱，被消耗后再占领会自动补齐；
- **跳跃台**：占领箭楼后在己方门前激活，站上去直达最近的己方已占领箭楼，阵亡复活后也能用；
- **中地小兵**：场地中央持续生成、允许被绕过；击杀攒能量，能量满按 Q 释放必杀技。

场地点位用 `/taketori sengoku setkeep / settower / setbell …` 划定，`/taketori sengoku check`
检查是否齐全；配置见[配置项](#五配置项)的五份 `sengoku*.yml`。

### 3.4 房间与匹配（月宫制）

- **动态房间**：房间由玩家按需创建，异步复制模板世界为专属世界，结算后自动删除回收；
  服务器最多同时 `room.max-rooms`（默认 8）个房间，每人限建 1 个；
- **等待与倒计时**：进房即封存自带状态（背包 / 血量 / 药水 / 游戏模式），等待区倒计时三档；
- **观战**：聊天栏给出可点击的「退出观战」按钮并定期重发；阵亡自动旁观，
  屏幕上方 BossBar 显示复活倒计时，结束回己方出生点复活；
- **跨局战绩**：总积分 / 胜场 / 击杀 / 拆家等写入 `data/stats.yml`，`/taketori ranks` 图形查看；
- **菜单时钟**：进服发放的道具，右键打开玩家菜单（匹配 / 队伍 / 角色 / 排行榜），丢不掉。

### 3.5 多世界兼容

给 Multiverse 之类的多世界服务器用，两项都默认收窄、可一键恢复旧行为：

- **进服送大厅只接管白名单世界**（默认仅大厅出生点所在的世界，其它世界位置不变）；
- **消息播报只发给消息所属世界**（对局播报 → 对局世界，大厅播报 → 大厅世界）。

## 四、指令参考

所有指令统一使用全名 `/taketori`，没有缩写别名。

### 玩家指令（`taketori.play`）

```
/taketori menu                     打开玩家菜单（匹配 / 队伍 / 角色 / 排行榜）
/taketori play                     同 menu（快捷入口）
/taketori ranks [榜单]             总计排行榜 GUI
/taketori stats [数量]             聊天栏版排行榜（跨局累计）
/taketori character <角色id|none>  选择 / 取消自己的角色
/taketori skills                   查看手里武器四个槽位的技能与冷却
/taketori mode                     手动触发 Q 槽技能（Q 被别的插件吞掉时用）
/taketori f                        手动触发第三槽技能
/taketori keys                     按键诊断：回放最近收到的原始输入事件
/taketori lobby join|leave|spectate 快速加入房间 / 退房回大厅 / 旁观
/taketori leave                    退出观战、离开等待房间（对局中参赛者不能中途退出）
/taketori room create [模板id]     创建房间（降临月宫，异步复制模板世界）
/taketori room delete|list         删除自己的等待房 / 房间列表
/taketori surrender                发起/确认本队投降（对局中）
/taketori doctor                   自检：识别链路、名字解析、配置校验、多世界范围
```

### 管理员指令（`taketori.admin`）

**战国 3v3**（独立模式；先 `arena setup` 选中场地，区域类用 `pos1`/`pos2` 选区，点类站在位置上）

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
/taketori sengoku mode [pvp|pve|sengoku_3v3]  查看 / 切换全局默认模式（写回 config.yml）
/taketori sengoku menu                     打开图形化面板（上面这些的按钮版）
```

**切模式**有两种粒度：

```
/taketori sengoku mode sengoku_3v3     全局默认（写回 config.yml，只影响之后新建的房间）
/taketori match mode sengoku_3v3 [房间id]  只切一个房间（等待中才能切，不影响别人）
```

**图形化面板**：`/taketori admin` → 「战国 3v3」，或直接 `/taketori sengoku menu`。

**场地**（多场地：`set*` / `del*` 作用于当前选中的场地，先用 `create` / `select` 选中）

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
/taketori arena delbase <red|blue> <编号>        删除某个基地
/taketori arena delminion <编号>                 删除某个月人刷新区
/taketori arena delloot <编号>                   删除某个道具刷新点
/taketori arena deloutpost                       清除据点位置
/taketori arena clearselection                   清空你的选区
/taketori arena list                             查看全部场地的启用与就绪情况
```

**月面模板（`moonmaps/`）**

```
/taketori moonmap list                            列出模板与 arenas.yml 定义状态
/taketori moonmap create <模板名>                 从零生成一张平坦模板世界并注册为场地
/taketori moonmap import <世界名> <场地id>        把服务器已有世界导入为模板（不必手动搬文件夹）
/taketori moonmap load <模板名>                   复制模板为编辑世界 k_tpl_<名> 并加载
/taketori moonmap unload <模板名>                 保存编辑世界写回 moonmaps 并卸载
```

**大厅**

```
/taketori lobby setspawn                         当前位置设为大厅出生点
/taketori lobby pos1 | pos2 | setregion          划大厅范围（用于大厅保护与识别）
/taketori lobby addsign <动作>                   准星对准告示牌绑定动作
/taketori lobby removesign                       移除准星所指告示牌的绑定
/taketori lobby list                             查看大厅配置与告示牌
```

**对局**（多房间：不写房间 id 时取「你所在房间 → default → 第一个房间」）

```
/taketori match start [房间id]                   对指定房间开局（双方各至少 1 人）
/taketori match force [房间id]                   人数不够也开（只按该房等待区的人分队）
/taketori match stop [房间id] [原因]             结束指定房间（不影响其他并发房间）；等待/倒计时阶段则直接解散房间，房内玩家回大厅
/taketori match status                           逐房间列出阶段 / 比分 / 剩余 / 基地 / 场上月人
/taketori match mode <pvp|pve|sengoku_3v3> [房间id]  切换指定房间模式（仅等待中可切，不写全局配置）
/taketori pve                                    查看 PVE 设置（难度 / 波次 / 据点耐久）
/taketori pve difficulty <easy|normal|hard>      切换 PVE 难度（下一局生效）
/taketori team <玩家> <red|blue|none>            手动分队（作用于目标玩家所在房间）
/taketori character <玩家> <角色id|none>         指定角色（同队不能重复）
```

**维护**

```
/taketori editor [武器id]                        武器数据编辑 GUI
/taketori give <玩家> <武器id>                   发放武器
/taketori debug [on|off]                         调试日志开关
/taketori qmode <drop|held-slot|none>            运行时切换 Q 键行为
/taketori reload                                 热重载全部配置
/taketori admin                                  管理员菜单（含删除区域等按钮）
```

**管理工具一览**：选区锄（默认下界合金锄，粒子描边、重叠提示）、道具点工具（默认结构空位）、
武器数据编辑 GUI（三层菜单、写回保留注释）、管理员菜单、角色分配（同队不重复，隐性标签权重裁决）、
`/taketori doctor` 自检与 `/taketori keys` 按键回放。

## 五、配置项

改完 `config.yml` 执行 `/taketori reload` 生效（模板世界文件夹在 `moonmaps/<模板名>/`，与场地定义 `arenas.yml`、大厅 `lobby.yml` 一样不受 reload 影响）。

| 段 | 关键项 | 默认 |
| --- | --- | --- |
| `match` | `mode` / `score-to-win` / `time-limit-minutes` / `team-size` / `respawn-delay-seconds` / `keep-inventory` | pvp / 600 / 20 / 3 / 5 / true |
| `waiting` | `min-players` / `countdown-seconds` / `half-countdown-seconds` / `full-countdown-seconds` / `cage-hold-seconds` / `end-delay-seconds` / `cage-material` / `pve-full-players` / `void-y-offset` / `protect` | 2 / 90 / 30 / 5 / 3 / 5 / GLASS / 0（取 team-size）/ -10 / true |
| `room` | `max-rooms` / `max-rooms-per-player` / `world-prefix` / `default-template` / `empty-dispose-seconds` / `understaffed-grace-seconds` | 8 / 1 / kassen_ / tsukuyomi / 60 / 60 |
| `scoring` | `minion-kill` / `player-kill` / `base-capture` | 3 / 10 / 50 |
| `combat` | `kill-heal` / `minion-kill-heal` / `friendly-fire-protection` / `third-slot-buff` / `match-saturation` | 6.0 / 0.0 / auto / 2 秒跳跃提升 V / true（对局内自动饱和） |
| `loadout` | `armor-enabled` / `armor-material` / `armor-protection` | true / IRON / 2（保护 II） |
| `minion` | `health` / `iron-armor` / `interval-seconds` / `per-spawn` / `max-alive` / `types` / `elite` / `normal.*` / `mixed.*` | 40 / true / 9 / 3 / 15 / 僵尸骷髅权重 / 每 5 波 / normal 与 mixed 两类刷新区各自的独立循环（enabled + interval-seconds，未配置回落全局间隔） |
| `pve` | `difficulty` / `big-waves` / `elite-scaling` / `outpost` | normal / 5 波·8 精英·60 秒 / 每多 1 人 +1 级 / 三档数值 |
| `base` | `count-per-team` / `capture-seconds` / `capture-delay-seconds` / `decay-per-second` / `multi-player-bonus` | 3（或 auto）/ 10 / 60 / 0.5 / true |
| `lobby` | `teleport-on-join` / `takeover-worlds` / `protect` / `return-after-match` | true / `[]`（仅大厅世界）/ true / true |
| `worlds` | `broadcast-scope` | world（只发给消息所属世界；`all` = 全服） |
| `menu-clock` | `enabled` / `material` / `give-on-join` / `name` / `lore` | true / CLOCK / true / … |
| `input` | `q-mode` / `weapon-slots` / `shift-right-trigger` | drop / [0,1,2,3] / double-sneak |
| `items` | `soulbound` / `auto-give-on-join` / `allow-drop` | true / false / false |
| `feedback` | `actionbar` / `particles` / `sounds` / `cooldown-display` | true / true / true / bossbar |
| `setup-wand` | `enabled` / `material` / `loot-material` / `give-on-join` | true / NETHERITE_HOE / STRUCTURE_VOID / false |
| `loot` | `enabled` / `interval-seconds` / `max-drops` / `items` | true / 30 / 6 / 道具池 |
| `tags` | `default` / `vip` / `staff` 的权重 | 0 / 10 / 100 |

### 数值文件（插件不会覆盖）

- `weapons.yml`：全部武器数值与技能参数。`attack-damage` / `attack-speed` 写的是**最终值**；`modes.<MODE>.skills` 会整段替换武器级同名槽位；写错的参数键会在启动时被配置校验点名。**当前模板版本 `config-version: 10`**（2.0 的武器与技能体系）。插件升级后如果控制台提示「你的 weapons.yml 是模板 v…」，同步方式是备份并删除该文件后 `/taketori reload` 重新生成。
- `characters.yml`：角色血量、移速、武器列表与描述。
- **战国 3v3 的五份配置**（改完 `/taketori reload` 即生效）：
  `sengoku.yml`（赛制 / 局时长 / 超时判定 / 天守阁）、`sengoku-towers.yml`（箭楼与守卫）、
  `sengoku-siege.yml`（击破器与跳跃台）、`sengoku-minions.yml`（中地小兵）、
  `sengoku-energy.yml`（能量与必杀）。点位写在 `arenas.yml` 的 `arenas.<id>.sengoku` 段，
  由 `/taketori sengoku setkeep …` 等命令写入。
- `messages.yml`：所有提示文案（新键会自动回填默认值）。
- 战斗数值全部外置、**没有硬编码**。（唯一例外是场地技能的参数在**施法瞬间快照**，热重载从下一次施法开始生效。）

---

# 给开发者

## 六、架构总览

插件分成两个严格分层：`core/` 是**纯 Java**（不 import 任何 Bukkit 类，构建护栏强制检查），
承载全部规则与数值判定；`paper/` 是 Bukkit 适配层，把 core 的规则接到事件、实体与调度器上。
这样设计的目的：规则可以被离线断言覆盖（见[构建与测试](#七构建与测试)），且将来换服务端平台时规则层可整体复用。

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
| 技能战斗层 | `paper/skill/impl/` | 一个实现类对应 `weapons.yml` 的一个 `type`（20 种），`SkillTargets` 统一选目标（友伤保护 / 范围 / 视线锥 / 前置状态） |
| 房间生命周期 | `paper/match/room/`（`GameRoom` / `RoomManager`） | `Phase = WAITING → STARTING → CAGED → PLAYING → ENDING` 的状态机，模板世界复制 / 回收，每秒 `tickAll()` 驱动 |
| 战国 3v3 | `core/match/sengoku/` + `paper/match/sengoku/` | core 放赛制规整、超时判定、箭楼互锁等纯规则；paper 放 `SengokuSession` 编排与 `TowerManager` / `SiegeBreakerManager` 等 8 个管理器。`GameRoom.Phase` 语义不变，多局制完全由 `SengokuSession` 承担，对 PVP / PVE 零影响 |
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
gradle build          # 产物在 build/libs/
```

`build.gradle.kts` 内置两道护栏：`checkCorePurity`（`core/` 层出现 Bukkit / NMS 引用即失败）与
`checkParamKeys`（技能读取的参数键必须在校验表里登记）。

### 离线脚本（无网络、无 Gradle 时）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1 -LibsDirs "<依赖 jar 目录>"
```

只需要 **JDK 21** 与一份 **paper-api 1.21.4** jar（再加 adventure 系列的 api / key / minimessage）。
脚本会跑同样的两道护栏、编译全部源文件、打包到 `build/dist/tsukikage-kassen-<版本>.jar`
（与 Gradle 的 `build/libs/tsukikage-kassen-<版本>.jar` 同名同内容）；
版本号唯一来源是 `gradle.properties` 的 `version=`（`plugin.yml` 占位符、jar 文件名、启动日志都由它派生）。

### 离线断言（无需启动服务器）

`tools/` 下的测试类都是带 `main` 的独立断言，编译后直接跑：

```powershell
javac -sourcepath src/main/java -d build/tools tools/SengokuRoundTest.java tools/TowerContestTest.java
java -cp build/tools SengokuRoundTest      # 74 项：赛制规整 / 超时判定 / 平局语义 / 模式解析 / 能量规则
java -cp build/tools TowerContestTest      # 39 项：箭楼互锁规则的各种组合
```

其余工具：`WeaponYamlCheck`（核对 weapons.yml / characters.yml 里的材质、粒子、音效、药水、弹体、
属性、技能类型与参数键）、`WorldScopeTest`（多世界判定回归）、`MiniMessageClickTest`、
`BaseArgParserTest`、`WeaponYamlEditorTest`。

### 面向开发的扩展点

- **加一把武器 / 一个技能**：在 `weapons.yml` 里写数值，技能槽引用 20 种 `type` 之一并填参数键
  （全部键在启动校验表里，写错会点名）；不需要写 Java。要新技能类型才需要新增 `paper/skill/impl/` 实现类。
- **加一个角色**：`characters.yml` 加段即可（血量 / 移速 / 武器列表），同队不重复的约束自动生效。
- **加一种对局模式**：参照战国 3v3 的分层：纯规则进 `core/match/`（可离线断言），
  编排与管理器进 `paper/match/`，挂到 `GameRoom` 上但不改 `Phase` 语义。
- **适配新版服务端**：`version/DefaultVersionAdapter` 的注册表里补新名字即可，不改调用点。

## 八、目录结构

```
src/main/java/com/taketori/kassen/
├─ core/        纯 Java 逻辑（角色 / 武器 / 技能定义、对局规则、战国规则、世界范围判定），禁止引用 Bukkit
├─ paper/       Bukkit 适配（监听器、技能实现、GUI、命令、房间与大厅管理、战国管理器）
│  ├─ skill/impl/   技能实现，一个类对应 weapons.yml 里的一个 type（20 种）
│  ├─ match/room/   GameRoom 状态机与 RoomManager（世界复制 / 回收）
│  ├─ match/sengoku/  战国 3v3 编排（SengokuSession、箭楼 / 击破器 / 跳跃台 / 小兵 / 能量）
│  └─ command/      TaketoriCommand / MatchCommand / AdminMenu / SengokuCommand
├─ version/     版本差异收口（属性 / 粒子 / 音效 / 药水的注册表解析）
├─ config/      配置加载、校验与迁移
└─ data/        持久化（YAML 存档：角色绑定、隐性标签、跨局战绩）
src/main/resources/
├─ plugin.yml / config.yml / weapons.yml / characters.yml / messages.yml
└─ sengoku.yml / sengoku-towers.yml / sengoku-siege.yml / sengoku-minions.yml / sengoku-energy.yml
tools/          离线测试与校验器（无需启动服务器）
build-offline.ps1  无网络环境的构建脚本
```

`.gitignore` 已排除构建产物、本地依赖 jar 与凭据文件（**不要把 token / 密钥提交进仓库**）。

---

## 九、许可

[MIT](LICENSE) © 2026 AnonTianjin
