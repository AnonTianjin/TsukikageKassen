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
| 额外机制 | — | 大波次、精英缩放、据点 |

## 二、对局规则（match / scoring 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `match.score-to-win` | 600 | 先到该分数获胜 |
| `match.time-limit-minutes` | 20 | 超时按分高者胜，平分判平局 |
| `match.team-size` | 3 | 每队人数上限（PVP 满员判定与分队容量） |
| `match.respawn-delay-seconds` | 5 | 死亡自动复活秒数；复活点固定为己方出生点 |
| `match.keep-inventory` | true | 死亡是否保留物品 |
| `scoring.minion-kill` | 3 | 击杀小怪得分 |
| `scoring.player-kill` | 10 | 击杀敌方玩家（队友互杀不计分，事件层取消） |
| `scoring.base-capture` | 50 | 拆除基地得分（PVE 无此项） |

胜负判定在 `GameRoom.tick`（PLAYING 分支）：到 `score-to-win` 即 `finish`；
通用时限只对 PVP/PVE 生效——**战国模式恒跳过**（战国积分恒 0-0，
胜负由 `SengokuSession` 按小局比分决定，见其文档第九节）。

## 三、基地占点（PVP 专属，base 段）

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

## 四、战斗参数（combat 段，两模式共用）

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

## 五、月人（minion 段）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `health` / `iron-armor` | 40 / true | 普通月人基础数值 |
| `interval-seconds` / `per-spawn` / `max-alive` | 9 / 3 / 15 | 刷新节奏全局默认，可被 arenas.yml 每场地覆盖 |
| `normal.enabled` / `normal.interval-seconds` | true / 9 | normal 类刷新区的独立计时循环 |
| `mixed.enabled` / `mixed.interval-seconds` | true / 9 | mixed 类刷新区的独立计时循环（精英波走这条） |
| `types` | ZOMBIE:3 SKELETON:2 | 普通月人种类权重，每波按权重抽 |
| `elite.every-waves` | 5 | 每 N 波出一批精英；0/1 = 关闭 |
| `elite.count` / `max-alive` / `health` / `armor` / `attack-damage` | 2 / 4 / 80 / DIAMOND / 15 | 精英数值。`attack-damage` 单位是生命点（15 = 7.5 心） |

**双计时循环**：`minion-regions` 的 `<编号>.kind` 决定区域归属——
`normal` 只进 normal 循环、`mixed`（默认）进 mixed 循环；普通月人在全部区之间
轮转均分，精英只在 mixed 区刷。两条循环互不共享计数与生命周期，某类区域全被关掉
或不存在时对应循环不启动。区间覆盖关系：场地未配置的项回落全局默认。

伤害/状态结算口径见 `SkillTargets.isFilteredTarget`：受友伤保护的队友、召唤物、
观战者、笼内/等待区保护期玩家对月人技能与对玩家技能同一套判定。

## 六、PVE 专属（pve 段）

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

## 七、PVE 房间要点

- 满员人数 `waiting.pve-full-players`（0 = 取 `match.team-size`）；
- 分队不需要选边：开局时未选队者统一并红队，等待区不会因「未选队伍」卡开局；
- 缺人判负（`room.understaffed-grace-seconds`）与补位机制只对 PVP 生效
  （`GameRoom.tick` 的 `isPve()` 分支跳过）；
- 战国模式不刷月人（`startPlay` 里刻意不启动月人组件），小兵只有战国自己的
  中地小兵，不要把两套刷怪叠到一张图里。
