# Android MVP skeleton

This tree is deliberately production-shaped but does not include a vendored
Android SDK or Tailscale AAR. The only network seam is
`PairedBridgeTransport.open(VerifiedPairingTransportBinding)`: callers cannot
provide an endpoint or obtain a generic socket operation.

The `tailnet-core` module is an integration spike for the confirmed project-built
minimal `tsnet-android` gomobile AAR. `TsnetLibTailscaleCore` accepts an injected
binding with `startNode`/`openPairedBridge`/`stopNode` methods and keeps node
state behind `NoBackupTailnetStateStore`; replacing that adapter must preserve
the same narrow interface.

`artifact-ports`, `capability-ports` and `control-ports` are source-only closed
contracts for selected attachments, per-capability reads, typed writes,
semantic screen actions and reviewed restricted templates. They are registered
Gradle modules and included in the root no-VPN scan even though they contain no
Android provider, screen service, encryption implementation or command
executor. The assistant-holder and main APK share a bounded text/opaque-grant
handoff contract whose default gate denies until a local user setting enables
it; no implicit IPC is implemented in this source-only slice.

## Historical MVP modules

The slices below describe retained MVP modules and their focused checks. They do
not establish that the current modular App wires those collectors or provides
the complete device execution path. The SDK-free source suite still has nine
baseline failures involving old App/Manifest/Bridge wiring and scan expectations;
see the [review record](../../docs/reviews/2026-10-02-review-remediation.zh-CN.md).

## Notification vertical slice

Fresh install is deny-first: the persisted notification policy starts with an
empty package allowlist, `METADATA` field access, `ON_DEMAND` delivery, and no
local grant. The local settings page can configure package IDs, metadata versus
content access, and `ON_DEMAND` versus `AUTO_SEND`. It also opens Android's
notification-listener settings, where the user completes the system listener
authorization. The Agent has a read-only query path; it cannot change the
local policy or system authorization.

Agent reads use the typed `NotificationAgentQueryGateway`. Each request carries
an operation ID and policy revision: identical operation retries are
idempotent, while reuse with different request data is rejected. The gateway
enforces the current local grant and revision, package filtering, content
eligibility, and metadata redaction before returning records.

`AUTO_SEND` records are written to the encrypted local outbox only when local
mode and the egress policy gate both allow them. `ON_DEMAND` reads do not create
new automatic events. Dispatcher recovery and authenticated ACK handling remain
in place for pending outbox events. The local notification authority persists
as format V2; malformed, truncated, unknown, or otherwise corrupt state fails
closed to deny-first behavior.

Focused verification for this slice:

```sh
cd apps/android
./gradlew --no-daemon --console=plain \
  :notification-collector:testDebugUnitTest \
  :policy-engine:testDebugUnitTest \
  :core-model:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :app:testDebugUnitTest
```

The focused tests cover the notification gateway/runtime, policy authority and
evaluator, core contracts, encrypted outbox/dispatcher behavior, and local app
settings. The SDK-free boundary check is:

```sh
python3 apps/android/tools/test_notification_runtime_static.py
python3 apps/android/tools/test_transport_boundary.py
```

These commands are scoped verification; they do not by themselves claim that
the full Android build is green.

The `sms-collector` module is a separate inbox-only read boundary. It has no
SMS-send, MMS, default-SMS, broadcast-receiver/platform-listener, VPN, generic
socket, URL, or process-execution surface. A local user controls `READ_SMS`, history start,
maximum records, on-demand/auto-send permission, Agent-request permission,
and the closed manual/15/30/60-minute interval choices. Periodic work is
best-effort JobScheduler work, with accepted event wire retained in an
encrypted outbox until a verified Bridge acknowledgement. See
[`docs/mvp/sms-read-readiness.md`](../../docs/mvp/sms-read-readiness.md) for
the evidence boundary and unresolved reboot-scheduling conflict.

Run the SDK-free source gate from the repository root:

```sh
python3 apps/android/tools/test_transport_boundary.py
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
```

With the pinned Android toolchain installed, run:

```sh
cd apps/android
./gradlew --no-daemon check
```

The source scaffold currently declares Gradle 8.12, AGP 8.9.2, Kotlin 2.1.20,
app compile SDK 36, target SDK 35 and min SDK 31. Only Android libraries in the
Full/Play app runtime dependency closure declare min SDK 31; standalone and
historical modules keep their separate baseline. Library modules retain compile
SDK 35. Builds and CI use JDK 21; app bytecode remains Java 17. API 36 Robolectric
tests require JDK 21. The Gradle wrapper JAR is included; the Android SDK and
native AAR are external. Actual build results
are recorded separately from controller approval under `MVP-DEP-ANDROID` and
`MVP-DEP-TSNET`; the historical dependency evidence needs renewal.

