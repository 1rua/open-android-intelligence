# OAI Android 首次启动引导独立代码审查

- 审查日期：2026-10-25（Asia/Shanghai）
- 目标分支：`feature/android-onboarding`
- 目标提交：`e1932cf80c53d754ffa58862505705a8bad1c11b`
- 比较基线：`13cb03cab74e98e24fd47057a131fe06f4652c81`

本报告中的源码行号均属于目标提交，不属于当前工作区。审查期间同一分支出现后续修复提交，因此使用 `git show e1932cf8:<path>` 和该提交的独立归档核验，未将后续修正混入结论。目标差异为 22 个 Kotlin 文件、增加 4603 行：15 个新增生产文件、4 个新增测试文件，以及 3 个接入文件。审查不修改业务代码，不操作账号、设备或系统权限，也不提交、推送或合并分支。

## 1. 审查结论

**FAIL。** 首次引导的页面结构和大部分基础设施已经实现，但目标提交仍有必须修复的重看退出缺陷，以及终局动效、特殊权限状态和返回处理方面的确定问题，不能据此验收完整流程。

- **阻断项：** 设置中重看引导并完成后，`replay` 不清除，宿主继续显示 `COMPLETED` 空页面，而不是回到登录页或工作台。
- **核心动效不符合需求：** 普通模式下，转场没有接入登录内容；宽按钮起点实际绘制为大圆，而非按钮轮廓。
- **权限体验不完整：** 通知使用权没有读取真实授权状态，屏幕采集被错误导向应用详情设置。
- **验证结果：** 目标提交原有 39 项单元测试全部通过；补充 9 项检查中 6 项通过、3 项失败，分别复现重看完成、起点几何和内容透明度衔接问题。
- **证据边界：** 已独立编译全部 15 个新增生产 Kotlin 文件及相关测试；完整 Gradle 任务被沙箱的本地套接字限制阻止，未取得 APK 构建、设备 UI、真实权限对话框或远程 CI 通过的证据。

## 2. 问题清单

### 🔴 严重（必须修）

#### R01 · 重看完成后仍留在引导中，显示空白完成页

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingPreferences.kt:50`；关联 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingState.kt:56`、`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:253`、`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:414`。
- **问题：** `markCompleted()` 只设置 `stage=COMPLETED`、`finished=true`，保留 `replay=true`。但宿主是否显示引导取决于 `replay || !finished`，因此重看完成后仍进入 `OnboardingFlow`，而 `COMPLETED` 分支是 `Unit`。
- **影响与触发：** 已完成用户从设置进入重看，再通过“我已部署”或教程完成按钮结束流程，都会到达这一状态。`Settled` 还会绘制不透明全屏覆盖面，最终没有可见登录表单或工作台。退出重看或重启进程可能恢复，但不能要求用户靠重启完成正常导航。
- **证据：** 补充测试直接调用目标提交的 `OnboardingPreferences`：`markCompleted → startReplay → markCompleted`，得到 `OnboardingState(stage=COMPLETED, finished=true, lastGatewayId=null, replay=true)`，`shouldShow` 仍为 `true`，断言失败。
- **修复建议：** 在完成操作中同时、原子地清除 `replay`，保证完成后的状态立即退出引导；补充首次完成、重看两条完成入口、退出重看和进程重建测试。

### 🟡 中等（建议修；涉及明确验收要求的项目应在验收前修复）

