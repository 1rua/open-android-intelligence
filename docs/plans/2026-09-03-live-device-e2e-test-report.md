# Open Android Intelligence Android 真机 E2E 测试方案与实时验证报告

## 1. 结论先行

本次测试对当前 `main` HEAD `23e88c4` 构建并安装了 `fullDebug` 主 App，在已授权的 Samsung SM-X710 真机上完成了 ADB 驱动的界面、系统选择器、账号边界、安全设置和部分 Android instrumentation 验证。

最终结论不是“发布通过”，而是：**BLOCKED / FAIL**。

- 当前 APK 可以完成本地登录握手并进入工作台，说明安装、启动、ADB reverse 联调、协议协商和密码会话建立路径可运行。
- 当前实际驻留的 Hermes Gateway 进程早于本次源码更新启动，旧进程使用的 SQLite 账号库缺少当前签名校验所需的 `device_keys` 表；业务请求因此连续返回 `401`，会话列表、创建对话、消息发送和附件上传成功闭环均未通过。
- 同一个旧驻留 Gateway 还允许未注册用户名登录并新建账号目录，构成真实安全 FAIL；该结果是对“当前运行部署”的结论，不能直接等同于当前未重启源码的结论。当前源码已经出现对应的未知账号防护分支，但尚未在重启后的同 HEAD Gateway 上重新验收。
- Gateway client 的设备测试发现 `httpsOnlyFactoryRejectsPlainHttp` 失败；当前 App 仍允许 `http://`，设置界面却显示 `TLS Pinned`，存在协议安全语义不一致。
- 已通过的 UI/系统入口只证明对应入口或降级态，不代表真实 Gateway 业务通过。相机、图库、SAF 选择器已拉起；真实附件上传因 Gateway `401` 失败。
- 系统 Assist 当前默认持有者仍是 Google，Open Android Intelligence 只出现在系统选择器中；跨 App Assist、屏幕选区和真实 handoff 因缺少用户选择/本机授权而 BLOCKED。

### 1.1 聚合结果

Android instrumentation 的原始聚合结果如下；`app` 和 `platform-kernel` 因测试源无法编译，没有被计入下表：

| 模块 | 总数 | PASS | FAIL | SKIPPED | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| `companion-bridge` | 4 | 4 | 0 | 0 | PASS |
| `gateway-client` | 14 | 13 | 1 | 0 | FAIL |
| `plugin-package` | 6 | 6 | 0 | 0 | PASS |
| `plugin-runtime-wasm` | 11 | 11 | 0 | 0 | PASS |
| `tailscale-companion` | 1 | 1 | 0 | 0 | PASS |
| `transport` | 5 | 5 | 0 | 0 | PASS |
| `tailnet-core` | 13 | 11 | 1 | 1 | FAIL / 前置缺失 |
| **原始聚合合计** | **54** | **51** | **2** | **1** | **FAIL** |

`tailnet-core` 另外按类过滤重跑了不依赖一次性 bundle 的用例：设备环境 2/2、VPN 表面 3/3、进程死亡恢复 4/4、无可用 enrollment 1/1、坏 bundle 1/1，均 PASS。这些是重复验证，不叠加到上面的 54 项总数。

### 1.2 关键证据入口

全部截图、UI 树、Logcat、JUnit XML、构建日志和 SHA-256 清单在 [`docs/test-evidence/2026-09-03-live`](../../test-evidence/2026-09-03-live/)；推荐先看：

- 启动登录页：[`00-launch.png`](../../test-evidence/2026-09-03-live/00-launch.png)、[`00-launch-ui-summary.txt`](../../test-evidence/2026-09-03-live/00-launch-ui-summary.txt)
- 登录进入工作台：[`01-login-result.png`](../../test-evidence/2026-09-03-live/01-login-result.png)、[`01-login-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/01-login-result-ui-summary.txt)
- 业务 401 与消息批次失败：[`20-message-send-final-ui-summary.txt`](../../test-evidence/2026-09-03-live/20-message-send-final-ui-summary.txt)
- 未注册账号登录结果：[`44-unknown-login-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/44-unknown-login-result-ui-summary.txt)
- 当前 Gateway 进程/数据库基线：[`79-gateway-final-baseline.txt`](../../test-evidence/2026-09-03-live/79-gateway-final-baseline.txt)、[`80-gateway-access-relevant.txt`](../../test-evidence/2026-09-03-live/80-gateway-access-relevant.txt)
- instrumentation 原始结果：[`77-gateway-client-results.xml`](../../test-evidence/2026-09-03-live/77-gateway-client-results.xml)、[`76-tailnet-aggregate-results.xml`](../../test-evidence/2026-09-03-live/76-tailnet-aggregate-results.xml)
- 所有证据文件的 SHA-256：[`93-evidence-manifest.sha256`](../../test-evidence/2026-09-03-live/93-evidence-manifest.sha256)

已有的 [`2026-09-03-e2e-device-testing-verification-report.md`](2026-09-03-e2e-device-testing-verification-report.md) 在本次开始前已经存在，且其中的 API 版本、方向和 PASS 结论与实时设备不一致；本次没有覆盖它，避免破坏既有用户草稿。

## 2. 测试目标与验收口径

本次测试目标是验证“Android Host → 当前本机 Hermes Gateway”的可复现闭环，同时覆盖 App 的核心界面和安全边界。测试判定遵循以下口径：

- **PASS**：实际在目标 Android App 进程或系统 UI 中完成操作，达到预期，并有截图、UI 树或日志证据。
- **FAIL**：实际执行了目标路径，但结果违反产品契约、预期结果或安全约束。
- **BLOCKED**：前置条件、外部服务、用户选择或测试源状态缺失，无法安全地执行，不把缺失当成功。
- **SKIPPED**：测试代码明确以 `Assume` 跳过，或该能力按当前架构不属于主 APK 的可执行范围；不计作 PASS。
- 构建成功只证明 APK 可生成；它不能替代 App 进程、Gateway、真实账号、系统 Assist、文件提供者或物理设备证据。
- 系统选择器入口通过只证明系统 Intent 可拉起；选择、读字节、摘要校验、上传、服务端确认和消息 exactly-once 仍须单独验收。

## 3. 实时测试环境与数据准备

### 3.1 设备基线

