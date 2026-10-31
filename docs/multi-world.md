# 多世界配置

本插件的房间是动态世界：玩家建房时把模板世界文件夹复制成独立世界，结算后删除。
本文覆盖世界生命周期、与 Multiverse 等多世界服务器的共存配置、以及排查方法。

## 一、世界结构

一个运行中的服务器最多存在四类世界：

| 世界 | 命名 | 生命周期 | 来源 |
| --- | --- | --- | --- |
| 大厅 / 主世界 | 任意（服务器已有） | 常驻 | 服主自建，`lobby.yml` 记录出生点 |
| 模板库存 | `plugins/TsukikageKassen/moonmaps/<模板名>/` | 常驻（不加载，只是文件夹） | `moonmap create/import` 或手动放入 |
| 模板编辑世界 | `k_tpl_<模板名>` | 编辑期间，unload 时写回库存 | `moonmap load` |
| 房间世界 | `room.world-prefix` + 序号（默认 `kassen_1`…） | 房间创建 → 结算/解散删除 | `room create` / 菜单 / 快速加入 / 状态牌 |

世界名合法字符：房间世界由插件生成，模板名限 `[a-z0-9_-]{1,32}`，导入的世界名限
`[A-Za-z0-9_-]{1,64}`。所有进入文件系统的名字都过白名单校验（`RoomManager.validatedMoonmapName`），
`..` 之类的目录穿越输入直接被拒。

## 二、模板生命周期

```text
moonmap create <名>            生成平坦模板 → moonmaps/<名>/
moonmap import <世界名> <名>   把服务器世界容器里的现有世界复制为模板
moonmap load <名>              复制到 k_tpl_<名> 并加载（编辑用）
moonmap unload <名>            保存并卸载编辑世界，写回 moonmaps/<名>
moonmap list                   列出库存模板与就绪状态（缺项点名）
```

约束与行为：

- **load 前必须 unload**：同名编辑世界已加载时 `load` 会把已在场玩家送回后重新拉起；
- **unload 前必须传出玩家**：世界内有玩家时 `unloadWorld` 必然失败：编辑流程
  （`arena setup done`）会自动把编辑世界内的玩家传送出去后再写回；
- 写回失败时编辑副本转存 `moonmap-recover/<名>`，不会被启动清理误删，
  下次 `load` 自动恢复；
- 复制模板为房间世界时跳过 `session.lock`、`uid.dat`（文件）与
  `playerdata`、`stats`、`advancements`（目录），见 `RoomManager.SKIP_COPY_FILES/_DIRS`；
- `pendingMoonmaps` 防止同模板并发加载。

## 三、房间世界生命周期

1. 创建（`RoomManager.createRoom`）：校验模板存在（世界文件夹预检：`level.dat`、`region/`、
   `entities/`、`data/` 任一存在即算世界，带 `dimensions/` 子目录的不算；缺 `level.dat` 的旧图
   会用服务端主世界那份补齐）与 `room.max-rooms` / `max-rooms-per-player` 上限 →
   异步复制模板文件夹 → 加载世界并应用规则 → 创建者传送进等待出生点；
2. 使用：等待区集结 → 开局 → 结算，全程独占该世界；
3. 回收（`destroyRoom`）：房内玩家先回大厅 → 从注册表移除 → 卸载世界（不保存）→
   异步删除世界文件夹。卸载失败按 5 秒间隔重试 5 次，仍失败则留待下次启动清理。

房间世界的 GameRule（`RoomManager.applyRoomWorldRules`）：关闭昼夜、天气、
自然刷怪、火焰蔓延、随机刻、生物破坏；开启 `keepInventory`；关闭死亡消息。
原版生物只来自插件刷怪逻辑（月人 / 守卫 / 小兵 / 召唤物）。

空房与残留清理：等待房全员离线超过 `room.empty-dispose-seconds`（默认 60s）
自动解散回收；启动与 reload 时 `cleanupStaleWorlds()` 删除崩溃遗留的
`kassen_*` 与 `k_tpl_*` 世界。

## 四、与其它多世界插件的共存

核心原则：插件只触碰自己创建的世界（`kassen_*`、`k_tpl_*`），其它世界一概不动。
Multiverse 只需要知道两件事：

1. 不要把 `kassen_*` / `k_tpl_*` 导入 Multiverse 管理：它们随对局生灭，
   导入后 MV 的缓存会指向已删除的世界；
2. 把本插件的大厅世界从 MV 的 respawn/传送接管里排除，避免进服拉扯冲突。

### 进服传送接管

`lobby.takeover-worlds`（config.yml）：控制「玩家从哪些世界进服会被送到大厅」。

| 配置 | 行为 |
| --- | --- |
| `[]`（默认） | 只接管大厅出生点所在的世界，其它世界进服位置不变 |
| `["*"]` | 所有世界进服都送大厅（旧行为） |
| `["world", "world_nether"]` | 显式白名单 |

### 播报范围

`worlds.broadcast-scope`（config.yml）：

| 配置 | 行为 |
| --- | --- |
| `world`（默认） | 对局播报只发对局世界，大厅播报只发大厅世界 |
| `all` | 全服广播（旧行为） |

判定逻辑在 `WorldScope.shouldReceive`（core 层纯函数，有离线单测 `WorldScopeTest`）。

### 状态牌与快速加入

大厅的实时房间状态牌（`/taketori lobby addstatus <模板id>`）跨世界可用：
牌子渲染文本每秒刷新，点击按房间阶段分流：等待/倒计时加入、进行中补位或旁观、
无房创建。旁观/观战入口对参赛者有守卫，对局中不会把自己切出战场。

## 五、排查

| 现象 | 原因与处理 |
| --- | --- |
| `unload` 报失败 / 世界删不掉 | 世界内有玩家或实体句柄未释放；编辑流程先传出玩家，
  房间流程重试 5 次。仍然失败则等下次启动 `cleanupStaleWorlds` 兜底 |
| 房间世界文件夹残留 | 上一种情况的落盘结果；下次启动自动清理，也可手动删除 |
| `moonmaps/<名>` 缺 `level.dat` | 旧图被删过 `level.dat`：`room create` 会先从服务端主世界拷一份补上；若仍被拒，说明这个目录连 `region/`、`entities/`、`data/` 都没有，不是世界文件夹 |
| 26.1 上模板导入/加载找不到世界 | Paper 26.1 把世界放在 `<服务端根>/dimensions/minecraft/<世界名>`；插件已按「已加载世界文件夹 → 维度目录 → 容器根」三级查找，若手动挪过世界请放回原位 |
| 从资源世界进服被拽到大厅 | `takeover-worlds` 配了 `*` 或把该世界列进了白名单 |
| 对局播报刷到别的世界 | `broadcast-scope` 配了 `all` |
| `k_tpl_*` 世界躺在服务器目录 | 编辑中途崩了；下次启动被当残留清掉，库存模板不受影响 |
| MV 控制台报世界不存在 | 把 `kassen_*` 导入了 MV；移除即可 |
