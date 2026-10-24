# 战国 3v3 配置

战国 3v3 是独立于 PVP / PVE 的第三种模式：三局两胜，每小局先攻陷敌方天守阁者胜。
本文面向开发者，覆盖模式的所有配置文件、点位划定指令与运行时组件的对应关系。

前置阅读：[区域配置](regions.md)（选区工具与点位指令的公共部分）。

## 一、文件与职责

| 文件 | 职责 | 代码入口 |
| --- | --- | --- |
| `sengoku.yml` | 赛制与天守阁 | `SengokuRules` / `SiegeRules`（keep 段） |
| `sengoku-towers.yml` | 箭楼数值与守卫 | `TowerRules` / `TowerManager` / `TowerCaptureManager` |
| `sengoku-siege.yml` | 击破器与跳跃台 | `SiegeRules` / `SiegeBreakerManager` / `JumpPadManager` |
| `sengoku-minions.yml` | 中地小兵 | `MidMinionRules` / `MidMinionManager` |
| `sengoku-energy.yml` | 能量槽与必杀技 | `EnergyRules` / `EnergyManager` |
| `arenas.yml` 的 `sengoku` 段 | 全部点位（区域与坐标） | `SengokuMapDef` |

规则文件不会被插件覆盖（`config-version` 只用于提示模板落后），改完执行
`/taketori reload` 生效。所有数值类在加载时都会做防御性规整（`best-of` 偶数进一、
秒数负数兜 0 等），不会因写错卡死赛制。

## 二、赛制（sengoku.yml → rounds）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `best-of` | 3 | 整场赛制。3 = 三局两胜、5 = 五局三胜；偶数自动进一（2→3），0/负数兜 1，上限 9 |
| `time-limit-minutes` | 8 | 单小局时限，到点按 `timeout-winner` 判定 |
| `timeout-winner` | `tower-count` | `tower-count` = 占领箭楼数多者胜、持平判平局重开；`draw` = 直接平局重开 |

小局状态机在 `SengokuSession`。平局重开**不推进局号**（`replayingRound` 标志），
`SengokuScore` 记分也不把平局算进局数。整场胜负由 `score.matchWinner(bestOf)` 判定，
打满局数仍未分胜负时按 `isFinished` 兜底收尾。

keep 段（天守阁）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `keep.invulnerable` | true | 天守阁永久不可破坏，唯一陷落方式是击破器读条 |
| `keep.arm-radius` | 4.0 | 击破器读条的判定半径。实际判据是「进入天守阁区域」，
  半径只是区域边界的**外扩容差**（贴墙站不算出界），不是球心距离——区域大时
  球心距判定会退化，见 `SiegeBreakerManager.insideKeep` |
| `keep.protect-blocks` | true | 拦截天守阁区域内的方块破坏与爆炸 |

## 三、点位划定（arenas.yml → sengoku 段）

点位全部用选区 / 指令写入 `arenas.yml`，运行时由 `SengokuMapDef` 读写。
每一座箭楼的三个点位必须**同编号**：

| 点位 | 指令（在编辑世界内、选好区后） | 类型 |
| --- | --- | --- |
| 天守阁区域 | `/taketori sengoku setkeep <red\|blue>` | `CuboidRegion` |
| 天守阁门前点 | `/taketori sengoku setkeepdoor <red\|blue>` | `Point`（击破器/跳跃台生成基准） |
| 箭楼占领区 | `/taketori sengoku settower <编号>` | `CuboidRegion` |
| 箭楼铜钟 | `/taketori sengoku setbell <编号>` | `Point`（方块交互点） |
| 箭楼守卫刷新点 | `/taketori sengoku setguard <编号>` | `Point` |
| 中地小兵刷新区 | `/taketori sengoku setmid [编号]` | `CuboidRegion`（至少 1 个） |
| 跳跃台落点 | `/taketori sengoku setjumppad <red\|blue>` | `Point` |

对应删除指令为 `delkeep / delkeepdoor / deltower / delbell / delguard / delmid /
deljumppad / delall`。

