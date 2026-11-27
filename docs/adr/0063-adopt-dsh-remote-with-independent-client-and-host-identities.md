---
status: accepted
date: 2026-11-13
implementation_status: not_started
acceptance_status: pending
issue: 185
---

# 采用 DSH Remote 并分离手机主动客户端与被控主机身份

目标架构用固定版本的 ds-harness-remote 取代 OAI Core、Gateway Protocol v2 与旧会话适配器，
复用其账号、设备目录、Noise 加密、连接协调和各 Agent 数据面。手机保持一个活动 Remote 账号，
同账号设备互相发现；默认使用官方服务，允许兼容自托管地址，不由 OAI 再部署中心鉴权、穿透或 Relay。
本决策已接受，不表示现有代码或发布已经切换；完整要求见[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)。

登录自动建立 OAI-Remote-Client 与 OAI-Virtual-Host 两种独立角色，分别持有设备身份、私钥、
凭据和连接生命周期。Host 默认仅被控，维持系统允许期间的轻量出站待命；Client 可随聊天工作独立休眠。
同账号和加密只证明访问关系与通道身份，Android 平台内核继续是设备能力授权唯一权威。
Host 限制不能连带关闭 Client 的电脑发现功能，退出或切服务须使旧连接与授权失效。

## 取舍

继续维护独立 Core 可以沿用已有状态机，但仍需维护中心服务、旧契约和各宿主桥。
采用 Remote 可减少这些独立维护面，并跟随原生 Agent 能力；代价是依赖 Remote Server 的账号、
信令与转发服务，并承担原生 Android 协议适配、手机 Host 和互通验证。上游没有现成 Kotlin SDK，
不能把复用协议写成直接引入某个 Kotlin 包，也不能把加密描述成不依赖 Server 首次身份分发。

不选择一个身份兼任两种角色，因为角色凭据、仅被控限制和后台生命周期会互相干扰。
不维护上游分叉；OAI 在本仓库补齐兼容层，以固定向量和真实 Host 互通控制升级风险。
不要求旧版兼容或旧数据迁移，最终版本不得保留旧服务旁路；现有用户功能仍逐项验收。

## 替代范围

本决策替代旧 Gateway/Bridge/统一 Core 的服务拓扑、线协议、服务端身份与适配器部署决策，
包括 ADR-0002、0006、0007、0013、0023、0030、0035、0037、0047、0048、0054—0056、0060
中依赖旧服务的部分。ADR-0057 的旧账号/部署范围分类退出新架构，凭据不随共享会话泄露的原则继续有效。
具体替代关系见[事实基线](../architecture/2026-11-13-dsh-remote-baseline.zh-CN.md)。
Android 本机授权、受保护插件隔离、加密镜像和用户显式附件选择原则继续有效。
