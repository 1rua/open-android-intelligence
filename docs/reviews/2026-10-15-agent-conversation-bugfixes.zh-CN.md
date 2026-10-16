# Agent 对话五项问题修复

基线：App `1bdb53b`、Hermes 插件 `30b2847`、OpenClaw 插件 `9b81b97`。

最终集成与 PR 交付日期：2026-10-16。

## 行为变更

1. 麦克风入口改为前台录制 AAC/M4A，结束后将 `audio/mp4` 原始附件上传给 Agent；不再依赖系统语音识别。取消、离开应用和录制失败释放麦克风并清理录音。单独发送语音不消耗已有文字和附件草稿；上传未完成不发送消息，结果未知时先恢复受理事实。
2. 停止操作捕获实际线程与 generation，调用真实宿主取消方法。Hermes 能解析 scoped 回调中的宿主 runner；OpenClaw 使用 SDK 的 AbortSignal，并等待实际运行结束。未知结果保留未知状态，不提前显示停止成功。
3. 助手消息与用户终态按 `generationId` 关联，单条消息完成不等于整轮完成。结束、失败或取消后清理同一轮残留的 streaming 进度；旧事件、旧停止响应和旧 current-generation 查询不能关闭新一轮或污染切换后的线程。
4. 新建按钮直接请求 `POST /v2/conversations`，由插件建立独立空宿主会话并保存绑定，不自动发送 `/new`。用户手动发送 `/new` 保持透传。重复客户端创建身份复用同一会话；失败保留原线程和草稿。
5. 斜杠菜单只使用实际宿主注册表，支持插件/技能命令；重新进入菜单刷新，按系统语言请求目录。空目录、加载中与失败如实显示，提供重试，不合并预设命令，不把标题当作参数提示。

## 并行分工

Sol 负责 Android 状态机、录音生命周期、Hermes 和 OpenClaw 宿主桥接；Luna 负责命令目录界面与映射，以及工具链和统一 Android 构建。共享协议由主 agent 统一维护，各 agent 按文件边界修改，最终共同验证。

## 协议与升级

协议契约提交：`4dd825ad3ae6ad20019ff38f6ff5c4f1a8708394`。

核心摘要：`sha256:59244afa522108614123e4d047a8ff51f6f2e2651e9b800bb8c63e5e6e82d320`。

完成事件的封闭 Schema 新增可选 `generationId`，状态事件规范副本与动态分派 Schema 对齐，命令发现响应 Schema 与 Android 使用的字段统一。插件的契约 pin、OpenClaw 运行快照与 Android 摘要必须配套更新。仅替换 App 无法修复旧插件的宿主桥接，而且摘要不一致时会被协商拒绝。

三个仓库的修复分支分别通过 PR 提交审阅。顺序为：先推送主仓契约提交，再推送两插件提交，最后推送含插件固定 SHA 的 App 提交；本轮不创建发行标签或合并 PR。合并主仓时需保留契约提交 `4dd825a`，以继续满足插件的固定 SHA 引用。

| 仓库 | 修复提交 | PR |
| --- | --- | --- |
| Hermes 插件 | `3488f57c1c3ac70b41b06ea281910a78bf4e930a` | [#1](https://github.com/1rua/hermes-gateway-plugin/pull/1) |
| OpenClaw 插件 | `9ad64561552810c05dd61c18ea3bf2383ac46de4` | [#1](https://github.com/1rua/openclaw-gateway-plugin/pull/1) |

## 验证记录

- 主仓 Node 全套回归：75 个文件共 795 项通过（含 Gateway 契约回归 168 项）；TypeScript 类型检查通过。
- 两插件契约 pin 与主仓匹配检查通过。
- 跨宿主一致性：Hermes 与 OpenClaw 各 70 个向量通过，3 项输出一致性检查通过。
- Hermes 独立测试：372 项通过、28 项原生宿主专用测试跳过；原生矩阵另行强制运行。
- Hermes 真实固定宿主矩阵：42 项通过；包含 AAC 原始附件、实际处理任务取消、流式结算和独立会话。
- OpenClaw：33 个文件共 152 项通过，类型检查、运行入口构建和契约快照检查通过。执行了固定 SDK 的真实 runner/context/media/routing；原生 RPC 边界为测试替身。
- Hermes 与 OpenClaw PR 的 GitHub CI 均通过。
- Android 领域层 23 项、网关客户端 211 项、会话数据 58 项、会话界面 260 项测试通过；包含真实 Hermes HTTP 互通、10 项独立语音提交回归与终态恢复场景。
- App 模块 232 项测试通过；以上五个 Android 模块合计 784 项通过。审批与登出集成测试补齐事件夹具，并沿用 Robolectric 主线程调度，保留真实 HTTP 与审计断言。
- `:app:assembleFullDebug` 构建通过；APK 使用仓库固定调试签名，签名身份校验通过。
- 此环境无运行中的 Android 模拟器或真机，未验证实际麦克风授权和 AAC 编码，也未连接用户部署的模型执行端到端测试。
