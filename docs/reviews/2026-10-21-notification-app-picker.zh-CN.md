# 通知白名单应用选择：提交后独立审查

- 审查日期：2026-10-21。
- 需求：将通知采集设置的应用白名单从输入包名改为直接选择本机已安装应用。
- 基线：`29caf814b4fa730c7b3019a9a6495aca937b6a85`。
- 已审查提交：`74f9b133cb56450165c485d5e6c8b85e51399bc4`（`74f9b13`，`新增: 通知白名单支持选择本机已安装应用`）。
- 比较命令：`git diff 29caf814b4fa730c7b3019a9a6495aca937b6a85..74f9b133cb56450165c485d5e6c8b85e51399bc4`。
- 依据：会话提供的 AGENTS.md、模块化插件架构总规格、`CONTEXT.md`、ADR 0050、本次 `docs/superpowers/specs/2026-10-21-notification-app-picker.md`。
- 方法：未参与实现的审查 Agent 检查固定提交；另以两个独立子 Agent 分别复核代码规范和需求规格。审查过程未修改生产代码或测试，未执行提交、推送等 Git 变更操作，唯一写入为本报告。

## 审查结论

发现 **1 项 P2 缺陷**：前台刷新可能让仍待保存的应用选择从列表中消失，用户无法逐项取消这个隐藏选择。建议修复后补充对应回归测试并重新验证。本次未发现 P0/P1 问题，也未发现绕过通知授权或改变采集器边界的问题。

## 代码规范轴

未发现阻断问题。已对照总架构、CONTEXT、ADR 0050 与本次规格检查固定提交：

- 应用选择仅通过 `NotificationPolicyPort` 保存包名，未引入采集器依赖或第二套授权。
- `QUERY_ALL_PACKAGES` 仅声明在 Full 清单，Play 保持受限查询并提示。
- 列表查询在 `Dispatchers.IO` 执行，取消正常传播；前台刷新和 `rememberSaveable` 草稿符合生命周期要求。
- 使用 Material 3、公共 `Dimensions` 与主题颜色；未发现新增布局魔数、伪实现或废弃输入入口残留。

规范轴子审查仅做静态只读检查，未运行 Gradle，真机行为不在其证据范围内。

## 需求规格轴

### P2：刷新后不可见的草稿应用失去取消入口

**位置：** `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt:68-72`。关联位置为同文件 `:121` 的已选计数、`:164-185` 的候选项勾选入口，以及 `NotificationCollectionScreen.kt:120-122` 的前台刷新、`:145-146` 的最新策略与独立草稿传入、`:160-161` 的草稿保存。

规格要求每次回到前台重新查询以反映安装和卸载；对不可见的原有选择要求“保留，并标明状态，允许用户取消”，并在版本冲突时“保留未保存草稿”。

候选项目前仅包含 `apps` 和当前权威策略的 `savedPackages`，没有包含 `selectedPackages` 中已经不可见的草稿项。刷新应用列表不会移除草稿，因而会出现列表与将要保存的选择不一致。

触发步骤：

1. 原白名单为空，打开「选择应用」，勾选已安装应用 A，先不保存。
2. 切到后台卸载 A，再返回设置页，触发前台应用列表刷新。
3. 新查询结果不含 A，已保存白名单也不含 A，A 因此从候选列表消失。
4. 页面仍显示“已选 1 个应用”，但没有 A 的行，无法逐项取消；点击保存仍把 A 的包名写入白名单。

同样问题可由策略并发更新触发：进入时原有不可见项 A 被预选，另一操作随后将 A 从权威策略移除。最新 `snapshot.packageIds` 使 A 消失，但旧草稿仍含 A。该分支会被旧 revision 拒绝保存，仍存在草稿不可见、无法逐项取消的问题。

**影响：** 用户无法完整检查和编辑当前选择；在卸载场景中可以保存列表中看不到的包名。返回取消再重新打开能绕开，但当前选择页不满足草稿应可见、可取消的交互要求。未据此推断越权采集或授权绕过。

**修复方向：** 构建候选项时同时合并草稿中的缺失包名，按包名去重并标明当前不可见；仅将这种合并用于展示，不自动重写草稿或提取最新 revision。补充“勾选后卸载、前台刷新仍可取消”及“并发移除原有不可见项后草稿仍可编辑”的回归用例。

