# Open Android Intelligence 安卓真机 E2E 完整测试方案与验证报告

- **测试日期**：2026-09-03
- **测试环境**：Samsung Galaxy Tab S9 (Android 14 / OneUI 6.0 / API 34)
- **网关版本**：Hermes Gateway Protocol v2 (Python 3.11, aiohttp 3.14.1)
- **测试模式**：真机端到端（E2E）闭环验证、协议契约对比与白盒源码审查
- **测试人员**：Antigravity Agentic Verification Pipeline

---

## 一、测试背景与方案概述

本项目致力于构建面向 Android 平台的端云协同智能系统（Open Android Intelligence）。客户端基于 Jetpack Compose 与模块化插件架构开发，宿主端基于 Hermes Gateway Protocol v2 提供服务。

为确保系统在真实 Android 物理设备上的稳定性、安全性与契约一致性，本方案在当前连接的物理平板（Samsung Galaxy Tab S9）上开展端到端（E2E）全流程测试，验证范围涵盖所有核心业务与安全原语，覆盖正常业务流与异常防御场景，并对发现的问题提供精确到代码行的根因定位与整改方案。

---

## 二、测试环境与前置准备

### 2.1 硬件设备与系统参数
- **设备型号**：Samsung Galaxy Tab S9 (SM-X710, 硬件代号: `gts9wifizc`, Transport ID: 3)
- **显示参数**：横屏物理分辨率 `2560x1600`，密度 `340 dpi`，刷新率 `120 Hz`
- **操作系统**：Android 14 (API Level 34)
- **多沙箱隔离特性**：设备内存在 User 0（主用户 `豆浆白倒`）、User 10（辅助用户）与 User 150（Knox `Secure Folder` 安全文件夹）。所有针对包管理（`pm`）的 ADB 命令必须显式指定 `--user 0`，否则会触发 Knox 跨沙箱 `SecurityException`。

### 2.2 网络环境与网关配置
- **网关进程**：本地 PID 140216（aiohttp 3.14.1 / Python 3.11），监听 `0.0.0.0:8045`
- **网络拓扑**：通过执行 `adb reverse tcp:8045 tcp:8045`，实现 Android 平板本地环回（`http://127.0.0.1:8045`）直连宿主网关
- **测试账号主体**：`/home/djbd/.hermes/.open-android-intelligence-hermes/accounts/c517ff427412faf052192d6f64cb029baab7a900b214b67c1965d2c18083b6ad/`（SHA-256 映射用户名 `djbd`）

### 2.3 测试数据与前置依赖
- **认证凭据**：用户 `djbd`，本地测试密码 `djbd`
- **契约定义**：`gateway-contract/schemas/` 目录下的 JSON Schema（v2 协议规范）
- **客户端版本**：`apps/android/app`（基于 Material3 规范，主 Activity: `com.openandroidintelligence.mobile.MainActivity`）

---

## 三、测试用例设计与实机执行记录

