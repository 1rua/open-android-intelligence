# PR #131 旧宿主恢复评论修复记录

日期：2026-11-11。关联 issue #130、PR #131，审核线程 `PRRT_kwDOTySHCM62BDrk`，评论 `4533712519`。本次审核起点为 `6a81f664a3c49fa366b1493c782060a63b57b187`，代码候选为 `2bda2cd4469c22a95dde9067e3c865893ca7c544`，独立规范与需求评审的固定 dev 基准及 merge-base 均为 `4cbe21b8324e7dc2b5bee44bce0174213c627980`。

## 审核结论与代码证据

审核意见成立。已支持的 `generationRecovery=true、eventSync=false` 旧宿主只收到无参数的 `startRecovery()` 调用，恢复参数仅到达新宿主事件同步。原 `startRecovery()` 遇到活动任务直接返回，已有回执使用二十五秒长轮询，失败后普通等待最长六十秒；因此明确前台或网络恢复无法独立唤醒旧宿主恢复，也无法替换其半开读取。

该结论通过公开 `timeline()`、`connectionRestored()` 及真实仓储恢复调用链确认，测试在 HTTP 边界控制响应与虚拟时间，没有通过反射调用私有方法。它确认的是本条评论指出的兼容覆盖遗漏，不代表最初现场后台断联只有这一个原因。旧宿主兼容契约见[原生事件同步](../architecture/2026-10-30-native-event-sync.zh-CN.md)。

最终[仓储实现](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt)的关键位置：

- `connectionRestored()` 第 323 行将三类恢复参数传入旧宿主路径。
- `startRecovery()` 第 1078 行复用稳定轮次与原 `recoveryJobs`，第 1100～1120 行合并退避唤醒、网络重校验与重建代次。
- 第 1126 行检查关闭状态、父作用域和原恢复任务所有者；没有新增仓储同步锁来反转既有锁顺序。
- `restoreHistory()` 第 1063 行只将 history HTTP 放入网络执行器；第 1135、1144～1147 行对 generation HTTP 应用前台预算与读取所有权检查。本地落盘、逐条发布和终态整理留在外层。
- 第 1211 行将被替换的网络请求恢复到同一任务；第 1237 行等待可合并唤醒的退避信号，保留原失败次数并清空同轮多余信号。

## 实际修复设计

每个稳定 `NativeTurn` 保存当前实际网络任务、等待标记、五秒冷却时间与最近确认的传输代次，由既有 `recoveryJobs` 锁维护。普通健康活动不取消健康读取、不跳过退避；明确前台或网络恢复可以唤醒等待，确认的新传输代次也可以单独唤醒。相同或过期代次不重复取消新的读取，重复突发信号合并且不重置失败计数。

前台 generation 长轮询等待五秒，每次 generation/history 实际网络请求预算八秒。持续前台即使没有新的生命周期或网络信号，也会在超时后沿原指数退避与抖动重试。后台保持原有二十五秒等待和底层网络预算。重校验只针对当时的网络请求，不留给本地保存结束后才开始的请求。

恢复始终使用原账号、Agent、会话、`clientMessageId` 和 `generationId`，不重新 POST 用户输入，也不调用旧宿主不支持的事件页、游标或 ACK。认证拒绝只允许凭据版本变化后恢复；TLS、安全与协议拒绝继续暂停。关闭仓储与账号退出不能被迟到信号重新启动。设备授权、Local-First 架构、用户界面、文案、通知内容、提示规则及交互均未修改。本轮无需修改 Core 或增加服务、权限、通知。

## 三条红绿反馈环

| 公开入口场景 | 修复前已运行的失败结果 | 修复后已运行的结果 | 实际证据边界 |
| --- | --- | --- | --- |
| 连续九次 generation GET 暂时失败，进入六十秒退避后收到明确恢复 | 第十次读取未发出，实际仍九次；红色用例 0.121 秒 | 同一虚拟时刻进行第十次原身份读取；绿色 0.141 秒 | 没有 POST 或 eventSync 请求 |
| running 回执之后第二次长轮询半开，返回前台重校验 | 应取消一个实际网络请求，实际零个；红色 0.132 秒 | 取消一次，第三次原身份读取完成；绿色 0.139 秒 | 只替换网络请求 |
| 持续前台半开，没有其他恢复信号 | 虚拟推进 8251 毫秒后仍未取消；红色 0.186 秒 | 八秒网络超时加首次二百五十毫秒退避，第三次读取完成；绿色 0.156 秒 | 虚拟时钟达到十秒内断言，不是真机恢复耗时 |

