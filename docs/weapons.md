# 武器配置

武器定义在 `plugins/TsukikageKassen/weapons.yml`（模板：`src/main/resources/weapons.yml`，
`config-version` 当前 10）。本文是开发者参考：schema、技能类型与参数键全表、
校验行为与数值基准。改完执行 `/taketori reload`；启动与 reload 都会跑参数键校验。

## 一、结构

```yaml
weapons:
  <武器id>:                      # 小写字母数字下划线，绑定角色（character 键）
    character: tsukuyomi         # 角色 id（characters.yml）
    display: '<gold>陨星锤</gold>' # MiniMessage
    material: DIAMOND_SHOVEL     # 原版 Material
    model-data: 0                # CustomModelData，0 = 不设
    lore: [...]                  # 物品说明（建议写清 左键/右键/双击潜行/被动）
    attributes:                  # 原版属性名（连字符写法）
      attack-damage: 16.0
      attack-speed: 1.0
    enchantments:                # 可选：物品附魔（如三叉戟的忠诚）
      loyalty: 3
    passive:                     # 武器级被动，不占按键槽位
      type: passive
      params:
        display: '陨星之重'
        speed: 0.05              # 常驻移速 +5%
        on-hit: armor_break      # 命中触发：mark / armor_break / slow / lifesteal / knockback
        chance: 0.15             # 近战命中概率
        pierce: 0.15
        ticks: 60
    skills:
      left:  { type: ..., cooldown: ..., params: {...} }   # 左键：近战留空 = 原版普攻；弓放主动
      right: { type: ..., cooldown: ..., params: {...} }   # 右键：留空 = 原版拉弓 / 投掷 / 格挡
      sneak: { type: ..., cooldown: ..., params: {...} }   # 双击潜行：只允许移动类
```

- **三槽模型（2.0）**：`left` / `right` / `sneak`，`shift-right`、`q`、`modes`、`default-mode`
  全部下线；写旧键会在加载时点名报错。
- **被动**：`passive` 的 `type` 必须是 `passive`；`chance` 是近战概率、`throw-chance` 是
  箭矢 / 投掷概率（未写时回落到 `chance`）。三叉戟就是靠 `chance: 0.25` + `throw-chance: 1.0`
  实现"投掷必定霜噬、近战只有 25%"。
- **sneak 白名单**：只允许 `self_boost` / `blink` / `rocket_jump` / `grapple`，其它类型会被校验拦下。
- **right 缺失**：只有 `BOW` / `TRIDENT` 允许（走原版拉弓 / 投掷）；弓必须写 `left`。
- 属性名映射：`attributes` 的键经 `VersionAdapter.attribute()` 解析
  （`attack-damage` → `generic.attack_damage` 等），解析失败会被校验点名；
- 冷却语义：`cooldown` 单位秒，只有 `SkillResult.SUCCESS` 才进冷却：
  无目标 / 抛异常都不扣。冷却展示见 `feedback.cooldown-display`。

## 一·五、2.0 武器一览（三技能 + 被动）

