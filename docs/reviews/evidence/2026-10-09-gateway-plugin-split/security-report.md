# Security Review: open-android-intelligence

## Scope

当前主仓的Gateway拆仓及其Android、契约、CI、受保护插件、本地E2E安全边界。

- Scan mode: repository
- Target kind: git_revision
- Target ID: target_sha256_0b87f119fbaba8ef432617f3fc2140edf5fce1dd8ffe83f91d538adfeb1d1d8e
- Revision: 6f923002b7a97c131da7621a91e310d2c80f94bc
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Artifacts reviewed: artifacts/validation/boundary-probe.log, artifacts/validation/contract-pin-tests.log, artifacts/validation/openclaw-conformance.log, artifacts/validation/hermes-conformance.log
- Scan context: 模型由独立架构检查生成；独立安全审计和父Agent汇总。审查未改生产代码。当前安全路径与迁移前95f2e17无文件差异，因此4项App安全风险为现有问题，非本次迁移引入。

Limitations and exclusions:
- 安全完整读取99个唯一跟踪文件，覆盖部分；未做全库穷尽漏洞审查
- 两个外部插件内部与生产部署不在当前源码审查范围，运行向量不代表审计其实现
- 后台与文件越界的生产调用链由源码确认，未在设备执行恶意操作
- 网络代理与更新策略为当前源码最小本地探针；没有网络利用
- 当前Android互操作结果来自Gradle缓存，不能说此次HTTP链路重跑通过

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 5 |
| Severity mix | medium: 4, low: 1 |
| Confidence mix | high: 5 |
| Coverage | partial |
| Validation mode | 离线当前源码审查、迁移差异对照、授权的最小本地CLI与当前CI日志核对 |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

本次模型来自独立 App 架构检查，覆盖 Android、gateway-client、conversation-data、契约工具与 CI，架构映射本身不代表完整漏洞审计。产品让 Android 安装实例连接用户自己的 Hermes/OpenClaw Gateway，手机负责本机设备能力最终授权（CONTEXT.md:3；docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:78-86）。生产 App 仅组合本仓 Android 模块，不直接依赖迁出的服务端插件源码（apps/android/app/build.gradle.kts:88-106）。独立插件提供服务端实现，本仓持有协议资产、Android 实现与一致性工具（docs/adr/0052-own-openclaw-gateway-plugin-in-standalone-repository.md:10-14）。App 先协商版本与 Schema 摘要，再进行账号认证并使用签名 HTTP；普通对话事件先尝试 WebSocket 后降级 SSE，平台设备事件使用独立 SSE 客户端和游标（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:211-241、864-870；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt:174-186、219-265）。生产 APK 构建、真实 Hermes HTTP 互操作、向量一致性和设备授权是不同证据层。

### Assets

- 账号密码、邀请凭据、短期 access token 与可轮换 refresh credential；首次连接来自用户输入或邀请，恢复登录来自保存账号。HTTPS 认证前核验 TLS 身份，恢复登录要求保留身份（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:195-241、579-629；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayIdentityTrustStore.kt:25-43）。
- 刷新凭据位于 context.filesDir/keystore-credentials/gateway_refresh_\<Base64URL(profileId)\>.bin；profileId=Base64URL(gatewayUrl+'|'+username)。每 profile 使用 Android Keystore AES-GCM 包装密钥，引用 open_android_intelligence_gateway_refresh_\<Base64URL(profileId)\>（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:131-137、1035-1037；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/AndroidKeystoreGatewayCredentialStore.kt:30-46、112-121）。
- 生产签名身份是 Ed25519DeviceKeyStore，经 Android Keystore AES 密钥包装后存放 context.filesDir/gateway-credentials/device_ed25519_\<Base64URL(profileId)\>.bin；包装密钥引用 open_android_intelligence_device_ed25519_wrap_\<Base64URL(profileId)\>，签名时在进程内解密。另一个凭据类的 EC deviceKey 方法不是当前生产签名来源（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DeviceKeySource.kt:22-30；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/Ed25519DeviceKeyStore.kt:56-85、89-135）。
- 本地非秘密账号资料在 Android AccountManager（类型 com.openandroidintelligence.gateway、Account.name 为 profileId）；TLS 信任在 gateway-identities 私有 SharedPreferences 的 \<scopeHash\>.pin/deployment。scope=SHA256(endpoint host/port/rawPath+trimmed username)，不含 scheme，阻止 HTTPS 身份以 HTTP 键旁路（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AndroidAccountProfileStore.kt:12-35、44-65、75；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayIdentityTrustStore.kt:12-43、47-64）。
- 对话镜像、附件草稿和历史媒体分别以 conversation-mirror-v1、attachment-map-v1、history-media-cache-v1 加 \[profileId,gatewayId,accountId,installId\] 规范 JSON 为独立 scope；最终为 context.noBackupFilesDir/private-documents/SHA256(scope)/SHA256(documentKey)，密钥引用 oai_documents_\<SHA256(scope)\>，AES-GCM 解密后再核 scope/key。媒体仅显式 retain，默认 64MiB、用户 8–256MiB、单份原件 8MiB，并验证 scope、长度与摘要（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt:13-20；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedAttachmentRecovery.kt:9-14；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedHistoryMediaCache.kt:14-21、46-69；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/EncryptedPluginState.kt:13-27）。
- 附件加密暂存位于 context.noBackupFilesDir/attachment-staging/SHA256(gatewayId+'\\n'+accountId+'\\n'+installId)/ast_\<随机标识\>.part或.stage；包装密钥引用 oai_attachment_\<scopeHash前32字符\>；AES-GCM 块的 AAD 绑定 scope、附件 ID、位置与长度，有界分块、期限清理、上传摘要核对（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:1055-1062；apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/EncryptedAttachmentStagingStore.kt:44-57、102-109、127-129）。
- 事件断点在 gateway-event-cursors 私有 SharedPreferences 的 SHA256(profileId)+'.'+SHA256(accountId)；平台客户端用 profileId+':platform' 独立游标，同步 commit 失败时报错（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AndroidEventCursorStore.kt:8-24；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:707、864-870、952）。
- 设备能力和执行结果：远端事件只是唤醒，重新获取认证请求，核 deviceId/pairingGeneration/grantRevision/期限，然后 claim、确认、调用插件和内核。本地能力 Schema 来自已验证包（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:32-41、61-90；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247）。
- 协议兼容与测试证据：SchemaContractHash.CORE 是源码常量，JUnit 读取七个 Schema 字节重算核对。跨宿主 JSONL/manifest 默认 gateway-contract/.artifacts/conformance，freshness 绑定向量、fixture 文件和结果字节，OpenClaw 额外绑定主仓 pin，Hermes 未绑定实现版本（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHash.kt:19-37；apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHashTest.kt:18-22；gateway-contract/tools/conformance-artifacts.ts:66-96、159-184）。
- APK 私有发行签名与发布权限：正式标签由 GitHub Secrets 提供材料，经 umask 077 写 runner.temp/oai-release.keystore，Gradle 检查完整配置并拒绝公开 debug 身份用于 release，产物为 app-full-release.apk，isDebuggable=false（.github/workflows/android-apk.yml:60-84；apps/android/app/build.gradle.kts:46-57、68-84）。

### Trust Boundaries

- 外部 oai://pair 链接/用户输入→账号连接：界面验证邀请 ID、摘要格式、期限；明确登录动作触发 Runtime 并核协商身份摘要。链接只能影响候选目的地，不能推定拥有账号、私钥或既有 TLS 身份（apps/android/app/src/main/AndroidManifest.xml:27-41；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt:77-93；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:337-349；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:217-220）。
- App→HTTP/SSE Gateway：URL=endpoint.baseUrl.trimEnd('/')+request.target，系统 TLS/SPKI 在写 body、读响应前核验，不跟随重定向。签名绑定规范目标、body 摘要、认证头；普通响应默认上限32MiB，附件上传验证长度/摘要（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt:33-36、59-73、80-122、179-223；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionFactory.kt:24-34；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt:174-186）。
- App→WebSocket Gateway：URI(baseUrl) 只取 host/port/TLS，目标固定 /open-android-intelligence/v2/events/ws（可加 cursor），未带 base URL path prefix。TLS socket 做 HTTPS hostname verification 和 SPKI，再发签名认证 Upgrade；失败可退 SSE（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:55-80、101-110、192-215、396）。
- Gateway/Agent→设备能力：配对身份、授权版本、能力作者、参数 Schema 与确认由 App/内核核验。受保护 WASM 和开发者信任原生插件边界不同；同进程原生代码为宿主可信代码，不假设 UID 隔离（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:32-90；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247；CONTEXT.md:23-28）。
- 主仓 CI→外部插件代码执行：Node 作业无 ref 检出 Hermes 后 editable 安装；Android 再次无 ref 检出普通安装。OpenClaw 由完整 SHA pin 检出，npm ci --ignore-scripts，typecheck/build 后导入 runtime/src/core/gateway-core.js，CI 核重建 runtime tracked 与 untracked（.github/workflows/ci.yml:46-69、85-106、127-144；gateway-contract/tools/run-openclaw-conformance.ts:33-52）。
- Gradle测试→插件/契约/Python：HERMES_PLUGIN_ROOT 优先，否则 .hermes-gateway-plugin；fixture=tests/android_gateway_fixture.py，cwd=pluginRoot，PATH 的 python3，参数 --contract-root，OPEN_ANDROID_GATEWAY_CONTRACT_ROOT 优先否则本仓 gateway-contract。fixture 缺失/未返 loopback URL 失败，finally 停子进程。Test 只转发路径环境变量，未声明外部 fixture/源码/Python包/契约内容 inputs；gateway-client 的 Schema/registry 测试同样读取模块外文件而未声明。org.gradle.caching=true 且 setup-gradle 持久缓存（apps/android/conversation-data/src/test/kotlin/com/openandroidintelligence/conversation/data/HermesHttpInteropTest.kt:35-62、113-115；apps/android/conversation-data/build.gradle.kts:26-40；apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHashTest.kt:18-22；apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/schema/DispatchedSchemaVectorTest.kt:18-23；apps/android/gateway-client/build.gradle.kts:1-20；apps/android/gradle.properties:1-4）。
- 插件契约门禁→本仓工作区：插件根环境变量→contract-pin.json→仓库身份与40位SHA→比较 schemas/vectors/core-dispatched-schemas.json/src，不比较 tools；缺路径默认失败，ALLOW_SKIP_PIN_GATE=1 为显式本地豁免（gateway-contract/tools/check-contract-pin.py:34-39、68-93、96-155；.github/workflows/ci.yml:74-83）。
- 可信发布配置→Release：APK 工作流 contents:write；正式标签用私有 Secrets 签名发布；独立 APK workflow 只 assemble；CI Android 同时 check+assemble。这些成功不能互相替代（.github/workflows/android-apk.yml:12-13、32-39、60-84；.github/workflows/ci.yml:151-161）。