| 项目 | 实时值 |
| --- | --- |
| ADB serial | `R52X909R9QT` |
| 制造商 / 型号 | Samsung / `SM-X710` |
| 产品 / device | `gts9wifizc` / `gts9wifi` |
| Android | 16，API 36，Build `BP4A.251205.006` |
| ABI | `arm64-v8a, armeabi-v7a, armeabi` |
| 物理尺寸 | `1600x2560` |
| 本次横屏窗口 | `2560x1600` |
| 物理密度 | `340` |
| 电量 | 收尾时 90%，USB connected |
| ADB 状态 | `device`，已授权 |
| 原始旋转设置 | `accelerometer_rotation=1`、`user_rotation=0` |
| 收尾旋转设置 | 已恢复为 `accelerometer_rotation=1`、`user_rotation=0` |

完整基线见 [`78-device-final-baseline.txt`](../../test-evidence/2026-09-03-live/78-device-final-baseline.txt) 和收尾状态见 [`90-final-package-state.txt`](../../test-evidence/2026-09-03-live/90-final-package-state.txt)。

### 3.2 APK 与包

| 产物 | 包名 | Activity / 入口 | 构建结果 |
| --- | --- | --- | --- |
| 主 App `app-full-debug.apk` | `com.openandroidintelligence.mobile` | `com.openandroidintelligence.mobile/.MainActivity` | PASS |
| Assistant holder `assistant-holder-debug.apk` | `com.openandroidintelligence.assistant` | `com.openandroidintelligence.assistant/.AssistantActivity` | PASS |

两份 APK 均从当前 HEAD 构建并安装；包信息为 `minSdk=34`、`targetSdk=35`。SHA-256 见 [`82-apk-sha256.txt`](../../test-evidence/2026-09-03-live/82-apk-sha256.txt)。

### 3.3 Gateway 与 ADB reverse

本次没有重启 Hermes，因为该进程由本机 user systemd 管理，且可能同时承载其他 Hermes 平台；未经确认重启会扩大测试副作用。实时状态：

- Hermes 进程 PID `140216`，启动时间 `2026-09-03 15:25:30`，工作目录 `/home/djbd/.hermes`。
- 本仓库相关 `core.py`、`http.py`、`adapter.py` 的实时文件修改时间分别为 17:09、16:54、17:06；当前 HEAD 提交时间为 17:32。
- 本机监听 `0.0.0.0:8045`；`GET /health` 返回 200，裸 `GET /open-android-intelligence/v2/commands` 返回 404。
- 测试前执行了：

```text
adb -s R52X909R9QT reverse tcp:8045 tcp:8045
adb -s R52X909R9QT reverse --list
```

实时输出为 `UsbFfs tcp:8045 tcp:8045`。因此 App 填写 `http://127.0.0.1:8045` 时的实际链路是：

```text
Android App
  → Android 设备自己的 127.0.0.1:8045
  → ADB reverse 隧道
  → 本机 127.0.0.1:8045
  → Hermes Gateway
```

如果不执行 `adb reverse`，Android 的 `127.0.0.1` 只指设备自己，不能指向本机 Hermes。这个路径是本地联调证据，不是 Wi-Fi、Tailscale、DERP 或公网 HTTPS 证据。

### 3.4 测试账号与文件

- 已使用本机已有的本地测试账号 `djbd`；密码只在手工输入时使用，不写入报告、脚本或日志。
- 为验证“未注册账号不能登录”，使用合成用户名 `unknown_e2e_20260903`，不应产生任何账号目录。
- 真实附件选择使用设备文件提供者中已有的 `screenshot_clean.png`，约 19 KB；未上传个人新文件。
- 设备上没有安装主 App 之外的短信、通知、通话记录或 Tailscale Companion APK；这些能力按项目架构属于插件/Companion 或 source-only 契约，不伪装成主 APK 功能。
- 系统 Assist 当前角色持有者仍为 `com.google.android.googlequicksearchbox`，没有改变设备默认助理设置。

## 4. 覆盖范围与测试设计

### 4.1 范围矩阵

| 核心模块 | 正常流程 | 异常/安全流程 | 本次范围 |
| --- | --- | --- | --- |
| Android Host 启动 | 安装、冷启动、Activity 解析 | 崩溃缓冲、冷启动失败 | 执行 |
| Gateway 认证/配对 | 协商、密码会话、账号展示 | 非法 scheme、不可达端口、未注册账号、刷新 404、退出/解除配对 404 | 执行 |
| 对话工作台 | 工作台、Tab、抽屉、创建对话、文本批次、命令目录、生成停止 | 401、404、无活动会话、命令重试 | 部分执行，业务成功 BLOCKED/FAIL |
| 附件 | 相机、图库、SAF、选择、上传、验证、消息提交 | 取消、上传 401、重试、移除 | 入口执行，上传成功 BLOCKED |
| 平台内核/安全 | Trust Mode、Pairing Grants、插件空状态、Kill Switch、审计 | 取消确认、撤权、审计更新、不可逆熔断 | 执行，发现 FAIL |
| 传输 | 默认 HTTPS/SSE 展示 | 明文 HTTP、pin 语义、P0t 失败关闭 | 执行/部分 BLOCKED |
| Assistant holder | 显式 `ACTION_ASSIST` 入口 | 无 grant 的输入、默认助理选择器 | 显式入口执行，真实 handoff BLOCKED |
| 短信/通知/通话插件 | 真实设备插件读取/确认/同步 | 无权限、撤权、隔离 | SKIPPED/BLOCKED：未随主 APK 安装，且没有已授权真实插件 |
| Tailscale Companion/P0t | 真实 enrollment、Bridge 443、OFFLINE/DIRECT | bundle 缺失、系统 VPN/路由禁止 | 边界 instrumentation 执行，真实 enrollment BLOCKED |
| 无障碍/性能/减少动态 | TalkBack、动态字号、帧/内存/旋转 | 权限拒绝、Doze、网络切换 | BLOCKED：没有已准备的验收入口/专用设备基线 |

### 4.2 用例约定

每个手工用例按以下步骤记录：

1. 记录当前 Activity、设备方向、包状态和前置服务状态。
2. `adb logcat -c` 清空本用例日志。
3. 执行 ADB 操作；所有点击中心坐标先由当前 UI 树的 `bounds` 计算，不能凭截图猜坐标。
4. 对中间状态和最终状态分别保存 `screencap`、`uiautomator dump`；需要时保存 UI 摘要。
5. 保存 `logcat -d` 和本机 Gateway access log 的对应时间段。
6. 用“预期 vs 实测 vs 状态”判定；失败要写 HTTP 路径、状态码、界面文字和代码定位。