**开局就绪判定**（`SengokuMapDef.missingHint`，`/taketori sengoku check` 也可看）：
双方天守阁区域 + 门前点 + 跳跃台、`tower-rules.count` 座箭楼的占领区/铜钟/守卫点、
至少一个中地小兵刷新区，**任缺其一不能开局**，`beginMatch` 会列出缺项。
编辑流程：`/taketori arena setup <场地id>` 进入编辑世界 → 划点位 →
`/taketori arena setup done` 写回模板。

## 四、箭楼（sengoku-towers.yml）

`towers` 段：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `count` | 2 | 箭楼座数，决定就绪判定要求的编号范围 |
| `capture-mode` | `channel` | `channel` = 敲钟后读条；`instant` = 敲钟即占领（`ringBell` 内直接易主，tick 不参与） |
| `capture-seconds` | 5.0 | 读满秒数；≤0 等同 instant |
| `decay-per-second` | 0.5 | 无人读条时的进度衰减；0 = 读一半跑掉也算数 |
| `contest-lock` | true | 双方同场时是否互锁 |
| `guard-respawn-seconds` | 20 | 守卫全灭后的重刷间隔 |

`contest-lock` 的精确语义（实现：`TowerContest.advanceFor`，纯逻辑可离线单测）：

- 双方同场且 `contest-lock: true` → 双方进度**原样停住**，不清零；
- 双方同场且 `contest-lock: false` → **两队各自独立读条**，先读满的一队易主；
  同一 tick 双双读满时按进度、再按红队先手结算（`TowerCaptureManager.tick`）；
- 一方在场一方不在 → 在场方推进，离场方进度**保持不衰减**（否则两人轮流进区
  会让读条永远推不动）；
- 都不在 → 按衰减衰减；
- 读满即占领，占领后守卫到点重刷，下一次争夺需重新清守卫 + 敲钟。

守卫（`guards` 段）：`ox-demon`（牛鬼，HUSK）与 `shrimp-crab`（虾兵蟹将群，
VINDICATOR）各自配 `entity / count / health / damage / speed-amplifier /
patrol-radius / aggro-radius`。`aggro-radius` 是守卫的主动索敌半径
（`TowerManager.retargetGuards`，每秒一次）。守卫跨区块卸载的存活判定有约 10 秒
宽限期，小局重置时按 PDC 标记 `towerGuardIndex` 全场清扫，不会跨局残留。

铜钟（`bell` 段）：`material`（默认 BELL，右键触发）、`sound`、`particle`。
敲钟前置是「该塔守卫已清空」（守卫活着时敲钟返回 `sengoku.tower-guards-alive`），
已激活的塔重复敲钟不算错。

## 五、击破器与跳跃台（sengoku-siege.yml）

连锁事件：己方占领任一箭楼 → 敌方天守阁门前生成**大将击破器**、己方门前激活
**跳跃台**（`TowerManager.onCaptured` → `siege().ensureBreaker` / `jumpPads().ensurePad`）。

`siege-breaker` 段关键项：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `material` / `name` / `glow` | NETHER_STAR | 物品外观；归属写在 PDC `sengoku_breaker_team` |
| `spawn-distance-from-keep` | 3.0 | 相对门前点向「远离天守阁中心」方向外推的格数 |
| `pick-radius` | 2.0 | 归属队玩家自动拾取半径（自行判定，不依赖原版拾取） |
| `arm-time-seconds` | 8.0 | 进敌方天守阁范围后的读条秒数；≤0 = 进圈即攻陷 |
| `one-per-team` | true | 每队同时一枚。地上 / 背包 / 掉落全部计入追踪 |
| `respawn-on-recapture` | true | 再占领时自动补齐被消耗的击破器；false 时每队每小局只发一次 |
| `allowed-characters` | 空 | 允许携带的角色 id；空 = 全部。不允许的角色进圈不推进并收到 `breaker-role-blocked` |
| `indestructible` / `undroppable` | true | 不可破坏（含爆炸/岩浆/仙人掌）与不可丢弃。虚空销毁无事件可拦，靠生成点落点校正规避 |

