# 贡献指南

感谢关注月影合战！欢迎提交 Issue 与 Pull Request。开始之前请先读一遍
[README](README.md) 的「开发者」部分（架构总览 / 构建与测试 / 目录结构）。

## 开发环境

| 项 | 要求 |
| --- | --- |
| JDK | 25（toolchain 已锁，装好即可） |
| IDE | 任意；IntelliJ IDEA 直接打开工程 |
| 网络 | 首次 `gradlew build` 需联网拉 Paper API（走 Maven Central / repo.papermc.io） |

```bash
# 标准构建（三道护栏 + 全部离线断言）
./gradlew build            # Windows: gradlew.bat build

# 只跑离线断言
./gradlew offlineCheck

# 无网络环境（libs/ 下已备好离线依赖时）
pwsh -File build-offline.ps1
```

## 构建护栏（违反即构建失败）

1. **core 层纯净**（`checkCorePurity`）：`core/` 包禁止引用
   `org.bukkit` / `io.papermc` / `net.minecraft` / `craftbukkit`，版本相关逻辑
   一律下沉到 `paper/` 或 `version/` 包，这是「升级只改适配层」的可验证承诺；
2. **参数键收录**（`checkParamKeys`）：技能实现读取的每个 `params` 键必须被
   `ConfigValidator` 收录，否则玩家改配置会静默失效；
3. **离线断言**（`offlineCheck`）：`tools/` 下登记在 `build.gradle.kts` 的 `offlineTools`
   列表里的每个测试类都必须通过。

## 测试

- 离线断言在 `tools/` 下，都是带 `main` 的独立类，`./gradlew build`（或 `./gradlew offlineCheck`）
  会全部执行，CI 跑的是同一条命令；
- **新增测试类必须登记**：在 `build.gradle.kts` 的 `offlineTools` 里补一行
  `OfflineTool("Foo", "offlineFoo", emptyList())`。漏登记不会报错，只会静默不执行；
- 涉及游戏内行为的改动请在真机（Paper 26.1.2）上验证一遍再提 PR。

## 提交约定

- 提交信息用一行中文摘要即可，主题化命名（一个 PR 聚焦一件事）；
- **版本号唯一来源是 `gradle.properties` 的 `version=`**，不要手改 `plugin.yml`；
  行为变更请在 `CHANGELOG.md` 顶部按倒序补一条目；
- 新增指令 / 配置键 / 技能参数时，同步更新 README 对应小节；
- 不要把 `build/`、`libs/`、`tools/` 的临时产物或个人信息提交进来（`.gitignore`
  已覆盖，如遇例外请在 PR 里说明）。

## 报告问题

- Bug 请用 [Issue 模板](.github/ISSUE_TEMPLATE/bug_report.yml)，附上
  服务端版本、`/taketori doctor` 输出与相关日志片段；
- 玩法 / 配置类问题先看 README 的玩法说明与配置项一节；
- 安全类漏洞请走 [安全策略](SECURITY.md)，不要公开提 Issue。