| 武器 | 被动 | 右键（主动） | 双击潜行（移动） |
| --- | --- | --- | --- |
| 陨星锤 | 移速 +5% / 15% 破甲 | 砸地冲击 `shockwave` 8s | 火箭跳 `rocket_jump` 6s |
| 镇魂铃 | 移速 +5% / 20% 易伤 | 月华庇护 `deploy_zone` 22s | 疾行 `self_boost` 8s |
| 金棒 | 抗性 I / 20% 破甲 | 掷棒 `projectile` 5s | 冲锋 `self_boost` 8s |
| 大太刀 | 移速 +3% / 25% 减速 II | 斩击波 `projectile` 4s | 瞬步 `blink` 9s |
| 剑 | 移速 +8% / 20% 易伤 | 剑气 `shockwave` 7s | 高速突进 `self_boost` 7s |
| 钢丝 | 15% 减速 I | 钢丝牵引 `pull` 6s | 快速位移 `blink` 4s |
| 苦无 | 移速 +5% / 20% 易伤 | 苦无投掷 `projectile` 7s | 影步 `blink` 8s |
| 弓 | 箭命中 25% 易伤（左键 = 瞄准射击 `mark_apply` 10s） | —（原版拉弓） | 疾跑 `self_boost` 8s |
| 短匕 | 移速 +10% / 20% 减速 I | 飞匕 `projectile` 1.2s | 闪身位移 `blink` 9s |
| 大盾 | 抗性 I / 15% 减速 I | 壁垒 `shield_guard` 22s | 盾冲 `self_boost` 8s |
| 壁垒令旗 | 抗性 I / 15% 易伤 | 旗阵 `shield_guard` 18s | 冲锋 `self_boost` 8s |
| 月镜 | 抗性 I / 20% 破甲 | 镜光爆发 `mirror_burst` 12s | 瞬镜 `blink` 8s |
| 冰棘鱼（三叉戟） | 投掷必定 / 近战 25% 霜噬，+2 点霜冻伤害 | —（原版投掷 + 忠诚 III） | 寒冰滑步 `blink` 9s |
| 铁牙 | 20% 吸血 | 咬合拖拽 `pull` 7s | 扑咬冲刺 `self_boost` 10s |
| 铁锁 | 15% 减速 II | 看门犬咆哮 `shockwave` 13s | 钩索位移 `grapple` 9s |
| 铁牙·改 | 20% 易伤 | 唤犬 `summon_ally` 45s | 冲锋 `self_boost` 8s |
| 影铳 | 15% 破甲 | 精准射击 `projectile` 1.6s | 侧步 `blink` 8s |
| 影镜 | 20% 减速 I | 减速弹 `projectile` 4s | 闪现 `blink` 8s |
| 吉野之苇 | 移速 +6% / 20% 易伤 | 风刃 `projectile` 2.4s | 乘风 `self_boost` 8s |
| 云丝 | 20% 减速 I | 丝网陷阱 `deploy_zone` 14s | 闪身 `blink` 7s |

## 二、技能类型与参数键全表

参数键的权威来源是 `ConfigValidator.ALLOWED_PARAMS`：写在 yml 里但没收录的键
会在启动/reload 时被逐条点名（这是护栏：防止配置键静默失效）。
下表按类型列出全部合法键，`display / particle / sound / hit-particle / hit-sound /
miss-sound / slow-type` 等表现键各类型通用，不再重复列出。

### 近战与范围伤害

| 类型 | 参数键 |
| --- | --- |
| `melee_smash` 近战扇形 | `damage` `range` `knockback` `angle` `min-charge` `combo-bonus` `combo-window` `combo-cap` `freeze-ticks` `slow-duration` `slow-amplifier` |
| `shockwave` 360° 冲击 | `damage` `radius` `knockback` `launch` `min-falloff` `min-charge` `freeze-ticks` `slow-duration` `slow-amplifier` `armor-pierce` `debuff-ticks` `debuff-stacks` `mark-bonus` `mark-ticks` |
| `echo_consume` 兑现近战 | `damage` `range` `angle` `knockback` `min-charge` `echo-bonus` `slow-duration` `slow-amplifier` |
| `mirror_burst` 范围压制 | `radius` `damage` `slow-duration` `slow-amplifier` `blindness-ticks` `echo-bonus` |
| `mark_apply` 易伤标记 | `damage` `radius` `angle` `mark-bonus` `mark-ticks` `invisible-ticks` `slow-duration` `slow-amplifier` |
| `armor_break` 破甲 | `damage` `radius` `knockback` `launch` `min-falloff` `armor-pierce` `debuff-ticks` `debuff-stacks` `slow-duration` `slow-amplifier` |

### 被动（passive）

`passive` 不是按键技能，写在武器顶层，由 `PassiveListener` 在命中时结算：

