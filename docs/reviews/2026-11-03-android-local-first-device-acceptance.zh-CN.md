# Android Local-First 模拟器验收记录

日期：2026-11-03（Asia/Shanghai）。本报告仅记录隔离模拟器和合成账号，不代表真机、生产部署或真实模型验收。

## 基线与环境

- 源码基线：`733c6a3079cfc33ad3f6f7aa01b7bf242b782ee4`。基线快照由该提交的 `git archive` 生成；将归档与快照比较时，没有发现文件内容差异，文件权限和所有者元数据不同。共享 worktree 有并行未提交修改，不能将从共享 worktree 直接构建的 APK 称为纯基线。
- 基线 APK：`apps/android/tools/local-first-validation/build/baseline-733c6a30/apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`。SHA-256：`663cc0b4bdb9de21be80b5503ac658ebb37e01a9440fb73ccde82808edff0ce5`。包名 `com.openandroidintelligence.mobile`，版本 `2.2.1-dev.224+733c6a3079cf`，versionCode `2240`。签名证书 SHA-256：`37d9445fab11d827b032b84b0739acb2c33aa8ace1f319ed20a4cc612624ce33`。设备上已安装 APK 的 SHA-256 与上述值一致。
- 前次构建日志为 `apps/android/tools/local-first-validation/build/baseline-build.log`。Gradle 最终报告 `BUILD SUCCESSFUL`，455 项任务中 229 项执行、226 项来自缓存；日志同时有 947 条 `classAnalysis.bin` 缓存写入异常，不能省略这一环境限制。
- 独立 AVD：`oai-local-first-20261103`，Android 15 / API 35，ADB 序列 `emulator-5562`，独立 ADB 服务端口 `5038`。未操作个人真机或清除应用数据。模拟器通过 ADB reverse 访问仅监听宿主机回环地址的合成 Core。
- 服务使用基线源码中的真实 Core 认证及 Native Adapter 测试夹具，账号和数据均为本地合成。当前夹具不运行模型，也不是生产 Core。相关会话 ID 为 `conv_20509d55-c7f0-440e-9f71-325e96d4f784`。

## 基线观察

