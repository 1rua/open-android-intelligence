---
status: accepted
date: 2026-08-24
contract: open-android-intelligence-device-plugin-package
version: 1.0.0
---

# open-android-intelligence 设备插件包契约 v1

## 1. 适用范围

本契约定义 Android 宿主可以发现、验证和安装的 `.alp` 产物。它覆盖受保护 WASM、开发者信任原生代码和 Companion APK 三种运行类型。

“必须”表示不满足即拒绝安装或运行；“应该”表示实现只有在记录明确理由时才能偏离；“可以”表示可选行为。

## 2. 插件身份

插件身份为：

```text
(plugin.id, SHA-256(author.publicKey))
```

- `plugin.id` 必须是反向域名式命名空间，匹配 `^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$`。
- 作者密钥算法固定为 Ed25519。
- 同一 `plugin.id` 但作者公钥不同的包是不同插件，不能更新、读取数据或继承授权。
- `plugin.version` 使用 SemVer 2.0.0；同一身份不能安装低于当前版本的包，显式回滚只能选择内核保存的上一已验证版本。

插件来源 URL、仓库和索引不参与身份判定。

## 3. 确定性容器

`.alp` 是 ZIP 容器，必须满足：

- 文件项按 UTF-8 路径字节升序排列；
- 所有项使用 ZIP `STORED`，不使用实现相关压缩；
- 时间戳固定为 `1980-01-01T00:00:00Z`；
- 不写目录项、扩展字段、注释、符号链接或绝对路径；
- 路径使用 `/`，不得包含空段、`.`、`..`、反斜杠或 Unicode 非 NFC 形式；
- 单项解包大小和总解包大小都先受宿主硬上限检查；
- `manifest.json` 和 `files.json` 使用 RFC 8785 JSON Canonicalization Scheme；
- 两次使用相同输入构建必须产生逐字节相同的 `.alp`。

标准布局：

```text
manifest.json
files.json
signature.ed25519
payload/plugin.wasm                 # protected-wasm
payload/classes.dex                 # developer-native，可选
payload/lib/<android-abi>/*.so      # developer-native，可选
payload/companion.apk               # companion
ui/<name>.json                      # 可选声明式 UI
assets/<path>                       # 可选静态资源
```

一个包只能声明一种运行类型，并且只能包含该类型允许的载荷。

## 4. 文件索引与签名

`files.json` 是数组，列出除 `manifest.json`、`files.json` 和 `signature.ed25519` 之外的所有载荷、UI 与静态资源文件。Manifest 与文件索引本身由签名输入直接覆盖，不能把 `files.json` 放入自身索引：

```json
[
  {
    "path": "payload/plugin.wasm",
    "sha256": "36bf...64-lowercase-hex-chars...",
    "size": 23891
  }
]
```

条目必须按 `path` 排序且不重复。`size` 是原始字节长度，`sha256` 是小写十六进制 SHA-256。

签名输入为以下字节串：

```text
UTF8("OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n")
+ canonical(manifest.json)
+ UTF8("\n")
+ canonical(files.json)
```

`signature.ed25519` 只包含无填充 base64url 编码的 64 字节 Ed25519 签名和结尾换行。验证顺序固定为：容器边界 → 路径 → 文件数量/大小 → 文件摘要 → 作者公钥 → 签名 → Schema → 宿主兼容 → 安全变化。

## 5. Manifest

最小 `manifest.json`：

```json
{
  "schemaVersion": "1.0",
  "plugin": {
    "id": "org.example.notifications",
    "version": "1.2.0",
    "name": "Example Notifications",
    "description": "Queries notifications after local authorization"
  },
  "author": {
    "algorithm": "Ed25519",
    "publicKey": "base64url-encoded-32-byte-key"
  },
  "runtime": {
    "type": "protected-wasm",
    "abiVersion": "1.0",
    "entrypoint": "open_android_intelligence_plugin_main",
    "payload": "payload/plugin.wasm"
  },
  "compatibility": {
    "androidHost": ">=2.0.0 <3.0.0",
    "gatewayProtocol": ">=2.0 <3.0"
  },
  "capabilities": {
    "provides": [
      {
        "id": "org.example.notifications.query",
        "version": "1.0.0",
        "schema": "assets/schemas/notifications-query.json"
      }
    ],
    "depends": [],
    "kernelPrimitives": [
      {
        "id": "kernel.notifications.read",
        "version": "1.0",
        "purpose": "Return user-approved notification fields"
      }
    ]
  },
  "security": {
    "network": [],
    "background": {
      "requested": false,
      "minimumIntervalSeconds": null
    },
    "resources": {
      "maxInvocationMillis": 5000,
      "maxMemoryBytes": 16777216,
      "maxStorageBytes": 10485760,
      "maxConcurrentInvocations": 1,
      "maxDailyNetworkBytes": 0
    }
  },
  "ui": {
    "settings": [],
    "cards": []
  },
  "state": {
    "schemaVersion": 1,
    "portableExport": false
  }
}
```

所有对象默认拒绝未知字段。新增可选字段只能提升 `schemaVersion` 次版本；改变既有字段语义必须提升主版本。

### 5.1 运行类型

`protected-wasm`：

- 载荷必须是单个通过宿主验证的 WASM 模块；
- 只能导入 `open_android_intelligence_kernel_v1` ABI；
- 禁止 WASI socket、文件系统、时钟、随机数和进程接口，所需能力由内核显式导出；
- UI 只能来自已列入 `files.json` 的声明式 JSON。

`developer-native`：

- 必须声明 `entrypointClass`，可以包含 `classes.dex` 和按 ABI 列出的 `.so`；
- 只有用户已开启开发者信任模式并逐包确认后才能启用；
- 宿主明确显示该插件与 App 共享 UID 和进程，内核不承诺隔离；
- 退出开发者信任模式立即停用。

