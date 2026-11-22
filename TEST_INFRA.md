# Open Android Intelligence (OAI) 全局测试与质量门禁体系

本文档描述仓库现行的全仓测试基础设施、增量调度规则、静态安全门禁及本地与 CI 协作机制。本地测试调度与 GitHub Actions 共用同一套影响分析与任务清单，避免“本地通过而流水线漏测或失配”。

---

## 1. 三级验证体系

仓库通过根目录 [package.json](package.json) 统一封装三级命令，底层均调用 [tools/smart-test.py](tools/smart-test.py)：

| 验证级别 | 触发命令 | 核心定位与执行内容 | 适用时机 |
| --- | --- | --- | --- |
| **Fast (Level 1)** | `npm run test:fast` | 仅跑秒级静态与轻量检查：固定上游来源锁定校验（`npm run lock:check`）、Android 硬编码中文扫描（`tools/check_hardcoded_chinese.py`）、Android 禁用表面门禁（`./gradlew noVpnSurfaceCheck`，不触发 Kotlin/APK 编译），以及按 diff 触发的插件工具/MCP 单元/CI 规划 `pytest` | 日常开发改完代码后的即时自测 |
| **PR (Level 2)** | `npm run test:pr` | 在 Fast 基础上叠加：XML 字符串资源完整性校验（`tools/check_string_resources_integrity.py`，兼容入口 `tools/test_string_resources_integrity.py`）、`npm run test:phone-mcp`（含原生 Kotlin 门面互通）、`npm run test:remote` 与 `tools/remote-server` 测试，并根据 `tools/ci/impact.py` 计算受影响模块（含反向依赖）及 `:app` 分组回归 | 提交 PR 前的准入自检 |
| **Full (Level 3)** | `npm run test:full` | 执行全部门禁、所有子项目（`plugin-tooling`、`oai-android-mcp`、`tools/remote*`、CI `pytest`）、所有 Android 模块单测，以及 `:app:testFullDebugUnitTest` 与 `:app:testPlayDebugUnitTest` 双变体全量测试 | 发布前验证或核心契约大改 |

> 支持附加 `-- --since <git-ref>` 指定增量比对基线（例如 `npm run test:pr -- --since origin/dev`）；未指定时自动探测 `origin/dev` merge-base 或 `HEAD~1` 并合并当前未跟踪与未提交变更。

---

## 2. 变更影响分析与 Android 任务规划

### 2.1 影响分类器：`tools/ci/impact.py`
`tools/ci/impact.py` 是本地 `smart-test.py` 与 GitHub Actions 工作流共享的唯一调度真相源，将文件变更（含重命名旧路径与新路径、删除、新增及未跟踪文件）映射至 10 个并发 lane：
- `fast`、`remote`
- `android-device`、`android-platform`、`android-notifications`、`android-conversation`、`android-gateway`、`android-plugins`、`android-app`、`android-play`

核心调度规则：
1. **反向依赖自动扩散（`MODULE_DEPENDENTS`）**：修改底层库模块时（如 `:gateway-client`、`:platform-kernel`、`:control-ports`、`:ui-resources`），自动同时激活所有消费方模块所属的测试 lane、安全预检（`runs_android_preflight`）及 `:app` 轻量编译兜底（`runs_android_app_compile`）。
2. **重型 App/Play 回归分层触发（`should_run_app_regressions`）**：耗时的 `android-app`（`Android App Regressions`）与 `android-play`（`Android Play Variant`）仅在推送/合并到 `main`、`dev`（保障 `dev-release.yml` 金丝雀预览发布前验证）或 `release/*` / `release-*` 分支、Git Tag、发版 PR（目标或源分支为 `main` / `release/*` / `release-*`）、PR 显式打上全量/发版标签（`run-full-ci` / `full-regression` / `full-ci` / `release`）或手动 `workflow_dispatch` 时放行；日常普通 `feature/*` / `fix/*` PR 在 CI 中并行运行受影响模块的定向单测、安全预检、`Fast Gate` 以及 `:app` 主代码/测试轻量编译校验（`Android App Compile Check`，防止语法或签名破坏合入 `dev`），将关键路径控制在 5 分钟以内。
3. **安全与契约路径强制全量（`SECURITY_OR_CONTRACT_PATHS` / `PREFIXES`）**：改动 Gradle 构建脚本、`libs.versions.toml`、`package.json`、`package-lock.json`、安全包路径、禁用表面或 i18n 门禁脚本、CI 工作流/Action 或影响分析自身时，设置 `full_required=true` 并拉起当前触发作用域下全部允许的 lane（日常 PR 含全部模块单测、安全门禁与 `:app` 编译兜底；`main`/`dev`/`release*` 推送、发版 PR、显式打标签或手动触发时含完整 App/Play 回归）。
4. **本地与 CI 策略分层说明**：本地执行 `npm run test:pr`（`tools/smart-test.py`）属于开发者主动发起的准入自检，显式传入 `allow_app_regressions=True` 以继续保留本地 `:app` 分组单测能力，且纯 Android Kotlin 源码改动不会误触发 Python CI 规划测试；GitHub Actions 日常 PR 则默认关闭重型 App/Play Robolectric 套件并由 `Android App Compile Check` 守住编译底线，待合入 `dev`（金丝雀预览发布前）或 `main` / 打全量标签时补跑完整 App/Play 回归。
5. **保守兜底原则**：出现未归类路径或空 diff（如浅克隆缺失基线）时，自动设置 `unknown=true` 与 `full_required=true`，宁可全量也不漏测。
6. **纯文档豁免**：`docs/`、`tmp/` 与非测试/非特定许可资产的 `*.md` / `*.txt` 变更不触发代码编译与测试 lane。