1. 应用使用合成账号登录后，页面显示 Core 已连接，抽屉显示 Hermes 在线及 1 个合成会话。这证明该次实际登录和列表展示，不证明其他账号或部署。
2. 在该会话输入并发送 `baseline-synthetic-message-001`，Adapter 观察日志记录一次消息提交、一次任务开始和一次任务完成；页面显示合成回复 `开始：完成`。发送后曾观察到“读取会话同步数据失败”提示及通道断开；原因尚未定位，不能归因于本次修复。后续截图只保留了通道断开和回复，没有捕获瞬时提示。
3. 暂停本次合成 Adapter 进程，Core 的未认证请求仍返回 HTTP 401。暂停意味着进程不响应，并不等同于已验证的 Adapter 正常离线。刷新前后以及稍后再次检查，抽屉仍显示 1 个 `native-alice` 会话；重新打开时可看到上述合成用户消息和回复。**该次未复现会话列表清空**。
4. 保持 Adapter 暂停，对专用模拟器中的应用执行 `am force-stop` 后冷启动。启动页为新对话；打开抽屉仍显示 1 个会话，进入后可看到两条合成消息。测试结束后恢复了 Adapter。该结果只适用于此数据和时序，不证明无闪烁、全部历史均完整或其他离线状态正确。
5. 冷启动 `am start -W` 返回 `LaunchState: COLD`、`TotalTime: 1453`、`WaitTime: 1455`，单位毫秒。这是 Android 报告的 Activity 启动时间，**不是列表或消息首次绘制时间**，也不是状态发布耗时。尚无逐帧/时间戳证据可计算消息首帧或闪烁时长。
6. 使用可控夹具对同一会话补测正常下线：恢复 Adapter 并刷新后，抽屉显示 1 个会话；正常停止 Adapter，确认其监听端口关闭，而 Core 仍对未认证请求返回 HTTP 401；刷新后抽屉显示 0 个会话。保持 Adapter 下线并重启应用，抽屉仍为 0 个。恢复相同 Adapter 和会话，再刷新，列表恢复为 1 个。这是基线 UI 的 `1 → 0 → 1` 复现；**不能由此推断底层加密记录被删除**。另一次冷启动的 `TotalTime` 为 2209 ms，同样不是消息首帧指标。
7. 为同一合成账号补建第二个 Native 会话 `conv_beea0c5c-5d64-4d43-88be-76768f3cca1b`，重复准备返回同一 ID，应用刷新后显示 2 个会话。原夹具的创建接口因测试宿主缺少 `_ensure_agent_session` 报错，故在 Adapter 停止期间通过测试存储接口补建；该错误是夹具限制，不能认定为产品缺陷。
8. 生成 640×400 合成色块 PNG，SHA-256 `ce3aabfb2ae0612dfdd4affbc10ccdd52e9b126a0c7f1bdc12414d4276ce44a8`。模拟器系统图片选择器显示此图，选取后应用显示“已核验，可发送”；在第二会话发送后，页面显示图片、合成文字与回复。切换到第一会话再返回、大图查看、应用强停后重新打开第二会话，静态截图均能看到色块。此结果不能证明全过程无闪烁，也不证明真实模型处理了图片。发送后曾显示“通道已断开”。
9. 新对话输入的草稿在切换到已有会话后仍出现在输入框；此项仅记录原有观察，不自行判断需求是否要求按会话隔离草稿。
10. 为性能对照增加基线和候选各 24 个不同的合成远端历史会话。先用 `remote-baseline-23` 进行夹具自检：应用实际展示其专属消息，Adapter 日志记录读取 1 条；该会话不计入样本，剩余基线 23 个、候选 24 个未用于此自检。增加会话后再次前测：在线列表为 50 个；正常停止 Adapter、清除搜索条件后为 0 个；恢复并刷新后为 50 个。该序列与之前单会话复现分开保存。
11. 原版基线 APK 上录制一次从抽屉进入图片会话的过程，逐帧检查发现先后出现“图片预览不可用”占位、图片、再次占位、图片；这是**该次操作实际捕获的闪烁**。视频 12.887 秒、59 帧、可变帧率，平均约 4.58 帧/秒；无法据此精确计算闪烁持续时间、首次内容绘制或 P95，也不能推断未捕获帧。原始视频 SHA-256 为 `d55db8d11546f55985afe8c3df1626a7843f1d1cb0662254fb9fa3be5719e028`。第一次录屏未覆盖完整切换，保留但不作为结论。
12. `733c6a30 + 测量补丁 v3` 的 APK 已构建并在专用模拟器保留数据覆盖安装，APK SHA-256 为 `0cfc2b4654e34c64d0b564fbf3d67a7a93bd7be0f83de2cc5de6776c8bbf9d59`，签名证书 SHA-256 与原基线相同。构建耗时 2 分 57 秒，455 项任务中 227 项执行、228 项来自缓存。两批同一会话的 `local` 采样共 10 条，中位数 1484.31 ms，最近秩 P95 为 2055.01 ms；样本数较少，尾部估计不稳定。统计是从 `openThread` 到 Compose 列表绘制完成，不是物理显示或图片绘制时间。基线没有 `memory` 路径；`remote` 采样首次因 Android CLI 超时退出，没有有效样本。

## 候选 1

候选 Android 源码 `d7036614d30fd4e18c99109e2c2930fdddade3a7`，APK SHA-256 `6f930c4c18aac17bde3b394afcf0e5b41799d8ebd79f57c6bef2bd452e4fdd02`，版本 `2.2.1`、versionCode `1`，签名证书 SHA-256 与基线相同。构建 55 秒，455 项任务中 82 项执行、4 项来自缓存、369 项已是最新。专用模拟器通过 `install -r -d` 保留数据覆盖安装；`-d` 是因为候选版本号低于测量基线，只用于本次可调试模拟器，不能证明正式升级路径。设备内 APK 摘要与构建产物一致。

