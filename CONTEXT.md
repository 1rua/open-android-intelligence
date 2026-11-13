# open-android-intelligence 领域词汇

本词汇表描述已确认的 DSH Remote 目标架构；它不表示运行时代码已经切换，代码状态以[架构重构规格](docs/specs/2026-11-13-dsh-remote-architecture-refactor.md)及其实施基线为准。

## Android 安全边界

**Android 宿主（Android Host）**:
安装在手机上的 open-android-intelligence 产品，负责本机交互、身份持有和设备权限裁决。
_Avoid_: 电脑主机、设备插件、能力合集

**平台内核（Platform Kernel）**:
Android 宿主内不可卸载的本机安全权威，负责插件身份、运行隔离、能力许可、审计和紧急停用。
_Avoid_: 核心插件、Remote 服务、AgentBackend

**受保护模式（Protected Mode）**:
由平台内核约束插件能力的默认运行模式；插件只能使用明确授予的本机能力。
_Avoid_: 普通模式、官方插件模式、开发者信任模式

**开发者信任模式（Developer Trust Mode）**:
用户明确启用后，把原生插件视为宿主可信代码的运行模式；平台内核不再承诺这些代码受插件沙箱约束。
_Avoid_: 调试模式、受保护模式、插件授权

**设备插件（Device Plugin）**:
为 Android 宿主提供边界明确的数据或设备操作能力的可扩展单元；安装和启用插件本身不授予其访问用户数据的许可。
_Avoid_: Android 模块、AgentBackend、无限权限插件

**受保护插件（Protected Plugin）**:
在受保护模式中运行、只能调用平台内核所授能力的插件。
_Avoid_: 原生插件、可信代码、WebView 插件

**插件沙箱（Plugin Sandbox）**:
受保护插件与宿主其他代码之间的隔离边界；它限制插件执行范围，但不替代平台内核的本机授权。
_Avoid_: Android 系统权限、原生代码信任、Agent 隔离

**原生插件（Native Plugin）**:
以宿主可信代码身份运行并可使用宿主原生扩展能力的设备插件，只能在开发者信任模式中运行。
_Avoid_: 受保护插件、沙箱插件、第三方能力

**原生界面接管（Native UI Takeover）**:
原生插件在开发者信任模式中替换或扩展 Android 宿主产品界面的能力；它不属于受保护插件可获得的界面扩展。
_Avoid_: 受保护界面贡献、插件卡片、WebView 页面

**插件作者密钥（Plugin Author Key）**:
由插件作者控制、用于标识插件发布来源与更新连续性的密码学身份。
_Avoid_: 官方批准、插件 ID、代码安全证明

**插件身份（Plugin Identity）**:
由插件命名空间与作者密钥共同确定的身份；同名但作者密钥不同的插件是不同主体。
_Avoid_: 插件 ID、版本号、文件摘要

**插件能力（Plugin Capability）**:
插件向宿主声明的一项用户可识别功能；它不能自行定义或扩大平台内核拥有的安全权限。
_Avoid_: Android 权限、内核原语、自然语言工具名

**插件包（Plugin Package）**:
带有可验证作者身份、能力声明和可执行内容的可分发插件单元。
_Avoid_: 源码仓库、裸运行载荷、已授予权限

**本机能力策略（Local Capability Policy）**:
由 Android 用户控制、限定本机能力是否可用以及可访问范围的策略；它独立于 Remote 登录身份和 Agent 会话。
_Avoid_: Remote 账号授权、设备发现、Agent 请求同意

**主动设备推送（Proactive Device Delivery）**:
用户允许 Android 按本机选定的数据范围，把短信或通知主动交给指定的 Agent 端点；它与 Agent 按需读取设备数据是不同的许可。
_Avoid_: 被动查询、默认同步、Remote 账号授权

**能力授权模式（Capability Authorization Mode）**:
本机用户对能力选择逐次手动批准或在已允许范围内免逐次确认的规则；敏感操作仍须本机手动确认。
_Avoid_: Android 系统权限、插件启用、自动扩大许可

