<!-- 标题建议：<类型>: <一句话说明>，例如 "fix: 修复双击潜行偶发不触发" / "feat: 新增角色吉野" -->

## 改动类型

- [ ] Bug 修复
- [ ] 新功能（新技能 / 新机制 / 新指令）
- [ ] 数值调整（`weapons.yml` / `characters.yml` / `config.yml` 默认值）
- [ ] 文档（README / CHANGELOG）
- [ ] 构建脚本 / 校验工具
- [ ] 其它

## 关联 Issue

<!-- 写 "Closes #编号"；没有对应 issue 就写「无」 -->

## 改动说明

<!-- 做了什么、为什么这么做；对玩家、管理员、配置文件各有什么影响 -->

## 自查清单

- [ ] `gradle build`（或 `pwsh -File build-offline.ps1`）通过，未绕过护栏
- [ ] `core/` 层没有新增 Bukkit / NMS 引用（`checkCorePurity` 会拦）
- [ ] 新增或改名的技能参数键已在参数校验表登记（`checkParamKeys` 会拦）
- [ ] 涉及玩法的行为已在 Paper 1.21.4 实机验证（按键触发 / 对局流程 / PVE 至少一项）
- [ ] 改了默认配置、指令或技能数值时，README 与 CHANGELOG 已同步
- [ ] 没有提交 token / 密钥 / 本地依赖 jar