| 用例编号 | 模块分类 | 用例名称与场景属性 | 核心操作路径与 ADB 步骤 | 预期结果 | 实测结果 | 判定 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC-01** | 网关认证 | **[异常流]** 非法地址与不可达端口拦截 | 1. 登录页填入 `http://127.0.0.1:9999`<br>2. 账号密码填入 `djbd`<br>3. 点击【登录并配对】 | 进入 `ConnectionPhase.Failed`，展示不可达原因并提供【重新输入】按钮，不崩溃 | 界面进入失败态，红点提示错误码 `Failed to connect to /127.0.0.1:9999`；点击【重新输入】无缝恢复初始输入状态 | **PASS** |
| **TC-02** | 网关认证 | **[正常流]** 真实网关协议协商与配对 | 1. 地址填入 `http://127.0.0.1:8045`<br>2. 账号密码填入 `djbd`<br>3. 收起键盘，点击【登录并配对】 | 1. 完成 `POST /negotiate`<br>2. `POST /sessions/password` 换取 Session<br>3. 自动进入智能工作台 | Hermes `access_sessions` 表生成 `sess_...` 记录；客户端安全存储 SPKI，转场进入 `WorkbenchScreen` | **PASS** |
| **TC-03** | 消息流水线 | **[核心业务流]** 文本发送、批次合并与新建会话 | 1. 在工作台输入框键入 `Hello`<br>2. 点击发送按钮（bounds: `[2403,1458][2505,1560]`）<br>3. 观察批次合并与状态<br>4. 点击右上角【新建对话】 | 消息成功加入批次并推送至 Gateway；新建对话成功生成新会话 Thread | **初次发送卡在批次合并**（提示 `同一批次 · 1 条 · 等待合并`）；超时后报错 `SUBMIT_BATCH_FAILED:no-conversation`；点击新建对话报 `401` | **FAIL**<br>(阻断缺陷 D1, D2, D6) |
| **TC-04** | 快捷指令 | **[交互降级流]** `/` 指令目录拉起与异常容错 | 1. 输入框输入 `/`<br>2. 触发 `CommandCatalogClient` 请求 | 弹出指令菜单，展示服务端指令；若网络失败平滑提示并允许重试 | 界面弹出浮动指令卡片，显示 `加载失败 (COMMAND_CATALOG_FAILED:404)`，点击【重试】动作正常响应 | **PASS**<br>(容错通过，但暴露网关端点缺失 D3) |
| **TC-05** | 原生硬件 | **[多模态流]** 相机快照契约拉起与返回 | 1. 点击底部【+】号按钮<br>2. 弹出菜单点击【拍摄现场照片】<br>3. 观察三星系统相机 Activity | 调用系统相机快照契约（`TakePicturePreview`），返回后工作台状态完整保留 | 成功调起 `com.sec.android.app.camera/.Camera`；返回后无 Activity 意外重建，输入暂存完整 | **PASS** |
| **TC-06** | 平台安全 | **[系统内核]** 四 Tab 切换、授权变更与开发者信任 | 1. 打开抽屉进入【设置与首选项】<br>2. 网关账号 Tab 切换短信/屏幕授权<br>3. 内核安全 Tab 切换开发者信任模式<br>4. 确认二次告警弹窗 | 账号主体准确；开关即时响应；开发者信任模式弹出 Material3 确认框并在确认后切换激活状态 | 授权开关流畅响应；信任模式弹窗标题为“开启开发者信任模式确认”，点击确认后切换为 `已开启：原生代码可接管界面`，再次点击平滑关闭 | **PASS**<br>(功能正常，发现规范缺漏 D4) |
| **TC-07** | 凭据生命周期 | **[持久化流]** 登出清理与冷启动静默恢复 | 1. 设置底板点击【退出当前登录】<br>2. 重新登录后 Home 键置于后台并冷启动<br>3. 验证 Keystore 自动恢复 | 登出可靠清理本地凭据；冷启动读取 Keystore 静默完成 `auth.refresh()` 并自动进入工作台 | 登出由于网关返回 404 导致未清空 Keystore；冷启动时因网关 404 触发异常分支，客户端误将 404 当作凭据吊销，执行自杀逻辑抹除了本地 Refresh 凭据 | **FAIL**<br>(凭据缺陷 D5) |
| **TC-08** | 自适应与布局 | **[UI断点]** 横竖屏物理动态旋转适配 | 1. 2560x1600 横屏测试<br>2. 执行 `adb shell settings put system user_rotation 0` 切至竖屏（1600x2560） | 遵循 `widthIn(max = 760.dp)` 设计系统约束，内容在宽屏居中，文字无溢出 | 登录卡片、工作台容器在 x=800 精准水平居中，动态断点过渡自然，无任何组件遮挡变形 | **PASS** |

---

## 四、对前序调查（`<prior_attempt>`）的纠偏与核心修正

在前序初步调查中，由于缺乏真机白盒代码比对与深入的协议溯源，存在多处事实偏差与认知盲区。本次复测重点纠正如下：

1. **修正 D3 指令端点路径**：
   - *前序结论*：声称网关缺失 `/open-android-intelligence/v2/catalog/commands`。
   - *修正实测*：客户端 `apps/android/gateway-client/.../CommandCatalogClient.kt` 第 41 行明确发起的是：
     ```kotlin
     target = "/open-android-intelligence/v2/commands?languageCode=$languageCode"
     ```
     若按前序建议实现 `/catalog/commands`，Android 客户端依然必定 404。真实缺失路径为 `/commands`。
