# Open Android Intelligence (OAI) 文档导航索引

本文档为仓库 `docs/` 目录的全局索引，帮助开发者快速定位现行架构规范、协议契约、开发指南与历史归档材料。

> [!IMPORTANT]
> 仓库主架构已切换至 **DSH Remote**。凡涉及早期 `OAI Core`、独立中心服务端部署、`Gateway Protocol v2` 会话桥或 `Tailnet` 虚拟组网的内容均属于**历史归档/参考背景**；现行开发请以标注“现行有效”的文档及根目录 [PROJECT.md](../PROJECT.md)、[TEST_INFRA.md](../TEST_INFRA.md) 为准。

---

## 1. 现行必读入口（快速上手）

| 主题 | 推荐文档 | 说明 |
| --- | --- | --- |
| **全局项目架构** | [PROJECT.md](../PROJECT.md) | DSH Remote 三端架构、仓库模块拓扑与本机安全边界 |
| **全局设计规范** | [DESIGN.md](../DESIGN.md) | 完全遵循 Google Material Design 3 官方标准的色彩、动效、交互与布局规范 |
| **全局测试与门禁** | [TEST_INFRA.md](../TEST_INFRA.md) | `test:fast`/`test:pr`/`test:full`、CI 影响图与各项质量门禁 |
| **核心词汇表** | [CONTEXT.md](../CONTEXT.md) | DSH Remote、Virtual Host、Protected Mode 等统一领域术语 |
| **DSH Remote 总规格** | [2026-11-13-dsh-remote-architecture-refactor.md](specs/2026-11-13-dsh-remote-architecture-refactor.md) | 现行目标架构、模块边界与 REM-01~20 需求定义 |
| **上游固定事实基线** | [2026-11-13-dsh-remote-baseline.zh-CN.md](architecture/2026-11-13-dsh-remote-baseline.zh-CN.md) | `ds-harness-remote` 固定提交事实核验与 OAI 适配边界 |
| **控制契约** | [oai-control-rpc-v1.md](contracts/oai-control-rpc-v1.md) | 手机 Virtual Host `phone-capabilities` 的 RPC、CLI 与回执契约 |
| **迁移验收台账** | [2026-11-14-dsh-remote-acceptance-index.zh-CN.md](reviews/2026-11-14-dsh-remote-acceptance-index.zh-CN.md) | 当前实现检查点、自动化结果与真机验收边界台账 |
| **Android 国际化指南** | [android-i18n.zh-CN.md](guides/android-i18n.zh-CN.md) | `:ui-resources` 模块规范、命名、占位符与硬编码中文扫描门禁 |
| **本机全局设备授权指南** | [global-device-authorization.zh-CN.md](guides/global-device-authorization.zh-CN.md) | 安装实例全局开关、授权模式与主动推送隔离规则 |

---

## 2. `docs/` 各子目录总览与状态分类

