# Spec 完整性审查修复记录

审查基线：`0a94d5908af9f7ef6d8c3830d23f8acaa1f2dd49`。修复分支：`fix/spec-completeness`。

本次修复覆盖原审查的 22 项 P1/P2 代码缺口。F17 按用户要求排除，后续 Spec 已取消内嵌 tailnet。以下结果区分生产实现、自动化验证和仍需要设备验证的范围。

|编号|级别|缺口|状态|验收记录|
|---|---|---|---|---|
|F01|P1|Android 生产端没有插件执行闭环|已修复|`ProductionPluginHost` 重新验证安装包并装配 runtime；`DeviceExecutionDriver` 读取认证请求、claim、执行并回传，先加密保存结果再重传。真实签名 SMS 包、编译后的 WASM、生产宿主及丢失结果 ACK 的回归已通过。|
|F02|P1|WASM ABI 不能访问 Spec 定义的内核能力|已修复|新增受控能力调用 ABI，连接身份、授权交集、存储、网络及调度；每次原语调用及输出检查当前授权。真实 Rust WASM 验证读取、调度及拒绝路径，修复模块初始内存和地址 0 的 ABI 兼容问题。|
|F03|P1|三个官方参考插件实际是 echo，打包 Schema 是占位对象|已修复|SMS、通知、通话插件实际调用原语；业务 Schema 有字段和约束。SMS 提供 schedule、job-status、cancel，接入加密任务记录与 Android JobScheduler。三个实际 WASM 的签名包构建通过。|
|F04|P1|Agent 宿主设备工具入口和结果消费端口没有接入|已修复|两个宿主均注册设备工具，从可信宿主会话提取设备来源并匹配当前能力绑定；读取终态结果，在宿主确认消费相同结果后 ACK。真实队列、结果及错误消费拒绝回归通过。|
|F05|P1|生产能力 Schema 绑定固化为测试 fixture|已修复|生产核心 Schema 改用内置目录；已验证安装包的真实 Schema 摘要与风险按设备、配对代次、授权 revision 发布。fixture 注册仅保留在显式测试入口；摘要不匹配和旧授权绑定被拒绝。|
|F06|P1|高权限临时请求无在线执行路径，创建即过期|已修复|TTL=0 按在线连接分发并使用短执行租约；离线立即失效。最后一个合法连接断开时清退未 claim 的临时请求；多连接、断线及不可再次 claim 的双宿主测试通过。|
|F07|P1|OpenClaw 缺少时间线历史读取和未知发送结果查询|已修复|通过可信 SDK 会话路由绑定原生 JSONL；提供稳定消息身份、快照 revision、分页和 clientMessageId 查询。实际 121 条 JSONL、编辑、旧游标失效及丢失 ACK 查询回归通过。|
|F08|P1|Hermes 历史分页是假分页，Android 也只加载第一页|已修复|Hermes 使用有会话作用域及 revision 的加密游标；Android 读取全部页并检查循环、revision 变化，有限重启读取。相同时间戳的 121 条消息无漏项、无重复。|
|F09|P1|丢失发送 ACK 后被当成失败，手动重试生成新消息身份|已修复|未知结果保留原 clientMessageId、批次成员与正文，先查询，再以冻结身份重试；服务端保存正文无关的幂等指纹。冻结身份可随加密镜像恢复。|
|F10|P1|完整加密对话镜像与进程重启恢复只有接口/内存状态|已修复|按 Gateway、账号和安装作用域持久化对话列表、完整时间线、草稿、附件恢复资料和冻结提交；离线镜像可重连。实际 AES-GCM 文件重开、分区隔离及明文检查通过。|
|F11|P1|CURSOR_EXPIRED 只清游标和报错，没有快照重建|已修复|连接运行时注册快照恢复回调，先安装完整加密基线，再持久化恢复游标并重新订阅；失败时保留旧游标。安装顺序及恢复失败回归通过。|
|F12|P1|正文保留边界和宿主长期历史职责仍未完整实现|已修复|长期正文读取原生宿主记录；Gateway 留短期交付数据和无正文映射。Hermes 旧正文先写入 SessionDB，再清除；OpenClaw 先绑定已有原生记录再清副本。请求 ACK/资源 TTL 同步清事件正文，旧事件有分批迁移。真实 Hermes SessionDB 的 17 会话迁移、重启及去重测试通过。|
|F13|P1|Hermes 附件 TTL 维护和实际字节删除未闭环|已修复|宿主维护循环覆盖空闲账号，执行附件过期、实际文件 unlink 及无正文元数据清理；失败清理可再次执行。实际附件文件、维护入口及字节删除回归通过。|
|F14|P1|真实 E2E 消息阶段可以把未发送草稿判成双向成功|已修复|真实模式要求本轮发送 ACK 的消息身份和正文摘要、对应完成事件及已完成回复 UI 证据；丢弃旧日志。仅输入草稿或可见 echo 的反例必须失败，回归已通过。|
|F15|P2|插件生命周期与受保护 UI 动作停在安装/展示|已修复|启用、禁用、同作者升级、回滚、卸载接入真实安装目录和 runtime 重载；声明式设置持久化，已验证对话卡动作走明确确认及内核调用。实际签名安装、升级、回滚、作者拒绝和卸载回归通过。|
|F16|P2|Developer Trust 原生加载和 UI 接管只有抽象与开关|已实现|同 APK 的全局 Developer Trust 连接只读 DEX/JNI 缓存、DexClassLoader、NativePlugin 和版本化 NativeUiProvider；接管界面保留外部恢复入口。异常及进程中断持久化隔离与崩溃计数。内核权限回归及构建验证通过；设备加载验证见下文。|
|F17|P2|Tailscale Companion 无可用传输，也不是独立可安装 APK|按要求排除|后续 Spec 已取消内嵌 tailnet|
|F18|P2|系统默认助理文字闭环尚未实现，当前仅 App 内浮动面板|已实现|assistant-holder 合并入主 APK；VoiceInteractionSession 提供文字、线程、流式回复、发送、取消及屏幕附件，私有同 UID IPC 连接真实工作台。正文共享需显式同意，账号切换撤销同意；修复非法进程名。设备系统角色验证见下文。|
|F19|P2|自由手绘圈选没有实现，只有矩形裁剪|已修复|记录自由路径，在选区边界内裁剪并对多边形外像素应用透明遮罩；NativeGraphics 像素回归通过。|
|F20|P2|批次、防抖设置和真实生成取消仍是设计/客户端预备代码|已修复|两端实现持久批次、成员映射、精确 newline-v1 拼接和会话 FIFO；Android 提供全局/按 Gateway 防抖设置。取消接入真实宿主停止接口，保留未支持/已完成/未知差异，未确认取消不放行后续轮次。批次、未知取消、授权拒绝与 Android 取消回归通过。|
|F21|P2|历史媒体读取与按 Gateway 加密缓存未实现|已修复|两端保存宿主原件引用和可用性元数据，签发 120 秒、设备及授权作用域的一次性下载许可；Android 显式加密保留，配额不足拒绝新增、不驱逐已有内容。实际原件、同尺寸替换、过期/重放、AES-GCM、离线重开及配额回归通过。|
|F22|P2|多 Agent 自动修复和 Verify Agent 只是写占位文档并合并|已修复|生产默认调用实际 Codex worker，最多四个隔离 worktree 并发；拒绝只有文档的“修复”，提交、rebase 及合并均检查真实回归进程结果，失败保留现场/撤回合并。普通 Python worker、真实 Git 仓库及失败门禁回归通过。|
|F23|P2|二维码/短码邀请与设备密钥会话未实现|已修复|管理入口生成五分钟邀请，Android URI/短码入口显式确认账号及身份；两端提供 pairing exchange、一次性 Ed25519 challenge 和 device-key session，拒绝跨账号、错误绑定、过期及重放。实际签名与会话回归通过。|

