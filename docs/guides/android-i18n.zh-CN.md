# Android 国际化与文案资源化开发指南

本指南规范 Open Android Intelligence（OAI）Android 客户端面向用户的所有可见文本的资源化维护、代码静态扫描豁免、完整性校验门禁及多语言扩展流程。

---

## 1. `:ui-resources` 模块定位与依赖边界

### 1.1 模块定位
- **模块路径**：`apps/android/ui-resources/`（在 [settings.gradle.kts](../../apps/android/settings.gradle.kts) 注册为 `:ui-resources`）
- **命名空间 / 包名**：`com.openandroidintelligence.ui.resources`（见 [build.gradle.kts](../../apps/android/ui-resources/build.gradle.kts)）
- **兼容级别**：已在根工程 [build.gradle.kts](../../apps/android/build.gradle.kts) 加入 `appAndroid12RuntimeLibraries`，确保 `minSdk = 31` 与主应用一致。
- **零出度设计**：`:ui-resources` 不依赖任何业务模块（`out-degree = 0`），仅托管资源定义与辅助静态资源映射，避免循环依赖。

### 1.2 单向依赖拓扑
- **允许依赖 `:ui-resources` 的模块**：展示层与通知提示层模块，包括 `:app`、`:conversation-ui`、`:assistant-holder`、`:capability-ports`（以及随需要接入的其他 UI 模块）。
- **严禁依赖 `:ui-resources` 的模块**：领域层与数据持久化层（`:conversation-domain`、`:conversation-data`、`:core-model`、`:policy-engine`、`:encrypted-store` 等）必须保持 0 UI 资源依赖。跨层消息传递遵循以下两条约定：
  1. **结构化错误码映射**：领域/数据层异常携带强类型枚举 `errorCode`（同时可保留英文或调试默认 `message` 兼容单元测试），由展示层通过映射函数转换为 `@StringRes` 资源 ID 后渲染。
  2. **默认会话标题解耦**：领域与数据库中的未命名默认会话保留 `null` 或空值，不将 `"新对话"` 等硬编码文案写入持久化层；由展示层统一映射到 `R.string.conv_*` 默认标题，防止语言切换污染既有会话标题或触发同步风暴。

### 1.3 AGP 多语言配置原则
- **基准语言声明**：[resources.properties](../../apps/android/ui-resources/src/main/res/resources.properties) 声明 `unqualifiedResLocale=zh-CN`，使 AGP 将无修饰符的 `values/` 识别为简体中文基准资源，而非默认的英文。
- **自动 LocaleConfig 生成**：主应用 [app/build.gradle.kts](../../apps/android/app/build.gradle.kts) 开启 `androidResources.generateLocaleConfig = true`，严禁手写 `res/xml/locale_config.xml`。
- **发布语言过滤器**：`localeFilters` 必须使用合法的 AAPT2 限定符格式（区域带 `r` 前缀如 `zh-rCN`，或 BCP-47 格式如 `b+zh+Hans+CN`），禁止写成 `zh-CN`；[i18n-quality-gates.gradle.kts](../../apps/android/gradle/i18n-quality-gates.gradle.kts) 中的 `verifyLocaleConfigPrerequisites` 任务会在构建前拦截错误配置。

---

## 2. `values/strings_*.xml` 文件职责划分

资源文件统一存放于 `apps/android/ui-resources/src/main/res/values/`，按功能域拆分维护。7 大核心功能域文件与现行 DSH Remote 扩展文件分工如下：

| 文件名 | 资源前缀 | 职责与适用范围 |
| --- | --- | --- |
| `strings_common.xml` | `common_` | 全局通用操作按钮（确认/取消/关闭/重试）、通用状态标签、单位、无障碍通用描述 |
| `strings_conversation.xml` | `conv_` / `assistant_` | 会话工作台、时间线、消息卡片、Markdown/思考折叠、输入框、附件面板、系统默认助理浮层与语音提示 |
| `strings_settings.xml` | `settings_` | 设置中心全量子项（外观主题、消息处理、能力与插件总览、备份与恢复、应用更新、关于页面等） |
| `strings_connection.xml` | `conn_` | 通用服务器连接表单、设备发现、引导与连接状态提示 |
| `strings_permissions.xml` | `perm_` | 系统运行时权限申请弹窗、无障碍/通知监听/录屏等特殊权限引导页、设备能力授权审批文案 |
| `strings_notifications.xml` | `notif_` | 系统通知渠道名称与说明、常驻前台服务通知、消息完成提醒与快捷动作 |
| `strings_errors.xml` | `err_` | 跨层结构化错误码映射、表单校验拦截、网络/IO/格式异常的用户友好提示 |
| `strings_remote.xml` *(DSH Remote 扩展)* | `remote_` | DSH Remote 服务登录、Host/Backend/Workspace 选择及远端会话控制相关文案 |
| `strings_remote_login.xml` *(DSH Remote 登录扩展)* | `remote_` | 浏览器 OAuth 授权等待、扫码配对电脑身份确认等独立登录引导文案 |

