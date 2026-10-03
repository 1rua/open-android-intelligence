# PR #5 复核后的补充修复

复核对象为 `280403a67b64d8f6dee135ff9674503e1b6a9a71`，原 PR 基线为 `f2e28dff60f21293efcbf2ac38290fc72b5060d3`。本次补充提交修复复核中已确认的 S01/S02、C01–C05 和 O01，并收窄原修复记录的完成声明。没有把尚未装配的产品功能或未验证候选写成已完成修复。

## 改动与回归边界

| 复核编号 | 原缺口 | 补充修复及控制 |
|---|---|---|
| S01 / H04 | 双宿主的全局密码计数先于 Schema 校验，匿名畸形请求可耗尽所有账号的登录预算 | 校验 Schema、账号与协商绑定后才准入；按宿主提供的传输 peer 与账号计数，最多两项并行验密；真实 HTTP 覆盖畸形请求、同 peer 不同账号、伪造 X-Forwarded-For、另一个来源的合法登录 |
| S02 / H03 | Hermes 已在途的旧密码验证可在 reset 后签发新的 refresh | 验密前快照本地摘要，签发的同一写事务内再次比较；OpenClaw 同步/异步及自定义 verifier 共用该栅栏；真实 Hermes HTTP 和独立数据库连接交错覆盖拒绝旧结果、不写 session/key、新密码与 refresh 正常 |
| C01 / M11 | 另一设备的 grant 事件会清除当前设备本机授权 | 新发事件携带目标 deviceId；客户端仅处理匹配设备的新 revision；回归覆盖跨设备、正常失效、重复 revision 与游标隔离 |
| C02 / M11 | 正文安全栅栏先关闭撤销连接，客户端收不到终态事件；未打开聊天时没有平台订阅 | SSE/WS 握手 401 抛出终态异常并停止自动重连；会话范围内始终运行独立平台 SSE，收到拒绝即取消控制器并解绑本机授权；平台、业务订阅使用独立持久游标 |
| C03 / M04 | Android 严格要求 minor=1，拒绝同 major 的兼容 minor | 保留同 major、非负 minor 及协商能力交集；整数必须位于 Int 范围，避免 long 转换截断；覆盖兼容 minor 和非法 major/minor |
| C04 / M16 | 部分保存的 profile 无完整 binding 时无法移除 | 新 profile 在 AccountManager 的同一次创建中写入完整 binding JSON；读取兼容旧字段；本机移除不再依赖完整 binding，可清理已有 refresh、签名 key 与 profile 游标 |
| C05 / H11 | unpair 后 grant 清理抛错会跳过所有秘密清理 | 收到完整远端 receipt 后先冻结连接，再逐项尝试清理并汇总失败；故障回归确认 refresh 和实际签名 key 仍删除，未获远端确认时仍保留恢复路径 |
| O01 / M08 | rollback 只同步 staging 根目录，遗漏嵌套目录项 | 对完整目录树自底向上同步后才能提交回滚；注入嵌套目录同步失败时保留当前可用版本，随后正常回滚仍保留嵌套资源 |

## 实际验证

本轮使用仓库锁定的 Node 24.18.0/npm 11.16.0、Python 3.12、JDK 17、Gradle 8.12 和 Android SDK 35。JDK 使用环境现有 CA 信任库和规定代理，未关闭 TLS 验证。通过本地 JVM 属性让 Robolectric 从项目已有的 Aliyun Maven 镜像下载 SDK；未修改仓库的依赖版本或来源配置。工具链、日志与构建产物未提交。

