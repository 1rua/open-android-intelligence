# Android 流式正文呈现与触觉反馈实施记录

关联 issue：#160；草稿 PR：#161；基线：`26c0e5c92745e3374b88c293d27677f200efff0f`；当前实现提交头：`82a51148`。

## 已实现

- Android 内部新增实时/快照来源和内容修订元数据；原有接收、SSE/WebSocket、镜像持久化、消息复制和后端协议未改。
- 新增单条活跃回复的 `receivedText`、展示偏移、待显缓冲、字素边界、终态排空和触觉节流逻辑。历史恢复静默展示，实时追加按字素推进，重复/旧修订不重复显示，非前缀权威改写清理旧动效。
- 主聊天、气泡窗口和系统助理接入共享正文呈现注册器。系统助理通过既有桥接通道传输来源元数据和触觉开关；活跃回复在终态排空期间受到桥接预算保护。
- Markdown 解析器增加可选来源追踪和保守前置块缓存；文字渐入使用批次范围，光标和短拖尾使用独立 Overlay 与真实 TextLayoutResult/代码布局回调。旧的文本光标占位已移除。
- 外观设置新增默认关闭的「震动反馈」开关，文案严格为「震动反馈」和「输出时伴随轻微震动触觉反馈」。持久化、备份/回滚、旧备份默认关闭和助理即时同步已接入。
- 触觉只由可见新增范围回调触发，使用原生 `KEYBOARD_TAP`，按约 100～150ms 限频，不积压事件，遵循视口、焦点、生命周期和系统触觉开关。
- 独立评审后修复了首屏/重开基线、同修订快照追加、公式占位来源长度、列表/表格逐项来源以及保守 Markdown 缓存正则重复分配问题。

## 已执行验证

使用共享 Gradle 依赖并在默认缓存只读时切换到 `/tmp/oai-smooth-gradle-home`，Android SDK 使用仓库 `.toolchains/android-sdk`。

- `:conversation-ui:compileDebugKotlin`：通过。
- `:conversation-ui:compileDebugUnitTestKotlin`：通过。
- `:assistant-holder:compileDebugKotlin`：通过。
- `:app:compileFullDebugKotlin`：通过。
- `:app:compileFullDebugUnitTestKotlin`：通过。
- `git diff --check`：通过。
- `npm run test:fast`：锁文件门禁和硬编码中文扫描通过；进入 Android 增量门禁时因仓库 wrapper 尝试写入只读 `/home/djbd/.gradle/...gradle-8.12-bin.zip.lck` 退出。此前使用直接 Gradle 二进制和可写临时 Gradle 用户目录完成了上面的编译验证。

尝试运行 `:conversation-ui:testDebugUnitTest` 的流式状态、Markdown 渲染和消息流式回归时，Robolectric 在测试启动前下载 `org.robolectric:android-all-instrumented:14-robolectric-10818077-i7` 被 Maven Central 返回 HTTP 429。22 个测试均停在 `MavenArtifactFetcher` 初始化阶段，没有进入业务断言；因此不能将这次运行记为通过或代码失败。

远端 Actions 已触发（当前 run `43091954307`，另有 APK run `43091945182`）。当前读取到 Device & Authorization、Android Fixtures、Android Settings & Notifications、Dependency Lock Evidence、Fast Gate、Change Impact Analysis、sign-plugin-packages 和 apk 相关前置检查通过；其余 Android 编译/回归作业仍为 pending；未执行前台轮询。

## 尚未完成的实证

- 未启动或操作 `emulator-5554`，因此没有模拟器上的跨行光标、滚动、旋转、后台恢复和性能帧数据。
- 未连接支持振动马达的 Android 实机；触觉强度、节奏和系统设置兼容性未验证。
- 未完成三轮固定输入脚本的首字时间、排空时间、解析次数、重组次数和内存对照。
- 复杂执行过程最终回答的交互仍需在可运行的 Robolectric/模拟器环境中重点验收，尤其是最终回答与工具卡切换时的展示进度。
