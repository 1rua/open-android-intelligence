# Android App Regressions 性能专项

关联问题：[#135](https://github.com/1rua/open-android-intelligence/issues/135)。目标分支：`dev`。

## 验收范围

目标是将完整 App 回归稳定控制在 8 分钟内，进一步争取 5 分钟。测试清单、用例数量、既有安全门禁和必要进程隔离必须保留。未完成的测量、未执行的测试以及无法从现有日志区分的开销均明确标记，不以推算代替实测。

## 固定基线

- 开发基点：`f6421dda`，开始工作时最新的 `origin/dev`。
- 已读取的完整运行：[42967680063](https://github.com/1rua/open-android-intelligence/actions/runs/42967680063)，源码 `657b108752449eb1f81c9b115f8a53662839b199`。
- 该次 App Job 从 05:44:38 UTC 到 05:58:20 UTC，共 13 分 42 秒；整个工作流从 05:44:08 UTC 到 05:58:25 UTC，共 14 分 17 秒。
- 第二个历史样本：[42962654289](https://github.com/1rua/open-android-intelligence/actions/runs/42962654289)，用于核对长尾波动。JUnit 报告已读取，统计和缓存边界见下文。
- 历史运行只能说明原有瓶颈；最终参数比较须使用同一源码、相同测试清单和明确的缓存条件。

## 测量方法

1. 分别记录 Job、Gradle 配置、编译、测试和整个工作流时间；列出 JUnit 类及方法耗时排名。
2. 从真实测试进程记录 Fork 次数、进程启动到测试事件的时间、CPU、内存和 GC；不把 JUnit 的累计时间误当作墙钟时间。
3. 审核测试的宿主静态状态、线程、Compose 空闲资源和 Android 环境；只有具备清理证据并通过重复、乱序及并行验证的明确清单才允许复用 JVM。
4. 核查普通 PR 的影响范围与完整门禁触发条件；比较前后实际测试标识，任何意外遗漏均失败。
5. 单 Runner 的测试执行优化先行。双 Runner 仅在包含重复编译、准备和排队的总时间更优且产物来源可靠时采用。

## 当前状态

测量与隔离审计中，尚未认定任何优化已达到性能目标。本报告将在同一 PR 中补齐可复核的数据、运行链接、验证结果及剩余瓶颈。

## 历史样本实测

以下两次运行源码不同，只用来确认耗时分布及缓存冷暖波动，不能作为优化前后因果比较。

| 运行 | App Job | 整个工作流 | 类 / 用例 | 失败 / 预期跳过 | 类累计时间 |
|---|---:|---:|---:|---:|---:|
| [42962654289](https://github.com/1rua/open-android-intelligence/actions/runs/42962654289) | 20分51秒 | 21分30秒 | 165 / 1806 | 0 / 1 | 1833.727 秒 |
| [42967680063](https://github.com/1rua/open-android-intelligence/actions/runs/42967680063) | 13分42秒 | 14分17秒 | 165 / 1836 | 0 / 1 | 1928.203 秒 |

两个样本没有删除用例；第二个样本新增 `NativeAgentBackgroundWorkTest` 24 项、`NativeConversationNotificationsTest` 6 项。唯一跳过是 Full 变体下预期不适用的 `BuiltInCapabilitiesTest`；后续逐项比较跳过身份，不能新增长尾跳过来制造提速。

较新样本的慢类排名：

| 测试类 | 用例 | 类累计耗时 |
|---|---:|---:|
| `ReplyNotificationsTest` | 99 | 82.046 秒 |
| `NativeConversationNotificationsTest` | 156 | 72.109 秒 |
| `BubbleConversationTest` | 36 | 61.496 秒 |
| `ProductionPluginHostTest` | 20 | 53.638 秒 |
| `GatewaySessionRefreshTest` | 50 | 53.180 秒 |
| `NativeAgentBackgroundWorkTest` | 34 | 52.633 秒 |
| `DeviceExecutionRecoveryTest` | 33 | 47.891 秒 |
| `SettingsHonestyCopyTest` | 13 | 39.160 秒 |
| `ConversationAttachmentPickersTest` | 54 | 38.728 秒 |
| `AgentSessionKeepAliveTest` | 72 | 38.051 秒 |

每类首个方法累计约占 JUnit 累计耗时的 54%，这包含初始化与该方法本身，不能直接当作 JVM 启动耗时。历史日志也没有 Fork 事件、CPU、内存和 GC 的完整数据，因此这些项必须用新采集器补测。

第一样本的 Gradle User Home 完全未命中；第二样本已恢复依赖、转换与大量模块编译结果，但仍实际执行 App Kotlin 编译。两次都重新计算并保存 Configuration Cache。当前未配置 `cache-encryption-key`，不能把“配置缓存功能已打开”描述为“跨 Runner 稳定命中”。官方依据见 [Gradle Actions 配置缓存说明](https://github.com/gradle/actions/blob/main/docs/configuration-cache.md)。本次不写入共享密钥；配置缓存后续工作与测试执行优化分别报告。

## 对照设计

- 固定同一分支 SHA，所有模式使用同一 App 清单与真实夹具，不共享未经验证的 Runner 编译产物。
- 手动对照的 Gradle 缓存只读，防止先结束的模式给后一个写热缓存；原 PR 门禁仍保持现有缓存写入策略。
- `isolated/default` 保持逐类 JVM 隔离与原 JIT，作为新采集器下的基线。
- `isolated/startup` 只将测试 JVM 设为 `-XX:TieredStopAtLevel=1`，比较短生命周期进程的 JIT 编译开销；隔离及所有断言不变。
- `reuse` 只将显式审计清单交给四个 JUnit Suite，其余类保留 `forkEvery=1`。先验证真实测试集合，再用固定不同种子重复、乱序和并行验收。
- `app-forks=2/4` 比较测试竞争情况，不增加并发上限，也不盲目调整 Gradle Workers。
- 性能采集模式强制实际执行测试，保留依赖和编译缓存；禁止用旧测试结果缓存充当新一轮验收。

## 第一轮固定源码 Actions 实测

固定源码 `60eedbfe4e436fd27ba8aa2c6e3e95c0c74d98c6`，以下四次 `workflow_dispatch` 在干净检出上运行。每次完整执行 **166 类、1840 个唯一用例，1839 通过、1 个相同预期跳过、0 失败**；使用严格比较器重新对照，各模式 0 个新增/遗漏/新增跳过，身份哈希一致。

| 配置 | 运行 | App Job | Gradle 配置 | App / 单测 Kotlin 编译 | Test task | Fork | 初始化累计 | GC 停顿累计 | 整个CI |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 逐类隔离 / 默认JIT / 4路 | [42977632677](https://github.com/1rua/open-android-intelligence/actions/runs/42977632677) | 776秒 | 3.833秒 | 44.91 / 41.47秒 | 498.46秒 | 166 | 672.33秒 | 40.41秒 | 916秒 |
| 逐类隔离 / 一级JIT / 4路 | [42977634823](https://github.com/1rua/open-android-intelligence/actions/runs/42977634823) | 746秒 | 5.174秒 | 58.91 / 57.86秒 | 392.08秒 | 166 | 473.68秒 | 30.79秒 | 887秒 |
| 逐类隔离 / 一级JIT / 2路 | [42978306801](https://github.com/1rua/open-android-intelligence/actions/runs/42978306801) | 551秒 | 3.435秒 | 54.18 / 35.40秒 | 364.99秒 | 166 | 205.70秒 | 12.50秒 | 638秒 |
| 安全复用 / 一级JIT / 4路 | [42978304527](https://github.com/1rua/open-android-intelligence/actions/runs/42978304527) | 646秒 | 5.011秒 | 82.36 / 60.86秒 | 376.35秒 | 124 | 426.52秒 | 27.16秒 | 736秒 |

全部 Runner 均为 4 逻辑核、约 16 GB 内存。默认四路主机 CPU 平均约 92.7%，一级 JIT 四路约 86.4%，一级 JIT 两路约 58.9%，安全复用四路约 83.1%。最低可用内存均高于 9 GB；这批样本未显示主机内存耗尽。采样不能还原未命中的瞬时峰值，GC 与初始化是多个进程累计，不能直接相减当作墙钟收益。

这轮两路 Fork + 一级 JIT 的 App Job 为 9分11秒，整个 CI 为 10分38秒，均通过；尚未满足 8分钟。四路改为一级 JIT 后 Test task 从 498.46秒降为392.08秒，但总 Job 受编译、依赖转换与准备波动影响。不同模式恢复的缓存快照仍需逐项核对，不能将所有差异完全归因于单一参数。安全复用两路的对照仍在运行。

另两次运行的 App 回归成功，但整个工作流在 Play 通道失败：`ColdStartConnectionTest.repeatedAvailabilityAndForegroundKeepTheHealthyPlatformEventOwner` 的历史请求基线断言 `expected 2 / actual 3`。这不是 App 回归失败，也不能忽略成整条 CI 成功；正在用真实 HTTP/SSE 的受控响应边界诊断。

## 本地稳定性与过滤器实证

- 两类、23 用例的定向实验使用旧 `--tests=*类名` 时仍请求166次Fork；改为Gradle标准大写简单类名过滤器后只请求2次Fork，同一23项断言全部通过。仅减少未选中类的发现/进程成本，不删测试。
- 46候选类的隔离基线：288用例，46Fork。复用后四组并发，种子135、20261111、重复135三次均精确288用例，0失败、0跳过、0新增、0遗漏，均4Fork。
- 本地首次复用初始化累计52.33→24.02秒；本地与Actions硬件、缓存不同，此数据只证明候选可重复运行及局部成本变化，不作为完整Job达标依据。
- `npm run test:fast` 初次失败是使用系统Python缺少`aiohttp`；改用工作区已有完整Core测试环境后通过，未改测试断言、业务或安装全局依赖。
- 独立评审发现并已修复：中断运行缺退出码误报成功；仅Reporter构造误报已测；采集目录旧文件混入；同名历史Profile误归属。本次采集从空目录开始，未完成数据失败，Profile与本次输出路径及内容摘要绑定。

## 冷启动测试的稳定性修复

两次真实 Play 失败都在 `ColdStartConnectionTest.repeatedAvailabilityAndForegroundKeepTheHealthyPlatformEventOwner`，而 App 通道通过。源码显示网络路由变化会先重开平台 SSE，再异步恢复工作台；`LIVE` 只说明平台流健康，不保证此前的历史同步已经完成。

本地先精确复现 `expected 2 / actual 3`，再做受控反例：不发送任何重复网络或前台事件，只释放初始路由历史连接，请求数仍从2变3。由此确认原测试的基线早于初始同步完成，不能把迟到的初始请求归因于五次重复回调。

修复只改变测试同步边界：真实 HTTP 响应使用闩锁，先确认 SSE 健康且初始历史仍在等待，再释放响应，并等唯一正文进入当前工作台后记录计数。五轮重复回调、平台订阅计数、Native历史计数和最终LIVE断言全部保留。没有改业务、缩短SDK覆盖或增加固定Sleep。

本地验证：原方法连续3轮通过；完整ColdStart类2轮各19项通过、0失败/错误/跳过，与失败CI原始19个方法身份完全一致。最终分支仍由完整Actions重新验证。

## 缓存可比性修正

第一轮真实缓存键及命中集合见 [缓存比较原始表](android-app-ci-performance/cache-comparison.json)。默认四路与一级JIT四路恢复同一份gateway缓存，均133个任务FROM-CACHE；两路隔离与四路复用恢复同一份plugins缓存，均166个任务FROM-CACHE。后一份额外命中33个模块编译任务，因此不能把跨组全部墙钟改善归因于JIT或Fork。

手动对照现改为所有Android通道只读，并启用Gradle Actions提供的实验输入`gradle-home-cache-strict-match`，只恢复本通道缓存，避免其它模块通道刚写入的快照改变下一轮输入。该输入仍需随上游版本核对，且最终仍以日志中真实恢复键与FROM-CACHE集合确认快照可比。PR及dev/main的非手动门禁保留原缓存写入策略。

串行GC是下一轮显式对照选项，默认暂不采用。它只改变测试JVM垃圾回收策略，仍保留所有断言、SDK、超时和逐类隔离。下一轮将记录Runner CPU型号与可用CPU配额，补充首轮仅记录核数的环境限制。

完整历史类与方法排名、机器统计见 [性能证据目录](android-app-ci-performance/)。原始日志/XML仍由各运行的Actions artifact提供，本目录不提交构建产物。

## 测量口径与保留的不确定项

`fork_and_worker_bootstrap` 是Gradle请求Fork到Worker开始测试的日志事件区间，包含调度和Worker引导，不是纯JVM启动。并行控制台会合并/缓冲事件，因此同时报告Fork请求数、已匹配引导事件和未匹配数；不对缺事件的进程伪造启动耗时。

Robolectric公开指标`initialization`覆盖其初始化范围；JUnit类/方法时间同时包含有效断言、规则和部分准备/清理。嵌套指标不能相加，现有观测不能精确剥离“仅测试主体”以及纯JVM启动，汇总保留`unknown`。这也是后续进一步细分剖析的入口。

原门禁没有生成行覆盖率/分支覆盖率报告，本专项没有将测试身份一致换算为覆盖率百分比。已验证的是类、方法及SDK展开后的真实执行集合一致，业务源码、既有断言及必要隔离保留；没有证据的覆盖率数值明确不提供。

未采用跨Runner编译产物分发或额外App分片。现有历史分片记录显示重复编译曾抵消测试并行收益；本轮先以单Runner实测改进执行。没有本轮双Runner同缓存的性能证据，不能宣称分片一定更慢，后续若继续评估须把两份编译、排队和整个CI关键路径一并计入。

## 第二轮固定源码与空远端缓存对照

固定源码`7109adb9531356cb0ae6a652db1cbcf1516fcde6`。三次手动运行都明确只读、严格限定本通道，且日志均为远端Gradle Home未命中；编译缓存命中集合一致，同次构建内的少量资源生成命中有差异。记录见[第二轮缓存事实](android-app-ci-performance/second-cache-comparison.json)与[完整指标概要](android-app-ci-performance/second-comparison-7109adb9.json)。

| 配置 | 运行 | App Job | Test task | 整个CI | 结果 |
|---|---|---:|---:|---:|---|
| 默认GC / 一级JIT / 2路 | [42983699710](https://github.com/1rua/open-android-intelligence/actions/runs/42983699710) | 14分29秒 | 489.41秒 | 16分52秒 | 通过 |
| 串行GC / 一级JIT / 2路 | [42983703887](https://github.com/1rua/open-android-intelligence/actions/runs/42983703887) | 16分02秒 | 525.73秒 | 19分09秒 | 通过 |
| 串行GC / 一级JIT / 4路 | [42983701444](https://github.com/1rua/open-android-intelligence/actions/runs/42983701444) | 14分01秒 | 391.05秒 | 16分40秒 | 通过 |

三组仍为166类/1840用例，0错误遗漏、0新增跳过，真实类选择检查通过。Runner CPU型号不同（EPYC 9V74/7763），因此这轮可以排除明显有利的GC结论，不能声称参数收益精确到上述差值。普通PR运行[42983598051](https://github.com/1rua/open-android-intelligence/actions/runs/42983598051)恢复旧App缓存后App为11分36秒、整个CI12分11秒，所有门禁通过，ColdStart修复在真实Play通道通过。

## 最终采用项与验收结果

1. **采用精确类过滤器**：选测只为真正选择的类启动进程，保留全部实际测试和跨模块核心组。
2. **采用CI App的一级JIT、维持原四路Fork和默认GC**：第一轮同源码、同恢复缓存的两次运行中Test task从498.46秒降到392.08秒，减少106.38秒（21.3%）；整个App Job从12分56秒降到12分26秒，整个CI从15分16秒降到14分47秒。只有各一份样本，不能据此承诺稳定幅度。
3. **安全复用保持显式实验**：46候选已通过本地重复/乱序/并行288项集合核对，完整Actions复用模式1840项集合也一致；但整Job没有胜过最佳隔离样本，默认继续逐类隔离。
4. **不采用串行GC、不继续盲目增加Fork、不新增App分片**：现有实测没有证明这些选择有稳定收益。
5. **保留真实性能采集与逐轮类集合检查**：每轮报告真实类/方法、Fork、初始化、CPU/内存/GC、配置编译、整个CI；不完整运行失败，无法归属的数据保持未知。

**8分钟稳定目标及5分钟目标均未达成。** 当前最佳完整App样本为9分11秒，但缓存快照和Runner存在波动，不能当作稳定承诺。冷缓存样本仍为14–16分钟。本次PR提供可独立合入的执行优化与精确选测、保持隔离的实验入口、稳定性修复和可复核证据，问题#135继续开放，不以PR合并自动关闭性能目标。

主要剩余瓶颈：98个Robolectric类重复加载Android框架与原生运行时；每类独立JVM的发现/启动成本；App与单测Kotlin编译每轮约90–140秒；Runner CPU型号与缓存快照波动；Configuration Cache没有跨Runner稳定密钥所以每轮重新配置。通知及真实HTTP大类存在仅cancel未join、夹具请求线程未确认结束等清理缺口，后续须先补所有权/结束证据，再考虑扩大安全复用范围，不能直接放开进程。

本地验收已执行：`npm run test:fast`通过（261项Python回归与越权面门禁）；`npm run test:pr`通过（同261项、越权面门禁与完整166类1840项App回归，0失败/1既有预期跳过）；规划、采集与复用边界专项测试通过；完整差异空白检查和文档本地链接检查通过。独立规范/需求评审三轮已收敛，记录见[评审回执](../reviews/2026-11-11-pr136-ci-performance-review.zh-CN.md)。最终头部的Actions结论单独补录，不使用旧提交成功替代。