#### R02 · 终局转场没有承载登录内容，内容层在普通模式下始终不可见

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:261`；关联同文件 `:269`、`:292`，以及 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealTransition.kt:140`。
- **问题：** `ButtonRevealTransition` 的内容插槽传入的是空的 `FadingOnboardingSurface`，不是登录页或登录品牌内容。外层内容透明度在进度 `0.42` 后才从 0 增大；内层表面透明度却在 `0.42` 前降至 0，随后直接不参与组合。
- **为什么是问题：** 普通动画中两个透明度的乘积恒为 0，用户只能看到覆盖面展开；真正的登录分支等 `onFinished()` 改写宿主状态之后才出现，不是“登录内容从扩展面内渐进显现”。目标提交中没有 `RevealLandingMark`，不能用后续版本的同名实现为这个提交判定通过。
- **证据：** 对 `0..1` 共 1001 个进度采样，内容插槽的最大组合透明度为 `0.0`；对应补充断言失败。该检查验证的是透明度公式与传入内容，不是截图渲染测试。
- **修复建议：** 将真实目标内容或与真实登录页共用的展示内容放入裁剪区域，并让宿主切换与该内容的生命周期衔接。不要用空表面冒充登录内容；增加中间进度确实存在可见目标内容的测试。

#### R03 · 宽按钮在进度 0 被当成圆形，起点形状与按钮不一致

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealTransition.kt:325`；关联同文件 `:309`、`:316`、`:344`。
- **问题：** 圆角等于短半边时就进入 `drawCircle(radius=halfWidth)`。对于宽大于高的胶囊按钮，`corner` 本来就等于 `halfHeight`，所以这一条件从进度 0 开始便成立，并不意味着宽、高已经相等。
- **为什么是问题：** 例如 `320×64` 的按钮应从 `320×64` 胶囊展开，实际第一帧却画出半径 `160`、直径 `320` 的圆，填充和描边都偏离按钮轮廓。早期内容裁剪又使用短边半径，覆盖面与裁剪面并不共用同一轮廓。
- **证据：** 通过记录真实生产 `drawRevealCover()` 的 `DrawScope` 调用，进度 0 得到两次 `drawCircle(... radius=160.0 ...)`，没有 `drawRoundRect`，补充断言失败。没有用另写的几何替身取代生产绘制函数。
- **修复建议：** 在宽、高半径都接近圆半径时才切换圆绘制，之前继续绘制圆角矩形；填充、描边和内容裁剪应使用同一插值几何。补充宽按钮、方形按钮和屏幕边缘按钮的实际绘制测试。

#### R04 · 特殊权限的状态不真实，屏幕采集入口也指向错误设置页

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:191`；关联 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:335`、`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:223`。
- **问题：** 对 `isSpecialSystemAccess && manifestPermissions.isEmpty()` 固定返回 `NOT_REQUIRED`，同时命中通知使用权和屏幕采集。展示层又把它们统一呈现为“未开启 · 使用时可去系统设置”，按钮为“前往设置”。
- **影响：** 通知使用权即使已经在系统中开启，返回页面后仍由同一解析器产生固定状态，无法显示真实结果；屏幕采集不存在可在应用详情中持久打开的同类开关，该按钮却走 `openAppDetailsSettings()`，不能完成文案暗示的操作。
- **特别澄清：** 目标提交中 `OnboardingFlow.kt:134` 的 `screen_capture -> Unit` 确实是空分支，但当前屏幕采集卡片的按钮走的是 `onOpenSystemSettings`，不是这个分支。本报告不将其描述成“点击了解无反应”；此提交的可见文案不是“了解”。
- **修复建议：** 通知使用权复用项目现有通知绑定能力查询真实状态，并在恢复前台时重查；屏幕采集单独显示“每次使用时确认”，提供真实解释而非无关设置入口。需要使用屏幕采集时再由业务调用系统授权流程。

#### R05 · 首次引导欢迎页吞掉系统返回，但不退出或隐藏引导

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:193`；关联 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:424`、`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingPreferences.kt:65`。
- **问题：** `BackHandler(enabled=true)` 总是消费返回。欢迎页没有上一步，会调用 `onExit()`；宿主只调用 `endReplay()`，而首次引导的 `replay=false`，该方法立即返回，不改变任何状态。
- **为什么是问题：** 注释承诺“欢迎页再按返回时退出引导”，实际首次流程的系统返回没有效果。这里指的是返回事件，不是说 Home 键也无法离开应用。
- **证据：** 补充测试确认初始状态调用 `endReplay()` 前后完全相同；系统返回被消费的调用链由目标源码确认，未冒充设备导航测试。
- **修复建议：** 将首次流程的 Activity 返回与重看退出分开；在首次欢迎页允许系统默认返回，或者提供明确的会话内退出状态，但不要顺带把引导标记为完成。

#### R06 · 动画期间返回上一页，后台转场仍会把引导标记为完成

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:173`；关联同文件 `:188`、`:193`。
- **问题：** 展开期间只拦截指针触摸，不限制系统返回。返回事件可以把阶段从部署页改为权限页，或从教程页改为部署页，但 `rememberCoroutineScope()` 所属的整个流程仍在组合中，动画协程不会因此取消；动画结束后仍无条件执行 `onFinished()`。
- **影响与触发：** 用户点击完成按钮后，在动画结束前按系统返回，虽然请求回到上一阶段，稍后仍被标记为已完成并切走。指针事件拦截不等于系统导航拦截。
- **证据边界：** 确认了完整控制流，没有执行设备上的快速返回操作，也没有把手写时序模拟标为 UI 复现。
- **修复建议：** 明确定义动画期间的返回策略：禁止这一小段时间内的阶段切换，或者在返回时取消对应转场任务且不调用完成回调。增加返回与动画完成竞争的流程测试。

