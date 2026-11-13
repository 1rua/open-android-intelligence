# Bridge runtime 生产部署设计（2026-08-17）

> [!WARNING]
> 本文已于 2026-08-24 被 [Open Android Intelligence 模块化插件架构规格](./2026-08-24-modular-plugin-architecture.md) 取代，仅保留历史背景。独立 Docker/systemd Bridge 不再是生产目标；Gateway 直接安装为 Hermes 或 OpenClaw 宿主插件。

## 结论

首版采用单主机生产垂直切片：Node 24 内置 SQLite、同库多进程
lease/fencing、只读文件系统 pairing verifier、锁定 Tailscale tsnet Go
sidecar、Docker/systemd 部署模板，以及真实 SQLite backup/restore drill。
该设计只解除 `MVP-DEP-BRIDGE` 的 Bridge 部署证据缺口；它不宣称跨主机
failover、公网 ingress、云 KMS 或完整 Android E2E。

## 1. 运行拓扑与信任边界

- `bridge-runtime` 必须运行于 Node `24.18.0`，使用内置 `node:sqlite`
  和 SQLite `3.53.1`，不引入第三方 SQLite npm 包。
- Go ingress sidecar 从锁定 Tailscale `v1.98.10` 模块构建，只负责
  userspace Tailnet listener、`WhoIs` 认证和 Unix socket 反向代理。
- Node runtime 只监听本机 Unix socket；sidecar 与 runtime 使用独立
  service user 和 socket 权限隔离。
- 业务 SQLite 文件位于 `/var/lib/open-android-intelligence-bridge/bridge.sqlite`，
  只归 Bridge service user 写入。
- pairing 验证公钥位于 `/var/lib/open-android-intelligence-bridge/secrets/`，由部署
  层只读挂载。Bridge 不能读取或接收签发私钥。
- 不发布 Docker host port，不声明 systemd `ListenStream`，不启用
  Funnel/public wildcard。

## 2. SQLite adapter

`bridge-contract` 的 SQLite port 从“external driver required”升级为
“locked node-sqlite adapter”：

- port：`open-android-intelligence.bridge-sqlite-adapter.v1`
- driver：`node:sqlite@24.18.0/sqlite@3.53.1`
- schema version：从 0 到 1，使用不可变、连续迁移
- 表：
  - `bridge_meta(key PRIMARY KEY, value NOT NULL)`：schema version
  - `bridge_entries(namespace, key, value, PRIMARY KEY(namespace,key))`
  - `bridge_leases(scope PRIMARY KEY, owner_id, fencing_token,
    expires_at_ms)`
  - `bridge_transaction_log(id, scope, started_at_ms, committed_at_ms)`
- `transact()` 使用 `BEGIN IMMEDIATE`、prepared statements 和
  `COMMIT/ROLLBACK`；namespace 使用合同白名单，value 使用闭合 JSON
  编码并显式保留 bigint。
- `runMigration()` 在同一 SQLite transaction 中执行 schema work 并发布
  目标版本。
- `backup()` 使用 Node SQLite backup API，输出 SHA-256 digest、路径、
  schema version 和时间。
- `restore()` 先恢复到隔离临时文件、验证 digest/schema/integrity，再
  原子替换当前数据库；失败时回滚旧数据库。
- `recover()` 执行 SQLite integrity/quick/foreign-key 检查，依赖 SQLite
  journal/WAL recovery，不静默丢弃业务数据。

## 3. Lease/fencing coordinator

lease 与业务数据同库，使用 `BEGIN IMMEDIATE` 保证多进程竞争串行化：

- 每个scope 的 lease 行携带单调递增 `fencing_token`。
- `acquire()` 只能在当前 lease 过期、不存在或同 owner 时取得；
  换 owner 会生成新 token。
- `renew()` 必须匹配 owner 与 token。
- `transact(lease, scope, work)` 在同一数据库 transaction 中先验证
  lease 未过期且 token 仍是当前值，再执行 `work`；过期后被其他进程
  接管时旧 worker 被 `BRIDGE_LEASE_FENCED` 拒绝。
- `release()` 只释放精确 owner/token 匹配的 lease。
- 进程内额外使用异步队列，避免一个 Node process 在同一连接上交错打开
  多个 transaction；跨进程正确性由 SQLite write lock 保证。

## 4. Pairing verifier 与 secret store

首版 secret store 是权限受控的本地只读文件卷，不引入网络 KMS：

