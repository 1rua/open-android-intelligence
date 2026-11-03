# Android 附件上传回归修复记录

日期：2026-11-03（Asia/Shanghai）。实施基线：`4c6bfc981225ddf873692465645e44d0d59b8bdb`；上一轮前对照：`733c6a3079cfc33ad3f6f7aa01b7bf242b782ee4`。独立分支 `feature/android-attachment-upload-20261103`，目标 `dev`。

## 范围与验证边界

检查 Android → Core → Agent 附件链路及反向代理，保留 SHA-256、加密、本地镜像与可靠投递。原始图片、日志、JSON 与临时工具只在忽略目录本机保存，交付材料仅 Markdown；源码与必要回归测试正常提交。

用户症状为较大图片约 3.8 MB 停止更新、较小图片 100% 后停在校验并失败。该大小仅为待验证线索；不得据此预设 4 MB 限制、放宽校验或延长超时。

## 已核实静态链路

- `GatewayAttachmentDraftCoordinator.prepare` 先加密暂存并记录真实长度与摘要，再经 `AttachmentUploader` 执行 create → status → PUT content → POST commit。
- `GatewayTransport.writeStreamBody` 使用固定 Content-Length、64 KiB 流写入，并核对实际长度与 SHA-256。上传字节进度在 `output.write` 返回后推进；服务端接收确认与 commit 完成是后续独立阶段。
- `HttpAttachmentTransport.commit` 实际发送 `{}` JSON，并验证返回 attachmentId 与 uploaded 状态；不是空正文请求。
- 比较 `733c6a30..4c6bfc98`，gateway-client、conversation-data、encrypted-store 三个底层模块没有改动；上一轮涉及 Runtime 中 Agent 范围注入及 UI。此事实不能单独排除间接回归。

## 实际复现与修复

### Core 真实 HTTP 基线：未复现常规卡点

通过真实 `create_app` 与 TCP HTTP 入口发送 3,800,140 字节，签名 PUT 成功返回 staged，随后 `{}` commit 返回 uploaded，长度与 SHA-256 匹配；单项探针约 0.5 秒通过。只证明本地 Core，尚不能代表部署中的代理及 Android 请求。

专用流式 PUT 绕过普通 JSON 的 1 MiB 限额，按 64 KiB 读取；未发现 Core 固定 4 MiB 限制。接收正文期间不持有 SQLite 事务；存储事务在接收完成后发生。Core 附件入口及存储代码在上一轮前后无修改。

### commit 中断恢复：独立已证实缺陷

公开 AttachmentStore 接口回归模拟 CAS 复制仅写 64 字节后发生存储错误；重试 commit 先前可返回 uploaded，随后读取却报 DECRYPTION_FAILED。最小修复先复制到加密临时文件，再原子替换正式文件，避免半份密文被视为成功。审查补充：未验证的 uploading 状态即使正式路径已存在，也从已完整校验的暂存源重建，覆盖旧版本遗留的半成品，新增对应读取完整正文的回归。

该项红绿测试已完成，修复前 1 项失败（读取报 DECRYPTION_FAILED），修复后 1 项通过；附件策略回归 26 项通过。分批提交 `01c4a8d`。不能据此认定它造成用户的常规上传卡顿；主症状仍需设备与代理差分。

### Android 请求写入停滞：已复现，最终修复待 CI

真实 TCP 服务端读完请求头后停止消费 3,800,000 字节正文。通过连接工厂将既有超时缩短为 250 ms，公开 `GatewayTransport.execute` 在 1.5 秒内未退出，证据有 write_started、无 write_completed。该失败说明读取超时并不能约束阻塞中的请求写入；TCP 缓冲耗尽的实际大小随系统和网络变化，不能将 3.8 MB 写成固定产品上限，也不能据此证明现场代理就是触发方。

最小修复为每次网络写入及 flush 使用既有空闲预算，超时主动断开连接并报告请求写入超时，保留调用方取消原因。未增加附件总耗时上限；持续进展的大附件可运行超过一个预算。曾验证整份请求共用总时限会误伤 4 次各 100 ms 的持续写入，该方案已放弃。

本地磁盘读取保持既有错误归属，网络超时不用于给本地文件读取分类。新增三个公开接口回归：停滞写入超时、持续进展总时长超过单次预算仍成功、调用方取消保留原因且真实 socket 关闭。用户随后明确禁止本地编译，最终逐次网络写入预算版本未在本地执行；先前草稿通过结果不作为当前版本绿灯，等待 CI 编译及测试。