### Attacker Capabilities

- 邀请链接发送者可控制查询参数和候选 Gateway/账号，不能自动登录，需用户动作并核身份；不假设其已有账号、设备私钥、保存 TLS 身份（GatewayLoginScreen.kt:77-93、MainActivity.kt:337-349、GatewayRuntime.kt:217-220，均在 apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/）。
- 网络攻击者可观察或修改用户显式允许的 HTTP 流量。该路径由契约接受，需持续警告，已保存 TLS 身份阻止降级，HTTPS 仍做系统 TLS/SPKI（docs/contracts/gateway-protocol-v2.md:14-16；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayIdentityTrustStore.kt:25-43；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionSecurity.kt:27-36）。
- 已认证/被选择 Gateway 可返回对话、事件、附件元数据和设备请求，权限不等同本机授权。授权、身份、Schema边界失败才可能增加设备权限（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:32-90；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247）。
- 能更新 Hermes 上游默认分支或控制可信 CI 依赖者能改变后续执行的 Python；不假设匿名用户能写上游/Secrets。OpenClaw 执行版本受本仓完整 pin（.github/workflows/ci.yml:46-69；gateway-contract/tools/run-openclaw-conformance.ts:34-52）。
- 能修改开发环境、Python搜索路径、插件根、结果缓存的人能改变本地验证资源；这是开发者权限，不能直接推出远程 App 攻击能力（gateway-contract/tools/run-hermes-conformance.py:26-33；gateway-contract/tools/run-openclaw-conformance.ts:37-52；gateway-contract/tools/conformance-artifacts.ts:66-70、200-232）。
- 受保护插件作者可以签名发布 manifest、WASM 和包资源，用户须安装并首次信任作者；该信任不授予任意文件读取、网络扩张或跳过配对/后台授权。日志读者可能看见本地 E2E stdout，但不应获得账号口令。

### Security Objectives

- 手机是设备能力最终授权者；安装、启用、登录、配对与后台授权互不替代（docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:78-82、149-159；CONTEXT.md:261-263）。
- HTTPS 传秘密前核真实身份，保存身份不能静默降级或变化后复用凭据（apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:211-241、610-629；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayIdentityTrustStore.kt:25-43）。
- Schema常量对应七个本仓原始文件；摘要变更需 Android/双Gateway 同步，不能用插件版本替代内容一致性（docs/contracts/gateway-protocol-v2.md:143-162；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHash.kt:19-37；apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHashTest.kt:18-22）。
- 刷新凭据、私钥、对话、附件按实际 profile/scope 和 Keystore 隔离；解密后核 scope/key，块AAD绑定完整scope，目录名不单独证明隔离（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/AndroidKeystoreGatewayCredentialStore.kt:112-121；apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/EncryptedPluginState.kt:17-27；apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/EncryptedAttachmentStagingStore.kt:44-57）。
- CI真实互操作应绑定此次插件、Python依赖、契约内容，不只相同路径或旧任务缓存（.github/workflows/ci.yml:124-158；apps/android/conversation-data/build.gradle.kts:26-40；apps/android/gradle.properties:1-4）。
- 实现版本、契约pin、runtime及一致性结果可追溯；OpenClaw有SHA/版本/入口/重建检查，Hermes仅契约pin和默认分支未锁实现（tools/check-openclaw-plugin-pin.py:32-70；.github/workflows/ci.yml:46-91；gateway-contract/tools/check-contract-pin.py:34-39）。
- Release用私有身份、禁公开debug；构建成功与测试完成分别报告（apps/android/app/build.gradle.kts:68-84；.github/workflows/android-apk.yml:32-84）。
- 受保护插件的文件引用、网络方法/路径、更新权限扩张和后台执行均须由内核约束；本地编排日志不得输出账号密码。

### Assumptions

