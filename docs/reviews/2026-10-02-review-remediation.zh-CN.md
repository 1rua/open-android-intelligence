# 2026-10-02 代码审查逐项核验与修复记录

仓库基线：`f2e28dff60f21293efcbf2ac38290fc72b5060d3`（当前主分支与报告一致）。用户提供 2026-10-01 报告、31 项 findings 和复现证据；未将报告严重度等同远程可利用性。当前仓库无 SECURITY.md/AGENTS.md，按 accepted 总规格、契约及 ADR 判断边界。

本记录追踪原报告的 31 项问题，库边界与完整产品链路分别标注。2026-10-03 的 PR 复核发现登录限流、密码重置交错、Android 生命周期和插件回滚仍有缺口；下表已同步补充修复后的范围，不再沿用初稿的「23 项完成」统计或草稿状态。补充修复、回归测试和剩余限制见 [2026-10-03 补充修复记录](2026-10-03-pr5-followup.zh-CN.md)。本记录不等同完整产品或设备安全验收。

## 核验与计划

P0 为身份/撤销/正文边界；P1 为发布前隔离、可靠性及核心功能；P2 为生命周期、验收与发行决策。实施顺序：身份/认证 → 插件隔离/安装 → 事件可靠性/回收 → 宿主集成 → 文档及全量门禁。H10 依赖 M05–M09；M12/M13 依赖宿主权威历史端口；H06 不得以删除唯一历史副本冒充符合数据所有权要求。