| 参数键 | 说明 |
| --- | --- |
| `display` | 显示名（`/taketori skills` 与 doctor 输出用） |
| `speed` / `resistance` / `jump` | 常驻属性（移速比例 / 抗性等级 / 跳跃等级），换手时刷新一次 |
| `on-hit` | 命中效果：`mark` / `armor_break` / `slow` / `lifesteal` / `knockback`（也认中文别名） |
| `chance` / `throw-chance` | 近战 / 箭矢与投掷的触发概率；`throw-chance` 缺省回落到 `chance` |
| `mark-bonus` / `pierce` / `ticks` | 易伤强度 / 破甲强度 / 效果持续 tick |
| `slow-amplifier` / `slow-duration` | 减速等级与时长（时长缺省用 `ticks`） |
| `lifesteal` / `bonus-damage` | 吸血比例 / 命中附加伤害（点） |
| `particle` / `sound` | 命中特效 |

连击（`melee_smash` 的 `combo-*`）：命中目标才叠层（1.6.1 起，空挥不计数），
`combo-window` 是层间最大间隔 tick，`combo-cap` 是伤害上限（0 = 不封顶）。

### 远程与弹体

| 类型 | 参数键 |
| --- | --- |
| `projectile` 弹体 | `projectile` `speed` `spawn-offset` `gravity` `damage` `radius`（溅射） `ignite` `ignite-ticks` `freeze-ticks` `slow-duration` `slow-amplifier` `knockback` `min-falloff` `homing-strength` `homing-range` `echo-bonus` `mark-bonus` `mark-ticks` `armor-pierce` `armor-pierce-ticks` |
| `special_shot_toggle` 特殊射击开关 | `damage-multiplier` `speed-multiplier` `volley-spread` `volley-interval` `mark-bonus` `mark-ticks` `hit-radius` `hit-radius-ratio` |

弹体伤害统一在 `ProjectileListener` 结算（原版伤害事件层取消），命中过滤与
homing 目标过滤同口径（统一目标过滤）。无箭射击（夕月）在 `CombatListener`
右键接管，250ms 限频。

### 位移

| 类型 | 参数键 |
| --- | --- |
| `rocket_jump` 爆炸位移 | `upward` `backward` `min-lift` `boost-ticks` `boost-keep-ratio` `boost-mode` `potion-boost` `potion-ticks` `potion-speed` `potion-jump` `potion-slow-falling` `potion-levitation` `potion-levitation-amplifier` `potion-levitation-ticks` `cancel-flight` `self-damage` `fall-immunity-ticks` |
| `self_boost` 推进 | 同 `rocket_jump`（少 `backward`/`self-damage`，多 `power`） |
| `blink` 瞬步 | `distance` `min-distance` `reserve-damage` `reserve-radius` `reserve-slow-duration` `reserve-slow-amplifier` |
| `grapple` 钩爪 | `range` `power` `upward` |
| `pull` 双向牵引 | `mode`（auto/target/self） `range` `power` `duration` `angle` `damage` `decelerate-amplifier` `decelerate-ticks` |

位移类 `boost-mode` 三条路径（见 weapons.yml 头部注释）：

- `potion`（默认）：纯药水（SPEED/JUMP/LEVITATION），原版效果不受反作弊影响；
  原版没有水平位移药水，水平表现为跑得更快；
- `teleport`：逐 tick 传送，位移确定但观感硬，可能被反作弊/领地拦截；
- `velocity`：`setVelocity` 最平滑，但速度可能被位置纠正清除。

### 防御与控制

| 类型 | 参数键 |
| --- | --- |
| `shield_guard` 护盾架势 | `duration-ticks` `resistance-amplifier` `absorption` `reflect-ratio` `self-slow-amplifier` `self-slow-ticks` `ally-radius` `ally-absorption` `ally-resistance-amplifier` `armor-pierce` |
| `reflect` 镜面 | `duration-ticks` `speed-multiplier` `absorption` `reflect-ratio` |
| `mirror_skill` 镜面模式 | 同 `reflect`（少 `speed-multiplier`） |
| `mode_switch` / `equip_switch` | 仅表现键（`target` 供 equip_switch） |

