# Gateway refresh 结果恢复补丁

本目录收纳 OpenClaw 与 Hermes Gateway 的 refresh 结果安全重放补丁。补丁不将服务端实现内嵌到主仓，也不改变 JSON body 或 Schema；协议扩展与行为约束见 [`docs/contracts/gateway-protocol-v2.md` §5.4](../../../docs/contracts/gateway-protocol-v2.md#54-刷新与设备密钥会话)。

`openclaw.patch` 和 `hermes.patch` 是交给各自 Gateway 维护仓审查的补丁文件。它们需要由 Gateway 维护仓合并并部署后才会生效；在维护仓合并、发布和实际部署前，客户端不能假设 Gateway 支持安全重放。本目录不会记录或虚构未来的服务端提交 SHA。

## 检查与应用

先把两个服务端仓库独立检出到本机。OpenClaw 必须位于主仓 `openclaw-plugin-pin.json` 指定的 revision；Hermes 必须位于主仓 `README.md` 安装命令中的固定 revision。两个 checkout 都必须是干净的 Git worktree 根目录。

默认运行只执行 pin、工作区干净状态和 `git apply --check` 检查，不修改文件：

```bash
python3 gateway-contract/patches/session-recovery/apply-gateway-patches.py \
  --openclaw-root /workspace/gateway-review-openclaw \
  --hermes-root /workspace/gateway-review-hermes
```

检查通过后，显式添加 `--apply` 才会应用两个补丁：

```bash
python3 gateway-contract/patches/session-recovery/apply-gateway-patches.py \
  --openclaw-root /workspace/gateway-review-openclaw \
  --hermes-root /workspace/gateway-review-hermes \
  --apply
```

脚本先验证两个 checkout，再对两个补丁执行 `git apply --check`，之后才开始应用。任何 pin 不匹配、dirty/untracked 文件、缺失补丁或 check 失败都会终止；检查与应用阶段均不执行 shell 命令、不访问网络。应用后服务端工作区会出现未提交改动，提交、评审、发布和部署由各自 Gateway 维护流程完成。

完成服务端合并和部署后，Android 客户端只有在协商响应明确返回 `X-Open-Android-Intelligence-Refresh-Replay: v1` 时，才能依赖幂等刷新重放。未协商该能力的旧 Gateway 仍按现有单次轮换语义工作；响应结果未知时，客户端不得自动以新操作 ID 重发旧 refresh credential。

## 回归验证

在主仓验证补丁工具的 pin、脏工作区、检查与应用行为：

```bash
python3 -m unittest discover -s gateway-contract/patches/session-recovery -p 'test_*.py'
```

应用补丁后，在 OpenClaw checkout 安装其固定版本依赖并运行：

```bash
npm run typecheck
npx vitest run test/refresh-replay.test.ts test/session-service.test.ts \
  test/session-logout.test.ts test/backup-and-rotation.test.ts \
  test/grant-revision-and-session-revoked-events.test.ts
```

在 Hermes checkout 安装其 Python 依赖与 pytest，将契约路径指向本主仓后运行：

```bash
OPEN_ANDROID_GATEWAY_CONTRACT_ROOT=/workspace/open-android-intelligence/gateway-contract \
  python3 -m pytest -q tests/test_refresh_replay.py tests/test_backup_and_rotation.py \
  tests/test_event_stream_revocation.py tests/test_pairing_revocation.py
```

两端新增测试均覆盖响应丢失后重放、进程重启、不同操作复用旧凭据、显式撤销、绑定不匹配、加密结果损坏、事务回滚以及重复或非法协商 header。重放保留同一 Token 与会话，同时返回当前设备授权修订；不会使用旧结果恢复已撤销的权限。