| ID | 核验 | 优先级 | 模块 | 最小方案 | 验证 | 结果 |
|---|---|---|---|---|---|---|
| H01 | 确认 | P0 | 双宿主事件传输/会话 | 绑定已验证 session/device/generation，撤销与到期前拦截发送并关闭连接 | 真实 SSE/WS 撤销、到期、同账号其他设备合法连接 | 已修复；真实双宿主撤销/到期与合法控制通过 |
| H02 | 确认 | P0 | Android GatewayRuntime/TLS | 持久化批准 pin/部署身份；首次恢复请求使用原 pin；缺身份拒绝自动刷新 | 冷启动恢复、替换身份不发凭据、正常轮换 | 已修复；持久 pin、端口别名/降级拒绝、显式重新确认；自动轮换 proof 未接通 |
| H03 | 确认 | P0 | 双宿主管理/凭据 | create 拒绝已有账号；独立本地 reset 更新摘要并事务撤销 refresh | 重复创建、旧 refresh 拒绝、新密码、配对保持/全部撤销 | 补充修复；签发事务内复核验密前的密码摘要，拒绝重置前已在途的旧验密结果；独立连接重置交错回归通过 |
| H04 | 确认 | P0 | 双宿主预认证 | 有界协商缓存/过期回收/限速与受限异步密码验证 | 过期/容量/重试绑定、事件循环进度 | 补充修复；Schema/账号/协商校验后按传输 peer 和账号计数，畸形请求不消耗验密预算；全局 2 个验密槽；1000 项 LRU 是尽力限流，不是严格抗缓存抖动的全局配额 |
| H05 | 确认 | P0 | plugin-tooling | 正式打包必需外部私钥；公开 seed 只在显式 fixture 路径 | 缺 key/公开 seed 拒绝，私有 key 签名、确定性 | 已修复；正式构建外部私钥，公开 seed/空 WASM 拒绝；fixture 分离 |
| H06 | 确认（部分叙述需收窄） | P0 | 双宿主正文与宿主端口 | 复用账号 AEAD；敏感暂存加密；宿主 ACK/TTL 清正文；历史归宿主，迁移不丢数据 | SQLite 不含正文、合法读写、ACK/到期；宿主历史验收 | 部分修复；正文及暂存 AEAD、物理迁移清理；宿主权威历史迁移仍未完成 |
| H07 | 确认 | P1 | 双宿主身份配置 | 提供部署实际 SPKI 配置；禁止全零占位，配置缺失明确拒绝 | 协商返回配置身份、无配置失败、真实证书 pin | 已修复；实际 SPKI 配置/缺失 null/全零拒绝；两宿主与客户端 Schema 同步 |
| H08 | 确认 | P1 | OpenClaw 注册/验签 | 使用 runtime.version；组装 Gateway 自有 Ed25519 验证器 | 锁定 SDK 形状注册、真实 raw HTTP 正反签名 | 已修复；runtime.version 和 Gateway 自有 raw Ed25519 校验/持久 nonce |
| H09 | 确认 | P1 | OpenClaw Agent 交付 | 回复 payload 映射为稳定消息事件；可确认交付后 completed；历史走宿主端口 | 实际 deliver 回调、失败状态、重连历史 | 部分修复；真实文本回复事件和交付失败状态；流式/媒体与宿主权威历史未完成 |
| H10 | 确认（库/未启用链路） | P1 | Android/参考插件/WASM | M05/M06/M07/M08 修复后才能启用；正式构建拒绝空 WASM、验证真实 ABI；执行链接按原 Spec 装配 | Rust 编译/业务语义、包 ABI、设备 claim→原语→result | 部分修复；私钥/真实 WASM/ABI 构建门禁与隔离库修复；业务 echo 和 App 执行链未完成 |
| H11 | 确认 | P0 | Android 设置/Runtime | 独立签名 unpair；服务端确认后清设备 key、grant、临时数据 | 实际 DELETE /pairings/current；失败保持可恢复状态 | 补充修复；完整 receipt 后先冻结连接，再逐项清理；grant 清理异常不再跳过 refresh 和实际签名 key；真实 Keystore 删除仍需设备验证 |
| M01 | 发行风险，身份迁移需决策 | P2 | APK 发布 | 正式发布改为 release 签名并区分测试构建；已安装迁移需发行身份决定 | release 变体不可调试/签名；无私钥配置明确失败 | 部分修复；正式标签改为私有 Release 签名；长期发行身份与旧 Debug 迁移需发行者决定 |
| M02 | 确认 | P1 | Companion IPC | 未形成 token 验证和 tsnet pump 前拒绝通道并关闭导出入口 | 跨 UID/伪造 token 拒绝，无 fd 创建；设备验收 | 部分修复；关闭导出/服务并拒绝全部通道；可信 token 签发和 tsnet pump 未完成 |
| M03 | 确认 | P1 | Android URL | 自动补全默认 HTTPS；显式 HTTP 保留持续警告 | 私网/公网/欺骗前缀/显式 HTTP | 已修复；缺 scheme 默认 HTTPS，显式 HTTP 警告，拒绝嵌套 scheme |
| M04 | 确认 | P1 | Hermes 登录/Android 协商 | 入口统一封闭 Schema；canonical Ed25519 key；完整协商字段及交集校验 | 畸形/额外字段先于验密与写库拒绝，合法控制 | 补充修复；Hermes 封闭 Schema/canonical key；Android 接受同 major 的非负兼容 minor，并拒绝整数截断；外层 envelope 完整闭集尚未单独扩展 |
| M05 | 确认（库/未启用链路） | P1 | Kernel/私有存储 | 完整 identity 校验注册与 provider；作者绑定存储分区 | 替换作者/版本拒绝，同身份合法调用 | 已修复（库）；完整 identity 核验，作者隔离且同作者版本升级保留分区 |
| M06 | 确认（库/未启用链路） | P1 | Chicory runtime | 实例化前收紧预算；实例独立 guard；页数向下取整 | 真实 memory.grow 超限、跨实例时钟/并发隔离 | 已修复（库）；实例化前预算、floor 页数、各 invocation 独立 guard；真实 WASM 回归 |
| M07 | 确认（库/未启用链路） | P1 | ALP 验证/安装 | 随机独立 staging；精确签名文件集及摘要复制；失败清理 | 残留/并行混包、篡改文件拒绝、合法安装 | 已修复（库）；独立 staging、封闭文件集、复制时摘要核验；不可外部 copy 的验证凭据 |
| M08 | 确认（库/未启用链路） | P1 | 插件安装事务 | 可恢复事务/持久指针；目录真实同步；启动恢复 | 更新/回滚各故障阶段恢复并保留可用版本 | 补充修复（库）；rollback 与 install 均自底向上同步完整目录树；嵌套目录同步失败不得发布回滚版本；未执行真实断电验证 |
| M09 | 确认（库/未启用链路） | P1 | 私有存储 backend | 通用账号删除契约；删除后旧 handle 拒绝 | 自定义 backend 删除/失败及 handle fencing | 已修复（库）；backend 删除契约、旧 handle fencing、删除失败时禁止重开并支持重试 |
| M10 | 确认 | P1 | Hermes EventStore | 持久单调提交序列；迁移旧表；统一重放排序 | 同毫秒逆序 UUID、时钟回退、重启重放 | 已修复；持久 sequence、旧表按原插入顺序迁移，同毫秒/回拨/重启通过 |
| M11 | 确认 | P1 | Android 账号事件/游标 | 处理 session/grant 平台事件；业务应用成功后提交游标 | 撤销/授权变化立即冻结，异常不推进游标 | 补充修复；独立平台订阅不依赖打开聊天；SSE/WS 握手 401 为终态；grant 事件带目标 deviceId，匹配设备和新 revision 才清本机授权；平台与业务游标分离 |
| M12 | 确认 | P1 | 双宿主/Android 恢复 | 过期游标响应及快照重建衔接；不循环重试旧游标 | 离线过期、重建并发事件、持久游标 | 部分修复；410 清除旧游标并停止旧游标循环；一致性快照/baseline 恢复未完成 |
| M13 | 确认 | P2 | Android 加密镜像 | 按 ADR0044 接入加密持久镜像，原子事件/快照/游标 | 离线历史、tombstone、作用域删除、备份排除 | 未修复；完整加密镜像及快照/事件/游标事务装配未完成，依赖 M12 权威快照契约 |
| M14 | 确认（库/未启用链路） | P1 | 设备请求/宿主工具端口 | 完整请求 envelope；结果交付/ACK；即时高权限入口保持 fencing | 两宿主完整输入/输出、幂等、离线拒绝/在线确认 | 部分修复；完整 envelope/封闭参数、flat claim、加密结果/ACK 端口；真实工具等待/ACK 与在线高权限入口未完成 |
| M15 | 确认 | P1 | 保留/回收 | 有界真实 TTL 清理；终态输入清理；审计窗口与链锚 | 磁盘记录删除、游标过期语义、审计链保持 | 部分修复；event/request/audit/idempotency 的有界回收有效；事件中的请求参数副本采用事件 TTL，Hermes 附件主动 TTL 尚未装配，真实宿主 ACK 未接通；不能宣称所有敏感副本已清理 |
| M16 | 确认 | P2 | Android 账号生命周期 | 持久多账号资料及统一生命周期；logout 保留非秘密资料 | 切换隔离、退出/移除/unpair 四动作区别 | 补充修复；新 profile 一次写入完整 binding，兼容旧字段；缺失/不完整 binding 也能移除本机资料、refresh 与实际签名 key；系统删除仍须走 App 清理 |
| M17 | 确认 | P1 | Android 安装 UI | IO 调度与 InputStream 有界 verifier；取消/错误清理 | 大输入限额、不结束流、UI 进度/取消 | 已修复；IO 有界 InputStream 验证与独立 staging 清理，取消/进度状态；非合作 SAF 阻塞仍需设备验证 |
| L01 | 确认 | P2 | README/契约 | 文档按真实验收层级描述；历史 fake 标清；同步登出例外 | 文档与实际产物/入口核对 | 已修复；撤下 Ready 与 FakeAdapter 泛化，真实构建步骤/限制，补齐既有 D4 豁免说明 |
| L02 | 确认 | P2 | 测试/CI | 同一模拟时钟；支持 Python3.12 和双宿主全套门禁 | 根测试、Hermes tests、CI 配置/编译 | 已修复；固定时钟、Python3.12、双宿主/插件/Android 完整 CI 门禁；历史门禁偏差单列 |
| L03 | 公告存在，可利用性未证实 | P2 | 依赖锁 | 升级有公告版本，保持 Schema/canonical 行为 | audit 与合同/类型/全仓测试 | 已修复依赖公告；fast-uri 3.1.8、Vitest 4.1.11，两套 npm audit 零公告；原可利用性未证实 |