- 固定文件：`pairing-ticket-public.pem`
- 算法：Ed25519
- ticket wire 格式：闭合 JSON envelope，包含 key id、ticket payload、
  base64url signature 和 domain separation 字符串。
- verifier 打开时校验：
  - 文件是普通文件而非 symlink；
  - 公钥是 Ed25519；
  - 文件未被 group/other 写入；
  - 部署模板必须以 read-only 方式挂载。
- verifier 只返回已签名且未篡改的 `PairingTicket`；单次消费、generation
  与 binding 一致性继续由 durable pairing repository 在同一事务中裁决。
- 私钥、OAuth secret 和 tsnet auth key 都不属于 Bridge runtime 状态库。

## 5. 认证 tsnet ingress

Go sidecar：

1. 使用锁定 `tailscale.com v1.98.10` `tsnet.Server` 连接私有 Tailnet。
2. 只监听配置的 tailnet 端口，不使用 Funnel。
3. 每个请求调用 LocalClient `WhoIs(remote address)`。
4. 将 peer node key 的 SHA-256 fingerprint 作为不可伪造的 sidecar
   header 传入 Node Unix socket。
5. Node runtime 不信任任何外部传入的 fingerprint header；只有 Unix
   socket 上由 sidecar 写入的值才被接受。
6. runtime 将 fingerprint 与已持久化 pairing binding 比对，再进入既有
   pairing generation、connection generation 和 replay 检查。

健康路由只提供 `/health/live` 和 `/health/ready`；readiness 包含
SQLite、lease、pairing verifier 和 tsnet sidecar 检查，不泄露异常文本
或 secret。

## 6. 部署模板

### Docker

- multi-stage Node image 构建 strict TypeScript `dist`。
- multi-stage Go image 构建 tsnet sidecar。
- runtime 镜像不包含 dev dependencies、私钥或构建工具。
- Compose 使用共享 private network namespace。
- state volume 与 secret volume 分离，secret 为 read-only。
- healthcheck 只访问本机 private namespace。
- 镜像构建脚本校验 Node/SQLite、Go、Tailscale module 与输出布局。

### systemd

- `open-android-intelligence-bridge.service` 和 `open-android-intelligence-ingress.service` 分别运行
  Node 与 Go sidecar。
- Node 服务只绑定 Unix socket。
- Go sidecar 持有 tsnet state。
- `ProtectSystem=strict`、`PrivateTmp=true`、`NoNewPrivileges=true`。
- 只为各自 state 目录开放必要的 `ReadWritePaths`。
- 安装脚本可生成 staging 布局并用 `systemd-analyze verify` 校验。

## 7. Backup/restore drill

真实 drill 不使用 fake SQLite adapter：

1. 创建源 SQLite DB，运行 schema migration。
2. 写入代表性 pairing、notification、subscription、operation/replay、
   Task 9 namespace entries。
3. 生成 backup artifact 并记录 SHA-256。
4. 打开完全隔离的 restore DB。
5. 恢复 backup，执行 recover 和 migration/schema check。
6. 逐 namespace canonical 比较 source/restore entries。
7. 输出机器可读 JSON evidence；任一 mismatch 或 digest 校验失败退出
   非零。
8. drill 结束删除临时源/恢复/backup 文件，不污染生产 state。

## 8. 验收与证据

`MVP-DEP-BRIDGE.verify_command` 更新为可执行的聚合命令，并至少覆盖：

1. Node/SQLite 版本锁定断言。
2. bridge-runtime Vitest 全量测试。
3. strict TypeScript/build。
4. 真实 SQLite adapter transaction/rollback/concurrency/crash seam 测试。
5. lease fencing 测试。
6. Ed25519 verifier 测试。
7. 真实 backup/restore/recover drill。
8. Docker Compose 配置/构建模板校验（本机无 Docker daemon 时输出
   explicit blocker，不得伪造 PASS）。
9. systemd unit `systemd-analyze verify`。

任何 source-seam-only fake、空测试任务或缺 daemon 的环境观察都不能写成
生产 PASS。

## 9. 非目标

- 跨主机 lease/failover。
- 公网 ingress 或 Funnel。
- 远程 KMS/HSM。
- 新的第二套 App/Bridge 自动化 DSL。
- 将 Android P0t、设备 E2E 或真实 Tailnet enrollment 证据混入 Bridge
  storage 验收。
