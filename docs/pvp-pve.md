# PVP / PVE 配置

两种基础模式共用 `config.yml` 的对局规则段：`match.mode` 决定走哪一套，
房间模式可被战国派生（见 [战国 3v3](sengoku-3v3.md)）。本文覆盖两种模式的
配置项、胜负判定路径与代码入口。

## 一、模式与切换

```yaml
match:
  mode: pvp        # pvp | pve
```

- 指令切换：`/taketori match mode pvp|pve`（写回 config.yml）；
- 房间创建后模式随之固定；`/taketori sengoku mode` 可切战国派生模式；
- 模式差异一览：

| | PVP（默认） | PVE |
| --- | --- | --- |
| 分队 | 红蓝对抗，等待区手动选边 | 所有人并红队（`beginMatch` 强制） |
| 得分来源 | 击杀玩家 + 拆基地 + 月人 | 击杀月人（小怪/精英同一套 `scoring.minion-kill`） |
| 基地占点 | 有（`base.*`） | 无（PVE 换成保卫据点 `pve.outpost`） |
| 友伤保护 | 默认开 | 默认开（同房间互不伤害） |
| 满员 | `team-size × 2` | `waiting.pve-full-players`（0 = 取 `team-size`） |
| 额外机制 | - | 大波次、精英缩放、据点 |

## 二、对局规则（match / scoring 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `match.score-to-win` | 600 | 进攻方先到该分数获胜（没有包点的场地两边都用它） |
| `match.defender-score-to-win` | 600 | 防守方（蓝）目标分；PVP 有包点时按各自目标显示进度 |
| `match.attacker-score-multiplier` | 1.0 | 进攻方（红）得分倍率，用来抵消包点给红队多出来的一条获胜路径 |
| `match.time-limit-minutes` | 20 | 超时按分高者胜，平分判平局 |
| `match.team-size` | 3 | 每队人数上限（PVP 满员判定与分队容量） |
| `match.respawn-delay-seconds` | 5 | 死亡自动复活秒数；复活点固定为己方出生点 |
| `match.keep-inventory` | true | 死亡是否保留物品 |
| `scoring.minion-kill` | 3 | 击杀小怪得分 |
| `scoring.player-kill` | 10 | 击杀敌方玩家（队友互杀不计分，事件层取消） |
| `scoring.base-capture` | 50 | 拆除基地得分（PVE 无此项） |

胜负判定在 `GameRoom.tick`（PLAYING 分支）：到 `score-to-win` 即 `finish`；
通用时限只对 PVP/PVE 生效：战国模式恒跳过（战国积分恒 0-0，
胜负由 `SengokuSession` 按小局比分决定，见其文档第九节）。

## 三、包点（PVP 专属，bomb 段）

场地划了包点之后，这一局多一条获胜路径：进攻方（红）在拆弹窗口内把 TNT 拆开就赢，不必刷满分数。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `bomb.activation-delay-seconds` | 40 | 自由时段：开局多少秒后包点激活成 TNT |
| `bomb.defuse-time-limit-seconds` | 110 | 拆弹时限：这么久没拆开算防守方守住 |
| `bomb.defuse-seconds-bare` | 10 | 空手读条秒数 |
| `bomb.defuse-seconds-clipper` | 6 | 背包里带着拆弹钳时的读条秒数 |
| `bomb.reset-seconds` | 7 | 中断后多少秒进度归零（只提醒进攻方） |
| `bomb.blue-keep-score` | 200 | 守住包点时防守方（蓝）得分 |
| `bomb.red-consolation-score` | 50 | 守住包点时进攻方（红）的安慰分 |

划线方式：站到要当包点的方块上，`/taketori arena setbomb`；取消用
`/taketori arena delbomb`。坐标存在 `arenas.yml` 的 `bomb` 段，只有一格。

### 三个相位（`BombPhase`）

| 相位 | 起点 | 结束 | 期间发生什么 |
| --- | --- | --- | --- |
| `FREE_TIME` | 开局 | `activation-delay-seconds` 到点 | 双方在出生点准备，包点还是原方块 |
| `DEFUSE` | 包点变 TNT | 拆开 / `defuse-time-limit-seconds` 到点 | 红队读条，蓝队守 |
| `BASE_FIGHT` | 超时结算完成 | 到目标分 / 时限 | 基地开放，占点 / 互杀 / 清月人继续计分 |

### 拆弹动作（`BombObjective`）

