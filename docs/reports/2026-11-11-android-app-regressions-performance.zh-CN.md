# Android App Regressions 性能专项

关联问题：[#135](https://github.com/1rua/open-android-intelligence/issues/135)。目标分支：`dev`。

## 验收范围

目标是将完整 App 回归稳定控制在 8 分钟内，进一步争取 5 分钟。测试清单、用例数量、既有安全门禁和必要进程隔离必须保留。未完成的测量、未执行的测试以及无法从现有日志区分的开销均明确标记，不以推算代替实测。

## 固定基线

- 开发基点：`f6421dda`，开始工作时最新的 `origin/dev`。
- 已读取的完整运行：[42967680063](https://github.com/1rua/open-android-intelligence/actions/runs/42967680063)，源码 `657b108752449eb1f81c9b115f8a53662839b199`。
- 该次 App Job 从 05:44:38 UTC 到 05:58:20 UTC，共 13 分 42 秒；整个工作流从 05:44:08 UTC 到 05:58:25 UTC，共 14 分 17 秒。
- 第二个历史样本：[42962654289](https://github.com/1rua/open-android-intelligence/actions/runs/42962654289)，用于核对长尾波动。JUnit 报告正在读取。
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

首个方法约占 JUnit 累计耗时的 54%，这包含初始化与该方法本身，不能直接当作 JVM 启动耗时。历史日志也没有 Fork 事件、CPU、内存和 GC 的完整数据，因此这些项必须用新采集器补测。

第一样本的 Gradle User Home 完全未命中；第二样本已恢复依赖、转换与大量模块编译结果，但仍实际执行 App Kotlin 编译。两次都重新计算并保存 Configuration Cache。当前未配置 `cache-encryption-key`，不能把“配置缓存功能已打开”描述为“跨 Runner 稳定命中”。官方依据见 [Gradle Actions 配置缓存说明](https://github.com/gradle/actions/blob/main/docs/configuration-cache.md)。本次不写入共享密钥；配置缓存后续工作与测试执行优化分别报告。

## 对照设计

- 固定同一分支 SHA，所有模式使用同一 App 清单与真实夹具，不共享未经验证的 Runner 编译产物。
- 手动对照的 Gradle 缓存只读，防止先结束的模式给后一个写热缓存；原 PR 门禁仍保持现有缓存写入策略。
- `isolated/default` 保持逐类 JVM 隔离与原 JIT，作为新采集器下的基线。
- `isolated/startup` 只将测试 JVM 设为 `-XX:TieredStopAtLevel=1`，比较短生命周期进程的 JIT 编译开销；隔离及所有断言不变。
- `reuse` 只将显式审计清单交给四个 JUnit Suite，其余类保留 `forkEvery=1`。先验证真实测试集合，再用固定不同种子重复、乱序和并行验收。
- `app-forks=2/4` 比较测试竞争情况，不增加并发上限，也不盲目调整 Gradle Workers。
- 性能采集模式强制实际执行测试，保留依赖和编译缓存；禁止用旧测试结果缓存充当新一轮验收。