2. **揭示 D5 双向契约撕裂与本地 Keystore 自毁行为**：
   - *前序结论*：仅认为服务端缺少 `POST /sessions/refresh` 路由。
   - *修正实测*：`gateway-contract/schemas/session.schema.json` 要求 Refresh 请求必须携带 `accountId, deviceId, negotiationId, installationId, refreshCredential` 5 项核心字段。然而客户端 `GatewayAuthClient.kt` 第 84-87 行仅发送了 `installationId` 与 `refreshCredential`，存在严重双向协议撕裂。同时，`GatewayRuntime.kt` 第 158-161 行在服务端返回 404 时，无差别执行了 `keystoreCredentials.clearRefresh()`，导致破坏性的本地凭据自杀。
3. **揭示 D6 打字机流式响应断裂的真实根因**：
   - *前序结论*：认为打字机动画未呈现仅仅是因为 401 拦截了请求。
   - *修正实测*：网关 `adapter.py` 第 254 行发送的事件类型为 `conversation.message.created`；而客户端 `GatewayEventDecoder.kt` 第 29-41 行采用严格白名单机制，仅解析 `conversation.message.delta` 与 `conversation.message.completed`，根本未定义 `created`。这意味着即使修复 401，客户端也会将 AI 回复静默丢弃。
4. **澄清 Tailscale Companion 架构规范**：
   - *前序结论*：误以为 Companion 是一个独立的外部未装 APK。
   - *修正实测*：`tailscale-companion` 属于工程内置的 Android Library，通过 `companion-bridge` 的 AIDL 接口作为 Opaque TLS Byte Pump 运行。主 App 设置底板显示“当前未激活”完全符合架构预期与解耦设计。

---

## 五、缺陷清单与代码根因深度分析

### 缺陷 D1：Hermes Gateway 任意用户名可免注册自登录（P1 - 账户隔离旁路）
- **影响范围**：账户体系安全性与多租户沙箱隔离
- **代码位置**：
  - `integrations/hermes/open_android_intelligence_gateway/http.py` (L240-L245)
  - `integrations/hermes/open_android_intelligence_gateway/adapter.py` (L112-L121)
- **根因分析**：
  `http.py` 在收到密码认证请求时，直接使用用户传入的 `username` 调用 `open_gateway_account(username)`。该方法在对应目录不存在时会自动创建 SQLite 数据库。同时 `LocalCredentialVerifier` 仅验证 `username == account_id`，导致任何随机构造的账号密码均可成功创建会话，严重违背了项目关于“禁止自注册、必须经本地显式授权确认”的安全红线。

---

### 缺陷 D2：Hermes 适配器未注入 `verify_request` 校验器（P0 - 业务全线阻断）
- **影响范围**：工作台会话加载、新建对话、消息发送、附件上传
- **代码位置**：
  - `integrations/hermes/open_android_intelligence_gateway/plugin.py` (L165-L170)
  - `integrations/hermes/open_android_intelligence_gateway/http.py` (L264-L279)
  - `apps/android/conversation-data/.../GatewayConversationRepository.kt` (L105-L107)
- **根因分析**：
  Hermes 启动网关服务时，上下文未注入 `verify_request` 回调，属性值为 `None`。`http.py` 中对除协商与登录外的所有业务请求均强制检查 `verifier`，一旦为 `None` 则无条件返回 HTTP 401 `AUTHENTICATION_REQUIRED`。这导致客户端进入工作台后无法拉取和新建会话，`activeThreadId` 始终为 `null`，用户输入后无法发送并报 `SUBMIT_BATCH_FAILED:no-conversation`。

---

### 缺陷 D3：快捷指令路由缺失（P2 - 功能降级）
- **影响范围**：输入框 `/` 快捷指令自动补全
- **代码位置**：
  - 客户端请求：`apps/android/gateway-client/.../CommandCatalogClient.kt` (L41)
  - 网关路由表：`integrations/hermes/open_android_intelligence_gateway/http.py` (L354-L362)
