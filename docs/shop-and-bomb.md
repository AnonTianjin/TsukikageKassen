# 队伍商店与炸弹

2.2.0 起 PVP 攻防多了两队的局内商店，包点也从「连点拉杆」改成 CS 式读条拆弹；时间到双方都没
达标还会进加时赛。本文覆盖三者的规则、配置与排错；指令总表见
[README 的指令参考](../README.md#四指令参考)。

## 一、队伍商店

### 商品

| 商品 | 队伍 | 价格 | 上限 | 效果 |
| --- | --- | --- | --- | --- |
| 拆弹钳 | 红队 | `shop.defuse-clipper.cost`（30） | `shop.defuse-clipper.max-per-player`（每人 2 把） | 背包里带着它拆包，读条 6 秒（空手 10 秒） |
| 地雷 | 蓝队 | `shop.land-mine.cost`（40） | `shop.land-mine.max-per-team`（每队 2 枚） | 埋成压力板，3 格触发、5 格必死 |

花的是局内个人积分（击杀 / 拆基地攒的），买完直接进背包。积分不够、不是该队、已达上限时，
界面里那一格会变灰并写明原因。

### 商人 NPC 配置步骤

1. 什么都不配也能用：`shop.npc.enabled` 默认 `true`，两个商人落在大厅出生点；
2. 要挪位置：站到目标点（抬头可顺便定朝向），执行 `/taketori shop setnpc red`（或 `blue`，
   需要 `taketori.admin`），坐标与 yaw 立刻写回 `config.yml` 并重建 NPC，不用 reload；
3. 要放到别处：直接改 `shop.npc.<red|blue>.world` 与 `x/y/z/yaw`，再 `/taketori reload`；
4. 不想用商店：`shop.npc.enabled: false`，NPC 不再生成。

| 配置 | 含义 |
| --- | --- |
| `npc.enabled` | `false` = 完全不生成商人 |
| `npc.<red\|blue>.name` | 头顶名字；含 `§` 按旧式颜色码解析，否则按 MiniMessage |
| `npc.<red\|blue>.world` | 留空 = 跟随大厅出生点所在世界；写了世界名但那个世界当前没加载时这一轮跳过（不回落到大厅世界，免得在错误的世界里生成一只） |
| `npc.<red\|blue>.x/y/z` | 全 0 且大厅设了出生点 = 直接用大厅出生点坐标 |
| `npc.<red\|blue>.yaw` | 朝向 |

商人本体是村民：无敌、无 AI、静音、不推动、无重力、持久化。插件每 10 秒做一次自愈检查，
位置对得上的残留 NPC 收养，位置不对的移除重建；实在算不出位置的队会跳过并打日志提示用 `setnpc`。

### 购买流程

```text
右键商人 NPC              按该队商人打开商店（红队商人 = 红队商品）
/taketori shop            打开未分队视图（两件都列，仅供查看）
/taketori shop clipper    直接买拆弹钳（红队，别名 剪刀 / 拆弹钳）
/taketori shop mine       直接买地雷（蓝队，别名 地雷）
```

- 只有在进行中的对局里才能买，否则提示「你不在进行中的对局中，无法购买道具」；
- 界面每次打开 / 点击都从房间实时读余额与可买性，不用退出重进；界面上的可买性与拒绝理由
  直接来自房间内核的同一套判定，不会出现「亮着可买、点了被拒」；
- 背包满了买不了（先腾出一格），不会出现扣了分、道具掉在地上的情况；
- 拆弹钳：背包里带着就生效（剪刀 + PDC 标记，主手/副手/背包任意位置都算），
  死亡时背包里的钳子被清空，**购买额度不退**；
- 地雷：拿到的是绊线钩，右键方块放下 → 该方块变石质压力板。引爆后原方块还原，
  爆炸本身零伤害、不破坏方块、不点火，只有 `kill-radius`（5 格）内的在册玩家被直接判定死亡。
  落点必须在对局场地范围内，且不能是包点方块、已有地雷的位置或箱子这类实体方块。

## 二、炸弹相位

包点用 `/taketori arena setbomb` 站在方块上划定，坐标存 `arenas.yml` 的 `bomb` 段，只有一格；
`/taketori arena delbomb` 取消。

| 相位 | 起点 | 结束 | 发生什么 |
| --- | --- | --- | --- |
| `FREE_TIME` | 开局 | `activation-delay-seconds` 到点 | 双方在出生点准备，包点还是原方块 |
| `DEFUSE` | 包点变 TNT | 拆开 / `defuse-time-limit-seconds` 到点 | 红队读条，蓝队守 |
| `BASE_FIGHT` | 超时结算 | 到目标分 / 时限 | 基地开放，占点 / 互杀 / 清月人继续计分 |

默认值下的时间轴：

```text
0s                  40s                    150s
|---- 自由时段 -----|---- 拆弹窗口 110s ----|---- 抢基地 ----→ 分出胜负
                    包点变 TNT              蓝队 +200 / 红队 +50
```

### 拆弹

| 动作 | 结果 |
| --- | --- |
| 红队站进包点 4.5 格内左键 | 开始 / 继续读条 |
| 右键包点 | 取消自己的读条 |
| 蓝队左键包点 | 只回一句「你是防守方，守住炸弹即可」 |
| 空手读满 | 10 秒（`defuse-seconds-bare`） |
| 背包里带着拆弹钳读满 | 6 秒（`defuse-seconds-clipper`） |
| 离开 4.5 格 / 死亡 / 掉线 / 换队 | 中断，进度保留 |
| 中断后 `reset-seconds`（7 秒）无人接手 | 进度归零，只提醒红队 |
| 读满 | 红队直接获胜，包点还原成原方块 |

进度按当前拆弹人累计：队友在 `reset-seconds` 内接手可以接着读。包点方块禁止被挖，
被外力换掉时每秒补回 TNT。

### 超时

`defuse-time-limit-seconds` 到点还没拆开 → 蓝队守住：蓝队 +`blue-keep-score`（200）、
红队 +`red-consolation-score`（50），两笔直接写队伍分（不套 `match.attacker-score-multiplier`），
随后基地开放，按正常规则打到目标分；保底分当场顶到目标分就立刻结算。

记分板在包点相位下多一行：`包点: 未激活` / `包点: 4s/10s` / `包点: 蓝队守住`。

没设包点的场地不进相位机，仍按「基地开放延迟 + 分数」判胜负。

## 三、加时赛

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `overtime.enabled` | true | `false` = 时间到直接按「防守方守住」结算，不投票 |
| `overtime.duration-seconds` | 60 | 加时时长 |
| `overtime.kill-score` | 1 | 每次击杀记几个人头 |

流程：

1. 时间到、双方都没到目标分 → 播报「比分清零，是否进入加时赛？」，双方队伍比分清零
   （个人分与击杀保留，结算播报与战绩记录照常）；
2. 30 秒投票窗口（硬编码，不可配）：`/taketori overtime` 或 `/taketori match overtime`；
3. 任一队全员同意，或某队已经没人时一人投票即通过 → 立刻开打；
4. 窗口到期没通过 → 按防守方守住包点结算（蓝队胜；蓝队已无人则平局）；
5. 加时纯 PVP：包点收掉、占点关闭，记分板显示「加时人头: 红 N : 蓝 M」；
6. 打满时长比人头：多者胜，相同判平局。

加时只改「怎么判胜负」，击杀回血、掉落、复活与个人分规则都不变。

## 四、配置键全表

```yaml
bomb:
  activation-delay-seconds: 40   # 开局多少秒后包点激活
  defuse-time-limit-seconds: 110 # 拆除时限
  defuse-seconds-bare: 10        # 空手拆除秒数
  defuse-seconds-clipper: 6      # 拿拆弹钳拆除秒数
  reset-seconds: 7               # 中断多少秒进度复位
  blue-keep-score: 200           # 超时后蓝队保留分
  red-consolation-score: 50      # 超时后红队安慰分
shop:
  npc:
    enabled: true
    red:  { name: '§c红队商人', world: '', x: 0, y: 0, z: 0, yaw: 0 }
    blue: { name: '§9蓝队商人', world: '', x: 0, y: 0, z: 0, yaw: 0 }
  defuse-clipper: { cost: 30, max-per-player: 2 }
  land-mine:      { cost: 40, max-per-team: 2, kill-radius: 5, trigger-radius: 3 }
overtime:
  enabled: true
  duration-seconds: 60
  kill-score: 1
```

`shop.npc.<team>.world` 留空 = 跟随大厅出生点所在世界；坐标全 0 = 用大厅出生点坐标。
`config-version` 12 起生效，老配置启动时自动补默认值并删掉退役的 `bomb.defuse-clicks`。

## 五、常见问题

**商人 NPC 不出现？** 依次查：`shop.npc.enabled` 是否为 `true`；`npc.<队>.world` 写的世界是否
存在；坐标是否全 0 而大厅又没设出生点（`/taketori lobby setspawn`）；控制台有没有一条提示用
`/taketori shop setnpc <队>` 设置位置的日志。改完 `/taketori reload`。

**NPC 被打死 / 区块重载后没了？** 不该发生：NPC 无敌且持久化，看护任务每 10 秒补回。
反复出现多半是别处也在生成同名实体，看控制台日志。

**包点没设置会怎样？** 不进相位机：记分板不显示包点行，本局仍按「基地开放延迟 + 分数」判胜负；
加时赛照旧可用。

**为什么我买不了？** 不在进行中的对局 / 积分不够 / 不是该道具的队伍（钳子只给红队、地雷只给蓝队）/
已达上限 —— 界面里那一格会写明是哪一条。

**拆弹钳死亡后没了？** 设计如此：死亡清空背包里的钳子，购买额度也不退，要再花 30 分买。

**地雷会炸到自己人吗？** 会。引爆只看「在册玩家进入触发半径」，不分队伍，自己人踩上去一样引爆，
引爆者也会被 5 格内的杀伤波及。