### 2.2 Gradle 任务翻译器：`tools/ci/android_tasks.py`
将 `impact.py` 的分类结果翻译为精确的 `./gradlew` 参数组：
- 严守 Gradle `--tests` 绑定规则（`--tests` 只作用于紧邻的前一个 Test 任务），将无过滤的模块级任务与带 `--tests=` 过滤器的 App 分组任务拆分为独立 Gradle 调用。
- 无论触发哪个子集，始终保留 `noVpnSurfaceCheck` 安全门禁。

---

## 3. 核心专项质量门禁

### 3.1 固定上游完整性门禁：`npm run lock:check`
由 [tools/check-remote-sources.mjs](tools/check-remote-sources.mjs) 调用 [tools/remote-source-integrity.ts](tools/remote-source-integrity.ts) 执行，在 `fast`、`pr`、`full` 级别及每次 CI 中必跑。核验以下目录的 SHA-256、字节长度、Git blob 校验和与清单完备性（禁止存在未登记文件或符号链接）：
- `tools/remote/fixtures/dsh-remote/` 与 `tools/remote/fixtures/media-upstream/`
- `tools/remote-server/fixtures/`
- `tools/remote-interop/fixtures/`、`release-fixtures/`（`v0.6.5`）、`harness-fixtures/`（`0.2.2-rc.1`）
- `integrations/oai-android-mcp/test/fixtures/dsh-remote/`
- `apps/android/gateway-client/src/test/resources/dsh-remote/`（`SHA256SUMS`）
- `apps/android/gateway-client/src/main/assets/licenses/remote/`（`sources.json`）

### 3.2 Android 禁用表面门禁：`noVpnSurfaceCheck`
定义于 [apps/android/gradle/mvp-forbidden-surfaces.gradle.kts](apps/android/gradle/mvp-forbidden-surfaces.gradle.kts)，挂载至根工程 `check` 生命周期及所有增量级别：
- **全局绝对禁止**：任何模块与源码（含 Owner）均禁止出现 `VpnService`、`BIND_VPN_SERVICE`、`TunInterface`、`TUN`、`addRoute`、`setHttpProxy`、`ProxyInfo`、`LocalAPI`、`Listen(`、`Dial(`。
- **出站网络归属控制**：禁止未登记模块或文件使用 `URLConnection`、`WebSocket`、`HttpClient`、`OkHttpClient`、`ServerSocket`、`DatagramSocket`、`Socket`、`openConnection(`、`createSocket(`。出站传输收口于 `gateway-client` 模块与显式登记的文件（如 `AppUpdateChecker.kt` 及极窄测试夹具/审计文件）。

### 3.3 Android 国际化双门禁
通过 [apps/android/gradle/i18n-quality-gates.gradle.kts](apps/android/gradle/i18n-quality-gates.gradle.kts) 注册为 Gradle 验证任务（`checkHardcodedChineseStrings`、`checkStringResourcesIntegrity`、`verifyLocaleConfigPrerequisites`），并集成至 `smart-test.py`：
1. **硬编码中文扫描器（[tools/check_hardcoded_chinese.py](tools/check_hardcoded_chinese.py)）**：在 Level 1（`fast`）起拦截生产源码中的未豁免中文字符，支持 4 级豁免体系（行内注释 `// i18n-ignore`、注解 `@Suppress("HardcodedChineseText")`、日志/断言/异常/弃用语法自动豁免、中央基线 `apps/android/ci/i18n-baseline.json`）。
2. **XML 资源完整性与引用校验器（[tools/check_string_resources_integrity.py](tools/check_string_resources_integrity.py)，兼容入口 [tools/test_string_resources_integrity.py](tools/test_string_resources_integrity.py)）**：在 Level 2（`pr`）起强制校验 5 条规则：
   - 多占位符（$\ge 2$）强制使用 `%1$s`、`%2$s` 显式位置索引；
   - 孤立未转义 `%`、未转义单引号 `'` 与 `&` 检查；
   - 模块内跨文件资源 ID 全局唯一性；
   - `<plurals>` 合法修饰词与必填 `other` 分支；
   - Kotlin 生产源码引用的 `R.string`/`R.plurals`/`R.array` 必须在默认 `values/` 中存在真实定义。
