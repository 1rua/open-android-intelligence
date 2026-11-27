# 原生会话命令、审批与生成取消修复

日期：2026-10-27。源码基线：`ccccf070e533b4b81aa41ac3da2fdf4edc06a267`。

## 范围与边界

链路保持 `Android App ↔ OAI Core ↔ Hermes Session Adapter ↔ Hermes Agent`。
设备认证、配对、授权及设备请求调度仍由 Core 独占；Hermes 只接入原生会话，不启动第二个
Gateway，也不恢复旧设备工具。既有账号、主密钥、会话 ID、历史和压缩链保持原有存储与归属。

原审查报告用于定位线索。当前代码和测试确认的断点包括 Core 移除 `conversationUi`、
适配器主动拒绝审批、两层流只接受 delta/done/error，以及 Android 原生仓储没有接入审批端口。
报告中的生产部署版本与偶发任务句柄问题不能由这些本地测试独立证明。

## 已实现行为

- Core 根据在线宿主能力与客户端声明协商命令目录、审批卡片、生成取消；Android 进一步检查
  当前选中 Agent 的能力。旧 `/conversations` 路由继续不可用，未实现的批次等能力不启用。
- Android 通过 `/agents/{agent}/commands?languageCode=...` 获取所选宿主的真实命令目录，
  沿用名称、描述、参数提示及内容版本语义；不混入其他 Agent 的目录。
- 原生消息流先发送真实 `generationId`，再传正文与审批 requested/resolved 卡片。
  审批复用协议 §7.2 公开字段，决策走会话下的独立端点，不改写为聊天文本。
- 适配器把 opaque 审批标识与宿主等待标识持久化，按账号、会话、可选档位、首次决策、过期和撤回
  验证后解析宿主等待。宿主内部会话键与请求标识不会出现在手机载荷中。
- Hermes 取消必须携带真实轮次标识；旧轮次不能取消新轮次。Android 不先断开本地流，
  仅按宿主 `cancelled`、`already_completed` 或 `outcome_unknown` 回执收尾。
- Android 将审批回复与停止请求绑定到提交时的 Agent 和会话，并过滤切换前的迟到事件。
  Codex/DSH 保留原有会话级取消请求，不冒用 Hermes 的轮次协议。

## 回归中另外确认并修复的问题

1. 宿主结束与审批超时同时发生时，原生流可能先发送 done，丢失队列中的审批终态和尾部正文。
   现在先结算待审批，再排空已提交事件后结束流；竞态场景连续 15 次独立进程回归通过。
2. 等待审批时停止，如果先 deny 解挂再中断任务，宿主可能继续下一步。真实 SDK 用例已捕获该行为；
   现在先走宿主中断路径，再撤回审批等待，验证停止后不继续执行。
3. 安装器测试原先硬编码个人电脑的 Python 3.14 site-packages，干净 Python 3.11 环境会加载错误
   的二进制依赖。测试现使用自身解释器环境，既有断言保留。
4. 重复或过期审批原先只回传错误码，手机无法得知已落定的决策。现在跨桥保留封闭公开终态，
   同一请求键的终态错误也保存回执，重试不会变成结果未知。

## 证据与验收

自动化覆盖 Core 路由、签名请求、真实两层 HTTP/NDJSON/SSE、SessionBackend、固定 Hermes SDK
插件加载器及 SessionStore，以及 Android 客户端和仓储。Agent 执行使用受控测试处理器，
不调用真实模型或执行敏感 Shell。

最终 Python 3.11 全套（含固定真实 SDK 和安装器）为 **291 passed**；Android 六个相关测试类
合计 **49 项通过**，App Kotlin 编译通过，App 与网关客户端的标准 Android Lint 均为零错误。
审批错误载荷也经过共享协议形状及路径 ID 校验，三项畸形终态回归均通过。
三类代码复核处理了复用、质量、效率各两项：
统一公开字段与选项常量、去除不可达分支及重复查询、回收审批锁并为待审批查询增加索引。

失败复现与复验入口：

```bash
python -m pytest oai-core/tests/test_native_interactions_core.py -q
python -m pytest oai-core/tests/test_hermes_interactions.py oai-core/tests/test_interaction_pipeline.py -q
HERMES_SDK_ROOT=<固定SDK检出目录> python -m pytest oai-core/tests/test_hermes_native.py -q
```

Core wheel 另在仓库外、未安装 Hermes 的干净环境中验证 `oai serve`、签名设备请求、Claim/Result/ACK；
Core Schema hash 保持 `sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23`。
CI 分开运行 Core 独立性与可选 Hermes 交互测试，避免 Core-only 测试引入 Hermes 运行依赖。

本地验证不等于当前生产部署或 Android 真机验收。升级时 Core、适配器和 Android 需使用本批变更；
配套插件仓库通过不可变 Core 提交锁定依赖。完成部署后，在真机逐项确认：

1. 输入 `/` 显示 Hermes 内置及已注册插件命令，点选后正确填入输入框。
2. 测试审批显示真实选项，允许一次和拒绝分别解挂；重复点击和超时不能重复执行。
3. 正文生成中及等待审批时停止，宿主不再继续该轮；新轮次不受旧请求影响。
4. 切换 Agent 后，旧流不会污染新会话；离线或未协商的能力不会显示为可用。
