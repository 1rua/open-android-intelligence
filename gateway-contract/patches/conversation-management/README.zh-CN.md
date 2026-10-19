# 原生会话管理配套补丁

本目录以主仓审查的 patch 交付 Hermes 与 OpenClaw 配套实现；不修改两个外部仓的发布分支。固定基线分别由 README 的 Hermes 安装命令（`3b113cadc0526f0475233e7c41790d4a8fdd9fc7`）与 `openclaw-plugin-pin.json`（`432e7c0545ecfe36af25fd4c451d777d434ae8fc`）给出。补丁包含账号范围历史分页、共享文件夹/排序、原生归档/删除、删除屏障、真实宿主测试以及 OpenClaw 编译产物。

先在两个固定提交的全新、干净 checkout 上检查：

```bash
python3 gateway-contract/patches/conversation-management/apply-gateway-patches.py \
  --openclaw-root /path/to/openclaw-gateway-plugin \
  --hermes-root /path/to/hermes-gateway-plugin
```

加 `--apply` 后才会修改文件。helper 在写入前检查两个 HEAD、干净工作树、两个 patch 的 `git apply --check`，并确认主仓契约与实际 HEAD 一致。它随后应用 patch、复制主仓的契约输入、将两个 `contract-pin.json` 写为当前主仓真实完整 HEAD，并更新 OpenClaw 静态清单的核心摘要。Hermes 的本地契约副本携带同一 `.pinned-revision`；OpenClaw 源码与 runtime 的 JSON Schema 使用主仓原始字节。

本地编写期间可显式加 `--allow-working-tree-contract` 来测试尚未提交的契约；该模式仍记录真实 HEAD，并明确输出测试模式，不能声称生产 pin 校验通过。提交主仓后应在另一组全新固定 checkout 上不带此参数重跑 helper、pin 校验和全部测试。重复应用、错误 HEAD、脏插件工作树与契约符号链接均拒绝。

```bash
python3 -m unittest discover -s gateway-contract/patches/conversation-management -p 'test_*.py'
OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$PWD/gateway-contract" \
  npm --prefix /path/to/openclaw-gateway-plugin run contract:check
```

应用补丁后 runtime 相对外部仓基线必然有改动，因此 CI 以补丁后的文件清单与 SHA-256 为构建基准，构建两次均要求新增、删除与内容字节完全一致。该检查替代旧的 `git diff --exit-code -- runtime`，仍能发现未更新编译产物与不确定构建。

会话管理只操作该 Gateway 账号已有的可信原生绑定，不把其它本地 Agent 聊天导入手机。Hermes 删除覆盖压缩链及原生展示 KV；OpenClaw 删除使用宿主原生生命周期 API，宿主自行决定其删除后 transcript 文件的保留策略。已有持久事件与短期传输数据遵守原有保留周期；删除屏障阻止其重新成为可读会话。