**设备发现（Device Discovery）**:
Remote 账号识别同一账号下可联系设备的过程；发现设备本身不代表该设备授予了任何能力。
_Avoid_: 能力授权、登录凭据、同账号设备许可

**紧急停用（Kill Switch）**:
用户立即停止本机受控能力执行并撤销相关活动许可的安全动作。
_Avoid_: 普通断开连接、卸载插件、服务端账号停用

## Remote 与 Agent 身份

**Remote 服务（RemoteServer）**:
承载账号登录、设备发现和远程控制请求的服务端；它可以是官方服务，也可以是兼容协议的自托管服务。
_Avoid_: Android 宿主、AgentBackend、旧协议服务

**Remote 账号（Remote Account）**:
用户在一个 Remote 服务上的身份；Android 同一时刻以一个 Remote 账号作为活动远程身份。
_Avoid_: Host 身份、AgentEndpoint、设备能力许可

**设备身份（DeviceIdentity）**:
Remote 服务用于识别并寻址某台 Android 设备的身份；它只表示设备主体，不代替本机能力策略。
_Avoid_: Android 安装配置、用户账号、Agent 身份

**主机（Host）**:
运行 DSH 并向 Remote 账号提供可控工作区的电脑或其他计算主机；此处的“主机”与 Android 宿主是不同主体。
_Avoid_: Android Host、Remote 服务、AgentBackend

**Agent 后端（AgentBackend）**:
由 DSH 主机提供的 Agent 执行环境；不同后端可以拥有不同的会话、模型与工作区能力。
_Avoid_: Remote 服务、Android 插件、模型名称

**Agent 端点（AgentEndpoint）**:
用户可选择的扁平 Agent 调用目标，用于把请求路由到一个可用的 Agent 后端及其工作区；目标之间不隐含父子关系。
_Avoid_: 嵌套后端层级、设备身份、会话 ID

**主动客户端身份（RemoteClient）**:
登录 Remote 账号后由 Android 建立、代表本机主动发现设备并发起控制的独立身份；它可以独立于被控身份休眠。
_Avoid_: VirtualHost、Android 宿主、AgentEndpoint

**虚拟主机身份（VirtualHost）**:
登录 Remote 账号后由 Android 建立、代表本机作为被控端接受合法控制请求的身份；默认只允许被控。
_Avoid_: 电脑主机、RemoteClient、后台 Agent

## 会话与消息

**原生会话（NativeSession）**:
由 Agent 后端拥有的真实会话；会话内容和生成状态以该后端为权威，模型与思考强度选项取决于该会话实际提供的能力。
_Avoid_: Remote 登录会话、Android 本地历史副本、服务端会话线程

**消息（Message）**:
属于一个 NativeSession 的用户输入、Agent 回复或执行结果；各后端差异由会话能力表达，不改变消息属于哪个会话。
_Avoid_: 独立设备请求、附件本体、Agent 账号

**会话投影（SessionProjection）**:
Android 为呈现和操作一个 NativeSession 而维护的本地视图状态；它不是另一份会话权威。
_Avoid_: 第二会话、独立聊天历史、RemoteClient

**附件草稿（Attachment Draft）**:
用户在消息发送前选定并仍可编辑的附件集合；草稿转入提交后，其内容和顺序固定。
_Avoid_: 已提交附件、后台收集内容、系统文件授权

**原子附件提交（Atomic Attachment Submission）**:
一次消息提交中，文本与选定附件作为一个整体被接受或拒绝；不得悄悄丢弃部分附件后仍报告完整成功。
_Avoid_: 部分发送、隐式重试、上传完成即发送

## 手机控制与屏幕语义

**控制工作区（ControlWorkspace）**:
当前接受手机控制任务的 DSH 工作区；来自原生入口和 MCP 的调用都遵循同一个工作区归属。
_Avoid_: Android 宿主、Agent 会话、账号资料

