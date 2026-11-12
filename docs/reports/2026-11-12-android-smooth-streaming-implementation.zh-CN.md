# Android 流式正文呈现与触觉反馈实施记录

关联 issue：#160；PR：#161；任务分支：`feature/android-smooth-streaming`。设计及独立评审固定基线为 `26c0e5c92745e3374b88c293d27677f200efff0f`。本轮功能代码验收头为 `7e8a7393`；另将独立安装身份的验收初始化配置入库，使专项测试可以在其他工作区复现。后续报告提交不改变业务代码。

## 实现与数据边界

完整接收正文仍来自既有消息状态，继续供同步、镜像、数据库及复制使用。Android 内部增加 LIVE/SNAPSHOT 来源与修订信息，经现有助理桥接传递，不修改后端协议。`StreamingPresentationRegistry` 位于列表项外，按既有身份管理一条当前回复；`StreamingPresentationState` 只保存展示进度和短期批次，不把未吐完的正文写回事实。

首个非空实时内容在下一可用帧开始消费，不等待积攒。正文提交约 30Hz，速度为每秒 40 字素加待显字素数除以 0.35 秒，约 120ms 平滑调整；终态以约 250ms 为内部排空目标。网络停顿时不制造字符或触觉；终态、取消成功及明确失败继续排空，取消请求本身不提前终结。旧修订与重复事件不推进，相同快照保持进度，追加型快照静默入缓冲，权威非追加改写立即校正并清理动效。

Android ICU 字素边界保护代理对、组合字符、肤色及 ZWJ 序列；不完整尾部保留，最后字素扩展在原位合并。挂载已有正文、重开页面及恢复前台直接建立全文基线；切会话、隐藏及销毁清理呈现，不影响原有后台接收。

主聊天、气泡和系统助理复用同一个正文入口；普通回复与执行过程后的最终回答使用同一份来源文档。时间线分组、工具卡、附件和列表 Key 仍使用完整事实。系统助理最新活跃回复的预算保护延续到终态，并覆盖后方存在工具事实的情况，防止超过 32k 的正文被终态快照截断。

## 渐入、光标与解析

在既有 Markdown 解析器上增加可选来源追踪和保守前置块缓存，不另写语法解析器。来源覆盖列表、表格、代码、链接、转义、实体及公式；相同文字按原文偏移区分，语法闭合不重新冒充新字。只有语法已关闭且有顶层空行分隔的前置块可缓存，可变尾部继续解析；每个流式前缀与原完整解析器对照测试。

短正文（不超过 256 个 UTF-16 单元）同步解析；首次进入长文路径也同步初始化，后续尾段由单个可取消后台任务处理。完整消息事实变化仍可能触发既有同步解析，不能将实现描述成“所有长文解析都在后台”。渐入、光标及拖尾的时间状态在绘制阶段读取，源码路径没有从动画帧调用解析器；本轮未取得解析/重组次数的设备计数，不能把该源码结论冒充动态测量。

新增范围以约 120ms 从 0.45 渐入到完全不透明，最多 8 个活动批次；稳定叶节点不保留离屏合成层。光标为独立 Canvas 细竖线，使用 TextLayoutResult 和公式实际内容边界，不向正文插入占位。源顺序确定最后可见节点，统一容器坐标；约 160ms 的短拖尾限制在末端附近。容器宽度、字形及公式测量变化重建基准；滚动随共同父容器移动。

KaTeX 块公式的 `.katex-html` 是满宽 block，不能直接作为字形边界。前一版测试同样误用了该矩形，虽 6 项通过，人工核对截图仍发现光标位于空白行右侧；已强化为 DOM 内容 Range、短公式宽度限制和截图位置三重断言。强化测试在旧实现上明确失败于字形右缘断言，见 [旧版红证](android-smooth-streaming/2026-11-12-math-cursor-before-fix-red.txt)。修复以 KaTeX 最后有效直属 `.base` 内容块定位，无内容块时使用 Range 兜底，按密度换算后跟随末端，并在 WebView 滚动、宽度改变后重新测量。修复后在 SHA-256 与本地构建一致的独立验收包 APK 上，强化单项通过（3.978 秒）：独立 DOM 字形右缘为 492px，截图光标覆盖 x=490～493px；[修复后绿证](android-smooth-streaming/2026-11-12-math-cursor-after-fix-green.txt)。气泡窗口按末条正文的实际高度增长继续贴底，用户拖动时停止跟随。减少动画仍保留吐字和静态末端指示，关闭渐入、位移插值和拖尾。

## 设置与触觉

