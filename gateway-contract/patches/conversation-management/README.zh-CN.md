# 原生会话管理配套补丁

本目录归档原始 Gateway 插件基线的回补工具和主仓审查 patch；冻结基线记录在本目录的 [`base-pins.json`](base-pins.json)：Hermes `3b113cadc0526f0475233e7c41790d4a8fdd9fc7`、OpenClaw `432e7c0545ecfe36af25fd4c451d777d434ae8fc`。补丁包含账号范围历史分页、共享文件夹/排序、原生归档/删除、删除屏障、真实宿主测试以及 OpenClaw 编译产物。

主仓 CI 已直接检出同步后的新插件提交。新安装请使用主仓 README 中记录的插件版本，不要对新插件重复应用这些归档 patch。同步决策和新提交记录见 [Gateway 插件同步评审](../../../docs/reviews/2026-10-19-gateway-plugin-sync.zh-CN.md)。

先在 `base-pins.json` 记录的两个固定提交上分别创建全新、干净 checkout，再检查：

```bash
python3 gateway-contract/patches/conversation-management/apply-gateway-patches.py \
  --openclaw-root /path/to/openclaw-gateway-plugin \
  --hermes-root /path/to/hermes-gateway-plugin
```

仅在需要回补这些归档 patch 的原始基线 checkout 上使用此 helper。加 `--apply` 后才会修改文件。helper 在写入前检查两个冻结基线 HEAD、干净工作树、两个 patch 的 `git apply --check`，并确认主仓契约与实际 HEAD 一致。它随后应用 patch、复制主仓的契约输入、将两个 `contract-pin.json` 写为当前主仓真实完整 HEAD，并更新 OpenClaw 静态清单的核心摘要。Hermes 的本地契约副本携带同一 `.pinned-revision`；OpenClaw 源码与 runtime 的 JSON Schema 使用主仓原始字节。

本地编写期间可显式加 `--allow-working-tree-contract` 来测试尚未提交的契约；该模式仍记录真实 HEAD，并明确输出测试模式，不能声称生产 pin 校验通过。提交主仓后应在另一组全新固定 checkout 上不带此参数重跑 helper、pin 校验和全部测试。重复应用、错误 HEAD、脏插件工作树与契约符号链接均拒绝。

```bash
python3 -m unittest discover -s gateway-contract/patches/conversation-management -p 'test_*.py'
OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$PWD/gateway-contract" \
  npm --prefix /path/to/openclaw-gateway-plugin run contract:check
```

应用补丁后 runtime 相对外部仓基线必然有改动，因此 CI 以补丁后的文件清单与 SHA-256 为构建基准，构建两次均要求新增、删除与内容字节完全一致。该检查替代旧的 `git diff --exit-code -- runtime`，仍能发现未更新编译产物与不确定构建。

会话管理只操作该 Gateway 账号已有的可信原生绑定，不把其它本地 Agent 聊天导入手机。Hermes 删除覆盖压缩链及原生展示 KV；OpenClaw 删除使用宿主原生生命周期 API，宿主自行决定其删除后 transcript 文件的保留策略。已有持久事件与短期传输数据遵守原有保留周期；删除屏障阻止其重新成为可读会话。