The optional `tailscale-companion`/`tailnet-core` path keeps its API 34 baseline
because the pinned tsnet native AAR requires API 34. Those modules are not in the
Full/Play host APK runtime dependency closure; core Gateway and device-plugin
workflows do not depend on that optional transport.

The SMS slice has no current device or native AAR validation in this checkout.
Its persisted JobScheduler configuration also conflicts with
the deliberate absence of `RECEIVE_BOOT_COMPLETED`; do not represent periodic
work as reboot-resilient until that reviewed policy decision is made.

## 附件导入与账号资料

- 主窗口支持系统 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 的图片、文档等 `content://` 文件分享。冷启动收到的附件在登录且目标会话选定、历史恢复完成后加入待发送区；没有会话时保留到用户创建或选择会话，旋转屏幕不会重复导入。纯文字或链接分享会明确提示复制后粘贴；`text/plain` 文件仍可作为附件分享。
- 主窗口、浮动会话面板和原生气泡共用附件选择/上传逻辑。相册支持多选；输入框的系统粘贴支持图片，同时保留普通文字和混合内容中的文字粘贴。
- 所有入口复用现有元数据解析、流式暂存、上传、校验和发送流程。处理期间切换账号、会话或发送消息，会阻止尚未处理的附件进入另一个输入草稿；读取失败的文件不阻断其余文件。
- 每批最多导入 10 个附件，超出时提示分批操作；每个输入草稿最多容纳 10 个附件，尚在准备中的附件也占用名额，防止多个导入任务绕过上限。系统相册使用相同上限。切换账号或会话时重建输入框的选区、输入法组合和撤销历史。
- 侧边栏显示真实登录账号与账号名首字符头像。点击头像使用单选系统相册，将缩放至最长边 256px 的图片副本保存到该账号的 AccountManager 公开资料，独立于相册临时 URI 权限，退出或重新登录后保留，移除账号时删除。
- Agent 状态由工作台的实时通道状态驱动，区分在线、连接中、重连和离线。名称优先使用协商响应的可选 `X-Open-Android-Intelligence-Agent-Name` 头（例如 `DSH`、`Hermes`，1–128 个字符、单行、单个值）；未提供时显示实际 `gatewayIdentity.deploymentId`。名称仅用于展示，不参与身份校验或授权，不更改 v2 JSON Schema 或契约摘要。

## Bundled device plugins

