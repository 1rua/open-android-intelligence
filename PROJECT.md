# Project: OAI Android 全量文案资源化与国际化 (i18n) 基础重构

## Architecture
- **构建体系**: Android Gradle Plugin (AGP) 8.9.2, Kotlin 2.1.20, Compose 2.1.20 (BOM 2024.12.01), Java 17, minSdk 31, compileSdk 36.
- **模块拓扑**:
  - 新增独立 `:ui-resources` Android Library 模块（位于 `apps/android/ui-resources/`，包名 `com.openandroidintelligence.ui.resources`），出度为 0，专门托管面向用户的 7 个功能域 XML 字符串资源。
  - 展示层模块（`:app`、`:conversation-ui`、`:assistant-holder`）及服务通知层（`:capability-ports`）单向依赖 `:ui-resources`。
  - 领域层（`:conversation-domain`）、数据层（`:conversation-data`）与核心模型（`:core-model`）保持 0 UI 资源依赖，杜绝反向依赖。
  - 注册 `apps/android/build.gradle.kts` 的 `appAndroid12RuntimeLibraries`，确保 `:ui-resources` 的 `minSdk = 31` 与主应用兼容。
  - 注册 `tools/ci/impact.py` 与 `tools/ci/android_tasks.py`，保持 CI 契约通过。
- **语言配置**:
  - 应用模块开启 `androidResources.generateLocaleConfig = true` 配合 `localeFilters += listOf("zh-rCN")`。
  - 配置 `res/resources.properties` 声明 `unqualifiedResLocale=zh-CN`。
  - 严禁手动声明 `locale_config.xml`。
- **持久化保护**:
  - 会话标题四分类治理：默认占位标题由展示层动态映射，数据库存空/哨兵；用户命名、Agent生成、服务端同步标题绝对冻结保全。
- **错误处理**:
  - Core/Domain/Data 层使用结构化错误码（`errorCode`），展示层根据错误码映射 `R.string.err_*`，同时保留默认中文 message 以 100% 兼容既有单元测试。
- **门禁矩阵**:
  - Level 1 (`test:fast`): `check_hardcoded_chinese.py` 扫描器（< 3 秒拦截）。
  - Level 2 (`test:pr`): `test_string_resources_integrity.py` 验证 XML 语法、占位符索引与 ID 唯一性；关联模块测试。
  - Level 3 (`test:full`): 语言回退、伪语言、会话标题隔离回归测试与全量模块回归。

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | 资源模块脚手架 `:ui-resources` | 创建独立资源库、注册 Gradle 与 CI 配置 | M1 | R1.1, Codebase Survey |
| 2 | AGP 语言自动配置 | generateLocaleConfig 与 unqualifiedResLocale=zh-CN | M1 | R1.2, Codebase Survey |
| 3 | 语言发布边界控制 | localeFilters 锁定 zh-rCN，测试资源隔离 en-US | M1 | R1.3, Codebase Survey |
| 4 | 7 个 XML 骨架建立 | values/strings_*.xml 模块化拆分 | M1 | R2.1, R2.2 |
| 5 | 硬编码中文轻量扫描器 | 秒级 AST/正则扫描器与 4 级白名单/豁免机制 | M1 | R1.6, Test Survey |
| 6 | 资源完整性与占位符校验器 | %1$s 索引占位符强制与 ID 唯一性校验 | M1 | R1.6, R1.7 |
| 7 | 通用与操作文案资源化 | strings_common.xml 迁移 (按钮、状态、单位) | M2 | R2.1, Audit Report |
| 8 | 网络与连接文案资源化 | strings_connection.xml 迁移 (配对、网关、引导) | M2 | R2.1, Audit Report |
| 9 | 权限与授权文案资源化 | strings_permissions.xml 迁移 (审批、系统授权) | M2 | R2.1, Audit Report |
| 10 | 系统通知与服务文案资源化 | strings_notifications.xml 迁移 (前台录屏、渠道) | M2 | R2.1, R2.3 |
| 11 | 会话交互与时间线文案资源化 | strings_conversation.xml 迁移 (输入框、Markdown) | M3 | R2.1, Audit Report |
| 12 | 助理浮层文案资源化 | assistant-holder 浮层、语音录入提示资源化 | M3 | R2.1, Audit Report |
| 13 | 会话标题四分类与持久化保护 | 默认占位解耦、消除 "新对话" 字面量判定 | M3 | R1.5, Test Survey |
| 14 | 设置中心全量文案资源化 | strings_settings.xml 迁移 (510+ 处文案) | M4 | R2.1, Audit Report |
| 15 | 异常与错误码结构化映射 | strings_errors.xml 迁移与 SettingsBackup 双轨映射 | M4 | R1.4, Audit Report |
| 16 | 2,095 处基线台账追踪 | 对照审计报告逐项核实迁移、优化(256处)与豁免 | M4 | R1.8, Audit Report |
| 17 | 既有测试去脆性适配 | 消除测试用例对硬编码中文的直接断言 | M4 | R1.9, Test Survey |
| 18 | 国际化指南文档交付 | 撰写 docs/guides/android-i18n.zh-CN.md | M5 | R1.10, Spec Miner |
| 19 | 默认资源完整性与语法验证 | 全量 XML 语法、特殊字符转义、复数规范 | Final | R1.7, Test Track |
| 20 | 语言回退与伪语言验证 | en-XA 文本膨胀与缺失翻译回退验证 | Final | R1.7, Acceptance |
| 21 | 会话标题持久化隔离回归测试 | 语言切换数据零污染、零同步风暴验证 | Final | R1.5, R1.7 |
| 22 | 生产环境全量单元测试与门禁验证 | npm run test:fast / test:pr / test:full 100% 通过 | Final | Acceptance |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | 基础架构与配置搭建 | 创建 `:ui-resources` 模块，配置 AGP 语言策略、CI 契约与静态扫描门禁 | none | PLANNED |
| M2 | 通用、连接、权限与通知文案迁移 | 迁移 strings_common / connection / permissions / notifications 四大功能域文案及服务常驻通知 | M1 | PLANNED |
| M3 | 会话与助理文案迁移及持久化保护 | 迁移 strings_conversation，重构会话标题逻辑与持久化隔离 | M1 | PLANNED |
| M4 | 设置中心全量迁移、错误映射与基线闭环 | 迁移 strings_settings / errors，重构结构化错误码，闭环 2,095 处基线台账 | M2, M3 | PLANNED |
| M5 | 国际化架构决策与指南文档交付 | 编写 `docs/guides/android-i18n.zh-CN.md` 完整规范与新增语言 SOP | M1, M4 | PLANNED |
| Final | E2E 验证、全量测试回归与覆盖加固 | Phase 1 通过 100% E2E 与三级门禁测试；Phase 2 对抗性覆盖审计闭环 | M4, M5 | PLANNED |