- 候选 Android 与候选 Core 的 Agent 正常下线场景：筛选会话在线时为 1 个，停止合成 Adapter、Core 仍响应并刷新后仍为 1 个，打开可读合成历史；离线冷启动清除筛选后显示 50 个，恢复 Adapter 并刷新后仍显示 50 个。恢复时 Adapter 状态接口在线，但界面一度显示“实时通道未建立”，不能宣称实时通道恢复通过。
- 合成 Core 完全停止后，应用冷启动进入离线镜像，抽屉显示 50 个会话，打开已有会话可读合成文字。但旧基线合成图片两次检查均显示“原件不可用”；重新启动 Core 并请求连接后曾显示“图片预览不可用 / 本地保存失败”。这是本次设备观察，原因未确定，不得推断已删除或归因于特定迁移。最终 APK 需复验旧媒体和新媒体。
- 当前普通 ADB 结果：候选 1 已验证保留安装数据、Agent 正常停止后列表保留、离线冷启动显示列表与可读文字、Adapter 恢复后列表仍在；**实时通道恢复未证明，旧图片离线与重连显示未通过**。候选 1 的三类绘制采样各有 5 条有效样本，见下表；无需等待更多样本才报告。
- 以下计时是从 `openThread` 到 Compose 列表绘制完成，不是状态发布、物理屏幕显示或图片可见时间。性能样本使用 `d7036614` Android 与 `733c6a30` 合成 Core，不代表最终 APK 的重测。P95 使用最近秩；样本少，尾部估计不稳定。

| 版本与来源 | n | 中位数 ms | P95 ms |
| --- | ---: | ---: | ---: |
| 733 + 测量补丁，local | 10 | 1484.31 | 2055.01 |
| 候选 1，local | 5 | 496.56 | 871.87 |
| 候选 1，memory，两个合成会话 | 5 | 43.09 | 54.49 |
| 候选 1，remote | 5 | 638.42 | 789.64 |

基线 `memory` 不存在，基线 `remote` 因 CLI 超时没有有效样本。候选 `memory` 的采样日志另含一次预热 `local` 567.92 ms，不混入上述五次 `local`。候选 `remote` 每次均核对本地未命中、远端非空返回与 Adapter 合成历史读取。

## 可提交证据

以下截图、结构和消息均为专用模拟器的合成数据；JSONL 已移除绝对时间，并对进程及会话标识作一致的不可逆别名处理。保留原始事件类型、代次、相对单调时钟和耗时，便于复核；不包含 APK、视频或个人聊天。

- [统计汇总](artifacts/2026-11-03-android-local-first-sync/first-draw-summary.json)、[基线 local](artifacts/2026-11-03-android-local-first-sync/baseline-local.jsonl)、[候选 local](artifacts/2026-11-03-android-local-first-sync/candidate1-local.jsonl)、[候选 memory](artifacts/2026-11-03-android-local-first-sync/candidate1-memory.jsonl)、[候选 remote](artifacts/2026-11-03-android-local-first-sync/candidate1-remote.jsonl)、[合成历史读取](artifacts/2026-11-03-android-local-first-sync/synthetic-history-reads.jsonl)。
- [脱敏界面结构摘要](artifacts/2026-11-03-android-local-first-sync/device-snapshots.json)保留各阶段可见数量、离线状态、筛选条件及原始结构 SHA-256。
- [基线正常下线零会话](artifacts/2026-11-03-android-local-first-sync/baseline-agent-offline-zero.png)、[基线图片切换关键帧](artifacts/2026-11-03-android-local-first-sync/baseline-image-switch-frames.png)、[基线图片冷启动](artifacts/2026-11-03-android-local-first-sync/baseline-image-coldstart.png)。
- [候选 Core 离线列表](artifacts/2026-11-03-android-local-first-sync/candidate1-core-offline.png)、[离线图片与文字](artifacts/2026-11-03-android-local-first-sync/candidate1-core-offline-image-recheck.png)、[Core 重连后提示](artifacts/2026-11-03-android-local-first-sync/candidate1-core-reconnect.png)。
- [最终 APK Core 离线列表](artifacts/2026-11-03-android-local-first-sync/final-core-offline-list.png)、[新图片发送后](artifacts/2026-11-03-android-local-first-sync/final-image-sent.png)、[新图片离线缩略图](artifacts/2026-11-03-android-local-first-sync/final-new-image-offline.png)、[新图片离线大图](artifacts/2026-11-03-android-local-first-sync/final-new-image-offline-open.png)、[合成发送事件摘要](artifacts/2026-11-03-android-local-first-sync/final-synthetic-send-summary.json)。