## 证据限定

- H06：原始基线的 Hermes `EventStore.append` 和 `DeviceRequestStore.enqueue` 已调用 `seal_json`；该部分不是明文落盘。基线中 Hermes `messages.text` 与 OpenClaw 对应暂存字段为明文，正文保留问题真实；当前补丁已加密这些字段，宿主权威历史归属仍未完成。
- H10/M05–M09/M14 的可执行性缺口与库边界缺陷分别记录，不能宣称当前 App 已遭插件代码执行。
- M01 的公开调试密钥是明确测试身份；仍需确认正式签名与已分发 Debug 安装迁移，不能凭空生成长期发行身份。
- L03 保留原 audit 声明与可达性限制，不将 dev dependency 公告报为生产 RCE。

## 初始实现验证记录（2026-10-02）

以下为原实现时记录的结果，保留用于溯源；不代表 2026-10-03 补充补丁已重复执行每一项。当前补丁的实际命令和结果单列在补充修复记录中。

工具链：Node 24.18.0 / npm 11.16.0、Python 3.12.14、Rust 1.88.0 + wasm32-unknown-unknown、Gradle 8.12、JDK 21、Android SDK 35。Android 使用临时环境中的已校验工具链；官方 Maven 下载遇到 429 后以本地 init script 使用镜像，未修改仓库依赖来源、未禁用 TLS 校验。正式 CI 继续使用仓库 Wrapper 和 JDK 17。

