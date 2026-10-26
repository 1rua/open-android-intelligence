# Android 屏幕原生执行与生命周期审查

日期：2026-10-26。开发基线：`736e458c481aa7a79b5e2d984efda7bcaf4c1287`（`dev`）。
本记录审查本次 worktree 的原生执行、底层授权接线、投影资源生命周期和前端改动范围。
最终提交与 CI 结论由 PR 中固定的提交 SHA 和验证记录给出；本记录不将源码检查当作真机验收。

## 复用来源和分发声明

Hermes Relay 固定参考提交为 `da782a8d74eb6631ea2f097b3cc69c6452ec33de`。
复用的代码组织和 Android 调用来自：

- `ScreenReader.kt`：扁平多窗口节点、节点属性和每个子节点的 `finally` 回收。
- `ScreenHasher.kt`：对屏幕语义内容进行 SHA-256 变化检测。
- `ActionExecutor.kt`：Accessibility 语义动作、系统全局动作和等待 `dispatchGesture` 回调。
- `HermesAccessibilityService.kt`：用户通过系统授权后绑定的无障碍服务、活动窗口读取和连接生命周期。
- `BridgeSafetyManager.kt`：只参考默认拒绝、权限过期、撤销与本地确认思路；未移植其业务、网络、偏好存储或覆盖层。

OAI 增加了遍历容器数量、深度、实际 JSON UTF-8 字节数上限、密码正文隐藏和过期节点引用保护。
状态摘要使用项目现有规范化 JSON 序列化，避免拼接字符串时字段分隔符造成歧义。
没有引入 Relay 的 Bridge 通信、专属 WebSocket、网络监听器或任何 Agent 名称分支。

上游声明为 `Copyright (c) 2026 Axiom Labs`，许可证为 MIT。
完整原文保存在 [LICENSES/hermes-relay-MIT.txt](../../LICENSES/hermes-relay-MIT.txt)，
同时作为 [Android 许可证资产](../../apps/android/screen-control/src/main/assets/licenses/hermes-relay-MIT.txt)
随应用打包；两份文本已用 `cmp` 核对一致。移植文件的头部列出来源和许可位置。

## 原生执行检查

公共类型位于 `control-ports/ScreenContracts.kt`，Android 实现位于 `screen-control`。
原生层不持有设备配对、不建立本地授权、不接受远程授权证明；生产调用由
`RemoteScreenRuntime` 经过现有内核、设备请求和本地授权进入。

读取硬上限为 16 个窗口、512 个输出候选节点、2048 次容器访问和 32 层深度。
单字段最多 256 个 UTF-16 代码单元；默认返回 128 个节点、24 层、48000 字节。
返回预算按整个响应的规范化 JSON UTF-8 字节数计算。
搜索或状态查询不改变同一屏幕的 `revision`；`hash` 表示语义内容，`revision` 额外绑定窗口事件代次。

节点 ID 由窗口 ID 和子节点路径组成。执行时先重新读取屏幕、核对 `expectedRevision`，
再从新窗口根节点重新定位，执行 `refresh()`，并核对节点属性和事件代次。
没有跨请求保存 `AccessibilityNodeInfo` 活引用。
坐标动作检查当前屏幕尺寸和旋转；`rotation` 为 Android 的 0、1、2、3，分别对应 0、90、180、270 度。
多窗口指默认显示器上的应用、弹窗和系统窗口，不声明已支持其他显示器的坐标控制。

点击、输入和滚动优先采用平台语义动作。坐标长按、滑动和拖动采用系统手势接口。
拖动先保持同一指针 500 毫秒，再用 continued stroke 移动；每次派发前再次检查授权。
返回和主页调用平台全局动作，应用启动使用包管理器取得的启动 Intent。
未使用 Root、ADB、Shell 或隐藏输入注入接口。

操作返回 `performed` 与实际观测的后续屏幕。Android 接受操作不代表目标应用已经完成渲染；
本实现不通过等待固定时长或重试伪造效果。后续状态不可读或权限失效时，正文为空并附带明确原因。
操作已经派发后，Android 没有通用的手势撤回 API：请求取消停止等待并沿用设备请求的未知结果终态；
回调缺失、系统取消回调或拖动保持后失败使用 `SCREEN_ACTION_OUTCOME_UNKNOWN`。
这些情况均不得自动换请求 ID 重放。

## API 31 和 API 34 生命周期检查