采样工具在操作前核对 AVD、API 和设备内 APK 摘要；汇总工具按进程、会话和代次关联，剔除歧义样本。测试工具通过 5 项目标选择测试和 7 项统计测试；这些不等于设备验收通过。

## 最终 APK 复验

- 实际构建 `403c5c58f5bba7f69b50c5da1878c172d07974ee`，与 `22dadd24d51d5e9b734885594fb93a4bf6a5d4de` 仅 `AGENTS.md` 不同。APK 于 13:24 在专用模拟器通过 `install -r` 保留数据覆盖安装，SHA-256 `a4cbe29527910cdff7528f3d24935c0b91725565e060ba3621aa0cfadacfe1ae`，版本 `2.2.1`、versionCode `1`，签名证书 SHA-256 `37d9445fab11d827b032b84b0739acb2c33aa8ace1f319ed20a4cc612624ce33`；设备内摘要一致，首次安装时间仍为 07:44:43。构建 2 分 4 秒，455 项任务中 8 项执行、447 项已是最新。
- **通过的有限观察**：停止专用合成 Core/Adapter、强停并冷启动应用，离线抽屉显示 50 个合成会话；进入已有会话可读历史。使用最终 APK 新选择并发送合成色块图片后，停止 Core、强停并冷启动，重新打开该会话，截图可见相同色块缩略图；点击后离线大图可见相同色块。此判据只证明本次静态可见，不证明逐帧无闪烁或任意图片均可恢复。
- **合成消息有限观察**：同一新图片消息在客户端日志出现请求开始、同一 generation 的 `generation`、`delta` 和 `done`，合成 Adapter 日志记录一次任务开始和一次完成，页面显示合成回复。该夹具只对第一会话记录 HTTP 提交次数，不能证明第二会话网络层恰好一次投递。静态测试历史未覆盖新事件，应用曾显示通道断开；没有捕获到可归因的 `NATIVE_HISTORY_BEHIND` 错误码，完整 EventSync 恢复和真实模型未验。
- **旧图片限制**：候选 1 中旧基线图片离线及重连后不可用的现象保留如上。当前代码要求旧媒体 `sourceAgent` 精确匹配才允许认领；本次未获得该条旧媒体的元数据或 HTTP 错误码，因此不能把该现象直接归因于此策略，也不能称为已恢复。
- **未完成**：最终 APK 的 Agent 单独停止后完整 `1→1→1`、新图跨会话回切、草稿回切与权威远端媒体读取未执行完整复验；候选 1 的 Agent 列表结果不能替代最终 APK 结果。之后提交 `8b89011debe36a82ec586f534672386791143ec4` 增加迟到交接输入屏障，主流程报告 39 项定向测试通过；本设备未安装该提交构建，不能宣称其设备验收通过。
- 离线测试结束后，专用合成 Core/Adapter 已使用保留的账号、密钥和会话数据恢复启动；这是测试夹具，不是生产服务。真实设备、真实模型、生产部署及完整实时通道恢复均未验收。