## Interface Contracts

### 展示层 ↔ 资源层 (`:app`, `:conversation-ui`, `:assistant-holder` -> `:ui-resources`)
- 资源访问采用官方 API：
  - Compose 环境：`androidx.compose.ui.res.stringResource(R.string.<id>, *formatArgs)` 或 `pluralStringResource(...)`
  - Context / Service / Notification 环境：`context.getString(R.string.<id>, *formatArgs)`
- 资源命名契约：
  - 格式：`[domain]_[subcomponent/screen]_[semantic_name]`
  - 示例：`common_action_confirm`, `settings_appearance_theme_title`, `conv_title_new_default`, `notif_channel_screen_capture_name`
  - 占位符契约：若占位符 $\ge 2$，必须带显式位置索引，如 `%1$s` 与 `%2$s`。

### 领域/数据层 ↔ 展示层 (`:conversation-domain`, `:conversation-data` ↔ `:conversation-ui`)
- 会话标题契约：
  - 领域层/数据层默认会话标题表示为 `null` 或空字符串 `""`。
  - 展示层提供单例/扩展函数：`fun resolveDisplayConversationTitle(title: String?): String`，若为空则返回 `stringResource(R.string.conv_title_new_default)`。
  - `WorkbenchController` 使用 `title.isNullOrBlank()` 替代 `currentTitle == "新对话"` 作为自动命名触发条件。

### 错误处理契约 (跨层错误映射)
- 业务异常扩展结构化错误枚举 `errorCode`：
  - 示例：`class SettingsBackupFormatException(val errorCode: BackupFormatErrorCode, val detailArgs: List<String> = emptyList(), message: String? = null) : Exception(message)`
  - 展示层提供映射函数：`fun BackupFormatErrorCode.toStringRes(): Int` 映射到 `R.string.err_backup_*`。

## Code Layout
```text
apps/android/
├── settings.gradle.kts                          # 注册 include(":ui-resources")
├── build.gradle.kts                             # appAndroid12RuntimeLibraries 注册
├── ui-resources/                                # 新增：独立资源库
│   ├── build.gradle.kts
│   └── src/main/res/
│       ├── values/
│       │   ├── strings_common.xml               # 通用操作、按钮、状态
│       │   ├── strings_conversation.xml         # 对话时间线、输入框、助理
│       │   ├── strings_settings.xml             # 设置中心全量子项
│       │   ├── strings_connection.xml           # 网络、网关、配对
│       │   ├── strings_permissions.xml          # 权限申请与审批
│       │   ├── strings_notifications.xml        # 系统通知与渠道
│       │   └── strings_errors.xml               # 错误码与异常提示
│       └── resources.properties                 # unqualifiedResLocale=zh-CN
├── app/                                         # 主应用壳与设置中心
│   ├── build.gradle.kts                         # generateLocaleConfig 与 localeFilters
│   └── src/main/kotlin/...
├── conversation-ui/                             # 对话组件与时间线
│   └── src/main/kotlin/...
├── conversation-domain/                         # 会话业务模型（无 UI 资源依赖）
├── capability-ports/                            # 前台录屏服务通知迁移
└── ci/
    ├── app-regression.txt                       # 单元测试注册清单
    └── i18n-baseline.json                       # 2,095 基线豁免与追踪台账
tools/
├── ci/
│   ├── impact.py                                # 模块 lane 注册
│   └── android_tasks.py                         # 测试任务注册
└── check_hardcoded_chinese.py                   # 源码级硬编码扫描门禁
docs/
└── guides/
    └── android-i18n.zh-CN.md                    # 国际化指南与规范文档
```
