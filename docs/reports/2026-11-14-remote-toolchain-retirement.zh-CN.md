# Remote 工具链退役依赖图

基线 `ce78d37e`，独立分支 `feature/dsh-remote-toolchain`；本报告只记录源码依赖与逐步验证，不代表旧消费者已经全部移除。

| 入口 | 当前依赖链 | 处理顺序与验收 |
| --- | --- | --- |
| 根 npm test/typecheck | 根工具 → gateway-contract；plugin-tooling | 改为中性插件包/Remote SDK/固定协议测试；保留签名与 schema 断言 |
| plugin-tooling、plugins manifests | `.alp` → compatibility.gatewayProtocol；六个引用清单 | 第一阶段移除旧传输兼容字段，只认 androidHost；历史/虚构传输字段拒绝，不改签名域或作者信任 |
| plugin-package | AlpVerifier schema → 强制 gatewayProtocol | 同步闭合 schema，仅改兼容字段；真实签名旧字段拒绝、Android host 范围、摘要/签名拒绝回归 |
| Android app.gradle Test | 每个测试输入注入 Core/Python/Hermes SDK/Gateway 契约路径 | App 消费者先迁 Remote；再撤整段旧 fixture 注入，不能先去掉输入让旧测试错误复用缓存 |
| gateway-client | 旧 JSON/schema/auth/event + 新 remote 包混放 | 保留 remote；中性 JSON/schema 由实际调用图抽出，旧协议断言迁档，设备能力断言迁新链 |
| smart-test / CI impact | core/hermes/dsh lanes；root full-required 触发旧 Python 套件 | Android lane/依赖扩大规则保留；生产路由切 Remote/MCP/插件，新测试验证删改名仍触发关联 lane |
| CI workflows/actions | Core wheel/旧适配器发行、历史 Gateway oracle、Android Hermes fixtures | 分阶段撤旧 jobs 与 checkout；保留 Rust→签名包 artifact、Android安全/Remote lanes、发布精确 SHA 门禁 |
| install/setup/uninstall | 全部安装/配置 Core 与适配器 | 旧脚本仅服务已退役 Core 与适配器，随被退役服务安全移走；无需为它们新增本仓 Remote installer。DSH Remote 服务/主机安装归上游，本仓手机端与本机 MCP 沿现有构建/stdio 入口交付，不虚构替代服务 |
| pins / dependency lock | OpenClaw/Hermes/tsnet 旧锁与历史验证命令 | 不生成假 Remote 版本或摘要；Remote 已固定上游 source.json 与 SDK lock 保持独立；旧锁退出生产门禁后归档 |

第一阶段受影响测试：plugin-tooling 全部清单/确定性/签名/ABI/安全种子测试；plugin-package 的 AlpManifestSchemaTest、AlpVerifierTest（以及同模块完整轻量 JVM 套件视影响决定）。App 的 TestAlpPackages 紧凑模板仅同步删除该兼容字段，其余 helper（含 SMS）保留；第一阶段当时不修改 App/UI/Push/SMS/SoM 生产源码；后续 CI 修复切片仅将既有显示文本原样搬入资源，补齐 Remote 测试清单与上游夹具边界。根目录不存在独立 `plugin-contract`，现有插件契约由 `docs/contracts/device-plugin-package-v1.md`、`plugin-tooling/src/manifest.ts` 和 `plugin-package/AlpVerifier` 三处共同承载。

旧目录仅在所有活跃消费者与测试路由迁完后，由主 worktree 统一安全移动到忽略回收区。此阶段不移动旧目录，不用删断言冒充迁移完成。

第一阶段本地证据：固定 Node 24.18.0、按 plugin-tooling 锁安装 Vitest 4.1.11，87 项测试通过且 TypeScript 类型检查通过。确定性包摘要因清单字节变化真实重算为 `556e4bb92990ccb7943919cac2bd6a1133880fff26747afe77d85cf4588773d7`；签名域、固定测试作者与输入载荷不变。Android 模块验证待独占 Gradle 槽，不以 Node 结果替代。