### 场地与召唤

| 类型 | 参数键 |
| --- | --- |
| `deploy_zone` 场地技能 | `radius` `duration-ticks` `period-ticks` `damage` `slow-duration` `slow-amplifier` `heal-per-second` `absorption` `ally-radius` |
| `summon_ally` 召唤单位 | `entity` `count` `health` `damage` `duration-ticks` `speed-amplifier` `spawn-distance` `name` |

`deploy_zone`：对敌周期伤害+减速，对友治疗+首次进入给吸收（`ally-radius > 0` 时
含施法者）；参数在施法瞬间快照，热重载对已部署的领域不生效。领域只在 PLAYING
阶段结算。吸收（金心）全插件统一封顶 20 点。

`summon_ally`：召唤物带 PDC 归属标记（`summoned_owner`），主人与受保护队友不能
伤害它、击杀不计分；到期 / 房间销毁统一回收。每人一个领域/召唤（再放顶旧的）。

## 三、机制键语义（跨类型）

| 键 | 语义 |
| --- | --- |
| `mark-bonus` / `mark-ticks` | 易伤标记：目标受到的所有伤害 ×(1+mark-bonus)。覆盖语义，不叠加 |
| `armor-pierce` / `debuff-*` | 破甲：独立于易伤标记的第二乘区，可共存；硬上限 0.30 |
| `echo-bonus` | 兑现倍率：目标带标记 / 破甲 / 减速 / 冻结任一时伤害 ×echo-bonus；≤1.0 等同关闭 |
| `fall-immunity-ticks` | 免摔窗口（覆盖式写入，`CombatStates` 惰性过期） |
| `freeze-ticks` | 定身（SLOWNESS 拉满 + 负跳跃，纯药水实现） |
| `min-falloff` | 距离衰减下限（0.6 = 边缘目标至少吃 60% 伤害） |

伤害公式与穿甲基准见下节；`echo-bonus` 与 `mark-bonus` 是独立乘区相乘，
这是设计行为不是双重结算，不要"修"。

## 四、数值基准

目标是「穿全套铁甲的玩家」（默认 `loadout.armor-material: IRON` + 保护 II）：

```text
铁甲 15 点护甲 → 减伤 15/(15+8) ≈ 65.2%  →  实际伤害 ≈ 配置伤害 × 0.35
反推：想要对铁甲 N 击击杀（20 血）→ 配置伤害 ≈ 20 / N × 2.87
```

例：想 3 刀击杀铁甲目标 → 配置伤害 ≈ 19.1。

## 五、校验与排查

| 校验 | 内容 | 失败表现 |
| --- | --- | --- |
| 参数键（`checkParamKeys`） | 技能 params 必须被 `ALLOWED_PARAMS` 收录 | 启动/reload 控制台逐条点名 |
| 属性名 | `attributes` 经 VersionAdapter 解析 | `weapons.yml [id] attributes … 无法解析` |
| 材质 / 模式 | `material` 可解析、`default-mode ∈ modes` | reload 报错，武器回退或拒绝 |
| 技能类型 | `type` 已注册（`SkillRegistry`） | `技能类型未注册` |

排查工具：`/taketori doctor`（识别、绑定、名字解析一次性报告）、
`/taketori keys`（按键是否到达服务端）、`/taketori debug on`（输入与技能派发日志）。
游戏中改武器数值可用 `/taketori editor`（菜单编辑，保存自动 reload）或直接改
weapons.yml 后 `/taketori reload`：对局进行中 reload 会结束所有房间，注意时机。

模板升级：插件不会覆盖你的 weapons.yml；模板落后时控制台提示
`config-version`，同步方式：备份并删除该文件 → `/taketori reload` 重新生成 →
回填自己的数值。
