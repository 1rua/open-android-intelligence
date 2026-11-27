# Bridge runtime 生产化实施计划（2026-08-17）

按可独立验证的垂直切片推进。每片先写失败测试，再实现，最后运行聚焦
命令；不把 fake 通过结果提升为生产证据。

1. **合同与 Node SQLite adapter**
   - 更新 SQLite driver marker 和连接断言。
   - 建立 schema v1、闭合 JSON 编码、transaction、migration、recover、
     backup、restore。
   - 聚焦验证：transaction 原子性、rollback、namespace/value guard、
     schema publication、backup digest、restore isolation。

2. **同库 lease coordinator**
   - 实现 acquire/renew/transact/release 和单调 fencing token。
   - 增加同进程交错与多连接竞争/fencing 测试。
   - 接入 fenced production composition。

3. **只读 secret store 与 Ed25519 verifier**
   - 定义 signed ticket envelope。
   - 实现文件权限/类型/key 算法检查和签名验证。
   - 验证错误 ticket、过期 ticket、篡改 ticket 和可接受 ticket。

4. **真实 backup/restore drill CLI**
   - 用真实 adapter 写代表性 namespaces。
   - 输出 JSON evidence 并作为 npm script 可重复执行。

5. **认证 tsnet Go sidecar**
   - 建立 Go module、locked dependency、WhoIs fingerprint header 和
     Unix socket proxy。
   - 不开启 Funnel/public socket；无法连接 Tailnet 时 fail closed。

6. **Node production composition/main**
   - 组合 SQLite、lease、verifier、health、Unix socket listener。
   - readiness 前执行 recover/migration/lease 获取。
   - 只接受 sidecar 通道的认证 metadata。

7. **Docker/systemd 模板**
   - 修复 build context、multi-stage build、volumes、users、permissions。
   - 提供 staging install/verify 脚本。
   - 在无 Docker daemon 时保留明确 BLOCKED 输出。

8. **MVP lock/evidence**
   - 更新 `MVP-DEP-BRIDGE` immutable version 与真实 verify command。
   - 重算 row hash。
   - 记录本地可复现命令、产物 digest 与环境 blocker。
   - 运行 root Vitest（排除 `.worktrees/**`）、typecheck、lock check、
     focused Go/systemd/Docker 验证。