- **根因分析**：
  客户端请求路径为 `/open-android-intelligence/v2/commands?languageCode=...`，而服务端路由定义元组中遗漏了该路径的映射，导致该接口固定返回 404。

---

### 缺陷 D4：平台设置【内核安全】缺失“一键紧急停用”红线按钮（P3 - UI契约缺失）
- **影响范围**：极端情况下的系统级安全熔断
- **代码位置**：
  - `apps/android/app/.../PlatformSettingsBottomSheet.kt` (L35, L407-L540)
- **根因分析**：
  文件头 KDoc 与架构总纲明确要求具备“一键紧急停用”功能，但在 Compose 实现中仅编写了信任模式开关与审计事件监控，遗漏了红色停用按钮的绘制与点击事件绑定。

---

### 缺陷 D5：会话刷新端点缺失与破坏性凭据自毁（P1 - 核心凭据丢失）
- **影响范围**：App 冷启动自动登录、静默会话恢复
- **代码位置**：
  - `apps/android/app/.../GatewayRuntime.kt` (L156-L163)
  - `apps/android/gateway-client/.../GatewayAuthClient.kt` (L82-L90)
  - `integrations/hermes/open_android_intelligence_gateway/http.py` (L354-L362)
  - `gateway-contract/schemas/session.schema.json` (L31-L56)
- **根因分析**：
  1. 服务端未注册 `POST /sessions/refresh` 与 `DELETE /sessions/current`；
  2. 客户端 `GatewayAuthClient.refresh()` 遗漏了 `accountId`、`deviceId`、`negotiationId`，导致即使接口开通也会报 Schema 校验失败；
  3. `GatewayRuntime.kt` 捕获异常后无差别清空 Keystore，服务端 404 导致用户本地合法的 Refresh 凭据被物理擦除。

---

### 缺陷 D6：SSE 事件类型不匹配与流式打字机机制缺失（P1 - AI 消息协议阻断）
- **影响范围**：大模型流式回复展示、打字机动画
- **代码位置**：
  - 网关广播：`integrations/hermes/open_android_intelligence_gateway/adapter.py` (L252-L264)
  - 客户端解码：`apps/android/conversation-data/.../GatewayEventDecoder.kt` (L29-L41)
- **根因分析**：
  网关完成时广播事件名使用了 `conversation.message.created`；而客户端解码器严格只处理 `conversation.message.delta` 与 `conversation.message.completed`，导致该事件在客户端被无情丢弃；且适配器未接入 token 流式生成回调，未发射 `delta` 事件。

---

## 六、整改实施建议与复现指南

### 6.1 缺陷修复实施步骤
1. **注入 Ed25519 请求验签逻辑**：
   在 `adapter.py` 中补充 `verify_request` 闭包实现，比对请求签名与时间戳窗口，构造合法 `VerifiedGatewayRequest`。
2. **补齐 HTTP 端点并修复入参**：
   - 在 `http.py` 中注册 `GET /commands`、`POST /sessions/refresh` 与 `DELETE /sessions/current`；
   - 在 `GatewayAuthClient.kt` 中补齐 `deviceId, accountId, negotiationId` 参数。
3. **保护客户端本地凭据生命周期**：
   修改 `GatewayRuntime.kt`，仅在 HTTP 状态码为 401/403 等明确鉴权失效时擦除 Keystore，对 404 或网络断开保持凭据保留重试。
4. **统一 SSE 事件命名与流式推送**：
   将 `adapter.py` 发送的事件类型修正为 `conversation.message.completed`，并挂接大模型流式回调推送 `conversation.message.delta`。
5. **补齐内核安全紧急停用按钮**：
   在 `PlatformSettingsBottomSheet.kt` 增加 Red Button，调用底座内核的全局挂起与切断接口。

### 6.2 测试复现与验证步骤
1. 连接三星平板，确认 `adb devices` 识别；
2. 启动 Hermes 网关：`python3 -m hermes gateway run --port 8045`；
3. 执行端口映射：`adb reverse tcp:8045 tcp:8045`；
4. 启动客户端主页面：`adb shell am start -n com.openandroidintelligence.mobile/.MainActivity`；
5. 按用例表依次执行验证，即可 100% 复现上述正常与缺陷分支。