对应提交：`bce3d0c5`（退避唤醒）、`df54fa6d`（网络替换）、`185a8928`（独立前台预算）、`2bda2cd4`（边界回归）。三对红绿 XML、日志与最终测试 XML 保存在被忽略的 `.toolchains/background-connection-pr131-review/`，未作为构建产物入库。

## 自动化与本地阶段保护

实际五类关联回归共 **85 项通过，零失败、零错误**：

| 测试类 | 通过 |
| --- | ---: |
| `NativeConnectionReliabilityTest` | 41 |
| `NativeEventSynchronizerTest` | 21 |
| `NativeRecoveryCommitTest` | 4 |
| `NativeRecoveryOwnershipTest` | 3 |
| `NativeRecoveryDeliverySemanticsTest` | 16 |

其中新增旧宿主专项十一项，覆盖明确恢复、无信号前台超时、原后台等待、普通信号不跳退避、五秒冷却、确认重建单独唤醒与代次去重、认证/TLS/协议暂停、关闭、历史保存和本地订阅背压。保存保护用例实际挂起历史 upsert 持久化，再发送重校验与重建信号，保存未被取消，随后读取也没有继承过期重校验。发布保护用例实际让订阅者变慢，八条历史消息仍完整、有序送达。终态队列回归也确认只有所属轮次存储提交成功后才释放其队列。

从本次 worktree 的 `apps/android` 目录复跑旧宿主专项：

```bash
JAVA_HOME="$PWD/../../../../.toolchains/jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110" \
ANDROID_HOME="$PWD/../../../../.toolchains/android-sdk" \
GRADLE_USER_HOME="$PWD/../../../../.toolchains/gradle-user-home" \
./gradlew :app:testFullDebugUnitTest \
  --tests 'com.openandroidintelligence.mobile.conversations.NativeConnectionReliabilityTest.*Legacy*' \
  --tests 'com.openandroidintelligence.mobile.conversations.NativeConnectionReliabilityTest.legacy*' \
  --init-script ../../../../.toolchains/background-connection-pr131-review/robolectric-offline.init.gradle \
  --console=plain
```

标准 Android 工具链环境下可使用同一 Gradle 任务和测试选择器；上述相对路径仅配置本地复用的 Java 21、SDK、Gradle 缓存与离线 Robolectric。验证未修改生产测试策略，未使用冷启动参数。

## 独立评审与门禁状态

两个未参与开发的独立代理按规范、需求两个轴，固定 dev 基准评审完整 PR，并重点核对本轮增量。规范轴在 `2bda2cd4` 的最终补评无实质发现。需求轴首轮固定 `df54fa6d` 发现前台预算缺口，随后由 `185a8928` 和对应红绿测试修复；最终需求轴也在 `2bda2cd4` 确认原问题关闭，零项剩余实质发现；两轴均未替代设备实测。

- `git diff --check`：通过。
- `npm run test:fast -- --since 6a81f664`：通过；包含静态安全、契约与增量表面检查。
- `npm run test:pr -- --since 6a81f664`：通过，首轮最终退出码为 0；状态读取时一度未取得最终结果，随后缓存复核也返回 0。两轮日志均保留，没有将状态未完成误写为源码测试失败。PR 门禁覆盖静态安全、契约、三个会话模块及 App core/conversations 域，复用既有增量缓存。对应 XML 汇总为 App 1217、conversation-domain 47、conversation-ui 849、conversation-data 60，共 2173 项，零失败、零错误；这里包含缓存有效结果，不能与前述 85 项相加。
- 新提交 CI、原审核线程回复与解决状态：推送后按精确 SHA 记录，不引用旧 HEAD 的绿色结果。

## 设备与系统限制

本轮没有重新执行 ADB、模拟器或真实设备验收，未新增三十分钟、两小时、六小时息屏或电量测量。原 PR 的既有设备报告不能替代本次旧宿主兼容变更的设备证据。八秒网络预算与 8251 毫秒成功是可重复的自动化断言，不能宣称真机十秒目标已经实测通过。

后台仍受系统是否允许进程执行和联网约束，本轮没有更改 Android 电源管理、后台服务或前台服务实现，也没有承诺系统强制暂停时绝对实时在线。