读条进度按携带者逐人记录（`progress`），中断条件：离开范围、死亡、退房，
进度清零不保留。读满即 `onRoundEnd(BREAKER_ARMED)` 结束本小局，用时取
`roundElapsedSeconds()`。小局重置时背包内与地上的击破器统一回收。

`jump-pad` 段：`mode`（`teleport` 直接传送 / `launch` 弹射）、`target`
（`nearest-captured-tower` 最近己方箭楼 / `specified` 固定 `specified-tower`）、
`cooldown-seconds`、`launch.power/upward`、`fall-immunity-ticks`（落地免摔窗口）、
`resistance-ticks`（落地抗性）。

## 六、中地小兵（sengoku-minions.yml）

设计约束：小兵**可以被绕过**（不堵路），击杀不计对局积分，只给能量。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `entity` / `display` | ZOMBIE | 载体实体与头顶名字 |
| `interval-seconds` / `per-spawn` | 6 / 4 | 刷新节奏 |
| `max-alive` | 40 | 场上上限，性能兜底 |
| `shard-tick` | 4 | 一轮刷新摊到 N 个 tick；实际夹到不超过刷新区数量 |
| `health` / `damage` | 20 / 4 | 数值 |

刷新区来自 `sengoku.mid-minion-regions`，多区之间轮转，按 `shard-tick` 分片。

## 七、能量与必杀（sengoku-energy.yml）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `max` | 100 | 能量上限，满值才能放必杀 |
| `per-minion` / `per-elite-minion` | 8 / 20 | 击杀回能量 |
| `display` | actionbar | `actionbar` / `bossbar` / `none`。BossBar 留给小局比分，默认动作栏 |
| `ultimate-slot` | q | 必杀占用的输入槽；当前只支持 `q`（`usesQSlot()`，配别的值 = 必杀不接管 Q） |

必杀与 Q 原行为的关系：能量**未满**时本文件完全不干预，Q 走 `config.yml`
`input.q-mode`；能量满时 Q 被必杀接管（`EnergyManager.tryUltimate`，在
`InputListener.onDrop` 中先于潜行-Q 第三槽与 q-mode 分支判定），放完立即清空。

`ultimate` 段：`default` 全员统一，逐角色键覆盖（键 = 角色 id，如 `tsukuyomi`）。
当前效果族为 `shockwave` 同构：`damage / radius / knockback / launch /
slow-duration / slow-amplifier / particle / sound`。命中判定走
`SkillTargets.enemiesInRadius`（统一目标过滤：受保护队友 / 召唤物 / 观战者 /
笼内玩家全部跳过），伤害带内部标记，不会触发近战改写。

## 八、运行指令

| 指令 | 说明 |
| --- | --- |
| `/taketori sengoku check` | 点位完整性检查（等价 `missingHint`） |
| `/taketori sengoku start` | 强制立即开局（跳过等待） |
| `/taketori sengoku pause` / `resume` | 暂停 / 恢复小局计时；只对进行中的小局生效 |
| `/taketori sengoku endround <red\|blue\|draw>` | 管理员直接判定当前小局 |
| `/taketori sengoku towers` / `breaker` / `jumppad` | 查看运行时状态 |
| `/taketori sengoku mode <pvp\|pve\|sengoku_3v3>` | 切换**全局默认模式**（写回 config.yml，只影响之后新建的房间）；改单个在跑的房间用 `/taketori match mode <模式> [房间id]` |

小局计时与组件 tick 都只在 `GameRoom.isRunning()`（PLAYING）时运转；玻璃笼阶段
（CAGED）不刷怪、不读条、不积能量。

## 九、常见排查

- **开局报「战国点位未就绪」**：`/taketori sengoku check` 看缺项；编号必须连续
  且与 `towers.count` 对齐；
- **敲钟没反应**：守卫没清完（会收到提示）或铜钟点位与实际方块不一致；
- **占领读条不动**：`contest-lock: true` 且双方都在区内是预期行为；单人在场仍不动
  查 `decay-per-second` 与读条秒数配置；
- **击破器消失**：查是否被带进未回收区域、`one-per-team` 是否被补齐逻辑重置；
  掉出虚空无事件可拦，属已知边界。
