# MVP implementation handoff

当前纵切包含：

- `mvp-contract/schemas/v1/`：策略、通知记录/API、助手对话的闭合 schema；
- `apps/android/`：双 APK、无系统 VPN 的 userspace Tailnet transport seam、
  通知策略/采集器、生命周期 runtime/AES-GCM outbox，以及 source-only 的封闭 capability-port、
  typed provider-contract 与高风险 control-port 授权边界（不宣称系统数据源/控制后端已实现）；
- `bridge-contract/`：配对、代际 fencing、通知 query/subscribe、ACK 与
  package/field filter 的 deterministic fake；
- `integrations/`：Hermes 历史 TypeScript 测试夹具、共享假适配器契约与
  `android-device-bridge` skill；Hermes 和 OpenClaw 的生产插件源码分别位于独立仓库。
- `openclaw-plugin-pin.json`、`gateway-contract/tools/check-openclaw-plugin-pin.py`：固定
  OpenClaw 插件提交，并在主仓 CI 中验证提交与契约 pin。
- `artifact-contract/`：图片/文件选择后的 digest、PoP、ticket、提交与
  orphan reclaim source contract；不宣称对象存储或真实上传已完成。
- `apps/android/artifact-ports/`：手机端授权选择、摘要、临时加密副本、提交回执
  与删除顺序的 source-only typed seam；不宣称 SAF、加密实现或设备验证已完成。
- `apps/android/core-model/AssistantHandoffContracts.kt`：assistant-holder 与主 APK
  之间受限的文本/opaque grant typed handoff；默认 gate deny，未实现隐式 IPC。
- `e2e/mvp/run-smoke.sh`：可在无 SDK 环境运行的跨层 smoke gate；它会把
  真实依赖锁与 Android 设备状态显示为 `PENDING/SKIPPED`。
- `e2e/mvp/run-readiness.sh`：串联 SDK-free smoke 与 WP-00..WP-10
  artifact audit；`--release` 会对依赖、工具链、设备及 P0a 门禁 fail-closed。
- `docs/mvp/mvp-dependency-lock.md` 与 `mvp-contract/tools/check-lock.ts`：
  真实依赖的 fail-closed 控制器门禁；OpenClaw 宿主与独立插件仓分列，其他依赖行的
  过期或占位证据仍会阻断整体门禁。
- `docs/mvp/p0a-gate-decisions.md`：记录已确认的 Task 7 D1–D4 与 Task 9
  产品字面值。它不会替代 Task 9 技术预检、依赖锁、设备或生产部署门禁。
- `docs/mvp/plugin-architecture-migration-evidence.md`：模块化插件架构迁移的
  自检清单与实测证据。它逐条标注 `VERIFIED` / `PENDING` / `FINDING`，只记录
  真正跑过的命令与结果；未运行的门禁不会写成通过。
- `legacy/README.md`：被取代设计与冻结代码的政策。旧 `bridge-runtime` 在
  Task 13/14/15 与 Android 门禁全部通过前**不迁入** `legacy/`。

## SDK-free checks

```bash
e2e/mvp/run-smoke.sh --sdk-free
npm run mvp:lock:check  # 其他依赖行仍有过期或占位证据时会失败
```

Protocol checks run in `.worktrees/p0a-protocol-security-model` with
`npm run typecheck && npm test -- --run`.

协议 worktree 当前验证为 **32 个测试文件/334 个测试，typecheck GREEN**。这表示
协议参考契约的确定性门禁通过，不等于 Android/Bridge 生产门禁通过。Task 9
当前的 bounded pre-replay authority gate、拒绝矩阵与允许路径已覆盖；固定跨语言
vectors、生产 cursor/ACK durability 与部署路由仍待完成。Bridge、integrations 与 MVP schema/Bridge runtime/artifact
联调为 16 个测试文件/98 个测试；Android SDK-free 静态门禁为 48 个测试。
`run-readiness.sh --sdk-free` 还会逐项列出 WP-00..WP-10 的源工件和
生产阻断原因。Bridge 仍不宣称真实网络监听或 production durable DB；当前有
`operation.claims` 本地 durable-dispatcher，以及 SQLite migration 和 userspace
ingress/health source seams。
`bridge-contract/src/durable-store.ts` 提供数据库/网络无关的
`DurableBridgeStore` transaction port 与 fail-closed marker 检查；配对、通知、
订阅状态仍是 process-local；生产 SQLite 驱动、认证 tsnet adapter、备份恢复
演练和部署尚未提供，因此不能将这些 source seam 视为生产 durable 实现证据。

本环境已有 JDK 17、Android SDK platform/build-tools 与 `adb`，但没有可用的锁定
Gradle 8.9、完整 NDK 或物理设备；因此 Android Kotlin/JUnit、APK、P0t
userspace AAR 和真实 Tailnet/Bridge E2E 仍需在锁定的 Android CI/参考设备上执行，
当前证据不宣称这些门禁已通过。