外观设置通过既有 AppearanceSwitch 新增一个默认关闭的开关，持久化成功后发布 StateFlow；备份加入可选字段，旧备份缺失时恢复关闭，并接入既有原子应用和失败回滚。助理桥接服务观察偏好变化并即时下发，避免依赖跨进程 SharedPreferences 缓存刷新。

生产字符串差异检查确认只新增「震动反馈」和「输出时伴随轻微震动触觉反馈」，没有修改或移除原有资源；证据见 [资源差异](android-smooth-streaming/2026-11-12-resource-diff.json)。没有新增提示、Toast、声音、权限、振动服务或第三方振动库。

参考源码为 RikkaHub `90269e39dcf1beabf3066139c03f29162ebb082b` 的 `ChatMessage.kt:338-343` 和 `SettingPreferencesGeneralPage.kt:183-189`：它观察内容，尾沿防抖 50ms，并在 loading 且开关开启时请求 KeyboardTap。OAI 按已确认计划保留当前 Compose 依赖，通过 LocalView 调用原生 `performHapticFeedback(KEYBOARD_TAP)`；触发源改为实际进入可见区域的新文字范围，采用约 100～150ms 时间节流，过期事件丢弃，不排队补震。每个批次只在下一帧用真实布局判定一次可见性；不可见批次永久丢弃，滚动/重排只更新坐标，不能补发旧震感。该边界由真实裁剪和滚动 Compose 回归覆盖。系统偏好、窗口焦点、视口与生命周期共同约束；开关关闭后下一次回调立即遵循，不依赖绘制帧触发。

## 验证环境与自动化

本地使用 Java 21.0.10、Gradle 8.12、既有 Android SDK 和可复用 Gradle 用户目录。沿用仓库已存在的历史 Hermes/SDK 固定夹具及 WASM 测试产物；没有修改 pin、跳过失败测试或增加超时来取得通过。早期只读缓存、Maven 429、Java 17 不支持 API 36 以及缺少工作区 WASM 的阻断已通过环境修正处理，不能继续当作当前业务结果。

| 检查 | 结果 |
| --- | --- |
| conversation-ui 单元与 Compose 回归 | 最终功能头 910 项，0 失败、0 错误、0 跳过；本轮实际执行 |
| conversation-domain | 47 项，0 失败、0 错误、0 跳过；Java 21 前轮实际执行，本轮 UP-TO-DATE |
| conversation-data | 61 项，0 失败、0 错误、0 跳过；Java 21 前轮实际执行，本轮 UP-TO-DATE |
| `npm run test:fast` | 当前功能头最终复核通过 |
| `npm run test:pr` | 当前 7e8a7393 最终复验通过；模块调用 45s，App 调用 11m14s，含编译与回归 |
| App 选中 Full 回归 | 最终功能头 1,856 项，0 失败、0 错误、1 条既有渠道条件跳过 |
| 验收 APK 和仪表 APK 构建 | 通过；独立验收包 APK 安装内容与构建 SHA-256 一致 |
| `git diff --check` 与文案差异 | 通过；最终提交前复核 |

原始命令输出见 [Fast](android-smooth-streaming/2026-11-12-fast-green.txt)、[PR](android-smooth-streaming/2026-11-12-pr-green.txt)、[APK 构建](android-smooth-streaming/2026-11-12-apk-build-green.txt)；[JUnit 汇总](android-smooth-streaming/2026-11-12-unit-results.json)保留类数、条件跳过与未执行任务。本地 PR 规划器本轮选择 Full 单元回归，未选择 Play 单元任务；唯一跳过是 BuiltInCapabilitiesTest 的 Play 渠道专用断言，未为了通过本次功能而新增跳过。Play 验收包实际构建与 6 项设备测试另列。

状态/注册器测试覆盖突发、小块、积压、终态先到、取消后排空、停顿、重复、旧修订、快照、权威改写、UTF-16 切分、身份升级、隐藏恢复及触觉不补震。设置和备份测试覆盖默认值、实时更新、偏好对象重建、旧备份及回滚；桥接测试覆盖元数据、偏好和 >32k 终态预算。纯状态测试使用隔离 MutableSnapshot，避免污染后续 Compose 测试的全局待提交状态。

验收期间发现并修复视口外批次在 200ms 内滚入可能补震的边界：将可见性检查从布局回调移到单个可取消收集器，同批只检查一次；正常可见新批次、多叶节点及销毁均有回归。相同两项回归在旧实现上精确失败于“滚动本身不能给旧批次补触觉”，修复后 2/2 通过；[旧版反证](android-smooth-streaming/2026-11-12-haptic-visible-before-fix-red.txt)与[修复后结果](android-smooth-streaming/2026-11-12-haptic-visible-green.txt)均入库。