---

## 3. 资源 ID 命名规范与调用方式

### 3.1 命名契约
所有新增资源 ID 统一遵循下划线小写风格：
```text
[domain]_[subcomponent_or_screen]_[semantic_name]
```
- `domain`：对应所属文件的功能域前缀（`common`、`conv`、`assistant`、`settings`、`conn`、`perm`、`notif`、`err`、`remote`）。
- `subcomponent_or_screen`：子页面、功能模块或卡片标识（如 `appearance`、`backup`、`pairing`、`channel`）。
- `semantic_name`：语义清晰的要素与状态描述（如 `title`、`summary`、`action_confirm`、`error_empty`、`empty_hint`）。
- 示例：`common_action_confirm`、`settings_appearance_theme_title`、`conv_title_new_default`、`notif_channel_screen_capture_name`、`err_backup_invalid_format`。
- 禁止以纯数字、无语义编号或界面位置（如 `text1`、`button_left`）命名，且全模块范围内 ID 必须唯一。

### 3.2 代码调用约定
- **Jetpack Compose**：优先使用 `androidx.compose.ui.res.stringResource(UiR.string.<id>, *formatArgs)` 或 `pluralStringResource(UiR.plurals.<id>, count, *formatArgs)`（其中 `UiR` 为 `com.openandroidintelligence.ui.resources.R` 别名，避免与 `:app` 等模块自身的 `R` 混淆）。
- **Context / Service / Notification**：使用 `context.getString(UiR.string.<id>, *formatArgs)` 或 `context.resources.getQuantityString(...)`。
- **禁止在生产源码中直接拼装用户可见的硬编码中文句子**：如“第 ” + count + “ 项”必须改写为带占位符的完整资源条目。

---

## 4. 多占位符与 XML 转义规则

编写 `strings_*.xml` 时必须严格遵守以下格式化规则，所有规则均由静态校验器在 PR 门禁阶段强制执行：

1. **多占位符显式位置索引**：
   - 单个占位符可使用 `%s` / `%d`，也可显式写为 `%1$s` / `%1$d`。
   - **当同一条字符串中存在 2 个及以上占位符时，必须全部使用显式位置索引**（如 `%1$s`、`%2$d`），严禁出现 `%s ... %s`，以确保不同语系调整语序时参数不发生错位。
   - 正确示例：`<string name="remote_pairing_host_identity">服务：https://dsh.r2049.cn\n电脑：%1$s\n公钥指纹：%2$s</string>`
2. **百分号 `%` 处理**：
   - 表示字面量百分比（如 `100%`）时，若该字符串支持格式化（默认 `formatted="true"`），必须转义为 `%%`（即 `100%%`）；若该字符串不含任何占位符，也可在 `<string>` 标签上声明 `formatted="false"`。
3. **单引号、双引号与 XML 实体转义**：
   - 未用英文双引号 `"` 完整包裹的字符串内部，所有英文单引号 `'` 必须转义为 `\'`（否则 AAPT2 编译失败）；或者用双引号包围整段文本。
   - `&`、`<`、`>` 必须按照标准 XML 实体写作 `&amp;`、`&lt;`、`&gt;`。
4. **单复数 `<plurals>` 规范**：
   - 每个 `<plurals>` 节点至少包含一个 `<item>`，且**必须包含 `quantity="other"`**，避免系统在部分语言环境下找不到回退分支导致崩溃。
   - 合法 `quantity` 仅限：`zero`、`one`、`two`、`few`、`many`、`other`，同一 `<plurals>` 内不可重复。
   - `<plurals>` 内部 `<item>` 的文本同样受多占位符索引与转义规则约束。

---

## 5. 硬编码中文扫描与 4 级豁免体系

仓库通过 [tools/check_hardcoded_chinese.py](../../tools/check_hardcoded_chinese.py) 对 `apps/android/` 下全部 Kotlin 生产源码进行词法级扫描（自动排除 `test`、`androidTest`、`build`、`.gradle` 等测试与构建产物目录）。在 `npm run test:fast` 中秒级执行拦截未豁免的硬编码中文。

扫描器支持以下 **4 级豁免体系**：

