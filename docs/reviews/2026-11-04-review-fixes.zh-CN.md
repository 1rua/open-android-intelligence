# 2026-11-04 审查问题修复与验证

原始报告：[dev 相对 main 的审查](2026-11-04-dev-vs-main-code-review.zh-CN.md)。修复基线为 `9e7ab2244b40c14e89cda60cd774e4f7ee8577e9`，分支为 `feature/review-report-fixes-20261104`，目标为 `dev`。

原报告 R01–R12 已完成代码修复并通过定向及统一自动化验证。原工作区尚未提交的 `WorkbenchScreen.kt` 修改保留在原目录；本分支从固定提交创建，后续在独立 worktree 修复登录后设置跨断点问题，没有复制原工作区的补丁。

## 修复范围

| 问题 | 修改与保持的行为 | 回归入口 |
| --- | --- | --- |
| R01 | [GatewayRuntime](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) 按固定 Agent ID 缓存仓库，仅从原发现控制器读取同 ID 的描述更新；切换后的旧发送仍属于原 Agent。旧工作台在等待持久化锁前停止接收新远端操作，原本在途工作按原身份完成或保留待查询结果 | [ColdStartConnectionTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ColdStartConnectionTest.kt) 的相同/不同会话 ID 交接测试、[WorkbenchCloseSubmissionTest](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchCloseSubmissionTest.kt) 的退役等待锁测试 |
| R02 | 通过绑定的 sessionKey 选择宿主数据库，逐跳验证账号、来源、聊天、线程及唯一压缩后继；保留父会话绑定，拒绝 reset、错源、跨账号及不明确后继 | [test_hermes_communication_reliability.py](../../oai-core/tests/test_hermes_communication_reliability.py) 新增合法发送与拒绝边界用例 |
| R03 | 每次网络尝试前持久化投递轮转位置，失败目标保留重试；在线目标继续推进，已确认目标不重复投递 | [DevicePushRuntimeTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntimeTest.kt)：65 条事件、重试/不可达目标、批次重启和取消 |
| R04 | 撤回失败保留重试记录，采集与投递继续处理其他已授权端点；本地已撤回目标继续隔离，父任务取消仍传播 | [DevicePushCycleTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/DevicePushCycleTest.kt)：生产周期委托、持久化重开、B 继续接收和撤回恢复 |
| R05 | 普通 RPC 使用同一截止时间覆盖写锁、管道写入及响应；观察超时不取消后台 turn/start 的迟到受理，清理未消费 Future | [test_codex.py](../../oai-core/tests/test_codex.py)：真实协议子进程暂停读入、512 KiB 消息、后续 status、迟到取消和关闭 |
| R06 | 加密消息编码与解码保留 localPlaceholder；旧存储缺省 false，不依据正文或 ID 前缀冒认真实宿主身份 | [NativeConversationMirrorTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirrorTest.kt)：存储重开、历史补回去除临时行、同文不同真实 ID 保留 |
| R07 | 容量仅统计当前活跃端点；退役、过期及失去有效授权的历史端点不占名额，重新激活仍检查上限，历史事实保留 | [test_push_endpoints.py](../../oai-core/tests/test_push_endpoints.py)：连续 64 次注销、过期名额回收、满额后重新激活拒绝 |
| R08 | 截图回调、协程恢复、Main/超时作用域返回和编码调度共用单次资源所有者；取消释放与编码 finally 不重复关闭硬件缓冲区 | [ScreenshotBufferOwnerTest](../../apps/android/screen-control/src/test/kotlin/com/openandroidintelligence/screen/ScreenshotBufferOwnerTest.kt)：13 种真实协程调度与资源关闭场景 |
| R09 | 从工作台宿主宽度计算设置呈现方式；840dp 及以上的 320dp 插槽使用 SIDEBAR，保留既有输入法避让 | [SettingsResponsiveHostRegressionTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsResponsiveHostRegressionTest.kt)：839/840/1000dp 的模式与插槽宽度 |
| R10 | 登录前设置与登录后工作台均在断点分支外持有同一可移动设置内容；工作台面板状态直接跟随宿主输入 | [SettingsResponsiveHostRegressionTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsResponsiveHostRegressionTest.kt)、现有 [WorkbenchLayoutRegressionTest](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchLayoutRegressionTest.kt)：双向切换保留子页、草稿、滚动、关闭与返回 |
| R11 | 密码输入在无可用终端或 EOF 时返回明确失败，向导退出，不再重试空密码；有控制终端时继续支持管道安装 | [test_install_script.py](../../tests/test_install_script.py)：无终端、伪终端 EOF 和管道输入行为 |
| R12 | 从 python3/python 中检测合格解释器并保存；创建虚拟环境使用同一解释器 | [test_install_script.py](../../tests/test_install_script.py)：仅 python、python3 不合格而 python 合格的实际 venv 创建 |

## 已执行的定向验证

以下是各问题的定向验证，与后续统一门禁分开记录。