按语法/类型 → 安全触发与合法控制 → 完整回归/构建的顺序验证：

| 检查 | 命令/范围 | 结果 |
|---|---|---|
| 类型 | `npm run typecheck`、`npx tsc -p tsconfig.openclaw.json`、`npm run typecheck --prefix plugin-tooling` | 通过 |
| Node 全仓 | `npm test`（含协议、两个 Gateway 边界、legacy 及跨宿主结果核对） | 100 文件、906 测试通过 |
| Hermes 全仓 | `python -m pytest -q integrations/hermes/tests` | 257 测试通过 |
| 插件工具 | `npm test --prefix plugin-tooling` | 3 文件、46 测试通过 |
| 协议一致性 | `npm run gateway:v2:conformance` | TS/Python 各 66 向量通过，3 项跨宿主摘要核对通过 |
| Rust 编译与 ABI | `cargo build --locked --offline --target wasm32-unknown-unknown --release`；`cargo test --locked --offline`（plugins 工作区） | 编译通过；现有单元与 9 项真实 WASM ABI 检查通过；1 个文档示例按原设置忽略 |
| 正式 ALP 构建 | `build:references` 使用本次临时私有验证 key 和真实编译产物 | 三个包通过；验证 key/包未提交，不是长期发行身份 |
| Python 分发构建 | `python -m build`（integrations/hermes） | wheel / sdist 通过 |
| npm 公告 | 根与 plugin-tooling 各 `npm audit --json` | 各 0 公告；没有宣称已完成 Android/Rust 全传递依赖可达性审计 |
| Android 账户针对性 | AccountProfile、GatewaySessionRefresh、GatewayIdentityTrustStore | 退出、切换、移除、unpair、失败保留、默认端口别名与降级控制通过 |
| Android 全量 | `check :app:assembleFullDebug` | 最终代码通过；1836 项 Gradle 任务，23 个模块的 332 份单元测试报告共 2318 次变体测试执行，0 失败/错误/跳过；包括 App Full/Play × Debug/Release；FullDebug APK 构建通过 |
| 发行签名 | 实际包装任务门禁负向验证；正常 `:app:assembleFullRelease`；`apksigner verify` / `aapt dump badging` | 缺配置和公开 debug 身份均按预期拒绝；正常私有验证构建通过（611 项任务）；两个 APK 签名有效，Release 非 Debug 且证书不同于公开 debug 身份 |
| 额外旧源码门禁 | `python3 -m unittest discover -s apps/android/tools -p 'test_*.py'` | 88 项中 79 通过、9 失败；原始基线同样 9 失败，集合一致 |
| 旧依赖准入锁 | `npm run mvp:lock:check` | 7 项 evidence 已过期；未替代控制方重新批准或伪造有效期 |
| 补丁完整性 | `git diff --check` | 通过 |