| 豁免级别 | 机制与语法示例 | 适用场景与边界说明 |
| --- | --- | --- |
| **Level 1：行内注释豁免** | 行尾或上一整行添加 `// i18n-ignore` 或 `// i18n-exempt: <原因>` | 仅用于极少数确非用户界面的测试桩、正则常量或协议字面量；注：写在字符串内部伪造注释标记无效 |
| **Level 2：注解抑制** | 文件顶部 `@file:Suppress("HardcodedChineseText")`，或声明级 `@Suppress("HardcodedChineseText")` | 作用范围按词法块精准绑定到紧随其后的 `val`/`var`/`fun`/`class`/`interface`/`object`/`typealias` 声明，不会泄漏到后续声明 |
| **Level 3：语法模式自动豁免** | 以下调用/语法中的中文自动豁免：<br>1. 日志：`*Log.<method>(...)` 或 `Timber.<method>(...)`；<br>2. 断言：`check`/`require`/`checkNotNull`/`requireNotNull`/`assert`/`error` 的消息参数或尾随 lambda；<br>3. 异常：`throw ...(...)`；<br>4. 弃用注解：`@Deprecated(...)` | 仅豁免对应消息参数或括号区间内的字面量，闭合后立即恢复检查；注：向 `require(cond, msg)` 传入命名或位置条件参数时，只有 `lazyMessage` / `message` 参数豁免 |
| **Level 4：中央基线台账** | 登记于 [apps/android/ci/i18n-baseline.json](../../apps/android/ci/i18n-baseline.json) | 仅供历史存量分批迁移过渡使用；按 `(file, text)` 消费出现次数配额，**新增业务代码严禁往基线新增条目** |

> 迁移消减存量文案后，可执行 `python3 tools/check_hardcoded_chinese.py --generate-baseline apps/android/ci/i18n-baseline.json`（或精确缩减对应条目）确保基线台账同步收敛，避免“幽灵豁免”。

---

## 6. 质量门禁命令与 Gradle 校验

除了 `tools/check_hardcoded_chinese.py`，[tools/check_string_resources_integrity.py](../../tools/check_string_resources_integrity.py)（兼容入口 [tools/test_string_resources_integrity.py](../../tools/test_string_resources_integrity.py)）会联合执行以下 5 项校验规则：
1. `RULE_1_PLACEHOLDER_INDEX`：多占位符强制显式位置索引；
2. `RULE_2_UNESCAPED_PERCENT` / `RULE_2_UNESCAPED_APOSTROPHE`：百分号与单引号转义完整性；
3. `RULE_3_DUPLICATE_ID`：模块内跨文件资源 ID 全局唯一性；
4. `RULE_4_PLURAL_*`：`<plurals>` 修饰词合法性、无重复且必含 `other`；
5. `RULE_5_MISSING_RESOURCE`：解析 Kotlin 生产源码中的 `R.string.*` / `R.plurals.*` / `R.array.*` 引用，确保默认 `values/` 目录中存在真实定义。

日常开发与 CI 验证命令：

```bash
# 1. Level 1 快速静态检查（含硬编码中文扫描）
npm run test:fast

# 2. 单独运行硬编码中文扫描（可加 -v 查看豁免明细）
python3 tools/check_hardcoded_chinese.py

# 3. 单独运行 XML 完整性与源码引用校验
python3 tools/check_string_resources_integrity.py

# 4. 通过 Gradle 运行门禁任务
cd apps/android && ./gradlew checkHardcodedChineseStrings checkStringResourcesIntegrity verifyLocaleConfigPrerequisites

# 5. 提交 PR 前验证（Level 2，含完整 XML 校验与受影响 Android 单测）
npm run test:pr
```

---

## 7. 新增语种标准操作流程（SOP）

向应用引入新语言（以新增英文 `en` 为例）时，按以下顺序操作并验证：

1. **创建目标语种资源目录**：
   在 `apps/android/ui-resources/src/main/res/` 下新建对应限定符目录（例如 `values-en/` 或 `values-zh-rTW/`），按原 9 个 `strings_*.xml` 的结构提供对应翻译，并保持资源 `name` 与占位符类型/数量/索引与 `values/` 基准文件完全一致。
2. **更新构建语言过滤器**：
   打开 [apps/android/app/build.gradle.kts](../../apps/android/app/build.gradle.kts)，将新语种限定符加入 `androidResources.localeFilters`（例如 `localeFilters += listOf("zh-rCN", "en")`）。切记区域码必须带 `r` 前缀（如 `zh-rTW`）或使用 BCP-47 格式，AGP 将在构建时据此与 `unqualifiedResLocale=zh-CN` 自动生成语言配置列表。
3. **执行静态与完整性校验**：
   运行 `python3 tools/check_string_resources_integrity.py` 与 `./gradlew verifyLocaleConfigPrerequisites`，确认所有新增 XML 无转义错误、无漏标位置索引的占位符、无重名 ID。
4. **伪语言与长文本布局自检**：
   使用伪语言（`en-XA`）或长文本用例验证 Compose 界面在文案膨胀 30%–50% 时无按钮文字溢出、无卡片重叠、无截断丢字。
5. **运行 PR 与全量语言回归**：
   执行 `npm run test:pr`（必要时执行 `npm run test:full`），确认缺失翻译自动回退至 `values/`、运行时 Locale 切换正常触发页面重组，且本地持久化会话标题未受语言切换影响。