3. **多语言配置前置校验（`verifyLocaleConfigPrerequisites`）**：启用 `generateLocaleConfig = true` 时校验 `src/main/res/resources.properties` 存在 `unqualifiedResLocale`，并拦截缺失区域前缀 `r` 的非法 `localeFilters` 写法（如报错提示使用 `zh-rCN` 而非 `zh-CN`）。

---

## 4. App 分组回归清单与 JVM 隔离复用

- **PR 级分组清单（[apps/android/ci/app-regression.txt](apps/android/ci/app-regression.txt)）**：将 `:app` 的 PR 级单元测试划分为 `[core]`、`[settings]`、`[conversations]`、`[plugins]`、`[push-notifications]` 五大域。单域变更仅运行 `[core]` + 对应域；跨域或未知变更运行全部分组。
- **清单强制对齐校验（`tools/test_ci_planning.py`）**：静态扫描 `apps/android/app/src/test/` 下全部 `*Test` 类，若新增测试类未登记入 `app-regression.txt`、存在重复或五域缺失，CI 规划校验即刻失败阻断。
- **受控 JVM 复用（[apps/android/ci/app-jvm-reuse.txt](apps/android/ci/app-jvm-reuse.txt)）**：`:app` 测试默认 `forkEvery = 1` 保证进程隔离；仅经同源码、重复、乱序与并行审计通过的测试类才可登记至 `app-jvm-reuse.txt`，由 CI 指定参数开启复用组。

---

## 5. GitHub Actions 流水线协作关系

仓库两条核心 CI 流水线分工如下：

| 工作流文件 | 触发时机 | 职责分工与核心步骤 |
| --- | --- | --- |
| [.github/workflows/remote-client.yml](.github/workflows/remote-client.yml) | 针对 `dev`/`main`/`release*` 的 PR（含全量标签 `labeled`/`unlabeled`）与推送、`workflow_dispatch` | **增量并发门禁**：`changes` 作业调用 `tools/ci/impact.py` 结合触发上下文计算受影响 lane、`runs_android_preflight`、`runs_android_app_compile`、`app_regressions_allowed` 与 `app_sections`；日常 PR 并行拉起 `fast`、`remote-sources`、`android-preflight`、`android-app-compile`（`:app` 主代码与测试源码轻量编译兜底）与受影响模块/remote lane（关闭 `android-app` / `android-play`），`dev`/`main`/`release*` 分支推送、发版 PR、显式打标签或手动触发时再放行完整 App/Play 回归；`concurrency` 按 PR 号即时取消过时 PR 与全量标签变更的旧运行，普通标签增删落入独立 `label-noop` 组静默跳过不打断运行中的 PR CI，同时保留发布分支推送排队 |
| [.github/workflows/android-apk.yml](.github/workflows/android-apk.yml) | 仅 `push` → `main` 与 `workflow_dispatch` | **调试 APK 产物构建**：日常 `feature/*`/`fix/*` PR 分支推送不再自动触发整包 APK 编译，消除与 `remote-client.yml` 的重复编译与并发竞争；`dev` 推送由 `dev-release.yml` 承担预览发布，`main` 推送继续产出 `app-full-debug` 供 `tools/download-latest-apk` 下载 |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | 针对 `main` 的 PR 与推送、`workflow_dispatch` | **Level 3 发布级完整回归**：不做路径过滤。固定包含两个必需状态检查项——`Node.js & Gateway Conformance`（`lock:check`、根工具/插件签名测试、发布元数据工具单测、Remote Server 与 MCP 测试）与 `Android Build & Check`（编译 6 个真实 Rust WASM 插件并用隔离测试 seed 签名打包为 `.alp`、执行 `./gradlew check :app:assembleFullDebug -Poai.requireBundledPlugins=true`、执行 `npm run test:phone-mcp:interop`，最后由 `apps/android/tools/verify_bundled_plugins.py` 逐字节核对 6 个 `.alp` 资产已准确打入 APK） |
