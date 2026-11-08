# CI 与本地验证分工（Agent 开发规范）

本文说明**哪些验证在本地跑、哪些交给 GitHub Actions**，以及 CI 的结构与耗时目标。
适用于所有在本仓库改代码的 Agent 与开发者。

## 一句话规则

> 本地只跑**秒级**静态检查；编译、单元测试、回归测试一律交给 CI。
> CI 失败时只读对应 job 日志与测试报告做定向修复，不要本地重跑全套。

## 三级验证

| 级别 | 命令 | 用途 | 目标耗时 |
| --- | --- | --- | --- |
| fast | `npm run test:fast` | 改完立刻自测：依赖锁、越权面门禁、语法检查 | 30–60 秒 |
| pr | `npm run test:pr` | 需要本地复现 CI 增量范围时使用（可选） | — |
| full | `npm run test:full` | 发布前全量回归；日常不要跑 | 10 分钟以内 |

`fast` 刻意**不编译 Android、不跑 Python 单元测试**。需要编译的问题由 CI 的
`android-preflight` 与各 lane 暴露。

## CI 结构（`.github/workflows/oai-core.yml`）

```
changes           变更影响分析 → 输出各 lane 开关
  ├─ fast          依赖锁 / 语法 / CI 规划测试
  ├─ core          3.11 + 3.12 矩阵
  ├─ hermes-native 原生 Hermes SDK 契约
  ├─ dsh           DSH Node 桥
  ├─ android-preflight   越权面门禁（独立并行）
  ├─ android-setup       Rust WASM 夹具，只服务 app / play lane
  ├─ android-device      设备能力、本地授权
  ├─ android-platform    平台内核恢复
  ├─ android-notifications 设置、通知策略、加密状态
  ├─ android-conversation  会话顺序、渲染、持久化
  ├─ android-gateway       Gateway 客户端传输
  ├─ android-app          App 单元测试（编译一次，4 fork 并行测试）
  └─ android-play          Play 变体
gate              汇总；分支保护只要求这一个检查名
```

### 变更影响分析

`tools/ci/impact.py` 是唯一的分类器，CI 与 `tools/smart-test.py` 共用：

```bash
python3 tools/ci/impact.py --base origin/dev --head HEAD   # 人读
python3 tools/ci/impact.py --base origin/dev --json        # 机器读
```

规则要点：

- 文档（`docs/`、`*.md`）不触发任何代码 lane。
- 单个 Android 模块变更只跑该模块 lane，但**同时**触发 app/play lane，因为模块会编进 APK。
- `gateway-contract/`、Gradle 构建脚本、`gradle.properties`、`package-lock.json`、
  `tools/smart-test.py` 等变更 → **全量矩阵**。
- `oai-core/` 下的认证/授权/会话/网关代码 → 全量矩阵（其他 lane 的夹具依赖同一套语义）。
- **无法分类的路径或空 diff → 全量矩阵**。宁慢勿漏。

### 为什么 Android 拆成多个 job

单次 Gradle 调用里串行跑完全部测试是此前 20 分钟关键路径的主因。拆分后每个
lane 是独立 runner、独立配置阶段、一批相互独立的测试任务。

App 回归集是唯一**不**拆分 job 的 lane：实测两个分片各自的 Gradle 墙钟为
15.6 与 9.2 分钟，而两者真正的测试阶段都只有约 3 分钟，其余是各自编译同一份
变体。拆成两个 job 等于把这份编译付两遍，关键路径被最慢的那份编译拖住。
因此它编译一次，靠 `-Poai.testParallelForks=4` 让测试 JVM 并行。

三条实测约束，改工作流时不要“简化”掉：

1. **Gradle 的 `--tests` 只绑定它前面最近的那个 Test 任务。** 因此每个 job 只声明
   一个 Test 任务，过滤器由 `tools/ci/android_tasks.py` 生成。
2. **job 级 `if` 依赖 `changes` 的布尔输出**，不要启动 runner 后再跳过命令。
3. **`changes` 失败 = 无法判断影响范围**，此时下游必须全跑；`gate` 会因此变红。

### 测试分组清单

`apps/android/ci/app-regression.txt` 定义 PR 级 App 回归集，按功能域分节：

```
[core]               任何 Android 改动都会跑（横切行为）
[settings]           设置、外观、备份
[conversations]      会话渲染与持久化
[plugins]            插件安装、授权、能力
[push-notifications] 推送与通知
```

影响分析（`tools/ci/impact.py`）同时输出 `app_sections`：单域改动只跑
`[core]` 加该域，跨域或无法判定时跑全部。实测：设置文案改动 36 个类，
全量 66 个类。`tools/test_ci_planning.py` 校验：

- 每个类名都真实存在于 `apps/android/app/src/test`，且无重复；
- `[core]` 非空、五个域齐全；
- 按域过滤时 `[core]` 始终被包含，未知域名直接报错；
- 本地 `test:pr` 与 CI 使用同一份域选择：`tools/smart-test.py` 从
  `tools/ci/impact.py` 取 `app_sections`，收窄范围与 CI 完全一致；全量或无法
  判定时退回全部域。

新增 App 回归测试时，放进 `[core]` 或对应功能域；**不要**只留 CI 全量跑。

## 缓存策略

