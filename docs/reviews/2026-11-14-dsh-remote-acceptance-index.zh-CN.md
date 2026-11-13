---
status: in_progress
acceptance_status: pending
date: 2026-11-14
tracking_issue: 187
baseline_commit: 12094e684e4bd6d4270513d54b6ef35437536552
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# DSH Remote 迁移验收台账

本台账对应[实施计划](../plans/2026-11-14-dsh-remote-migration-plan.zh-CN.md)、
[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)与[控制契约](../contracts/oai-control-rpc-v1.md)。
`VERIFIED` 只适用于已记录命令、版本、场景和证据的具体断言；`BLOCKED` 表示已明确的环境/能力阻塞；
`UNVERIFIED` 表示未取得所需证据。文档入库不提升运行状态。

## 环境与证据分层

用户确认稍后提供专用手机与 Host，先不进行真机测试；不占用日常 Host 账号、会话或连接。
当前未提供测试连接方式、Android/API、Host/后端版本与服务 origin。独立实现和自动化继续推进。

| 层级 | 证明范围 | 当前状态 |
| --- | --- | --- |
| 协议/本地自动化 | 固定密码/协议向量、类型化请求、隔离、状态机和故障注入 | UNVERIFIED |
| 真实 Host/后端 | 固定 DSH 插件加载、双向握手、后端数据面与 MCP 原生挂载 | BLOCKED：专用环境待提供 |
| 模拟器 | APK 装配、系统生命周期与支持场景 | UNVERIFIED |
| 物理设备 | 权限、像素、焦点、锁屏、overlay、急停与网络切换 | BLOCKED：本轮暂不进行真机测试 |
| 发布/部署 | 实际 APK/Host/服务版本、干净安装与线上能力 | UNVERIFIED |

每份新增证据必须记录：REM 编号、阶段、OAI SHA、固定 upstream SHA、APK/Host SHA 或版本、命令、
环境、断言、状态与仓内证据位置；敏感正文、账号、密钥不入库。不能将某层的 VERIFIED 传播到另一层。

## 阶段门禁

| 阶段 | 跟踪 | 必需门禁 | 状态 |
| --- | --- | --- | --- |
| P0 | #188 | 契约增量、Noise/协议向量、依赖许可/ABI、公开目标绑定探针 | UNVERIFIED |
| P1 | #189 | 双身份、原生通道、最小真实 Session V3 与未改 remote_* 双向链路 | UNVERIFIED；真实环境 BLOCKED |
| P2 | #187 后续子项 | Harness/Codex/ACP 数据面及现有聊天/附件/审批/助理回归 | UNVERIFIED |
| P3 | #187 后续子项 | 最小账本、原生执行、SoM、收敛、overlay、锁屏与敏感确认 | UNVERIFIED；物理设备 BLOCKED |
| P4 | #187 后续子项 | 进程内 Bridge/Stdio MCP、双入口和跨后端无误投 | UNVERIFIED；真实 Host BLOCKED |
| P5 | #187 后续子项 | 功能覆盖、旧依赖退出、Fast/PR/Full、独立评审与发布矩阵 | UNVERIFIED |

## REM 需求矩阵