- 独立模型仅离线映射主仓源码，未审查外部插件/fixture实现、实际宿主监听/TLS/主密钥/账号隔离。账号文件级隔离是文档要求，不能由本仓证明外部实现（docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:211-227）。
- 生产 APK 无需源码 gateway-contract 目录；使用编译常量；SharedContract/DispatchedSchemaRegistry 本地文件消费者是测试。设备生产参数 Schema 由已安装包提供（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHash.kt:30；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/DispatchedSchemaRegistry.kt:13-29；apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/schema/DispatchedSchemaVectorTest.kt:18-23）。
- 每次构建重算摘要的注释实际上依赖JUnit，独立 APK assemble 无 check，check还可命中缓存。本轮主Agent已独立Python重算当前七文件，确与 CORE 相等（SchemaContractHash.kt:29-30、SchemaContractHashTest.kt:18-22、.github/workflows/android-apk.yml:32-39，前两文件在 apps/android/gateway-client 对应 main/test schema 目录）。
- 主Agent取得当前提交CI：conversation-data debug/release测试 FROM-CACHE；双端66向量、Node与Hermes测试通过；本轮本地固定 OpenClaw f4a04a28d4cc419de4c544d7c99bb08d62a3ee97 与 Hermes 3e3b737333a803c92c29e1b6f22d25ca5bdb12fc 均66/66，跨宿主3/3。缓存绿不能说此次Hermes与App重新走过HTTP。
- Hermes runner 直接导入Python环境，HERMES_PLUGIN_ROOT不选模块；CI editable安装使Node指向检出目录；本地需安装或PYTHONPATH。Android fixture内部是否改sys.path/导入正确包在主仓不可见（gateway-contract/tools/run-hermes-conformance.py:26-33；.github/workflows/ci.yml:52-53；HermesHttpInteropTest.kt:43-62，位于 apps/android/conversation-data/src/test/kotlin/com/openandroidintelligence/conversation/data/）。
- Hermes计划只写schemas/vectors，但当前门禁还比registry/src，属文档落后而非该输入漏检（docs/superpowers/plans/2026-10-08-hermes-gateway-plugin-split.md:65-72；gateway-contract/tools/check-contract-pin.py:34-39）。
- 总规格相邻两行同时写无等价WS和WS优先/SSE降级；当前契约和普通事件支持双通道，平台事件禁WS，文档冲突保留（docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:231-232；docs/contracts/gateway-protocol-v2.md:14；GatewayHttpClient.kt:74、GatewayRuntime.kt:864-870，分别位于 gateway-client/main/http 与 app/main/mobile）。
- HTTP/SSE使用baseURL完整path，WS固定target；是否有带prefix部署及实际影响未知（apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt:183-185；apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/ws/GatewayWebSocketTransport.kt:55-68）。
- artifact freshness未绑定Hermes实现或Schema字节；旧缓存不能单独证明新实现。当前CI专门conformance主动跑双runner，本地bare vitest复用路径有区别（gateway-contract/tools/conformance-artifacts.ts:79-96、255-267；package.json:12；.github/workflows/ci.yml:94-97）。
- 发行Secrets/Release权限来自可信配置，未发现普通PR获取路径，未审查外部GitHub保护设置，未复制秘密（.github/workflows/android-apk.yml:12-13、60-84）。
- 审查未修改生产代码、未执行设备登录/授权/真实宿主E2E；最小契约/CLI验证由主Agent执行，不证明设备权限或外部服务安全。

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [受保护插件清单引用逃出包目录并无界读取](#finding-1) | medium | high | inline below |
| [受保护联网忽略路径范围](#finding-2) | medium | high | inline below |
| [远程读取固定前台标识，绕过后台授权](#finding-3) | medium | high | inline below |
| [同作者更新扩大HTTP方法不重新批准](#finding-4) | medium | high | inline below |
| [E2E打印账号口令](#finding-5) | low | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] 受保护插件清单引用逃出包目录并无界读取

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | 独立审计与父Agent核对具体源码控制；有最小运行证据的部分已标明，其余未声称实机利用。 |
| Category | 路径遍历与无界宿主读取 |
| CWE | CWE-22 |
| Affected lines | apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:89-95, apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:105-113, apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:406-423, apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:104-112 |

#### Summary

provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。 恶意受保护包可使宿主读取包外JSON或无限设备流，阻塞/耗尽宿主资源；没有证明私钥/刷新凭据明文泄露。

#### Root Cause

所有静态资源必须为已验签包内普通文件且读取有界。 当前数据流：provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:89-95`

provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。

```kotlin
                    val entries = (JsonFields.field(declarations,"provides") as? JsonValue.JArray)?.items.orEmpty().map { entry ->
                        val cap = entry as? JsonValue.JObject ?: error("MANIFEST_INVALID")
                        val capabilityId = JsonFields.string(cap,"id") ?: error("MANIFEST_INVALID")
                        val schemaPath = JsonFields.string(cap,"schema") ?: error("MANIFEST_INVALID")
                        VerifiedCapability(verified.identity,capabilityId,JsonFields.string(cap,"version") ?: error("MANIFEST_INVALID"),
                            Json.parse(File(verified.stagedDirectory,schemaPath).readText()),
                            PluginCapabilityRisk.classify(verified.runtime.type,primitives),primitives)
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:105-113`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
                        (JsonFields.field(ui,slot) as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value }
                    }.mapNotNull { path ->
                        val file = File(verified.stagedDirectory,path)
                        if (!file.canonicalPath.startsWith(verified.stagedDirectory.canonicalPath + File.separator) || !file.isFile || file.length() > 1024*1024) null
                        else runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(file.readText()) }.getOrNull()
                    }
                    cardIds[id]=(JsonFields.field(ui,"cards") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { path ->
                        (path as? JsonValue.JString)?.value?.let { name -> runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(File(verified.stagedDirectory,name).readText()).id }.getOrNull() }
                    }.toSet()
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:406-423`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    private fun parseCapabilityEntries(capabilities: JsonValue.JObject): Set<String> {
        val array = capabilities.get("provides") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:provides")
        val ids = LinkedHashSet<String>()
        for (item in array.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:provides")
            rejectUnknownFields(obj, setOf("id", "version", "schema"), "provides")
            val id = (obj.get("id") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:provides")
            if ((obj.get("version") as? JsonValue.JString)?.value == null ||
                (obj.get("schema") as? JsonValue.JString)?.value == null
            ) {
                throw PackageRejected("SCHEMA_INVALID:provides")
            }
            ids += id
        }
        return ids
    }
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:104-112`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    fun refresh() { host?.reload(); installed = resolvedStore.listInstalled() }
    fun installBytes(bytes: ByteArray, approved: Boolean = false) {
        if (installing) return
        installing = true
        installScope.launch {
            try {
                val view = runInterruptible(Dispatchers.IO) { resolvedStore.install(bytes,approved) }
                refresh(); pendingApproval = null
                notice = "已安装 ${view.pluginId} v${view.version}，可启用并为当前配对授权。"
```

#### Validation

父Agent核对验包、签名索引、安装刷新与冷启动读取：ZIP项检查未覆盖schema/ui.cards引用，存在无界readText。未执行/dev/zero或恶意安装。

Validation method: 独立静态审计、父Agent源码复核和最小本地探针

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:89-95`

provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。

```kotlin
                    val entries = (JsonFields.field(declarations,"provides") as? JsonValue.JArray)?.items.orEmpty().map { entry ->
                        val cap = entry as? JsonValue.JObject ?: error("MANIFEST_INVALID")
                        val capabilityId = JsonFields.string(cap,"id") ?: error("MANIFEST_INVALID")
                        val schemaPath = JsonFields.string(cap,"schema") ?: error("MANIFEST_INVALID")
                        VerifiedCapability(verified.identity,capabilityId,JsonFields.string(cap,"version") ?: error("MANIFEST_INVALID"),
                            Json.parse(File(verified.stagedDirectory,schemaPath).readText()),
                            PluginCapabilityRisk.classify(verified.runtime.type,primitives),primitives)
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:105-113`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
                        (JsonFields.field(ui,slot) as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value }
                    }.mapNotNull { path ->
                        val file = File(verified.stagedDirectory,path)
                        if (!file.canonicalPath.startsWith(verified.stagedDirectory.canonicalPath + File.separator) || !file.isFile || file.length() > 1024*1024) null
                        else runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(file.readText()) }.getOrNull()
                    }
                    cardIds[id]=(JsonFields.field(ui,"cards") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { path ->
                        (path as? JsonValue.JString)?.value?.let { name -> runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(File(verified.stagedDirectory,name).readText()).id }.getOrNull() }
                    }.toSet()
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:406-423`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    private fun parseCapabilityEntries(capabilities: JsonValue.JObject): Set<String> {
        val array = capabilities.get("provides") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:provides")
        val ids = LinkedHashSet<String>()
        for (item in array.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:provides")
            rejectUnknownFields(obj, setOf("id", "version", "schema"), "provides")
            val id = (obj.get("id") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:provides")
            if ((obj.get("version") as? JsonValue.JString)?.value == null ||
                (obj.get("schema") as? JsonValue.JString)?.value == null
            ) {
                throw PackageRejected("SCHEMA_INVALID:provides")
            }
            ids += id
        }
        return ids
    }
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:104-112`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    fun refresh() { host?.reload(); installed = resolvedStore.listInstalled() }
    fun installBytes(bytes: ByteArray, approved: Boolean = false) {
        if (installing) return
        installing = true
        installScope.launch {
            try {
                val view = runInterruptible(Dispatchers.IO) { resolvedStore.install(bytes,approved) }
                refresh(); pendingApproval = null
                notice = "已安装 ${view.pluginId} v${view.version}，可启用并为当前配对授权。"
```

Counterevidence and remaining uncertainty:
- ZIP项防遍历/大小限制不覆盖manifest引用
- 签名证明作者，不证明引用安全
- Play不允许运行时插件
- 未跑恶意包/实机；没有证明明文凭据泄露

Limitations:
- 越界引用由源码验证，未运行DoS载荷或实机安装。

#### Dataflow

provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。

- **Source:** 作者签名的受保护插件，经用户安装，无需启用/配对授权。

- **Sink:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt

- **Outcome:** 恶意受保护包可使宿主读取包外JSON或无限设备流，阻塞/耗尽宿主资源；没有证明私钥/刷新凭据明文泄露。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:89-95`

provides\[\].schema/ui.cards\[\] → AlpVerifier 仅验类型 → 安装/reload → File(stagedDirectory,ref).readText() → 包外读取，dev/zero可能耗尽内存。

```kotlin
                    val entries = (JsonFields.field(declarations,"provides") as? JsonValue.JArray)?.items.orEmpty().map { entry ->
                        val cap = entry as? JsonValue.JObject ?: error("MANIFEST_INVALID")
                        val capabilityId = JsonFields.string(cap,"id") ?: error("MANIFEST_INVALID")
                        val schemaPath = JsonFields.string(cap,"schema") ?: error("MANIFEST_INVALID")
                        VerifiedCapability(verified.identity,capabilityId,JsonFields.string(cap,"version") ?: error("MANIFEST_INVALID"),
                            Json.parse(File(verified.stagedDirectory,schemaPath).readText()),
                            PluginCapabilityRisk.classify(verified.runtime.type,primitives),primitives)
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:105-113`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
                        (JsonFields.field(ui,slot) as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value }
                    }.mapNotNull { path ->
                        val file = File(verified.stagedDirectory,path)
                        if (!file.canonicalPath.startsWith(verified.stagedDirectory.canonicalPath + File.separator) || !file.isFile || file.length() > 1024*1024) null
                        else runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(file.readText()) }.getOrNull()
                    }
                    cardIds[id]=(JsonFields.field(ui,"cards") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { path ->
                        (path as? JsonValue.JString)?.value?.let { name -> runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(File(verified.stagedDirectory,name).readText()).id }.getOrNull() }
                    }.toSet()
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:406-423`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    private fun parseCapabilityEntries(capabilities: JsonValue.JObject): Set<String> {
        val array = capabilities.get("provides") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:provides")
        val ids = LinkedHashSet<String>()
        for (item in array.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:provides")
            rejectUnknownFields(obj, setOf("id", "version", "schema"), "provides")
            val id = (obj.get("id") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:provides")
            if ((obj.get("version") as? JsonValue.JString)?.value == null ||
                (obj.get("schema") as? JsonValue.JString)?.value == null
            ) {
                throw PackageRejected("SCHEMA_INVALID:provides")
            }
            ids += id
        }
        return ids
    }
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt:104-112`

所有静态资源必须为已验签包内普通文件且读取有界。

```kotlin
    fun refresh() { host?.reload(); installed = resolvedStore.listInstalled() }
    fun installBytes(bytes: ByteArray, approved: Boolean = false) {
        if (installing) return
        installing = true
        installScope.launch {
            try {
                val view = runInterruptible(Dispatchers.IO) { resolvedStore.install(bytes,approved) }
                refresh(); pendingApproval = null
                notice = "已安装 ${view.pluginId} v${view.version}，可启用并为当前配对授权。"
```

#### Reachability

作者签名的受保护插件，经用户安装，无需启用/配对授权。

- **Attacker:** 作者签名的受保护插件，经用户安装，无需启用/配对授权。

- **Entry point:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt

- **Outcome:** 恶意受保护包可使宿主读取包外JSON或无限设备流，阻塞/耗尽宿主资源；没有证明私钥/刷新凭据明文泄露。

Existing controls:
- ZIP项防遍历/大小限制不覆盖manifest引用
- 签名证明作者，不证明引用安全
- Play不允许运行时插件
- 未跑恶意包/实机；没有证明明文凭据泄露

Limitations:
- 越界引用由源码验证，未运行DoS载荷或实机安装。

#### Severity

**Medium** — 源码确认权限或资源边界失效；影响受已安装/授权或本地日志权限等前提约束，未扩大为无条件远程攻破。

若实机或部署证据证明可直接触达更高价值资源则上调；有效的统一授权/路径校验可阻断该路径。

#### Remediation

验包及运行统一验证包内签名索引、规范路径、普通文件与大小；去掉ui.cards未经检查的第二次读取。

Tests:
- 验证合法签名包的schema、UI和payload引用不在签名索引时被拒绝
- 验证点段、绝对路径、非普通文件和过大JSON均被拒绝，普通合法包可安装

Preventive controls:
- 把安全声明完整保留至实际消费者，并以越权拒绝为回归断言。

<a id="finding-2"></a>

### [2] 受保护联网忽略路径范围

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | 独立审计与父Agent核对具体源码控制；有最小运行证据的部分已标明，其余未声称实机利用。 |
| Category | 授权范围未完整校验 |
| CWE | CWE-863 |
| Affected lines | apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:290-315, apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:46-50, apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:112-125 |

#### Summary

security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。 插件可访问被批准域名内范围外路径及同域重定向；目标服务认证仍有效，非任意主机SSRF。

#### Root Cause

每跳匹配主机、方法、路径范围。 当前数据流：security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:290-315`

security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。

```kotlin
                val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText()))!!
                val rules = (JsonFields.field(JsonFields.obj(JsonFields.field(manifest,"security")),"network") as? JsonValue.JArray)?.items.orEmpty()
                val methods = rules.flatMap { rule -> (JsonFields.field(rule as? JsonValue.JObject,"methods") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value } }.toSet()
                val byHost=rules.mapNotNull { rule ->
                    val r=rule as? JsonValue.JObject ?: return@mapNotNull null
                    val host=JsonFields.string(r,"host") ?: return@mapNotNull null
                    host.lowercase().trimEnd('.') to JsonFields.strings(r,"methods").toSet()
                }.toMap()
                val usage=EncryptedDocuments(context,"plugin-network-usage:${call.identity}:${call.accountId}:${call.pairingId}")
                MediatedNetworkProxy(NetworkAllowlist(verified.security.surface.networkHosts,methods,byHost),object : MediatedTransport {
                    override fun exchange(request: MediatedRequest): MediatedResponse = runBlocking {
                        val transport = GatewayTransport(GatewayProfile("plugin","plugin","plugin","https://${request.host}"),maximumResponseBytes=minOf(request.maximumResponseBytes.toLong(),call.budget.maxOutputBytes/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val result = withTimeout(call.budget.maxInvocationMillis) { transport.execute(WireRequest(request.method,request.pathAndQuery,
                            request.headers.map { RawHeader(it.key,it.value) },request.body ?: ByteArray(0))) }
                        MediatedResponse(result.status,result.headers.associate { it.name to it.value },result.body)
                    }
                },call.budget.maxDailyNetworkBytes,usageStore=object:NetworkUsageStore {
                    override fun read():NetworkUsage?=usage.read("daily")?.let { bytes -> val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("NETWORK_USAGE_INVALID");NetworkUsage(JsonFields.long(o,"start") ?: error("NETWORK_USAGE_INVALID"),JsonFields.long(o,"spent") ?: error("NETWORK_USAGE_INVALID")) }
                    override fun write(value:NetworkUsage)=usage.write("daily",Json.canonical(Json.of(mapOf("start" to value.windowStartMillis,"spent" to value.spentBytes))).toByteArray())
                })
            } finally { verified.stagedDirectory.deleteRecursively() }
        }
        val response = synchronized(proxy) { proxy.exchange(MediatedRequest(uri.scheme,uri.host,if (uri.port == -1) 443 else uri.port,
            JsonFields.string(args,"method") ?: "GET", (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
            JsonFields.obj(JsonFields.field(args,"headers"))?.fields.orEmpty().associate { it.first to ((it.second as? JsonValue.JString)?.value ?: error("SCHEMA_INVALID")) },
            JsonFields.string(args,"body")?.let { Base64.getDecoder().decode(it) })) }
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:46-50`

每跳匹配主机、方法、路径范围。

```kotlin
data class NetworkAllowlist(
    val hosts: Set<String>,
    val methods: Set<String>,
    val methodsByHost:Map<String,Set<String>> = emptyMap(),
)
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:112-125`

每跳匹配主机、方法、路径范围。

```kotlin
    private fun checkAllowed(request: MediatedRequest) {
        if (!request.scheme.equals("https", ignoreCase = true)) {
            throw NetworkDenied("SCHEME")
        }
        if (request.port != 443) throw NetworkDenied("PORT")
        if (!isAllowedHost(request.host)) throw NetworkDenied("HOST_NOT_ALLOWED")
        val methods=if (allowlist.methodsByHost.isEmpty()) allowlist.methods else allowlist.methodsByHost[request.host.lowercase().trimEnd('.')].orEmpty()
        if (!methods.any { it.equals(request.method, ignoreCase = true) }) {
            throw NetworkDenied("METHOD_NOT_ALLOWED")
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        val normalised = host.lowercase().trimEnd('.')
```

#### Validation

当前Kotlin源码临时编译；真实MediatedNetworkProxy把同域/private/admin交给替身传输层。host加载规则未保存pathPrefix。探针未访问网络。

Validation method: 独立静态审计、父Agent源码复核和最小本地探针

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:290-315`

security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。

```kotlin
                val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText()))!!
                val rules = (JsonFields.field(JsonFields.obj(JsonFields.field(manifest,"security")),"network") as? JsonValue.JArray)?.items.orEmpty()
                val methods = rules.flatMap { rule -> (JsonFields.field(rule as? JsonValue.JObject,"methods") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value } }.toSet()
                val byHost=rules.mapNotNull { rule ->
                    val r=rule as? JsonValue.JObject ?: return@mapNotNull null
                    val host=JsonFields.string(r,"host") ?: return@mapNotNull null
                    host.lowercase().trimEnd('.') to JsonFields.strings(r,"methods").toSet()
                }.toMap()
                val usage=EncryptedDocuments(context,"plugin-network-usage:${call.identity}:${call.accountId}:${call.pairingId}")
                MediatedNetworkProxy(NetworkAllowlist(verified.security.surface.networkHosts,methods,byHost),object : MediatedTransport {
                    override fun exchange(request: MediatedRequest): MediatedResponse = runBlocking {
                        val transport = GatewayTransport(GatewayProfile("plugin","plugin","plugin","https://${request.host}"),maximumResponseBytes=minOf(request.maximumResponseBytes.toLong(),call.budget.maxOutputBytes/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val result = withTimeout(call.budget.maxInvocationMillis) { transport.execute(WireRequest(request.method,request.pathAndQuery,
                            request.headers.map { RawHeader(it.key,it.value) },request.body ?: ByteArray(0))) }
                        MediatedResponse(result.status,result.headers.associate { it.name to it.value },result.body)
                    }
                },call.budget.maxDailyNetworkBytes,usageStore=object:NetworkUsageStore {
                    override fun read():NetworkUsage?=usage.read("daily")?.let { bytes -> val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("NETWORK_USAGE_INVALID");NetworkUsage(JsonFields.long(o,"start") ?: error("NETWORK_USAGE_INVALID"),JsonFields.long(o,"spent") ?: error("NETWORK_USAGE_INVALID")) }
                    override fun write(value:NetworkUsage)=usage.write("daily",Json.canonical(Json.of(mapOf("start" to value.windowStartMillis,"spent" to value.spentBytes))).toByteArray())
                })
            } finally { verified.stagedDirectory.deleteRecursively() }
        }
        val response = synchronized(proxy) { proxy.exchange(MediatedRequest(uri.scheme,uri.host,if (uri.port == -1) 443 else uri.port,
            JsonFields.string(args,"method") ?: "GET", (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
            JsonFields.obj(JsonFields.field(args,"headers"))?.fields.orEmpty().associate { it.first to ((it.second as? JsonValue.JString)?.value ?: error("SCHEMA_INVALID")) },
            JsonFields.string(args,"body")?.let { Base64.getDecoder().decode(it) })) }
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:46-50`

每跳匹配主机、方法、路径范围。

```kotlin
data class NetworkAllowlist(
    val hosts: Set<String>,
    val methods: Set<String>,
    val methodsByHost:Map<String,Set<String>> = emptyMap(),
)
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:112-125`

每跳匹配主机、方法、路径范围。

```kotlin
    private fun checkAllowed(request: MediatedRequest) {
        if (!request.scheme.equals("https", ignoreCase = true)) {
            throw NetworkDenied("SCHEME")
        }
        if (request.port != 443) throw NetworkDenied("PORT")
        if (!isAllowedHost(request.host)) throw NetworkDenied("HOST_NOT_ALLOWED")
        val methods=if (allowlist.methodsByHost.isEmpty()) allowlist.methods else allowlist.methodsByHost[request.host.lowercase().trimEnd('.')].orEmpty()
        if (!methods.any { it.equals(request.method, ignoreCase = true) }) {
            throw NetworkDenied("METHOD_NOT_ALLOWED")
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        val normalised = host.lowercase().trimEnd('.')
```

Counterevidence and remaining uncertainty:
- 主机/方法/443/TLS/三次重定向/预算仍有效
- 网络原语write，远程调用仍前台确认
- 未证明外部管理接口可利用，非任意主机SSRF

Limitations:
- 仅本地替身传输层，无真实接口利用或数据泄露验证。

#### Dataflow

security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。

- **Source:** 安装启用获网络原语授权的受保护插件。

- **Sink:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt

- **Outcome:** 插件可访问被批准域名内范围外路径及同域重定向；目标服务认证仍有效，非任意主机SSRF。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:290-315`

security.network.pathPrefix → host只取hosts/methods → allowlist无路径字段 → checkAllowed不访问pathAndQuery → 原路径交GatewayTransport。

```kotlin
                val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText()))!!
                val rules = (JsonFields.field(JsonFields.obj(JsonFields.field(manifest,"security")),"network") as? JsonValue.JArray)?.items.orEmpty()
                val methods = rules.flatMap { rule -> (JsonFields.field(rule as? JsonValue.JObject,"methods") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value } }.toSet()
                val byHost=rules.mapNotNull { rule ->
                    val r=rule as? JsonValue.JObject ?: return@mapNotNull null
                    val host=JsonFields.string(r,"host") ?: return@mapNotNull null
                    host.lowercase().trimEnd('.') to JsonFields.strings(r,"methods").toSet()
                }.toMap()
                val usage=EncryptedDocuments(context,"plugin-network-usage:${call.identity}:${call.accountId}:${call.pairingId}")
                MediatedNetworkProxy(NetworkAllowlist(verified.security.surface.networkHosts,methods,byHost),object : MediatedTransport {
                    override fun exchange(request: MediatedRequest): MediatedResponse = runBlocking {
                        val transport = GatewayTransport(GatewayProfile("plugin","plugin","plugin","https://${request.host}"),maximumResponseBytes=minOf(request.maximumResponseBytes.toLong(),call.budget.maxOutputBytes/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val result = withTimeout(call.budget.maxInvocationMillis) { transport.execute(WireRequest(request.method,request.pathAndQuery,
                            request.headers.map { RawHeader(it.key,it.value) },request.body ?: ByteArray(0))) }
                        MediatedResponse(result.status,result.headers.associate { it.name to it.value },result.body)
                    }
                },call.budget.maxDailyNetworkBytes,usageStore=object:NetworkUsageStore {
                    override fun read():NetworkUsage?=usage.read("daily")?.let { bytes -> val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("NETWORK_USAGE_INVALID");NetworkUsage(JsonFields.long(o,"start") ?: error("NETWORK_USAGE_INVALID"),JsonFields.long(o,"spent") ?: error("NETWORK_USAGE_INVALID")) }
                    override fun write(value:NetworkUsage)=usage.write("daily",Json.canonical(Json.of(mapOf("start" to value.windowStartMillis,"spent" to value.spentBytes))).toByteArray())
                })
            } finally { verified.stagedDirectory.deleteRecursively() }
        }
        val response = synchronized(proxy) { proxy.exchange(MediatedRequest(uri.scheme,uri.host,if (uri.port == -1) 443 else uri.port,
            JsonFields.string(args,"method") ?: "GET", (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
            JsonFields.obj(JsonFields.field(args,"headers"))?.fields.orEmpty().associate { it.first to ((it.second as? JsonValue.JString)?.value ?: error("SCHEMA_INVALID")) },
            JsonFields.string(args,"body")?.let { Base64.getDecoder().decode(it) })) }
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:46-50`

每跳匹配主机、方法、路径范围。

```kotlin
data class NetworkAllowlist(
    val hosts: Set<String>,
    val methods: Set<String>,
    val methodsByHost:Map<String,Set<String>> = emptyMap(),
)
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/MediatedNetworkProxy.kt:112-125`

每跳匹配主机、方法、路径范围。

```kotlin
    private fun checkAllowed(request: MediatedRequest) {
        if (!request.scheme.equals("https", ignoreCase = true)) {
            throw NetworkDenied("SCHEME")
        }
        if (request.port != 443) throw NetworkDenied("PORT")
        if (!isAllowedHost(request.host)) throw NetworkDenied("HOST_NOT_ALLOWED")
        val methods=if (allowlist.methodsByHost.isEmpty()) allowlist.methods else allowlist.methodsByHost[request.host.lowercase().trimEnd('.')].orEmpty()
        if (!methods.any { it.equals(request.method, ignoreCase = true) }) {
            throw NetworkDenied("METHOD_NOT_ALLOWED")
        }
    }

    private fun isAllowedHost(host: String): Boolean {
        val normalised = host.lowercase().trimEnd('.')
```

#### Reachability

安装启用获网络原语授权的受保护插件。

- **Attacker:** 安装启用获网络原语授权的受保护插件。

- **Entry point:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt

- **Outcome:** 插件可访问被批准域名内范围外路径及同域重定向；目标服务认证仍有效，非任意主机SSRF。

Existing controls:
- 主机/方法/443/TLS/三次重定向/预算仍有效
- 网络原语write，远程调用仍前台确认
- 未证明外部管理接口可利用，非任意主机SSRF

Limitations:
- 仅本地替身传输层，无真实接口利用或数据泄露验证。

#### Severity

**Medium** — 源码确认权限或资源边界失效；影响受已安装/授权或本地日志权限等前提约束，未扩大为无条件远程攻破。

若实机或部署证据证明可直接触达更高价值资源则上调；有效的统一授权/路径校验可阻断该路径。

#### Remediation

完整规则保留，规范路径按段边界匹配pathPrefix，所有重定向同样校验编码与点段。

Tests:
- 声明/v1/public/时拒绝/private/admin并确保传输层不被调用
- 同域重定向及编码/点段越界拒绝，合法子路径允许

Preventive controls:
- 把安全声明完整保留至实际消费者，并以越权拒绝为回归断言。

<a id="finding-3"></a>

### [3] 远程读取固定前台标识，绕过后台授权

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | 独立审计与父Agent核对具体源码控制；有最小运行证据的部分已标明，其余未声称实机利用。 |
| Category | 授权范围未完整校验 |
| CWE | CWE-863 |
| Affected lines | apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:83-91, apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247, apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt:269-271, apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:768-772 |

#### Summary

device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。 已授前台读取的Gateway在App后台且进程存活时读取通知/通话记录，不需要后台授权；仍需已有读取/插件/配对/系统权限。

#### Root Cause

后台必须manifest请求/手机允许/配对后台授权共同成立。 当前数据流：device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:83-91`

device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。

```kotlin
                val requiresConfirmation = JsonFields.bool(request,"requiresForegroundConfirmation") == true ||
                    JsonFields.string(request,"risk") in setOf("write","high-privilege-ephemeral") || entry.risk in setOf("write","high-privilege-ephemeral")
                if (requiresConfirmation && !confirm(id,entry.identity,key,parameters,request)) result = DeviceRequestResult.Denied(mapOf("code" to "LOCAL_CONFIRMATION_REQUIRED"))
                else {
                    currentCoroutineContext().ensureActive()
                    check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() && grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                    invocationStarted=true
                    val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id) }
                    result = DeviceRequestResult.Succeeded(mapOf("result" to Json.parse(output.decodeToString())))
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
    fun invoke(identity: PluginIdentity, accountId: String, capability: String, parameters: JsonValue, correlationId: String, background: Boolean = false): ByteArray {
        val entry = entries(identity.pluginId).firstOrNull { it.identity == identity && it.key == capability } ?: throw CapabilityDenied(capability)
        check(CapabilitySchemaValidator.accepts(entry.schema,parameters)) { "SCHEMA_INVALID" }
        val pairingId = grants.state.value?.pairingId ?: error("PAIRING_REQUIRED")
        return kernel.invoke(identity,accountId,pairingId,capability,Json.canonical(parameters).toByteArray(),
            SessionConstraints(entry.primitives + entry.key,background,correlationId)).output
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt:269-271`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
            if (session.background && !EffectiveCapabilities.compute(CapabilityInputs(hostEnvelope.primitives,
                phoneLimits.primitives, registration.declaredPrimitives, true, grants(pairingId), session)).backgroundAllowed) {
                throw CapabilityDenied("kernel.background.run")
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:768-772`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
                foreignChange = { executionDriver?.cancelAll(); pairingGrants.clearCurrent(); pairingGrants.bind(binding) })
            capabilityPublisher = publisher
            val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) })
```

#### Validation

父Agent核对DeviceExecutionDriver、host默认值、Kernel后台分支和进程级事件消费者；当前Kotlin纯函数探针确认无后台grant时background=true被判backgroundAllowed=false，生产调用却传默认false。

Validation method: 独立静态审计、父Agent源码复核和最小本地探针

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:83-91`

device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。

```kotlin
                val requiresConfirmation = JsonFields.bool(request,"requiresForegroundConfirmation") == true ||
                    JsonFields.string(request,"risk") in setOf("write","high-privilege-ephemeral") || entry.risk in setOf("write","high-privilege-ephemeral")
                if (requiresConfirmation && !confirm(id,entry.identity,key,parameters,request)) result = DeviceRequestResult.Denied(mapOf("code" to "LOCAL_CONFIRMATION_REQUIRED"))
                else {
                    currentCoroutineContext().ensureActive()
                    check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() && grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                    invocationStarted=true
                    val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id) }
                    result = DeviceRequestResult.Succeeded(mapOf("result" to Json.parse(output.decodeToString())))
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
    fun invoke(identity: PluginIdentity, accountId: String, capability: String, parameters: JsonValue, correlationId: String, background: Boolean = false): ByteArray {
        val entry = entries(identity.pluginId).firstOrNull { it.identity == identity && it.key == capability } ?: throw CapabilityDenied(capability)
        check(CapabilitySchemaValidator.accepts(entry.schema,parameters)) { "SCHEMA_INVALID" }
        val pairingId = grants.state.value?.pairingId ?: error("PAIRING_REQUIRED")
        return kernel.invoke(identity,accountId,pairingId,capability,Json.canonical(parameters).toByteArray(),
            SessionConstraints(entry.primitives + entry.key,background,correlationId)).output
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt:269-271`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
            if (session.background && !EffectiveCapabilities.compute(CapabilityInputs(hostEnvelope.primitives,
                phoneLimits.primitives, registration.declaredPrimitives, true, grants(pairingId), session)).backgroundAllowed) {
                throw CapabilityDenied("kernel.background.run")
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:768-772`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
                foreignChange = { executionDriver?.cancelAll(); pairingGrants.clearCurrent(); pairingGrants.bind(binding) })
            capabilityPublisher = publisher
            val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) })
```

Counterevidence and remaining uncertainty:
- 仍需读取能力、作者、提供者、系统权限、配对与期限
- 写/高权限仍前台确认
- 定时调用传true
- 系统可能杀进程不等同授权

Limitations:
- 纯内核判定探针已运行；未跑设备生命周期与远程Gateway完整闭环。

#### Dataflow

device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。

- **Source:** 已配对并获读取授权但未获后台授权的恶意Gateway。

- **Sink:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt

- **Outcome:** 已授前台读取的Gateway在App后台且进程存活时读取通知/通话记录，不需要后台授权；仍需已有读取/插件/配对/系统权限。

**实际入口或敏感消费** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt:83-91`

device.requested → 认证请求核对claim → read不confirm → host.invoke省略background → 默认false → Kernel只在true时后台拒绝。

```kotlin
                val requiresConfirmation = JsonFields.bool(request,"requiresForegroundConfirmation") == true ||
                    JsonFields.string(request,"risk") in setOf("write","high-privilege-ephemeral") || entry.risk in setOf("write","high-privilege-ephemeral")
                if (requiresConfirmation && !confirm(id,entry.identity,key,parameters,request)) result = DeviceRequestResult.Denied(mapOf("code" to "LOCAL_CONFIRMATION_REQUIRED"))
                else {
                    currentCoroutineContext().ensureActive()
                    check(Instant.parse(JsonFields.string(request,"expiresAt")!!).toEpochMilli() > System.currentTimeMillis() && grantRevision == currentGrantRevision()) { "REQUEST_LEASE_EXPIRED" }
                    invocationStarted=true
                    val output = runInterruptible(Dispatchers.IO) { host.invoke(entry.identity,accountId,key,parameters,id) }
                    result = DeviceRequestResult.Succeeded(mapOf("result" to Json.parse(output.decodeToString())))
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:242-247`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
    fun invoke(identity: PluginIdentity, accountId: String, capability: String, parameters: JsonValue, correlationId: String, background: Boolean = false): ByteArray {
        val entry = entries(identity.pluginId).firstOrNull { it.identity == identity && it.key == capability } ?: throw CapabilityDenied(capability)
        check(CapabilitySchemaValidator.accepts(entry.schema,parameters)) { "SCHEMA_INVALID" }
        val pairingId = grants.state.value?.pairingId ?: error("PAIRING_REQUIRED")
        return kernel.invoke(identity,accountId,pairingId,capability,Json.canonical(parameters).toByteArray(),
            SessionConstraints(entry.primitives + entry.key,background,correlationId)).output
```

**关联控制与反证** — `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt:269-271`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
            if (session.background && !EffectiveCapabilities.compute(CapabilityInputs(hostEnvelope.primitives,
                phoneLimits.primitives, registration.declaredPrimitives, true, grants(pairingId), session)).backgroundAllowed) {
                throw CapabilityDenied("kernel.background.run")
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt:768-772`

后台必须manifest请求/手机允许/配对后台授权共同成立。

```kotlin
                foreignChange = { executionDriver?.cancelAll(); pairingGrants.clearCurrent(); pairingGrants.bind(binding) })
            capabilityPublisher = publisher
            val executor = com.openandroidintelligence.mobile.plugins.DeviceExecutionDriver(context,sessionScope,http,pluginHost,
                session.accountId,session.deviceId,session.pairingGeneration,binding.storageKey,{ publisher.grantRevision },
                isForeground = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) })
```

#### Reachability

已配对并获读取授权但未获后台授权的恶意Gateway。

- **Attacker:** 已配对并获读取授权但未获后台授权的恶意Gateway。

- **Entry point:** apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt

- **Outcome:** 已授前台读取的Gateway在App后台且进程存活时读取通知/通话记录，不需要后台授权；仍需已有读取/插件/配对/系统权限。

Existing controls:
- 仍需读取能力、作者、提供者、系统权限、配对与期限
- 写/高权限仍前台确认
- 定时调用传true
- 系统可能杀进程不等同授权

Limitations:
- 纯内核判定探针已运行；未跑设备生命周期与远程Gateway完整闭环。

#### Severity

**Medium** — 源码确认权限或资源边界失效；影响受已安装/授权或本地日志权限等前提约束，未扩大为无条件远程攻破。

若实机或部署证据证明可直接触达更高价值资源则上调；有效的统一授权/路径校验可阻断该路径。

#### Remediation

真实执行按生命周期传后台状态，核签名manifest、手机与身份绑定后台grant，并处理执行时生命周期切换。

Tests:
- 前台只读允许，进入后台且没有background grant后拒绝且原语未执行
- 后台grant齐全时允许；授权撤销及前后台切换均重新核验

Preventive controls:
- 把安全声明完整保留至实际消费者，并以越权拒绝为回归断言。

<a id="finding-4"></a>

### [4] 同作者更新扩大HTTP方法不重新批准

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | 独立审计与父Agent核对具体源码控制；有最小运行证据的部分已标明，其余未声称实机利用。 |
| Category | 授权范围未完整校验 |
| CWE | CWE-863 |
| Affected lines | apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:9-22, apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:60-85, apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:462-506, apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:120 |

#### Summary

原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。 同作者新版扩大方法不出现权限扩张确认，继承旧启用与授权；需用户已安装该作者插件，新请求仍受目标认证和本机其他授权约束。

#### Root Cause

安全范围扩张先重新批准，不继承扩大部分权限。 当前数据流：原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。

**实际入口或敏感消费** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:9-22`

原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。

```kotlin
data class SecuritySurface(
    val kernelPrimitives: Set<String>,
    val networkHosts: Set<String>,
    val maxStorageBytes: Long,
    val maxMemoryBytes: Long,
    val maxInvocationMillis: Long,
    val maxConcurrentInvocations: Int,
    val maxDailyNetworkBytes: Long,
    val backgroundRequested: Boolean,
    val companionPackageName: String?,
    val nativeAbis: Set<String>,
)

data class InstalledSurface(
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:60-85`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
        if (after.kernelPrimitives - before.kernelPrimitives != emptySet<String>()) {
            reasons += "KERNEL_PRIMITIVE_ADDED"
        }
        if (after.networkHosts - before.networkHosts != emptySet<String>()) {
            reasons += "NETWORK_HOST_ADDED"
        }
        if (after.nativeAbis - before.nativeAbis != emptySet<String>()) {
            reasons += "NATIVE_ABI_ADDED"
        }
        if (after.companionPackageName != null && before.companionPackageName == null) {
            reasons += "COMPANION_ADDED"
        }
        if (after.backgroundRequested && !before.backgroundRequested) {
            reasons += "BACKGROUND_ENABLED"
        }
        if (
            after.maxStorageBytes > before.maxStorageBytes ||
            after.maxMemoryBytes > before.maxMemoryBytes ||
            after.maxInvocationMillis > before.maxInvocationMillis ||
            after.maxConcurrentInvocations > before.maxConcurrentInvocations ||
            after.maxDailyNetworkBytes > before.maxDailyNetworkBytes
        ) {
            reasons += "RESOURCE_LIMIT_RAISED"
        }

        return if (reasons.isEmpty()) UpdateDecision.AutoApply else UpdateDecision.RequireApproval(reasons)
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:462-506`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
    private fun parseSurface(security: JsonValue.JObject, runtimeType: String): SecuritySurface {
        val network = security.get("network") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:network")
        val hosts = network.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:networkRule")
            (obj.get("host") as? JsonValue.JString)?.value
        }

        val background = security.get("background") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:background")
        rejectUnknownFields(background, setOf("requested", "minimumIntervalSeconds"), "backgroundField")
        val backgroundRequested =
            (background.get("requested") as? JsonValue.JBoolean)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:backgroundRequested")
        // §5: requested 为 false 时契约示例写作 null；允许数字（间隔）或 null。
        when (background.get("minimumIntervalSeconds")) {
            is JsonValue.JNull, is JsonValue.JNumber -> {}
            else -> throw PackageRejected("SCHEMA_INVALID:minimumIntervalSeconds")
        }

        val resources = security.get("resources") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:resources")
        rejectUnknownFields(
            resources,
            setOf(
                "maxInvocationMillis", "maxMemoryBytes", "maxStorageBytes",
                "maxConcurrentInvocations", "maxDailyNetworkBytes",
            ),
            "resourcesField",
        )

        fun longField(name: String): Long =
            (resources.get(name) as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:$name")

        val companionPackageName = if (runtimeType == "companion") {
            val runtime = security // companion package name lives on the runtime block
            (runtime.get("packageName") as? JsonValue.JString)?.value
        } else {
            null
        }

        return SecuritySurface(
            kernelPrimitives = emptySet(),
            networkHosts = hosts,
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:120`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
                    if (preferences.getString(id,null) == verified.identity.authorKeyFingerprint && !isQuarantined(id)) enableVerified(verified)
```

#### Validation

临时编译当前AlpVerifier/PluginUpdatePolicy；通过真实Manifest解析同域GET与POST后，版本1.0.0→1.0.1被分类AutoApply。父Agent核对Installer和作者启用/身份grant持续性。

Validation method: 独立静态审计、父Agent源码复核和最小本地探针

**实际入口或敏感消费** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:9-22`

原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。

```kotlin
data class SecuritySurface(
    val kernelPrimitives: Set<String>,
    val networkHosts: Set<String>,
    val maxStorageBytes: Long,
    val maxMemoryBytes: Long,
    val maxInvocationMillis: Long,
    val maxConcurrentInvocations: Int,
    val maxDailyNetworkBytes: Long,
    val backgroundRequested: Boolean,
    val companionPackageName: String?,
    val nativeAbis: Set<String>,
)

data class InstalledSurface(
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:60-85`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
        if (after.kernelPrimitives - before.kernelPrimitives != emptySet<String>()) {
            reasons += "KERNEL_PRIMITIVE_ADDED"
        }
        if (after.networkHosts - before.networkHosts != emptySet<String>()) {
            reasons += "NETWORK_HOST_ADDED"
        }
        if (after.nativeAbis - before.nativeAbis != emptySet<String>()) {
            reasons += "NATIVE_ABI_ADDED"
        }
        if (after.companionPackageName != null && before.companionPackageName == null) {
            reasons += "COMPANION_ADDED"
        }
        if (after.backgroundRequested && !before.backgroundRequested) {
            reasons += "BACKGROUND_ENABLED"
        }
        if (
            after.maxStorageBytes > before.maxStorageBytes ||
            after.maxMemoryBytes > before.maxMemoryBytes ||
            after.maxInvocationMillis > before.maxInvocationMillis ||
            after.maxConcurrentInvocations > before.maxConcurrentInvocations ||
            after.maxDailyNetworkBytes > before.maxDailyNetworkBytes
        ) {
            reasons += "RESOURCE_LIMIT_RAISED"
        }

        return if (reasons.isEmpty()) UpdateDecision.AutoApply else UpdateDecision.RequireApproval(reasons)
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:462-506`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
    private fun parseSurface(security: JsonValue.JObject, runtimeType: String): SecuritySurface {
        val network = security.get("network") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:network")
        val hosts = network.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:networkRule")
            (obj.get("host") as? JsonValue.JString)?.value
        }

        val background = security.get("background") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:background")
        rejectUnknownFields(background, setOf("requested", "minimumIntervalSeconds"), "backgroundField")
        val backgroundRequested =
            (background.get("requested") as? JsonValue.JBoolean)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:backgroundRequested")
        // §5: requested 为 false 时契约示例写作 null；允许数字（间隔）或 null。
        when (background.get("minimumIntervalSeconds")) {
            is JsonValue.JNull, is JsonValue.JNumber -> {}
            else -> throw PackageRejected("SCHEMA_INVALID:minimumIntervalSeconds")
        }

        val resources = security.get("resources") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:resources")
        rejectUnknownFields(
            resources,
            setOf(
                "maxInvocationMillis", "maxMemoryBytes", "maxStorageBytes",
                "maxConcurrentInvocations", "maxDailyNetworkBytes",
            ),
            "resourcesField",
        )

        fun longField(name: String): Long =
            (resources.get(name) as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:$name")

        val companionPackageName = if (runtimeType == "companion") {
            val runtime = security // companion package name lives on the runtime block
            (runtime.get("packageName") as? JsonValue.JString)?.value
        } else {
            null
        }

        return SecuritySurface(
            kernelPrimitives = emptySet(),
            networkHosts = hosts,
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:120`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
                    if (preferences.getString(id,null) == verified.identity.authorKeyFingerprint && !isQuarantined(id)) enableVerified(verified)
```

Counterevidence and remaining uncertainty:
- 作者不符/降级/新原语新host预算扩张已有控制
- 无自动更新轮询，也适用于手动装新版
- 需要签名能力
- 执行pathPrefix与更新比较为不同控制

Limitations:
- 探针使用真实Manifest解析与分类器，不代表已执行完整签名安装或联网写操作。

#### Dataflow

原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。

- **Source:** 同作者签名能力持有者。

- **Sink:** apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt

- **Outcome:** 同作者新版扩大方法不出现权限扩张确认，继承旧启用与授权；需用户已安装该作者插件，新请求仍受目标认证和本机其他授权约束。

**实际入口或敏感消费** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:9-22`

原GET→更高版本同域POST → parseSurface仅networkHosts → classify无法察觉 → AutoApply安装 → 作者指纹重新启用/旧grant → 新代理允许POST。

```kotlin
data class SecuritySurface(
    val kernelPrimitives: Set<String>,
    val networkHosts: Set<String>,
    val maxStorageBytes: Long,
    val maxMemoryBytes: Long,
    val maxInvocationMillis: Long,
    val maxConcurrentInvocations: Int,
    val maxDailyNetworkBytes: Long,
    val backgroundRequested: Boolean,
    val companionPackageName: String?,
    val nativeAbis: Set<String>,
)

data class InstalledSurface(
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt:60-85`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
        if (after.kernelPrimitives - before.kernelPrimitives != emptySet<String>()) {
            reasons += "KERNEL_PRIMITIVE_ADDED"
        }
        if (after.networkHosts - before.networkHosts != emptySet<String>()) {
            reasons += "NETWORK_HOST_ADDED"
        }
        if (after.nativeAbis - before.nativeAbis != emptySet<String>()) {
            reasons += "NATIVE_ABI_ADDED"
        }
        if (after.companionPackageName != null && before.companionPackageName == null) {
            reasons += "COMPANION_ADDED"
        }
        if (after.backgroundRequested && !before.backgroundRequested) {
            reasons += "BACKGROUND_ENABLED"
        }
        if (
            after.maxStorageBytes > before.maxStorageBytes ||
            after.maxMemoryBytes > before.maxMemoryBytes ||
            after.maxInvocationMillis > before.maxInvocationMillis ||
            after.maxConcurrentInvocations > before.maxConcurrentInvocations ||
            after.maxDailyNetworkBytes > before.maxDailyNetworkBytes
        ) {
            reasons += "RESOURCE_LIMIT_RAISED"
        }

        return if (reasons.isEmpty()) UpdateDecision.AutoApply else UpdateDecision.RequireApproval(reasons)
```

**关联控制与反证** — `apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/AlpVerifier.kt:462-506`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
    private fun parseSurface(security: JsonValue.JObject, runtimeType: String): SecuritySurface {
        val network = security.get("network") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:network")
        val hosts = network.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:networkRule")
            (obj.get("host") as? JsonValue.JString)?.value
        }

        val background = security.get("background") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:background")
        rejectUnknownFields(background, setOf("requested", "minimumIntervalSeconds"), "backgroundField")
        val backgroundRequested =
            (background.get("requested") as? JsonValue.JBoolean)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:backgroundRequested")
        // §5: requested 为 false 时契约示例写作 null；允许数字（间隔）或 null。
        when (background.get("minimumIntervalSeconds")) {
            is JsonValue.JNull, is JsonValue.JNumber -> {}
            else -> throw PackageRejected("SCHEMA_INVALID:minimumIntervalSeconds")
        }

        val resources = security.get("resources") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:resources")
        rejectUnknownFields(
            resources,
            setOf(
                "maxInvocationMillis", "maxMemoryBytes", "maxStorageBytes",
                "maxConcurrentInvocations", "maxDailyNetworkBytes",
            ),
            "resourcesField",
        )

        fun longField(name: String): Long =
            (resources.get(name) as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:$name")

        val companionPackageName = if (runtimeType == "companion") {
            val runtime = security // companion package name lives on the runtime block
            (runtime.get("packageName") as? JsonValue.JString)?.value
        } else {
            null
        }

        return SecuritySurface(
            kernelPrimitives = emptySet(),
            networkHosts = hosts,
```

**关联控制与反证** — `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt:120`

安全范围扩张先重新批准，不继承扩大部分权限。

```kotlin
                    if (preferences.getString(id,null) == verified.identity.authorKeyFingerprint && !isQuarantined(id)) enableVerified(verified)
```

#### Reachability

同作者签名能力持有者。

- **Attacker:** 同作者签名能力持有者。

- **Entry point:** apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginUpdatePolicy.kt

- **Outcome:** 同作者新版扩大方法不出现权限扩张确认，继承旧启用与授权；需用户已安装该作者插件，新请求仍受目标认证和本机其他授权约束。

Existing controls:
- 作者不符/降级/新原语新host预算扩张已有控制
- 无自动更新轮询，也适用于手动装新版
- 需要签名能力
- 执行pathPrefix与更新比较为不同控制

Limitations:
- 探针使用真实Manifest解析与分类器，不代表已执行完整签名安装或联网写操作。

#### Severity

**Medium** — 源码确认权限或资源边界失效；影响受已安装/授权或本地日志权限等前提约束，未扩大为无条件远程攻破。

若实机或部署证据证明可直接触达更高价值资源则上调；有效的统一授权/路径校验可阻断该路径。

#### Remediation

安全surface包含规范化每条host/method/path范围；扩张待批准，不能沿用旧启用与扩大部分grant。

Tests:
- 同域GET→新增POST必须RequireApproval
- 扩大path范围、后台频率等安全范围必须RequireApproval；缩小范围可维持普通更新

Preventive controls:
- 把安全声明完整保留至实际消费者，并以越权拒绝为回归断言。

<a id="finding-5"></a>

### [5] E2E打印账号口令

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | 独立审计与父Agent核对具体源码控制；有最小运行证据的部分已标明，其余未声称实机利用。 |
| Category | 日志泄露秘密 |
| CWE | CWE-532 |
| Affected lines | e2e/android-cli/run-e2e-orchestrator.py:685-688, e2e/android-cli/run-e2e-orchestrator.py:588-590 |

#### Summary

自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。 传真实或复用口令且stdout被他人/日志系统读取时，该人员获得认证秘密；公开默认测试值没有新增秘密。

#### Root Cause

真实认证口令不得进入普通日志。 当前数据流：自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。

**实际入口或敏感消费** — `e2e/android-cli/run-e2e-orchestrator.py:685-688`

自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。

```python
        create_cmd = hermes_account_cli() + f' create "{username}" "{password}"'
        self.log(AgentRole.RUNNER, f"执行账号注册命令: {create_cmd}")
        rc_create, out_create, err_create = self.runner.run(create_cmd)
        cmds.append(create_cmd)
```

**关联控制与反证** — `e2e/android-cli/run-e2e-orchestrator.py:588-590`

真实认证口令不得进入普通日志。

```python
    def log(self, agent: AgentRole, message: str):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] [{agent.value}] {message}")
```

#### Validation

核对run_stage_2到log/print的直接源码数据流；现有自定义测试口令案例也在测试stdout出现。summary JSON的字段白名单不含commands_executed。

Validation method: 独立静态审计、父Agent源码复核和最小本地探针

**实际入口或敏感消费** — `e2e/android-cli/run-e2e-orchestrator.py:685-688`

自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。

```python
        create_cmd = hermes_account_cli() + f' create "{username}" "{password}"'
        self.log(AgentRole.RUNNER, f"执行账号注册命令: {create_cmd}")
        rc_create, out_create, err_create = self.runner.run(create_cmd)
        cmds.append(create_cmd)
```

**关联控制与反证** — `e2e/android-cli/run-e2e-orchestrator.py:588-590`

真实认证口令不得进入普通日志。

```python
    def log(self, agent: AgentRole, message: str):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] [{agent.value}] {message}")
```

Counterevidence and remaining uncertainty:
- 默认测试口令公开，无新增秘密
- summary JSON不导出commands_executed
- 持久stdout取决部署
- 未用凭据

Limitations:
- 未使用真实凭据；日志持久化和查看者权限取决运行环境。

#### Dataflow

自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。

- **Source:** 可读控制台/收集stdout而无口令授权的人员。

- **Sink:** e2e/android-cli/run-e2e-orchestrator.py

- **Outcome:** 传真实或复用口令且stdout被他人/日志系统读取时，该人员获得认证秘密；公开默认测试值没有新增秘密。

**实际入口或敏感消费** — `e2e/android-cli/run-e2e-orchestrator.py:685-688`

自定义password → create_cmd → self.log → print stdout，内存commands_executed也留明文。

```python
        create_cmd = hermes_account_cli() + f' create "{username}" "{password}"'
        self.log(AgentRole.RUNNER, f"执行账号注册命令: {create_cmd}")
        rc_create, out_create, err_create = self.runner.run(create_cmd)
        cmds.append(create_cmd)
```

**关联控制与反证** — `e2e/android-cli/run-e2e-orchestrator.py:588-590`

真实认证口令不得进入普通日志。

```python
    def log(self, agent: AgentRole, message: str):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] [{agent.value}] {message}")
```

#### Reachability

可读控制台/收集stdout而无口令授权的人员。

- **Attacker:** 可读控制台/收集stdout而无口令授权的人员。

- **Entry point:** e2e/android-cli/run-e2e-orchestrator.py

- **Outcome:** 传真实或复用口令且stdout被他人/日志系统读取时，该人员获得认证秘密；公开默认测试值没有新增秘密。

Existing controls:
- 默认测试口令公开，无新增秘密
- summary JSON不导出commands_executed
- 持久stdout取决部署
- 未用凭据

Limitations:
- 未使用真实凭据；日志持久化和查看者权限取决运行环境。

#### Severity

**Low** — 源码确认权限或资源边界失效；影响受已安装/授权或本地日志权限等前提约束，未扩大为无条件远程攻破。

若实机或部署证据证明可直接触达更高价值资源则上调；有效的统一授权/路径校验可阻断该路径。

#### Remediation

日志仅操作名和账号，命令展示/异常统一隐藏秘密。

Tests:
- 合成非真实口令执行账号步骤后，stdout和错误信息均不包含该标记
- commands_executed只保存隐藏秘密后的展示形式

Preventive controls:
- 把安全声明完整保留至实际消费者，并以越权拒绝为回归断言。

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| 受保护插件清单引用逃出包目录并无界读取 | not recorded | Reported | 父Agent核对验包、签名索引、安装刷新与冷启动读取：ZIP项检查未覆盖schema/ui.cards引用，存在无界readText。未执行/dev/zero或恶意安装。 |
| 远程读取固定前台标识，绕过后台授权 | not recorded | Reported | 父Agent核对DeviceExecutionDriver、host默认值、Kernel后台分支和进程级事件消费者；当前Kotlin纯函数探针确认无后台grant时background=true被判backgroundAllowed=false，生产调用却传默认false。 |
| 受保护联网忽略路径范围 | not recorded | Reported | 当前Kotlin源码临时编译；真实MediatedNetworkProxy把同域/private/admin交给替身传输层。host加载规则未保存pathPrefix。探针未访问网络。 |
| 同作者更新扩大HTTP方法不重新批准 | not recorded | Reported | 临时编译当前AlpVerifier/PluginUpdatePolicy；通过真实Manifest解析同域GET与POST后，版本1.0.0→1.0.1被分类AutoApply。父Agent核对Installer和作者启用/身份grant持续性。 |
| E2E打印账号口令 | not recorded | Reported | 核对run_stage_2到log/print的直接源码数据流；现有自定义测试口令案例也在测试stdout出现。summary JSON的字段白名单不含commands_executed。 |
| 本地E2E Shell解释候选的攻击权限 | not recorded | Rejected | 字符串Shell行为已确认，会改变含$的密码，应作为工具正确性缺陷修复。但当前main仅固定测试值，配置由操作者提供，无源证据证明低权限或远程攻击者获得新增执行权限。保留条件性危险，不报当前远程RCE。 |
| 迁移后的契约与验证工具 | not recorded | Needs follow-up | 已确认Hermes缺实现pin、Gradle外部输入未入缓存键、pin自身pytest 5失败及SSH规范化错误；它们是验证/功能缺陷，未归为当前远程攻破。当前本地双端66/66、跨宿主3/3与App摘要相等。 |
| 全库未深入审查的剩余生产与测试文件 | not recorded | Needs follow-up | 本次完整安全读取99个唯一当前跟踪文件，其余仅有架构映射/搜索或未读；未做全库穷尽。主要剩余包括bridge-contract、artifact-contract、protocol剩余代码、legacy运行时、conversation-ui及其他未列模块。 |
| 物理设备、部署宿主与两个外部插件仓 | not recorded | Needs follow-up | 没有执行设备E2E、真实宿主插件加载/生产监听，也没有审计两个外部仓内部。当前CI的Android互操作FROM-CACHE；向量通过不能替代此证据。 |
| Android、契约和独立插件消费架构 | not recorded | No issue found | 独立架构检查已完成；映射本身不算已完成安全源码审计。 |
| HTTPS/SSE/WebSocket 的身份校验与账户分区 | not recorded | No issue found | 独立审计核TLS在传认证前、禁重定向、WS hostname/SPKI；存储scope包含Gateway/账号/安装和pairing，未证实跨账号读取或身份旁路。仅覆盖已列的完整读取文件。 |
| 跟踪源码的凭据/发行身份与参考插件签名 | not recorded | No issue found | 常见秘密形式离线扫描：测试私钥用途可由测试签名消费者证明；Debug keystore为公开测试身份，Release拒绝该身份；正式reference seed来自外部，未证实嵌入生产凭据。 |

## Open Questions And Follow Up

- 未取得此次检出与App未缓存的真实HTTP互操作证据
- 两个独立仓及宿主配置需另行审查
- 网络/更新最小探针未证明真实Android服务与安装闭环；后台与路径拒绝需加入设备验证
- 审查集中于迁移和相连安全边界；剩余未深入文件不作无漏洞保证。
  - Follow-up prompt: Review deferred unit remaining-repository-source and close its stated proof gap. Paths: bridge-contract, artifact-contract, protocol, legacy/bridge-runtime, apps/android/conversation-ui.
- 需以未缓存Android互操作和明确设备/插件部署身份完成真实链路验证。
  - Follow-up prompt: Review deferred unit unexecuted-device-host-e2e and close its stated proof gap.