收尾另确认列表回收或呈现容器切换时，新建局部绘制状态可能补播旧批次。`7e8a7393` 在组件初挂载时记录原始批次作为静默基线，仅后续批次获得新增范围；全文、显示偏移和活跃状态不变。两项原生 Compose 回归覆盖真实销毁重挂载、旧批次无渐入/触觉、下一批正常以及新 LIVE 0→1 首字正常，见 [定向结果](android-smooth-streaming/2026-11-12-remount-green.txt)。该最小修改及回归经独立窄范围复核无具体问题。

本轮门禁还发现一处测试夹具错误：缺失修订的宿主帧被解码为修订 0，而历史事实为修订 1，测试却要求匹配实时来源。已显式修订匹配用例并增加旧宿主静默降级回归，保留通知顺序、只落盘一次和 ACK 断言；`NativeEventSynchronizerTest` 定向验证已通过。

独立规范评审未发现硬性规范违反；独立需求评审所提的气泡贴底、居中公式末端两项 P2 已在 `4c44b64e` 修复，并于 `da5d10a1` 完成窄范围源码复查。随后 `551e11d0` 的实际公式末段修复及强化测试也经独立窄范围评审，未发现具体标准/需求缺陷。该复查不替代设备结果。远端自动评审曾因账号额度失败，不能计为通过。验收期间远端 dev 前进到 `02ad53e0`（备份摘要格式及历史契约门禁两项），本分支仍按已固定基线验证，未把新基线的验证冒充为本轮结果；最终 PR 合并兼容由 GitHub 状态与 CI 另行确认。

## 模拟器与性能证据

设备为 `emulator-5554`，API 36、1080×2400、420dpi、约 60Hz。使用独立安装包 `com.openandroidintelligence.mobile.streamingacceptance` 和当前用户 10，未替换原包或访问原有账号。首次安装修复版时，设备可用空间虽有约 433MB，仍被安装预留量限制拒绝；先前脚本未阻断，误用旧版独立验收包的结果已排除。本轮临时将模拟器存储预留上限调至 64MiB，安装成功后在 finally 中恢复原值，未删除应用、用户文件或账号。随后对设备已安装独立验收包 APK 和本地构建逐字节 SHA-256 校验一致，见 [安装身份](android-smooth-streaming/2026-11-12-apk-identity.json)。后续安装失败会立即中止，不再进入测试。验收使用 debug 空宿主和本地合成事实；测试没有连接真实 Core/Hermes。

前轮 `ff1bb57b` 有 5 项仪表测试通过，耗时 70.45 秒，原始结果保留为 [首次运行日志](android-smooth-streaming/2026-11-12-emulator-first-pass.txt) 与 [首次运行数据](android-smooth-streaming/2026-11-12-emulator-first-pass.json)。该证据不自动覆盖后续优化及新增公式测试。

`7e8a7393` 最终 6 项仪表测试全部通过，耗时 61.51 秒；覆盖真实排版/跨行/滚动/旋转、停止恢复/Activity 重建、模拟触觉与减少动画、聚合执行最终回答、实际公式 DOM 与截图末端、三场景三轮对照。[最终原始日志](android-smooth-streaming/2026-11-12-emulator-final.txt)、[最终原始数据及汇总](android-smooth-streaming/2026-11-12-emulator-final.json)、[截图清单](android-smooth-streaming/2026-11-12-screenshot-manifest.json)已入库。

下表为每场景三轮中位数，单位 ms。帧 P95 列为三轮各自 P95 的中位数，未把全部帧合并重算。

| 场景 | 直接 TTFT | 平滑 TTFT | 直接排空观察 | 平滑排空观察 | 直接帧 P95 | 平滑帧 P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 150 字、4 分块 | 42.43 | 72.30 | 6.78 | 265.37 | 27.97 | 16.79 |
| 5,086 字 Markdown、5 分块 | 41.75 | 69.37 | 157.66 | 466.80 | 50.09 | 72.81 |
| 150 字、8 突发分块 | 54.15 | 65.80 | 6.02 | 282.63 | 16.78 | 29.40 |