签名负向检查通过本地 init script 隔离执行真实 `packageFullRelease` 的首个门禁 action，没有修改仓库脚本或声称运行两次完整负向发行构建。正向发行构建使用原任务链，无隔离脚本。两个 APK 的 applicationId 均为 `com.openandroidintelligence.mobile`；FullDebug 为 debuggable，证书 SHA-256 为既有 `37d9445f…624ce33`；FullRelease 为不可调试，临时验证证书为 `79a45d3d…7f4cc7`。Release APK SHA-256：`adf1b9894b8441aebd81a14f352c44ffa1a713d0e8929675e35f9c1f161ac191`。这些密钥和产物未提交，临时证书不是正式发行身份。

安全触发不再复现的直接证据：双宿主真实已建立事件连接在会话撤销、解除配对、账号删除或到期后停止发送新事件，同账号另一个未撤销设备继续接收；真实 Ed25519 raw HTTP 正确签名可读，重复 nonce、篡改与跨账号失败；密码重置后旧 refresh 家族不能恢复；SQLite/WAL 的正文标记被加密迁移清理且消息仍可解密，读锁导致清理失败时不服务账号并可重试；篡改/多余 staging 文件不能安装；真实 WASM 的内存增长和嵌套调用不能绕过调用预算；删除账号后旧存储 handle 失效，backend 删除失败时新 handle 同样拒绝，重试清理后正常打开。

普通合法路径分别由新回归和相邻既有测试覆盖：两个合法账号/设备、密码登录与 refresh 轮换、明示 HTTP、同作者插件升级、正常安装/回滚、正常 SSE/WS 重放与正常设备 claim/result。游标遇到 reducer/持久化失败或取消会保留上次完成位置，允许至少一次重放。

独立候选审查按安全修复技能只发起一次。工具返回内容拦截，未提供独立结论；后续由主任务补做直接调用者、输入别名、失败分支、平台/backend 和兼容性复核。该限制如实保留，不能把这一步写为「独立审查通过」。本轮 `adb devices` 无设备，未执行 instrumentation 或真实设备/真实宿主整体 E2E。

## 未完成项与原因