| 目录 | 定位与核心内容 | 状态分类 | 关键代表文档 |
| --- | --- | --- | --- |
| `specs/` | 产品与子系统权威设计规格 | **部分现行 / 部分替代** | 现行：[DSH Remote 架构重构规格](specs/2026-11-13-dsh-remote-architecture-refactor.md)、[全局设备授权规格](specs/2026-11-01-global-device-authorization.md)、[内建设备能力重构](specs/2026-11-08-builtin-capability-consolidation.md)；早期 Bridge/Core 规格仅作历史背景 |
| `architecture/` | 系统事实基线、模块边界与稳定性架构记录 | **部分现行 / 含历史基线** | 现行：[DSH Remote 事实基线](architecture/2026-11-13-dsh-remote-baseline.zh-CN.md)、[设置系统](architecture/2026-11-05-android-settings-system.zh-CN.md)、[设置备份](architecture/2026-11-08-android-settings-backup.zh-CN.md)；历史：[OAI Core 架构事实基线](architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md) |
| `contracts/` | 跨模块与跨端正式接口契约（RPC、插件包、更新元数据等） | **部分现行 / 含退役契约** | 现行：[控制 RPC v1](contracts/oai-control-rpc-v1.md)、[设备插件包 v1](contracts/device-plugin-package-v1.md)、[App 更新发布元数据 v1](contracts/app-update-release-v1.md)；退役参考：[gateway-protocol-v2.md](contracts/gateway-protocol-v2.md) |
| `adr/` | 架构决策记录（ADR-0001 至 ADR-0065） | **按演进序列有效** | DSH Remote 核心决策：[ADR-0063](adr/0063-adopt-dsh-remote-with-independent-client-and-host-identities.md)、[ADR-0064](adr/0064-share-control-sessions-through-dsh-and-local-mcp.md)、[ADR-0065](adr/0065-separate-native-screen-control-from-wasm-data-plugins.md)；早期 ADR 随架构迭代被后续 ADR 替代 |
| `guides/` | 面向开发者与 Agent 的工程实践指南 | **部分现行 / 含历史指南** | 现行：[Android 国际化开发指南](guides/android-i18n.zh-CN.md)、[全局设备授权指南](guides/global-device-authorization.zh-CN.md)、[屏幕前端交接/接口文档](guides/android-screen-interfaces.zh-CN.md)；历史：[OAI Core 第三方 Agent 适配器指南](guides/agent-adapter-development-guide.zh-CN.md)、[CI 与本地验证旧版分工](guides/ci-and-local-verification.zh-CN.md) |
| `plans/` | 专项重构与工程实施阶段性计划 | **实施跟踪与历史计划** | 现行规划：[DSH Remote 迁移实施计划](plans/2026-11-14-dsh-remote-migration-plan.zh-CN.md)；已归档专项计划：[Android i18n 项目计划归档](plans/2026-11-14-android-i18n-project-plan.zh-CN.md)、[Android i18n 测试基础设施归档](plans/2026-11-14-android-i18n-test-infra.zh-CN.md) |
| `reviews/` | 代码审查、质量审计与功能验收台账报告 | **验收事实与审计记录** | 现行台账：[DSH Remote 迁移验收索引](reviews/2026-11-14-dsh-remote-acceptance-index.zh-CN.md)；2026-11-14 专项审计：[Agent 流与状态审计](reviews/2026-11-14-agent-stream-and-state-audit-report.md)、[中文文案审计](reviews/2026-11-14-chinese-copywriting-audit-report.md) |
| `reports/` | 专项问题排查、性能优化与稳定性复盘报告 | **阶段性技术报告** | [Remote 工具链退役报告](reports/2026-11-14-remote-toolchain-retirement.zh-CN.md)、[SoM 截图安全报告](reports/2026-11-14-som-capture-safety.zh-CN.md)、[Remote UI 基线恢复审计](reports/2026-11-15-remote-ui-baseline-recovery.zh-CN.md)、[Android App JVM 隔离报告](reports/2026-11-11-android-app-jvm-isolation.zh-CN.md) |
| `handoffs/` | 早期 UI 与模块化任务的跨人/跨上下文交接备忘 | **历史归档** | `2026-08` ~ `2026-09` 期间的阶段交接记录，仅作追溯参考 |
| `mvp/` | 早期 MVP 垂直切片验收材料与门禁证据 | **历史归档** | 详见 [docs/mvp/README.md](mvp/README.md)，记录 P0a/P0t 与 Bridge 阶段证据 |

### 其他辅助目录说明
- `deployment/`：早期 OAI Core 0.2 独立部署指南（[2026-10-26-core-independent.zh-CN.md](deployment/2026-10-26-core-independent.zh-CN.md)），**已随 OAI Core 退役成为历史文档**。
- `integrations/`：早期 Hermes 集成目录清单，现行外部 Agent 接入请直接查阅 [手机 OAI MCP README](../integrations/oai-android-mcp/README.md) 与 [OAI Phone Control Skill](../integrations/skills/oai-phone-control/SKILL.md)。
- `prompts/`：早期前端设计与动效提示词参考。
- `protocol/`：早期 v1 控制协议信封与威胁模型草案（`protocol/v1/`），**历史参考**。

---

## 3. 文档维护约定

1. **相对路径引用**：所有 `docs/` 内部及跨根目录互链一律使用相对路径，禁止出现本地或 CI 绝对路径。
2. **禁止与外部临时路径混放**：设计规格、审查报告、测试台账与指南须直接提交至本目录对应子目录（`specs/`、`plans/`、`reviews/`、`reports/` 等），不得留在工作区外或临时文件夹。
3. **状态诚实原则**：新增或更新验收类文档时，严格区分「设计已接受」、「本地/CI 自动化通过」与「真机/真实 Host 环境验收」，禁止越级宣称未验收链路已可用。