| 环节 | 已检查行为 | 证据范围 |
| --- | --- | --- |
| 无障碍服务 | Manifest 要求系统 `BIND_ACCESSIBILITY_SERVICE`；用户必须在系统设置主动开启；反馈中断撤销本地会话但保留系统绑定，解绑和销毁清除绑定 | 源码、API 31 Robolectric、替身执行测试 |
| 本地权限 | READ、CAPTURE、CONTROL 分开；会话绑定账号、配对、连接代次和授权修订；控制复用 `DefaultControlAuthorizer` 的本地确认 | 接口和生产调用链源码、授权测试入口 |
| API 31–33 旋转 | 配置监听更新同一个 VirtualDisplay 和 ImageReader；关闭时注销监听 | 源码检查；未进行物理设备旋转验证 |
| API 34+ 投影 | 先获得系统授权，再等待既有前台服务就绪；先注册投影回调再建立一个 VirtualDisplay；尺寸变化使用 `onCapturedContentResize` | 源码、API 34 Robolectric 编排替身；不等同于真实投影 |
| 投影授权票据 | 不存储、不重放票据，不用同一授权重复建立 VirtualDisplay | 源码检查和编排测试 |
| 投影被系统停止 | `captureStopped()` 清图片、取消截图请求和停止远程投影前台服务；独立 READ/CONTROL 本地会话不随 CAPTURE 的系统权限失效而撤销 | 生产安装器和生命周期源码 |
| 帧交付 | 撤权或会话代次变化后丢弃迟到帧；取消继续传播；读取或编码失败释放资源 | Source 编排替身测试、生产调用链检查 |
| 远程图片 | 内存句柄绑定原本地会话，分块返回前复核权限、锁屏和系统投影；失败交付清掉本次迟到句柄 | 源码和 ImageStore/RemoteScreenRuntime 测试入口 |
| 断连和账号切换 | 撤销屏幕会话、清确认和图片、取消执行、释放远程投影来源 | GatewayRuntime/DeviceExecutionDriver 接线源码 |
| 前台服务隔离 | 本地圈选和远程截图各有所有权；释放一方不停止另一方；过期的排队启动不恢复权限 | 所有权测试和源码 |

截图沿用 `capability-ports` 的 MediaProjection 实现，没有增加第二套截图框架。
远程截图使用独立系统同意会话，不借用会话附件圈选已得到的授权。
未新增授权界面，因此当前缺少对应本地入口时默认拒绝；具体接入顺序见
[接口说明](../guides/android-screen-interfaces.zh-CN.md)及独立前端交接文档。

## 本次交叉审查发现与处理

| 问题 | 处理 |
| --- | --- |
| 手势未知结果的错误码与设备执行结果映射不一致 | 统一为 `SCREEN_ACTION_OUTCOME_UNKNOWN`，映射到现有未知结果终态 |
| 控制副作用之后撤权可能被报为“拒绝执行” | 生产调用记录 `controlPerformed`；执行之后的授权失效保留未知结果并禁止正文交付 |
| 已保存截图分块交付前可能发生系统投影撤销或锁屏 | 交付前再次检查系统可用性与本地授权，失效时清理缓存 |
| 撤权清缓存后，旧采集请求仍可能迟到写入句柄 | 记录本次创建的句柄；未成功交付时在 `finally` 精确清掉该句柄 |
| 投影 resize 在会话锁内回调授权层，可能与 Source 释放形成相反锁序 | 对外停止回调移到会话锁之外 |
| 只有控制授权时动作结果可能附带屏幕语义树 | 只有同时拥有 READ 授权才返回树，否则返回 `SCREEN_READ_PERMISSION_REQUIRED` |
| APK 未自动携带仓库根目录的 MIT 文本 | 增加许可证资产，使二进制分发也携带原始声明 |
| 把 `onInterrupt()` 当成解绑会使仍启用的无障碍服务永久不可用 | 反馈中断只增加代次并撤销本地会话，保留系统绑定；后续真实本地同意可重新建立会话，无需开关系统无障碍权限 |

`onInterrupt()` 的处理依据为 [Android 官方 API 语义](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)：
它用于中断无障碍反馈，不表示系统解除服务绑定。保留绑定不会恢复已撤销的本地屏幕会话。
上述处理已检查到对应源码；最终自动化回归由主 Agent 统一运行。没有使用真实设备凭据或读取用户实际屏幕。

## 前端范围核对

相对 `dev` 检查 `git diff` 后，以下现有界面内容没有变化：

- `conversation-ui`、`plugin-ui` 及 `app/src/main/res`。
- 现有设置、权限管理、平台设置、通知设置和插件管理页面。
- 现有会话页面、登录页面和首次启动引导。

`GatewayRuntime` 的改动只把既有连接/账号生命周期传到屏幕底层；
`DeviceExecutionDriver` 只接入既有确认、取消、结果映射和幂等执行记录。
没有新增 Compose 页面、按钮、弹窗、覆盖层、主题或交互文案。
新增无障碍服务描述 XML、Manifest 合并和许可证资产属于底层系统接线。

## 自动化证据和未验证项目

本工作单独执行 `:screen-control:testDebugUnitTest --offline --no-build-cache`，
最近一次独立结果为 16 项全部通过、0 跳过；其中 10 项为执行与遍历替身测试，
1 项为 API 31 平台节点属性测试，5 项为 API 31 平台 Runtime 集成测试。
随后增加“拖动保持后撤权不继续移动、保留未知结果”的第 17 项回归用例，
以及“反馈中断撤销本地会话但保留系统绑定、重新本地同意后可用”的第 18 项回归用例，
均交给主 Agent 的最终汇总运行验证；最终数量与结果以该轮记录和 CI 为准。

本审查未进行 Android 真机或模拟器操作，未声称投影系统弹窗、实际截图画面、跨应用点击、
输入法、厂商无障碍差异、分屏旋转和长期运行内存行为已经完成设备验收。
后续前端仍需接入系统无障碍入口、MediaProjection 系统同意、本地屏幕会话授权及撤销，
并按交接文档完成用户设备验证。