## 验证记录

以下命令从仓库根目录执行，Android 命令从 `apps/android` 执行。需要 Python 3.12、Node 24.18、Rust 1.90、Java 17 和 Android SDK 35。

|检查|结果|
|---|---|
|`tools/run-node24 npm test`|104 个文件、915 项通过|
|`python -m pytest integrations/hermes/tests -q`|272 项通过；原生宿主可选集成 1 项跳过，单独安装固定宿主版本后已执行通过|
|`npm run gateway:v2:conformance`|OpenClaw/Hermes 各 66/66，跨宿主比较 3 项通过|
|根目录、`tsconfig.openclaw.json` 和 plugin-tooling 类型检查|通过|
|plugin-tooling 测试|46 项通过|
|`cargo test --manifest-path plugins/Cargo.toml`|38 项通过；示例文档测试 1 项忽略|
|`cargo build --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml`|真实 WASM 构建通过|
|三个参考插件的生产签名打包命令|通过；本地验证使用临时测试签名种子|
|原生 Hermes 历史/媒体 + E2E 编排回归|20 项通过；修复 worker 测试使用普通 Python 子进程，未在本次验证启动 AI 修复任务|
|Android `check :app:assembleFullDebug`|通过；2396 项测试中 2394 项通过、2 项因 Play 版禁用运行时插件而按设计跳过；Full Debug APK 构建成功|