先逐轮配对再取中位数，平滑呈现相对直接显示的 TTFT 差值分别为 +29.87、+27.63、+11.80ms，排空观察差值为 +258.38、+316.18、+261.87ms；不能用表中两个中位数相减替代配对统计。直接显示共 88 帧，其中 80 帧超过约 16.67ms；平滑呈现共 266 帧，其中 222 帧超过预算。调试模拟器的基线本身也慢，长 Markdown 的额外负担仍是明确风险，不能报告稳定 60fps 或性能验收完全通过。两模式帧数和观察时段不同，不能因为平滑组总体超预算比例较低就宣称帧性能改善。原始数据与逐轮统计已由未参与开发的 Agent 独立重算核验一致。

本轮截图为合成正文，可查看[居中公式](android-smooth-streaming/screenshots/centered-math-cursor.png)、[跨行末端](android-smooth-streaming/screenshots/wrapped-text-cursor.png)、[旋转后追加](android-smooth-streaming/screenshots/rotated-text-cursor.png)、[执行过程最终回答](android-smooth-streaming/screenshots/aggregated-final-answer.png)及[重建后静默恢复](android-smooth-streaming/screenshots/recreated-baseline.png)。

性能对照是同一提交、同一渲染器的 `presentation=null` 直接显示控制组，不是旧版本基线。每个场景三轮，直接显示先于平滑呈现，来自同一个调试测试进程。TTFT 为本地首块写入至无障碍树观察到当前正文前缀的延迟上界；排空观察包括正文尾部进入窗口及滚动范围稳定 80ms，不能等同调度器耗时，也不能统一减去 80ms。帧数据来自真实 Window.FrameMetrics；内存为运行前后 Java/Native 堆和 PSS 快照，不是分配次数、长期保留量或泄漏证明。

## 尚未闭环的验收与风险

- 实体手机未连接：振动强度、节奏、系统关闭触觉、无马达/OEM/省电兼容性均未实机验证。模拟器仅验证模拟 View 回调计数，不能替代触觉手感验收。
- 三个真实入口尚无完整账号网络 E2E：主聊天真实发送、系统气泡创建/展开/贴底/拖动、VoiceInteractionSession show/hide 和实际跨进程桥接仍需设备验证。共享时间线夹具不等同这些入口已全部验收。
- 真实 SSE 重连、取消确认、同步快照如何抵达三入口，及真实进程重启后的偏好/备份回滚，当前只有状态、协议和 Robolectric 层证据。
- 性能尚未确认达标。前轮长 Markdown 显示明显额外开销；最终数据见上述表格与原始 JSON。解析次数、重组次数、分配计数未测量，发布包、多设备、独立冷/热启动和长时间内存恢复未验证；不能报告稳定 60fps、没有性能回退或可见长文已在 250ms 内排空。
- 本地 `test:full` 未执行；本轮是 PR 验证，完整发布回归由发布流程完成。远端 CI 将在推送最终提交后触发，交付时读取状态；pending 不计为成功，后续采用有截止时间的后台记录，不前台等待远端构建。

## 修改文件与复现入口

独立评审与修复闭环见 [评审记录](android-smooth-streaming/2026-11-12-independent-review.zh-CN.md)。完整代码/测试修改列表见 [文件清单](android-smooth-streaming/2026-11-12-changed-files.txt)。主要入口为：

- `StreamingPresentationState.kt`、`StreamingPresentationHost.kt`：缓冲、身份、生命周期及触觉副作用。
- `StreamingTextDecorations.kt`、`markdown/MarkdownSourceTrace.kt`、`MarkdownInlineText.kt`、`MarkdownMath.kt`、`MarkdownViews.kt`：来源映射、范围绘制、真实末端及公式。
- `WorkbenchScreen.kt`、`MessageTimeline.kt`、`ExecutionViews.kt`、`BubbleConversationActivity.kt`、assistant-holder：三个入口接入。
- `AppearancePreferences.kt`、`SettingsViewModel.kt`、appearance/backup、`AssistantBridgeService.kt` 与桥接协议：设置、持久化、回滚及助理即时同步。
- `StreamingReplyInstrumentedTest.kt`、debug 宿主和 `tools/ci/android-streaming-acceptance.init.gradle`：合成事实、帧/内存观察、截图、独立安装身份与生命周期验收。

执行命令：`npm run test:fast`、`npm run test:pr`；验收构建为 `:app:assemblePlayDebug :app:assemblePlayDebugAndroidTest`，在 `apps/android` 中显式传入 `--init-script ../../tools/ci/android-streaming-acceptance.init.gradle -Poai.streamingAcceptance=true` 追加独立安装身份。普通仪表批次会跳过该专项测试，避免打开已有账号的应用进程。APK 构建产物不入库，原始日志、JSON 和合成内容截图保存在本报告旁。