#### R07 · 在组合主线程同步扫描已安装插件，且引导隐藏时仍会执行

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:403`；关联 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginInstallStore.kt:56`、同文件 `:146`、`:171`。
- **问题：** `remember(onboardingPluginRevision)` 中直接执行 `installStore.listInstalled()`，后者包含目录遍历、清单文件读取和 JSON 解析。该计算位于 `if (onboardingVisible)` 之前，所以首次组合、插件修订变化时，即使引导并未显示也会计算。
- **为什么是问题：** `remember` 是缓存组合结果，不是后台调度器；这些文件操作仍发生在 UI 线程，插件数量增加或存储变慢时会拖慢启动与界面更新。本次没有量测耗时，不声称已经复现 ANR。
- **线程安全澄清：** `isEnabled()` 本身不是发现点。其底层注册表为 `ConcurrentHashMap`，状态读取有可见性保证，没有证据支持“registrationFor 线程不安全”的判断。
- **修复建议：** 由插件宿主暴露已计算的启用能力快照，或在 IO 调度器计算后发布状态；至少将仅用于引导的计算限制到引导需要它时。增加隐藏引导时不触发扫描、慢存储不阻塞主线程的检查。

### 🔵 轻微（可选）

#### R08 · 半色调装饰的深浅主题判断反向

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/HalftoneOverlay.kt:59`。
- **问题：** 用前景色 `onSurface.luminance() < 0.5f` 判断背景是否为深色，再选择 `Screen` 混合模式。正常浅色主题的前景是深色，条件反而为真；深色主题则相反。
- **影响：** 与文件中“深色背景用 Screen、浅色背景普通叠加”的设计意图相反，浅色背景上的深色网点无法按预期加深背景。此项基于实际分支与混合公式，未进行深浅色截图验收。未调用的 `printGrain` 内也有同类判断，但不另算当前运行故障。
- **修复建议：** 依据实际背景色而非前景色选择混合模式，或使用不依赖背景判断的普通透明叠加。

#### R09 · “每帧只重绘、不重组”的注释与当前数据传递不符

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealTransition.kt:107`；关联 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:261`、同文件 `:263`。
- **问题：** 外层组合直接读取每帧更新的 `revealPhase`，并把当帧的不可变 `phase` 值作为参数传入组件。仅在内部使用 `drawBehind` 或 `graphicsLayer` lambda，不会消除外层已经建立的组合读取依赖。
- **影响：** 不能据此宣称整个流程每帧只重绘。这不是“快照回写失效”的证据，而是优化说明过度；本次没有测量重组次数或帧耗时。
- **修复建议：** 如果确实需要绘制阶段读取动画值，分离结构阶段与绘制进度，以状态或进度读取函数传递；否则先改正注释，避免形成错误维护前提。

#### R10 · 存在无效条件和未使用导入，应清理而非算作功能实现

- **位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:230`；未使用导入示例 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:24`。
- **问题：** 欢迎页某处 `if (reduceMotion) ... else ...` 两侧文案完全相同，对输出无影响。另核对到 11 个未使用导入：`OnboardingFlow.kt:24`、`:33`、`:34`、`:50`；`DeploymentChoiceScreen.kt:24`、`:54`；`GatewayGuideScreen.kt:58`；`PermissionsScreen.kt:50`；`WelcomeScreen.kt:4`、`:33`、`:35`。这些文件均位于本报告列明的 onboarding 包内。
- **为什么是问题：** 不构成已证实的运行崩溃，但违反本次明确的死代码清理要求，并容易让读者误以为该文案具有减少动画适配行为。
- **修复建议：** 删除无效条件、未使用读取和导入；同时删除或明确约束不可达的空权限分支，避免未来新入口误接到空操作。

### 审查重点 A—D 的明确答复

#### A. 每帧回写、落定覆盖层和裁剪边界

1. **每帧回写确实生效，不是 Compose 快照丢失。** `animateTo` 的回调接收当前 `Animatable`，其中 `value` 是当帧值；赋给 `mutableStateOf` 支持的 `revealPhase` 会产生状态更新。补充测试使用真实 Compose `Animatable`、测试帧时钟和快照状态，记录到 9 帧，包含 `0..1` 中间值，最终为 `1.0`。这不等于已经验证屏幕每一帧的视觉表现。
2. **普通首次完成不会留下永久拦截层。** `markCompleted()` 使 `shouldShow=false`，`MainActivity` 移除整个引导分支；覆盖层随之离开组合树。即使尚在 `Settled`，组件的指针拦截条件也是 `phase is Expanding`，不是 `Settled`。重看路径的真实故障是 R01：宿主没有切换，留下空白覆盖面，而不是“登录页已在下面但仍被永久拦截触摸”。
3. **进度 1 的几何覆盖成立。** `revealRadius()` 取按钮中心到容器四角距离的最大值；`revealExtentAt(..., 1f)` 得到该半径，圆形裁剪覆盖四角，实测补充用例通过。问题在早期轮廓和实际内容接入，见 R02、R03。此结论针对传入容器坐标，不代替设备系统栏/异形屏裁剪验证。

#### B. MainActivity 的接入与既有副作用

- **括号和原有分支：通过源码完整性检查。** 删除新增 import、引导包装块及其新增闭合括号后，目标 `MainActivity.kt` 与父提交文件逐字节一致；该文件净新增 42 行，没有删除原代码。原来的登录/工作台代码没有被重写，只是放进 `else`。
- **插件列表计算：有同步 IO，见 R07；不能把它归因于 `isEnabled()` 的注册表并发错误。** 核对了 `ProductionPluginHost.isEnabled()`、`PluginKernel.registrationFor()`、`PluginStateMachine` 的实现。
- **已有副作用仍会执行。** `MainActivity.kt:333` 的 `LaunchedEffect(Unit)`、以及更早声明的其他效果均不在新增可见性分支内。`restoreSessionIfAvailable()` 不是每次都会发网络请求：`GatewayRuntime.kt:949` 起先检查网关、账号、设备编码和刷新凭据，真正全新、无账号的状态会提前返回；具有可恢复会话的升级用户或重看用户则仍可能恢复连接。
- **已有通知权限副作用也没有引导可见性保护。** `MainActivity.kt:185` 的效果在连接成功、通知开关开启、SDK 满足要求、尚未申请且权限未获准时，可以主动启动通知权限申请；`ReplyNotifications.kt:37` 的开关默认值为开启。不能仅凭新引导中的“只由用户点击触发”注释断言整屏不存在自动弹窗。这里确认的是有条件可达路径，未实际制造账号和权限状态去复现。
- **建议：** 明确哪些既有后台恢复应保留，哪些前台弹窗应延迟到引导退出后；不要为了隐藏引导期间的弹窗而直接删除原有会话恢复或清理账号。

#### C. 权限集合、BLOCKED 和 screen_capture

- **没有把 `mutableSetOf` 直接判为竞态。** `requestedInSession` 的已知写入来自主线程用户操作/Activity Result，已知读取来自组合和生命周期回调；没有发现后台访问的调用证据。可以用主线程约束和封装提高可维护性，但本次不报告已发生的多线程损坏。
- **BLOCKED 是启发式推断，不是系统返回的永久拒绝标志。** 源码条件确实是“未授权、没有 rationale、会话内申请过”。但“在系统设置撤权后同一进程必然保留原集合”并未证实；普通权限撤销可能导致进程退出，不能忽略这一点而直接报确定缺陷。该场景列为待设备验证风险，不计入已复现问题数。
- **进程重启不会自动恢复被系统永久拒绝权限的弹窗能力。** 本地集合清空只会改变本应用的推断依据，不能改变系统的权限历史；相关注释应避免作出超出该集合能力的承诺。
- **`screen_capture -> Unit` 与可见按钮必须分开审查。** 当前卡片进入应用详情设置的真实错导见 R04；不能拿后续版本中的“了解”按钮文案代替目标提交的实现。

#### D. 持久化与重看推理

| 操作 | 目标提交行为 | 结论 |
| --- | --- | --- |
| `setStage()` | 仅阶段变化时持久化，并保留其余字段 | ✓；有去重，无假异步 |
| `setLastGateway()` | 去重保存教程选择 | ✓；补充进程重建测试通过 |
| `markCompleted()` | 保存 `COMPLETED/finished=true`，但保留 `replay` | ✗；R01 |
| `startReplay()` | 内存中进入欢迎页、设置 `replay=true`，不清空完成历史 | 设计方向合理 |
| `endReplay()` | 仅对重看生效，回到欢迎或完成阶段，保留 `finished` | 重看退出合理；不能承担首次返回职责，见 R05 |
| 新建 `OnboardingPreferences` / 进程重启 | `read()` 把 `replay` 设为 `false`，读取完成标记与教程选择 | ✓；补充测试通过 |

因此，用户提出的“**下次启动因为 read() 清除 replay，所以不会再显示引导**”这一局部推理成立；但不能推出“**当前重看完成没有问题**”，因为当前进程不会重新执行 `read()`。

## 3. 需求符合性逐项对照

符号说明：✓ 表示已核对该实现或对应自动化证据；✗ 表示至少一项明确要求未满足。“未验证”不等于通过，不以静态检查代替设备体验验收。

| 编号 | 需求 | 实现位置 | 对照结果 |
| --- | --- | --- | --- |
| 1 | 欢迎页：品牌标识、应用名、简介、轨道示意、“开始使用” | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:56`、同文件 `:85`、`:160`、`:171`、`:219`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/OrbitalDiagram.kt:68` | **✓ 结构齐全。** 轨道是装饰性说明，未拿它充当已连接状态。深浅色装饰分支另见 R08。 |
| 2 | 权限卡片、三组区分、动态可见、标准申请/特殊设置、恢复刷新、可跳过、不阻断、不混淆 Agent 授权、不诱导/重复弹窗 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:73`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:76`、同文件 `:181`、`:266`、`:335`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:92`、同文件 `:104`、`:113` | **✗ 部分满足。** 分组、按平台/插件筛选、Activity Result 申请、恢复前台重查、跳过和授权边界说明存在；通知使用权不是真实状态，屏幕采集错导，见 R04。既有通知弹窗效果没有引导保护；系统撤权与永久拒绝细节未做设备验证。 |
| 3 | A 已部署直接转场；B 未部署进入教程 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:74`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:236` | **✓ 分支选择实现。** A 直接调用 `beginReveal`，B 设置 `GUIDE`，A 不要求阅读教程；完成转场本身仍受 R01—R03 影响。 |
| 4 | 三套教程、名称/简介/前置/安装/配置建号/验证、分步编号、终端框、复制、官方仓库、真实命令 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:78`、同文件 `:139`、`:191`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:87`、同文件 `:365`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/TerminalBlockView.kt:131` | **部分满足；命令与步骤须分别核验。** 三份数据和共享模板存在，剪贴板为真实写入，未假装部署成功。不能用“数据非空”的单测证明命令可部署或配置步骤完整；未执行真实宿主安装。 |
| 5 | 按钮位置与形状起点、非线性缓动、展开覆盖、登录内容渐进、减少动画、非简单淡入淡出 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:171`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealTransition.kt:115`、同文件 `:210`、`:325` | **✗。** 有按钮几何、强调缓动、覆盖几何和减少动画分支；每帧状态回写及最终四角覆盖检查通过，但起点错画为圆、登录内容没有接入，见 R02、R03。 |
| 6 | 首启与进度恢复、完成直达、不伪造连接、设置重看、返回/系统导航、保持原登录逻辑 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingPreferences.kt:29`、同文件 `:74`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt:543`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:397`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:193` | **✗。** 普通完成、持久化恢复、设置入口和原登录源码完整性通过；重看完成、首次返回、动画期间返回失败，见 R01、R05、R06。没有将“读完教程”写成“网关已连接”。 |
| 7 | Compose+M3、无平行框架、屏幕/安全区/深浅色/字体缩放、可访问性、登录无缝整合、动效与内容解耦可扩展 | 四个 `screens/*.kt` 的滚动布局、系统栏 padding、语义；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:372`、`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:259`；`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/motion/ButtonRevealTransition.kt:115` | **✗ 完整要求尚未满足。** 复用 Compose/M3、主题令牌、可滚动正文、安全区处理、读屏标签和内容插槽；但登录衔接有 R02，主题有 R08。未在不同屏幕、字体缩放、TalkBack 和减少动画系统设置下做 UI 验收，不能把这些项写成已通过。 |

### 六项项目规范逐条核对

| 规范 | 结果 | 依据与边界 |
| --- | --- | --- |
| 1. 不得伪造实现与死状态 | **✗** | 特殊权限用固定状态替代真实查询（R04），转场没有真实目标内容（R02），存在无效条件和空分支清理项（R10）。没有发现伪造网关连接成功；剪贴板反馈也不是假异步。 |
| 2. 优先平台标准原语 | **✓** | 使用 Compose、M3、Activity Result、Lifecycle、SharedPreferences、系统设置和剪贴板；未引入平行框架。 |
| 3. 禁止 `Color(0xFF...)` | **✓ 静态核对** | 新增 19 个 Kotlin 文件未发现此类颜色构造，颜色来自语义主题；项目既有状态颜色令牌不另作违规。 |
| 4. 禁止散值 `RoundedCornerShape(...dp)` | **✓ 静态核对** | 相关形状使用 `AppRadius` 或项目已映射的 Material 形状；插值绘制的按钮半径是动画几何，不误报为组件圆角散值。 |
| 5. 中文 KDoc 与设计意图 | **✓，但需纠正不准确承诺** | 新增 19 文件有中文 KDoc，说明设计目的；R09 等注释与实际调用关系不符，不能因有注释就认为行为正确。 |
| 6. 不泄露密钥、真实凭据和个人信息 | **✓ 静态未发现** | 核对目标增量、教程和测试，未发现真实密钥或个人凭据；这不是依赖扫描、历史仓库或完整安全审计结论。 |

## 4. 亮点

- 状态模型、持久化、教程数据、页面与动画绘制分开，三套教程共享模板；增加宿主不必复制整套页面。
- 使用标准 Activity Result 权限契约，恢复前台时重新查询，并在 `DisposableEffect.onDispose` 移除观察者；未发现对应生命周期监听泄漏。
- `rememberCoroutineScope()` 位于 Composable 内，离开流程时由 Compose 管理取消；R06 是阶段切换仍保留同一作用域的问题，不是作用域被放在 Composable 外。
- 权限页明确说明“系统权限不等于 Agent 授权”，允许跳过可选权限，不把拒绝当作登录阻断条件。
- 终端复制确实写入系统剪贴板，成功反馈后的延时复位有真实动作前提；装饰性空语义和无按钮状态的空回调不误报为空功能。
- 四角最大距离的最终覆盖计算和越界进度夹取有原始单测，独立补充检查也确认最终裁剪覆盖成立。
- 原登录/工作台代码通过逐字节还原检查，新增流程没有借机重构或重置既有账号逻辑。

## 5. 验证记录

### 5.1 范围、完整性和静态审查

实际执行过的主要命令：

```bash
git status --short
git log -1 --stat
git show --no-patch --format='%H%n%P%n%s' e1932cf8
git show --stat --oneline e1932cf8
git diff --name-status e1932cf8^...e1932cf8
git diff --check e1932cf8^ e1932cf8
git archive e1932cf8
```

- `git log -1 --stat` 表明共享分支在审查过程中已经前进；实际审查对象始终固定为 `e1932cf8`。没有把后续提交中的修复当成本提交的事实。
- 逐文件读取全部 15 个新增生产文件、4 个新增测试文件和 3 个接入文件的目标差异；追查了插件存储、插件内核、通知权限副作用和会话恢复实现。
- `git diff --check e1932cf8^ e1932cf8` 通过。
- 通过只读脚本扫描目标提交文件：新增 Kotlin 文件 19 个，硬编码颜色、散值圆角、缺中文 KDoc、常见密钥签名的匹配结果均为 0；11 个未使用导入经实际用途核对列入 R10。模式扫描不证明不存在所有秘密。
- 主 Activity 还原检查：移除新增 import、引导包装块和新增的 `else` 闭合括号后，与父提交完全一致，输出 `main_activity_original_body_identical: true`、`added_lines: 42`、`removed_lines: 0`。

### 5.2 完整 Gradle 任务尝试：未能执行

在目标提交的独立归档中，使用已缓存的 Gradle 8.12、JDK 17、Android SDK、独立可写的 Gradle 用户目录和只读依赖缓存，实际尝试：

```bash
gradle --offline --no-daemon --max-workers=2 --no-build-cache --console=plain \
  :app:testFullDebugUnitTest \
  --tests 'com.openandroidintelligence.mobile.onboarding.*'
```

这是执行命令的参数摘录；实际使用的是本机缓存的 Gradle 可执行文件，设置了 `GRADLE_USER_HOME`、`GRADLE_RO_DEP_CACHE`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 和固定目标 `OAI_GIT_COMMIT`。

结果：退出码 `1`，在任务执行前无法启动 Gradle daemon，根因为 `java.net.SocketException: Operation not permitted`（沙箱禁止本地套接字）。`--no-daemon` 仍需要本次构建使用的单次 daemon。**这既不是 Gradle 测试通过，也不能归因于目标代码编译失败。**

### 5.3 独立 Kotlin/Compose 编译与 JUnit：实际执行

为避免把环境限制当作免测理由，在独立归档外创建临时审查测试工具，使用本机缓存的真实 Kotlin 2.1.20 编译器、Compose 编译插件、Compose/Android 依赖和 JUnit 4.13.2，运行 `run_pinned_tests.py`。生产源码全部来自 `git archive e1932cf8`，不是被其他会话修改后的工作区文件。

- 最终独立编译覆盖全部 **15 个新增生产 Kotlin 文件、4 个原测试文件以及补充测试文件**，编译退出码 `0`。
- 编译有两条弃用警告：旧位置的 `LocalLifecycleOwner`、非自动镜像的 `Icons.Filled.ScreenShare`；没有将警告描述为编译失败。
- 缓存的既有项目编译 jar 仅用作未修改的外部依赖/符号解析；测试执行时新编译的目标类排在类路径最前。**这不是完整 app 的重新编译，未验证 3 个宿主接入文件的完整构建，也未生成 APK。**
- 原测试类：`OnboardingStateTest`、`PermissionCatalogTest`、`GatewayTutorialsTest`、`motion.ButtonRevealGeometryTest`；单独执行结果为 **`OK (39 tests)`，退出码 `0`**。
- 加上 9 个审查补充用例后结果为 **`Tests run: 48, Failures: 3`，退出码 `1`**。三个失败都是针对产品应有行为的断言失败，不是缺类、框架初始化或测试工具异常。

补充检查明细：

| 补充用例 | 结果 | 实际观察 |
| --- | --- | --- |
| `firstCompletionHidesOnboarding` | 通过 | 普通首次完成后 `shouldShow=false` |
| `replayCompletionMustHideOnboardingInSameProcess` | **失败** | 完成后仍为 `replay=true/shouldShow=true`，R01 |
| `processRestartClearsReplayAfterCompletion` | 通过 | 新建偏好读取实例后 `replay=false` 且不显示引导 |
| `stageAndGatewaySelectionSurviveNewPreferencesInstance` | 通过 | `GUIDE` 和 `openclaw` 选择可恢复 |
| `endReplayDoesNothingForFirstRunWelcome` | 通过（证明当前行为） | 初始状态无变化；结合宿主返回链支持 R05，不表示导航符合需求 |
| `zeroProgressCoverMustMatchWideButtonRatherThanCircle` | **失败** | `320×64` 起点记录到半径 `160` 的圆，R03 |
| `finalClipCoversAllFourCorners` | 通过 | 实际 `revealExtentAt(..., 1f)` 半径覆盖四角 |
| `transitionSlotMustHaveVisibleOverlap` | **失败** | 1001 个进度采样的组合透明度最大值为 `0.0`，R02 |
| `animateToFrameCallbackCanUpdateComposeSnapshotState` | 通过 | 9 帧，含中间值，最终为 `1.0` |

测试方法边界：偏好测试使用内存 `SharedPreferences` 实现替代磁盘，但调用真实生产状态方法；几何检查通过记录 `DrawScope` 调用并反射调用真实私有绘制/裁剪函数，不是截图；帧回调检查使用真实 Compose 动画和测试时钟，不是设备帧率测试。补充工具与原始日志放在审查临时目录，本文件保存了结论、用例、观察结果与限制，未把临时构建内容加入仓库。

### 5.4 边界、资源释放与未验证项

- 原几何测试覆盖零尺寸容器、位于屏幕边缘的中心、进度端点和越界夹取；新增的实际绘制测试补上了“插值端点正确不代表调用了正确绘图原语”的缺口。
- `GatewayTutorials.byId()` 对未知 ID 回退第一套教程；当前 `ALL` 是三项非空常量。流程中也直接使用 `ALL.first()`，如果未来允许空配置，需要同步移除这一假设；本提交没有可达的空教程列表崩溃证据。
- 权限可见项允许按平台和插件筛选，空分组不阻止继续；没有把“没有可见设备能力”伪装成用户已经授予能力。
- 生命周期观察者有移除，组合协程和 `pointerInput` 由 Compose 生命周期管理；未发现无限存活的独立作用域或无清理监听器。
- 未执行 APK/Manifest 合并、Android 仪器测试、设备/模拟器操作、真实设置撤权、TalkBack、字体缩放/横屏截图、性能测量、真实 Gateway 安装或远程 CI。因此这些证据不能相互替代，也不宣称完整流程已经通过。
- AndroidX `Animatable`、Android 运行时权限和 MediaProjection 官方说明仅用于核对框架语义；代码结论以固定提交源码及上述测试为准，不以网上示例替代项目实现。

**建议修复顺序：** R01 → R04 → R02/R03 → R05/R06 → R07；随后补上宿主与流程级回归测试，重新验证权限状态和真实目标内容的中间帧，再做设备体验验收。