- 红队站进包点 4.5 格内**左键**开始读条，**右键**取消；蓝队左键只回一句「你是防守方」；
- 进度按当前拆弹人累计：队友在 `reset-seconds` 内接手可以接着读，超时整条进度清零；
- 离开包点、死亡、掉线、换队都会中断，复位提示只发给红队；
- 读满 → `finish(RED)`，红队直接获胜，包点方块还原成原方块；
- 空手 10 秒、背包里带着拆弹钳 6 秒；钳子从队伍商店买（见 [队伍商店与炸弹](shop-and-bomb.md)）；
- 包点方块禁止被挖，被外力换掉时每秒补回 TNT。

### 超时结算（`bombTimedOut`）

110 秒没拆开 → 蓝队 +`blue-keep-score`、红队 +`red-consolation-score`，两笔直接写队伍分、
不套 `match.attacker-score-multiplier`；随后 `startBaseFight()` 开放基地，谁先到目标分谁赢
（保底分当场顶到目标分也会立刻结束）。

不划包点的场地不进相位机（`usesBombPhase()` 为 false）：基地开放仍按
`base.capture-delay-seconds` 的计时走，判胜负只看分数。

### 局内购买（商店）

对局中两队各有商人 NPC：右键它或 `/taketori shop` 打开 27 格商店，花局内个人积分买两件道具 ——
红队拆弹钳（30 分，每人 2 把，死亡清空）、蓝队地雷（40 分，每队 2 枚，踩到即死）。
快捷购买 `/taketori shop clipper` / `/taketori shop mine`，规则细节见
[队伍商店与炸弹](shop-and-bomb.md)。

## 四、加时赛（overtime 段）

时间到、双方都没到目标分时开加时：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `overtime.enabled` | true | 关掉后时间到直接按「防守方守住」结算，不投票 |
| `overtime.duration-seconds` | 60 | 加时赛时长 |
| `overtime.kill-score` | 1 | 每次击杀记多少人头 |

流程（`GameRoom.openOvertimeVote` / `startOvertime` / `settleOvertime`）：

1. 时间到 → 播报「比分清零，是否进入加时赛？」，`resetTeamScores()` 只清双方队伍比分
   （个人分与击杀保留，结算播报与战绩记录不受影响）；
2. 30 秒投票窗口：`/taketori overtime`（等价 `/taketori match overtime`）投同意；
3. 任一队全员同意，或某队已经没人（此时一人投票即通过）→ 立刻开打；
4. 窗口到期仍没通过 → 按防守方守住包点结算（蓝队胜；蓝队已无人则平局；有包点的场地照
   `blue-keep-score` / `red-consolation-score` 给保底分）；
5. 加时纯 PVP：包点收掉、占点关闭，每击杀一名敌人记 `overtime.kill-score`（默认 1）个人头，
   记分板多一行「加时人头: 红 N : 蓝 M」；
6. 打满 `duration-seconds` 比人头：多者胜，相同判平局。

加时只改「怎么判胜负」：击杀回血、掉落、复活与个人分规则都不变。

## 五、基地占点（PVP 专属，base 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `count-per-team` | 3 | `3` = 编号 1~3 且双方配满才能开局；`auto` = 至少 1 个即可（编号上限 16）；`N` = 1~16 任意 |
| `capture-seconds` | 10 | 连续占点秒数（可理解为基地耐久） |
| `capture-delay-seconds` | 60 | 开局保护期，期间禁止占点 |
| `decay-per-second` | 0.5 | 区域内无敌时进度衰减 |
| `multi-player-bonus` | true | 多人同占是否加速 |

占点结算在 `BaseCaptureManager`：敌方玩家在基地区域内累积进度，读满拆除、
得分 `scoring.base-capture`。基地区域用 `/taketori arena setbase <red|blue> [编号]`
划定（见 [区域配置](regions.md)）。

## 六、战斗参数（combat 段，两模式共用）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `kill-heal` | 6.0 | 击杀敌方玩家回血（生命点，2 = 1 心）；0 = 关闭 |
| `minion-kill-heal` | 0.0 | 击杀小兵回血 |
| `melee-charge-gate` | 0.9 | 近战蓄力门控：攻击冷却低于该值保留原版轻击、不放左键技能；0 = 关闭 |
| `friendly-fire-protection` | auto | `auto/on/off`：同房间同队互不造成伤害 |
| `match-saturation` | true | 对局期间自动续饱和（不消耗饥饿） |
| `spawn-buff.*` | 10s 三效果 | 开局/复活临时增益（`type` 用原版药水效果名） |
| `third-slot-buff.*` | 跳跃提升 V 2s | 第三槽技能成功触发时的附加增益 |