Android CI 已增加真实 Rust WASM 构建，JVM 集成回归直接运行 Cargo 产物；Gradle 把这些产物列为测试输入，避免 WASM 改变后沿用旧测试缓存。

PR #6 首轮 CI 暴露两处问题，修复后已重新执行完整 Gateway 检查和 Android `check :app:assembleFullDebug --no-build-cache`：

- SMS 已提供后台调度，但旧 Manifest 契约测试仍要求 `background.requested=false`。测试现在检查 SMS 申请后台执行、最低间隔 60 秒及对应内核原语，同时继续检查另外两个只读插件不申请后台执行。
- 系统助理在 UI 线程执行 `Bitmap.compress`，触发 `WrongThread`。真实 PNG 编码移到工作线程，选区 Bitmap 由编码线程释放；返回 UI 后重新检查连接、会话令牌和共享同意，过期结果清除字节并丢弃。新回归在 Debug/Release 均验证实际 PNG 像素、工作线程、释放时机及关闭/未同意时不交付。

主要新增回归可见：

- [生产签名包与设备执行重传](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHostTest.kt)
- [实际编译的参考 WASM](../../apps/android/plugin-runtime-wasm/src/jvmTest/kotlin/com/openandroidintelligence/plugin/wasm/CompiledReferencePluginTest.kt)
- [OpenClaw 原生历史](../../integrations/openclaw/test/native-history.test.ts)、[Hermes 原生 SessionDB 迁移](../../integrations/hermes/tests/test_native_history_complete.py)
- [加密镜像恢复](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/EncryptedMirrorRecoveryTest.kt)、[加密媒体配额](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/EncryptedHistoryMediaCacheTest.kt)
- [助理截图编码与会话结束时丢弃](../../apps/android/assistant-holder/src/test/kotlin/com/openandroidintelligence/assistant/AssistantScreenAttachmentTest.kt)
- [Hermes 媒体原件与下载许可](../../integrations/hermes/tests/test_history_media_complete.py)、[真实 Git/worker 与草稿反例](../../e2e/android-cli/test_e2e_orchestrator.py)

## 验证范围

本次环境没有 Android 模拟器、KVM 或真机。构建、JVM/Robolectric、真实 WASM、文件/数据库和普通子进程回归已执行；尚未执行手机与运行中 LLM 宿主之间的全系统 E2E。F16 的实际 DEX/JNI 加载与界面接管、F18 的系统默认助理角色、手机采集权限和 JobScheduler 跨设备重启行为仍需设备验收。

Hermes 原生数据库验证固定到 `b3aa561faffd64f05436e429a6415d175e534ec9`。在该宿主 checkout 位于 `PYTHONPATH` 时，可执行 `python -m pytest integrations/hermes/tests/test_native_history_complete.py integrations/hermes/tests/test_history_media_complete.py -q`。原生历史尚未确认接收的旧正文保留待迁移，避免删除唯一副本。

媒体缓存单项上限为 8 MiB，默认作用域配额 64 MiB，可调整至 8–256 MiB；Gateway 原件读取上限 25 MiB。上述限制会明确拒绝过大的保留请求。
