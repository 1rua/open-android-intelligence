# Open Android Intelligence (OAI) Android 全量中文文案审计与用户体验优化报告

> **审计基准 Commit**: `8820fe250aa810cfb11925d6b07d4e6714f60be5`  
> **审计基准分支**: `dev` (工作区分支: `audit_chinese_copywriting`)  
> **审计范围**: Android App 生产代码与资源（涵盖 7 个子模块与 AndroidManifest.xml）  
> **报告生成原则**: 纯静态与调用链全面只读审计，本阶段严禁且未修改任何生产业务代码。  

---

## 目录

- [一、审计背景与核心发现](#一审计背景与核心发现)
- [二、全量统计总结](#二全量统计总结)
- [三、全局术语统一方案](#三全局术语统一方案)
- [四、优先整改清单 (Priority Action List)](#四优先整改清单-priority-action-list)
- [五、重点问题文案深度评析与替换建议清单](#五重点问题文案深度评析与替换建议清单)
- [六、全量中文文案清单 (按功能模块索引)](#六全量中文文案清单-按功能模块索引)
- [七、工程化缺陷与架构整改建议](#七工程化缺陷与架构整改建议)
- [八、验证缺口与后续建议 (Remaining Questions & Gaps)](#八验证缺口与后续建议-remaining-questions--gaps)

---

## 一、审计背景与核心发现

本次审计针对 **Open Android Intelligence (OAI)** Android 客户端（Commit `8820fe250aa810cfb11925d6b07d4e6714f60be5`）展开全量中文文案审查。

通过对整个 Android 代码仓进行无死角源码分析（排除测试集 `src/test`, `src/androidTest`, `build/` 派生缓存），系统性检索了 Kotlin 源码字符串、多行文本模板、清单文件 `AndroidManifest.xml` 以及 XML 资源配置。在审查的 119 个生产代码与清单文件中，共完整提取出 **2,095 条生产可见中文文案实例（去重后 1,749 条独立中文文案）**。

### 批判性复审与数据纠偏说明
在本次深度审计过程中，针对前期粗筛结果进行了严格的独立调用链复核与交叉验证，纠正了以下关键偏差：
1. **剔除 5 条非 UI 源码片段与底层日志**：原初筛清单中因正则边界缺陷误收录了 3 处 `MarkdownInline.kt` 的 Kotlin 语法解析片段（如 `-> SpanStyle`、`quote = input[index]`）、1 处废弃编译注解 `@Deprecated`（`MarkdownMath.kt:671`）以及 1 处纯内部 Logcat 错误日志（`ApprovalClient.kt:215`）。该 5 项不属于 UI 可见范畴，已全量剔除并归档；
2. **纠正关键代码路径归属**：纠正了部分跨进程助理文案的文件位置归属，如将误标为 `AssistantActivity.kt` 的跨进程提示精准定位至 `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:373`；
3. **系统性补查 68 条遗漏的内部术语暴露文案**：前序初筛将大量在设置与备份中暴露 `schemaVersion`、`JSON`、`Agent`、`未装配` 等技术术语的文案草率标记为「保留」。本次深化审计严格依照用户直觉与去架构化原则，将上述 68 条文案全部纳入待修改清单，使全仓实际建议修改文案数由 188 条精准收敛至 **256 条**，建议保留 **1,839 条**。

### 核心发现四大用户体验痛点：

1. **内部架构与协议术语严重外溢**：
   - `Gateway`（网关）、`Core`（核心服务端）、`Agent`（智能体/智能助手）、`EventStream / 事件通道`、`主进程`、`JSON`、`schemaVersion` 等内部开发概念直接出现在面向最终用户的登录页、主会话气泡、通知栏和弹窗中。例如气泡提示直接出现「Gateway 运行环境不可用」、「等待 Gateway 接收」；系统错误提示中出现「Gateway 事件通道认证仍被拒绝」、「助理进程无法访问主进程的对话状态」；
2. **术语严重撕裂与不统一**：
   - 核心概念在同一应用不同页面甚至同一页面混用：
     - **服务实体**：在登录页同时出现「OAI Core 服务端地址」、「网关地址」、「Gateway 身份」；
     - **交互载体**：在会话主页面既叫「会话」又叫「对话」，同时混杂「Session」、「线程」；
     - **智能实体**：既叫「Agent」又叫「助理」、「数字助理」、「智能助手」；
     - **截图能力**：混用「投屏」、「屏幕采集」、「屏幕共享」、「截图」、「圈选」；
3. **表达冗长、机械且防卫性说教严重**：
   - 大量状态反馈与错误提示长达 60~80 字，充满防御性技术解释（如「Agent 已经回话了，但没有返回新建会话的结果，无法确认新会话的标识，因此仍停留在原会话...」）。这类文案试图用大段文字弥补异步协议设计缺陷，造成普通用户极大认知负担；
4. **0% 资源化率与 100% 源码硬编码**：
   - 全仓 Android XML 资源中 **不存在任何 `strings.xml` 文件**。所有 2,095 条生产可见文案全部硬编码在 Jetpack Compose 和 Kotlin 源码中。未实现字符串资源化，无法支持多语言（i18n）切换，缺乏统一文案治理机制。

---

## 二、全量统计总结

### 2.1 整体数据概览

| 指标项 | 实际核验数量 | 说明 |
| :--- | :--- | :--- |
| **Android 生产代码与资源文件总数** | **458 个** | 涵盖 7 个子模块与 1 个清单文件 |
| **涉及生产用户可见中文文案文件数** | **119 个** | 118 个 Kotlin 文件 + 1 个 AndroidManifest.xml (剔除纯日志文件 ApprovalClient) |
| **生产中文文案总引用条数** | **2,095 条** | 剔除 5 条误提取代码/日志后的实际用户可见中文条数 |
| **去重后独立中文文案数** | **1,749 条** | 文本内容完全唯一的独立 UI 文案条数 |
| **合格合规文案 (建议保留)** | **1,839 条** | 占 87.8%，语义通俗直观，符合 Android 交互规范 |
| **存在缺陷文案 (建议修改)** | **256 条** | 占 12.2%，包含技术架构暴露、术语撕裂、机械说教、冗长等问题 |
| **纯内部日志/废弃代码 (建议清理)** | **5 条** | 误提取的 MarkdownInline 代码片段、Deprecated 注解及 Logcat 语句 |

### 2.2 缺陷问题类型分布 (256 条待整改文案)

| 问题分类 | 实际检出数量 | 核心问题表现 | 典型代码位置 |
| :--- | :--- | :--- | :--- |
| **内部架构与协议暴露** | **148 条** | 暴露 `Gateway`、`Core`、`EventStream`、`WASM`、`SPKI`、`主进程/助理进程` | `BubbleConversationActivity.kt:66`、`AssistantSessionRoot.kt:373` |
| **术语撕裂与混用** | **68 条** | 实体称谓分裂（`Agent` vs `助手`；`会话` vs `对话`；`网关` vs `Core`） | `GatewayLoginScreen.kt:200`、`ConnectionSettingsScreen.kt:78` |
| **技术实现与内部字段暴露** | **22 条** | 暴露 `原生删除接口`、`JSON`、`schemaVersion`、`correlationId` | `SettingsBackupDocument.kt:108`、`NativeAgentSessionsScreen.kt:196` |
| **机械式表达与防卫性说教** | **11 条** | 「未装配」、「交付重试」、「未提供...能力」、长达 70 字的防御性免责长篇 | `StateViews.kt:105, 152`、`GatewayRuntime.kt:2251` |
| **不符合 Android 交互习惯** | **7 条** | 「去设置」（非标准动词）、「我已保存」（第一人称按钮）、「Agent 会话」（任务标题） | `MainActivity.kt:161`、`AndroidManifest.xml:93` |

### 2.3 严重程度与修改优先级分布

| 严重程度 / 优先级 | 数量 | 处置策略 | 重点覆盖范围 |
| :--- | :--- | :--- | :--- |
| **高 (High / P0)** | **84** | **立即整改** | 直接出现在应用启动登录、桌面气泡、系统常驻通知、输入建议、前台核心错误提示中 |
| **中 (Medium / P1)** | **146** | **次级迭代** | 设置一级/二级说明、权限引导弹窗、备份恢复导入校验、二次确认提示等 |
| **低 (Low / P2)** | **26** | **逐步收敛** | 按钮动词规范化（如「前往设置」）、高级网络诊断参数说明微调、备份字段类型提示等 |
| **保留 (Retain)** | **1,839** | **标准入库** | 表达规范，保持语义原样，纳入后续 strings.xml 标准资源化迁移 |

### 2.4 按功能模块分布

| 功能模块 / 业务领域 | 涉及文件数 | 文案总数 | 建议修改数 | 建议保留数 |
| :--- | :--- | :--- | :--- | :--- |
| 设置界面 (Settings) | 22 | 510 | 66 | 444 |
| 对话界面 - 工作台与时间线 (Workbench) | 13 | 301 | 26 | 275 |
| 插件与能力扩展 (Plugins) | 9 | 198 | 6 | 192 |
| 屏幕控制与授权 (Screen Control) | 7 | 195 | 1 | 194 |
| 初始化引导流程 (Onboarding) | 10 | 124 | 25 | 99 |
| 连接与账号 (Connection & Auth) | 2 | 118 | 33 | 85 |
| 对话界面 - 状态与空页面 (State Views) | 2 | 99 | 39 | 60 |
| 主界面与原生会话 (Main & Bubbles) | 4 | 97 | 19 | 78 |
| 系统通知与回复 (Notifications & Reply) | 6 | 82 | 24 | 58 |
| 对话界面 - Markdown渲染 (Markdown) | 6 | 77 | 2 | 75 |
| 应用核心与集成 (App Core) | 11 | 72 | 7 | 65 |
| 对话界面 - 助理与其他 (UI Other) | 4 | 45 | 0 | 45 |
| 数字助手 (Assistant) | 10 | 42 | 6 | 36 |
| 应用更新 (App Updates) | 2 | 37 | 0 | 37 |
| 对话界面 - 屏幕选区/截图 (Selection) | 1 | 31 | 0 | 31 |
| 主动推送策略 (Device Push) | 1 | 30 | 1 | 29 |
| 短信作用域与过滤 (SMS) | 1 | 14 | 0 | 14 |
| 对话界面 - 语音交互 (Voice) | 3 | 9 | 0 | 9 |
| 会话领域与数据层 (Conversation Domain) | 1 | 6 | 0 | 6 |
| 设备能力前台服务 (Capability Ports) | 1 | 4 | 0 | 4 |
| 会话领域与数据层 (Conversation Data) | 1 | 3 | 0 | 3 |
| 系统清单与组件 (Manifest) | 1 | 1 | 1 | 0 |
| **合计** | **119** | **2,095** | **256** | **1,839** |

### 2.5 按文案表现类型分布

| 文案交互类型 | 数量 | 典型组件与出现场景 |
| :--- | :--- | :--- |
| 正文/说明 | 1,175 | Text, Card 说明, 引导页长文本 |
| 标题 | 228 | TopAppBar 标题, 对话框标题, 设置分组标题 |
| 按钮/操作 | 220 | Button, TextButton, 悬浮操作胶囊 |
| 错误提示 | 194 | 异常状态提示卡片, 表单输入校验, 连接阻断拦截 |
| Toast/提示 | 96 | 操作成功/失败临时悬浮提醒 |
| 状态/空页面 | 73 | 列表空状态, 加载骨架占位, 离线断网提示 |
| 无障碍辅助 | 54 | contentDescription, 无障碍播报 |
| 系统通知 | 23 | NotificationCompat.Builder, 前台常驻通知 |
| 输入占位/表单 | 19 | OutlinedTextField 占位文本 |
| 弹窗/对话框 | 11 | AlertDialog, ModalBottomSheet 提示 |
| 调试预览 | 2 | 调试面板与组件预览 |

---

## 三、全局术语统一方案

为了彻底消除内部开发术语外溢与界面用语分裂，建立面向用户的统一术语体系。

### 3.1 核心概念标准映射表

| 源码现状概念 | 普通用户界面标准用语 (UI Standard) | 高级/诊断界面允许用语 (Advanced) | 严格禁止出现的表达 (Forbidden) |
| :--- | :--- | :--- | :--- |
| **OAI Core / Gateway / 网关 / 服务端** | **服务器** / **OAI 服务** | 服务器地址 (Core / Gateway) | 严禁日常通知出现「Core 实例」、「Gateway 镜像」 |
| **Agent / 智能体 / 助理 / 智能助手** | **智能助手** / **助手** | 智能体 (Agent) | 严禁通知提示「Agent 正在处理」、「等待 Agent 回复」 |
| **VoiceInteraction / 系统数字助理** | **系统数字助理** / **数字助理** | 系统 VoiceInteraction 服务 | 严禁与普通应用内助手混为一谈 |
| **会话 / Session / Thread / 线程** | **对话** (交互视角) / **连接** (通道视角) | 会话 (Session) / 线程 (Thread) | 严禁普通界面出现「会话镜像」、「Thread 同步失败」 |
| **EventStream / 事件通道 / 长连接** | **实时连接** | 实时事件通道 (EventStream) | 严禁在气泡或 Toast 中提示「事件通道恢复已暂停」 |
| **投屏 / 屏幕采集 / 屏幕共享 / 截图 / 圈选** | **截取屏幕** / **圈选屏幕** | 系统投屏授权 (MediaProjection) | 统一操作动作为「圈选屏幕」与「截取屏幕」 |
| **插件 / 设备能力 / Tool / WASM** | **设备能力** (权限项) / **插件** (管理项) | 插件 (Plugin) / 沙盒环境 (WASM) | 严禁向用户提示「WASM 沙箱隔离状态」 |
| **令牌 / Token / 凭据 / SPKI / 公钥指纹** | **登录凭据** / **安全证书** | 公钥指纹 (SPKI) / Keystore 凭据 | 严禁提示「新的访问令牌已生效」 |
| **JSON 文件 / schemaVersion** | **备份文件** / **版本标识** | JSON 备份文件 | 严禁向普通用户抛出「缺少 schemaVersion」代码字段 |
| **内部代号 (Hermes / DSH / Codex)** | *(用户层彻底抹除)* | Hermes 适配器预览 | 严禁在工具调用详情中展示「Hermes 在消息中未提供参数」 |

### 3.2 分级规范落地原则
1. **普通用户层（主界面、气泡、通知、Toast、标准设置）**：
   - 贯彻「去架构化、去协议化」原则。所有 RPC/网关交互，对外表现为自然的「连接」、「服务器」、「助手」、「对话」。
   - 动作动词规范：使用「前往设置」替代「去设置」；使用「确认已保存」替代「我已保存」；使用「继续」替代「已核对，继续」。
2. **开发者与诊断层（高级设置、连接诊断、插件详情、审计日志）**：
   - 允许展示技术协议与参数（如 HTTP/HTTPS、TLS 证书固定、WASM 沙箱、SPKI 公钥指纹），但必须使用标准的专业中文术语，禁止夹杂内部未翻译字段名。

---

### 3.3 萌趣灵动与亲和力文案风格指南 (Playful & Delightful Persona Guidelines)

为了让 OAI Android 从冰冷机械的技术工具转变为用户身边**聪明灵动、温和谦逊、风趣幽默的「贴心智能搭子」**，在遵循去架构化原则的基础上，全面融入亲和力与微趣味设计：

1. **角色人设定位（灵动小助手）**：
   - 告别高冷与死板公文腔（如「已成功执行会话创建操作」），使用活泼自白或温和对话体（如「搞定啦！」、「正在打包全新对话，马上就好~」）；
   - 主动拉近与用户的距离，实体称谓在普通交互中统一使用轻盈亲切的「小助手」或「助手搭子」；

2. **生动风趣的场景隐喻（化尴尬为乐趣）**：
   - **掉线 / 断网**：以幽默拟人化代替报错（例：「网络偷偷溜号啦」、「助手掉线喝水去啦，连上网就飞奔回来！」）；
   - **思考 / 等待**：赋予鲜活动态（例：「正在飞速动脑中…」、「等我一下下哦~」）；
   - **兼容 / 缺失字段**：以生活化比喻化解技术黑话（例：将 `schemaVersion` 缺失喻为「忘带出生证明啦」，将高版本备份喻为「来自未来的新版 App，快把我升级一下再来认亲吧~」）；
   - **格式不支持 / 报错**：自嘲式幽默化解挫败感（例：「这个文件有点认生」、「这种格式把小助手难倒了，换个格式试试看？」）；

3. **安全与确定性底线（风趣但不轻浮）**：
   - 在涉及**彻底删除记录、敏感硬件权限开启、证书篡改警告**等核心风险操作时，保持后果清晰警示，风趣但不儿戏（例：「确认要彻底告别这段对话吗？删掉可就找不回啦~」）；
   - 避免无意义或过度卖萌的无脑叠字，保持 Android 原生体验的克制、清晰与得体。


## 四、优先整改清单 (Priority Action List)

按照用户使用频次与感知破坏力排序，整改应优先从以下模块推进：

### 4.1 P0 级：主流程与高频界面（84 项立即整改重点）

| 序号 | 模块与文件 | 涉及文案 | 核心问题 | 整改目标 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `AndroidManifest.xml` | 「Agent 会话」 | 系统多任务卡片暴露内部称谓 | 改为「助手对话」 |
| 2 | `BubbleConversationActivity.kt` | 「Gateway 运行环境不可用」、「等待 Gateway 接收」 | 气泡悬浮窗直接暴露 Gateway | 改为「连接服务不可用」、「等待服务器接收…」 |
| 3 | `ReplyNotifications.kt` | 「Gateway 已断开，任务状态待同步」、「Agent 正在处理」 | 系统通知暴露架构，语法机械 | 改为「连接已断开，状态待同步」、「正在处理…」 |
| 4 | `GatewayLoginScreen.kt` | 「未连接 Gateway」、「正在连接 OAI Core…」、「OAI Core 服务端地址」 | 登录主流程术语三重分裂 | 统一为「未连接服务器」、「正在连接…」、「服务器地址」 |
| 5 | `GatewayRuntime.kt` | 「Gateway 事件通道恢复已暂停…」、「新的访问令牌已生效」 | 核心 Toast 充斥底层日志 | 改为「实时连接已暂停，请检查网络配置」、「登录状态已续期」 |
| 6 | `StateViews.kt` | 「登录已成功，但 Gateway 拒绝了事件通道的会话认证…」(74字) | 空状态与错误提示长篇大论 | 缩减为 25 字以内明确提示与恢复操作 |
| 7 | `StateViews.kt` | 「Agent 已经回话了，但没有返回新建会话的结果…」(77字) | 冗长防卫性技术解释 | 改为「新建对话未成功，仍保留在原对话中」 |
| 8 | `AssistantSessionRoot.kt` | 「助理进程无法访问主进程的对话状态…」 | 跨应用助理暴露主进程架构 | 改为「数字助手暂未就绪，请先打开应用后重试」 |
| 9 | `MarkdownToolCallView.kt` | 「Hermes 在消息中未提供命令参数」 | 泄露内部代号 Hermes | 改为「未提供命令参数」 |
| 10 | `ScreenErrorMessages.kt` | 「这次操作需要在手机上的确认…」 | 语法错误与大段模板化重复 | 修复语病为「此操作需要在手机上确认」 |
| 11 | `MessageTimeline.kt` | 「等待 Agent 接收」、「Agent 已接收」、「Agent 处理失败」 | 核心消息气泡状态主语重复 | 统一为「等待助手接收…」、「助手已接收」、「执行失败：」 |
| 12 | `NativeAgentSessionsScreen.kt` | 「这会调用 ${agent?.name} 的原生删除接口」 | 暴露后端 RPC 接口细节 | 改为「将彻底删除此对话及所有消息记录」 |

### 4.2 P1 级：设置、权限与数据管理（146 项次级迭代重点）

| 序号 | 模块与文件 | 涉及文案 | 核心问题 | 整改目标 |
| :--- | :--- | :--- | :--- | :--- |
| 13 | `ConnectionSettingsScreen.kt` | 「OAI Core 地址」、「实时事件通道」、「Core 身份验证」 | 设置首页术语与登录页不一致 | 统一术语规范为「服务器地址」、「实时连接」 |
| 14 | `SettingsBackupScreen.kt` | 「保存为 JSON 文件」、「不认识的字段」 | 文件格式暴露与轻佻用词 | 改为「保存为备份文件」、「不受支持的项目」 |
| 15 | `SettingsBackupDocument.kt` | 「备份文件缺少 schemaVersion」 | 异常文案抛出代码字段名 | 改为「备份文件缺少版本标识」 |
| 16 | `PermissionCatalog.kt` | 「权限 ≠ 授权」大段技术辨析 (4段长篇说教) | 降低界面信息密度 | 简化为重点权限实际用途说明 |
| 17 | `NotificationCollectionScreen.kt` | 「加密待发队列不可用」、「自动把通知发送给 Gateway」 | 内部队列架构与废弃机制暴露 | 改为「通知转发未就绪」、「自动同步通知至服务器」 |
| 18 | `SecuritySettingsScreen.kt` | 「插件：${event.pluginId}\n账号：${event.accountId}\n配对：${event.pairingId}\n请求：${event.correlationId}」 | 审计列表粗暴堆砌内部 ID | 格式化为通俗的「操作记录详情」 |
| 19 | `PushSettingsScreen.kt` | 「${coreInstanceId} / ${accountScope} / 第 ${generation} 代」 | 推送端点内部 key 堆砌 | 展示设备名称与认证时间 |
| 20 | `SettingsBackupCoordinator.kt` | 「通知采集范围未导入：当前版本未装配通知采集」 | 机械生硬工程词汇 | 改为「通知采集范围未导入：当前版本未启用通知转发功能」 |

### 4.3 P2 级：低频与规范微调（26 项持续治理重点）

| 序号 | 模块与文件 | 涉及文案 | 核心问题 | 整改目标 |
| :--- | :--- | :--- | :--- | :--- |
| 21 | `MainActivity.kt` | 「去设置」 | 不符合 Material Design 交互用词 | 统一为「前往设置」 |
| 22 | `GatewayLoginScreen.kt` | 「我已保存」、「已核对，继续」 | 第一人称与机械对话框动作 | 统一为「确认已保存」、「继续」 |
| 23 | `SettingsBackupDocument.kt` | 「字段 $name 必须是字符串/布尔值/整数」 | 面向编程类型的错误提示 | 规范为「设置项 $name 格式错误（须为文本/开关值/数值）」 |
| 24 | `ConversationTitlePolicy.kt` | 「新对话」默认标题策略 | 策略良好，保持统一 | 保留并作为全 App 对话创建的标准命名基准 |

---

## 五、重点问题文案深度评析与替换建议清单

本节汇总全仓经评估判定为需要**修改**的全部 256 条重点文案，包含原始文本、精确代码位置、问题分类、严重度评级、建议替换文案及修改理由：

| 原始文案 | 源码位置 | 所属组件/场景 | 问题类型 | 严重程度 | 建议替换文案 | 修改理由 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `Agent 后台会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:33` | 应用核心与集成 (App Core) | 术语不统一 | **高** | 小助手 后台会话 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `仅在 Agent 任务运行期间接收会话回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:34` | 应用核心与集成 (App Core) | 术语不统一 | **高** | 仅在 小助手 任务运行期间接收会话回复 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Agent 任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:114` | 应用核心与集成 (App Core) | 术语不统一 | **高** | 小助手 任务处理中 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Gateway 运行环境不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:66` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 | **高** | 通信小助手开小差啦，连接服务暂不可用~ | 以萌趣拟人化消除冷硬技术词 Gateway，给用户温暖反馈 |
| `正在连接 Gateway…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:158` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 / 术语不统一 | **高** | 正在呼叫服务器小搭子… | 可爱自然，消除网关冷冰感 |
| `正在查找该 Agent…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:160` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 | **高** | 正在呼叫专属小助手… | 以专属小助手替代冷硬的 Agent |
| `Gateway 连接失败，请在应用中重新连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:162` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 / 术语不统一 | **高** | 哎呀，没连上服务器，回 App 重新牵下手吧~ | 生动可爱，消除技术词 |
| `Gateway 尚未连接，请在应用中连接后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:163` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 / 术语不统一 | **高** | 服务器还没牵手成功呢，请先在应用里连一下哦~ | 以牵手隐喻连接，俏皮亲切 |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:174` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 / 技术名词暴露 | **高** | 小助手对话框 | 亲和可爱的界面标题 |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:311` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 | **高** | 正在飞速动脑中… | 生动传神展示思考状态 |
| `Gateway 未声明支持会话同步，通知推送不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:81` | 应用核心与集成 (App Core) | 技术名词暴露 / 术语不统一 | **高** | 服务器 未声明支持会话同步，通知推送不可用 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `连接 OAI Core，发现已接入的 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:200` | 连接与账号 (Connection & Auth) | 技术名词堆砌 / 术语生硬 | **高** | 连上 OAI 服务端，召唤你的专属智能搭子！ | 把枯燥的技术迎宾语变成充满吸引力的召唤搭子 |
| `OAI Core 服务端地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:229` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器地址 (Core / Gateway) | 标签清晰说明 |
| `登录 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:317` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 登录 OAI 服务器 | 规范统一 |
| `已核对，继续` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:438` | 连接与账号 (Connection & Auth) | 不符合 Android 交互习惯 | **低** | 看好啦，继续！ | 利落俏皮的确认 |
| `我已保存` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:494` | 连接与账号 (Connection & Auth) | 不符合 Android 交互习惯 | **低** | 好哒，已保存！ | 欢快可爱的确认反馈 |
| `未连接 Gateway` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:586` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 还没连上服务器呢，快连一下吧~ | 温和可爱的未连接提醒 |
| `正在连接 OAI Core…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:591` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 正在努力敲服务器的门… | 生动风趣的连接中提示 |
| `正在重新连接 OAI Core…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:593` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 断线别慌，正在重新连线中… | 安抚用户情绪的风趣提示 |
| `且无法核验 Gateway 身份。请仅在你信任的本机或局域网内使用，正式部署请改用 https://。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:706` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 无法核验服务器身份哦。请仅在信任的局域网内使用，正式上线记得换 https:// 呀~ | 严谨安全提示中融入温和语调 |
| `本机已退出，登出未获 Gateway 确认，请检查连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1249` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 已经退出登录啦（服务器还没来得及确认），请检查网络后重试哦~ | 口吻亲切自然 |
| `本机已退出，离线登出未获 Gateway 确认；本机凭据已锁定保留，重新登录后可继续处理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1252` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 已离线退出登录，本地小账本已妥善锁好，重新登录就能继续啦~ | 将冰冷凭据形象化为妥善保管的小账本 |
| `Gateway 已登出，但本机凭据清理失败，请检查设备存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1274` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 已登出，但本机凭据清理失败，请检查设备存储。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `本机已退出，登出未获 Gateway 确认，且本机凭据清理失败，请检查设备存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1276` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 本机已退出，登出未获 服务器 确认，且本机凭据清理失败，请检查设备存储。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `本机账号已移除；Gateway 账号和其他设备配对保持有效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1493` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 本机账号已移除；服务器 账号和其他设备配对保持有效。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `需要连接当前 Core 后才能解除设备配对。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1529` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 需要连接当前 服务器 后才能解除设备配对。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `解除配对未获 Core 确认，本机凭据已保留，请检查连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1549` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 解除配对未获 服务器 确认，本机凭据已保留，请检查连接后重试。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Core 已解除配对，本机任务收尾失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1562` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 已解除配对，本机任务收尾失败，请重试本机账号清理。，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Core 已解除配对，未完成下载的恢复记录清理失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1572` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 已解除配对，未完成下载的恢复记录清理失败，请重试本机账号清理。，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Core 已解除配对，但本机数据清理失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1595` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 已解除配对，但本机数据清理失败，请重试本机账号清理。，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `已向 Gateway 续期会话凭据，新的访问令牌已生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1637` | 连接与账号 (Connection & Auth) | 技术实现暴露 | **高** | 通行证已成功续签，可以继续愉快玩耍啦~ | 消除底层 Token 名词，通俗可爱 |
| `Gateway 尚未提供刷新结果重放，续期结果暂不确定。账号资料已保留，请更新 Gateway 后重新认证。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1649` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 尚未提供刷新结果重放，续期结果暂不确定。账号资料已保留，请更新 服务器 后重新认证。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 会话恢复暂未完成，登录资料已保留，连接可用后将自动重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1650` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 会话恢复暂未完成，登录资料已保留，连接可用后将自动重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 已明确撤销或拒绝会话凭据，请重新登录。账号资料和历史记录已保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1744` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 已明确撤销或拒绝会话凭据，请重新登录。账号资料和历史记录已保留。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `本地镜像读取失败，正在尝试连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1914` | 连接与账号 (Connection & Auth) | 技术实现暴露 | **高** | 本地小记录走丢了，正在重新连接找回中… | 消除镜像实现术语 |
| `设备结果交付重试中。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:2251` | 连接与账号 (Connection & Auth) | 机械式表达 | **高** | 正在拼命重试提交结果中… | 消除机械交付用词，充满活力 |
| `上次使用的 Agent「$lost」已不在当前列表中，请重新选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:2757` | 连接与账号 (Connection & Auth) | 术语不统一 | **高** | 上次使用的 小助手「$lost」已不在当前列表中，请重新选择。 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Gateway 事件通道认证仍被拒绝，已暂停该通道。登录和本机授权已保留，请检查 Gateway 会话与设备认证配置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3054` | 连接与账号 (Connection & Auth) | 技术名词暴露 | **高** | 服务器认证碰壁了，实时连线已先休息。登录资料都在，快去检查下账号吧~ | 化解长篇技术免责 |
| `Gateway 事件通道恢复已暂停，登录资料已保留，请检查连接配置或本机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3072` | 连接与账号 (Connection & Auth) | 技术名词暴露 | **高** | 实时连线先喝口水歇会儿，资料都好好的，检查下网络再试吧~ | 风趣且突出数据安全 |
| `离线镜像：可阅读历史并编辑草稿，重新连接后可发送。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3389` | 连接与账号 (Connection & Auth) | 技术实现暴露 | **高** | 离线模式：翻看历史写草稿都没问题，连上网就能一键起飞~ | 生动形象的离线体验 |
| `Gateway 拒绝续期：刷新凭据已被使用过，请重新登录以取得新的会话凭据。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3416` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 拒绝续期：刷新凭据已被使用过，请重新登录以取得新的会话凭据。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 拒绝续期：App 与 Gateway 版本不一致，请把两端升级到同一版本后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3420` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 拒绝续期：App 与 服务器 版本不一致，请把两端升级到同一版本后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 拒绝续期：当前凭据已失效或被撤销，请重新登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3423` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 服务器 拒绝续期：当前凭据已失效或被撤销，请重新登录。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `刷新网关凭据失败：无法连接 Gateway，当前连接仍然有效，请检查网络后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3427` | 连接与账号 (Connection & Auth) | 技术名词暴露 / 术语不统一 | **高** | 刷新网关凭据失败：无法连接 服务器，当前连接仍然有效，请检查网络后重试。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Agent 实时任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:25` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手任务动态播报 | 生动有活力的通知渠道名 |
| `进行中的 Agent 任务状态，可在系统支持的 Live Updates 界面显示` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:26` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 进行中的 小助手 任务状态，可在系统支持的 Live Updates 界面显示 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Agent 任务排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:59` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 任务排队中… | 精简冗余主语 |
| `等待 Agent 开始处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:61` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 等待助手处理… | 状态与通知文案简洁化 |
| `去设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:161` | 主界面与原生会话 (Main & Bubbles) | 不符合 Android 交互习惯 | **低** | 前往设置瞧瞧 | 萌趣不失指引性 |
| `Agent 会话操作未完成：$code` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:107` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 / 技术名词暴露 | **高** | 助手对话操作未完成：$code | 在 Android 标题栏、清单与列表中，用户交互统称为「对话」，实体称为「助手」，避免暴露内部概念 |
| `此 OAI Core 尚未接入 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:113` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 | **高** | 当前服务端暂未接入小助手。 | 空状态提示应使用自然中文 |
| `此 Core 已接入的共享会话，所有已登录账号均可访问。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:119` | 主界面与原生会话 (Main & Bubbles) | 技术名词暴露 / 术语不统一 | **高** | 此 服务器 已接入的共享会话，所有已登录账号均可访问。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:124` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 / 技术名词暴露 | **中** | 助手对话 | 在 Android 标题栏、清单与列表中，用户交互统称为「对话」，实体称为「助手」，避免暴露内部概念 |
| `此 Agent 未提供会话列表能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:128` | 主界面与原生会话 (Main & Bubbles) | 机械式表达 / 术语不统一 | **中** | 当前助手不支持列出对话。 | 避免使用「未提供...能力」的生硬开发者视角表达 |
| `此 Agent 未提供历史读取能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:156` | 主界面与原生会话 (Main & Bubbles) | 机械式表达 / 术语不统一 | **中** | 当前助手不支持读取历史消息。 | 开发者接口视角转为功能视角 |
| `此 Agent 暂不支持实时消息收发。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:174` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 | **中** | 当前助手暂不支持实时消息收发。 | 统一使用「助手」 |
| `这会调用 ${agent?.name.orEmpty()} 的原生删除接口。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:196` | 主界面与原生会话 (Main & Bubbles) | 技术实现暴露 | **高** | 确认要彻底告别这段对话吗？删掉可就找不回啦~ | 萌趣中不失严谨后果提示 |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:31` | 系统通知与回复 (Notifications & Reply) | 术语不统一 / 技术名词暴露 | **中** | 助手对话 | 在 Android 标题栏、清单与列表中，用户交互统称为「对话」，实体称为「助手」，避免暴露内部概念 |
| `Agent 任务回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:176` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手 任务回复 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Agent 处理中实时显示公开回复，并在任务结束时提醒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:177` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手 处理中实时显示公开回复，并在任务结束时提醒 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:222` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 正在处理… | 精简为标准 MD3 动态进行时状态 |
| `Agent 任务排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:224` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 排队等待中，马上轮到我啦！ | 可爱自白式语调 |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:225` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手正在飞速动脑中… | 拟人化生动思考状态 |
| `Agent 任务已完成` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:226` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 搞定啦！任务已顺利完成~ | 活泼欢快的完成反馈 |
| `Agent 任务失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:227` | 系统通知与回复 (Notifications & Reply) | 反馈不明确 / 术语不统一 | **高** | 哎呀任务卡壳了，点我看看怎么回事吧~ | 风趣且引导明确 |
| `Agent 任务已取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:228` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 任务已取消 | 状态精简 |
| `Gateway 已断开，任务状态待同步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:230` | 系统通知与回复 (Notifications & Reply) | 技术名词暴露 / 内部架构暴露 | **高** | 网络偷偷溜号啦，连上后立即同步进度！ | 风趣幽默的网络断开解释 |
| `等待 Agent 开始处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:233` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手正在赶来的路上… | 充满期待感的拟人化状态 |
| `等待 Agent 回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:234` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手正在全力思考中，等我一下下~ | 可爱亲切的等待文案 |
| `允许系统突出显示运行中的 Agent 任务，具体展示位置由设备决定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:99` | 应用核心与集成 (App Core) | 术语不统一 | **中** | 允许系统突出显示运行中的 小助手 任务，具体展示位置由设备决定 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Agent 任务通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:241` | 应用核心与集成 (App Core) | 术语不统一 | **中** | 小助手 任务通知 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `状态校验未通过，请检查 OAI Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:264` | 应用核心与集成 (App Core) | 技术名词暴露 / 术语不统一 | **中** | 状态校验未通过，请检查 OAI 服务 版本 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `自动把通知发送给 Gateway` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:368` | 系统通知与回复 (Notifications & Reply) | 技术名词暴露 | **中** | 自动将通知同步至服务器 | 统一术语，改用自然动词「同步」 |
| `旧通知投递通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:383` | 系统通知与回复 (Notifications & Reply) | 遗留架构暴露 | **中** | 通知转发通道 | 不应将内部代码重构遗留字样展示在设置项标题上 |
| `加密待发队列已装配；最近创建的旧通知运行时未装配投递组件，或尚未启动监听。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:386` | 系统通知与回复 (Notifications & Reply) | 内部架构暴露 / 表达冗长 | **中** | 通知转发服务尚未就绪，暂未开始监听通知。 | 将内部组件装配状态、队列状态直接暴露，极为机械晦涩 |
| `连接你的 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:461` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 连接你的 OAI 服务 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `不需要再为 Gateway 账号单独开启设备能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:315` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 不需要再为 服务器 账号单独开启设备能力。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `安装 OAI Core 与 CLI` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:81` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 安装 OAI 服务 与 CLI | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `在服务端主机创建独立 Python 环境。Core、CLI 与 MCP 来自同一个项目。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:82` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 在服务端主机创建独立 Python 环境。服务器、CLI 与 MCP 来自同一个项目。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Terminal · 安装 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:84` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | Terminal · 安装 服务器 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Hermes、DSH 或 Codex 实例；具体适配器配置见官方文档。手机始终连接 Core 的同一个地址与端口。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:95` | 初始化引导流程 (Onboarding) | 技术名词暴露 | **中** | Hermes、DSH 或 Codex 实例；具体适配器配置见官方文档。手机始终连接 服务器 的同一个地址与端口。 | Hermes 为内部模型/系统代号，普通用户无法理解，不应出现在工具详情中 |
| `密码通过交互输入。设备能力逐账号授权；Hermes 会话保留账号隔离，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:99` | 初始化引导流程 (Onboarding) | 技术名词暴露 | **中** | 密码通过交互输入。设备能力逐账号授权；Hermes 会话保留账号隔离， | Hermes 为内部模型/系统代号，普通用户无法理解，不应出现在工具详情中 |
| `此服务维护手机连接、账号验证和设备请求状态。切换 Agent 不需要另开手机连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:108` | 初始化引导流程 (Onboarding) | 术语不统一 | **中** | 此服务维护手机连接、账号验证和设备请求状态。切换 小助手 不需要另开手机连接。 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `Terminal · 启动 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:110` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | Terminal · 启动 服务器 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `登录并查看已接入的 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:115` | 初始化引导流程 (Onboarding) | 术语不统一 | **中** | 登录并查看已接入的 小助手 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `手机填写配置好的 Core 地址、OAI 账号与密码。登录后查看真实在线状态与原生会话；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:116` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 手机填写配置好的 服务器 地址、OAI 账号与密码。登录后查看真实在线状态与原生会话； | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `登录后展示此 Core 已明确接入的 Agent；未接入时显示空列表。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:118` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 登录后展示此 服务器 已明确接入的 小助手；未接入时显示空列表。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `旧 Gateway 兼容入口。本阶段不参与 Core 的 Agent 适配器重构，继续按原方式连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:129` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 旧 服务器 兼容入口。本阶段不参与 服务器 的 小助手 适配器重构，继续按原方式连接。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `安装 Gateway 插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:161` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 安装 服务器 插件 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `OAI Core\n部署好了吗？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:109` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | OAI 服务\n部署好了吗？ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `手机只连接一个 OAI Core，由 Core 接入不同 Agent。若已完成部署，直接进入登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:118` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 手机只连接一个 OAI 服务，由 服务器 接入不同 小助手。若已完成部署，直接进入登录。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `已启动 OAI Core 并创建 OAI 账号。使用同一个服务端地址登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:127` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 已启动 OAI 服务 并创建 OAI 账号。使用同一个服务端地址登录。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `查看 OAI Core 安装、账号创建与 oai serve 启动步骤，也保留旧 OpenClaw 兼容指南。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:137` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 查看 OAI 服务 安装、账号创建与 oai serve 启动步骤，也保留旧 OpenClaw 兼容指南。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `去设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:326` | 初始化引导流程 (Onboarding) | 不符合 Android 交互习惯 | **低** | 前往设置 | 统一使用符合 Android Material Design 规范的「前往设置」操作动词 |
| `连接一个\nOAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:174` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 连接一个\nOAI 服务 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `一个手机连接入口，由 OAI Core 接入多个自托管 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:185` | 初始化引导流程 (Onboarding) | 技术名词堆砌 / 架构暴露 | **中** | 手机连接自托管 OAI 服务端，统一管理你的多位小助手。 | 欢迎页大段技术架构解释，应转化为易懂的功能特性说明 |
| `Agent 会话与设备插件独立管理，设备能力始终需要你的授权。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:186` | 初始化引导流程 (Onboarding) | 术语不统一 / 技术名词暴露 | **中** | 助手对话与设备插件独立管理，设备能力始终需要你的授权。 | 在 Android 标题栏、清单与列表中，用户交互统称为「对话」，实体称为「助手」，避免暴露内部概念 |
| `凭据存于 Android Keystore · 自托管 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:199` | 初始化引导流程 (Onboarding) | 技术名词暴露 / 术语不统一 | **中** | 凭据存于 Android Keystore · 自托管 服务器 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `保存失败：请先登录 Gateway。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:216` | 插件与能力扩展 (Plugins) | 技术名词暴露 / 术语不统一 | **中** | 保存失败：请先登录 服务器。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `服务端仍使用旧账号能力策略，请升级 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:528` | 插件与能力扩展 (Plugins) | 技术名词暴露 / 术语不统一 | **中** | 服务端仍使用旧账号能力策略，请升级 OAI 服务 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `服务端尚不支持 Android 统一授权，请升级 OAI Core；当前设备能力不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:529` | 插件与能力扩展 (Plugins) | 技术名词暴露 / 术语不统一 | **中** | 服务端尚不支持 Android 统一授权，请升级 OAI 服务；当前设备能力不可用 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Gateway 未提供能力状态；请检查连接与 OAI Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:540` | 插件与能力扩展 (Plugins) | 技术名词暴露 / 术语不统一 | **中** | 服务器 未提供能力状态；请检查连接与 OAI 服务器 版本 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `参数 JSON` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:43` | 插件与能力扩展 (Plugins) | 技术名词暴露 | **中** | 调用参数 | 向普通用户弹窗暴露开发专用格式 JSON，应使用通俗用语「参数」 |
| `参数 JSON 无效` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:47` | 插件与能力扩展 (Plugins) | 技术名词暴露 | **中** | 参数格式不正确 | 向普通用户弹窗暴露开发专用格式 JSON，应使用通俗用语「参数」 |
| `尚无已验证的接收设备。连接 Core 并刷新后选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:334` | 主动推送策略 (Device Push) | 技术名词暴露 / 术语不统一 | **低** | 尚无已验证的接收设备。连接 服务器 并刷新后选择。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `这次操作需要在手机上的确认，未确认所以没有执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:96` | 屏幕控制与授权 (Screen Control) | 不符合用户直觉 / 语法不通 | **高** | 刚才的操作需要你在手机上点个头，没点头所以先没敢动哦~ | 修复语病，风趣幽默又安全准确 |
| `Core、Agent 和已保存账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:9` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 服务器、小助手 和已保存账号 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `查看 Gateway 部署教程与权限说明` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:83` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 查看 服务器 部署教程与权限说明 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `覆盖当前 Core 的设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:105` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 覆盖当前 服务器 的设置 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `当前 Core：$gatewayUrl` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:124` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器：$gatewayUrl | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `当前 Core 覆盖` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:130` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 覆盖 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `当前 Core 使用全局默认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:139` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 使用全局默认。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `未选择 Core；可编辑全局默认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:143` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **低** | 未选择 服务器；可编辑全局默认。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `$unresolved 个 Core 的消息合并覆盖在本机没有对应地址，已跳过；连接该 Core 后可重新设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:399` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | $unresolved 个 服务器 的消息合并覆盖在本机没有对应地址，已跳过；连接该 服务器 后可重新设置。 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `，${cores.size} 个 Core 单独覆盖` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:171` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | ，${cores.size} 个 服务器 单独覆盖 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `key 必须是 Gateway 地址的 SHA-256。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:182` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | key 必须是 服务器 地址的 SHA-256。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `保存为 JSON 文件，可带到另一台设备或重装后恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:124` | 设置界面 (Settings) | 技术名词暴露 | **中** | 保存为备份文件，换手机或重装时一键带走所有记忆~ | 充满温度的记忆备份 |
| `选择之前导出的 JSON 文件；导入前会先列出将被覆盖的项目` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:140` | 设置界面 (Settings) | 技术名词暴露 | **低** | 选择之前导出的备份文件；导入前会先列出将被覆盖的项目 | 设置导入统一使用「备份文件」概念 |
| `文件中有 ${pending.ignoredFields.size} 个本版本不认识的字段，将被忽略。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:205` | 设置界面 (Settings) | 表达不当 / 拟人化 | **中** | 备份里有 ${pending.ignoredFields.size} 个不认识的新奇玩意儿，先悄悄跳过它们咯~ | 活泼风趣说明字段兼容 |
| `已忽略 ${outcome.ignoredFields.size} 个本版本不认识的字段。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:153` | 设置界面 (Settings) | 表达不当 / 拟人化 | **中** | 已忽略 ${outcome.ignoredFields.size} 个本版本不受支持的项目。 | 「不认识的字段」口语化且轻佻，正式设置界面应使用「不受支持的项目」或「不兼容的设置项」 |
| `Core 同步：${capabilityStatusLabel(diagnostic.status, diagnostic.reason)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:431` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 服务器 同步：${capabilityStatusLabel(diagnostic.status, diagnostic.reason)} | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Core 未连接；本机开关与授权照常生效，连接恢复后自动同步。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:434` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 服务器 未连接；本机开关与授权照常生效，连接恢复后自动同步。呢~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `无法访问 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:39` | 设置界面 (Settings) | 技术名词暴露 | **高** | 无法连接服务器 | 统一为用户能直观理解的「服务器」 |
| `OAI Core 地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:73` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | OAI 服务 地址 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `尚未选择 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:73` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 尚未选择 服务器 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `实时事件通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:75` | 设置界面 (Settings) | 技术名词暴露 | **高** | 实时连接 | EventStream / 事件通道为长连接技术名词，用户层统一称「实时连接」 |
| `当前 Core 不支持 Agent 列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:84` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 不支持 小助手 列表 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `请检查 Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:84` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 请检查 服务器 版本 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `请检查 Core 上的 Agent 配置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:85` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 请检查 服务器 上的 小助手 配置 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Core 身份验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:144` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 服务器 身份验证 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `取消手机与 Core 配对，清理当前账号本地数据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:153` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 取消手机与 服务器 配对，清理当前账号本地数据 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `实时事件通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:189` | 设置界面 (Settings) | 技术名词暴露 | **高** | 实时连接 | EventStream / 事件通道为长连接技术名词，用户层统一称「实时连接」 |
| `连接失败，请检查 Core 地址、网络及账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:196` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 连接失败，请检查 服务器 地址、网络及账号，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `此 Core 支持的功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:200` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 此 服务器 支持的功能 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `当前 Core 未开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:205` | 设置界面 (Settings) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 未开启 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `Agent 任务通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:48` | 设置界面 (Settings) | 术语不统一 | **中** | 小助手 任务通知 | 对外通知和重要标题使用更具亲和力的规范中文「智能助手」或「助手」 |
| `插件：${event.pluginId}\n账号：${event.accountId}\n配对：${event.pairingId}\n请求：${event.correlationId}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:100` | 设置界面 (Settings) | 表达冗长 | **中** | 插件：${event.pluginId}\n账号：${event.ac… | 手机界面 Toast / 错误提示字数过多，超出单屏阅读舒适度 |
| `助理进程无法访问主进程的对话状态。请先打开 App，再从系统助理重新唤起。` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:373` | 数字助手 (Assistant) | 内部架构暴露 | **高** | 小助手还在打瞌睡呢，快去打开 App 把它叫醒吧！ | 将干瘪冰冷的多进程 IPC 架构细节转化为可爱的打瞌睡唤醒 |
| `Gateway 未连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:141` | 数字助手 (Assistant) | 技术名词暴露 / 术语不统一 | **中** | 服务器 未连接呢~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 已连接 · 正在停止生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:142` | 数字助手 (Assistant) | 技术名词暴露 / 术语不统一 | **中** | 服务器 已连接 · 正在停止生成 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 已连接 · 正在生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:143` | 数字助手 (Assistant) | 技术名词暴露 / 术语不统一 | **中** | 服务器 已连接 · 正在生成 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 已连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:144` | 数字助手 (Assistant) | 技术名词暴露 / 术语不统一 | **中** | 服务器 已连接 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `请先在 App 中连接 Gateway` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:150` | 数字助手 (Assistant) | 技术名词暴露 / 术语不统一 | **中** | 请先在 App 中连接 服务器 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `正在从 Gateway 读取内容…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:40` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 正在从 服务器 读取内容… | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `暂时无法取得内容。请检查连接后重试；若持续失败，请检查 Gateway 服务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:70` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 暂时无法取得内容。请检查连接后重试；若持续失败，请检查 服务器 服务。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `无法连接 Gateway。请检查地址与网络，并确认 App 与插件版本一致后重试；若持续失败，请查看 Gateway 服务与日志。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:80` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 无法连接 服务器。请检查地址与网络，并确认 App 与插件版本一致后重试；若持续失败，请查看 服务器 服务与日志。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `登录已成功，但 Gateway 拒绝了事件通道的会话认证，本机授权已冻结。账号资料和历史记录已保留，请重新登录；若持续发生，请检查 Gateway 的会话与设备认证配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:105` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 | **高** | 门票认证被拒啦，重新登录一下就能进去玩耍！本地记录都安好无恙呢~ | 把长达 74 字的技术说教变成可爱安抚 |
| `当前 Gateway 会话已被撤销，本机授权已冻结。账号资料和历史记录已保留，请重新登录。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:107` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 会话已被撤销，本机授权已冻结。账号资料和历史记录已保留，请重新登录。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `会话同步数据未能处理，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App 与 Core 的版本兼容性。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:124` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 会话同步数据未能处理，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App 与 服务器 的版本兼容性。，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `审批决策没有送达 Gateway，卡片已恢复为可点击，请重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:132` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 审批决策没有送达 服务器，卡片已恢复为可点击，请重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `引导消息已交给 Agent，由 Agent 处理当前任务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:134` | 对话界面 - 状态与空页面 (State Views) | 机械式表达 / AI味 | **中** | 消息已提交，助手正在处理… | 过度拟人与机械流程描述，界面应简洁传达状态 |
| `当前 Gateway 不支持会话管理，请安装配套网关更新后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:139` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 不支持会话管理，请安装配套网关更新后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 尚未确认这次会话修改，请刷新列表并重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:148` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 尚未确认这次会话修改，请刷新列表并重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Agent 已经回话了，但没有返回新建会话的结果，无法确认新会话的标识，因此仍停留在原会话。通常意味着当前 Gateway/宿主未实现该命令入口，请重启或升级后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:152` | 对话界面 - 状态与空页面 (State Views) | 表达冗长 / 内部逻辑暴露 | **高** | 新对话没建成功，不过旧记录都安全，咱们还在原来的聊天室哦！ | 消除长篇技术甩锅，可爱直观 |
| `该 Gateway 不支持由 App 新建对话。App 不会创建只有本机可见的会话，请升级 Gateway 或联系部署者。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:154` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 表达冗长 | **中** | 当前服务端不支持在手机端新建对话，请升级服务端或联系管理员。 | 避免使用「App 不会创建只有本机可见的会话」等开发防卫性措辞 |
| `Agent 迟迟没有返回新建的会话，已停在原会话。请确认 Gateway 与 Agent 正在运行后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:157` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 小助手 迟迟没有返回新建的会话，已停在原会话。请确认 服务器 与 小助手 正在运行后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `新建对话失败，已停留在原会话，本地没有留下与 Gateway 不一致的会话。请检查连接后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:162` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 新建对话失败，已停留在原会话，本地没有留下与 服务器 不一致的会话。请检查连接后重试。，请稍后再试试看哦~ | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 未提供可核验的 TLS 身份，已按安全要求拒绝连接。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:167` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 未提供可核验的 TLS 身份，已按安全要求拒绝连接。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `App 与 Gateway 协议版本不一致（或连接已失效），连接被拒绝。请把 App 与插件升级到同一版本后重试；若刚升级过，重新登录一次即可。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:174` | 对话界面 - 状态与空页面 (State Views) | 表达冗长 / 技术名词暴露 | **高** | 我和服务器版本对不上暗号啦！升级一下就能愉快玩耍咯~ | 幽默通俗说明版本问题 |
| `Gateway 与当前 Agent 宿主版本不兼容，请在 Agent 端升级插件或宿主后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:176` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 与当前 小助手 宿主版本不兼容，请在 小助手 端升级插件或宿主后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 正在连接或连接未完成。请确认地址指向有效的 Gateway 服务，并检查版本与运行日志。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:180` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 正在连接或连接未完成。请确认地址指向有效的 服务器 服务，并检查版本与运行日志。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Agent 当前不可用，附件已由 Gateway 接收但尚未处理。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:189` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 小助手 当前不可用，附件已由 服务器 接收但尚未处理。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `登录凭据已失效，请前往账号与 Gateway 重新登录。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:191` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 登录凭据已失效，请前往账号与 服务器 重新登录。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `当前账号没有访问权限，请检查账号或联系 Gateway 管理员。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:192` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 当前账号没有访问权限，请检查账号或联系 服务器 管理员。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `无法验证 Gateway 的安全连接，请检查地址与证书配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:193` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 无法验证 服务器 的安全连接，请检查地址与证书配置。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 附件存储空间不可用，请检查 Agent 端磁盘空间后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:194` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 附件存储空间不可用，请检查 小助手 端磁盘空间后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 或反向代理拒绝了上传请求，请检查服务端存储与代理配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:195` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 或反向代理拒绝了上传请求，请检查服务端存储与代理配置。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Gateway 一直没有返回回复。已尝试重新同步会话，若仍无内容请检查 Gateway 与 Agent 是否在运行。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:198` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 服务器 一直没有返回回复。已尝试重新同步会话，若仍无内容请检查 服务器 与 小助手 是否在运行。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `会话同步进度尚未获 Gateway 确认，本机记录已保留。请检查连接后重新进入会话重试同步，避免重复发送原消息。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:200` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 会话同步进度尚未获 服务器 确认，本机记录已保留。请检查连接后重新进入会话重试同步，避免重复发送原消息。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `读取会话同步数据失败，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App、Core 与 Agent 的版本兼容性。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:202` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 读取会话同步数据失败，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App、服务器 与 小助手 的版本兼容性。，请稍后再试试看哦~ | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `与 Gateway 的实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:204` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 与 服务器 的实时通道已断开，暂时收不到新回复。请检查网络或 服务器 服务后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `暂时无法连接 Gateway，请检查网络和服务地址后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:206` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 暂时无法连接 服务器，请检查网络和服务地址后重试。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `正在连接 Gateway…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:227` | 对话界面 - 状态与空页面 (State Views) | 技术名词暴露 / 术语不统一 | **中** | 正在连接服务器… | 统一使用通俗易懂的「服务器」 |
| `审批结果未知，请以 Gateway 记录为准` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:433` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 审批结果未知，请以 服务器 记录为准 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `正在请求停止，等待 Gateway 确认` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:879` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 正在请求停止，等待 服务器 确认 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `当前 Gateway 不支持停止生成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:882` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 当前 服务器 不支持停止生成 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `此 Gateway 暂不支持会话管理，请更新 Gateway。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:351` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 此 服务器 暂不支持会话管理，请更新 服务器。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `连接 Gateway 后可管理会话。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:352` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 连接 服务器 后可管理会话。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| ` }}”？对话记录将从 Gateway 删除。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:478` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | }}”？对话记录将从 服务器 删除。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `与当前 Gateway 继续对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:250` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 与当前 服务器 继续对话 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `会话协议响应无效，请更新 Core 与适配器` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:262` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 会话协议响应无效，请更新 服务器 与适配器 | 统一全 App 服务端代称，避免普通用户面对 Core 困惑 |
| `等待 Gateway 接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:974` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 | **中** | 等待服务器接收… | 气泡与消息时间线中统一使用「服务器」 |
| `实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:252` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 实时通道已断开，暂时收不到新回复。请检查网络或 服务器 服务。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `输入 / 可查看此 Gateway 提供的命令。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1108` | 对话界面 - 工作台与时间线 (Workbench) | 技术名词暴露 / 术语不统一 | **中** | 输入 / 可查看此 服务器 提供的命令。 | 普通用户无网关概念，除高级网络配置外统称「服务器」 |
| `Hermes 在消息中未提供命令参数` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:333` | 对话界面 - Markdown渲染 (Markdown) | 技术名词暴露 | **高** | 命令未提供具体参数哦 | 彻底抹除 Hermes 内部代号 |
| `参数来自 Hermes 预览，可能已被截断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:340` | 对话界面 - Markdown渲染 (Markdown) | 技术名词暴露 | **高** | 参数来自预览数据，可能不太完整哦~ | 抹除 Hermes 代号，语调温和 |
| `Agent 会话` | `app/src/main/AndroidManifest.xml:93` | 系统清单与组件 (Manifest) | 术语不统一 / 技术名词暴露 | **高** | 和小助手聊聊天 | 多任务卡片可爱亲切 |
| `备份文件不是有效的 JSON。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:89` | 设置界面 (Settings) | 技术名词暴露 | **低** | 这份备份文件好像摔坏了，没法读取呢~ | 形象损坏比喻 |
| `备份文件顶层必须是 JSON 对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:90` | 设置界面 (Settings) | 技术实现暴露 | **低** | 备份文件格式不太对劲，请用从 App 导出的正版备份哦~ | 通俗指出非标准备份 |
| `备份文件缺少 settings。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:93` | 设置界面 (Settings) | 技术实现暴露 | **低** | 备份文件里空空的，找不到设置数据呢~ | 通俗清晰 |
| `备份文件缺少 schemaVersion。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:108` | 设置界面 (Settings) | 技术实现暴露 | **低** | 这份备份忘带“出生证明”（版本号）啦，小助手认不出来~ | 将 schemaVersion 形象比作出生证明 |
| `备份文件来自更新版本的 App（schemaVersion=$version），请先升级 App。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:110` | 设置界面 (Settings) | 技术实现暴露 | **低** | 这份备份来自未来的新版 App！快把我升级一下再来认亲吧~ | 充满科幻幽默感的升级提示 |
| `备份文件 schemaVersion 非法。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:112` | 设置界面 (Settings) | 技术实现暴露 | **低** | 备份版本号奇奇怪怪的，不敢乱恢复呢~ | 风趣克制 |
| `备份文件缺少导出时间。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:114` | 设置界面 (Settings) | 反馈不明确 | **低** | 备份文件忘写导出日期啦~ | 生活化表达 |
| `备份文件缺少 App 版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:116` | 设置界面 (Settings) | 术语不统一 | **低** | 备份文件不知道是哪个版本生成的呢~ | 通俗口语化 |
| `备份文件缺少 App versionCode。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:118` | 设置界面 (Settings) | 技术实现暴露 | **低** | 备份文件缺少内部版本编号呢~ | 消除 versionCode |
| `备份文件版本 $version 无法迁移到当前版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:123` | 设置界面 (Settings) | 表达生硬 | **低** | 这个远古版本的备份和现在不兼容，搬不过来啦~ | 幽默说明远古版本 |
| `设置项 $name 必须是 JSON 对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:130` | 设置界面 (Settings) | 技术名词暴露 | **低** | 设置项 $name 长相不太符合规范呢~ | 消除 JSON 对象术语 |
| `字段 $name 必须是字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:148` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 内容格式错误（须为文本）。 | 将编程类型「字符串」转化为通俗用词「文本」 |
| `字段 $name 必须是布尔值。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:152` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 内容格式错误（须为开关值）。 | 将编程类型「布尔值」转化为通俗用词「开关值」 |
| `字段 $name 必须是整数。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:157` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 内容格式错误（须为整数数值）。 | 规范表述为通俗中文 |
| `字段 $name 必须是数组。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:162` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 内容格式错误（须为列表）。 | 将编程类型「数组」转化为通俗用词「列表」 |
| `字段 $name 只能包含字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:165` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 列表项须为文本。 | 通俗化说明 |
| `字段 $name 必须是数组。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:171` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 内容格式错误（须为列表）。 | 通俗化说明 |
| `字段 $name 只能包含对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:173` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 列表项格式异常。 | 通俗化说明 |
| `字段 $name 必须是对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:179` | 设置界面 (Settings) | 技术实现暴露 | **低** | 设置项 $name 格式异常。 | 通俗化说明 |
| `通知提醒偏好未导入：当前版本未接入 Agent 任务通知。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:353` | 设置界面 (Settings) | 术语不统一 | **中** | 任务通知设置跳过啦：当前版本还没有这项本领呢~ | 萌趣自谦口吻 |
| `$missingAgent 条会话排序未导入：本机还没有对应 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:443` | 设置界面 (Settings) | 术语不统一 | **中** | $missingAgent 条对话排序先跳过啦：手机上还没安装对应的小助手呢~ | 可爱直观 |
| `Agent 选择将在重新连接后生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:481` | 设置界面 (Settings) | 术语不统一 | **中** | 换好助手搭子啦，重新连一下就带它出场！ | 充满登场期待感 |
| `$missing 个账号的 Agent 选择未导入：本机还没有对应账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:483` | 设置界面 (Settings) | 术语不统一 | **中** | $missing 个账号的偏好先跳过啦：手机里还没登录这些账号呢~ | 通俗易懂 |
| `通知采集范围未导入：当前版本未装配通知采集。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:506` | 设置界面 (Settings) | 机械式表达 | **中** | 通知采集设置跳过啦：当前应用还没有点亮这项技能哦~ | 以“点亮技能”替代冷硬“未装配” |
| `Agent 选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:661` | 设置界面 (Settings) | 术语不统一 | **中** | 选择助手 | 统一操作文案为动词短语「选择助手」 |
| `通知与提醒：Agent 任务通知${if (replyAlertsEnabled) ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:111` | 设置界面 (Settings) | 术语不统一 | **中** | 通知与提醒：助手任务通知${if (replyAlertsEnabled) | 设置项摘要统一为「助手任务通知」 |
| `会话列表：${organizations.size} 个账号/Agent 的排序、文件夹与归类` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:229` | 设置界面 (Settings) | 术语不统一 | **中** | 对话列表：${organizations.size} 个账号/助手的排序与归类 | 统一会话为对话，Agent 为助手 |
| `含重复的账号/Agent 组合。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:278` | 设置界面 (Settings) | 术语不统一 | **中** | 存在重复的账号与助手组合。 | 统一称谓为助手 |
| `Agent 选择：记录 ${selectedByProfile.size} 个账号的上次选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:305` | 设置界面 (Settings) | 术语不统一 | **中** | 助手选择：记录 ${selectedByProfile.size} 个账号的偏好 | 统一实体为助手 |
| `含空 Agent ID。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:314` | 设置界面 (Settings) | 技术实现暴露 | **中** | 助手标识不能为空。 | 消除内部变量名 Agent ID，通俗说明 |
| `Agent 管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:78` | 设置界面 (Settings) | 术语不统一 | **中** | 助手管理 | 统一模块标题称谓为「助手管理」 |
| `当前 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:82` | 设置界面 (Settings) | 术语不统一 | **中** | 当前助手 | 统一实体称谓为助手 |
| `正在检查 Agent 支持情况` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:83` | 设置界面 (Settings) | 术语不统一 | **中** | 正在检查助手支持情况… | 统一称谓并添加省略号表示动态 |
| `尚未发现 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:85` | 设置界面 (Settings) | 术语不统一 | **中** | 未发现可用助手 | 统一称谓为助手 |
| `刷新 Agent 列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:100` | 设置界面 (Settings) | 术语不统一 | **中** | 刷新助手列表 | 统一操作按钮为「刷新助手列表」 |
| `尚未加载 Agent 命令目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:79` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 命令小手册还在翻找中… | 形象比喻 |
| `正在加载 Agent 命令目录…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:80` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 正在加载命令小手册… | 活泼形象 |
| `Agent 尚未提供可用命令，回车可原样发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:81` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 / 表达冗长 | **中** | 暂无专属快捷命令，按回车直接发给我吧~ | 清晰且亲和 |
| `无法加载 Agent 命令目录（${catalogState.code}）` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:82` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 翻不开命令小手册呢（${catalogState.code}） | 幽默通俗 |
| `Agent 目录中没有匹配命令，回车可原样发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:195` | 对话界面 - 工作台与时间线 (Workbench) | 表达冗长 / 术语不统一 | **中** | 没找到对应命令，按回车直接发送就行~ | 自然省心 |
| `当前 Agent 不支持删除对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:620` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 小助手舍不得删这条对话，先留着吧~ | 幽默温馨 |
| `等待 Agent 接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:255` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **高** | 正在递给小助手… | 生动动作细节 |
| `Agent 已接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:256` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **高** | 小助手收下啦！ | 充满正反馈的提示 |
| `Agent 处理完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:257` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 搞定啦！ | 轻快简短 |
| `Agent 处理失败：` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:258` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **高** | 糟糕，这次没办成： | 拟人化自白 |
| `Agent 暂不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:259` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **高** | 小助手暂时走开啦 | 萌趣暂离提示 |
| `当前 Agent 不支持此消息或附件功能` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:261` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 / 表达生硬 | **中** | 小助手还不认识这类文件，换个常见格式吧~ | 可爱通俗 |
| `Agent 读取附件失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:264` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 没能看清附件内容呢，再发一次试试？ | 亲切可爱 |
| `Agent 无法处理此媒体` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:265` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **中** | 这种多媒体小助手还消化不了呢~ | 自嘲幽默 |
| `随时向 Agent 提问，或发送图片与文件。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1078` | 对话界面 - 工作台与时间线 (Workbench) | 术语不统一 | **高** | 随时抛问题给我，发图甩文件通通没问题！ | 热情开朗的迎宾语气 |
| `审批已超时，命令未执行。如需执行请让 Agent 重新发起。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:130` | 对话界面 - 状态与空页面 (State Views) | 表达冗长 / 术语不统一 | **中** | 等太久超时啦，操作没敢执行。想继续的话让小助手重新发起吧~ | 温和风趣说明原因 |
| `当前 Agent 不支持删除对话，会话记录已保留。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:140` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **中** | 小助手舍不得删这条对话，已经替你好好存着啦！ | 幽默化解能力缺失尴尬 |
| `当前 Agent 不支持归档操作，会话记录已保留。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:141` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **中** | 这个对话还不支持归档，先留在这儿陪你吧~ | 亲切温和 |
| `当前 Agent 不支持重命名对话。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:142` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **中** | 这个对话改不了名字哦，就叫它原来的名字吧~ | 口吻自然 |
| `当前 Agent 已离线，请等待恢复连接后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:144` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **高** | 助手掉线喝水去啦，等网络连上就飞奔回来！ | 拟人化幽默化解掉线 |
| `正在等待 Agent 返回新会话，请稍候。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:150` | 对话界面 - 状态与空页面 (State Views) | 内部逻辑暴露 / 术语不统一 | **高** | 正在打包一份全新的对话，马上就好~ | 生动期待感 |
| `Agent 无法读取附件，请重新选择文件后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:183` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **高** | 哎呀，这个文件有点认生，重新选一个试试吧~ | 生动拟人化 |
| `Agent 不支持处理此附件格式。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:185` | 对话界面 - 状态与空页面 (State Views) | 术语不统一 | **中** | 这个文件格式把小助手难倒了，换个格式试试看？ | 自嘲幽默语气 |
| `模型拒绝了包含附件的请求格式，请检查 Agent 端模型配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:187` | 对话界面 - 状态与空页面 (State Views) | 内部实现暴露 | **中** | 附件发送碰壁啦，当前助手模型暂时吃不下这种格式哦~ | 幽默通俗解释模型限制 |
| `已允许：Agent 可以按下面的范围读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:307` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **中** | 已允许：小助手可以在下面圈出的范围里看通知~ | 亲切可爱 |
| `未允许：Agent 读取通知时会被拒绝` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:309` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **中** | 未允许：小助手会被挡在门外，看不到通知哦~ | 生动被拒画面 |
| `加密待发队列不可用，旧通知无法排队投递。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:385` | 系统通知与回复 (Notifications & Reply) | 内部架构暴露 | **中** | 通知转发通道还没搭好，旧通知暂时没法同步呢~ | 消除加密待发队列内部名词 |
| `接收 Agent 回复、生成完成与审批提醒。关闭后仍可使用，但需要保持应用在前台。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:80` | 初始化引导流程 (Onboarding) | 术语不统一 | **中** | 接收助手回复、生成完成与审批提醒。关闭后仍可使用，但需要保持应用在前台。 | 引导流程统一使用「助手」 |
| `权限 ≠ 授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:308` | 初始化引导流程 (Onboarding) | 说教式机械表达 | **中** | 权限与小秘密（使用说明） | 消除说教辩解 |
| `Agent 正在执行任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:59` | 系统通知与回复 (Notifications & Reply) | 术语不统一 / 机械式表达 | **高** | 小助手正在全力干活中… | 可爱勤奋的拟人状态 |
| `Agent 已回复，仍有任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:219` | 系统通知与回复 (Notifications & Reply) | 术语不统一 | **高** | 小助手已回话，后台任务还在加班加点中~ | 可爱拟人化表达 |
| `Agent 列表暂不可用，请刷新重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:113` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 | **高** | 小助手们还在后台排队，刷新一下看看？ | 生动排队画面感 |
| `此 Agent 的会话按当前 OAI 账号隔离。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:118` | 主界面与原生会话 (Main & Bubbles) | 技术实现暴露 | **中** | 各个小助手的对话记录都是各自的小秘密，互不打扰~ | 通俗可爱的隐私隔离隐喻 |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:174` | 主界面与原生会话 (Main & Bubbles) | 术语不统一 / 技术名词暴露 | **高** | 小助手对话框 | 亲和可爱的界面标题 |

---

## 六、全量中文文案清单 (按功能模块索引)

> **本清单包含 Android App 生产代码与清单中全部 2,095 条用户可见中文文案（已剔除 5 条纯内部代码片段与调试日志）。**
> 每条文案均标明精确文件路径、行号、组件归属、触发场景、可达性以及「**保留**」或「**修改**」处置判定。

### 6.1 设置界面 (510 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `外观与交互` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:8` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `主题、配色与动画` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:8` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `连接与账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:9` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `Core、Agent 和已保存账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:9` | 正文/说明 | SettingsDestinations | 生产可达 | **修改** |
| `通知与提醒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:10` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `任务通知、会话气泡与系统权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:10` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `设备能力与授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:11` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `设备能力、主动推送与插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:11` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `安全与隐私` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:12` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `运行策略、操作记录与紧急停止` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:12` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `高级设置与关于` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:13` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `消息处理、应用维护与帮助` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsDestinations.kt:13` | 正文/说明 | SettingsDestinations | 生产可达 | **保留** |
| `当前未接入账号管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:172` | 错误提示 | SettingsNavHost | 异常兜底 | **保留** |
| `设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:211` | 正文/说明 | SettingsNavHost | 生产可达 | **保留** |
| `开启开发者信任模式？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:308` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `原生插件将使用本应用的权限并访问全部数据。请仅在信任插件来源时开启。\n\n${DeveloperTrustMode.Acknowledgement.RE…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:309` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `我已知晓并开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:310` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:311` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `紧急停止所有插件？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:312` | 标题 | SettingsNavHost | 异常兜底 | **保留** |
| `将立即停止所有插件并撤销临时授权，需重启应用恢复。保存的设置与系统权限不变。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:313` | 标题 | SettingsNavHost | 异常兜底 | **保留** |
| `确认停止` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:314` | 按钮/操作 | SettingsNavHost | 异常兜底 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:315` | 按钮/操作 | SettingsNavHost | 异常兜底 | **保留** |
| `设备配对已解除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:320` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `解除设备配对？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:320` | 标题 | SettingsNavHost | 生产可达 | **保留** |
| `将取消设备配对并断开连接，清理手机上的本地聊天与缓存数据。系统权限与全局功能设置保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:322` | 正文/说明 | SettingsNavHost | 生产可达 | **保留** |
| `正在请求取消配对，请稍候…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:323` | 正文/说明 | SettingsNavHost | 生产可达 | **保留** |
| `配对已解除，手机端数据已清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:324` | 正文/说明 | SettingsNavHost | 生产可达 | **保留** |
| `知道了` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:331` | 按钮/操作 | SettingsNavHost | 异常兜底 | **保留** |
| `确认解除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:331` | 按钮/操作 | SettingsNavHost | 异常兜底 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt:332` | 按钮/操作 | SettingsNavHost | 异常兜底 | **保留** |
| `关闭设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsScaffold.kt:58` | 按钮/操作 | SettingsScaffold | 生产可达 | **保留** |
| `返回` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsScaffold.kt:59` | 正文/说明 | SettingsScaffold | 生产可达 | **保留** |
| `高级设置与关于` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:60` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `消息处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:62` | 正文/说明 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `应用维护` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:65` | 正文/说明 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `应用更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:68` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `检查正式版和预览版更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:69` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `备份与恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:73` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `导出或导入本机设置，换机与重装后无需逐项重配` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:74` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `帮助与反馈` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:79` | 正文/说明 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `新手引导与部署教程` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:82` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `查看 Gateway 部署教程与权限说明` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:83` | 标题 | AdvancedSettingsScreen | 生产可达 | **修改** |
| `项目文档` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:86` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `问题与反馈` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:87` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `关于 OAI` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:91` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:93` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:93` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `构建渠道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:95` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:98` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `构建详情` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:99` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `开源许可证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:100` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `项目地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:101` | 标题 | AdvancedSettingsScreen | 生产可达 | **保留** |
| `无法打开链接，请检查系统浏览器后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/AdvancedSettingsScreen.kt:111` | 错误提示 | AdvancedSettingsScreen | 异常兜底 | **保留** |
| `构建详情` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:27` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `当前安装` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:29` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:31` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:31` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `版本代码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:32` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `构建渠道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:34` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:37` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `应用 ID` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:38` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:38` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `分发版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:39` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:39` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `签名证书 SHA-256` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:41` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:42` | 标题 | BuildDetailsScreen | 生产可达 | **保留** |
| `以上信息来自当前安装包及其构建信息。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildDetailsScreen.kt:46` | 正文/说明 | BuildDetailsScreen | 生产可达 | **保留** |
| `未知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/BuildLabels.kt:9` | 系统通知 | BuildLabels | 生产可达 | **保留** |
| `消息处理暂不可编辑：运行时未连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:91` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `全局默认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:94` | 标题 | MessageProcessingSettings | 生产可达 | **保留** |
| `覆盖当前 Core 的设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:105` | 正文/说明 | MessageProcessingSettings | 生产可达 | **修改** |
| `当前 Core：$gatewayUrl` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:124` | 正文/说明 | MessageProcessingSettings | 生产可达 | **修改** |
| `当前 Core 覆盖` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:130` | 标题 | MessageProcessingSettings | 生产可达 | **修改** |
| `当前 Core 使用全局默认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:139` | 错误提示 | MessageProcessingSettings | 异常兜底 | **修改** |
| `未选择 Core；可编辑全局默认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:143` | 正文/说明 | MessageProcessingSettings | 生产可达 | **修改** |
| `0 秒直接发送；大于 0 秒合并短时间连续消息（最长 30 秒，最多 20 条）。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:146` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `消息处理设置未能保存，重启后可能恢复原设置，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:157` | 错误提示 | MessageProcessingSettings | 异常兜底 | **保留** |
| `合并连续消息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:177` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `立即发送（不合并）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:179` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `等待 ${draftDelay.toInt() / 1000.0} 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:179` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `$title 等待时间` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:191` | 无障碍辅助 | MessageProcessingSettings | 生产可达 | **保留** |
| `$title：新消息延长等待` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/MessageProcessingSettings.kt:194` | 正文/说明 | MessageProcessingSettings | 生产可达 | **保留** |
| `开源许可证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/OpenSourceNoticesScreen.kt:29` | 标题 | OpenSourceNoticesScreen | 生产可达 | **保留** |
| `此处展示项目许可证及随应用分发的 KaTeX 许可证原文；并非全部第三方依赖清单。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/OpenSourceNoticesScreen.kt:32` | 正文/说明 | OpenSourceNoticesScreen | 生产可达 | **保留** |
| `无法读取此许可证，请查看项目仓库中的原文。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/advanced/OpenSourceNoticesScreen.kt:40` | Toast/提示 | OpenSourceNoticesScreen | 异常兜底 | **保留** |
| `外观与交互` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:67` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `主题模式` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:108` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `主题色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:113` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `系统动态取色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:118` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `主题色由壁纸提取；关闭后使用预设色板` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:120` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `从当前壁纸提取配色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:120` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `色彩主题` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:128` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `（已启用动态取色）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:130` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `已开启动态取色；选用预设色板将关闭动态取色。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:147` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `已开启动态取色；关闭开关后可选预设色板。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:149` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `使用预设色板；开启后将改用壁纸配色。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:151` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `桌面图标` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:159` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `图标动态取色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:162` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `跟随桌面主题配色，需在系统桌面开启主题图标` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:163` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `动效与无障碍` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:169` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `减少动画` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:172` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `减少滑动与缩放过渡；遵循系统动效设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:173` | 标题 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `外观设置未能保存，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:186` | 错误提示 | AppearanceSettingsScreen | 异常兜底 | **保留** |
| `跟随系统` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:227` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `浅色模式` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:228` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `浅色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:228` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `深色模式` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:229` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `深色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:229` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `图标同款` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt:277` | 正文/说明 | AppearanceSettingsScreen | 生产可达 | **保留** |
| `备份文件中没有可恢复的设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:249` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `备份文件中没有可恢复的设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:277` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:303` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `导入失败，本机设置已恢复为导入前的状态。${readableFailure(failure)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:307` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `导入失败，且以下设置未能恢复原状，请到对应设置页核对：${unrestored.joinToString(` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:309` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:338` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `通知提醒偏好未导入：当前版本未接入 Agent 任务通知。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:353` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:357` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `消息合并已保存；当前会话在下次发送时生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:394` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:396` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `$unresolved 个 Core 的消息合并覆盖在本机没有对应地址，已跳过；连接该 Core 后可重新设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:399` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `会话排序未导入：本机安装标识尚未生成。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:412` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `会话排序与文件夹已保存；若列表未立即变化，重新进入会话列表即可。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:440` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `$missingProfile 条会话排序未导入：本机还没有对应账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:442` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `$missingAgent 条会话排序未导入：本机还没有对应 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:443` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `连接地址已保存为登录页默认值；已登录的账号不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:457` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:459` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `Agent 选择将在重新连接后生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:481` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `$missing 个账号的 Agent 选择未导入：本机还没有对应账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:483` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:490` | 系统通知 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `通知采集范围未导入：当前版本未装配通知采集。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:506` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:514` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `通知采集范围已更新；此前已采集、尚未发送的通知按旧策略记录，不会补发。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:532` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `通知采集范围保存时被其他操作修改，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:535` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `通知采集范围保存失败（${outcome.reason}）。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:536` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `主动推送范围未导入：本机加密队列不可用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:549` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `主动推送范围未导入：无法读取本机推送配置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:553` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:559` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `推送容量不能小于当前排队的事件数，请先清理队列或调大上限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:566` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `主动推送范围已更新；不再符合新范围或已超保留期的排队事件已移除，该操作不可撤销。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:571` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `设备能力策略未导入：本机授权状态不可用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:582` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `设备能力策略未导入：本机授权状态异常，请重启应用后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:586` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `设备能力策略未导入：当前没有已安装的能力可对应。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:591` | 状态/空页面 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `${merged.unmatched} 项设备能力策略未导入：本机插件声明已变化（升级或重装），请在对应能力页重新设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:604` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `设备能力：${merged.policies.size} 项（本机已是该值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:608` | 状态/空页面 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `设备能力：${changed.size} 项的授权模式与读取范围（总开关保持本机值）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:612` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `外观` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:641` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `通知提醒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:648` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `消息处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:652` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `连接地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:656` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `Agent 选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:661` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **修改** |
| `应用更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:664` | 正文/说明 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `通知采集范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:673` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `设备能力策略` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:686` | 状态/空页面 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `会话排序` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:691` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `会话列表刷新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:699` | 正文/说明 | SettingsBackupCoordinator | 生产可达 | **保留** |
| `请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:862` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `通知偏好未能保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:863` | 正文/说明 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `更新渠道未能保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:864` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `连接地址未能保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:865` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupCoordinator.kt:866` | 错误提示 | SettingsBackupCoordinator | 异常兜底 | **保留** |
| `备份文件不是有效的 JSON。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:89` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件顶层必须是 JSON 对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:90` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件缺少 settings。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:93` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件缺少 schemaVersion。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:108` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件来自更新版本的 App（schemaVersion=$version），请先升级 App。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:110` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件 schemaVersion 非法。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:112` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件缺少导出时间。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:114` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件缺少 App 版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:116` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件缺少 App versionCode。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:118` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `备份文件版本 $version 无法迁移到当前版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:123` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `设置项 $name 必须是 JSON 对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:130` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:148` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是布尔值。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:152` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是整数。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:157` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是数组。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:162` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 只能包含字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:165` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是数组。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:171` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 只能包含对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:173` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `字段 $name 必须是对象。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupDocument.kt:179` | 错误提示 | SettingsBackupDocument | 异常兜底 | **修改** |
| `取值「$value」不受支持。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:41` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `外观：主题` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:63` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `，预设色板 ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:64` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `（跟随系统壁纸取色）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:65` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `，图标动态取色开` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:66` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `，已减少动画` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:67` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `，动画开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:67` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:77` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:79` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `跟随系统` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:96` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `浅色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:97` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `深色` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:98` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `通知与提醒：Agent 任务通知${if (replyAlertsEnabled) ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:111` | 正文/说明 | SettingsBackupModel | 生产可达 | **修改** |
| `（系统通知权限仍需在 Android 设置中授予）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:112` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:117` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `立即发送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:128` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `${delayMillis / 1000.0} 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:128` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:135` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `超出 0–10000 毫秒。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:137` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `消息处理：全局 ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:169` | 状态/空页面 | SettingsBackupModel | 生产可达 | **保留** |
| `保持本机现值` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:170` | 状态/空页面 | SettingsBackupModel | 生产可达 | **保留** |
| `，${cores.size} 个 Core 单独覆盖` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:171` | 状态/空页面 | SettingsBackupModel | 生产可达 | **修改** |
| `缺少 key。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:181` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `key 必须是 Gateway 地址的 SHA-256。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:182` | 错误提示 | SettingsBackupModel | 异常兜底 | **修改** |
| `会话列表：${organizations.size} 个账号/Agent 的排序、文件夹与归类` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:229` | 正文/说明 | SettingsBackupModel | 生产可达 | **修改** |
| `缺少 profileId。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:240` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `缺少 agentId。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:242` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `缺少 folderId。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:248` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `缺少 name。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:249` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `只能包含字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:255` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含空或重复的 folderId。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:260` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含空文件夹名称。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:262` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含空或重复的会话 ID。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:264` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含空会话 ID。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:266` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含重复的账号/Agent 组合。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:278` | 错误提示 | SettingsBackupModel | 异常兜底 | **修改** |
| `连接地址：$gatewayUrl（只用于登录页预填，不包含账号与凭据）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:287` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:292` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `「$url」不是合法的 http/https 地址。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:294` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `Agent 选择：记录 ${selectedByProfile.size} 个账号的上次选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:305` | 正文/说明 | SettingsBackupModel | 生产可达 | **修改** |
| `含空档案 ID。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:311` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `只能包含字符串。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:313` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `含空 Agent ID。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:314` | 错误提示 | SettingsBackupModel | 异常兜底 | **修改** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:364` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `设备能力：${policies.size} 项的授权模式与读取范围（总开关与系统权限不变）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:392` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺少 key。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:398` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `缺少 policy。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:400` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `应用更新：${if (channel == ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:411` | 系统通知 | SettingsBackupModel | 生产可达 | **保留** |
| `}渠道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:411` | 系统通知 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:418` | 系统通知 | SettingsBackupModel | 生产可达 | **保留** |
| `通知采集范围：` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:531` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| ` 个允许读取的应用，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:531` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `含正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:532` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `仅元数据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:532` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `自动发送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:533` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `按需查询` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:533` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `（读取授权仍需在本机确认）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:534` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `主动推送范围：${packageIds.size} 个通知来源、${senderAddresses.size} 个短信号码，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:572` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `开关状态与登录凭据不在备份内` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:573` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `；保留 $retentionHours 小时，上限 $maxEvents 条` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:605` | 正文/说明 | SettingsBackupModel | 生产可达 | **保留** |
| `缺失。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:618` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `超出 $MIN_RETENTION–$MAX_RETENTION 小时。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:621` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `超出 $MIN_EVENTS–$MAX_EVENTS 条。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:625` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `超出允许的队列容量。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupModel.kt:629` | 错误提示 | SettingsBackupModel | 异常兜底 | **保留** |
| `备份与恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:111` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `备份` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:120` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `导出设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:123` | 标题 | SettingsBackupScreen | 生产可达 | **保留** |
| `保存为 JSON 文件，可带到另一台设备或重装后恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:124` | 标题 | SettingsBackupScreen | 生产可达 | **修改** |
| `当前可导出内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:129` | 标题 | SettingsBackupScreen | 生产可达 | **保留** |
| `正在读取…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:130` | 标题 | SettingsBackupScreen | 生产可达 | **保留** |
| `${state.exportSummary.size} 类设置：` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:131` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:136` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `导入设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:139` | 标题 | SettingsBackupScreen | 生产可达 | **保留** |
| `选择之前导出的 JSON 文件；导入前会先列出将被覆盖的项目` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:140` | 标题 | SettingsBackupScreen | 生产可达 | **修改** |
| `不会导入：账号密码、登录凭据、主密钥、会话历史、附件、` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:145` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `系统权限与设备能力的实际授权状态。这些仍需在手机上手确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:146` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `知道了` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:160` | 按钮/操作 | SettingsBackupScreen | 生产可达 | **保留** |
| `导入这份设置？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:184` | 标题 | SettingsBackupScreen | 生产可达 | **保留** |
| `来源：App ${pending.appVersionName}，导出于 ${readableTime(pending.exportedAt)}；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:193` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `本机 App ${pending.localVersionName}。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:194` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `将覆盖本机的同名设置项：` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:199` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `文件中有 ${pending.ignoredFields.size} 个本版本不认识的字段，将被忽略。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:205` | 正文/说明 | SettingsBackupScreen | 生产可达 | **修改** |
| `以下内容与当前一致的项目不会被重复写入；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:211` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `账号凭据、系统权限与设备能力总开关不会被导入修改。导入失败会自动恢复本机原设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:212` | 正文/说明 | SettingsBackupScreen | 生产可达 | **保留** |
| `导入并覆盖` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:218` | 按钮/操作 | SettingsBackupScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:219` | 按钮/操作 | SettingsBackupScreen | 生产可达 | **保留** |
| `文件过大，不是本 App 导出的设置文件。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupScreen.kt:242` | 错误提示 | SettingsBackupScreen | 异常兜底 | **保留** |
| `当前环境无法读取设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:52` | 正文/说明 | SettingsBackupViewModel | 生产可达 | **保留** |
| `无法读取本机设置摘要：${failure.message ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:67` | Toast/提示 | SettingsBackupViewModel | 异常兜底 | **保留** |
| `无法写入所选位置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:82` | 错误提示 | SettingsBackupViewModel | 异常兜底 | **保留** |
| `设置已导出（$name）。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:90` | Toast/提示 | SettingsBackupViewModel | 生产可达 | **保留** |
| `导出失败：${it.message ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:91` | Toast/提示 | SettingsBackupViewModel | 生产可达 | **保留** |
| `无法读取所选文件。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:105` | 错误提示 | SettingsBackupViewModel | 异常兜底 | **保留** |
| `无法导入：${it.message ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:121` | Toast/提示 | SettingsBackupViewModel | 异常兜底 | **保留** |
| `已恢复 ${outcome.appliedSummaries.size} 项设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:150` | 状态/空页面 | SettingsBackupViewModel | 生产可达 | **保留** |
| `已忽略 ${outcome.ignoredFields.size} 个本版本不认识的字段。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:153` | Toast/提示 | SettingsBackupViewModel | 生产可达 | **修改** |
| `导入失败：${it.message ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/backup/SettingsBackupViewModel.kt:158` | 错误提示 | SettingsBackupViewModel | 异常兜底 | **保留** |
| `屏幕` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:26` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `通讯与通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:27` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `屏幕内容读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:30` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `屏幕截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:31` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `屏幕控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:32` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `短信` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:34` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `通知读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:42` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `通话记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityCatalog.kt:43` | 正文/说明 | CapabilityCatalog | 生产可达 | **保留** |
| `短信定时任务设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:85` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `短信任务状态查询` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:86` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `短信任务取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:87` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `屏幕读取与控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:104` | 标题 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `停止屏幕请求` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:127` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `停止后将撤销所有临时授权并终止屏幕请求，长期设置保持不变。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:129` | 按钮/操作 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `立即停止当前屏幕请求` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:132` | 按钮/操作 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `停止当前屏幕请求` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:136` | 标题 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `将撤销所有临时授权并终止当前的屏幕请求，功能设置和系统权限不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:137` | 标题 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `临时授权已撤销，但未能停止屏幕请求，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:142` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `停止并撤销` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:145` | 按钮/操作 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:146` | 按钮/操作 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `系统权限已授予；本机能力设置保持原样。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:191` | Toast/提示 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `系统权限未授予；本机能力设置保持原样，可在应用权限设置中检查。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:191` | Toast/提示 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `插件运行环境未连接，暂时无法读取或修改本机设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:233` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `这项能力未能加载：${builtInFailureLabel(builtInFailure ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:242` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `)}。请重新安装应用；仍失败请反馈这一原因。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:242` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `看不到这项能力的提供者。请确认对应插件仍然安装并启用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:244` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `打开插件管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:248` | 按钮/操作 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:258` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `本机授权设置未能保存，请检查存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:275` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `未能选择此提供者，请检查插件状态后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:284` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `使用此插件提供这项能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:286` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `短信任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:313` | 状态/空页面 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `本机授权设置未能保存，请检查存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:329` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `${label}开关` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:334` | 无障碍辅助 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `三项都只控制对应能力；是否读取短信仍由上方的短信读取开关决定。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:338` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `后台任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:352` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `允许后台任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:360` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `后台任务许可未能保存，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:365` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `允许后台任务开关` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:370` | 无障碍辅助 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `定时与后台操作需要这项单独许可；普通查询不需要。它与上方能力开关、系统权限分别生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:374` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `Android 系统权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:386` | 状态/空页面 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `运行状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:427` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `Core 同步：${capabilityStatusLabel(diagnostic.status, diagnostic.reason)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:431` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **修改** |
| `Core 未连接；本机开关与授权照常生效，连接恢复后自动同步。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:434` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **修改** |
| `通知采集范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:447` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `推送配置已在其他位置修改，请重新检查后再保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:495` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `推送设置未能保存，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:496` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `无法保存：范围或容量无效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:498` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `推送设置未能保存，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:500` | 错误提示 | CapabilitySettingsScreen | 异常兜底 | **保留** |
| `主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:507` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `已就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:535` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `插件未启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:536` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `未获手机授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:537` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `系统权限或服务未就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:538` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `尚未选择提供者` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:539` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `手机服务未就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:540` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:541` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `服务已开启但当前未连接，请回到无障碍设置确认服务显示「已连接」` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:560` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `系统权限或服务已就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:561` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `权限尚未授予` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:562` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `权限被拒绝` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:563` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `需在系统设置中开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:564` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `需要屏幕共享授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:565` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `前往系统设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:576` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `申请系统权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:576` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `打开无障碍设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:577` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `打开通知使用权设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:578` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `发起屏幕共享授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:579` | 正文/说明 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `打开应用权限设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilitySettingsScreen.kt:584` | 按钮/操作 | CapabilitySettingsScreen | 生产可达 | **保留** |
| `已开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityState.kt:12` | 正文/说明 | CapabilityState | 生产可达 | **保留** |
| `已关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityState.kt:15` | 正文/说明 | CapabilityState | 生产可达 | **保留** |
| `需授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityState.kt:18` | 正文/说明 | CapabilityState | 生产可达 | **保留** |
| `不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityState.kt:21` | 正文/说明 | CapabilityState | 生产可达 | **保留** |
| `撤销这些临时授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:72` | 标题 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `撤销后，相应请求需要重新确认；长期能力设置和 Android 系统权限不变。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:73` | 标题 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `撤销` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:78` | 按钮/操作 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:80` | 按钮/操作 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `生效中的临时授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:86` | 正文/说明 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:89` | 正文/说明 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `修改` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:90` | 正文/说明 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:91` | 正文/说明 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `剩余约 $seconds 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:98` | 正文/说明 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `撤销` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:102` | 按钮/操作 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `撤销全部` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/CapabilityTemporaryApprovals.kt:106` | 按钮/操作 | CapabilityTemporaryApprovals | 生产可达 | **保留** |
| `设备能力与授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:105` | 标题 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `更多` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:135` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `插件管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:138` | 标题 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `管理插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:139` | 标题 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `部分内建能力未能加载：` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:148` | 错误提示 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `。请重新安装应用，仍失败请反馈这两项信息。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:150` | 错误提示 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `无法读取本机授权设置，当前按关闭处理。${deviceCapabilityReasonLabel(it)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:157` | 错误提示 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `应用自带的能力文件缺失` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:190` | 错误提示 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `能力文件校验未通过` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:191` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `能力文件身份不匹配` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:192` | 正文/说明 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `当前版本不支持该能力的运行环境` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:193` | 正文/说明 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `能力未能注册` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:194` | 正文/说明 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `当前安装渠道不支持设备能力，请使用完整版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:195` | 正文/说明 | DeviceCapabilitiesScreen | 异常兜底 | **保留** |
| `加载失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:196` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `屏幕内容读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:200` | 标题 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `屏幕截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:201` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `屏幕控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:202` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `短信` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:203` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `通知读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:204` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `通话记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/capabilities/DeviceCapabilitiesScreen.kt:205` | 正文/说明 | DeviceCapabilitiesScreen | 生产可达 | **保留** |
| `网络已断开` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:37` | 错误提示 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `未检测` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:37` | 错误提示 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `可访问` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:38` | 错误提示 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `无法访问 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:39` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `安全校验阻止` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:40` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `未连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:44` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:45` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `已连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:46` | 正文/说明 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `正在重连` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:47` | 正文/说明 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `连接失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:48` | 正文/说明 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `连接与账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:69` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `当前连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:71` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `OAI Core 地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:73` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `尚未选择 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:73` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `HTTP 连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:74` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `实时事件通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:75` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `连接详情与诊断` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:76` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `Agent 管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:78` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `当前 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:82` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `未获取，连接后刷新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:82` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在检查 Agent 支持情况` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:83` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `请稍候…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:83` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `当前 Core 不支持 Agent 列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:84` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `请检查 Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:84` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `尚未发现 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:85` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `请检查 Core 上的 Agent 配置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:85` | 状态/空页面 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `在线` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:90` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `离线` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:90` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| ` · 账号隔离` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:91` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| ` · 共享实例` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:91` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| ` · 当前使用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:92` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `刷新 Agent 列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:100` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `账号管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:102` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `未登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:105` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在恢复登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:106` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `已登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:107` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `需要重新登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:108` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `当前账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:110` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `移除本机账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:121` | 按钮/操作 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `切换账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:123` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `添加账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:124` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `刷新登录凭据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:126` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `续期中…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:127` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `提前更新当前登录凭据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:127` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `退出登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:133` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `连接安全` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:136` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `传输加密` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:138` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `明文（HTTP），易被窃听` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:139` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `已加密，证书公钥一致` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:140` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `已加密，系统证书受信任` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:141` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `未建立加密连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:142` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `Core 身份验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:144` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `已核对保存的服务器身份` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:145` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `通过系统证书验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:146` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `HTTP 无法验证身份` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:147` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `未验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:148` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `设备配对` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:150` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `保留账号资料，待连接核实` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:150` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `无配对信息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:150` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `解除设备配对` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:152` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `取消手机与 Core 配对，清理当前账号本地数据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:153` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `退出当前账号？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:163` | 标题 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `将保留已存账号、配对和本地聊天记录。该账号的远程任务与通知推送将暂停。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:164` | 标题 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `退出登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:165` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:166` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `移除本机账号？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:171` | 标题 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `将移除「${profile.username}」在手机上的登录与聊天数据；服务器端数据不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:172` | 标题 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `\n先返回账号管理，再确认要移除的账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:173` | 按钮/操作 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `进入账号管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:177` | 按钮/操作 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `移除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:177` | 按钮/操作 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:178` | 按钮/操作 | ConnectionSettingsScreen | 异常兜底 | **保留** |
| `连接详情与诊断` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:184` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `连接状态与诊断` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:186` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `HTTP 连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:188` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `实时事件通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:189` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `连接阶段` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:190` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `连接正常` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:191` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `离线查看本地记录，账号未退出` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:192` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:193` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在验证账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:194` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `正在重连` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:195` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `连接失败，请检查 Core 地址、网络及账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:196` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `尚未连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:197` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `此 Core 支持的功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:200` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `设备端任务执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:203` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `连接后可查看` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:204` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `当前 Core 未开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:205` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **修改** |
| `插件服务未就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:206` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `已支持，执行需授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/connection/ConnectionSettingsScreen.kt:207` | 正文/说明 | ConnectionSettingsScreen | 生产可达 | **保留** |
| `无法打开系统通知设置，请在 Android 设置中找到本应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:40` | 错误提示 | NotificationSettingsScreen | 异常兜底 | **保留** |
| `通知与提醒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:42` | 错误提示 | NotificationSettingsScreen | 异常兜底 | **保留** |
| `App 通知偏好` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:45` | 错误提示 | NotificationSettingsScreen | 异常兜底 | **保留** |
| `Agent 任务通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:48` | 标题 | NotificationSettingsScreen | 生产可达 | **修改** |
| `显示进度与完成提醒，可直接回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:51` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `当前版本未接入任务通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:51` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `Android 系统设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:55` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `系统通知权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:57` | 按钮/操作 | NotificationSettingsScreen | 生产可达 | **保留** |
| `已允许` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:57` | 按钮/操作 | NotificationSettingsScreen | 生产可达 | **保留** |
| `系统未允许；App 偏好仍保留` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:57` | 按钮/操作 | NotificationSettingsScreen | 生产可达 | **保留** |
| `系统通知设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:58` | 按钮/操作 | NotificationSettingsScreen | 生产可达 | **保留** |
| `管理权限与通知类别` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:58` | 按钮/操作 | NotificationSettingsScreen | 生产可达 | **保留** |
| `实时任务状态权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:61` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `已允许突出显示进行中的任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:61` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `未允许或类别已关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:61` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `实时任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:64` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `需 Android 16+` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/notifications/NotificationSettingsScreen.kt:64` | 正文/说明 | NotificationSettingsScreen | 生产可达 | **保留** |
| `安全与隐私` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:37` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `插件运行安全` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:39` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `开发者信任模式` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:42` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `当前版本不支持` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:46` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `已紧急停止，重启恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:47` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `原生插件可使用应用的权限和数据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:48` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `原生插件未开启；其他插件正常受控运行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:49` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `插件运行环境` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:53` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `插件服务正常` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:53` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `未启用插件服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:53` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `安全规则与系统权限独立检查。关闭信任模式不影响已允许功能。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:55` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `使用记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:61` | 按钮/操作 | SecuritySettingsScreen | 生产可达 | **保留** |
| `授权与调用记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:63` | 按钮/操作 | SecuritySettingsScreen | 生产可达 | **保留** |
| `查看重要操作与执行结果` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:63` | 按钮/操作 | SecuritySettingsScreen | 生产可达 | **保留** |
| `紧急停止` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:65` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `紧急停止所有插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:68` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `已停止 ${uiState.emergencyStoppedCount} 个插件，重启恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:69` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `立即停用所有插件并撤销临时授权，重启恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:69` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `授权与调用记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:81` | 正文/说明 | SecuritySettingsScreen | 生产可达 | **保留** |
| `全部记录（${uiState.auditEvents.size} 条）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:87` | 状态/空页面 | SecuritySettingsScreen | 生产可达 | **保留** |
| `暂无记录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:89` | 状态/空页面 | SecuritySettingsScreen | 生产可达 | **保留** |
| `允许` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:94` | 正文/说明 | SecuritySettingsScreen | 异常兜底 | **保留** |
| `拒绝` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:95` | 正文/说明 | SecuritySettingsScreen | 异常兜底 | **保留** |
| `失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:96` | 正文/说明 | SecuritySettingsScreen | 异常兜底 | **保留** |
| `插件：${event.pluginId}\n账号：${event.accountId}\n配对：${event.pairingId}\n请求：${even…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/settings/security/SecuritySettingsScreen.kt:100` | 错误提示 | SecuritySettingsScreen | 异常兜底 | **修改** |

### 6.2 对话界面 - 工作台与时间线 (301 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `审批卡当前无法显示或提交。命令仍在等待审批，尚未执行；请手动输入以下命令之一。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:169` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `审批卡不可用，命令仍在等待` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:255` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `命令需要你的批准` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:255` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `高危操作` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:267` | 错误提示 | ApprovalCard | 异常兜底 | **保留** |
| `已超时` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:295` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `已允许这一次执行` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:421` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `已在本次会话内允许` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:422` | 错误提示 | ApprovalCard | 异常兜底 | **保留** |
| `已添加到白名单` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:423` | 错误提示 | ApprovalCard | 异常兜底 | **保留** |
| `已拒绝，命令不会执行` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:424` | 错误提示 | ApprovalCard | 异常兜底 | **保留** |
| `审批已超时，命令不会再执行` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:430` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `审批已超时，命令没有执行` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:430` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `审批已撤回，命令没有执行` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:432` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `审批结果未知，请以 Gateway 记录为准` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:433` | 正文/说明 | ApprovalCard | 生产可达 | **修改** |
| `该网关不支持审批卡片，请用 /approve 文本命令回复。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:483` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `知道了` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:492` | 按钮/操作 | ApprovalCard | 生产可达 | **保留** |
| `允许一次` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:501` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `本次会话允许` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:502` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `添加到白名单` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:503` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `拒绝` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:504` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `未知` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt:505` | 正文/说明 | ApprovalCard | 生产可达 | **保留** |
| `附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:67` | 正文/说明 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `只分享你主动选择的内容` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:69` | 正文/说明 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `关闭附件库` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:77` | 按钮/操作 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `选择图片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:92` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `从相册选择已有图片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:93` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `选择文件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:100` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `通过系统文件选择器添加文档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:101` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `拍摄照片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:107` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `使用系统相机拍摄照片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:108` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `当前待发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:117` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `${attachments.size} 个` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:117` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `还没有选择附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:136` | 正文/说明 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `选择后可在此查看上传与核验状态，再回到对话发送。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:138` | 正文/说明 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `历史媒体` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:159` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `${historical.size} 个` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:160` | 标题 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `当前对话暂无历史媒体。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLibraryScreen.kt:165` | 正文/说明 | AttachmentLibraryScreen | 生产可达 | **保留** |
| `大图预览` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:88` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `关闭大图预览` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:91` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `显示大图操作菜单` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:94` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `图片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:276` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `重试加载原图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:373` | 按钮/操作 | AttachmentLightbox | 生产可达 | **保留** |
| `关闭` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:403` | 按钮/操作 | AttachmentLightbox | 生产可达 | **保留** |
| `重试加载原图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:636` | 按钮/操作 | AttachmentLightbox | 生产可达 | **保留** |
| `正在加载原图…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:675` | 错误提示 | AttachmentLightbox | 异常兜底 | **保留** |
| `原图暂不可用$detail，当前为预览图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:680` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `原图暂不可用$detail，图片预览也无法显示` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:682` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `超过 24MB 上限` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:693` | 状态/空页面 | AttachmentLightbox | 异常兜底 | **保留** |
| `解码失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:694` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `原件不在本机` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:695` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `原件已被替换或删除` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:696` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `远端原件不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:697` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `加载失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:698` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `正在读取图片…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:704` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `原件不可用，暂时无法查看大图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:705` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `本地保存失败，请检查手机存储空间或连接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:706` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `图片预览不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:707` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `保存至本地` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:712` | 正文/说明 | AttachmentLightbox | 生产可达 | **保留** |
| `保存中…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:713` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `已保存到下载目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:714` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `保存失败，重试保存` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/AttachmentLightbox.kt:715` | 正文/说明 | AttachmentLightbox | 异常兜底 | **保留** |
| `尚未加载 Agent 命令目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:79` | 状态/空页面 | CommandMenu | 生产可达 | **修改** |
| `正在加载 Agent 命令目录…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:80` | 状态/空页面 | CommandMenu | 生产可达 | **修改** |
| `Agent 尚未提供可用命令，回车可原样发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:81` | 状态/空页面 | CommandMenu | 生产可达 | **修改** |
| `无法加载 Agent 命令目录（${catalogState.code}）` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:82` | 状态/空页面 | CommandMenu | 生产可达 | **修改** |
| `重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:184` | 按钮/操作 | CommandMenu | 生产可达 | **保留** |
| `Agent 目录中没有匹配命令，回车可原样发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/CommandMenu.kt:195` | 正文/说明 | CommandMenu | 生产可达 | **修改** |
| `添加附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:278` | 按钮/操作 | ComposerBar | 生产可达 | **保留** |
| `添加附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:299` | 按钮/操作 | ComposerBar | 生产可达 | **保留** |
| `语音输入` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:316` | 按钮/操作 | ComposerBar | 生产可达 | **保留** |
| `添加到当前对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:347` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `拍照` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:356` | 标题 | ComposerBar | 生产可达 | **保留** |
| `使用系统相机拍摄照片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:357` | 标题 | ComposerBar | 生产可达 | **保留** |
| `相册选择` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:364` | 标题 | ComposerBar | 生产可达 | **保留** |
| `从相册选择一张或多张图片` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:365` | 标题 | ComposerBar | 生产可达 | **保留** |
| `文档选择` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:372` | 标题 | ComposerBar | 生产可达 | **保留** |
| `通过系统文件选择器添加文档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:373` | 标题 | ComposerBar | 生产可达 | **保留** |
| `输入消息或 / 命令...` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:490` | 状态/空页面 | ComposerBar | 生产可达 | **保留** |
| `等待取消确认` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:569` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `停止生成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:571` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:573` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `核实附件上传结果` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:812` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `重试上传` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:812` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `移除附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:822` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `正在准备文件…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:846` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在准备上传…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:847` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在上传` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:848` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在确认上传…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:849` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `已就绪，可发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:850` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `上传失败，可重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:851` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `无法上传，请移除后重新选择` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:852` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `上传结果未确认，请核实` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:853` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `已取消上传` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:854` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在准备文件 · 已读 ${formatAttachmentSize(draft.transferredBytes)}` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:861` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在上传 · ${formatAttachmentSize(draft.transferredBytes)} / ${formatAttachmentSi…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:863` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `本机存储空间不足或暂存不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:869` | 错误提示 | ComposerBar | 异常兜底 | **保留** |
| `无法读取所选文件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:870` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `附件完整性校验失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:871` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `错误代码：${code.take(80)}` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:872` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `消息已接收，等待回复` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:877` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在接收回复` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:878` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `正在请求停止，等待 Gateway 确认` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:879` | 正文/说明 | ComposerBar | 异常兜底 | **修改** |
| `本次生成已停止` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:880` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `本次生成失败，请检查连接后重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:881` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `当前 Gateway 不支持停止生成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:882` | 正文/说明 | ComposerBar | 异常兜底 | **修改** |
| `排队等待当前任务完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:911` | 按钮/操作 | ComposerBar | 生产可达 | **保留** |
| `排队` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:912` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `引导当前任务` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:924` | 按钮/操作 | ComposerBar | 生产可达 | **保留** |
| `引导` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:925` | 无障碍辅助 | ComposerBar | 生产可达 | **保留** |
| `选择方式后点击发送。排队等待本轮结束；引导发送给当前任务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:936` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `${delivery.attachmentCount} 个附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:975` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `排队中，当前任务结束后发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:982` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `正在发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:983` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `发送失败，等待重连后重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:984` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `发送结果未知，不会自动重发` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:985` | 正文/说明 | ComposerBar | 异常兜底 | **保留** |
| `重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:991` | 按钮/操作 | ComposerBar | 异常兜底 | **保留** |
| `（空消息）` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:1030` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `${members.size} 条 · 等待合并` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ComposerBar.kt:1038` | 正文/说明 | ComposerBar | 生产可达 | **保留** |
| `搜索会话…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:260` | 输入占位/表单 | ConversationBrowser | 生产可达 | **保留** |
| `清除搜索` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:265` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `已归档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:286` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `未分类` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:287` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `全部会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:288` | 无障碍辅助 | ConversationBrowser | 生产可达 | **保留** |
| `全部会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:293` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `未分类` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:293` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `已归档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:305` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `管理文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:311` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `刷新会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:313` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `新建会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:315` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:323` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `${filtered.size} 个会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:329` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `自定义顺序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:336` | 无障碍辅助 | ConversationBrowser | 生产可达 | **保留** |
| `最近更新` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:336` | 无障碍辅助 | ConversationBrowser | 生产可达 | **保留** |
| `会话排序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:337` | 无障碍辅助 | ConversationBrowser | 生产可达 | **保留** |
| `按更新时间倒序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:340` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `自定义顺序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:343` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `此 Gateway 暂不支持会话管理，请更新 Gateway。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:351` | 正文/说明 | ConversationBrowser | 生产可达 | **修改** |
| `连接 Gateway 后可管理会话。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:352` | 正文/说明 | ConversationBrowser | 生产可达 | **修改** |
| `正在保存会话设置…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:353` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `文件夹与排序保存在本机；长按会话可拖动排序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:354` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `长按会话并拖动可调整顺序` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:355` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `历史会话加载失败，请重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:369` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:370` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `暂无匹配对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:374` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `暂无已归档对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:374` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `暂无历史对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:374` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `上移会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:408` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `下移会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:408` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `重命名对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:457` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `对话标题` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:457` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `移动到文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:464` | 标题 | ConversationBrowser | 生产可达 | **保留** |
| `未分类` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:467` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `请先在“管理文件夹”中新建文件夹。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:471` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:474` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `删除对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:478` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `确定删除“${thread.title.ifBlank { ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:478` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| ` }}”？对话记录将从 Gateway 删除。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:478` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **修改** |
| `管理文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:486` | 标题 | ConversationBrowser | 生产可达 | **保留** |
| `暂无文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:489` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `重命名文件夹 ${folder.name}` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:493` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `删除文件夹 ${folder.name}` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:494` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `新建文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:499` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:500` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `新建文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:503` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `文件夹名称` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:503` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `重命名文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:507` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `文件夹名称` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:507` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `删除文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:512` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `删除“${folder.name}”后，其中的会话将移到未分类。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:512` | 弹窗/对话框 | ConversationBrowser | 生产可达 | **保留** |
| `任务进行中` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:562` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `已归档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:563` | 状态/空页面 | ConversationBrowser | 生产可达 | **保留** |
| `未命名对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:571` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `管理会话 ${thread.title.ifBlank { ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:609` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `重命名对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:614` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `移动到文件夹` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:615` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `取消归档` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:616` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `归档对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:616` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `删除对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:618` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `当前 Agent 不支持删除对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:620` | 按钮/操作 | ConversationBrowser | 生产可达 | **修改** |
| `任务结束后可归档或删除` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:622` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `打开会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:655` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `确定` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:677` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:678` | 按钮/操作 | ConversationBrowser | 生产可达 | **保留** |
| `删除` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:685` | 标题 | ConversationBrowser | 异常兜底 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:686` | 按钮/操作 | ConversationBrowser | 异常兜底 | **保留** |
| `刚刚` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:712` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `${elapsed / MINUTE_MILLIS} 分钟前` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:713` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `${elapsed / HOUR_MILLIS} 小时前` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:714` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `${elapsed / DAY_MILLIS} 天前` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ConversationBrowser.kt:715` | 正文/说明 | ConversationBrowser | 生产可达 | **保留** |
| `不到 1 秒` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionTiming.kt:121` | 正文/说明 | ExecutionTiming | 生产可达 | **保留** |
| `$seconds 秒` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionTiming.kt:125` | 正文/说明 | ExecutionTiming | 生产可达 | **保留** |
| `$seconds.$remainder 秒` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionTiming.kt:125` | 正文/说明 | ExecutionTiming | 生产可达 | **保留** |
| `$minutes 分 $seconds 秒` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionTiming.kt:132` | 正文/说明 | ExecutionTiming | 生产可达 | **保留** |
| `此消息没有可显示的文本` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:67` | 状态/空页面 | ExecutionViews | 生产可达 | **保留** |
| `执行过程 · ${run.stepCount} 步` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:214` | 标题 | ExecutionViews | 生产可达 | **保留** |
| `执行过程 · ${run.stepCount} 步` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:216` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| `已执行 ${run.stepCount} 步` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:218` | 错误提示 | ExecutionViews | 异常兜底 | **保留** |
| ` · 含 ${run.failedStepCount} 步失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:220` | 错误提示 | ExecutionViews | 异常兜底 | **保留** |
| ` · 已中断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:221` | 错误提示 | ExecutionViews | 异常兜底 | **保留** |
| ` · 点击收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:222` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| ` · 点击展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:222` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| ` · $unknownSteps 步结果未知` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:223` | 标题 | ExecutionViews | 异常兜底 | **保留** |
| ` · 执行中` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:224` | 标题 | ExecutionViews | 异常兜底 | **保留** |
| `收起执行过程` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:242` | 按钮/操作 | ExecutionViews | 生产可达 | **保留** |
| `展开执行过程` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:242` | 按钮/操作 | ExecutionViews | 生产可达 | **保留** |
| `执行过程，${run.stepCount} 步$unknownSuffix$runningSuffix，${if (expanded) ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:249` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| `执行过程，已执行 ${run.stepCount} 步，${if (expanded) ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:251` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| `已展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:253` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| `已收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:253` | 正文/说明 | ExecutionViews | 生产可达 | **保留** |
| `执行过程包含失败步骤` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:263` | 错误提示 | ExecutionViews | 异常兜底 | **保留** |
| `执行过程中断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt:264` | 错误提示 | ExecutionViews | 异常兜底 | **保留** |
| `浮动对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:126` | 标题 | FloatingConversationPanel | 生产可达 | **保留** |
| `浮动对话仅在此 App 内显示` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:165` | 正文/说明 | FloatingConversationPanel | 生产可达 | **保留** |
| `与当前 Gateway 继续对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:250` | 正文/说明 | FloatingConversationPanel | 生产可达 | **修改** |
| `新建对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:255` | 按钮/操作 | FloatingConversationPanel | 生产可达 | **保留** |
| `此消息没有可显示的文本` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:191` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `等待 Agent 接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:255` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `Agent 已接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:256` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `Agent 处理完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:257` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `Agent 处理失败：` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:258` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `Agent 暂不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:259` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `消息内容或附件参数无效` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:260` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `当前 Agent 不支持此消息或附件功能` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:261` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `会话协议响应无效，请更新 Core 与适配器` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:262` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `请求未能完成，请检查会话连接或服务日志` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:263` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `Agent 读取附件失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:264` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `Agent 无法处理此媒体` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:265` | 错误提示 | MessageTimeline | 异常兜底 | **修改** |
| `模型请求格式不接受` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:266` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `审批卡不可用，命令尚未执行；请回复 /approve 或 /deny` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:268` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `请重试或重新选择附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:269` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `语音消息` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:394` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `语音附件：$name` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:401` | 无障碍辅助 | MessageTimeline | 生产可达 | **保留** |
| `正在读取语音…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:438` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `原件不可用，附件信息仍保留` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:439` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `本地保存失败，请检查手机存储空间或连接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:440` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `已保存到下载目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:444` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `下载失败，请重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:445` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `下载中…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:452` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `下载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:452` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `图片附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:499` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `查看大图：${attachment.filename.ifBlank { ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:510` | 按钮/操作 | MessageTimeline | 生产可达 | **保留** |
| `查看大图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:511` | 按钮/操作 | MessageTimeline | 生产可达 | **保留** |
| `正在读取图片…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:543` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `原件不可用，暂时无法查看大图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:548` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `本地保存失败，请检查手机存储空间或连接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:553` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `已保存到下载目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:564` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `下载失败，请重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:570` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `下载中…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:588` | 正文/说明 | MessageTimeline | 异常兜底 | **保留** |
| `已保存` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:589` | 正文/说明 | MessageTimeline | 异常兜底 | **保留** |
| `重试下载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:590` | 正文/说明 | MessageTimeline | 异常兜底 | **保留** |
| `下载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:591` | 正文/说明 | MessageTimeline | 异常兜底 | **保留** |
| `查看大图` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:630` | 按钮/操作 | MessageTimeline | 生产可达 | **保留** |
| `查看大图：${attachment.filename.ifBlank { ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:632` | 无障碍辅助 | MessageTimeline | 生产可达 | **保留** |
| `正在加载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:633` | 无障碍辅助 | MessageTimeline | 生产可达 | **保留** |
| `正在加载图片预览` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:637` | 无障碍辅助 | MessageTimeline | 生产可达 | **保留** |
| `图片预览不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:637` | 无障碍辅助 | MessageTimeline | 生产可达 | **保留** |
| `原件不可用，暂时无法查看` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:659` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `图片预览不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:661` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `附件文件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:679` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `正在保存到本机…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:681` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `原件不可用，附件信息仍保留` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:682` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `本地保存失败，请检查手机存储空间或连接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:683` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `已保存到下载目录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:687` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `下载失败，请重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:688` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `下载中…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:695` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `下载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:695` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `复制成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:944` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:944` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `发送失败 · ${entry.submissionFailureCode}` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:966` | 错误提示 | MessageTimeline | 异常兜底 | **保留** |
| `等待合并` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:974` | 状态/空页面 | MessageTimeline | 生产可达 | **保留** |
| `等待 Gateway 接收` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:974` | 状态/空页面 | MessageTimeline | 生产可达 | **修改** |
| `正在输出…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:975` | 状态/空页面 | MessageTimeline | 生产可达 | **保留** |
| `正在思考` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:1072` | 正文/说明 | MessageTimeline | 生产可达 | **保留** |
| `附件库` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:127` | 按钮/操作 | ThreadDrawer | 生产可达 | **保留** |
| `设置` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:156` | 正文/说明 | ThreadDrawer | 生产可达 | **保留** |
| `退出登录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:175` | 错误提示 | ThreadDrawer | 异常兜底 | **保留** |
| `关闭抽屉` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:234` | 无障碍辅助 | ThreadDrawer | 生产可达 | **保留** |
| `更换账号头像` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:278` | 按钮/操作 | ThreadDrawer | 生产可达 | **保留** |
| `账号头像` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:278` | 按钮/操作 | ThreadDrawer | 生产可达 | **保留** |
| `离线` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:350` | 状态/空页面 | ThreadDrawer | 生产可达 | **保留** |
| `在线` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt:351` | 正文/说明 | ThreadDrawer | 生产可达 | **保留** |
| `回到最新` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/TimelineScaffold.kt:169` | 正文/说明 | TimelineScaffold | 生产可达 | **保留** |
| `实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:252` | Toast/提示 | WorkbenchScreen | 生产可达 | **修改** |
| `发送消息开始对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:509` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `重命名对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:863` | 标题 | WorkbenchScreen | 生产可达 | **保留** |
| `对话标题` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:869` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `确定` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:883` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:888` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `新对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:933` | 标题 | WorkbenchScreen | 生产可达 | **保留** |
| `打开会话抽屉` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:951` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `新建对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:963` | 无障碍辅助 | WorkbenchScreen | 生产可达 | **保留** |
| `更多操作` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:970` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `重命名对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:977` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `附件库` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:986` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `浮动助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:995` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `设置` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1004` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |
| `有什么想聊的？` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1076` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `随时向 Agent 提问，或发送图片与文件。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1078` | 正文/说明 | WorkbenchScreen | 生产可达 | **修改** |
| `新建对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1090` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `输入 / 可查看此 Gateway 提供的命令。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1108` | 正文/说明 | WorkbenchScreen | 生产可达 | **修改** |
| `正在创建对话…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1192` | 正文/说明 | WorkbenchScreen | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:1197` | 按钮/操作 | WorkbenchScreen | 生产可达 | **保留** |

### 6.3 插件与能力扩展 (198 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `通知读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:33` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `短信读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:39` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `通话记录读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:45` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `屏幕读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:51` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `屏幕截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:57` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `屏幕控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/BundledPlugins.kt:63` | 正文/说明 | BundledPlugins | 生产可达 | **保留** |
| `能力已开启，按下方模式处理请求` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:45` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `能力已关闭，授权模式仍会保留` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:45` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `${capabilityAuthorizationTitle(capability)}总开关` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:54` | 标题 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `此能力仅提供读取；「允许只读」和「自动允许」不会产生额外权限差异。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:93` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `设置保存在本地。系统权限与确认提示仍需单独允许。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:100` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `每次手动` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:108` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `允许只读` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:109` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `自动允许` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:110` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `每次请求需手动确认，可选择临时允许。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:114` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `已授权范围内的读取自动执行；发送、修改和控制仍需确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:115` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `在本机允许范围内自动执行，仍须满足系统权限和必要的安全确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:116` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `屏幕内容读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:120` | 标题 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `屏幕截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:121` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `屏幕控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:122` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `短信读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:123` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `短信定时任务设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:124` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `短信任务状态查询` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:125` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `短信任务取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:126` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `通知读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:127` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `通话记录读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:128` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `设备能力授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityAuthorizationControls.kt:129` | 正文/说明 | CapabilityAuthorizationControls | 生产可达 | **保留** |
| `可在「系统权限与后台任务」中申请对应权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:113` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `需要在系统设置中手动开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:114` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `通知监听服务尚未连接，请在系统设置中确认本应用的通知使用权已开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:116` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `无障碍服务已开启但当前未连接，请回到无障碍设置确认服务已连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:117` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `上次授权被系统拒绝，请重新发起一次授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:119` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `系统暂时限速，稍等片刻重试即可` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:120` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `该能力已关闭，开启对应开关即可恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:121` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `安装或启用对应插件即可恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:122` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `在插件条目里选择由哪个插件提供这项能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:123` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `正在同步本机能力设置，稍候会自动更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:124` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `当前设备或系统版本不支持这项能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityBlock.kt:125` | 正文/说明 | DeviceCapabilityBlock | 生产可达 | **保留** |
| `短信读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:75` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `读取本机短信内容供 Agent 查询。不授权时短信能力不可用，其他功能不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:78` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `通话记录读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:86` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `读取本机通话记录供 Agent 查询。不授权时通话记录能力不可用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:89` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `通知使用权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:97` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `读取新到通知的内容供 Agent 处理。需要先在系统「通知使用权」里允许本应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:99` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `无障碍服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:114` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `读取屏幕内容、执行点按等操作，并在开启后按需截取屏幕画面。必须在系统无障碍设置里开启本应用的服务。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:117` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `读取屏幕内容并执行点按等操作。必须在系统无障碍设置里开启本应用的服务。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:119` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `屏幕共享（备用）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:128` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `屏幕共享` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:128` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `仅当无障碍截图不可用、且你明确同意时才需要。每次屏幕共享会话都要单独确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:131` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `当前系统不支持无障碍截图，只能通过系统屏幕共享授权截图。每次会话都要单独确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:133` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `无法自动打开无障碍设置。请手动进入：系统设置 → 无障碍 → 已下载的服务 / 已安装的应用 → 找到本应用 → 开启，并确认服务显示「已连接」。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:265` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `无法自动打开通知使用权设置。请手动进入：系统设置 → 通知 → 通知使用权（或「设备和应用通知」→ 特殊访问权限）→ 找到本应用 → 允许。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:267` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `屏幕共享需要一次系统授权。请在应用内重新点击「屏幕共享」发起；若系统未提供录屏服务，无障碍截图仍是可用路径。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:269` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `无法自动申请该权限。请手动进入：系统设置 → 应用 → 本应用 → 权限 → 允许${requirement.label}。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilityPermissions.kt:271` | 正文/说明 | DeviceCapabilityPermissions | 生产可达 | **保留** |
| `设备操作确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:645` | 系统通知 | DeviceExecutionDriver | 生产可达 | **保留** |
| `设备操作需要确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:650` | 标题 | DeviceExecutionDriver | 生产可达 | **保留** |
| `正在显示原生插件界面` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/NativePluginSurface.kt:70` | 正文/说明 | NativePluginSurface | 生产可达 | **保留** |
| `该界面由已信任的原生插件渲染，与宿主运行在同一进程、共享同一 UID，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/NativePluginSurface.kt:75` | 正文/说明 | NativePluginSurface | 生产可达 | **保留** |
| `因此拥有与本应用相同的系统权限。请只在插件来源可信、且已开启开发者信任模式时使用；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/NativePluginSurface.kt:76` | 正文/说明 | NativePluginSurface | 生产可达 | **保留** |
| `随时可以返回宿主界面，插件设置与已装数据不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/NativePluginSurface.kt:77` | 正文/说明 | NativePluginSurface | 生产可达 | **保留** |
| `恢复宿主界面` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/NativePluginSurface.kt:90` | 正文/说明 | NativePluginSurface | 生产可达 | **保留** |
| `当前版本暂不支持插件运行，这里的设置无法操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:92` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `已安装 ${view.pluginId} v${view.version}；启用插件后，设备请求仍按各项能力的本机授权模式处理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:156` | Toast/提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `更新扩大了权限或资源范围，需要确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:160` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `无法打开能力设置，请从设备能力与授权中配置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:184` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `插件请求的能力未声明，无法打开能力设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:198` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `这个插件没有可配置的能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:200` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `已提交 $action（$component）——$KERNEL_ABSENT_NOTICE` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:204` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `设置已保存到当前账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:216` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `保存失败：请先登录 Gateway。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:216` | Toast/提示 | PluginManagementScreen | 生产可达 | **修改** |
| `确认插件更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:220` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `该更新扩大了系统调用、联网、后台或资源范围。批准后，本机能力开关、系统权限和后台设置仍分别生效；作者和签名将再次核验。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:221` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `批准更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:222` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:223` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `卸载损坏包` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:228` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `将删除插件目录 $directoryName 的损坏包及其本机设置。重新安装后需要重新启用插件，并重新检查系统权限与通知读取范围。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:229` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `损坏插件包已卸载，请重新安装并启用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:237` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `损坏插件包未能卸载，请检查本机存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:238` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `确认卸载` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:239` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:241` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `确认卸载插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:247` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `将停用并卸载插件 $id，清理其插件私有数据、已保存的插件专属授权和后台任务。重新安装后需要重新启用并检查相关权限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:248` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `已卸载并清理插件数据。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:253` | Toast/提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `卸载未完成，请检查存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:254` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `确认卸载` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:255` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:257` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `权限与能力设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:264` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `这个插件没有可配置的能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:270` | 状态/空页面 | PluginManagementScreen | 生产可达 | **保留** |
| `风险：${entry.risk}；需要：${entry.primitives.joinToString()}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:277` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:287` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `调用插件能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:291` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `能力 ID@版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:298` | 输入占位/表单 | PluginManagementScreen | 生产可达 | **保留** |
| `JSON 参数` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:299` | 输入占位/表单 | PluginManagementScreen | 生产可达 | **保留** |
| `调用被拒绝或失败，请检查设备连接、本机能力开关、系统权限和参数。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:311` | 按钮/操作 | PluginManagementScreen | 异常兜底 | **保留** |
| `执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:313` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:313` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `从网络地址安装` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:330` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `需要提供服务器证书的公钥指纹（SPKI，不是证书本身指纹）或文件摘要；下载后仍会校验插件作者签名与内容。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:332` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `插件包或索引的 HTTPS 地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:336` | 输入占位/表单 | PluginManagementScreen | 生产可达 | **保留** |
| `证书公钥指纹 SPKI（sha256:…）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:337` | 输入占位/表单 | PluginManagementScreen | 生产可达 | **保留** |
| `文件摘要 SHA-256（可代替公钥指纹）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:338` | 输入占位/表单 | PluginManagementScreen | 生产可达 | **保留** |
| `清单中的插件 ID（直接下载插件包时留空）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:339` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:341` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `下载失败，请检查地址、公钥指纹（SPKI）或文件摘要。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:345` | Toast/提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `下载并校验` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:346` | Toast/提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `插件管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:373` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `插件运行环境` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:384` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `插件可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:387` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `当前版本暂不支持运行插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:387` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `已安装并启用的插件，只有在本机能力开关、应用声明和系统权限均满足时才会执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:389` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `已安装插件不会运行，「启用」不可用；安装仍会校验并保管包内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:391` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `撤销临时授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:400` | 错误提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `无法读取插件列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:403` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `无法确认已安装的插件，请检查手机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:403` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `已安装插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:408` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `未安装任何插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:414` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `卸载损坏包` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:431` | 错误提示 | PluginManagementScreen | 异常兜底 | **保留** |
| `设置此项能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:447` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `允许后台任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:453` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `已在本机明确允许后台任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:454` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `普通查询无需此许可；定时/后台操作仍受系统调度和当前本地授权约束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:454` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `已明确允许此插件执行后台任务。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:458` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `已在本机关闭此插件的后台任务许可。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:458` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `后台任务许可未能保存，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:459` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `插件状态已更新。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:467` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `启用失败：请检查开发者信任模式、插件兼容性或安全限制。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:467` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `停用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:467` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:467` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `系统权限与后台任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:469` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `已切换为该插件自带的界面，随时可以切回来。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:471` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `需要先开启开发者信任模式并启用插件。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:471` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `使用插件界面` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:471` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `解除安全限制（保持停用）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:472` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `通知读取范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:475` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `卸载` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:489` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `安装新插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:500` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `从文件安装插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:504` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `选择一个插件包文件，应用会先校验它的签名与内容再安装` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:505` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `从网络地址安装` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:510` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `需要提供服务器证书公钥指纹或文件摘要，安装前仍会校验插件作者签名` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:510` | 按钮/操作 | PluginManagementScreen | 生产可达 | **保留** |
| `从文件安装插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:513` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `当前版本不允许安装插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:514` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `服务端报告已就绪；本机权限与数据范围仍单独检查` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:527` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `服务端仍使用旧账号能力策略，请升级 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:528` | 正文/说明 | PluginManagementScreen | 生产可达 | **修改** |
| `服务端尚不支持 Android 统一授权，请升级 OAI Core；当前设备能力不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:529` | 正文/说明 | PluginManagementScreen | 生产可达 | **修改** |
| `Android 未安装对应插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:530` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `Android 插件未启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:531` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `本机能力尚未开启或需要重新同步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:532` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `需要 Android 系统权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:533` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `本机采集器或监听服务未就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:534` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `尚未选择此能力提供者` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:535` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `正在同步本机能力设置，尚未确认服务端状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:536` | 正文/说明 | PluginManagementScreen | 异常兜底 | **保留** |
| `正在重试同步，尚未确认当前能力状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:537` | 正文/说明 | PluginManagementScreen | 异常兜底 | **保留** |
| `设备能力同步失败，正在重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:538` | 正文/说明 | PluginManagementScreen | 异常兜底 | **保留** |
| `Android 版本未提供详细授权状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:539` | 正文/说明 | PluginManagementScreen | 异常兜底 | **保留** |
| `Gateway 未提供能力状态；请检查连接与 OAI Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:540` | 正文/说明 | PluginManagementScreen | 生产可达 | **修改** |
| `等待同步当前设备能力状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:541` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `尚未申请授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:562` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `等待你在系统里操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:563` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `服务未连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:564` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `系统拒绝了授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:565` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `系统暂时限速` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:566` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `本机能力开关已关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:567` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `插件未安装或未启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:568` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `尚未选择提供者` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:569` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `等待同步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:570` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `当前不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:571` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `（这一项不能在本机恢复）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:574` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `这个插件的配置文件无法读取，已按未知插件列出` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:606` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| ` · 已安装，未启用（当前版本暂不支持运行插件）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:613` | 错误提示 | PluginManagementScreen | 异常兜底 | **保留** |
| ` · 载入失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:614` | 错误提示 | PluginManagementScreen | 异常兜底 | **保留** |
| ` · 已启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:615` | 错误提示 | PluginManagementScreen | 异常兜底 | **保留** |
| ` · 已停用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:616` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `这个插件无法载入` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:623` | 标题 | PluginManagementScreen | 异常兜底 | **保留** |
| `请先卸载它，再重新安装并检查本机能力开关和所需系统权限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:623` | 标题 | PluginManagementScreen | 异常兜底 | **保留** |
| `插件设置无法显示` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:650` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `这个插件提供的设置项无法被安全显示：$code` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:651` | 标题 | PluginManagementScreen | 生产可达 | **保留** |
| `当前安装包未附带${cause.plugin.displayName}插件，请从文件导入已签名的 .alp 插件包。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:724` | Toast/提示 | PluginManagementScreen | 生产可达 | **保留** |
| `插件包校验未通过：${cause.message ?: ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:725` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `安装被拒绝：${cause.message ?: cause::class.java.simpleName}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:726` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `安装失败：无法读取或处理所选插件包。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:727` | 正文/说明 | PluginManagementScreen | 生产可达 | **保留** |
| `设置未保存，请检查当前插件授权。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:33` | 错误提示 | ProtectedConversationCards | 异常兜底 | **保留** |
| `确认设备能力调用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:39` | 按钮/操作 | ProtectedConversationCards | 生产可达 | **保留** |
| `当前选择：$capability` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:42` | 按钮/操作 | ProtectedConversationCards | 生产可达 | **保留** |
| `参数 JSON` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:43` | 按钮/操作 | ProtectedConversationCards | 生产可达 | **修改** |
| `参数 JSON 无效` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:47` | 正文/说明 | ProtectedConversationCards | 生产可达 | **修改** |
| `调用未完成，请检查当前授权、系统权限和参数。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:51` | 按钮/操作 | ProtectedConversationCards | 异常兜底 | **保留** |
| `执行中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:52` | 按钮/操作 | ProtectedConversationCards | 异常兜底 | **保留** |
| `执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:52` | 按钮/操作 | ProtectedConversationCards | 异常兜底 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProtectedConversationCards.kt:52` | 按钮/操作 | ProtectedConversationCards | 异常兜底 | **保留** |
| `短信被动查询范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:95` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `仅限制本能力的短信读取。主动推送的范围和接收目标另行配置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:97` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `包含短信正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:113` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `允许读取短信正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:115` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `仅允许读取发送方、时间等信息，不包含正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:115` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `包含短信正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:124` | 无障碍辅助 | SmsPassiveScopeSettings | 生产可达 | **保留** |
| `保存被动查询范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/SmsPassiveScopeSettings.kt:138` | 正文/说明 | SmsPassiveScopeSettings | 生产可达 | **保留** |

### 6.4 屏幕控制与授权 (195 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `获取屏幕截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:10` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取屏幕内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:11` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取短信` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:12` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `设置短信后台同步任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:13` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `查询短信任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:14` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `取消短信后台同步任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:15` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:16` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取通话记录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:17` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `设备操作：${operation.take(160).ifBlank { ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:18` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `参数无法解析，请核对技术详情；授权范围以系统实际执行检查为准。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:24` | 状态/空页面 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `未指定额外筛选参数；实际访问范围仍受本机设置限制。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:25` | 状态/空页面 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `应用来源` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:28` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `目标应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:29` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `短信发送方` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:30` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `起始时间` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:31` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `结束时间` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:32` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `最多读取条数` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:33` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `查询条件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:34` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取字段` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:35` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `请求范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:36` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `屏幕动作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:37` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `操作类型` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:38` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `横坐标` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:39` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `纵坐标` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:40` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `动作时长（毫秒）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:41` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `目标控件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:42` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `屏幕状态校验` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:43` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `输入内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:44` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:48` | 状态/空页面 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `已提供${(value as? JsonValue.JString)?.value?.let { ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:49` | 状态/空页面 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| ` }.orEmpty()}，可在技术详情核对` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:49` | 状态/空页面 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `…（完整内容见技术详情）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:51` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `另有 ${fields.size - 24} 项参数，请查看技术详情。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:56` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `可能点击、输入文字或切换应用，改变手机当前状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:61` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `读取内容可能包含屏幕上的个人信息；截图许可不会授予屏幕控制权限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:63` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `可能涉及正文、来源、号码和时间等敏感信息；仅按实际能力和本机允许范围执行。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:65` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `可能读取或改变设备数据，请核对操作及访问范围。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/DeviceAuthorizationCopy.kt:66` | 正文/说明 | DeviceAuthorizationCopy | 生产可达 | **保留** |
| `当前版本未接入屏幕能力，暂时不能开始` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:102` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `还没有连接到已配对的设备，请先登录并配对` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:104` | 状态/空页面 | ScreenCapabilityController | 生产可达 | **保留** |
| `请至少选择一项权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:105` | 状态/空页面 | ScreenCapabilityController | 生产可达 | **保留** |
| `现在无法确定要操作哪个界面，请先开启无障碍服务并保持目标应用在前台` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:110` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `会话有效期不合法，请重新选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:121` | 错误提示 | ScreenCapabilityController | 异常兜底 | **保留** |
| `开始屏幕会话失败，请稍后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:125` | 错误提示 | ScreenCapabilityController | 异常兜底 | **保留** |
| `开始了新的屏幕会话，上一个已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:173` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `你已主动结束屏幕会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:174` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `屏幕会话已到期，需要重新确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:175` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `账号或配对已变化，屏幕会话已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:176` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `与设备的连接已断开，屏幕会话已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:177` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `已切换账号，屏幕会话已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilityController.kt:178` | 正文/说明 | ScreenCapabilityController | 生产可达 | **保留** |
| `5 分钟` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:50` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `10 分钟` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:51` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `30 分钟` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:52` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `开启无障碍服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:90` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `系统投屏授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:98` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `结束屏幕会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:114` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `撤销屏幕权限并停止正在进行的操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:115` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `屏幕会话已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:120` | Toast/提示 | ScreenCapabilitySection | 生产可达 | **保留** |
| `开始屏幕会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:125` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `选择屏幕权限后方可开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:126` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `屏幕会话已开始，你可以在有效期内使用屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:149` | Toast/提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `允许屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:181` | 标题 | ScreenCapabilitySection | 生产可达 | **保留** |
| `这些许可只保存在这台手机上，随时可以结束。进程重启后需要重新确认。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:188` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `需要先在系统设置中开启无障碍服务，开启后才能选择这一项` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:213` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `有效期` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:222` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `开始屏幕会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:256` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:259` | 按钮/操作 | ScreenCapabilitySection | 生产可达 | **保留** |
| `当前版本未接入屏幕能力，这里不会有可以生效的开关。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:333` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `还没有连接到已配对的设备。屏幕能力需要在登录并配对之后才能授予。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:335` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `屏幕会话进行中（$remaining）。结束会话会立即撤销所有已授予的屏幕权限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:338` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `屏幕能力默认关闭。开始会话后，Agent 才能按你选择的权限读取或操作屏幕。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:340` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `当前版本未接入屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:345` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未连接设备，暂不可授予` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:346` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `已允许。${screenPermissionDescription(permission)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:350` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `已允许，但当前不可用：${readableScreenError(error)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:352` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `未允许。读取需要无障碍服务，服务已就绪，可在开始会话时选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:358` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未允许。读取需要先开启无障碍服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:360` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未允许。开始包含截图权限的会话后，再完成一次系统投屏授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:363` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未允许。开始包含截图权限的会话后，再完成一次系统投屏授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:365` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未允许。需要在开始会话时单独勾选，并且每次操作仍会单独确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:368` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `未允许。需要先开启无障碍服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:370` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `当前版本未接入屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:377` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `无障碍服务已连接，可以读取界面结构和执行操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:378` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `尚未连接：${readableScreenError(state.accessibilityError)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:379` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `当前版本未接入屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:384` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `需要先开始一个包含截图权限的屏幕会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:385` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `已开启无障碍服务，截图按需直接进行，不需要重复授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:388` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `已获得系统投屏授权，可以截图` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:389` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `尚未授权：${readableScreenError(state.captureError)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:390` | 错误提示 | ScreenCapabilitySection | 异常兜底 | **保留** |
| `截图会让你在开始后再确认一次系统投屏弹窗；截图像素不直接等于控制坐标。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:396` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `系统未提供无障碍设置入口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:422` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `系统未提供无障碍设置入口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenCapabilitySection.kt:428` | 正文/说明 | ScreenCapabilitySection | 生产可达 | **保留** |
| `确认设备请求` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:66` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `用途：${purpose.ifBlank { ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:72` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `上述信息由请求方声明，仅供审计记录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:74` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `操作类型：${operation.ifBlank { ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:80` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `本次参数与范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:81` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `临时允许仅适用于当前操作与数据范围，系统权限仍需单独满足。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:89` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `请求已过期，不能继续允许。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:94` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `剩余确认时间：${(expiresAtMillis - now + 999L) / 1_000L} 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:94` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `隐藏技术详情` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:101` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `查看技术详情` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:101` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `插件：$pluginId\n能力：$capability\n参数：$parameters` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:104` | 正文/说明 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `仅允许本次` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:119` | 按钮/操作 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `允许 1 小时` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:119` | 按钮/操作 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `允许 $duration 分钟` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:119` | 按钮/操作 | ScreenControlConfirmationDialog | 生产可达 | **保留** |
| `拒绝` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlConfirmationDialog.kt:123` | 错误提示 | ScreenControlConfirmationDialog | 异常兜底 | **保留** |
| `点击界面上的控件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:44` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `在屏幕坐标处点击一下` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:44` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `长按界面上的控件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:45` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `在屏幕坐标处长按` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:45` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `在输入框中填写文字` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:46` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `滚动界面内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:47` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `在屏幕上滑动` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:48` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `在屏幕上拖动` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:49` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `执行返回操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:51` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `回到主屏幕` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:52` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `打开最近任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:53` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `下拉打开通知栏` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:54` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `打开快捷设置面板` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:55` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `执行一个系统操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:56` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `打开应用 $it` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:58` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `打开一个应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:58` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `读取屏幕内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:59` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `执行屏幕操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:59` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `执行屏幕操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:60` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `发送短信` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:76` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `拨打电话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:77` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `写入日历` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:78` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `创建或修改闹钟` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:79` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `写入剪贴板` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:80` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `发送系统通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:81` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `读取设备数据` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:82` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `执行设备操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenControlCopy.kt:83` | 正文/说明 | ScreenControlCopy | 生产可达 | **保留** |
| `屏幕会话已失效，请重新确认要允许的屏幕权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:16` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前会话缺少所需权限，请重新配置屏幕权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:18` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `未连接配对设备，请先完成登录与配对` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:20` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `此操作需要你在手机上确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:22` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `操作已超时，请重新发起` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:24` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `无障碍服务还没有开启，请在系统设置中启用后返回` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:28` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `设备已锁屏，解锁后才能读取或操作屏幕` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:30` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `现在没有可以读取的窗口，请确认目标应用已打开` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:32` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `暂时无法获取屏幕信息，请稍后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:34` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `目标控件当前不接受操作，可能是页面还在变化` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:36` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `截图还没有可用路径：请开启无障碍服务（推荐，之后不再重复确认），或完成一次系统投屏授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:40` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `无障碍服务已开启但当前未连接，请到系统无障碍设置确认本应用的服务显示「已连接」` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:44` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `系统拒绝了这次截图授权，请重新发起一次授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:46` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前界面受系统保护（如密码、支付页面），系统不允许截取；请切换到普通界面再试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:48` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `系统对截图做了频率限制，稍等片刻再试即可，不需要重新授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:50` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前系统版本或无障碍服务配置不支持直接截图，只能使用系统投屏授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:52` | 正文/说明 | ScreenErrorMessages | 异常兜底 | **保留** |
| `截图失败，请稍后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:54` | 正文/说明 | ScreenErrorMessages | 异常兜底 | **保留** |
| `截图处理失败，请尝试降低截图宽度` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:56` | 正文/说明 | ScreenErrorMessages | 异常兜底 | **保留** |
| `截图数量或体积超出上限，请稍后再试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:58` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `这张截图已过期或已释放，请重新截取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:60` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `屏幕内容已经变化，请重新读取后重新确认要操作的位置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:64` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `目标窗口已经变化，请重新读取屏幕再操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:66` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `要操作的控件已经不在当前界面上，请重新读取屏幕` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:68` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `这个控件不能输入文字` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:70` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `这个控件不能滚动` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:72` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `坐标超出当前屏幕范围，请重新选取位置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:76` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `系统拒绝了这次操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:78` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `没有找到要打开的应用，请确认它已安装` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:80` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `系统不允许在后台启动这个应用，请先回到前台再试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:82` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `操作结果无法确认，请先重新读取屏幕再决定下一步，不要直接重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:84` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前版本未接入屏幕能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:86` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `上一个屏幕请求还在进行，请稍候` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:88` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `屏幕内容超出单次返回上限，请缩小范围后再读` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:90` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `屏幕请求的执行环境不可用，请稍后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:92` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `这次操作需要在手机上的确认，未确认所以没有执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:96` | 正文/说明 | ScreenErrorMessages | 生产可达 | **修改** |
| `本机授权不足，这次操作没有被执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:98` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前账号的授权已被撤销，这次操作没有执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:100` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前条件不满足，这次操作没有被执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:104` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `请求参数不合法，这次操作没有被执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:106` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `当前条件不满足，这次操作没有被执行` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:108` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `这次操作没有完成，请稍后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:109` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `读取其他应用的界面结构：文字、控件位置和窗口信息，不包含密码框内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:120` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `取得屏幕画面。需要单独的系统投屏授权，不会因为允许读取而自动获得` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:122` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `点击、输入、滑动、返回等操作。每次操作仍需你在手机上单独确认` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:124` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `读取屏幕内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:130` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `截取屏幕画面` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:131` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `操作其他应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:132` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `已到期` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:137` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `剩余 $minutes 分 $seconds 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:141` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `剩余 $seconds 秒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/ScreenErrorMessages.kt:141` | 正文/说明 | ScreenErrorMessages | 生产可达 | **保留** |
| `参数按本次批准的完整请求精确匹配` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:8` | 状态/空页面 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `未指定额外筛选；仍受本机保存的数据范围限制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:9` | 状态/空页面 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `应用来源：${(value as? JsonValue.JArray)?.items?.size ?: 0} 项已批准条件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:12` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `目标应用：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:13` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `电话号码：已指定筛选条件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:14` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `查询或输入内容：已指定（不在设置中保留正文）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:15` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `时间范围：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:16` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `读取数量：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:17` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `操作参数：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:18` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `目标界面与控件：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:19` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `读取字段：已指定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:20` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |
| `另有一项批准参数，其值仅用于精确校验` | `app/src/main/kotlin/com/openandroidintelligence/mobile/screen/TemporaryApprovalCopy.kt:21` | 正文/说明 | TemporaryApprovalCopy | 生产可达 | **保留** |

### 6.5 初始化引导流程 (124 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `未检测到已设置。请在系统设置中选择 OAI 作为数字助理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:129` | Toast/提示 | OnboardingFlow | 生产可达 | **保留** |
| `系统未提供可用的数字助理设置页，可稍后在系统设置中手动选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:220` | Toast/提示 | OnboardingFlow | 生产可达 | **保留** |
| `连接你的 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/OnboardingFlow.kt:461` | 正文/说明 | OnboardingFlow | 生产可达 | **修改** |
| `基础功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:11` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `可选增强` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:12` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `设备能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:13` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:79` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `接收 Agent 回复、生成完成与审批提醒。关闭后仍可使用，但需要保持应用在前台。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:80` | 正文/说明 | PermissionCatalog | 生产可达 | **修改** |
| `麦克风` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:98` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `发送语音消息。仅在按下录音时采集，音频直接发往你的 Agent，不进行转录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:99` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `屏幕采集` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:109` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `圈选屏幕内容发送给 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:110` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `默认数字助理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:121` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `把这台设备上的数字助理设为 OAI。设置后可用系统 Assist 手势在任何应用中唤起助手；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:122` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `不设置时仍可在应用内正常对话，只是没有跨应用入口。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:123` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `短信读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:138` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `用于让 Agent 查询短信验证码与消息通知。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:139` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `通话与电话状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:149` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `用于让 Agent 查询通话记录或检测来电状态，协助进行电话管理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:150` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `通知使用权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:162` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `用于让 Agent 查询系统其它应用通知与提醒，需在系统设置中手动开启。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:163` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `权限 ≠ 授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:308` | 正文/说明 | PermissionCatalog | 生产可达 | **修改** |
| `以下权限只影响对应功能。可选权限跳过不影响登录与对话；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:310` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `设备能力由本机决定：插件启用后默认开启，仍需满足系统权限和本地范围设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:311` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `设备能力由 Android 本机决定，可在插件管理中关闭；已关闭的设置会保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:313` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `旧设置需先在本机复核。系统权限、通知读取范围、屏幕许可、后台许可和敏感操作确认仍分别检查，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:314` | 正文/说明 | PermissionCatalog | 生产可达 | **保留** |
| `不需要再为 Gateway 账号单独开启设备能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt:315` | 正文/说明 | PermissionCatalog | 生产可达 | **修改** |
| `返回` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/OnboardingStepHeader.kt:58` | 按钮/操作 | OnboardingStepHeader | 生产可达 | **保留** |
| `已复制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/TerminalBlockView.kt:183` | 正文/说明 | TerminalBlockView | 生产可达 | **保留** |
| `复制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/TerminalBlockView.kt:183` | 正文/说明 | TerminalBlockView | 生产可达 | **保留** |
| `已复制命令` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/TerminalBlockView.kt:195` | 按钮/操作 | TerminalBlockView | 生产可达 | **保留** |
| `复制命令` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/components/TerminalBlockView.kt:195` | 按钮/操作 | TerminalBlockView | 生产可达 | **保留** |
| `一个服务端地址连接手机，统一管理设备调用与已接入 Agent 的会话。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:76` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `安装 OAI Core 与 CLI` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:81` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `在服务端主机创建独立 Python 环境。Core、CLI 与 MCP 来自同一个项目。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:82` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `Terminal · 安装 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:84` | 正文/说明 | GatewayTutorials | 生产可达 | **修改** |
| `配置一个手机可访问的地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:92` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `编辑 oai-core/examples/core.json。示例仅监听本机；远程连接前须配置 ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:93` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `listen.host 和 TLS 证书 tls.certFile、tls.keyFile。agents 数组只填写明确接入的 ` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:94` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `Hermes、DSH 或 Codex 实例；具体适配器配置见官方文档。手机始终连接 Core 的同一个地址与端口。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:95` | 正文/说明 | GatewayTutorials | 生产可达 | **修改** |
| `创建 OAI 登录账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:98` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `密码通过交互输入。设备能力逐账号授权；Hermes 会话保留账号隔离，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:99` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `DSH 和 Codex 在此部署中共享可读取的原生会话。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:100` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `Terminal · 创建账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:102` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `启动统一服务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:107` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `此服务维护手机连接、账号验证和设备请求状态。切换 Agent 不需要另开手机连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:108` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `Terminal · 启动 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:110` | 正文/说明 | GatewayTutorials | 生产可达 | **修改** |
| `登录并查看已接入的 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:115` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `手机填写配置好的 Core 地址、OAI 账号与密码。登录后查看真实在线状态与原生会话；` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:116` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `设备插件在设置中独立安装和授权。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:117` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `登录后展示此 Core 已明确接入的 Agent；未接入时显示空列表。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:118` | 正文/说明 | GatewayTutorials | 生产可达 | **修改** |
| `不支持的会话操作会禁用，切换 Agent 不会重置手机连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:119` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `OpenClaw 兼容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:127` | 正文/说明 | GatewayTutorials | 遗留/废弃兼容 | **保留** |
| `旧 Gateway 兼容入口。本阶段不参与 Core 的 Agent 适配器重构，继续按原方式连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:129` | 正文/说明 | GatewayTutorials | 生产可达 | **修改** |
| `插件 v1.1.0` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:130` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `准备主密钥文件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:134` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `生成一个 32 字节的随机密钥文件，并把权限设为 0600。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:135` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `这是加密存储消息正文的根密钥，不要放进版本库。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:136` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `命令带存在性判断：**密钥已存在时不会覆盖**——换掉密钥会让历史消息无法解密。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:137` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `Terminal · 生成主密钥` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:139` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `告知 OpenClaw 密钥位置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:149` | 标题 | GatewayTutorials | 遗留/废弃兼容 | **保留** |
| `通过环境变量指定密钥文件路径。这一条要在**启动 OpenClaw 的那个环境**里生效，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:150` | 标题 | GatewayTutorials | 遗留/废弃兼容 | **保留** |
| `改了之后需要重启宿主。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:151` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `安装 Gateway 插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:161` | 标题 | GatewayTutorials | 生产可达 | **修改** |
| `按指定版本安装以确保稳定。插件会随安装自动同步协议定义，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:162` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `启动时无需联网下载。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:163` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `Terminal · 安装插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:165` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `核验插件运行状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:171` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `检查插件是否已被运行时加载。JSON 输出里能看到插件状态与协议版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:172` | 标题 | GatewayTutorials | 生产可达 | **保留** |
| `Terminal · 检查插件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:174` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `JSON 中插件状态为已加载，且协议版本显示 2.1。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/data/GatewayTutorials.kt:178` | 正文/说明 | GatewayTutorials | 生产可达 | **保留** |
| `外观选项` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/AppearanceSetupScreen.kt:53` | 正文/说明 | AppearanceSetupScreen | 生产可达 | **保留** |
| `选择适合你的界面主题与色彩风格` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/AppearanceSetupScreen.kt:55` | 正文/说明 | AppearanceSetupScreen | 生产可达 | **保留** |
| `下一步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/AppearanceSetupScreen.kt:80` | 正文/说明 | AppearanceSetupScreen | 生产可达 | **保留** |
| `OAI Core\n部署好了吗？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:109` | 正文/说明 | DeploymentChoiceScreen | 生产可达 | **修改** |
| `手机只连接一个 OAI Core，由 Core 接入不同 Agent。若已完成部署，直接进入登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:118` | 正文/说明 | DeploymentChoiceScreen | 生产可达 | **修改** |
| `我已部署` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:126` | 标题 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `已启动 OAI Core 并创建 OAI 账号。使用同一个服务端地址登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:127` | 标题 | DeploymentChoiceScreen | 生产可达 | **修改** |
| `进入登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:128` | 正文/说明 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `我还未部署` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:136` | 标题 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `查看 OAI Core 安装、账号创建与 oai serve 启动步骤，也保留旧 OpenClaw 兼容指南。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:137` | 标题 | DeploymentChoiceScreen | 遗留/废弃兼容 | **修改** |
| `查看教程` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:138` | 按钮/操作 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `说明` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:162` | 正文/说明 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `暂不提供统一云端服务，需连接自托管服务器` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/DeploymentChoiceScreen.kt:169` | 正文/说明 | DeploymentChoiceScreen | 生产可达 | **保留** |
| `部署完毕，进入登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:195` | 正文/说明 | GatewayGuideScreen | 生产可达 | **保留** |
| `未完成？可稍后在设置中重新查看` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:201` | 正文/说明 | GatewayGuideScreen | 生产可达 | **保留** |
| `${tutorial.shortName} 部署教程` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:234` | 无障碍辅助 | GatewayGuideScreen | 生产可达 | **保留** |
| `打开官方仓库 ${tutorial.repoUrl}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:337` | 按钮/操作 | GatewayGuideScreen | 生产可达 | **保留** |
| `官方仓库` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:349` | 正文/说明 | GatewayGuideScreen | 生产可达 | **保留** |
| `验证通过的样子` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/GatewayGuideScreen.kt:453` | 正文/说明 | GatewayGuideScreen | 生产可达 | **保留** |
| `按需授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:122` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `当前没有需要授权的系统权限。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:142` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `下一步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:216` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `跳过可选权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:225` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `基础功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:238` | 输入占位/表单 | PermissionsScreen | 生产可达 | **保留** |
| `可选增强` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:239` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `OPTIONAL · 可跳过` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:239` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `设备能力` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:240` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `PLUGIN · 按需` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:240` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `使用时由系统询问 · 无需现在授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:306` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `无需操作` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:307` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `系统未提供可用的数字助理设置页 · 仍可在应用内对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:312` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `当前设备不支持` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:314` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `不支持` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:316` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `已设为默认数字助理 · 可用 Assist 手势唤起` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:320` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `已设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:321` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `未设为默认数字助理 · 可在系统设置中选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:325` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `去设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:326` | 按钮/操作 | PermissionsScreen | 生产可达 | **修改** |
| `已授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:330` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `已开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:331` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `已被拒绝 · 需在系统设置开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:335` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `前往设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:336` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `未授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:340` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:341` | 按钮/操作 | PermissionsScreen | 生产可达 | **保留** |
| `必需` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:476` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `系统设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:478` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `可选` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:480` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `按需` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt:482` | 正文/说明 | PermissionsScreen | 生产可达 | **保留** |
| `连接一个\nOAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:174` | 正文/说明 | WelcomeScreen | 生产可达 | **修改** |
| `一个手机连接入口，由 OAI Core 接入多个自托管 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:185` | 正文/说明 | WelcomeScreen | 生产可达 | **修改** |
| `Agent 会话与设备插件独立管理，设备能力始终需要你的授权。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:186` | 正文/说明 | WelcomeScreen | 生产可达 | **修改** |
| `凭据存于 Android Keystore · 自托管 Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:199` | 正文/说明 | WelcomeScreen | 生产可达 | **修改** |
| `设备能力默认拒绝 · 逐项授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:200` | 正文/说明 | WelcomeScreen | 生产可达 | **保留** |
| `开始使用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/WelcomeScreen.kt:228` | 正文/说明 | WelcomeScreen | 生产可达 | **保留** |

### 6.6 连接与账号 (118 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `已连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:182` | 状态/空页面 | GatewayLoginScreen | 生产可达 | **保留** |
| `未连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:182` | 状态/空页面 | GatewayLoginScreen | 生产可达 | **保留** |
| `连接 OAI Core，发现已接入的 Agent` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:200` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `改用密码登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:219` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `使用邀请短码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:219` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `二维码内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:220` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `粘贴 oai://pair?… 邀请内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:226` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `OAI Core 服务端地址` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:229` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **修改** |
| `https://gateway.example.local:8443 或 http://10.0.2.2:8045` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:243` | 错误提示 | GatewayLoginScreen | 异常兜底 | **保留** |
| `请输入包含主机名的有效网关地址（例如 http://10.0.2.2:8045 或 https://gateway.example.com）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:245` | 错误提示 | GatewayLoginScreen | 异常兜底 | **保留** |
| `用户名 / 账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:255` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `输入账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:259` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `一次性邀请短码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:264` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `访问凭据 / 密码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:264` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `输入访问凭据或密码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:267` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `正在连接…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:315` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `登录 OAI Core` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:317` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `凭据只用于本次认证，刷新凭据由 Android Keystore 保护` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:339` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `已保存账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:348` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `移除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:374` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `邀请内容无效或已过期，请重新获取。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:404` | 错误提示 | GatewayLoginScreen | 异常兜底 | **保留** |
| `使用已配对设备密钥登录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:410` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `重新确认网关身份` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:413` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:419` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `移除账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:430` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `将退出 ${profile.username} 并删除本地凭据与缓存，服务端账号不受影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:431` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `移除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:432` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:433` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `重新确认网关身份` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:436` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `请先向管理员核对部署或证书变更。继续会清除该账号的旧网关身份和自动登录凭据；下一次密码登录会重新建立 TLS 信任。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:437` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `已核对，继续` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:438` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **修改** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:439` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `二次验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:456` | 标题 | GatewayLoginScreen | 生产可达 | **保留** |
| `绑定验证器` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:457` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `保存恢复码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:458` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `请输入一次性恢复码。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:465` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `请输入验证器中的动态验证码。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:465` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `使用动态验证码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:467` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `使用恢复码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:467` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `恢复码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:470` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `动态验证码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:470` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `请在验证器中添加以下地址或密钥，然后输入动态验证码。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:476` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `动态验证码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:479` | 输入占位/表单 | GatewayLoginScreen | 生产可达 | **保留** |
| `请将以下一次性恢复码安全保存。确认保存后才能完成登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:485` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `我已保存` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:494` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **修改** |
| `验证` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:500` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:503` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `隐藏密码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:556` | 无障碍辅助 | GatewayLoginScreen | 生产可达 | **保留** |
| `显示密码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:556` | 无障碍辅助 | GatewayLoginScreen | 生产可达 | **保留** |
| `清除内容` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:565` | 无障碍辅助 | GatewayLoginScreen | 生产可达 | **保留** |
| `未连接 Gateway` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:586` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `正在连接 OAI Core…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:591` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `正在重新连接 OAI Core…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:593` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `正在连接…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:595` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `正在验证凭据…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:597` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `离线模式：仅显示本地缓存，连接后可继续收发` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:609` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `已连接 ${phase.username}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:624` | 状态/空页面 | GatewayLoginScreen | 生产可达 | **保留** |
| `重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:649` | 按钮/操作 | GatewayLoginScreen | 生产可达 | **保留** |
| `未加密连接（HTTP）：账号口令、消息与附件内容在传输途中可被读取或篡改，` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:705` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `且无法核验 Gateway 身份。请仅在你信任的本机或局域网内使用，正式部署请改用 https://。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:706` | 正文/说明 | GatewayLoginScreen | 生产可达 | **修改** |
| `未加密（HTTP）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:732` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `TLS 已固定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:733` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `系统 CA 信任` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:734` | 正文/说明 | GatewayLoginScreen | 生产可达 | **保留** |
| `自动登录凭据未能保存，下次启动需要重新登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1092` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `验证码未通过，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1156` | 错误提示 | GatewayRuntime | 异常兜底 | **保留** |
| `验证码或恢复码未通过，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1182` | 错误提示 | GatewayRuntime | 异常兜底 | **保留** |
| `本机退出状态未能保存，请检查设备存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1209` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `本机已退出，但本地会话保存失败，请检查设备存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1238` | Toast/提示 | GatewayRuntime | 异常兜底 | **保留** |
| `本机已退出，登出未获 Gateway 确认，请检查连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1249` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `本机已退出，离线登出未获 Gateway 确认；本机凭据已锁定保留，重新登录后可继续处理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1252` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Gateway 已登出，但本机凭据清理失败，请检查设备存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1274` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `本机已退出，登出未获 Gateway 确认，且本机凭据清理失败，请检查设备存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1276` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `当前账号操作尚未结束，请稍后再切换。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1324` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `当前账号操作尚未结束，请稍后再选择账号。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1338` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `账号选择未能保存，请检查本机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1351` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `本地会话保存失败，请检查手机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1363` | Toast/提示 | GatewayRuntime | 异常兜底 | **保留** |
| `账号切换未完成，请检查手机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1397` | Toast/提示 | GatewayRuntime | 异常兜底 | **保留** |
| `旧网关身份和自动登录凭据已清除，请核对新身份后使用密码重新登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1413` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `网关身份清理失败，请检查本机存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1414` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `请先进入账号管理，并等待当前账号操作完成后再移除。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1437` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `正在退出并移除本机账号…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1444` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `本机账号已移除；Gateway 账号和其他设备配对保持有效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1493` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `本机账号移除未完成，资料已保留，请检查连接或存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1495` | Toast/提示 | GatewayRuntime | 异常兜底 | **保留** |
| `当前账号操作尚未结束，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1520` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `需要连接当前 Core 后才能解除设备配对。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1529` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `本地会话未能保存，设备配对和凭据已保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1541` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `解除配对未获 Core 确认，本机凭据已保留，请检查连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1549` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `Core 已解除配对，本机任务收尾失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1562` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Core 已解除配对，未完成下载的恢复记录清理失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1572` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Core 已解除配对，但本机数据清理失败，请重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1595` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `配对已解除，但部分本机资料尚未清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1600` | Toast/提示 | GatewayRuntime | 异常兜底 | **保留** |
| `配对已解除，本机清理被中断，请检查本机账号资料。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1604` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `解除配对被中断，请重新连接后确认配对状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1604` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `配对已解除，本机清理未完成，请检查存储后重试本机账号清理。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1610` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `解除配对未完成，本机凭据已保留，请检查连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1610` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `本机没有为当前账号保存刷新凭据，请重新登录以获取自动登录凭据。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1629` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `已向 Gateway 续期会话凭据，新的访问令牌已生效。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1637` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Gateway 尚未提供刷新结果重放，续期结果暂不确定。账号资料已保留，请更新 Gateway 后重新认证。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1649` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Gateway 会话恢复暂未完成，登录资料已保留，连接可用后将自动重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1650` | 错误提示 | GatewayRuntime | 异常兜底 | **修改** |
| `Gateway 已明确撤销或拒绝会话凭据，请重新登录。账号资料和历史记录已保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1744` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `本地镜像读取失败，正在尝试连接。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1914` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `设备结果交付重试中。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:2251` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `设备能力状态同步未完成（${capabilitySyncErrorCode(cause)}），正在重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:2282` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `上次使用的 Agent「$lost」已不在当前列表中，请重新选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:2757` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `Gateway 事件通道认证仍被拒绝，已暂停该通道。登录和本机授权已保留，请检查 Gateway 会话与设备认证配置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3054` | Toast/提示 | GatewayRuntime | 异常兜底 | **修改** |
| `Gateway 事件通道恢复已暂停，登录资料已保留，请检查连接配置或本机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3072` | Toast/提示 | GatewayRuntime | 生产可达 | **修改** |
| `未完成的下载恢复失败，已保留恢复记录，可在附件卡片重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3106` | 错误提示 | GatewayRuntime | 异常兜底 | **保留** |
| `设备能力状态已变化，正在重新同步；本机开关保持原设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3222` | Toast/提示 | GatewayRuntime | 生产可达 | **保留** |
| `读取本地会话失败，历史记录已保留，请重新连接后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3388` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `离线镜像：可阅读历史并编辑草稿，重新连接后可发送。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3389` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `本地附件状态恢复失败，消息正文已保留，附件暂不可用，请重试同步。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3390` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `Gateway 拒绝续期：刷新凭据已被使用过，请重新登录以取得新的会话凭据。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3416` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `刷新网关凭据失败：网关部署身份或证书已变更，请核对后重新登录以建立新信任。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3418` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `Gateway 拒绝续期：App 与 Gateway 版本不一致，请把两端升级到同一版本后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3420` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `Gateway 拒绝续期：当前凭据已失效或被撤销，请重新登录。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3423` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `刷新网关凭据失败：连接超时，当前连接仍然有效，请检查网络后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3425` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |
| `刷新网关凭据失败：无法连接 Gateway，当前连接仍然有效，请检查网络后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3427` | 正文/说明 | GatewayRuntime | 生产可达 | **修改** |
| `刷新网关凭据失败，当前连接仍然有效，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:3428` | 正文/说明 | GatewayRuntime | 生产可达 | **保留** |

### 6.7 对话界面 - 状态与空页面 (99 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `设置条目 · 动态取色` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:25` | 调试预览 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `外观与动效` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:37` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `系统动态取色` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:39` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `跟随系统壁纸提取色调（Android 12+）` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:40` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `配对能力授权清单` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:48` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `读取与发送短信` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:50` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `限制：每次交互须经手机确认` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:51` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `开发者信任模式` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:57` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `未开启：处于 WASM 沙箱隔离保护状态` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:58` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `系统级安全熔断` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:64` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `一键紧急停用` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:66` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `隔离全部已启用插件并关闭信任模式` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:67` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `展开折叠容器` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:79` | 调试预览 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `收起输出` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:86` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `查看输出` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:86` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `这里是展开后追加的内容行` | `conversation-ui/src/debug/kotlin/com/openandroidintelligence/conversation/components/SettingsComponentsPreview.kt:90` | 标题 | SettingsComponentsPreview | 仅设计预览 | **保留** |
| `选择会话后显示内容` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:32` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `正在加载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:39` | 标题 | StateViews | 生产可达 | **保留** |
| `正在从 Gateway 读取内容…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:40` | 标题 | StateViews | 生产可达 | **修改** |
| `还没有内容` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:46` | 标题 | StateViews | 生产可达 | **保留** |
| `刷新` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:49` | 按钮/操作 | StateViews | 生产可达 | **保留** |
| `暂时无法加载` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:53` | 标题 | StateViews | 生产可达 | **保留** |
| `重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:56` | 按钮/操作 | StateViews | 生产可达 | **保留** |
| `暂时无法取得内容。请检查连接后重试；若持续失败，请检查 Gateway 服务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:70` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `无法连接 Gateway。请检查地址与网络，并确认 App 与插件版本一致后重试；若持续失败，请查看 Gateway 服务与日志。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:80` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `登录已成功，但 Gateway 拒绝了事件通道的会话认证，本机授权已冻结。账号资料和历史记录已保留，请重新登录；若持续发生，请检查 Gateway 的会话…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:105` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `当前 Gateway 会话已被撤销，本机授权已冻结。账号资料和历史记录已保留，请重新登录。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:107` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `本机会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:110` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机授权状态` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:111` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机设备功能` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:112` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机附件记录` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:113` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机媒体缓存` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:114` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机对话历史` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:115` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机工作台` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:116` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `本机初始化` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:117` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `登录已成功，但 App 未能完成${stage}的初始化。账号资料和历史记录已保留，请更新 App 后重试；若持续发生，请查看手机上的连接诊断。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:119` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `会话记录保存失败，同步已暂停。请检查存储空间后重新进入会话重试；暂勿重复发送原消息。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:122` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `会话同步数据未能处理，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App 与 Core 的版本兼容性。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:124` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `设备认证已修复，请重新登录一次以更新设备公钥。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:125` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `网关暂时无法保存附件或发送消息（主密钥来源不可用）。请重启网关；若持续发生，请让网关部署者检查密钥文件的路径、所有者与权限。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:126` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `审批卡暂时无法显示，命令仍在等待执行。请回复 /approve 或 /deny 完成审批。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:128` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `审批已超时，命令未执行。如需执行请让 Agent 重新发起。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:130` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `审批决策没有送达 Gateway，卡片已恢复为可点击，请重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:132` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `引导消息已交给 Agent，由 Agent 处理当前任务。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:134` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `实时引导只接受文本；带附件的消息请选择排队。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:136` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `引导消息未提交，草稿已保留，请重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:137` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `当前 Gateway 不支持会话管理，请安装配套网关更新后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:139` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `当前 Agent 不支持删除对话，会话记录已保留。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:140` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `当前 Agent 不支持归档操作，会话记录已保留。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:141` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `当前 Agent 不支持重命名对话。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:142` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `暂无可用的会话缓存。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:143` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `当前 Agent 已离线，请等待恢复连接后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:144` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `会话仍有发送或任务正在处理，请等待结束后再归档或删除。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:145` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `另一端已修改文件夹或排序，已重新同步，请重试这次操作。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:146` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `文件夹已发生变化，请刷新会话列表后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:147` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `Gateway 尚未确认这次会话修改，请刷新列表并重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:148` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `历史会话同步失败，已保留本机记录。请检查连接后刷新。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:149` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `正在等待 Agent 返回新会话，请稍候。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:150` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `Agent 已经回话了，但没有返回新建会话的结果，无法确认新会话的标识，因此仍停留在原会话。通常意味着当前 Gateway/宿主未实现该命令入口，请重启或…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:152` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `该 Gateway 不支持由 App 新建对话。App 不会创建只有本机可见的会话，请升级 Gateway 或联系部署者。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:154` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `还没有可承接新对话的会话，请先发送一条消息。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:155` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `Agent 迟迟没有返回新建的会话，已停在原会话。请确认 Gateway 与 Agent 正在运行后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:157` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `已停止等待新建对话，仍停留在原会话。可以重新点击「新建对话」。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:159` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `操作结果尚未确认。请刷新核实，避免重复提交。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:160` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `新建对话失败，已停留在原会话，本地没有留下与 Gateway 不一致的会话。请检查连接后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:162` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `网关没有保存这次重命名，已恢复原标题。请确认网关版本支持会话重命名。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:163` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `网关拒绝了这次请求的内容，请更新 App 或检查网关版本。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:164` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `会话进度已过期，请刷新以重新同步内容。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:165` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `网关地址不受支持，请使用 http:// 或 https:// 开头的地址。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:166` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `Gateway 未提供可核验的 TLS 身份，已按安全要求拒绝连接。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:167` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `App 与 Gateway 协议版本不一致（或连接已失效），连接被拒绝。请把 App 与插件升级到同一版本后重试；若刚升级过，重新登录一次即可。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:174` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `Gateway 与当前 Agent 宿主版本不兼容，请在 Agent 端升级插件或宿主后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:176` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `Gateway 正在连接或连接未完成。请确认地址指向有效的 Gateway 服务，并检查版本与运行日志。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:180` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `操作结果尚未确认。请刷新核实，避免重复提交。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:181` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `Agent 无法读取附件，请重新选择文件后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:183` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `Agent 不支持处理此附件格式。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:185` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `模型拒绝了包含附件的请求格式，请检查 Agent 端模型配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:187` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `Agent 当前不可用，附件已由 Gateway 接收但尚未处理。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:189` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `这次请求与已有操作冲突，请刷新会话核实结果。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:190` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `登录凭据已失效，请前往账号与 Gateway 重新登录。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:191` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `当前账号没有访问权限，请检查账号或联系 Gateway 管理员。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:192` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `无法验证 Gateway 的安全连接，请检查地址与证书配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:193` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `Gateway 附件存储空间不可用，请检查 Agent 端磁盘空间后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:194` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `Gateway 或反向代理拒绝了上传请求，请检查服务端存储与代理配置。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:195` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `请求过于频繁，请稍后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:196` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `Gateway 一直没有返回回复。已尝试重新同步会话，若仍无内容请检查 Gateway 与 Agent 是否在运行。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:198` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `会话同步进度尚未获 Gateway 确认，本机记录已保留。请检查连接后重新进入会话重试同步，避免重复发送原消息。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:200` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `读取会话同步数据失败，本机记录已保留。请重新进入会话重试；若持续失败，请检查 App、Core 与 Agent 的版本兼容性。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:202` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `与 Gateway 的实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:204` | 正文/说明 | StateViews | 异常兜底 | **修改** |
| `连接超时，请检查网络后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:205` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `暂时无法连接 Gateway，请检查网络和服务地址后重试。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:206` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `实时通道未建立` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:216` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `正在连接…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:217` | 正文/说明 | StateViews | 生产可达 | **保留** |
| `已连接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:218` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `重连中…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:219` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `通道已断开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:220` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `正在连接 Gateway…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:227` | 正文/说明 | StateViews | 生产可达 | **修改** |
| `连接中断，正在重连…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:229` | 正文/说明 | StateViews | 异常兜底 | **保留** |
| `实时通道已断开，暂时收不到回复` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt:230` | 正文/说明 | StateViews | 异常兜底 | **保留** |

### 6.8 主界面与原生会话 (97 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Gateway 运行环境不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:66` | 正文/说明 | BubbleConversationActivity | 生产可达 | **修改** |
| `会话链接无效` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:157` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **保留** |
| `正在连接 Gateway…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:158` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **修改** |
| `正在查找该 Agent…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:160` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **修改** |
| `此气泡属于其他账号，无法读取或回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:161` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **保留** |
| `Gateway 连接失败，请在应用中重新连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:162` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **修改** |
| `Gateway 尚未连接，请在应用中连接后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:163` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **修改** |
| `会话列表` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:173` | 标题 | BubbleConversationActivity | 生产可达 | **保留** |
| `全部会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:174` | 标题 | BubbleConversationActivity | 生产可达 | **保留** |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:174` | 标题 | BubbleConversationActivity | 生产可达 | **修改** |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:174` | 标题 | BubbleConversationActivity | 生产可达 | **修改** |
| `关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:177` | 按钮/操作 | BubbleConversationActivity | 生产可达 | **保留** |
| `打开通知对应的会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:193` | 按钮/操作 | BubbleConversationActivity | 生产可达 | **保留** |
| `会话连接已失效，请返回列表重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:205` | 正文/说明 | BubbleConversationActivity | 生产可达 | **保留** |
| `重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:241` | 按钮/操作 | BubbleConversationActivity | 生产可达 | **保留** |
| `此会话暂无消息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:250` | 状态/空页面 | BubbleConversationActivity | 生产可达 | **保留** |
| `关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:305` | 按钮/操作 | BubbleConversationActivity | 生产可达 | **保留** |
| `排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:310` | 正文/说明 | BubbleConversationActivity | 生产可达 | **保留** |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:311` | 正文/说明 | BubbleConversationActivity | 异常兜底 | **修改** |
| `已完成` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:312` | 正文/说明 | BubbleConversationActivity | 异常兜底 | **保留** |
| `任务失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:313` | 正文/说明 | BubbleConversationActivity | 异常兜底 | **保留** |
| `已取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:314` | 正文/说明 | BubbleConversationActivity | 异常兜底 | **保留** |
| `本次处理已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:315` | 正文/说明 | BubbleConversationActivity | 生产可达 | **保留** |
| `连接中断，等待恢复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt:316` | 正文/说明 | BubbleConversationActivity | 生产可达 | **保留** |
| `新建对话失败，请重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationWindow.kt:118` | Toast/提示 | BubbleConversationWindow | 异常兜底 | **保留** |
| `去设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:161` | 按钮/操作 | MainActivity | 生产可达 | **修改** |
| `请切换到收到回复的账号后查看会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:220` | 状态/空页面 | MainActivity | 生产可达 | **保留** |
| `录音已丢失，请重新录制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:254` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `对话已切换，语音未发送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:256` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `对话已切换，语音未发送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:258` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `语音未发送，请查看对话提示后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:260` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `无法开始录音，请检查麦克风权限及占用情况` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:276` | 错误提示 | MainActivity | 异常兜底 | **保留** |
| `录音或发送失败，请重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:277` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `对话已切换，请重新开始录音` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:310` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `当前对话暂不能发送语音，请等待处理完成后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:312` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `需要麦克风权限才能发送语音` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:335` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `对话尚未就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:341` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `当前对话暂不能发送语音，请等待处理完成后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:343` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `已获得屏幕采集授权，请再次点击圈选开始截取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:419` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `屏幕采集会话建立失败，圈选暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:420` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `已获得屏幕采集授权，请再次点击圈选开始截取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:429` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `未获得屏幕采集授权，圈选暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:430` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `未开启屏幕截图功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:466` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `未获得屏幕截图授权，截图暂时不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:476` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `截图能力或本机设置已变化，请重新发起系统授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:485` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `截图能力已撤销，未使用系统授权结果` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:496` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `已获得屏幕截图授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:506` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `屏幕截图会话建立失败，截图暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:507` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `未开启屏幕截图功能` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:515` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `请先启用截图插件并开启截图权限` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:516` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `截图已可用，不需要额外的系统授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:519` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `系统未提供屏幕录制服务，截图暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:523` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `本地会话暂时不可用，资料已保留。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:708` | 按钮/操作 | MainActivity | 生产可达 | **保留** |
| `重试连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:709` | 按钮/操作 | MainActivity | 生产可达 | **保留** |
| `设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:710` | 按钮/操作 | MainActivity | 生产可达 | **保留** |
| `现在可以直接圈选，无需额外授权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:780` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `此设备不支持屏幕采集，圈选不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:784` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `离线模式：仅显示本地缓存，连接后可继续收发` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:853` | 正文/说明 | MainActivity | 生产可达 | **保留** |
| `重新连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:859` | 按钮/操作 | MainActivity | 生产可达 | **保留** |
| `设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:65` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `在线` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:70` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `离线` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:70` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `刷新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:101` | 按钮/操作 | NativeAgentSessionsScreen | 异常兜底 | **保留** |
| `回复流已中断，执行结果暂不确定。请刷新原生历史后再决定是否重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:106` | 按钮/操作 | NativeAgentSessionsScreen | 异常兜底 | **保留** |
| `Agent 会话操作未完成：$code` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:107` | 按钮/操作 | NativeAgentSessionsScreen | 异常兜底 | **修改** |
| `关闭提示` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:108` | 按钮/操作 | NativeAgentSessionsScreen | 异常兜底 | **保留** |
| `此 OAI Core 尚未接入 Agent。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:113` | 错误提示 | NativeAgentSessionsScreen | 异常兜底 | **修改** |
| `Agent 列表暂不可用，请刷新重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:113` | 错误提示 | NativeAgentSessionsScreen | 异常兜底 | **修改** |
| `此 Agent 的会话按当前 OAI 账号隔离。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:118` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `此 Core 已接入的共享会话，所有已登录账号均可访问。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:119` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:124` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `新建会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:126` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `此 Agent 未提供会话列表能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:128` | 错误提示 | NativeAgentSessionsScreen | 异常兜底 | **修改** |
| `暂无可读取的会话。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:129` | 错误提示 | NativeAgentSessionsScreen | 异常兜底 | **保留** |
| `新对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:136` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `已归档` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:139` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `返回会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:146` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `重命名` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:148` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `归档` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:150` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `删除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:152` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `新对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:154` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `此 Agent 未提供历史读取能力。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:156` | 状态/空页面 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `此 Agent 暂不支持实时消息收发。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:174` | 输入占位/表单 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `消息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:177` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `停止生成` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:181` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `发送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:184` | 正文/说明 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `重命名会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:190` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `标题` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:191` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `保存` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:193` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:194` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `删除会话？` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:195` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `这会调用 ${agent?.name.orEmpty()} 的原生删除接口。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:196` | 标题 | NativeAgentSessionsScreen | 生产可达 | **修改** |
| `删除` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:197` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:198` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `OAI 账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:199` | 标题 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `退出当前账号` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:207` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |
| `关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt:209` | 按钮/操作 | NativeAgentSessionsScreen | 生产可达 | **保留** |

### 6.9 系统通知与回复 (82 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Agent 实时任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:25` | 系统通知 | LiveUpdateNotifications | 生产可达 | **修改** |
| `进行中的 Agent 任务状态，可在系统支持的 Live Updates 界面显示` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:26` | 系统通知 | LiveUpdateNotifications | 生产可达 | **修改** |
| `Agent 任务排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:59` | 状态/空页面 | LiveUpdateNotifications | 生产可达 | **修改** |
| `Agent 正在执行任务` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:59` | 状态/空页面 | LiveUpdateNotifications | 生产可达 | **修改** |
| `等待 Agent 开始处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:61` | 正文/说明 | LiveUpdateNotifications | 生产可达 | **修改** |
| `任务进行中，等待新的公开回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:61` | 正文/说明 | LiveUpdateNotifications | 生产可达 | **保留** |
| `排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:70` | 正文/说明 | LiveUpdateNotifications | 生产可达 | **保留** |
| `处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:70` | 正文/说明 | LiveUpdateNotifications | 生产可达 | **保留** |
| `打开会话窗口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/LiveUpdateNotifications.kt:74` | 正文/说明 | LiveUpdateNotifications | 生产可达 | **保留** |
| `Agent 会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:31` | 标题 | NativeConversationNotifications | 生产可达 | **修改** |
| `[附件回复]` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:289` | 正文/说明 | NativeConversationNotifications | 生产可达 | **保留** |
| `[附件回复]` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:358` | 正文/说明 | NativeConversationNotifications | 生产可达 | **保留** |
| `[附件回复]` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:491` | 正文/说明 | NativeConversationNotifications | 生产可达 | **保留** |
| `会话 ${target.conversationId.takeLast(12)}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NativeConversationNotifications.kt:664` | 标题 | NativeConversationNotifications | 生产可达 | **保留** |
| `点击查看完整回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/NotificationReplyExcerpt.kt:7` | 正文/说明 | NotificationReplyExcerpt | 生产可达 | **保留** |
| `Agent 任务回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:176` | 系统通知 | ReplyNotifications | 生产可达 | **修改** |
| `Agent 处理中实时显示公开回复，并在任务结束时提醒` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:177` | 系统通知 | ReplyNotifications | 生产可达 | **修改** |
| `我` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:190` | 正文/说明 | ReplyNotifications | 生产可达 | **保留** |
| `Agent 已回复，仍有任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:219` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `部分任务失败，仍有任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:220` | 正文/说明 | ReplyNotifications | 异常兜底 | **保留** |
| `部分任务已取消，仍有任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:221` | 正文/说明 | ReplyNotifications | 异常兜底 | **保留** |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:222` | 正文/说明 | ReplyNotifications | 生产可达 | **修改** |
| `Agent 任务排队中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:224` | 正文/说明 | ReplyNotifications | 生产可达 | **修改** |
| `Agent 正在处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:225` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `Agent 任务已完成` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:226` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `Agent 任务失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:227` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `Agent 任务已取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:228` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `任务已结束` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:229` | 正文/说明 | ReplyNotifications | 生产可达 | **保留** |
| `Gateway 已断开，任务状态待同步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:230` | 正文/说明 | ReplyNotifications | 生产可达 | **修改** |
| `等待 Agent 开始处理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:233` | 正文/说明 | ReplyNotifications | 生产可达 | **修改** |
| `等待 Agent 回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:234` | 正文/说明 | ReplyNotifications | 异常兜底 | **修改** |
| `已收到回复，点击查看会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:235` | 正文/说明 | ReplyNotifications | 异常兜底 | **保留** |
| `任务未完成，请查看会话中的错误信息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:236` | 正文/说明 | ReplyNotifications | 异常兜底 | **保留** |
| `任务已取消` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:237` | 正文/说明 | ReplyNotifications | 异常兜底 | **保留** |
| `任务已结束，点击查看会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:238` | 正文/说明 | ReplyNotifications | 生产可达 | **保留** |
| `重新连接后同步任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:239` | 正文/说明 | ReplyNotifications | 生产可达 | **保留** |
| `打开会话窗口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ReplyNotifications.kt:259` | 正文/说明 | ReplyNotifications | 生产可达 | **保留** |
| `选择应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:108` | 标题 | NotificationAppPicker | 生产可达 | **保留** |
| `取消选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:111` | 按钮/操作 | NotificationAppPicker | 生产可达 | **保留** |
| `保存` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:119` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `已选 ${selectedIds.size} 个应用；未选择任何应用时不会采集通知。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:135` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `此发行版仅显示系统允许查询的已安装应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:144` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `搜索应用名称或包名` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:157` | 无障碍辅助 | NotificationAppPicker | 生产可达 | **保留** |
| `取消全选` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:182` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `全选` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:182` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `反选` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:201` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `设置没有保存成功：$rejection` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:208` | 错误提示 | NotificationAppPicker | 异常兜底 | **保留** |
| `重新载入已保存选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:216` | 按钮/操作 | NotificationAppPicker | 生产可达 | **保留** |
| `无法读取本机应用列表，请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:223` | 按钮/操作 | NotificationAppPicker | 异常兜底 | **保留** |
| `重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:224` | 按钮/操作 | NotificationAppPicker | 异常兜底 | **保留** |
| `正在读取本机应用…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:230` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `未找到可选择的已安装应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:235` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `没有匹配的应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:235` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `未安装或当前不可见，可以保留或取消原有选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:248` | 正文/说明 | NotificationAppPicker | 生产可达 | **保留** |
| `通知读取范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:237` | 标题 | NotificationCollectionScreen | 生产可达 | **保留** |
| `暂不支持读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:256` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `当前版本未包含通知读取组件，暂无法读取或查询系统通知。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:261` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `无法打开系统设置，请前往系统设置手动开启通知使用权。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:284` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `设置没有保存成功：$reason` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:285` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `允许读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:305` | 标题 | NotificationCollectionScreen | 生产可达 | **保留** |
| `已允许：Agent 可以按下面的范围读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:307` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **修改** |
| `未允许：Agent 读取通知时会被拒绝` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:309` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **修改** |
| `系统通知使用权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:316` | 标题 | NotificationCollectionScreen | 生产可达 | **保留** |
| `已开启：系统允许本应用读取通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:318` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `未开启：系统不允许本应用读取通知，因此读不到任何通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:320` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `前往系统设置开启通知使用权` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:331` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `读取通知的标题与正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:344` | 标题 | NotificationCollectionScreen | 生产可达 | **保留** |
| `已开启：通知的标题与正文会被读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:346` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `已关闭：只读取应用名、时间等基本信息，标题与正文会被丢弃` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:348` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `自动把通知发送给 Gateway` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:368` | 标题 | NotificationCollectionScreen | 生产可达 | **修改** |
| `已开启：符合条件的通知使用原有自动投递配置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:370` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `旧自动发送已关闭；通知查询和另行配置的主动推送不受此开关控制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:372` | 正文/说明 | NotificationCollectionScreen | 遗留/废弃兼容 | **保留** |
| `旧通知投递通道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:383` | 标题 | NotificationCollectionScreen | 遗留/废弃兼容 | **修改** |
| `加密待发队列不可用，旧通知无法排队投递。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:385` | 正文/说明 | NotificationCollectionScreen | 遗留/废弃兼容 | **修改** |
| `加密待发队列已装配；最近创建的旧通知运行时未装配投递组件，或尚未启动监听。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:386` | 正文/说明 | NotificationCollectionScreen | 遗留/废弃兼容 | **修改** |
| `最近创建的旧通知运行时已装配投递组件；这不表示当前正在投递或投递成功。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:387` | 正文/说明 | NotificationCollectionScreen | 遗留/废弃兼容 | **保留** |
| `读取哪些应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:403` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `选择应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:415` | 状态/空页面 | NotificationCollectionScreen | 生产可达 | **保留** |
| `未选择应用时不会读取任何通知。请点击上方按钮添加应用。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:420` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `未安装或当前不可见，已保留原有选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:437` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `正在读取应用信息，已保留原有选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:439` | 正文/说明 | NotificationCollectionScreen | 生产可达 | **保留** |
| `移除 $packageId` | `app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationCollectionScreen.kt:445` | 无障碍辅助 | NotificationCollectionScreen | 生产可达 | **保留** |

### 6.10 对话界面 - Markdown渲染 (77 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `无法打开链接` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownInlineText.kt:63` | Toast/提示 | MarkdownInlineText | 生产可达 | **保留** |
| `公式渲染不可用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownMath.kt:138` | 正文/说明 | MarkdownMath | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:129` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:150` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `代码` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:191` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `代码` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:225` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:237` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:238` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:258` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:258` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:292` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:292` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:310` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:310` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:342` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:378` | 错误提示 | MarkdownParser | 异常兜底 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:385` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:385` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:392` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownParser.kt:392` | 正文/说明 | MarkdownParser | 生产可达 | **保留** |
| ` · 点击收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:98` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| ` · 点击展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:98` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `正在思考` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:100` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `正在思考 · 点击展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:100` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `思考已中断 · ${block.thought.length} 字$durationSuffix$actionSuffix` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:101` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `已思考 · ${block.thought.length} 字$durationSuffix$actionSuffix` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:102` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `思考已中断 · 无思考内容` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:103` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `无思考内容` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:104` | 正文/说明 | MarkdownThoughtView | 生产可达 | **保留** |
| `收起思考过程` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:141` | 按钮/操作 | MarkdownThoughtView | 生产可达 | **保留** |
| `展开思考过程` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:141` | 按钮/操作 | MarkdownThoughtView | 生产可达 | **保留** |
| `已展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:146` | 无障碍辅助 | MarkdownThoughtView | 生产可达 | **保留** |
| `已收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:146` | 无障碍辅助 | MarkdownThoughtView | 生产可达 | **保留** |
| `思考已中断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownThoughtView.kt:168` | 无障碍辅助 | MarkdownThoughtView | 生产可达 | **保留** |
| `执行中` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:138` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行已中断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:139` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `结果未知` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:140` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:141` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `已调用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:142` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `已完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:143` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:144` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:158` | 状态/空页面 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:158` | 状态/空页面 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行中` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:160` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行已中断 · 结果未知` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:161` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `结果未知` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:162` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:163` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `已调用` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:164` | 错误提示 | MarkdownToolCallView | 异常兜底 | **保留** |
| `已完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:165` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:166` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `收起工具详情` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:204` | 按钮/操作 | MarkdownToolCallView | 生产可达 | **保留** |
| `展开工具详情` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:204` | 按钮/操作 | MarkdownToolCallView | 生产可达 | **保留** |
| `已展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:208` | 无障碍辅助 | MarkdownToolCallView | 生产可达 | **保留** |
| `已收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:208` | 无障碍辅助 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:268` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `[执行命令]` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:268` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行成功` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:286` | 状态/空页面 | MarkdownToolCallView | 生产可达 | **保留** |
| `执行失败` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:286` | 状态/空页面 | MarkdownToolCallView | 生产可达 | **保留** |
| `命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:314` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `Hermes 在消息中未提供命令参数` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:333` | 正文/说明 | MarkdownToolCallView | 生产可达 | **修改** |
| `参数来自 Hermes 预览，可能已被截断` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:340` | 正文/说明 | MarkdownToolCallView | 生产可达 | **修改** |
| `输出` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:357` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `错误输出` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:357` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `未收到工具结果` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:370` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `此调用未附带返回值` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:371` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `无输出` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:372` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `复制命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:436` | 按钮/操作 | MarkdownToolCallView | 生产可达 | **保留** |
| `已复制命令` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:439` | Toast/提示 | MarkdownToolCallView | 生产可达 | **保留** |
| `已复制到剪贴板` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:440` | Toast/提示 | MarkdownToolCallView | 生产可达 | **保留** |
| `已复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:452` | 无障碍辅助 | MarkdownToolCallView | 生产可达 | **保留** |
| `复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:452` | 无障碍辅助 | MarkdownToolCallView | 生产可达 | **保留** |
| `已复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:461` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownToolCallView.kt:461` | 正文/说明 | MarkdownToolCallView | 生产可达 | **保留** |
| `已完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownViews.kt:311` | 无障碍辅助 | MarkdownViews | 生产可达 | **保留** |
| `未完成` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownViews.kt:311` | 无障碍辅助 | MarkdownViews | 生产可达 | **保留** |
| `复制代码` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownViews.kt:618` | 无障碍辅助 | MarkdownViews | 生产可达 | **保留** |
| `已复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownViews.kt:623` | 正文/说明 | MarkdownViews | 生产可达 | **保留** |
| `复制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/markdown/MarkdownViews.kt:623` | 正文/说明 | MarkdownViews | 生产可达 | **保留** |

### 6.11 应用核心与集成 (72 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `账号已切换，头像未更换` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AccountAvatarPicker.kt:45` | Toast/提示 | AccountAvatarPicker | 生产可达 | **保留** |
| `账号已切换，头像未更换` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AccountAvatarPicker.kt:53` | Toast/提示 | AccountAvatarPicker | 异常兜底 | **保留** |
| `无法更换头像，请重新选择图片` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AccountAvatarPicker.kt:57` | Toast/提示 | AccountAvatarPicker | 异常兜底 | **保留** |
| `无法打开系统相册` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AccountAvatarPicker.kt:85` | Toast/提示 | AccountAvatarPicker | 生产可达 | **保留** |
| `Agent 后台会话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:33` | 系统通知 | AgentSessionForegroundService | 生产可达 | **修改** |
| `仅在 Agent 任务运行期间接收会话回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:34` | 系统通知 | AgentSessionForegroundService | 生产可达 | **修改** |
| `Agent 任务处理中` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:114` | 标题 | AgentSessionForegroundService | 生产可达 | **修改** |
| `正在后台接收会话回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/AgentSessionForegroundService.kt:115` | 标题 | AgentSessionForegroundService | 生产可达 | **保留** |
| `每次最多导入 $MAX_COMPOSER_ATTACHMENTS 个附件，其余文件请分批分享或选择` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:80` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `对话已切换，附件未加入` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:88` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `对话已切换，剩余附件未加入` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:94` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件，请先移除附件或发送后再添加` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:98` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `对话已切换，剩余附件未加入` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:105` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `附件无法读取，请重新选择或分享` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:111` | Toast/提示 | ConversationAttachmentPickers | 异常兜底 | **保留** |
| `对话尚未就绪，无法添加附件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:146` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `对话尚未就绪，无法选择附件` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:151` | Toast/提示 | ConversationAttachmentPickers | 生产可达 | **保留** |
| `无法打开附件选择器：${failure.message}` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationAttachmentPickers.kt:158` | Toast/提示 | ConversationAttachmentPickers | 异常兜底 | **保留** |
| `已启用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:51` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `本网关不支持` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:52` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `未声明` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:53` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `未接入推送通道，通知推送不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:77` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `Gateway 未声明支持会话同步，通知推送不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:81` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **修改** |
| `网关不支持会话同步，通知推送不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:85` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `推送通道与会话镜像均已就绪` | `app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationUiCapabilities.kt:89` | 正文/说明 | ConversationUiCapabilities | 生产可达 | **保留** |
| `关闭操作提示` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:35` | 无障碍辅助 | SettingsCapabilityUi | 生产可达 | **保留** |
| `当前版本未接入任务回复通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:47` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `系统尚未允许通知；打开开关会请求权限，或前往系统设置手动允许` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:48` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `任务进行中与完成时会通知你，可直接在通知里查看并回复` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:49` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `会话气泡` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:68` | 标题 | SettingsCapabilityUi | 生产可达 | **保留** |
| `系统允许全部会话使用气泡` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:70` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `由 Android 按会话选择是否显示气泡` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:71` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `系统尚未允许会话气泡，可前往系统设置开启` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:72` | 按钮/操作 | SettingsCapabilityUi | 生产可达 | **保留** |
| `系统未提供通知设置入口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:86` | Toast/提示 | SettingsCapabilityUi | 异常兜底 | **保留** |
| `实时任务状态` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:98` | 标题 | SettingsCapabilityUi | 生产可达 | **保留** |
| `允许系统突出显示运行中的 Agent 任务，具体展示位置由设备决定` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:99` | 标题 | SettingsCapabilityUi | 生产可达 | **修改** |
| `系统未提供通知设置入口` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:110` | Toast/提示 | SettingsCapabilityUi | 异常兜底 | **保留** |
| `未绑定活动配对，授权暂不可用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:128` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:170` | 按钮/操作 | SettingsCapabilityUi | 生产可达 | **保留** |
| `指令目录` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:184` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `新建对话指令` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:185` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `审批卡片` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:186` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `批量发送消息` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:187` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `换行输入` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:188` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `生成中止` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:189` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `会话镜像` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:190` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `会话与文件夹管理` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:191` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `Agent 任务通知` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:241` | 标题 | SettingsCapabilityUi | 生产可达 | **修改** |
| `尚未提供可识别的阻塞原因` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:254` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `本机设置已变化，正在同步最新设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:256` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `连接已变化，等待当前连接重新同步` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:257` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `状态已更新，正在重新读取` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:258` | 正文/说明 | SettingsCapabilityUi | 异常兜底 | **保留** |
| `网络暂不可用，连接恢复后自动重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:259` | 正文/说明 | SettingsCapabilityUi | 异常兜底 | **保留** |
| `无法读取本机授权设置，请检查存储或升级应用后重试` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:262` | 正文/说明 | SettingsCapabilityUi | 异常兜底 | **保留** |
| `已关闭` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:263` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `状态校验未通过，请检查 OAI Core 版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:264` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **修改** |
| `当前阻塞原因` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt:265` | 正文/说明 | SettingsCapabilityUi | 生产可达 | **保留** |
| `本机加密队列暂不可用，请检查设备存储和密钥状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt:80` | 正文/说明 | SettingsScreen | 生产可达 | **保留** |
| `主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt:84` | 正文/说明 | SettingsScreen | 生产可达 | **保留** |
| `通知偏好未能保存，请检查手机存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt:339` | 错误提示 | SettingsViewModel | 异常兜底 | **保留** |
| `信任模式当前已改变，但未能保存；下次启动可能恢复原设置，请检查存储。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt:367` | 错误提示 | SettingsViewModel | 异常兜底 | **保留** |
| `信任模式当前已关闭，但关闭状态未能保存，请检查存储后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt:376` | 错误提示 | SettingsViewModel | 异常兜底 | **保留** |
| `本次紧急停止已生效，但信任模式关闭状态未能保存；重启前请检查存储并重新确认关闭状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt:387` | 错误提示 | SettingsViewModel | 异常兜底 | **保留** |
| `Agent 当前尚未就绪，请稍后再选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt:419` | 错误提示 | SettingsViewModel | 异常兜底 | **保留** |
| `未收到可读取的文件；文字或链接请复制后粘贴到输入框` | `app/src/main/kotlin/com/openandroidintelligence/mobile/SharedConversationAttachments.kt:38` | Toast/提示 | SharedConversationAttachments | 生产可达 | **保留** |
| `无法创建新会话，请检查当前连接` | `app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt:98` | 正文/说明 | AssistantBridgeService | 生产可达 | **保留** |
| `操作未完成，请检查当前连接或对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt:146` | 错误提示 | AssistantBridgeService | 异常兜底 | **保留** |
| `屏幕附件无法读取，请重新圈选` | `app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt:156` | 错误提示 | AssistantBridgeService | 异常兜底 | **保留** |
| `语音附件无法读取，请重新录制` | `app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt:190` | 错误提示 | AssistantBridgeService | 异常兜底 | **保留** |
| `语音未能发送，已放入附件草稿` | `app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt:208` | 正文/说明 | AssistantBridgeService | 生产可达 | **保留** |
| `新对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt:834` | 标题 | CoreAgentConversationRepository | 生产可达 | **保留** |
| `新对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt:907` | 正文/说明 | CoreAgentConversationRepository | 生产可达 | **保留** |
| `新对话` | `app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt:231` | 正文/说明 | EncryptedConversationMirror | 生产可达 | **保留** |

### 6.12 对话界面 - 助理与其他 (45 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `\n\n…[内容已截断]…\n\n` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantBridgeProtocol.kt:36` | 正文/说明 | AssistantBridgeProtocol | 生产可达 | **保留** |
| `智能助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:121` | 标题 | AssistantView | 生产可达 | **保留** |
| `当前会话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:122` | 标题 | AssistantView | 生产可达 | **保留** |
| `智能助理，已展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:439` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `智能助理，已收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:439` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `已展开` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:440` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `已收起` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:440` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `展开助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:443` | 按钮/操作 | AssistantView | 生产可达 | **保留** |
| `展开助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:449` | 正文/说明 | AssistantView | 生产可达 | **保留** |
| `收起助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:457` | 正文/说明 | AssistantView | 生产可达 | **保留** |
| `移动到左侧` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:465` | 正文/说明 | AssistantView | 生产可达 | **保留** |
| `移动到右侧` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:470` | 正文/说明 | AssistantView | 生产可达 | **保留** |
| `结束助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:475` | 正文/说明 | AssistantView | 生产可达 | **保留** |
| `圈选` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:641` | 按钮/操作 | AssistantView | 生产可达 | **保留** |
| `收起助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:646` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `结束助理` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt:652` | 无障碍辅助 | AssistantView | 生产可达 | **保留** |
| `CONVERSATION_BUSY:请等待当前发送和回复结束` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:1251` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `SEND_OUTCOME_UNKNOWN:点击发送可查询并重试原消息` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:1583` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `SEND_OUTCOME_UNKNOWN:请确认并查询待处理批次` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:1583` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件，请先移除附件或发送后再添加` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:2651` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `OFFLINE_MIRROR:重新连接后可发送，草稿已保留` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3023` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `LOCAL_HISTORY_LOADING:正在恢复本地会话，草稿已保留` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3030` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `ATTACHMENT_NOT_VERIFIED:请重新选择附件` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3140` | Toast/提示 | WorkbenchController | 异常兜底 | **保留** |
| `[附件]` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3199` | 状态/空页面 | WorkbenchController | 生产可达 | **保留** |
| `新对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3247` | 标题 | WorkbenchController | 生产可达 | **保留** |
| `新对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3253` | 标题 | WorkbenchController | 生产可达 | **保留** |
| `SEND_OUTCOME_UNKNOWN:原批次含已删除消息，只能查询其余成员，禁止重发` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3843` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `SEND_OUTCOME_UNKNOWN:原批次已有受理或删除事实，请重新查询确认` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:3860` | Toast/提示 | WorkbenchController | 生产可达 | **保留** |
| `；上次中断的下载尚未恢复，请稍后重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:4243` | Toast/提示 | WorkbenchController | 异常兜底 | **保留** |
| `已保存到下载/OpenAndroidIntelligence：${result.filename}$recoveryNotice` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:4244` | Toast/提示 | WorkbenchController | 异常兜底 | **保留** |
| `[附件]` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:5323` | 状态/空页面 | WorkbenchController | 生产可达 | **保留** |
| `新对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:5732` | 正文/说明 | WorkbenchController | 生产可达 | **保留** |
| `已创建新对话` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:5927` | 正文/说明 | WorkbenchController | 生产可达 | **保留** |
| `炽橙` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:38` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `OAI 标志性品牌橙色，与应用图标同款配色` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:38` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `靛蓝` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:41` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `沉稳的蓝紫，适合长时间阅读` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:41` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `青碧` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:44` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `清透的青蓝，界面更轻盈` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:44` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `紫罗兰` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:47` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `鲜明的紫罗兰，强调动作更醒目` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:47` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `暖橙` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:50` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `温暖的琥珀橙，消息氛围更亲切` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:50` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `石墨` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:53` | 正文/说明 | AppPalettes | 生产可达 | **保留** |
| `近乎中性的单色，最安静克制` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt:53` | 正文/说明 | AppPalettes | 生产可达 | **保留** |

### 6.13 数字助手 (42 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `当前入口没有系统截图。把本 App 设为默认数字助理后，才能在其他 App 之上圈选屏幕。` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantActivity.kt:120` | 正文/说明 | AssistantActivity | 生产可达 | **保留** |
| `助理服务无法连接，请打开 App` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:91` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `助理连接已中断，请重新打开助理` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:103` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `助理服务无法连接，请打开 App` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:122` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `草稿超过 $MAX_ASSISTANT_DRAFT_CHARS 字，超出部分不会同步` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:201` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `屏幕附件无法编码，请重新圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:261` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `圈选图片过大，请缩小范围` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:266` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `屏幕附件无法读取，请重新圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:271` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `连接已中断，请重新打开助理` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantBridgeClient.kt:377` | 正文/说明 | AssistantBridgeClient | 生产可达 | **保留** |
| `输入栏已展开` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:309` | 正文/说明 | AssistantCompactBar | 生产可达 | **保留** |
| `助理消息输入` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:392` | 输入占位/表单 | AssistantCompactBar | 生产可达 | **保留** |
| `输入消息` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:399` | 正文/说明 | AssistantCompactBar | 生产可达 | **保留** |
| `助理尚不可用` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:399` | 正文/说明 | AssistantCompactBar | 生产可达 | **保留** |
| `语音输入` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:411` | 按钮/操作 | AssistantCompactBar | 生产可达 | **保留** |
| `屏幕圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:417` | 按钮/操作 | AssistantCompactBar | 生产可达 | **保留** |
| `正在停止生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:425` | 无障碍辅助 | AssistantCompactBar | 生产可达 | **保留** |
| `停止生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:426` | 正文/说明 | AssistantCompactBar | 生产可达 | **保留** |
| `发送消息` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:427` | 正文/说明 | AssistantCompactBar | 生产可达 | **保留** |
| `附件：${attachment.filename}` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:469` | 无障碍辅助 | AssistantCompactBar | 生产可达 | **保留** |
| `移除附件：${attachment.filename}` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantCompactBar.kt:509` | 无障碍辅助 | AssistantCompactBar | 生产可达 | **保留** |
| `关闭提示` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantConversationBody.kt:62` | 按钮/操作 | AssistantConversationBody | 生产可达 | **保留** |
| `回到最新` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantExpandedPanel.kt:382` | 正文/说明 | AssistantExpandedPanel | 生产可达 | **保留** |
| `选区太小，请重新圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionContent.kt:145` | 正文/说明 | AssistantSessionContent | 生产可达 | **保留** |
| `需要麦克风权限，请在系统设置中允许后重试` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionContent.kt:185` | 正文/说明 | AssistantSessionContent | 生产可达 | **保留** |
| `还没有消息` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:364` | 正文/说明 | AssistantSessionRoot | 生产可达 | **保留** |
| `这里会显示当前对话的真实消息，输入内容后发送即可开始。` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:366` | 正文/说明 | AssistantSessionRoot | 生产可达 | **保留** |
| `助理服务未连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:371` | 正文/说明 | AssistantSessionRoot | 生产可达 | **保留** |
| `助理进程无法访问主进程的对话状态。请先打开 App，再从系统助理重新唤起。` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:373` | 正文/说明 | AssistantSessionRoot | 生产可达 | **修改** |
| `打开 App` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionRoot.kt:377` | 按钮/操作 | AssistantSessionRoot | 生产可达 | **保留** |
| `系统没有提供截图，请重新唤起助理后再圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt:255` | 正文/说明 | AssistantSessionService | 生产可达 | **保留** |
| `圈选图片无法读取，请重新圈选` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt:284` | 正文/说明 | AssistantSessionService | 生产可达 | **保留** |
| `无法打开 App，请从启动器打开` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt:300` | 正文/说明 | AssistantSessionService | 生产可达 | **保留** |
| `助理服务未连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:140` | 正文/说明 | AssistantState | 生产可达 | **保留** |
| `Gateway 未连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:141` | 正文/说明 | AssistantState | 生产可达 | **修改** |
| `Gateway 已连接 · 正在停止生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:142` | 正文/说明 | AssistantState | 生产可达 | **修改** |
| `Gateway 已连接 · 正在生成` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:143` | 正文/说明 | AssistantState | 生产可达 | **修改** |
| `Gateway 已连接` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:144` | 正文/说明 | AssistantState | 生产可达 | **修改** |
| `助理服务无法连接，请打开 App 后重试` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:149` | 正文/说明 | AssistantState | 生产可达 | **保留** |
| `请先在 App 中连接 Gateway` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:150` | 正文/说明 | AssistantState | 生产可达 | **修改** |
| `上一条消息结果未知，请先在 App 中确认` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantState.kt:151` | 正文/说明 | AssistantState | 生产可达 | **保留** |
| `录音无法读取，请重新录制` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantVoiceDelivery.kt:48` | 状态/空页面 | AssistantVoiceDelivery | 生产可达 | **保留** |
| `录音无法读取，请重新录制` | `assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantVoiceDelivery.kt:52` | 正文/说明 | AssistantVoiceDelivery | 生产可达 | **保留** |

### 6.14 应用更新 (37 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `未找到可打开发布页的浏览器，请确认系统浏览器已安装。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:117` | Toast/提示 | AppUpdateScreen | 生产可达 | **保留** |
| `应用更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:125` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `当前版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:141` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `更新渠道` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:146` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `正式版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:162` | 系统通知 | AppUpdateScreen | 生产可达 | **保留** |
| `预览版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:162` | 系统通知 | AppUpdateScreen | 生产可达 | **保留** |
| `正式版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:176` | 系统通知 | AppUpdateScreen | 生产可达 | **保留** |
| `预览版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:176` | 系统通知 | AppUpdateScreen | 生产可达 | **保留** |
| `仅检查已发布的正式版本。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:184` | 系统通知 | AppUpdateScreen | 生产可达 | **保留** |
| `跟踪开发预览测试版和带版本号的测试版（如 Beta、RC），也会提示后续可升级的正式版。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:186` | 正文/说明 | AppUpdateScreen | 生产可达 | **保留** |
| `正在检查更新…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:192` | 按钮/操作 | AppUpdateScreen | 生产可达 | **保留** |
| `检查更新` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:192` | 按钮/操作 | AppUpdateScreen | 生产可达 | **保留** |
| `正在获取发布信息，下方保留上次结果…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:199` | 正文/说明 | AppUpdateScreen | 生产可达 | **保留** |
| `更新渠道未能保存，下次打开时可能恢复原设置。请重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:206` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `查看所有发布` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:221` | 按钮/操作 | AppUpdateScreen | 生产可达 | **保留** |
| `更新信息来自本项目的 GitHub 发布页，打开此页面会自动检查，也可手动重查。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:224` | 正文/说明 | AppUpdateScreen | 生产可达 | **保留** |
| `发现新版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:244` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `此版本的签名与当前安装不同或无法核对，不能确认可直接覆盖安装。请先查看发行说明中的迁移安排。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:257` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `前往发布页` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:263` | 按钮/操作 | AppUpdateScreen | 生产可达 | **保留** |
| `暂无可升级版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:267` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `此渠道最新可检测版本为 ${result.latestVersionName}，当前安装无需升级。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:268` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `尚未发布正式版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:271` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `尚未发布预览版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:271` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `发布后可在这里检查更新。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:272` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `暂无法检查此版本` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:275` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `当前分发渠道请通过原安装来源更新。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:277` | 正文/说明 | AppUpdateScreen | 生产可达 | **保留** |
| `发布版本尚未提供可验证的更新信息，请查看发布页。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:278` | 正文/说明 | AppUpdateScreen | 生产可达 | **保留** |
| `检查更新失败` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:282` | 标题 | AppUpdateScreen | 生产可达 | **保留** |
| `网络连接失败，请检查网络后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:285` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `更新服务的访问次数已达上限，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:286` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `更新服务暂时不可用，请稍后重试。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:287` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `发布信息不完整或无法验证，请稍后重试或查看发布页。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:288` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `预览发布列表过长，未能完整检查；请稍后重试或查看全部发布。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateScreen.kt:289` | 错误提示 | AppUpdateScreen | 异常兜底 | **保留** |
| `正式版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateViewModel.kt:112` | 系统通知 | AppUpdateViewModel | 生产可达 | **保留** |
| `开发预览测试版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateViewModel.kt:113` | 系统通知 | AppUpdateViewModel | 生产可达 | **保留** |
| `版本号测试版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateViewModel.kt:114` | 系统通知 | AppUpdateViewModel | 生产可达 | **保留** |
| `开发预览测试版` | `app/src/main/kotlin/com/openandroidintelligence/mobile/updates/AppUpdateViewModel.kt:115` | 系统通知 | AppUpdateViewModel | 生产可达 | **保留** |

### 6.15 对话界面 - 屏幕选区/截图 (31 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `屏幕圈选` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:120` | 无障碍辅助 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `当前选区 ${(bounds.width / density.density).roundToInt()} × ` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:473` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `尚未选择区域` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:475` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `自由圈选` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:516` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `切换矩形选区` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:516` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `正在截取屏幕…` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:545` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `取得这一帧之后才能圈选，请稍候。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:547` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:555` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `当前内容不允许共享` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:583` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `系统没有提供可用的 Assist 截图，无法进行屏幕圈选。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:585` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:593` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `沿目标边缘描绘自由选区` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:648` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `拖动选择要分享的区域` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:649` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `调整选区边界后确认` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:650` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `松手后可继续确认；自由圈选只保留路径内的像素。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:657` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `松手后仍可调整，确认后才会加入当前对话。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:658` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `拖动角柄或边柄调整，也可用下方四个边界滑块精确调整。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:659` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `左边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:673` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `上边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:683` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `右边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:698` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `下边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:708` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `左边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:721` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `右边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:729` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `上边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:737` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `下边界` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:745` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `选区至少需要 ${Dimensions.SpaceLarge.value.roundToInt()}dp，` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:757` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `并且必须位于截图范围内。` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:758` | 错误提示 | ScreenSelectionOverlay | 异常兜底 | **保留** |
| `取消` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:773` | 按钮/操作 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `确认选区` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:782` | 正文/说明 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `$label，可调节` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:812` | 无障碍辅助 | ScreenSelectionOverlay | 生产可达 | **保留** |
| `${value.roundToInt()}像素` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/selection/ScreenSelectionOverlay.kt:813` | 无障碍辅助 | ScreenSelectionOverlay | 生产可达 | **保留** |

### 6.16 主动推送策略 (30 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `无法读取本机推送配置，请检查存储状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:77` | 错误提示 | PushSettingsScreen | 异常兜底 | **保留** |
| `返回` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:82` | 按钮/操作 | PushSettingsScreen | 异常兜底 | **保留** |
| `正在读取推送设置` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:83` | 按钮/操作 | PushSettingsScreen | 异常兜底 | **保留** |
| `推送配置已在其他位置修改，请重新载入已保存选择后再保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:107` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `推送配置保存失败，尚未应用本次修改。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:108` | 错误提示 | PushSettingsScreen | 异常兜底 | **保留** |
| `无法保存：范围或容量无效，容量不能小于当前队列用量。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:110` | 错误提示 | PushSettingsScreen | 异常兜底 | **保留** |
| `推送配置保存失败，尚未应用本次修改。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:111` | 错误提示 | PushSettingsScreen | 异常兜底 | **保留** |
| `无法读取当前队列状态。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:118` | 错误提示 | PushSettingsScreen | 异常兜底 | **保留** |
| `返回` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:170` | 按钮/操作 | PushSettingsScreen | 异常兜底 | **保留** |
| `主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:211` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `推送默认关闭。开启后只推送你选中的应用，并只投递到你选定的接收设备。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:212` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `通知主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:215` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `推送通知正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:230` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:274` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `推送默认关闭，与被动读取范围互不影响。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:275` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `短信主动推送` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:278` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `保存短信范围` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:302` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `推送短信正文` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:303` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `正文默认包含；关闭正文开关后仅推送元数据。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:309` | 按钮/操作 | PushSettingsScreen | 生产可达 | **保留** |
| `关闭推送、缩小范围或移除接收设备，会立即清除不再允许的待发内容。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:310` | 按钮/操作 | PushSettingsScreen | 生产可达 | **保留** |
| `刷新推送目标` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:311` | 按钮/操作 | PushSettingsScreen | 生产可达 | **保留** |
| `推送目标（可多选）` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:331` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `尚无已验证的接收设备。连接 Core 并刷新后选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:334` | 状态/空页面 | PushSettingsScreen | 生产可达 | **修改** |
| `${key.coreInstanceId} / ${key.accountScope}\n${key.endpointId} · 第 ${key.gene…` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:345` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `接收端身份已更新，原选择和待发记录已保留。请手动重选；旧记录不会转给新接收端。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:349` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `当前接收目标不可用，原选择和待发记录已保留，恢复后重试；可手动取消选择。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:350` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `已选择 ${packageIds.size} 个应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:417` | 按钮/操作 | PushSettingsScreen | 生产可达 | **保留** |
| `仅收集以上应用；留空时不采集。此范围不修改被动读取设置。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:418` | 按钮/操作 | PushSettingsScreen | 生产可达 | **保留** |
| `选择应用` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:427` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |
| `应用范围已在其他位置修改，请重新载入已保存选择后再保存。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt:447` | 正文/说明 | PushSettingsScreen | 生产可达 | **保留** |

### 6.17 短信作用域与过滤 (14 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `全部` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:36` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `关键词` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:37` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `电话号码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:38` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `关键词` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:57` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `电话号码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:57` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `请至少填写一个关键词。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:60` | 状态/空页面 | SmsScopeSelector | 生产可达 | **保留** |
| `请至少填写一个电话号码。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:60` | 状态/空页面 | SmsScopeSelector | 生产可达 | **保留** |
| `最多填写 256 项，每项不超过 256 个字符。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:61` | 状态/空页面 | SmsScopeSelector | 生产可达 | **保留** |
| `用逗号或换行分隔；短信内容匹配任一关键词即可。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:62` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `用逗号或换行分隔；匹配任一发件号码即可。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:63` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `关键词在本机匹配，正文开关决定是否提供正文。` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:76` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `短信范围：全部短信` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:83` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `短信范围：匹配 ${parseSmsScopeEntries(keywords).size} 个关键词` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:84` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |
| `短信范围：清单中的 ${parseSmsScopeEntries(phoneNumbers).size} 个电话号码` | `app/src/main/kotlin/com/openandroidintelligence/mobile/sms/SmsScopeSelector.kt:85` | 正文/说明 | SmsScopeSelector | 生产可达 | **保留** |

### 6.18 对话界面 - 语音交互 (9 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `录音波形` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/RecordingWaveform.kt:132` | 无障碍辅助 | RecordingWaveform | 生产可达 | **保留** |
| `当前没有可发送的录音` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecorder.kt:70` | 正文/说明 | VoiceRecorder | 生产可达 | **保留** |
| `语音正在发送，请稍候` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecorder.kt:71` | 正文/说明 | VoiceRecorder | 异常兜底 | **保留** |
| `会话已切换，录音未发送` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecorder.kt:72` | 正文/说明 | VoiceRecorder | 异常兜底 | **保留** |
| `录音失败，请重试` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecorder.kt:73` | 正文/说明 | VoiceRecorder | 异常兜底 | **保留** |
| `取消录音` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecordingRow.kt:76` | 按钮/操作 | VoiceRecordingRow | 生产可达 | **保留** |
| `正在录音` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecordingRow.kt:84` | 无障碍辅助 | VoiceRecordingRow | 生产可达 | **保留** |
| `已录制 $totalSeconds 秒` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecordingRow.kt:85` | 无障碍辅助 | VoiceRecordingRow | 生产可达 | **保留** |
| `发送语音` | `conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/voice/VoiceRecordingRow.kt:153` | 无障碍辅助 | VoiceRecordingRow | 生产可达 | **保留** |

### 6.19 会话领域与数据层 (6 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `新对话` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:18` | 正文/说明 | ConversationTitlePolicy | 生产可达 | **保留** |
| `语音消息` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:26` | 正文/说明 | ConversationTitlePolicy | 生产可达 | **保留** |
| `屏幕选区` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:27` | 正文/说明 | ConversationTitlePolicy | 生产可达 | **保留** |
| `附件内容` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:31` | 状态/空页面 | ConversationTitlePolicy | 生产可达 | **保留** |
| `新对话` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:32` | 状态/空页面 | ConversationTitlePolicy | 生产可达 | **保留** |
| `新对话` | `conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt:36` | 正文/说明 | ConversationTitlePolicy | 生产可达 | **保留** |

### 6.20 设备能力前台服务 (4 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `屏幕圈选采集` | `capability-ports/src/main/kotlin/com/openandroidintelligence/capability/MediaProjectionCaptureService.kt:63` | 系统通知 | MediaProjectionCaptureService | 生产可达 | **保留** |
| `屏幕圈选依赖系统投屏授权，采集期间显示此常驻通知。` | `capability-ports/src/main/kotlin/com/openandroidintelligence/capability/MediaProjectionCaptureService.kt:64` | 系统通知 | MediaProjectionCaptureService | 生产可达 | **保留** |
| `屏幕圈选采集中` | `capability-ports/src/main/kotlin/com/openandroidintelligence/capability/MediaProjectionCaptureService.kt:71` | 标题 | MediaProjectionCaptureService | 生产可达 | **保留** |
| `正在使用你授予的系统投屏权限采集屏幕截图。` | `capability-ports/src/main/kotlin/com/openandroidintelligence/capability/MediaProjectionCaptureService.kt:72` | 标题 | MediaProjectionCaptureService | 生产可达 | **保留** |

### 6.21 会话领域与数据层 (3 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `新对话` | `conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt:194` | 标题 | GatewayConversationRepository | 生产可达 | **保留** |
| `新对话` | `conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt:278` | 标题 | GatewayConversationRepository | 生产可达 | **保留** |
| `新对话` | `conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt:436` | 标题 | GatewayConversationRepository | 生产可达 | **保留** |

### 6.22 系统清单与组件 (1 条)

| 原始文案 | 源码位置 | 文案类型 | 使用场景 / 触发条件 | 可达性 | 处置判定 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Agent 会话` | `app/src/main/AndroidManifest.xml:93` | 标题 | 应用清单声明 | 生产可达 | **修改** |

---

## 七、工程化缺陷与架构整改建议

本次全量文案审计不仅暴露了文字表述问题，更揭示了客户端架构在文案管理与国际化基础设施层面的重大工程化隐患：

1. **全仓零资源化与硬编码治理（创建 strings.xml）**：
   - 全仓 Android XML 资源中**完全不存在任何 `strings.xml`**。所有 2,095 条生产中文文案全部直接以硬编码字面量写入 Kotlin / Jetpack Compose 源码中；
   - **架构危害**：彻底丧失国际化（i18n）与多语言切换能力；相同文本（如「新对话」、「重试」、「设置」、「取消」）在几十个文件中重复硬编码，极易造成修改遗漏与二次术语冲突；
   - **整改规划**：后续开发重构必须建立标准的 `res/values/strings.xml`（中文）与 `res/values-en/strings.xml`（英文），按业务领域全量迁移为 `stringResource(R.string.xxx)`。

2. **底层异常穿透与领域层映射（Domain 层 UserFacingMessage 异常拦截器）**：
   - 服务层与运行时（如 `GatewayRuntime.kt`、`SettingsBackupCoordinator.kt`）直接构造包含技术细节的异常信息，未经过 Domain 层的用户化映射，直接通过 `_operationNotice` 或 `Snackbar` 抛到用户界面；
   - **整改规划**：必须在 `domain` 模块设计 `UserFacingMessage` 转换器，严格拦截所有底层协议码和原始 Exception 信息，统一转换为通俗简明的故障提示与恢复操作。

3. **字符串模板拼接与语法断裂防范**：
   - 源码中存在大量动态内嵌拼接语句（如 `"已提供${...}，可在技术详情核对"`、`"这次操作需要在手机上的确认"`），在变量为空或边界变化时，常常产生语法残缺或语病；
   - **整改规划**：利用 Android 官方 XML 的 `%1$s` 占位符机制配合格式化资源定义，保证语法结构完整。

4. **引入静态代码门禁（Lint 规则）**：
   - 在 Gradle 构建流水线与 CI 门禁中开启 `HardcodedText` 规则，严禁后续在 Kotlin/Compose 代码中直接编写裸中文字符串，从源头杜绝硬编码再生。

---

## 八、验证缺口与后续建议 (Remaining Questions & Gaps)

1. **服务端动态下发文案的本地化防护缺口**：
   - 本次审计完成了 Android 客户端本地代码的 100% 静态提取。但部分运行时动态内容（如 Core 服务端返回的 Agent 自定义名称、插件工具调用返回的错误 payload、网关动态下发的 error message）由远端服务器生成。若服务端下发带英文或底层架构术语的文本，客户端目前缺乏本地化防卫与清洗层；
2. **低频硬件故障与特定系统分支的呈现效果**：
   - 涉及 Android Keystore 硬件加密芯片损坏、跨进程 Binder 意外断链、本地 AES-GCM 数据库并发写冲突等低频异常文案已在静态代码中收录，但其在真机上的实际视觉展示效果（如通知折叠是否截断、是否被系统状态栏遮挡）需在后续真机自动化测试中核验；
3. **后续整改推进路线图 (Next Steps)**：
   - **阶段一（高频高感知收敛）**：优先处理 84 条 P0 级文案（系统通知、气泡悬浮窗、登录界面、核心错误），彻底消除外溢的 `Gateway`、`Agent`、`Core` 名词；
   - **阶段二（全仓资源化迁移）**：在模块中引入 `res/values/strings.xml`，将 256 条修改文案与 1,839 条保留文案逐步结构化迁移至资源文件；
   - **阶段三（异常映射层建设）**：在 Domain 层实现统一的异常转换管道，隔绝底层网络与加密协议细节。