> **2026-10-28 授权规则**：Android 统一决定设备能力；已安装且启用插件的前台能力默认开启，后台同步、系统权限、数据范围和敏感操作另行控制。实现已在 `feature/android-device-authority-20261028`，待 PR 合入 `dev`。当前测试结果与待真机验收项见[实施记录](../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；旧阶段静态证据见[只读修复计划](../../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)。上方 Historical MVP modules 的旧授权描述仅记录旧模块，不定义当前规则。

Full APKs can carry three official plugins under `assets/bundled-plugins/`:
`notifications.alp`, `sms.alp` and `call-log.alp`. These are the existing
protected WASM plugins, each installed explicitly from plugin settings through
the normal Ed25519 verifier. Installation leaves them disabled. In the target
design, enabling the installed plugin enables its capabilities by default without
a separate Gateway account or pairing capability grant. Android-local data scope,
system permissions, and required confirmations remain in force. Play builds
exclude the assets.

Build the real payloads and sign the packages with a private 32-byte Ed25519
seed file (one publisher key signs all three):

```sh
cargo +1.90.0 build --locked --target wasm32-unknown-unknown --release \
  --manifest-path plugins/Cargo.toml \
  -p open-android-intelligence-notifications \
  -p open-android-intelligence-sms \
  -p open-android-intelligence-call-log
npm ci --prefix plugin-tooling
OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE=/private/path/plugin-seed.bin \
  npm run build:android-plugins --prefix plugin-tooling -- /tmp/signed-plugins
cd apps/android
OAI_BUNDLED_PLUGINS_DIR=/tmp/signed-plugins \
  ./gradlew :app:assembleFullDebug -Poai.requireBundledPlugins=true
```

Both official publishing workflows require repository secret
`OAI_PLUGIN_SIGNING_KEY_BASE64`: the base64 encoding of that private seed. Keep the
same publisher key across releases. This is separate from APK signing; the public
debug key and fixture seed cannot serve as a plugin publisher identity. Missing or
unsafe plugin keys stop publishing. The workflow checks that the final APK contains
the exact packages produced by the signed build, byte for byte for each of the
three plugins. PR CI uses an isolated random test author and does not need
production secrets.

Local source builds may omit `OAI_BUNDLED_PLUGINS_DIR`; plugin settings then
explain that a signed `.alp` must be imported. Removing the input also clears any
previous generated assets. Existing installed providers are never replaced by the
bundled install buttons; updates keep using the ordinary signed package import
path.

## Debug signing

Debug variants are signed with the checked-in key `app/keystore/debug.keystore`
(alias `androiddebugkey`, password `android`) instead of the per-machine
`~/.android/debug.keystore` that AGP would create on demand. A machine-local key
means every workstation — and every fresh CI runner — signs with a different
identity, so installing a new build over an existing one fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` until the app is uninstalled. With the pinned
key, local, CI and released debug APKs share one signing identity and can be
installed over each other. The configuration lives in the root `build.gradle.kts`
and applies to every `com.android.application` module (`app`, `assistant-holder`),
so signature-protected IPC between the holder and the host app cannot silently
break on a machine change.

Verify any debug APK against the pinned certificate:

```sh
apps/android/tools/verify-debug-signing.sh [path/to/app-full-debug.apk]
```

Preview CI and `dev-release.yml` run the same script before publishing
or publishing a debug APK, so a regression fails the workflow instead of reaching a
device. Key parameters and the regeneration procedure: `app/keystore/README.md`.
This key is for debugging only and must never sign a release build.

## Release signing

合并到 `main` 的 PR 或 main 上手动触发会构建 `:app:assembleFullRelease` 并发布
`app-full-release.apk`。Tag `vX.Y.Z` 从 App 包配置中的 `versionName` 生成；正式版本身不从 Git Tag 读取。Release builds are non-debuggable and require all four
external settings: `OAI_RELEASE_KEYSTORE`, `OAI_RELEASE_STORE_PASSWORD`,
`OAI_RELEASE_KEY_ALIAS`, and `OAI_RELEASE_KEY_PASSWORD`. Gradle refuses missing
configuration and refuses a certificate matching the checked-in public debug
identity. No permanent release key is generated by the build.

For GitHub Actions, the repository owner supplies `OAI_RELEASE_KEYSTORE_BASE64`
and the three password/alias secrets. Both the preview release workflow (`dev-release.yml`)
and the stable release workflow (`release.yml`) reuse these same secrets to sign the APKs with
the identical certificate identity. As a result, users can upgrade between preview and stable channels
seamlessly without uninstalling or losing data.
The workflow writes the decoded keystore to the runner's temporary directory with mode 0600. 构建结束后将临时密钥移至
runner 临时目录下的 `open-android-intelligence-trash/`，供人工复核。
The owner must choose and retain the release identity before a formal tag can
publish successfully.

## App 更新发布

设置中的更新检测分为正式版与预览版。新的 `dev-release.yml` 会给每次开发预览运行
生成 `vX.Y.Z-dev.<run_number>` 预发行 Tag；正式 `release.yml` 使用包配置版本生成
`vX.Y.Z`。历史 `nightly` 下载链接和 Release 仍可读取，新的工作流不再移动固定 `nightly` Tag。

两类发布流水线都会附带 `oai-update.json`，构建时注入真实版本名、安装版本代码、
源码提交、分发类型和渠道；发布前核对 APK 输出信息并验证实际签名。
包版本提取器支持 Node/RN/Electron、Flutter、Android、iOS 和 Rust/Tauri 配置。
相同源码的开发预览运行有不同 Tag，但安装版本代码保持不变，不会被更新检测当作新安装版本。
正式版与开发预览版支持统一签名（通过 CI 配置 `OAI_RELEASE_KEYSTORE_BASE64` 等环境变量），以便无缝覆盖安装。`Android APK Artifact` 工作流在任意分支推送时自动触发并上传 Actions 构建产物，不发布 GitHub Release。

完整字段定义、版本顺序和最小验证命令见
[`App 更新发布元数据 v1`](../../docs/contracts/app-update-release-v1.md)。

## Account lifecycle

Public Gateway profiles live in Android `AccountManager`; passwords and refresh
credentials are never stored there. Selecting another profile tears down the
active client and restores only that profile's credential, approved TLS identity
and event cursor. Logout revokes the target refresh credential, keeps the public
profile and preserves its pairing key. Remove-account first confirms logout
when credentials remain, then deletes that profile's local secrets and staging
data. Unpair sends the signed pairing-revocation request and removes device keys
and grants only after a matching server confirmation.

The system account authenticator refuses removal through Android Settings;
remove profiles in the App so credential revocation and local cleanup run before
their public metadata is removed. It also exposes no Gateway auth tokens.

An approved TLS identity survives logout. Cold restoration pins it before the
first network request. A changed deployment or SPKI is refused; the login form
offers explicit identity reconfirmation after the user checks the administrator's
change. This clears the old automatic-login credential before password login.
Automatic acceptance of a rotation proof is not implemented.
