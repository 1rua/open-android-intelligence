# Hermes 批次互通日志隔离诊断与验收

关联问题：#169。

## 问题与固定输入

Full CI 运行 `43094885415` 的 `HermesHttpInteropTest.batchAcceptanceDispatchReplyAndReplayUseTheRealHttpAndSseBoundary` 在最后的宿主执行次数检查失败：`批次重放不能再次执行宿主回合 expected:<2> but was:<0>`。此前批次受理、真实 SSE 回答、批次重放结果与后继普通回复均已通过断言。

本轮固定输入为 OAI `02ad53e09049c2b06c21cacd9d009c2f14e6d5bd`、历史 Gateway 夹具 `ebe527da2426dd25877dd335786e80cf85d1cdde`、Hermes SDK `75b083e9399115b12513385252811f09c3503c90`。CI 合成合并提交 `105d9d5bf44270157f45bd00bf2f5748205cbade` 与该 OAI 提交的文件树没有差异。

## 已完成的根因验证

1. 在隔离副本单独运行原失败方法，真实 HTTP/SSE 路径通过，夹具日志包含两次 `agent-turn`。
2. 保持原测试与固定夹具源码不变，在第二次真实 `agent-turn` 之后，按 `ProcessBuilder.redirectError` 的相同文件截断语义启动第二个真实夹具。第二个夹具也完成启动并报告真实本机 HTTP 地址。
3. 原测试再次通过两次真实回复断言，随后在原位置复现 `expected:<2> but was:<0>`。本轮增量执行耗时 15 秒，38 个任务中 34 个保持最新。

两实例使用同一 `tmp/conversation-bugfix/hermes-batch-interop.txt`；后启动的实例会截断先启动实例的执行证据。Full 同时执行 Debug 与 Release 测试，允许出现同样的时序。受控复现证明这一日志观测缺陷能够解释该症状；现存 CI 日志未保存每个进程的文件打开时间，不能进一步断言其精确调度时序。

夹具在真实回合入口写入记录并显式 `flush=True`，原测试在进程退出后才读取记录；已检查的 SDK 回合入口直接等待消息处理函数，没有围绕该调用改写标准错误输出。单实例通过、第二实例启动触发同症状，支持并发日志覆盖这一根因。

## 修复范围

- 每次夹具启动使用独立日志文件，覆盖本测试类中批次、历史、流式和登录场景，避免其他场景出现同一类跨构建变体干扰。
- 保留原真实 HTTP/SSE 业务回复及批次重放断言。
- 除执行次数外，核对宿主记录中的当前会话、批次首消息与后继消息身份，避免误读另一实例恰好两条记录后通过。
- 使用相同受控并发入口验证修复前失败、修复后通过，再执行该真实互通测试类的 Debug 与 Release 聚焦回归。
- 此修复不修改宿主、网关或 Android 生产行为。

## 已完成的红绿检查

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 原测试单独执行 | 通过，两次真实宿主执行 | 建立单实例基线 |
| 同一重放脚本对 `02ad53e0` 原测试启动第二个真实夹具 | 13 秒内复现相同 `expected 2 / actual 0`，第二夹具也完成 HTTP 启动 | [红测输出](2026-11-12-hermes-batch-log-isolation-evidence/red-gradle.txt)、[触发证据](2026-11-12-hermes-batch-log-isolation-evidence/red-probe.txt) |
| 同一重放脚本对修复后测试启动第二个真实夹具 | 13 秒通过，复用既有编译产物；原实例使用唯一日志，仍断言两次执行及本会话/消息身份 | [绿测输出](2026-11-12-hermes-batch-log-isolation-evidence/green-gradle.txt)、[触发证据](2026-11-12-hermes-batch-log-isolation-evidence/green-probe.txt) |
| Debug 与 Release 各执行完整 `HermesHttpInteropTest` 类 | 两种变体各 5 个用例通过，0 失败；总耗时 1 分 24 秒，含 Release 首次缓存恢复/编译 | [Gradle 输出](2026-11-12-hermes-batch-log-isolation-evidence/debug-release-gradle.txt)、[执行用例与数量](2026-11-12-hermes-batch-log-isolation-evidence/debug-release-test-results.txt) |

原始进程日志保留在执行副本的 `tmp/conversation-bugfix/`，归档输出只将本地绝对路径替换为占位符，没有保留任何真实账号或用户会话。

## 重放命令

在仓库根目录准备上述两个固定外仓源码，并使用已有 Python 依赖、JDK 与 Gradle 缓存。脚本强制加载真实 Hermes 宿主，避免独立回退路径混入证据。以下命令在 Linux 上观察真实夹具写入的第二次 `agent-turn`，随后启动第二个真实夹具截断旧共享日志路径；它不会修改测试或外仓夹具源码。

```bash
export HERMES_PLUGIN_ROOT=.toolchains/legacy-gateway-ebe527da
export HERMES_SDK_ROOT=.toolchains/hermes-sdk-75b083e
export OPEN_ANDROID_GATEWAY_CONTRACT_ROOT=gateway-contract
python3 docs/reviews/2026-11-12-hermes-batch-log-isolation-evidence/reproduce.py --expected-exit 0
```

修复前的重放：在独立副本检出 `02ad53e0`，从修复分支复制上述 `reproduce.py`，保持相同依赖环境，执行 `python3 reproduce.py --repository-root . --expected-exit 1`。预期原用例同位置失败；重放脚本以原测试退出码等于预期值为通过条件。它只禁用目标测试任务的结果复用，不重跑无关编译任务。

两构建变体的完整真实互通类回归命令：

```bash
cd apps/android
./gradlew :conversation-data:testDebugUnitTest --tests com.openandroidintelligence.conversation.data.HermesHttpInteropTest \
  :conversation-data:testReleaseUnitTest --tests com.openandroidintelligence.conversation.data.HermesHttpInteropTest
```

这些检查验证固定历史宿主夹具下的真实 HTTP/SSE、WebSocket 与日志观测边界；不代表最终 Full 已通过，也不替代生产部署或实体手机验证。
