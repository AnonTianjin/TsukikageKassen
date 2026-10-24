# 武器配置

武器定义在 `plugins/TsukikageKassen/weapons.yml`（模板：`src/main/resources/weapons.yml`，
`config-version` 当前 10）。本文是开发者参考：schema、技能类型与参数键全表、
校验行为与数值基准。改完执行 `/taketori reload`；启动与 reload 都会跑参数键校验。

## 一、结构

```yaml
weapons:
  <武器id>:                      # 小写字母数字下划线，绑定角色（character 键）
    character: tsukuyomi            # 角色 id（characters.yml）
    display: '<gold>陨星锤</gold>' # MiniMessage
    material: DIAMOND_SHOVEL     # 原版 Material
    model-data: 0                # CustomModelData，0 = 不设
    lore: [...]                  # 物品说明
    attributes:                  # 原版属性名（连字符写法）
      attack-damage: 16.0
      attack-speed: 1.0
    default-mode: HAMMER         # 默认 Q 模式（大写，须存在于 modes）
    modes:
      <模式名>:                  # 大写；Q 切换在这些模式之间轮转
        display: '<yellow>重锤模式</yellow>'
        skills:
          left:  { type: ..., cooldown: ..., params: {...} }   # 左键
          right: { type: ..., cooldown: ..., params: {...} }   # 右键
          # shift-right：第三槽技能（触发方式 config.yml input.shift-right-trigger）
          # q：模式切换（mode_switch）/ 装备切换（equip_switch）
```

- **属性名映射**：`attributes` 的键经 `VersionAdapter.attribute()` 解析
  （`attack-damage` → `generic.attack_damage` 等），解析失败会被校验点名；
- **`attack-speed` 与近战手感**：左键技能的结算频率由原版攻击冷却驱动，
  `combat.melee-charge-gate`（默认 0.9）以下的蓄力保留原版轻击；
- **`default-mode`**：写错（不存在于 `modes`）时 reload 报错。此前会静默回退
  武器级技能，1.6.1 起显式校验；
- **冷却语义**：`cooldown` 单位秒，只有 `SkillResult.SUCCESS` 才进冷却——
  无目标 / 被门控拦下 / 抛异常都不扣。冷却展示见 `feedback.cooldown-display`。

## 二、技能类型与参数键全表

参数键的权威来源是 `ConfigValidator.ALLOWED_PARAMS`——**写在 yml 里但没收录的键
会在启动/reload 时被逐条点名**（这是护栏：防止配置键静默失效）。
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

连击（`melee_smash` 的 `combo-*`）：**命中目标才叠层**（1.6.1 起，空挥不计数），
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
| `mark-bonus` / `mark-ticks` | 易伤标记：目标受到的所有伤害 ×(1+mark-bonus)。**覆盖语义**，不叠加 |
| `armor-pierce` / `debuff-*` | 破甲：独立于易伤标记的第二乘区，可共存；硬上限 0.30 |
| `echo-bonus` | 兑现倍率：目标带标记 / 破甲 / 减速 / 冻结任一时伤害 ×echo-bonus；≤1.0 等同关闭 |
| `fall-immunity-ticks` | 免摔窗口（覆盖式写入，`CombatStates` 惰性过期） |
| `freeze-ticks` | 定身（SLOWNESS 拉满 + 负跳跃，纯药水实现） |
| `min-falloff` | 距离衰减下限（0.6 = 边缘目标至少吃 60% 伤害） |

伤害公式与穿甲基准见下节；`echo-bonus` 与 `mark-bonus` 是**独立乘区相乘**，
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
weapons.yml 后 `/taketori reload`——对局进行中 reload 会结束所有房间，注意时机。

模板升级：插件**不会覆盖**你的 weapons.yml；模板落后时控制台提示
`config-version`，同步方式：备份并删除该文件 → `/taketori reload` 重新生成 →
回填自己的数值。