`companion`：

```json
{
  "type": "companion",
  "payload": "payload/companion.apk",
  "packageName": "org.example.openandroidintelligence.companion",
  "certificateSha256": "64-lowercase-hex-chars",
  "minVersionCode": 12,
  "ipcContract": "open-android-intelligence-companion-v1"
}
```

- 宿主必须从 PackageManager 读取真实 package name、versionCode 和当前签名证书摘要；
- APK 自报信息不能作为验证依据；
- Companion 不接收账号密码、刷新凭据、设备配对私钥或 Gateway access token；
- 每次 IPC 调用必须使用平台内核签发的单用途操作令牌。

## 6. 能力

`capabilities.provides` 中的 ID 必须位于插件作者控制的命名空间。能力 Schema 文件必须在包内、列入 `files.json` 并参与签名。

依赖项格式：

```json
{
  "capability": "org.example.contacts.lookup",
  "version": ">=1.0.0 <2.0.0",
  "required": true
}
```

插件不能声明对另一个插件 ID、文件路径、类名或代码的依赖。必需能力无可用提供者时状态为 `missing-capability`；可选能力缺失时，宿主只允许 Manifest 已声明的降级路径。

`kernelPrimitives` 是请求清单，不是授权。平台内核分别计算：

```text
有效能力 = 宿主包络 ∩ 手机级限制 ∩ 插件声明 ∩ 插件启用状态 ∩ 当前配对授权 ∩ 当前会话约束
```

任何一项缺失都失败关闭。

## 7. 网络、存储与后台

网络规则示例：

```json
{
  "scheme": "https",
  "host": "api.example.org",
  "port": 443,
  "methods": ["GET"],
  "pathPrefix": "/v1/public/",
  "purpose": "Fetch public device metadata"
}
```

受保护插件不得使用通配 host、IP 字面量、非 HTTPS scheme、动态端口或原始 socket。重定向后的每一跳重新匹配规则，DNS 解析与证书验证由平台内核完成。

可变数据命名空间为：

```text
SHA-256(authorKey) / pluginId / gatewayAccountId / androidInstallationId
```

插件只获得命名空间内的键值/Blob API，不获得真实文件路径。退出登录不删除该空间；移除本地账号、卸载插件和用户清除数据按总架构规定删除。

后台执行必须同时满足 Manifest 请求、手机级允许、当前配对 Background Sync Grant 和 Android 系统调度约束。宿主不会以常驻进程作为可用性承诺。

`kernel.scheduler.set` 接收 `runAt`、`query`、`capabilityId` 和 `capabilityVersion`。目标能力必须属于调用者当前已验证的插件身份，并且已由该配对授权、选为提供者；`query` 必须符合目标能力包内签名 Schema。宿主将目标 ID、能力版本、Schema 摘要及插件/作者/账号/配对/授权版本写入加密任务记录，定时执行重新验证这些绑定，不从插件 ID 推测能力名称或版本。缺少目标信息的旧任务进入 `target_unavailable`，不会调用猜测出的目标。SMS 参考插件在内核调用中补充自己的 `org.openandroidintelligence.sms.query@1.0.0`，其公开 schedule Schema 保持 SMS 查询参数格式。

## 8. 声明式 UI

V1 组件白名单：`section`、`text`、`status`、`toggle`、`select`、`button`、`permission-request`、`capability-picker`。每个交互提交结构化 action ID 和已验证值，由平台内核再次执行授权检查。

插件不得提交 HTML、JavaScript、WebView URL、自定义 Android View 类、任意 Intent 或可执行表达式。未知组件或属性使对应 UI contribution 不可用，但不破坏插件其他已验证能力。

## 9. 安装与更新状态机

```text
discovered
  -> downloading
  -> verifying
  -> installed-disabled
  -> enabled

verifying -> rejected
enabled -> waiting-reauthorization | missing-capability | incompatible-host
enabled -> quarantined
enabled -> rollback-available
* -> uninstalled
```

安装事务只有在容器、摘要、签名、Schema、兼容性和运行载荷全部通过后才提交。进程中断后只能回到旧已验证版本或 `installed-disabled`，不能留下部分新版本。

以下变化属于安全边界扩大，必须重新批准：

- 新增或扩大内核安全原语；
- 新增网络 host、method、path 或流量预算；
- 提高后台频率、存储、内存、并发或执行时间；
- 新增 UI action、Companion 或本机 ABI；
- 扩大可导出数据或宿主兼容范围；
- 更换作者密钥但没有已确认的轮换证明。

## 10. 卸载与清理

卸载立即停用插件、撤销全部配对授权和后台任务，删除该插件所有账号的私有存储，并使未完成操作进入明确终态。Companion APK 是否同时卸载由 Android 用户确认；即使保留 APK，宿主也撤销全部操作令牌和绑定。

最小审计保留卸载主体、时间、插件身份、动作和结果，不保留插件业务正文。

## 11. 一致性要求

插件工具链和 Android 宿主必须共享以下黄金向量：

- 相同输入产生相同 `.alp` SHA-256；
- 路径穿越、重复项、大小欺骗和 ZIP bomb 在解包前拒绝；
- 任一载荷字节变化导致摘要或签名失败；
- 同 ID 不同作者不能更新或读取数据；
- 能力、网络、资源、UI 或 Companion 扩大触发重新授权；
- 安装中断恢复旧版本或停用状态；
- Companion 证书、版本、令牌或权限错误失败关闭；
- 账号切换无法读取另一账号插件状态。

只有完整通过这些向量的包构建器、验证器和运行时才能声明支持 v1。
