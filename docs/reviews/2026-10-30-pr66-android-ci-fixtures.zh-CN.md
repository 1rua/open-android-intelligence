# PR #66 Android CI 缺失真实 WASM 产物修复

日期：2026-10-30（Asia/Shanghai）。失败作业为 GitHub Actions run `40966830001` / job `123773348226`，对应 PR 头提交 `99f4d5a13cde29702df30c961834b2f6b2dfdd85`。本次在原独立 worktree 和同一 feature 分支追加修复，不修改 dev/main，不修改授权与设备执行逻辑。

## 已确认根因

远端 `Core connection, Agent sessions, platform recovery and transport regressions` 阶段运行 254 个 App 用例，10 项失败：`ClientCapabilityAuthorizationTest` 7 项、`PluginManagementScreenTest` 3 项，均为 `FileNotFoundException`。日志失败时间为本地 2026-10-30 06:43（UTC 日志为 2026-10-29 22:43）。

[TestAlpPackages.compiledSmsPackage / compiledNotificationsPackage](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/TestAlpPackages.kt) 读取真实 Rust 编译文件，组装并签署合成测试作者的可安装插件包：

- `plugins/target/wasm32-unknown-unknown/release/sms.wasm`
- `plugins/target/wasm32-unknown-unknown/release/notifications.wasm`

这些构建产物被 Git 忽略，干净 checkout 中不存在。[oai-core.yml](../../.github/workflows/oai-core.yml) 的 Android job 扩充了测试清单，却没有像既有 ci.yml / android-apk.yml 那样先构建 Rust 插件。此前本地验证已经构建过 WASM，因而没有暴露这个 CI 前置依赖缺口。

## 复现与修复

先把本 worktree 的两个文件移动到忽略目录留存，不删除文件。运行原失败的两个测试类：

```sh
./gradlew :app:testFullDebugUnitTest \
  --tests '*ClientCapabilityAuthorizationTest' \
  --tests '*PluginManagementScreenTest' \
  --console=plain --max-workers=2
```

得到 **23 tests completed, 10 failed**；将本地失败名称与远端失败名称自动比较，10/10 完全一致。

修复在任何 Android Gradle 测试前增加：

1. 安装与仓库既有工作流一致的 Rust 1.90.0 和 wasm32-unknown-unknown target。
2. 缓存真实 workspace 编译结果；`plugins -> target` 的 target 路径相对于 plugins，实际缓存为 `plugins/target`，并开启 workspace crate 缓存。缓存步骤显式设置 RUSTUP_TOOLCHAIN=1.90.0，避免 cargo metadata 进入 plugins 时被该目录的 1.88.0 工具链文件重新选择。
3. 使用 Cargo.lock、离线依赖解析和显式输出目录编译当前 Rust workspace。
4. 在进入测试前核验 notifications、sms、call_log、screen_reader、screen_capture、screen_control 六个 WASM 文件均非空；缺失时直接给出具体路径并失败。

```sh
cargo +1.90.0 build --locked --offline \
  --target wasm32-unknown-unknown --release \
  --manifest-path plugins/Cargo.toml --target-dir plugins/target
```

不更改失败测试的断言、选择条件或本地授权决定，不制造空 WASM，不依赖预先存在的缓存作为唯一来源。缓存缺失时仍执行真实编译。

## 验证记录

本地复用现成 Rust 1.88.0 工具链，执行同一 locked/offline/target/release/manifest/output 参数构建，六项文件的 WASM magic、长度与 SHA-256 已记录。CI 继续使用仓库已有的 1.90.0 版本；本地不宣称执行过未安装的 1.90.0。

按修改后 workflow 的原命令核验两步，均为 BUILD SUCCESSFUL：

| CI 步骤 | 实际结果 |
| --- | --- |
| Full：Gateway 客户端 + App 筛选 | 566 项通过、0 失败/错误、0 跳过，其中 Gateway 312、App 254；原先 10 个失败均通过 |
| Play：App 筛选 | 166 项通过、0 失败/错误、8 项跳过 |

Play 的 8 个跳过来自已有 ALLOW_RUNTIME_PLUGINS 前置条件：7 个 ClientCapabilityAuthorization 用例和 1 个 CapabilityPublisher 用例。这些用例在 Full 中均已实际通过；Play 不修改或取消产品能力限制。PluginManagementScreen 中三个读取真实 WASM 的用例在 Play 也均通过。

YAML 解析、六项文件头、构建步骤先于两种 App 测试的顺序，以及本地/远端 10 个失败名称的一致性检查均通过。本次仅追加工作流前置构建；`npm run test:fast -- --since HEAD` 与 `npm run test:pr -- --since HEAD` 均退出 0，另有上述 CI 原命令的真实定向回归。未对未改动的业务模块重复全量编译。

完整日志、失败名称匹配、真实产物摘要、逐步命令及 XML 位于工作区忽略目录 `.toolchains/pr66-android-ci-fixtures/`；最初红结果另在 `.toolchains/dev-reliability-20261030/pr66-ci-wasm-red/`。之前完整 Fast/PR 的本地结果继续有效，但不能替代本次修复后新的远端 CI 结论。