| 缓存 | 键 | 说明 |
| --- | --- | --- |
| Gradle 依赖 + 构建缓存 | `gradle/actions/setup-gradle` 自动 | dev/main 可写，PR 只读 |
| Configuration Cache | Gradle 本地 | 已启用；重复调用低于 1 秒完成配置 |
| Rust 插件 workspace | `plugins/**` 内容哈希 | 仅在 `android-setup` 构建一次 |
| pip | `oai-core/pyproject.toml` | Core 与 Hermes lane |
| 夹具 wheel | `apps/android/ci/hermes-fixture-requirements.txt` | 仅 `android-conversation` lane |
| npm | 各 `package-lock.json` | fast / dsh |

`plugins/target` 不进 Gradle 构建缓存，改走 artifact，避免把不安全的产物跨 job 共享。

## Configuration Cache 的前置条件

`apps/android/gradle/mvp-forbidden-surfaces.gradle.kts` 的 `doLast` **必须自包含**：
任何对脚本级函数/属性的引用都会捕获 Kotlin 脚本实例，使整个构建无法使用
配置缓存（并退化为每次重新配置）。文件列表与正则文本在配置阶段求值并声明为
任务输入；扫描逻辑在闭包内完整内联。

如果新增 Gradle 脚本任务出现类似报错，优先把逻辑改成“配置阶段取值 + 闭包内自包含”。

## 发布安全

| 工作流 | 触发 | 发布前校验 |
| --- | --- | --- |
| `dev-release.yml` | push → dev | `release-gate.yml`（`required-workflow: oai-core.yml`） |
| `release.yml` | 手动，main | `release-gate.yml`（`required-workflow: ci.yml`） |
| `android-apk.yml` | 非 dev 分支、手动 | 不发布，只产出可下载调试包 |

`release-gate.yml` 与构建**并行**：构建可以立刻开始，但 `publish-*` 依赖它。
因此不会出现“测试失败但已发布”，也不会把等待时间加到打包关键路径上。

`android-apk.yml` **不再监听 dev**：dev 推送已由 `dev-release.yml` 构建并发布同一份
APK，重复构建会产生不同 `versionCode` 的两份产物。

## 实测基线（2026-11-08）

同一 PR（`feature/ci-perf-20261108`，普通代码 + 配置改动，冷缓存）：

| 指标 | 优化前（单 `android` job） | 优化后 |
| --- | --- | --- |
| 完整 CI 墙钟 | 约 21 分钟 | **7.7–8.1 分钟**（run 42463831548：7 分 44 秒） |
| 最长单 job | 21 分钟（串行 5 次 Gradle） | 7 分 4 秒（`android-app`，全量 76 个类） |
| Fast Gate | 无（未单独存在） | 16–17 秒 |
| Android 模块 lane | 串在同一个 job 内 | 1.5–2.6 分钟，全部并行 |
| Core | 3.4 分钟 | 2.9–5.0 分钟（两版本并行） |
| Play 变体 | 与主 job 串行（约 5–6 分钟） | 3.6–4.3 分钟，与 app lane 并行 |
| 缓存命中 | 无跨 run 复用 | 232 个任务 FROM-CACHE，编译降到约 111 秒 |
| 配置阶段 | 每次约 8 秒 × 5 次 | 复用配置缓存（重复调用 <1 秒） |

单域改动的收窄效果（本地实测，同一份代码）：

| 范围 | 测试类数 | App lane 耗时 |
| --- | --- | --- |
| 全部域 | 76 | 约 5 分钟 |
| `[core]` + `[settings]` | 42 | 3 分 27 秒 |

剩余瓶颈与已知事实：

- `android-app` 的 12 分钟里，编译约 6 分钟、测试约 5.5 分钟。该 lane 已在一
  个 job 内编译一次（两分片方案实测会把编译付两遍，反而更慢）。
- 冷缓存（改动 `build.gradle.kts` / `gradle.properties`）会让 Gradle 构建缓存
  整体失效，这是本次实测的环境，属于最坏情况。
- Play lane 8.4 分钟，主要也是编译；已与 app lane 并行。

## CI 失败时怎么查

1. 打开失败 job，读**第一条**失败的 step。
2. Android 失败：下载 `test-reports-<lane>-*` artifact，读 `build/test-results/**/TEST-*.xml`
   里的 `<failure>` 与堆栈，定位到具体测试类。
3. 只本地复现该**单个**测试类：

   ```bash
   cd apps/android
   ./gradlew :app:testFullDebugUnitTest --tests '*TheFailingTest'
   ```

4. 修复后推送，让 CI 重跑受影响 lane；不要本地跑全量回归。

## 已知的本地限制

- 多数 App 测试需要 `plugins/target/**/*.wasm`（CI 的 `android-setup` 产物）。
  本地缺失时相关测试会失败，这是**预期**的，不代表代码有问题。
- `conversation-data` 的 `HermesHttpInteropTest` 需要历史 Hermes 夹具
  （`HERMES_PLUGIN_ROOT`、`HERMES_SDK_ROOT`、`OPEN_ANDROID_GATEWAY_CONTRACT_ROOT`），
  缺失时**直接失败而不跳过**，这是刻意设计。
- 真机能力（如实际通知监听授权、屏幕捕获）在云 runner 上不可验证；相关置信度
  仍来自本地真机验证，CI 只覆盖可模拟的部分。
