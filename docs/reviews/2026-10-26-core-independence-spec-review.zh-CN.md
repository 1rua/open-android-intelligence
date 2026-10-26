# Core 独立化规格审查

审查范围：`git diff f2c4a4eab081aa48fc0307c51f0f3c8754a5891d...eb6798dabbe4acbe5a45bbfe1dce83131b3513cd`，提交仅 `eb6798da`。规格来源为本次用户完整要求及仓内迁移/部署文档。另核对 Hermes 工作树基线 `b64dda00a0de9ff1a95a375dcf92f62b5a1027b3` 的未提交瘦身补丁，不视为已发布状态。

## P1：多个 Hermes 入口可以破坏账号一对一映射

用户要求：“Core 与 Hermes 账号显式一对一映射”“不同账号不得共享 Hermes 会话上下文”。

原提交 `oai-core/src/oai_core/sessions.py:138` 至 `:157` 仅验证每个配置块内的映射；`adapters/hermes.py:17` 至 `:35` 同样只检查本实例。`AgentRegistry.register` 没有校验同一宿主的多个入口。

最小运行复现：同一个 `http://127.0.0.1:8792` 配置 `hermes_a.accounts={alice:shared}` 与 `hermes_b.accounts={bob:shared}`。真实 `build_registry` 成功，两个实例为各自账号生成的路由头均为 `X-OAI-Account: shared`。宿主在线后，两名 Core 用户会读取、修改相同原生会话。应在注册层对同一宿主实施双向唯一映射，覆盖直接构造 Registry；同一账号的入口别名仍可保留。

修复复核通过，P1 在当前未提交补丁中关闭：注册层按实际 HTTP 客户端 `yarl.URL` 规范化宿主，统一检查双向唯一；同 owner 多别名、不同宿主继续允许。独立复跑 9 项回归通过，并确认域名尾点、普通 IDNA、`faß`/punycode 和展开 IPv6 的冲突映射全部拒绝。`yarl` 已直接声明。原发现仍对应上述提交，不能将补丁结论倒用于旧 SHA。

复核文件 SHA-256：`adapters/hermes.py` 为 `ed58dc26b96cba1a98bc716d09d8af2f359113953ab3611b767ab7f4f1e7d539`；`sessions.py` 为 `6e7eac2d97498695cfb2cc3caa1cfd70fc27232eb92de975d9630c8f381c5512`。

## 其余核验

已逐项阅读 Core 的直接及延迟导入，未发现 SDK 反向依赖；设备状态机与旧基线逐行比较，签名、Schema Hash、generation/grantRevision、队列/结果、超时/取消和游标算法保持原样。SessionBackend 复用 Core 存储库，未构造设备请求或凭据状态机；独占连接正常生命周期可阻止旧库竞争写入，未知提交故障路径由另一审查单独处理。可选包使用原生 SessionStore/SessionDB 与压缩链接口；Hermes 仓旧模块转发至同一实现。未改 OpenClaw。

生产 Hermes 部署、模型服务及 Android 真机尚未验收，现有文档已明确披露，属于证据边界，不据此列代码缺陷。