## 自动化与设备验收

基线定向命令：`:gateway-client:testDebugUnitTest --tests '*AttachmentUploaderTest' --tests '*GatewayRequestBodyTest' --tests '*GatewayTransportConcurrencyTest'`，实际 14 项通过、0 失败。覆盖既有摘要/长度、上传与 commit 状态及并发 HTTP 行为，但没有复现用户的大图/校验挂起；不得作为症状修复证据。

正式 Core HTTP 回归在 `test_core_transport.py`，分批提交 `10969a7`：100 KiB、1 MiB、3 MiB、4 MiB、50 MiB 均成功上传及 commit，返回长度与摘要一致。真实 aiohttp 反向代理逐块转发 3 MiB 后验签及 commit 成功；故意超过测试代理的 4 MiB 限额返回 413，Core 状态仍待上传。该 4 MiB 为测试故障注入，不能推断部署环境配置。定向结果 6 passed、21 deselected，约 2.72 秒；HTTP 样本为合成二进制，不冒充真实图片解码或消息发送验收。

Terra 已准备专用 API 35 模拟器 `oai-attachment-upload-20261103`（`emulator-5580`，独立 ADB 5049）及真实可解码的 100 KB、1 MB、3 MB、4 MB、50 MB PNG。合成 Core/Adapter 使用基线归档源码，端口 18981/18982，不使用生产账号。基线设备内 APK SHA-256 为 `613df3c045fa731ae22a2559d059c789c0b644faa749fd860e2b1ab61629e0a7`。

截至本次记录，100 KB 图片在真实 App 中已显示“已核验，可发送”；尚不能以此替代最终消息发送、4 MB/50 MB、代理和断网重试验收。阶段耗时及后续结果由 Terra 单独记录。最终修复 APK 使用 CI 产物，不再进行本地编译。

## 已执行检查

- 最终 Core 文件组合（审查补修后）：`test_attachment_policy.py` 与 `test_core_transport.py`，54 passed，约 19.27 秒；包含新旧 CAS 半成品恢复及真实 HTTP 大小/代理回归。
- 本轮基线 Android 14 项通过不等于最终 Kotlin 修复通过；最终运行以当前 PR 检查为准。
- `npm run lock:check` 通过（8 项）；`git diff --check` 通过。
- 遵照最新用户要求，后续不在本地编译，Android 构建和最终回归交由 CI。首次 CI（`1e3d6a81`）中 Core 3.11/3.12、Hermes、DSH 与 APK 通过；Android 374 项有 2 项新增测试失败，具体结果见[首次 CI 记录](2026-11-03-pr76-attachment-upload-ci.zh-CN.md)。没有执行会触发本地 Gradle 的 `test:fast`/`test:pr`；不能将等价或部分检查标成这两条完整门禁已通过。

## 分批提交与 CI

- `01c4a8da`：新附件 CAS 复制使用临时副本与原子替换。
- `10969a71`：真实 HTTP 100 KiB 至 50 MiB 与反向代理完整性回归。
- `2a275331`：请求写入停滞超时及持续进展/取消回归。
- `be00b2a2`：修复已有半成品恢复；网络写入超时不改变本地源读取错误分类。
- CI 增加已有 `AttachmentPhaseTest`、`AttachmentRecoveryTest` 的定向执行，检查已确认 uploaded 不重传、未知结果状态与恢复。工作流 YAML 解析及测试路径检查已通过；Android 最终运行结果以 PR 当前提交检查为准。

## 剩余风险与交付

已完成上述受控故障复现与部分修复；用户报告的实际部署卡顿尚未闭环。Core 测试计数与 Android CI/设备结果分别记录，不引用上一轮计数冒充本轮结果。

公开状态 staged 不能区分未收到正文与正文已暂存；未获上传确认的重试可能重传相同正文。已确认 uploaded 的附件查询后复用，不重新 PUT。该既有协议边界不能通过猜测成功来绕过，本轮不新增跨仓状态协议。

追加忙碌会话探针：同一真实 Core HTTP、Hermes bridge 与 SQLite 中，4 MiB PUT+commit 空闲时约 86 ms，generation gate 未释放、SSE 保持打开且 eventSync 读写时约 140 ms，上传完成时 generation 仍 running；本机探针 1 passed。只能排除这个受控条件必然阻塞，不能排除部署中的其他同步阻塞或长事务。