**控制总线（ControlBus）**:
Android VirtualHost 持续提供的设备控制入口，负责把合法请求交给本机执行能力。
_Avoid_: Android UI 导航、RemoteClient、电脑 AgentBackend

**控制执行器（ControlExecutor）**:
原生 DSH 请求与经本机桥接的 MCP 请求共用的确定性控制执行能力；它遵循同一工作区归属和本机许可。
_Avoid_: 各 Agent 独立的设备执行器、模型推理、第二授权权威

**控制任务会话（ControlTaskSession）**:
为一次独立控制任务创建的执行上下文；它与常驻控制总线分开，任务之间不共享隐式执行状态。
_Avoid_: NativeSession、Remote 登录会话、常驻总线

**操作回执（OperationReceipt）**:
对一个已接收控制操作的结果记录，用于让调用方确认该操作已完成、被拒绝或仍无法判定。
_Avoid_: 聊天消息、授权凭据、执行日志正文

**结果未知（OutcomeUnknown）**:
操作可能已经产生外部影响，但当前没有足够证据确认结果的状态；它不能被当作成功，也不能据此盲目重做。
_Avoid_: 失败、成功、可安全重试

**本机桥接（LocalBridge）**:
电脑上的 MCP 入口与 DSH 控制总线之间的进程内连接，使外部 Agent 复用 DSH 的设备通道和授权边界。
_Avoid_: Android Companion、独立 Relay 服务、第二控制通道

**Agent 委派（AgentDelegation）**:
一个 Agent 请求另一个实际可用 Agent 后端完成工作的行为；同一 DSH 主机上的委派可能串行切换共享工作区。
_Avoid_: 模型切换、虚构端点、Remote 账号切换

**屏幕观察（ScreenObservation）**:
在本机许可范围内取得的当前屏幕结构或图像事实；观察结果不授予后续操作权限。
_Avoid_: 屏幕控制、持久屏幕历史、授权回执

**语义节点（SemanticNode）**:
无障碍界面树中描述可见控件及其语义属性的节点。
_Avoid_: 像素区域、永久控件 ID、通用目标句柄

**快照绑定目标（SnapshotBoundTarget）**:
与一次屏幕观察及其界面状态相绑定的操作目标；界面状态改变后，旧目标不能直接沿用。
_Avoid_: 永久节点引用、跨页面定位、可盲目重放目标

**标记集（Set-of-Mark, SoM）**:
屏幕观察中用于明确指出候选目标及其语义的标注集合，供用户或 Agent 形成可核验的操作意图。
_Avoid_: 任意坐标集合、截图权限、自动点击

**界面稳定（UiSettled）**:
界面在一次操作后进入可继续观察或判断的稳定状态；尚未稳定时，后续动作不得假定上一动作已产生预期界面。
_Avoid_: 操作成功回执、固定等待时长、动作重试

**无障碍确认（AccessibilityConfirmation）**:
通过 Android 无障碍界面向用户呈现并取得的本机操作确认；远程请求不能伪造这项用户决定。
_Avoid_: Agent 同意、账号登录、系统权限授予

**锁屏边界（Lock-screen Boundary）**:
设备锁定时，只允许本机明确授予后台访问的数据能力；屏幕观察、截图和屏幕控制均不可用。
_Avoid_: 锁屏绕过、无障碍授权等同解锁、后台屏幕访问

## 默认助理

**默认助理入口（Default Assistant Entry）**:
用户通过 Android 系统选择的助理身份唤起 Android 宿主的入口；系统角色只决定唤起方式，不授予屏幕或插件能力。
_Avoid_: Remote 账号、默认 Agent、屏幕许可

**助理会话（Assistant Session）**:
由默认助理入口唤起、可承载临时对话草稿与附件的 Android 会话界面。
_Avoid_: NativeSession、常驻控制总线、系统 LLM

**助理交接（Assistant Handoff）**:
用户把助理会话中的当前对话意图、草稿和附件交给 Android 主会话界面的动作；交接结果必须明确，不得静默丢失草稿。
_Avoid_: 后台复制、隐式发送、第二份历史