CI 修复切片（基于 `1ebb1696`，验证待实际执行）：根 TypeScript 排除只限两组固定上游 checkout 的 fixtures，根工具仍完整检查；App 测试清单补齐七个已存在的 Remote/手机测试，保留全类覆盖门禁。手机门面仅将 title 与 error.message 原文移入库资源，由生产 Android Context 明确加载，所有协议 code/type/标识保持固定。界面与备份提示原文入资源，不新增基线豁免。独立协议测试注入测试文本，生产组合使用资源文本。

旧安装器无需新建替代安装器即可随旧 Core 服务退役；“必须先实现新 installer”不是现行规格要求，也不构成清退阻塞。旧脚本的消费闭包和对应旧服务专属验证将一并退出；Android 手机能力、Remote 协议、安全和插件签名门禁继续保留。


## 独立服务退役切片

退役起点为 `a016b26b`。脚本、测试和适配器均按受版本控制的明确文件白名单安全移入工作区回收目录，不携带未跟踪文件。

| 范围 | 归属证据与处理 |
| --- | --- |
| scripts 的 6 个文件 | README 与 install/setup/uninstall 只提供 Core 安装配置；installer.py 导入 oai_core.scripts_runtime，core_setup.py 调 CoreConfig/服务入口；全部退役 |
| tests 的 9 个文件 | 8 个安装/恢复/配置/卸载测试仅验证上述脚本或 Core 运行时；test_oai_client_skill.py 验证旧 oai CLI 和 Core 技能安装；全部退役 |
| 两个 session-adapter 的 39 个文件 | Hermes Python 包与 DSH Cordis 包只连接旧 Core；包含各自清单、安装器与专属测试；全部退役 |
| integrations/shared 的 5 个文件与共享 npm 配置 | 仅旧 adapter/legacy Hermes 合约替身及其 npm 测试配置，无 Remote 生产消费；全部退役 |
| 三组通信观察/测量/审计工具及测试 | 读取旧 Core/Hermes 账本与旧测量报告，不是 Remote 验收；随服务退役 |
| 旧依赖锁与 OpenClaw pin 验证工具 | 旧服务/契约工具链门禁退出，改为固定 Remote 原文、协议向量、许可证 bytes/SHA-256 完整性门禁 |

新路由以 tools/ci/impact.py 为唯一影响图；本地 smart-test 与 CI 共用该图。Remote、手机 MCP、插件签名、Android 模块和 App 全类清单门禁继续执行；删除/改名与未知路径仍扩大范围。独立 Core/Hermes/DSH jobs 不再执行。
工作流改为 remote-client.yml，开发版发布及同 SHA 发布门禁同步引用新文件。总检查 CI Gate 保持外部规则标识；完整 CI 的 Node.js & Gateway Conformance 显示名仅兼容现有规则，实际内容只执行 Remote 与插件，不再执行旧 Gateway 合约或独立服务回归。

本切片仍保留 Android 专属旧 HTTP 测试当前读取的 oai-core/gateway-contract 与固定历史夹具，避免删输入后复用旧测试缓存。它们是后续旧生产/测试迁移的剩余退出项，不能据此声明全部旧依赖已清零。Remote、通用 JSON、诊断、手机能力和签名校验不在搬移清单内。

WebRTC 上游原文 `apps/android/gateway-client/src/main/assets/licenses/remote/webrtc-native-and-third-party.txt` 保留原始空白。
最终 diff 空白检查仅对该单一路径例外；同门禁强制验证 sources.json 的全部许可资产，以及该 notices 的固定 788358 字节和 SHA-256 `d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`。其余本仓源码空白检查保持开启。


独立评审修正：许可资产在影响图中先于普通文档判定，新增/修改/删除 `.txt` 原文均触发 Remote 来源完整性门禁；新增九组删改选路回归。完整 CI 继续执行 Android 发布元数据、包版本提取、发布说明和内置 APK 插件验证工具的四组 unittest，它们不属于旧 Core 专属测试，不能随旧服务撤销。新增工作流覆盖断言防止再次遗漏。