角色数值（血量、攻速等属性）在 `characters.yml`，不在本文范围。

## 七、月人（minion 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `health` / `iron-armor` | 40 / true | 普通月人基础数值 |
| `interval-seconds` / `per-spawn` / `max-alive` | 9 / 3 / 15 | 刷新节奏全局默认，可被 arenas.yml 每场地覆盖 |
| `normal.enabled` / `normal.interval-seconds` | true / 9 | normal 类刷新区的独立计时循环 |
| `mixed.enabled` / `mixed.interval-seconds` | true / 9 | mixed 类刷新区的独立计时循环（精英波走这条） |
| `types` | ZOMBIE:3 SKELETON:2 | 普通月人种类权重，每波按权重抽 |
| `elite.every-waves` | 5 | 每 N 波出一批精英；0/1 = 关闭 |
| `elite.count` / `max-alive` / `health` / `armor` / `attack-damage` | 2 / 4 / 80 / DIAMOND / 15 | 精英数值。`attack-damage` 单位是生命点（15 = 7.5 心） |

双计时循环：`minion-regions` 的 `<编号>.kind` 决定区域归属：
`normal` 只进 normal 循环、`mixed`（默认）进 mixed 循环；普通月人在全部区之间
轮转均分，精英只在 mixed 区刷。两条循环互不共享计数与生命周期，某类区域全被关掉
或不存在时对应循环不启动。区间覆盖关系：场地未配置的项回落全局默认。

伤害/状态结算口径见 `SkillTargets.isFilteredTarget`：受友伤保护的队友、召唤物、
观战者、笼内/等待区保护期玩家对月人技能与对玩家技能同一套判定。

## 八、PVE 专属（pve 段）

### 难度

`pve.difficulty`，指令 `/taketori pve difficulty <easy|normal|hard>`（写回文件）。
每档数值在 `pve.outpost.difficulties`，切换只影响据点三件套
（血量 / 月人拆迁速度 / 每波精英数与 buff 加成）。

### 大波次（pve.big-waves）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` / `count` | true / 5 | 一共 N 波精英潮，打完只剩常规刷怪 |
| `interval-seconds` / `start-delay-seconds` | 60 / 30 | 波间隔与首波延迟 |
| `elites-per-wave` | 8 | 每波数量；每波开始先清上一波残留 |
| `announce` | true | 波次播报 |

### 精英缩放（pve.elite-scaling）

每多 1 名参战玩家，精英药水等级整体 +`buffs-per-player`（默认 1），
上限 `max-buff-amplifier`（默认 6）。叠加示例：3 人 normal 局
= 难度加成 1 + 人数加成 2 = 精英 buff 高 3 级。

### 保卫据点（pve.outpost）

不会移动、也不会被玩家打死的雪傀儡（`OutpostManager`），月人进入 `radius`
后开始拆。位置 `/taketori arena setoutpost` 划定，未划则取月人刷新区中心。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `end-match-on-destroyed` | true | 据点被拆即结束对局；false = 只播报 |
| `difficulties.<档>.health` | 240/320/460 | 据点血量 |
| `difficulties.<档>.damage-per-second` | 4/7/11 | 月人每秒拆迁量 |
| `difficulties.<档>.elites-per-wave` | 6/8/10 | 覆盖 `big-waves.elites-per-wave` |
| `difficulties.<档>.elite-buff-bonus` | 0/1/2 | 叠加进精英 buff 等级 |

PVE 的胜负路径：`score-to-win` 达分获胜；据点被拆且
`end-match-on-destroyed: true` 时结束对局（管理指令停止不写战绩）。

## 九、PVE 房间要点

- 满员人数 `waiting.pve-full-players`（0 = 取 `match.team-size`）；
- 分队不需要选边：开局时未选队者统一并红队，等待区不会因「未选队伍」卡开局；
- 缺人判负（`room.understaffed-grace-seconds`）与补位机制只对 PVP 生效
  （`GameRoom.tick` 的 `isPve()` 分支跳过）；
- 战国模式不刷月人（`startPlay` 里刻意不启动月人组件），小兵只有战国自己的
  中地小兵，不要把两套刷怪叠到一张图里。