## 5. 手工真机 E2E 执行记录

### 5.1 启动、认证与账号生命周期

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `AUTH-01` | 启动 `MainActivity`；出现 Gateway 地址、账号名、密码和登录按钮；按钮在字段不完整时不可用 | 启动冷态 UI 树显示完整登录表单；[`00-launch-ui-summary.txt`](../../test-evidence/2026-09-03-live/00-launch-ui-summary.txt) | PASS |
| `AUTH-02` | 输入 `ftp://127.0.0.1:8045`；不应发起网络请求，登录按钮应保持 disabled | UI 树中登录按钮 `enabled=false`，点击后仍停留登录页；[`14-invalid-scheme-after-tap-ui.xml`](../../test-evidence/2026-09-03-live/14-invalid-scheme-after-tap-ui.xml) | PASS |
| `AUTH-03` | 输入 `http://127.0.0.1:9999` + 测试账号；点击登录；展示连接失败、原始原因和重新输入 | 展示 `连接失败`、`Failed to connect to /127.0.0.1:9999`；点击 `重新输入` 回到无失败提示状态；[`15-unreachable-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/15-unreachable-result-ui-summary.txt)、[`16-retry-reset-ui-summary.txt`](../../test-evidence/2026-09-03-live/16-retry-reset-ui-summary.txt) | PASS |
| `AUTH-04` | 输入 `http://127.0.0.1:8045` + `djbd`；完成 negotiate、password session，进入工作台 | Gateway access log 记录 negotiate 200 和 sessions/password 200；App 进入工作台；[`01-login-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/01-login-result-ui-summary.txt)、[`80-gateway-access-relevant.txt`](../../test-evidence/2026-09-03-live/80-gateway-access-relevant.txt) | PASS（仅认证层） |
| `AUTH-05` | 输入未注册用户名；必须认证失败，不得创建账号数据库或进入工作台 | 旧驻留 Gateway 返回 password 200，App 进入工作台；主机账号目录新增了对应 SHA-256 目录。随后该临时目录被移动到 `/tmp/open-android-intelligence-trash/2026-09-03/`，没有永久删除 | FAIL |
| `AUTH-06` | Home/force-stop 后冷启动；有效 refresh credential 应换取新会话并回到工作台 | 旧 Gateway 对 `POST /sessions/refresh` 返回 404，App 回到登录页；但前后 `gateway_refresh_*` 文件数量和大小保留，当前 App 没有因 404 自毁凭据；[`51-cold-start-refresh-404-ui-summary.txt`](../../test-evidence/2026-09-03-live/51-cold-start-refresh-404-ui-summary.txt)、[`50-app-data-before-cold-start.txt`](../../test-evidence/2026-09-03-live/50-app-data-before-cold-start.txt)、[`51-app-data-after-refresh-404.txt`](../../test-evidence/2026-09-03-live/51-app-data-after-refresh-404.txt) | FAIL（服务端阻断；保留凭据子项 PASS） |
| `AUTH-07` | 设置 → 退出当前登录；应撤销当前 session/refresh，清除本地活动凭据并回到干净登录页 | 实际请求 `DELETE /sessions/current?revokeRefresh=false` 返回 404，App 展示 `LOGOUT_FAILED:404`；本地 refresh 文件仍存在；[`73-logout-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/73-logout-result-ui-summary.txt)、[`73-logout-logcat.txt`](../../test-evidence/2026-09-03-live/73-logout-logcat.txt) | FAIL |
| `AUTH-08` | 设置 → 解除配对并擦除；应撤销配对并清理 refresh、设备密钥和镜像 | 实际请求 `DELETE /sessions/current?revokeRefresh=true` 返回 404，App 展示 `LOGOUT_FAILED:404`；没有进入成功清理态；[`87-unpair-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/87-unpair-result-ui-summary.txt)、[`87-unpair-logcat.txt`](../../test-evidence/2026-09-03-live/87-unpair-logcat.txt) | FAIL |
| `AUTH-09` | 点击密码眼睛切换可见/隐藏；失败后点击重新输入 | `显示密码` 切换为 `隐藏密码`，密码内容可见；失败重试恢复初始状态；[`17-password-visible-ui-summary.txt`](../../test-evidence/2026-09-03-live/17-password-visible-ui-summary.txt) | PASS |

### 5.2 工作台、对话和命令

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `CONV-01` | 认证成功后拉取会话列表；成功显示历史，空列表显示真实空状态，失败显示可重试错误 | `GET /conversations?limit=50` 返回 401；工作台显示 `尚未请求数据`，没有伪造历史；[`02-workbench-idle-ui-summary.txt`](../../test-evidence/2026-09-03-live/02-workbench-idle-ui-summary.txt) | FAIL（真实数据链路） |
| `CONV-02` | 初始折叠态点击抽屉；顶部 Header 应在可见且可点击区域 | UI 树中的抽屉节点位于 `[58,41][101,84]`，但被系统 ActionBar `[0,64][2560,200]` 覆盖；按 UI 树中心点击没有打开抽屉；展开输入栏后 Header 移到可操作区域，抽屉才可打开；[`03-drawer.png`](../../test-evidence/2026-09-03-live/03-drawer.png)、[`19-message-draft.png`](../../test-evidence/2026-09-03-live/19-message-draft.png) | FAIL（初始布局） |
| `CONV-03` | 展开输入栏后点击抽屉；抽屉显示 Gateway、会话列表、重试和设置入口 | 抽屉正常打开；会话失败态显示 `CONVERSATIONS_FAILED:401`，设置入口可操作；[`23-drawer-open-ui-summary.txt`](../../test-evidence/2026-09-03-live/23-drawer-open-ui-summary.txt) | PASS（降级态） |
| `CONV-04` | 点击工作流 Tab；切换成功，目录可用时展示 Agent 命令 | Tab 可切换；目录请求返回 404，界面显示 `加载失败` 与 `COMMAND_CATALOG_FAILED:404`；[`67-workflow-tab-ui-summary.txt`](../../test-evidence/2026-09-03-live/67-workflow-tab-ui-summary.txt) | PASS（切换/错误态）；功能数据 FAIL |
| `CONV-05` | 点击新建对话；Gateway 创建新 thread，清空旧 timeline 并选中新 thread | 实际 `POST /conversations` 返回 401；SnackBar/UI 树显示 `CONVERSATION_CREATE_FAILED:CONVERSATION_CREATE_FAILED:401`；[`66-new-conversation-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/66-new-conversation-result-ui-summary.txt) | FAIL |
| `CONV-06` | 普通文本输入 → 发送；消息先进入 pending batch，flush 后服务端确认并出现 Agent 回复 | `e2e-message-20260903` 进入 `同一批次 · 1 条 · 等待合并`；flush 后显示 `SEND_FAILED:SUBMIT_BATCH_FAILED:no-conversation`，pending 条仍在 UI；[`20-message-send-immediate-ui-summary.txt`](../../test-evidence/2026-09-03-live/20-message-send-immediate-ui-summary.txt)、[`20-message-send-final-ui-summary.txt`](../../test-evidence/2026-09-03-live/20-message-send-final-ui-summary.txt) | FAIL |
| `CONV-07` | 输入 `/`；拉取动态目录；点击重试后能重新请求；目录命令只填入不自动发送 | 目录错误态和 `重试` 可见；点击重试再次得到 404；未进入命令成功选择分支；[`21-command-menu-error-ui-summary.txt`](../../test-evidence/2026-09-03-live/21-command-menu-error-ui-summary.txt)、[`22-command-retry-final-ui-summary.txt`](../../test-evidence/2026-09-03-live/22-command-retry-final-ui-summary.txt) | PASS（错误处理）；目录可用 BLOCKED |
| `CONV-08` | 生成期间发送按钮转停止；停止后等待真实 `CANCELLED/OUTCOME_UNKNOWN` | 无法创建会话和生成，未能安全地产生真实 generation；没有伪造停止通过 | BLOCKED |
| `CONV-09` | 输入框有内容时展开，点击收起保留草稿；无内容时恢复折叠布局 | `expand-check` 展开后，点击 `收起` 保留草稿并恢复折叠控件；[`69-composer-expanded-ui-summary.txt`](../../test-evidence/2026-09-03-live/69-composer-expanded-ui-summary.txt)、[`70-composer-collapsed-ui-summary.txt`](../../test-evidence/2026-09-03-live/70-composer-collapsed-ui-summary.txt) | PASS |
| `CONV-10` | 点击语音输入；应打开系统语音输入或显示明确不可用/权限态 | 点击后 UI 无变化、无权限弹窗、无状态或错误提示；源码为 `onClick = { /* 语音输入入口，暂留扩展 */ }` | FAIL |
| `CONV-11` | 完成 SSE 连接、delta、completed、断线 cursor 恢复和时间线更新 | 业务请求在建立 active conversation 前已被 401 阻断，未获得真实 SSE/Agent 事件 | BLOCKED |

### 5.3 附件

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `ATT-01` | 点击 `+`；显示拍照、图库、本地文档三项菜单 | 三项菜单均可见，触控区域和文字完整；[`04-attachment-menu.png`](../../test-evidence/2026-09-03-live/04-attachment-menu.png) | PASS |
| `ATT-02` | 选择拍摄现场照片；系统相机打开；取消返回后 App 不重建/不崩溃 | 当前焦点为 `com.sec.android.app.camera/.Camera`；Back 后回到主 App，无崩溃；[`05-camera-open-ui-summary.txt`](../../test-evidence/2026-09-03-live/05-camera-open-ui-summary.txt)、[`06-camera-return-ui-summary.txt`](../../test-evidence/2026-09-03-live/06-camera-return-ui-summary.txt) | PASS（相机入口/取消） |
| `ATT-03` | 选择图库；系统 Photo Picker 打开；取消回到 App | Photo Picker 显示“只能访问您选择的照片”；Back 后回到 App；[`08-gallery-open-ui-summary.txt`](../../test-evidence/2026-09-03-live/08-gallery-open-ui-summary.txt)、[`09-gallery-cancel-return-ui-summary.txt`](../../test-evidence/2026-09-03-live/09-gallery-cancel-return-ui-summary.txt) | PASS |
| `ATT-04` | 选择本地文档；SAF 打开并展示文档；取消回到 App | DocumentsUI `PickActivity` 打开，显示 PNG/XML 测试文件；Back 后回到 App；[`11-document-open-ui-summary.txt`](../../test-evidence/2026-09-03-live/11-document-open-ui-summary.txt)、[`12-document-cancel-return-ui-summary.txt`](../../test-evidence/2026-09-03-live/12-document-cancel-return-ui-summary.txt) | PASS |
| `ATT-05` | 选择 `screenshot_clean.png`；完成 create → upload → verify，展示已就绪 | App 实际请求 `POST /attachments`，Gateway 返回 401；UI 显示 `上传失败`、`19KB · 失败`；[`63-document-selected-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/63-document-selected-result-ui-summary.txt) | FAIL |
| `ATT-06` | 失败附件点击重试；再次请求；点击移除清除附件草稿 | 重试再次产生 401 请求；移除后附件行消失；[`64-attachment-retry-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/64-attachment-retry-result-ui-summary.txt)、[`65-attachment-removed-ui-summary.txt`](../../test-evidence/2026-09-03-live/65-attachment-removed-ui-summary.txt) | PASS（错误处理） |
| `ATT-07` | 所有附件 verified 后冻结一次 Pending Submission Intent，恰好 POST 一次消息 | 没有 verified 附件和 active conversation，无法验证 exactly-once、摘要、服务端确认和重启恢复 | BLOCKED |

### 5.4 设置、平台内核和传输

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `SET-01` | 抽屉 → 设置；显示 Gateway、内核安全、设备插件、传输链路四 Tab | 设置底板打开，四个 Tab 均可点击；[`24-settings-sheet-ui-summary.txt`](../../test-evidence/2026-09-03-live/24-settings-sheet-ui-summary.txt) | PASS |
| `SET-02` | 内核安全 → Trust Mode → 取消；状态保持关闭 | 弹窗标题、风险正文、取消按钮完整；取消后显示 `未开启（处于沙箱隔离保护状态）`；[`26-trust-dialog-ui-summary.txt`](../../test-evidence/2026-09-03-live/26-trust-dialog-ui-summary.txt)、[`27-trust-cancelled-ui-summary.txt`](../../test-evidence/2026-09-03-live/27-trust-cancelled-ui-summary.txt) | PASS |
| `SET-03` | Trust Mode → 确认开启 → 再关闭；有明确风险说明 | 确认后显示 `已开启：原生代码可接管界面`，再次关闭恢复未开启；[`28-trust-enabled-ui-summary.txt`](../../test-evidence/2026-09-03-live/28-trust-enabled-ui-summary.txt)、[`29-trust-disabled-ui-summary.txt`](../../test-evidence/2026-09-03-live/29-trust-disabled-ui-summary.txt) | PASS |
| `SET-04` | Kill Switch → 二次确认 → 执行；隔离插件、关闭 Trust Mode、记录审计且按钮不可重复 | 执行后显示 `已触发紧急停用...共隔离 0 个已启用插件`，Trust Mode 为关闭；但同页面审计仍为 `0 条`，没有刷新出刚刚由 Kernel 记录的事件；[`31-emergency-dialog-ui-summary.txt`](../../test-evidence/2026-09-03-live/31-emergency-dialog-ui-summary.txt)、[`32-emergency-triggered-ui-summary.txt`](../../test-evidence/2026-09-03-live/32-emergency-triggered-ui-summary.txt) | FAIL（安全动作子项通过，审计展示失败） |
| `SET-05` | Pairing Grants 开关可切换并在重新打开设置后保留 | 短信和通知开关可即时切换，屏幕开关可关闭；关闭设置再打开后恢复默认 `false/true/false`；[`39-grants-toggled-ui-summary.txt`](../../test-evidence/2026-09-03-live/39-grants-toggled-ui-summary.txt)、[`41-settings-reopen-ui-summary.txt`](../../test-evidence/2026-09-03-live/41-settings-reopen-ui-summary.txt) | FAIL |
| `SET-06` | 设备插件 Tab 无插件时显示真实空状态，不显示伪造插件 | 显示 `当前运行时尚未安装任何设备插件`；[`33-settings-plugins-ui-summary.txt`](../../test-evidence/2026-09-03-live/33-settings-plugins-ui-summary.txt) | PASS |
| `SET-07` | 传输链路 Tab 显示默认 HTTPS/SSE 和 Tailscale Companion 状态 | Tab 可打开并显示三条拓扑说明；但当前 Gateway URL 为 `http://...`，仍显示 `TLS Pinned`，与实际传输和 pin 传递不符 | FAIL（语义安全） |
| `SET-08` | 旋转到竖屏再返回横屏；内容不溢出、可操作区域跟随窗口 | 竖屏 UI 树为 `1600x2560`，登录字段和按钮仍在屏内；之后恢复自动旋转；[`48-portrait-login-ui-summary.txt`](../../test-evidence/2026-09-03-live/48-portrait-login-ui-summary.txt)、[`89-final-clean-login-ui-summary.txt`](../../test-evidence/2026-09-03-live/89-final-clean-login-ui-summary.txt) | PASS（布局基础）；Header 覆盖问题仍 FAIL |

### 5.5 Assistant 与系统入口

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `ASST-01` | 显式启动 `ACTION_ASSIST` 到 holder；不应崩溃，按无 grant 输入应不读取附件 | `com.openandroidintelligence.assistant/.AssistantActivity` 启动成功，本次只传入普通 `EXTRA_TEXT`，未传附件；当前 holder 代码对无 grant 分支 fail-closed。UI 只有系统 ActionBar，当前 source-only holder 不提供对话表面；[`45-assistant-explicit-ui-summary.txt`](../../test-evidence/2026-09-03-live/45-assistant-explicit-ui-summary.txt) | PASS（边界启动） / SKIPPED（完整表面） |
| `ASST-02` | 系统 Assist 选择器中选择 OAI 并设为默认，然后通过系统 Assist 手势唤起 | 系统选择器显示三星生活助手、ChatGPT、Google、Open Android Intelligence Assistant；当前角色仍是 Google，没有替用户改变默认值；[`46-assist-default-resolver-ui-summary.txt`](../../test-evidence/2026-09-03-live/46-assist-default-resolver-ui-summary.txt)、[`46-assist-role.txt`](../../test-evidence/2026-09-03-live/46-assist-role.txt) | BLOCKED |
| `ASST-03` | 前台第三方 App → Assist screenshot → 屏幕选区 → 主 App 消息附件 handoff | 需要用户选择默认助理、系统 screenshot grant、被测前台 App 和经过审查的 local adapter；本次未获得这些前置条件 | BLOCKED |

### 5.6 运行时稳定性

| 编号 | 操作路径与预期 | 实测与证据 | 判定 |
| --- | --- | --- | --- |
| `SYS-01` | 相机、Photo Picker、DocumentsUI、BottomSheet、错误 SnackBar 多次切换后无 App crash | App 进程多次冷启动/切换；最终 crash buffer 中没有 `com.openandroidintelligence.mobile` 或 `com.openandroidintelligence.assistant` 命中；[`90-crash-buffer-final.txt`](../../test-evidence/2026-09-03-live/90-crash-buffer-final.txt) | PASS（无 crash 证据） |
| `SYS-02` | Main App 与 assistant holder 安装包可解析、Activity 可启动 | 两个 APK 均安装；MainActivity 和 AssistantActivity 均成功启动 | PASS |

## 6. Android instrumentation 结果

### 6.1 已通过的设备套件

以下 XML 是设备 `SM-X710 - 16` 的原始 JUnit 结果：

- [`77-companion-bridge-results.xml`](../../test-evidence/2026-09-03-live/77-companion-bridge-results.xml)：4/4 PASS。
- [`77-plugin-package-results.xml`](../../test-evidence/2026-09-03-live/77-plugin-package-results.xml)：6/6 PASS。
- [`77-plugin-runtime-wasm-results.xml`](../../test-evidence/2026-09-03-live/77-plugin-runtime-wasm-results.xml)：11/11 PASS。
- [`77-tailscale-companion-results.xml`](../../test-evidence/2026-09-03-live/77-tailscale-companion-results.xml)：1/1 PASS。
- [`77-transport-results.xml`](../../test-evidence/2026-09-03-live/77-transport-results.xml)：5/5 PASS。

这些结果证明设备上的 AIDL failure boundary、插件包策略、WASM runtime、opaque channel 和 transport generation 测试可执行；它们不证明主 App 到 Hermes 的真实业务流。

### 6.2 Gateway client：13/14 通过，1 项失败

[`77-gateway-client-results.xml`](../../test-evidence/2026-09-03-live/77-gateway-client-results.xml) 显示 14 项中 1 项失败：

```text
com.openandroidintelligence.gateway.http.PinnedTlsInstrumentedTest
  httpsOnlyFactoryRejectsPlainHttp
  AssertionError: a plain http URL must never be opened
```

这是设备 instrumentation 的真实 FAIL，不是静态推断。当前源码 [`HttpsConnectionFactory.kt`](../../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/HttpsConnectionFactory.kt) 第 22–30 行明确接受 `http` 或 `https`；主 App Manifest 也设置了 `usesCleartextTraffic="true"`。该结果与手工 E2E 中使用 HTTP 连接成功、设置显示 `TLS Pinned` 相互印证。

### 6.3 tailnet-core：一次性前置缺失

完整原始结果见 [`76-tailnet-aggregate-results.xml`](../../test-evidence/2026-09-03-live/76-tailnet-aggregate-results.xml)，13 项中：

- 11 项 PASS，包括设备 API/ABI/AAR、VPN 表面、状态恢复、不可用 enrollment 和坏 bundle。
- `enrolledNodeReportsBackendOfflineForAbsentBridgePeer` 为 SKIPPED，因为未提供 `p0tOfflineBundle`。
- `unreachableControlFailsClosedWithoutVpnOrPublicFallback` 为 FAIL，但异常是测试前置缺失：`p0tFailClosedBundle provisioning blob missing`，不是被测 native 返回的控制不可达结果。

因此当前 P0t 真实 enrollment、Bridge peer OFFLINE 和 direct/DERP/Doze/网络切换矩阵仍是 BLOCKED。

### 6.4 app/platform-kernel 测试源不同步

执行命令的完整输出见 [`74-broken-androidtest-sources.txt`](../../test-evidence/2026-09-03-live/74-broken-androidtest-sources.txt)。失败发生在 `compile...AndroidTestKotlin`，没有设备执行：

- `app/src/androidTest/.../CoreWithoutPluginsInstrumentedTest.kt` 引用已不存在的 `GatewayPresenter`、`ConversationPresenter`、`AttachmentPresenter`、`PlatformSettingsPresenter`。
- `platform-kernel/src/androidTest/.../ReferencePluginIsolationInstrumentedTest.kt` 仍使用旧的 `PluginManifest(authorPublicKey/packageSha256)` 参数、继承 final `AndroidAuditStore`、旧 `DeveloperTrustMode` 构造方式。

这些测试需要先按当前生产 API 重写，不能用编译失败前的旧测试结论替代本次真机结果。

## 7. 缺陷与追踪记录

### D-001 [P0] 驻留 Gateway 与当前 HEAD 不一致，导致所有签名业务请求 401

- 复现：启动当前 HEAD App → 登录成功 → 进入工作台 → 等待会话列表/命令目录加载。
- 预期：`GET /conversations` 和 `GET /commands` 使用刚建立的设备密钥和 session 通过验证。
- 实测：`negotiate` 200、`sessions/password` 200；随后 `GET /conversations?limit=50` 401、`GET /commands?languageCode=zh-CN` 404。
- 证据：[`80-gateway-access-relevant.txt`](../../test-evidence/2026-09-03-live/80-gateway-access-relevant.txt)、[`79-gateway-final-baseline.txt`](../../test-evidence/2026-09-03-live/79-gateway-final-baseline.txt)。
- 根因证据：当前源码 `core.py` 的 `AccountStore` 创建 `device_keys` 表，但实时账户库只有 `access_sessions` 等旧表，没有 `device_keys`；实时进程启动时间早于源码/HEAD 更新，内存中没有加载当前修复。
- 影响：会话历史、创建 thread、文本消息、附件、SSE 真实业务全部无法验收。
- 修复/复测：在隔离的测试 Gateway 配置中，以当前 HEAD 重新启动 Hermes，确认迁移建表；登录生成新 device key；先做 `GET /conversations`/`GET /commands`，再做消息和附件链路。重启前需确认不会影响其他 Hermes 平台。

### D-002 [P1] 运行中的旧 Gateway 允许未注册账号自注册

- 复现：在 App 登录页填 `http://127.0.0.1:8045`、`unknown_e2e_20260903`、任意非空密码，点击登录。
- 预期：返回 `AUTHENTICATION_FAILED`，账号目录数量不变，App 保持失败态。
- 实测：password session 返回 200，App 进入工作台；账号目录出现合成用户名对应的新 SHA-256 目录。
- 证据：[`44-unknown-login-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/44-unknown-login-result-ui-summary.txt) 与同时间段 [`80-gateway-access-relevant.txt`](../../test-evidence/2026-09-03-live/80-gateway-access-relevant.txt)。临时目录已经安全移动到 `/tmp/open-android-intelligence-trash/2026-09-03/unknown-account-67729b5dfdd90042e5e25a38136393b07336fce7572d3cef4dac2df2ce789af6/`，可人工复核、可恢复。
- 代码校准：当前工作树 `http.py` 已包含“先检查 `_account_exists`，未知账号不得创建”的分支，但实时进程未重启，不能把源码存在当作部署通过。
- 影响：账户隔离和本地显式注册边界被绕过。
- 修复/复测：当前 HEAD Gateway 重启后，用新合成账号重复测试；同时检查账号目录、SQLite 数量和响应码。任何 2xx 或新目录均不接受。

### D-003 [P0/P1] Refresh、logout、unpair 端点在运行 Gateway 中返回 404

- 复现：登录后冷启动，或在设置中点击“刷新凭据”“退出当前登录”“解除配对并擦除”。
- 预期：刷新返回新 session；logout/unpair 返回 2xx 并执行对应清理。
- 实测：`POST /sessions/refresh`、`DELETE /sessions/current?revokeRefresh=false/true` 均 404；App 分别显示登录页、`LOGOUT_FAILED:404`，业务状态无法完成。
- 证据：[`51-cold-start-refresh-404-logcat.txt`](../../test-evidence/2026-09-03-live/51-cold-start-refresh-404-logcat.txt)、[`73-logout-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/73-logout-result-ui-summary.txt)、[`87-unpair-result-ui-summary.txt`](../../test-evidence/2026-09-03-live/87-unpair-result-ui-summary.txt)。
- 正向子项：当前 App 对 refresh 404 不擦除本地 refresh 文件，避免把可恢复服务故障变成永久退出；[`91-app-data-final.txt`](../../test-evidence/2026-09-03-live/91-app-data-final.txt) 可见文件仍在。
- 修复/复测：同 D-001，使用重启后的当前 HEAD Gateway；分别核对 revokeRefresh false/true 的 session、refresh、device key 和本地文件结果。

### D-004 [P1] 传输层允许明文 HTTP，且界面静态显示 TLS Pinned

- 证据：`gateway-client` 真机 instrumentation 的 `httpsOnlyFactoryRejectsPlainHttp` 失败；[`77-gateway-client-results.xml`](../../test-evidence/2026-09-03-live/77-gateway-client-results.xml)。
- 代码证据：`HttpsConnectionFactory.open` 接受 `url.protocol == "https" || url.protocol == "http"`；主 Manifest 开启 `usesCleartextTraffic`；`GatewayRuntime.establish` 创建 `GatewayProfile` 时没有把 negotiate 解析出的 `tlsSpkiSha256` 传入 pin 集合。
- UI 证据：设置显示 URL `http://127.0.0.1:8045` 和 `TLS Pinned`；[`24-settings-sheet.png`](../../test-evidence/2026-09-03-live/24-settings-sheet.png)。
- 影响：明文联调配置容易被误认为已完成 TLS 身份校验；生产协议和本地开发例外没有清晰分离。
- 修复/复测：默认只允许 HTTPS；如果保留本机 loopback 测试例外，应绑定明确的 debug-only policy、持续警告并禁止敏感凭据/附件/后台同步，同时让 UI 如实显示未 pin，而不是 `TLS Pinned`。

### D-005 [P1] 初始折叠态顶部 Header 被系统 ActionBar 覆盖

- 复现：认证成功进入工作台，保持输入栏折叠，按 UI 树中 `打开会话抽屉` 的 bounds 中心点击。
- 预期：抽屉打开。
- 实测：节点在 `[58,41][101,84]`，系统 ActionBar 在 `[0,64][2560,200]`，点击没有打开；输入栏展开后 Header 被重新布局到 y=412 左右，才可点击。
- 证据：[`02-workbench-idle-ui-summary.txt`](../../test-evidence/2026-09-03-live/02-workbench-idle-ui-summary.txt)、[`03-drawer-ui.xml`](../../test-evidence/2026-09-03-live/03-drawer-ui.xml)、[`19-message-draft.png`](../../test-evidence/2026-09-03-live/19-message-draft.png)。
- 影响：新用户在默认状态下不能进入抽屉、工作流或新建对话；在不同方向/insets 下行为不稳定。
- 修复/复测：使用无 ActionBar 主题或正确的 edge-to-edge inset 组合，确认初始折叠、展开、横屏、竖屏四个状态的可见 bounds 与点击闭环。

### D-006 [P1] Pairing Grants 只存于 Composable 临时状态，重新打开即丢失

- 复现：设置 → 网关账号，切换 SMS/屏幕/通知开关，关闭设置，再重新打开。
- 预期：授权变更写入本机策略/配对状态，并按 Gateway/安装实例恢复；如未接真实服务应显示未连接而不能假装成功。
- 实测：开关当场变化；重新打开恢复默认 `SMS=false, screen=true, notification=false`，没有 Gateway 请求或持久化证据。
- 代码证据：`PlatformSettingsBottomSheet.kt` 第 327–348 行在 Composable 内使用 `remember { mutableStateOf(...) }`。
- 影响：用户以为已授权/撤权，实际状态不可追踪，敏感能力契约失真。
- 修复/复测：接入 Platform Kernel/Pairing Grant 真正的 state holder、revision、审计和 Gateway 绑定；执行冷启动、切换 Gateway、拒绝和重启复测。

### D-007 [P1] Kill Switch 执行后审计列表不刷新

- 复现：内核安全 → 滚动到 Kill Switch → 确认执行。
- 预期：安全熔断成功、Trust Mode 关闭、插件被隔离、审计至少出现一条 `emergency.stop`。
- 实测：熔断状态和不可逆提示出现，隔离 0 个插件；同一界面仍显示 `安全审计日志 0 条`。
- 代码证据：`PlatformSettingsBottomSheet.kt` 第 536–552 行对 `environment.auditSink.events()` 使用一次性 `remember`，没有可观察 Flow/state。
- 影响：用户无法在安全动作后看到可追踪审计事实。
- 修复/复测：让审计源成为可观察的持久 state/Flow；执行熔断、重新组合、重启和拒绝后调用复测。

### D-008 [P2] 语音输入按钮为空回调

- 复现：工作台折叠输入栏 → 点击麦克风。
- 预期：系统语音输入、明确 unsupported 或权限请求。
- 实测：无状态变化、无权限请求、无错误提示；[`68-voice-input-noop-ui-summary.txt`](../../test-evidence/2026-09-03-live/68-voice-input-noop-ui-summary.txt)。
- 代码证据：`ComposerBar.kt` 第 242–244 行为空 `onClick`。
- 影响：可操作控件伪装成已实现功能，违反“禁止死状态/空函数”。
- 修复/复测：接真实系统输入 Intent 或删除/禁用入口并显示明确状态；验证取消、权限拒绝和输入回填。

### D-009 [P1] Android instrumentation 测试源未随生产 API 重构

- 证据：[`74-broken-androidtest-sources.txt`](../../test-evidence/2026-09-03-live/74-broken-androidtest-sources.txt)。
- 影响：主 App 和 platform-kernel 的设备测试不能编译，无法形成当前生产 API 的 instrumentation 门禁。
- 修复/复测：按当前 `GatewayRuntime`/`WorkbenchController`/`PluginManifest`/`AndroidAuditStore`/`DeveloperTrustMode` API 重写测试；先 focused compile，再 connected device，再保存 XML。

## 8. BLOCKED / SKIPPED 清单

以下项目不是“测试失败后忽略”，而是本次没有满足安全或真实环境前置：

| 项目 | 状态 | 缺少的前置或原因 |
| --- | --- | --- |
| 当前 HEAD Gateway 正常业务 E2E | BLOCKED | 运行中的 Hermes 未重启，进程/SQLite schema 与 HEAD 不一致；重启可能影响其他 Hermes 平台 |
| 对话成功发送与 Agent 回复 | BLOCKED | 会话列表/创建被 401 阻断，没有 active conversation |
| SSE delta/completed/cursor 恢复 | BLOCKED | 无成功消息生成和可验证 SSE 流 |
| verified 附件提交/exactly-once | BLOCKED | create/upload 在 401 失败，未得到 verified attachment |
| 真实默认 Assist/跨 App screenshot | BLOCKED | 默认 Assist 仍为 Google；需要用户选择、系统 grant 和被测前台 App |
| P0t enrollment / Bridge TCP 443 / OFFLINE | BLOCKED | `p0tFailClosedBundle`、`p0tOfflineBundle` 未提供；一次性 key 和 Bridge 目标不能猜测 |
| app/platform-kernel connected tests | BLOCKED | androidTest 源编译错误 |
| 短信/通知/通话记录真实插件 | SKIPPED/BLOCKED | 主 APK 未声明/安装这些插件；项目边界要求它们不进入可见 Host 核心 |
| Tailscale Companion 独立 APK UI | SKIPPED | 当前 `tailscale-companion` 是库/opaque channel 测试，不是设备上独立安装的用户 UI |
| TalkBack/减少动态/性能预算 | BLOCKED | 本次没有专用 accessibility/performance 验收入口和基线，不能用截图主观判定 |

## 9. 可复现执行方案

### 9.1 安装与启动

```bash
cd apps/android
./gradlew --no-daemon --console=plain \
  :app:assembleFullDebug :assistant-holder:assembleDebug
./gradlew --no-daemon --console=plain \
  :app:installFullDebug :assistant-holder:installDebug

adb -s R52X909R9QT reverse tcp:8045 tcp:8045
adb -s R52X909R9QT reverse --list
adb -s R52X909R9QT logcat -c
adb -s R52X909R9QT shell am force-stop com.openandroidintelligence.mobile
adb -s R52X909R9QT shell am start -W -n com.openandroidintelligence.mobile/.MainActivity
```

### 9.2 UI 树驱动操作

```bash
adb -s R52X909R9QT exec-out uiautomator dump /dev/tty > /tmp/ui-step.xml
python3 /home/djbd/.codex/plugins/cache/openai-curated-remote/test-android-apps/0.1.2/skills/android-emulator-qa/scripts/ui_pick.py \
  /tmp/ui-step.xml "登录并配对"
adb -s R52X909R9QT shell input tap <x> <y>
adb -s R52X909R9QT exec-out screencap -p > /tmp/step.png
adb -s R52X909R9QT logcat -d > /tmp/step-logcat.txt
```

本次的真实证据没有把 `/tmp` 当最终保存位置，而是复制/保存到 `docs/test-evidence/2026-09-03-live/`；每个步骤的文件名使用顺序号和场景名。执行前后应保留：

- `uiautomator dump` 原始 XML 和摘要；
- `screencap -p` PNG；
- `logcat -c` 后的 `logcat -d`；
- Gateway access log 对应时间窗口；
- 设备 serial、Activity、方向和 build 信息；
- JUnit XML 和命令退出码。

### 9.3 设备 instrumentation

```bash
cd apps/android
./gradlew --no-daemon --console=plain \
  :companion-bridge:connectedDebugAndroidTest \
  :gateway-client:connectedDebugAndroidTest \
  :plugin-package:connectedDebugAndroidTest \
  :plugin-runtime-wasm:connectedDebugAndroidTest \
  :tailscale-companion:connectedDebugAndroidTest \
  :transport:connectedDebugAndroidTest

./gradlew --no-daemon --console=plain :tailnet-core:connectedDebugAndroidTest
```

重跑特定类时使用 `-Pandroid.testInstrumentationRunnerArguments.class=...`，但必须在报告中说明这是 focused rerun，不要与聚合总数相加。一次性 provisioning bundle 只能由专门流程生成并立即使用，不应从旧文件猜测或复用。

## 10. 交付物与追踪

本次新增的实时证据目录包含 299 个文件，包含 PNG、UI XML/摘要、Logcat、Gateway access 筛选、APK hash、JUnit XML、Gradle 输出和最终状态。证据文件清单见 [`92-evidence-file-index.txt`](../../test-evidence/2026-09-03-live/92-evidence-file-index.txt)，完整 SHA-256 见 [`93-evidence-manifest.sha256`](../../test-evidence/2026-09-03-live/93-evidence-manifest.sha256)。

重要隐私提示：[`05-camera-open.png`](../../test-evidence/2026-09-03-live/05-camera-open.png) 是真实相机预览，可能包含设备前方环境/人像；在提交或共享证据前应由设备所有者复核。临时未知账号目录未永久删除，已移动到 `/tmp/open-android-intelligence-trash/2026-09-03/` 供复核。现有 `.gitignore` 第 51 行已忽略 `/docs/test-evidence`，因此这些证据会留在当前工作区但不会出现在普通 `git status`；本次未擅自修改该用户既有忽略规则。

## 11. 下一轮复测门槛

下一轮不应直接重复所有 UI 点击，应按下面顺序减少变量：

1. 由用户确认一个不会影响其他 Hermes 平台的隔离 Gateway 重启/测试 profile；以当前 HEAD 启动并确认 `device_keys` 表、`/commands`、`/sessions/refresh`、`/sessions/current` 与当前契约一致。
2. 先用合成未知账号复测 D-002，再用注册账号建立新设备 session；保存 negotiation/session 返回字段，但不保存 token 明文。
3. 先通过 `GET /conversations` 和 `POST /conversations`，再验证同一 active thread 的文本 batch、SSE delta/completed/cursor，最后验证附件 create/upload/commit/message exactly-once。
4. 修复或明确 HTTPS debug policy，并让 `TLS Pinned` 与真实 pin 集合一致；重新执行 `gateway-client` 全部 connected tests。
5. 修复 `app`/`platform-kernel` androidTest API 漂移，补主 App Compose/UI instrumentation；不能仅依赖 ADB 手工路径。
6. 在设备所有者明确选择默认 Assist 并授权后，单独执行第三方前台 App、系统 screenshot、screen-selection attachment、返回/锁屏/撤权矩阵。
7. 生成并立即消费专用 P0t bundle，独立采集设备网络/VPN/route/DNS 前后状态；任何普通 shell backend 结果都不能替代 App 进程证据。