- **H06/H09**：加密解决明文暂存与迁移风险，文本回复也确实交付为事件；OpenClaw 的权威历史端口尚未接通，Hermes 现有 Gateway 数据仍含唯一历史副本。没有删除唯一副本来伪装零保留。需要先提供真实宿主 ConversationPort、ACK 与历史迁移验收，随后才能清除长期正文并补齐流式/媒体回复。
- **H10**：执行与原语的 WASM 调用契约还缺失。当前受保护 ABI 只定义 log/random/time；参考插件未实现具体业务 Schema 和内核查询，App 的 runtime/provider/limit 装配仍为空。新增私有的业务 ABI 会构成契约扩展，不能以任意原语调用或 echo 输出代替可执行设备业务。先明确版本化原语 ABI、具体 Schema 与作者信任输入，再接通安装→注册→授权→claim→原语→result；本轮保留不可启用状态。
- **M02**：当前没有 tsnet pump、可信 token 交换和独立 APK IPC 交付验证。已关闭不安全入口，没有把未经鉴权的 pipe 当成真实通道。需要完整实现并在跨 UID 设备上验收后才能重新导出服务。
- **M12/M13**：协议尚缺权威快照与 baseline 的一致性边界，双宿主也未实现相应历史端口。已停止旧游标无限循环并持久保存合法游标；完整快照重建、加密镜像与事件/游标原子事务没有实现。需要明确快照 revision/baseline、重建期间事件交接、tombstone 与分页，再按 ADR 0044 完成镜像装配。这是确认的功能缺口，未宣称完成。
- **M14**：请求/claim/result 的封装、参数验证、加密结果与内部 ACK 端口已修复；这些接口还没有连接真实宿主 ToolExecutionPort。即时高权限请求继续拒绝过期/离线执行，没有虚构线上确认与等待结果。须与 H10 一起实现宿主等待/交付/ACK 及独立在线入口。
- **M01**：代码已经阻止公开 debug 身份产生正式 release。仓库所有者仍须配置长期发行 keystore；不同签名不能覆盖旧 Debug 安装并继承 UID/Keystore，需要发行者决定安装迁移与辅助 APK 签名策略。未生成或发布长期发行密钥，未卸载任何用户安装。

## 误报、过时信息与验证边界

- **H06 的部分叙述过宽**：Hermes 的事件、请求参数和幂等结果原本已采用 AEAD；真实缺陷是消息正文与保留/归属，修复没有重复引入另一套 Hermes 加密实现。
- **L01 登出豁免**：不是擅自放松认证。既有 Wave 0 D4 明确允许失去设备私钥但仍持有有效 bearer 的设备终止自身会话；仅这个无资源创建的路由豁免签名，仍核验完整会话绑定。本轮将已接受决策同步到协议正文，保留解除配对的强签名。
- **H10/M05–M09/M14**：库缺陷或功能缺失真实；App 当前不可执行的链路不被报告为已可利用的插件代码执行。
- **L03**：原公告版本确有风险，但未证明 fast-uri 输入能到达本项目生产 SSRF sink，也未证明开发 mock server 暴露在生产。修复依赖公告，不把原报告的公告严重度当成本项目漏洞严重度。
- **旧源码门禁**：9 项失败涉及旧 MVP collector/Manifest/Bridge 装配和源码扫描范围；部分装配要求与 accepted 模块化架构的 collector 不进入 App 原则冲突，其余也与既有实现不一致。在独立解包的原始提交上复现相同失败集合。模块仍在 Gradle 注册，不能把缺少 App 编译依赖误写为模块未注册；也未将全部 9 项一概判为误报。保留断言，没有为绿灯重新引入旧采集器或放宽架构边界。建议按当前 accepted Spec 单独逐项同步这些历史测试。
- **旧依赖锁**：7 条批准证据在当前日期已过期。这需要控制方重新验证和批准，不能仅把日期延长。根协议测试、双宿主、插件工具链和 Android 构建的实际运行结果分别列出。

剩余技术风险：尚无设备证据证明 Android Keystore 的实际清除、跨 UID Companion、真实断电恢复、非合作 SAF Provider 的取消和真实 TLS 证书轮换。正常 v2 审计链可保留窗口清理；损坏或 legacy 链不会被重写成「完整」，需要单独迁移或保留策略。部署时两宿主与 Android 必须同步升级核心 Schema 摘要，并配置真实 SPKI 和外部账号 AEAD 主密钥；已有数据库升级须保管原密钥、备份并释放读锁，留足物理清理空间。README 已补齐这些要求。
