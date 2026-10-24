# 区域配置

场地的全部点位（区域与坐标）存在 `plugins/TsukikageKassen/arenas.yml`，
由选区工具 + 指令写入，运行时由 `ArenaDef` 读写。本文覆盖两种选区工具、
全部区域类型、划点指令与存储结构。战国专用点位见 [战国 3v3](sengoku-3v3.md)。

## 一、选区工具

两种工具都绑定在特定物品上，只有持有者有 `taketori.admin` 权限才生效：

| 工具 | 物品（config.yml `setup-wand.*`） | 操作 |
| --- | --- | --- |
| 场地选区锄 | `NETHERITE_HOE` | 左键 = 角点 1，右键 = 角点 2，潜行+左键 = 清空选区，潜行+右键 = 查看选区 |
| 道具点工具 | `STRUCTURE_VOID`（`loot-material`） | 同上，专用于道具刷新点划区 |

`setup-wand.outline-particles: true` 时手持工具会粒子描边；`give-on-join: true`
时管理员进服自动发放。等价指令：`/taketori arena pos1` / `pos2` / `clearselection`。
选区为两点构成的长方体（`CuboidRegion`），与当前世界绑定。

## 二、划点指令（/taketori arena …）

编辑对象是「当前选中的场地」（`/taketori arena select <id>`），在**模板编辑世界**
里操作（`/taketori moonmap load <名>` → `/taketori arena setup <名>` 一条龙）。

| 指令 | 写入 | 说明 |
| --- | --- | --- |
| `setminion [编号] [kind]` | `minion-regions.<n>` | 月人刷新区。`kind: normal\|mixed`（默认 mixed） |
| `setbase <red\|blue> [编号]` | `bases.<队>.<n>` | 基地占点区。编号受 `base.count-per-team` 约束 |
| `setspawn <red\|blue>` | `spawns.<队>` | 队伍出生点（开局传送 + 笼子中心），以玩家当前位置写入 |
| `setwait [半径]` | `wait-spawn` 或 `wait-region` | **有选区 = 整个选区设为等待区**（加入者随机分布）；无选区 = 以当前位置为等待单点（向后兼容） |
| `setloot <编号>` | `loot-regions.<n>` | 对局内道具刷新点（区域） |
| `setoutpost` / `deloutpost` | `outpost` | PVE 保卫据点位置（单点） |
| `delminion <n>` / `delbase <队> <n>` / `delloot <n>` | — | 删除对应编号 |

`setwait` 的两种形态影响等待体验：区域形态下加入者在区域内 `randomLocation`
分布，虚空拉回用区域中心；单点形态则全部站在 `wait-spawn`。虚空判定阈值：
Y 低于「等待点 Y + `waiting.void-y-offset`（默认 -10）」自动拉回。

编辑收尾：`/taketori arena setup done` 写回模板世界并保存 arenas.yml；
`cancel` 放弃。**进行中的对局不受影响**——点位属于模板，下一次建房生效。

## 三、区域类型与运行时语义

| 区域 | 运行时类 | 语义 |
| --- | --- | --- |
| 月人刷新区（normal） | `MinionSpawner` | normal 循环专属；只刷普通月人 |
| 月人刷新区（mixed） | `MinionSpawner` | mixed 循环；普通+精英。两类循环独立计时（`minion.normal/mixed.*`） |
| 基地（per team） | `BaseCaptureManager` | PVP 占点拆家，进度/衰减见 `base.*` |
| 等待区 / 等待点 | `GameRoom` / `WaitingListener` | 集结、倒计时、完全保护（`waiting.protect`）、虚空拉回 |
| 道具刷新点 | `LootSpawner` | 按 `loot.*` 配置周期刷道具，`max-drops` 全场合计 |
| PVE 据点 | `OutpostManager` | 雪傀儡位置，未划则取月人刷新区 1 号中心 |
| 战国点位 | `SengokuMapDef` | 天守阁 / 箭楼 / 铜钟 / 守卫点 / 中地小兵区 / 跳跃台，见战国文档 |

道具刷新池在 config.yml `loot.items`：原版物品写 `material + amount`，
插件武器写 `weapon: <武器id>`，`weight` 控制权重。武器绑定了角色，只有对应
角色能捡起使用，默认池只放原版道具。

每场地覆盖刷新节奏：`minion-spawn` 段（`interval-seconds` / `per-spawn` /
`max-alive`），未配置的项回落 config.yml 的 `minion.*` 全局默认。

## 四、arenas.yml 存储结构

每个场地一段 `arenas.<id>`，键名与 `ArenaDef.write` 一一对应：

```yaml
arenas:
  tsukuyomi:
    display: ...
    enabled: true
    minion-regions:            # 月人刷新区，编号为键
      1: { world: ..., x1: ..., kind: mixed }
      2: { ..., kind: normal }
    loot-regions:
      1: { ... }
    bases:
      red:  { 1: {...}, 2: {...}, 3: {...} }
      blue: { 1: {...}, ... }
    spawns:                    # 队伍出生点
      red:  { world, x, y, z, yaw, pitch }
      blue: { ... }
    outpost: { ... }           # PVE 据点（可选）
    wait-spawn: { ... }        # 等待单点（与 wait-region 二选一）
    wait-region: { ... }       # 等待区域（有选区时写入）
    minion-spawn:              # 每场地刷新节奏覆盖（可选）
      interval-seconds: 9
      per-spawn: 3
      max-alive: 15
    sengoku:                   # 战国点位（见战国文档，非战国地图无此段）
      keeps / keep-doors / towers / bells / guard-spawns /
      mid-minion-regions / jump-pads
```

区域条目是 `CuboidRegion.write` 的世界名 + 两角点；单点是 `Point.write` 的
世界名 + 坐标 + 朝向。`copyForWorld` 会在复制模板为房间世界时把所有区域
重定向到新世界名。手工编辑 arenas.yml 后 `/taketori reload` 生效，但建议
走指令——区域合法性（两点世界一致、编号连续）由写入路径保证。

## 五、就绪判定与排查

`/taketori arena list` 显示每个模板的就绪状态；判定口径：

- **PVP/PVE**（`ArenaDef.missingHint`）：双方出生点 + 等待出生点 +
  双方基地配满 `base.count-per-team`（auto 模式下每队至少 1 个）+ 至少一个
  月人刷新区（PVE 还要求据点可用，未划自动回落刷新区中心，不算缺）；
- **战国**（`SengokuMapDef.missingHint`）：见战国文档第三节。

| 现象 | 原因与处理 |
| --- | --- |
| 开局报「场地未就绪」 | `arena list` 看缺项；基地编号必须从 1 连续 |
| 月人不刷 | 区域 kind 与 `minion.normal/mixed.enabled` 对不上（全 normal 且 mixed 关了 → normal 循环照跑；两类都关 → 无怪） |
| 等待者站进地形里 | `setwait` 用的单点落在方块内；改用选区形态让玩家随机分布 |
| 道具点不刷 | `loot.enabled` 关了、编号超 `max-drops` 或刷新池为空 |
| 选区锄点了没反应 | 非管理员（1.6.1 起普通玩家持锄不禁挖也不响应）、`setup-wand.enabled: false` 或不在编辑世界 |
