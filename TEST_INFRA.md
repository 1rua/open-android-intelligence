# E2E Test Infra: OAI Android 全量文案资源化与国际化基础重构

## Test Philosophy
- 需求驱动、不透明箱（Opaque-box）与白盒双轨测试，严格脱钩于底层实现细节。
- 遵循 4-Tier + Tier 5 体系：
  - Tier 1: 功能覆盖（每个功能域至少 5 个测试用例，覆盖正常展示与交互）
  - Tier 2: 边界与极端场景（长文本布局膨胀、缺失翻译回退、特殊标点与转义字符、空值）
  - Tier 3: 跨功能组合（系统运行时语言切换与多组件重组联动、双语会话标题与消息交互）
  - Tier 4: 真实业务全链路场景（启动引导 -> 连接网关 -> 创建会话 -> 修改设置 -> 授权录屏 -> 接收通知）
  - Tier 5: 对抗性覆盖审计（全量源码硬编码中文残留扫描与基线闭环验证）

## Feature Inventory
| # | Feature | Source | Tier 1 | Tier 2 | Tier 3 |
|---|---------|--------|:------:|:------:|:------:|
| 1 | 资源模块 `:ui-resources` 编译与接入 | R1.1 | 5 | 5 | ✓ |
| 2 | AGP 语言自动配置与边界过滤 | R1.2, R1.3 | 5 | 5 | ✓ |
| 3 | XML 语法、特殊字符转义与格式化合规性 | R1.7, R2.4 | 5 | 5 | ✓ |
| 4 | 资源 ID 全局唯一性与无命名冲突 | R1.6, R2.1 | 5 | 5 | ✓ |
| 5 | 通用、连接、权限与通知文案资源化 | R2.1, R2.3 | 5 | 5 | ✓ |
| 6 | 会话时间线与助理浮层文案资源化 | R2.1 | 5 | 5 | ✓ |
| 7 | 会话标题四分类持久化保护与隔离 | R1.5 | 5 | 5 | ✓ |
| 8 | 设置中心全量子项与结构化错误映射 | R1.4, R2.1 | 5 | 5 | ✓ |
| 9 | 源码级硬编码中文自动化扫描器与门禁 | R1.6 | 5 | 5 | ✓ |
| 10 | 语言回退机制与伪语言（en-XA/ar-XB） | R1.7 | 5 | 5 | ✓ |

## Test Architecture
- **预编译与静态门禁 (Level 1 / Fast)**:
  - 扫描脚本: `tools/check_hardcoded_chinese.py`
  - 目标: 校验 Kotlin/Compose 源码中不存在未在 `apps/android/ci/i18n-baseline.json` 登记且无行内豁免的硬编码中文字符。
  - 触发方式: `npm run test:fast` 或 Gradle `checkHardcodedChineseStrings` 任务。耗时 < 3s。
- **资源完整性与命名冲突门禁 (Level 2 / PR)**:
  - 测试脚本: `tools/test_string_resources_integrity.py`
  - 校验逻辑:
    1. AAPT2 资源 ID 全局唯一性；
    2. 多占位符强制 `%1$s`, `%2$s` 索引规范；
    3. 特殊字符转义（`\'`, `\"`, `&amp;`）；
    4. 单复数 `<plurals>` 语法完整性。
  - 触发方式: `npm run test:pr`。
- **单元测试与运行时回归套件 (Level 2 & 3)**:
  - `LanguageFallbackUnitTest`: 验证 `res/values/` 默认中文回退机制。
  - `PseudoLocaleLayoutTest`: 验证 `en-XA` 文本膨胀 30%~50% 下 Compose 组件无错位无截断。
  - `ConversationTitlePersistenceI18nTest`: 验证默认占位标题不入库，用户自命名与远端标题在语言切换时 100% 冻结保全。
  - `SettingsScreenRecompositionTest`: 验证运行时 Locale 变更触发 Compose 自动重组刷新。
  - 注册规范: 必须在 `apps/android/ci/app-regression.txt` 显式登记。

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | 完整冷启动与欢迎引导、连接配对流程 | F1, F2, F5, F10 | Medium |
| 2 | 新建会话、首条消息发送与标题自命名隔离 | F6, F7, F10 | High |
| 3 | 外观/网络/存储设置修改与设置项多语言适配 | F5, F8, F10 | Medium |
| 4 | 前台录屏服务启动、常驻通知展示与权限审批弹窗 | F5, F10 | High |
| 5 | 设置备份导出异常触发、结构化错误本地化映射与兼容提示 | F8, F10 | Medium |

## Coverage Thresholds
- Tier 1: ≥5 per feature (共 ≥50 测试用例)
- Tier 2: ≥5 per feature (共 ≥50 边界用例)
- Tier 3: 覆盖主要功能对组合交互 (≥10 用例)
- Tier 4: ≥5 个真实业务全链路端到端场景
- Tier 5: 覆盖审计 2,095 处硬编码基线 100% 闭环