| 范围 | 结果 | 说明 |
| --- | --- | --- |
| Hermes 压缩及原生绑定 | 59 passed，16.19 秒 | 固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90`；6 个合法发送用例修复前失败、修复后通过，另有 32 项拒绝边界回归 |
| Codex 协议适配 | 37 passed，6 subtests passed，2.45 秒 | 大消息/status 与关闭清理回归修复前失败；使用合成子进程及真实管道 |
| 独立推送 JVM | 59 tests passed | 修复前在线 B 仅收到 63/65 条；修复后全部收到。生产周期函数确实被测试调用，系统采集器由合成输入替代 |
| 截图取消所有权 JVM | 13 tests passed | Kotlin 2.1.20、协程 1.9.0、JUnit 4.13.2；截图运行时定向编译通过 |
| Core 端点 | 初轮 27 passed | 真实 HTTP 注册、注销、满额重新激活；后续新增过期容量回收用例由统一门禁执行 |
| 安装脚本 | 初轮 28 passed，补强伪终端 3 passed | 实际解释器选择及 venv 创建；后续补强伪终端用例另行记录 |

定向测试的主要入口：

```bash
# Core / 安装
PYTHONPATH=oai-core/src python3 -m pytest -q oai-core/tests/test_codex.py oai-core/tests/test_push_endpoints.py tests/test_install_script.py
# 独立推送 JVM
python3 apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/run_push_unit_tests.py
# 项目快速 / PR 准入
npm run test:fast
npm run test:pr
```

## 统一门禁

首轮 `npm run test:fast`：依赖锁检查通过，Core/安装等 714 passed、1 skipped、6 subtests passed，Hermes 231 passed。Android conversation-ui 执行 743 项，现有 `settingsSubpageAndDraftSurviveWindowBreakpointChanges` 在手机切宽屏后丢失子页状态，1 项失败，未进入完整 App 测试。

失败定位到登录后工作台的两份设置内容，以及 LaunchedEffect 延后更新面板状态。第二轮 Core/安装 716 passed、1 skipped、6 subtests passed，Hermes 231 passed；Android 共 2467 项，5 项失败：3 项来自设置移动时的重复保存键或返回键归属，2 项为下述既有测试断言。没有将失败运行记为门禁通过。

设置部分继续修正：移除设置外层多余的 movableTabletSidebar，保持单一移动所有者与原保存机制；登录前设置移除会抢占子页返回的手机宿主返回处理，沿用设置自身的根页关闭与子页返回。定向 WorkbenchLayoutRegressionTest 和 SettingsResponsiveHostRegressionTest 已通过；补入连续四次断点往返及关闭重开后再次通过，既有可保存状态仍恢复。

完整 App 验证还发现两项既有测试断言与基线源码不一致；对应生产函数及调用链未被本次修改：

- `DeviceCapabilityBlockTest` 原先要求 SYNC_FAILED 追加阻塞说明，但现有实现已通过主状态文案呈现“同步失败，正在重试”。测试改为断言失败与重试均可见，不把同步故障说成不可恢复，也不要求重复说明。
- `PluginManagementGrantUiTest` 对全界面唯一“已授权”的假设不成立：状态说明与禁用按钮都显示该文字。测试改为按既有按钮 tag 同时核验文字及禁用状态，保持产品文案。

以上两项只修改测试，未改变产品行为。四个定向测试类合计 44 项通过；最终 `npm run test:fast` 与 `npm run test:pr` 均通过，代码文件指纹核对一致：

| 门禁 | 结果 | 用时 |
| --- | --- | --- |
| test:fast | 依赖锁通过；Core/安装 716 passed、1 skipped、6 subtests passed；Hermes 231 passed；Android 2467 项零失败 | 663.88 秒 |
| test:pr | 全部阶段通过；Android 复用上述已通过结果，439 个任务中 438 项保持最新 | 156.60 秒 |

Android 明细：control-ports 38、capability-ports 67、screen-control 41、device-primitives 55、conversation-domain 39、conversation-ui 743、app FullDebug 1484，均无失败。

`.github/workflows/oai-core.yml` 已补入新增 Runtime、推送周期及设置 Compose 回归，避免 App 的现有 `--tests` 白名单漏跑。CI 结果以 PR 的具体提交为准。

## 验证边界

尚未进行生产部署、真实模型、Android 真机输入法、图形驱动或截图内存验收。JVM 取消时序测试证明资源所有权规则，不代表系统截图像素和驱动已验收；Compose 窗口测试也不替代真机键盘可见性检查。

本次不改变账号、设备密钥、宿主真实消息/会话 ID 或历史归属。新增 localPlaceholder 字段向后兼容，原有存储未声明该字段时按 false 读取。PR 保持打开，合并与部署另行处理。

本地完整日志、Android XML 汇总及代码文件指纹保存在该修复 worktree 的 `.toolchains/review-fixes/`，按既有忽略规则保留，不提交生成物。唯一跳过用例为卸载器的符号链接保护场景：已单独运行并确认当前文件系统无法创建符号链接；Linux CI 继续覆盖该场景。