| 需求 | 阶段 | 必需场景与证据 | 状态 |
| --- | --- | --- | --- |
| REM-01 | P1/P2 | 单账号、官方/兼容服务真实能力缺失诊断 | UNVERIFIED |
| REM-02 | P1 | Client/Host 独立身份、凭据、退出和撤销 | UNVERIFIED |
| REM-03 | P0/P1 | Noise 向量、LAN/P2P/TURN/Relay、重连未知写入不重放 | UNVERIFIED |
| REM-04 | P1/P2 | 完整端点隔离、同名原生 Session/消息不串线 | UNVERIFIED |
| REM-05 | P2 | Harness/Codex/各 ACP 实际数据面与能力矩阵 | UNVERIFIED |
| REM-06 | P2 | 当前 Session 模型/effort 确认及旧代际拒绝 | UNVERIFIED |
| REM-07 | P2 | 现有聊天组件/交互保留与连接接线回归 | UNVERIFIED |
| REM-08 | P2/P5 | 分块附件、真实提交、未知发送与离线媒体身份 | UNVERIFIED |
| REM-09 | P1 | 真实最小 Session V3、受限文件面、未改原生工具互通 | UNVERIFIED |
| REM-10 | P0/P1/P3 | 总线单步/独立任务、JSON/CLI 同义解析和请求状态机 | UNVERIFIED |
| REM-11 | P3 | Kotlin 屏幕/WASM 数据分离、共同内核与沙箱授权 | UNVERIFIED |
| REM-12 | P3 | 原生/WebView/Flutter/图像界面树与 SoM，快照绑定不伪造节点 | UNVERIFIED |
| REM-13 | P3 | 400ms 静默/3000ms 截止、事件风暴、执行与收敛分开 | UNVERIFIED |
| REM-14 | P3 | overlay 无焦点冲突/截图泄漏/远程自批准，锁屏与急停 | UNVERIFIED；设备 BLOCKED |
| REM-15 | P1/P3 | 默认手动、范围只读、后台独立、敏感强制确认与撤销 | UNVERIFIED |
| REM-16 | P0/P1/P3 | 原子最小账本、崩溃/取消/交付竞态、旧任务不恢复、status 查询 | UNVERIFIED |
| REM-17 | P0/P4 | Stdio/进程内桥、已有路由无新监听、全部真实后端串行委派 | UNVERIFIED |
| REM-18 | P5 | 生产/构建/安装/CI/测试/插件工具链退出旧 Core/契约/adapter | UNVERIFIED |
| REM-19 | P2/P3/P5 | 聊天/助理/附件/通知/定时/主动推送/发送/插件/镜像/导出覆盖 | UNVERIFIED |
| REM-20 | P0–P5 | 需求、实现、本地自动化、模拟器、设备、部署证据独立可追溯 | UNVERIFIED |

## 定向测试矩阵

| 组别 | 必需测试 |
| --- | --- |
| 请求与任务 | JSON/CLI 等价、未知字段/Shell 拒绝、总线 owner/request 隔离、多任务 create、显式 task/snapshot 跨工具 |
| 最小账本 | 同 ID 同摘要只回执、改参数/任务冲突、受理/栅栏/端口/结果发布崩溃点、账本损坏安全拒绝 |
| 无历史恢复 | Prompt/短信正文/树/图像/结果正文不持久；重启不回放/续做；旧 task 只查最小回执、resultAvailable=false |
| 取消 | 待确认请求可立即精确取消、迟到取消 A 不误停 B、整任务冻结、MCP 断开仅停等待、未知结果不重放 |
| 屏幕 | 陈旧/跨任务快照、旋转/缩放/窗口变化、SoM 同源、无节点图像、400/3000ms、捕获失败不改执行事实 |
| 本机门禁 | READ/CAPTURE/CONTROL 分开、排队后/派发前/交付前复核、权限撤销、锁屏、急停与 Shizuku 不绕过 |
| overlay | 第三方输入焦点保留、树/截图无确认内容、节点/坐标拒绝命中胶囊；API34 窗口截图与较低 API/MediaProjection 分验 |
| Bridge/MCP | 错误 bearer/Host/Origin、登出/切 origin/卸载失效、无新监听、Stdio stdout 纯协议、重启查原回执 |
| 公开委派 | 同 sessionId 跨 Host、选择后/调用前切换、在途流关闭、目标代际与结果归属、断线不重投、不抢回工作区 |
| 功能保真 | 默认助理生命周期、附件迟到交接、模型拒绝/切换、推送接收确认、定时恢复、离线媒体、导出与旧服务管理退役 |

## 证据记录

启动时仅完成需求/计划登记；尚无新增运行验证。后续记录必须按具体提交和场景追加，不能把待执行命令记为通过。