**证据级别：** 依据固定提交的数据流与候选项表达式确认，并用只读 CLI 演算刷新前后的候选项、草稿集合；该场景尚无真机或 Compose 动态复现。现有测试没有覆盖选择草稿与安装列表在打开期间发生这种分离的情形。

需求规格轴独立子审查亦发现上述同一 P2。其余检查未见阻断问题：保存与取消、状态恢复、旧版本拒绝、错误重试、Full/Play 可见性及 ADR 0050 端口边界均符合。

## 已核验的实现边界

| 核查点 | 结果 |
| --- | --- |
| Full/Play 应用可见性 | 源清单与本机已合并的 FullDebug/PlayDebug 清单一致：Full 声明 `QUERY_ALL_PACKAGES`；Play 不含该权限，二者均含 `MAIN` + `LAUNCHER` 查询。Play 页面说明系统可见范围。 |
| 应用身份和旧白名单 | 以包名区分应用；同名应用不合并，查询结果按包名去重。原有不可见白名单在常规进入、保存、显式移除路径中保留，受上述动态草稿缺陷限制。 |
| 查询与生命周期 | `PackageManager` 枚举、名称与图标读取在 IO 线程；取消异常继续传播，查询失败显示重试且禁用保存；返回前台重新查询，离开组成时移除生命周期观察者。 |
| 保存与取消 | 勾选只改草稿；端口接受后关闭；取消与系统关闭请求放弃草稿。保存只传 `packageIds`，不修改采集同意、内容范围或投递模式。 |
| 界面恢复 | 草稿列表、打开时 revision 与搜索词使用 `rememberSaveable`；已存在相应恢复测试。 |
| 版本冲突 | 保存携带打开时 revision；被拒绝后保留草稿并显示原因，保存按钮禁用；用户明确重新载入后才从权威端口重置选择及 revision。 |
| 授权与架构 | 仅调用 `notification-control` 接口，未改存储格式、Gateway 配对授权、采集授权或 collector 装配方式。 |

## 验证证据及边界

审查 Agent 独立读取本机 XML，核对以下定向测试的 `failures=0`、`errors=0`、`skipped=0`：

| 定向测试类 | FullDebug | PlayDebug |
| --- | ---: | ---: |
| `InstalledNotificationAppsTest` | 16 | 16 |
| `NotificationCollectionScreenTest` | 15 | 15 |
| `ArchitectureBoundaryTest` | 2 | 2 |
| `DistributionVariantTest` | 1 | 1 |
| `MaterialTokenGuardTest` | 2 | 2 |
| `SettingsHonestyCopyTest` | 2 | 2 |
| 合计 | 38 | 38 |

XML 位于 `apps/android/app/build/test-results/testFullDebugUnitTest/` 与 `apps/android/app/build/test-results/testPlayDebugUnitTest/`。查询测试实际在 API 31、34 各执行一次，界面测试在 API 34 执行；不能据此宣称所有 Android 版本均已验证。Play 目录中另有 0 项、1970 时间戳的 `AgentSessionForegroundServiceTest` XML，未计入本次 38 项。

审查 Agent 独立执行以下短检查，全部通过：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 apps/android/tools/test_notification_runtime_static.py
PYTHONDONTWRITEBYTECODE=1 python3 apps/android/tools/test_wp03_static.py
git diff --check 29caf814b4fa730c7b3019a9a6495aca937b6a85..74f9b133cb56450165c485d5e6c8b85e51399bc4
```

两个静态套件分别运行 5 项、8 项。测试夹具为新安装的 Robolectric 包补齐默认图标，生产查询逻辑没有为测试绕过失败处理；主审查核对了该差异与现有查询断言。

本次审查没有重新运行 Gradle，没有操作 ADB 或真机，也没有将本机测试等同于真实系统包可见性验证。截至主 Agent 提供的状态，开发预览发布已完成，完整 CI 仍运行；这些状态不构成此报告独立核验的完成证据，也不代表完整 CI 或真机验收通过。

代码规范轴：0 项发现，无阻断问题。需求规格轴：1 项 P2，最严重问题为刷新后草稿选择隐藏且无法逐项取消。