| 检查 | 本轮结果 |
|---|---|
| Hermes 全量 `python -m pytest -q integrations/hermes/tests` | 259 项通过 |
| Node 全量 `npm test` | 101 文件、909 项通过；使用正常 umask 022；真实 scrypt 多次验密控制单独设置 30 秒超时 |
| 类型 | 根 TypeScript、OpenClaw tsconfig 和 plugin-tooling 均通过 |
| 协议 `npm run gateway:v2:conformance` | TypeScript/Python 各 66/66，通过 3 项跨宿主核对 |
| Android Kotlin 编译 | `:gateway-client:compileDebugKotlin :plugin-package:compileDebugKotlin :app:compileFullDebugKotlin` 通过 |
| Android 针对性回归 | NegotiationClient、GatewayEventStream、PluginInstaller、AndroidAccountProfileStore、GatewaySessionRefresh 共 56 项通过 |
| Android 模块回归 | gateway-client 183 项、plugin-package 50 项通过，无失败/错误/跳过 |
| 插件工具 `npm test --prefix plugin-tooling` | 3 文件、46 项通过 |
| Android App 回归 | `:app:testFullDebugUnitTest` 23 份报告、112 项通过，无失败/错误/跳过；新增生命周期回归已实际执行 |
| Android 全量及 APK | `check :app:assembleFullDebug` 通过；1836 项 Gradle 任务；23 个模块、332 份报告、2350 次变体测试执行，零失败/错误/跳过；FullDebug APK 构建通过 |

完整 Android 命令采用增量构建，复用了本轮已经完成且输入未变的任务（688 项执行、1148 项 up-to-date）；测试执行数包含 Debug/Release 和 App Full/Play 变体，不代表 2350 个不同测试。针对性回归随后由全量任务重新覆盖。

按安全修复流程执行了一次独立、只读的边界调查和一次独立候选复核。候选复核发现一处 Kotlin 补丁落点错误，已修正并通过随后实际编译。复核另外执行了 Hermes 的空摘要/自定义 verifier、None→有摘要、reset 交错、两槽忙重试、1100 个来源输入下容量不超过 1000、61 秒过期和未知账号不建库控制；未确认其他存活绕过。独立复核没有运行 Node/Gradle 包级测试，其结果不代替上表主任务的实际运行。

## 兼容性与完成范围

- 核心 Schema 摘要更新为 `sha256:99309b87dec79f9c737b7d875a6e949ceffb6bb18b468d7ce98ca37d77dd3d50`，Android 常量、OpenClaw manifest、共享 fixture 摘要与两宿主运行时已同步。部署时应同步更新 Android 和 Gateway。
- 旧数据库中的 `pairing.grant.changed` 允许缺少 deviceId 以供重放；客户端将此类旧通知视为无目标的提示，不用它清理本机授权。所有新通知均带目标设备。
- 密码预算的 1000 项 LRU 是有界、尽力的来源限流；大量来源造成缓存抖动时可以绕过单来源次数积累。昂贵验密仍有两个任务的硬并行上限。传输 peer 不读取请求正文或 X-Forwarded-For；反向代理后的共享地址仍需部署侧可信来源策略。
- 摘要栅栏依赖账号数据库写事务与 reset 的串行化，不承诺同一个 Hermes AccountStore 连接可跨线程重入；生产 HTTP 与复现均使用独立账号连接。独立自定义 verifier 不替代本地凭据 reset 的版本边界。
- M15 的完成范围已收窄：指定 store 的回收有效，事件参数副本仍按事件 TTL 保留，Hermes 附件主动 TTL 与真实宿主 ACK 仍未装配。本轮未改写未统一的数据保留策略。
- H06/H09/H10/M01/M02/M12/M13/M14 的权威历史、业务执行链、发行身份迁移、Companion 通道及快照/镜像功能仍维持原报告的部分或未完成状态。
- 未执行真实设备 Keystore 清除、跨 UID IPC、证书轮换、非合作 SAF 取消、真实断电恢复或真实宿主完整 E2E；单元测试和 APK 构建不代表这些设备验收通过。未重新尝试先前被工具拦截的恶意 WASM 等候选。

原始实现的验收声明和本次实际检查分开保存，见 [原 31 项修复记录](2026-10-02-review-remediation.zh-CN.md)。
