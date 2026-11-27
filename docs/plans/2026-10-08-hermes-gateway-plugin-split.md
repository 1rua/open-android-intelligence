# Hermes 网关插件拆分为独立仓库

- 日期：2026-10-08
- 插件仓：`1rua/hermes-gateway-plugin`
- 影响面：Agent 宿主接入方式、契约分发、CI 门禁、跨仓发版流程

## 背景

Hermes 网关插件此前以 `integrations/hermes/` 的形式留在应用主仓内，实际部署依靠手工在
`~/.hermes/plugins/<name>/` 建目录、写 `plugin.yaml`，再把包目录符号链接进仓库。用 Hermes
v0.21.3 的官方校验命令复现，该部署是不合格的：

```
"capability probe": { "ok": false, "detail": "import failed: No module named ..." }
"security scan":     { "ok": false, "detail": "dangerous: symlink_escape" }
```

两条都致命：加载失败，而即便路径修好，跳出插件目录的符号链接也会被判为 dangerous。同时该方式
无法 `hermes plugins update`（只对 git checkout 生效），仓库一移动就断——本机就实际发生过一次。

Hermes 官方自 2026-06 起不再接受新的第三方产品插件进其 in-tree 树，要求作为独立插件仓库发布
（`hermes-agent/plugins/AGENTS.md`）。本次拆仓同时解决合规与可用性。

## 决策

### 1. 拆为独立仓库，主仓不留副本

主仓删除 `integrations/hermes/`，一律通过已安装包引用。保留两份源码必然漂移，与项目
"不新旧两套架构并存"的铁律冲突。

代价是跨仓协同：插件与本仓不再原子提交。第 4 条用契约 pin 与门禁补上。

### 2. 契约不入库，按锁定提交获取

`core.py` 的契约定位依赖 `gateway-contract/schemas/` 与 `vectors/` 同时存在，缺失即拒绝服务。
三种做法里选了"按锁定提交获取"：

| 做法 | 否决理由 |
|---|---|
| 把契约复制进插件仓 | 出现第二份人工维护的副本，与手机端协商的契约可能漂移 |
| 契约独立成第三个包 | 单一真源最彻底，但改造面最大，且未解决"谁发布它"的问题 |
| **按 pin 从本仓获取（采用）** | 单一真源仍是本仓，无副本可漂移 |

`contract-pin.json` 锁定仓库地址与 40 位提交。插件加载时用
`git init` + `sparse-checkout` + `fetch --depth 1 <sha>` 把契约取到插件目录内——稀疏检出
是必需的：源仓是整个 Android 应用，全量检出会把无关的构建产物写进插件目录。

落位在插件目录内，使 `core.py` 既有的向上搜索天然命中，无需改动核心定位逻辑；同时支持
`OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 供离线与内网部署直接指定。

### 3. 契约获取失败时降级而非崩

拉取失败返回可操作的中文原因，插件照常加载，宿主保持可启动，`hermes open-android-intelligence
contract status` 如实显示"未就绪"及原因。真正的拒绝发生在网关服务侧（算不出摘要就无法协商），
不会伪装成"就绪"。

### 4. 版本一致性 fail-closed

原先 `HERMES_PLUGIN_MANIFEST["protocolVersion"]`（`plugin.py`）与 `core.py` 的 `PROTOCOL_VERSION`
是四个互不相识的字面量，跨仓独立升版时会静默漂移，且没有任何测试会失败——真实拦截只会在手机端
协商时表现为 `PROTOCOL_INCOMPATIBLE`。

现在加载时校验，不一致直接拒绝并说明差异，把问题挡在能处理它的那台机器上。

### 5. pin 门禁比对契约树而非提交号

`gateway-contract/tools/check-contract-pin.py` 比对 pin 提交与本仓工作区的
`gateway-contract/{schemas,vectors}`。只比对 schemas/vectors 目录，不含 `tools/`（那是仓库工具，
不是契约的一部分）。

比"pin 是否等于 HEAD"更准确：只改应用代码、不动契约的提交不会造成误报，而真正的契约变更一定
会被拦下。

## 验证

- 插件仓 305 项测试通过；`hermes plugins validate` 返回 `ok: true`，安全扫描为 `safe`
- 主仓跨宿主一致性：Hermes 侧 66/66 向量通过，OpenClaw 侧与跨宿主比对测试通过
- 契约 pin 门禁在"契约未变"与"契约已变"两种情形下分别通过与失败
- `legacy/bridge-runtime` 在系统 Node 26 下有 2 项失败，为项目已知的版本问题；按既有约定用
  `./tools/run-node24` 运行后 87 项全部通过

## 遗留

- catalog 条目已备好（`docs/superpowers/integrations/hermes/catalog/`），向上游提 PR 后
  `hermes plugins install open-android-intelligence-gateway` 即可直接安装
- 跨仓发版顺序：本仓契约变更 → 升级插件仓 pin → 发布插件 → 手机端升级。顺序颠倒会让手机端在
  协商阶段被拒
