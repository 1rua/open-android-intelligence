# 提交 Hermes 插件目录条目：open-android-intelligence-gateway

本 PR 向 `plugin-catalog/` 新增一个条目，使任何 Hermes Agent 用户可以用
`hermes plugins install open-android-intelligence-gateway` 直接安装 Open Android
Intelligence 的手机端网关适配器。

## 变更

新增 `plugin-catalog/open-android-intelligence-gateway.yaml`：

| 字段 | 值 |
|---|---|
| `name` | `open-android-intelligence-gateway` |
| `repo` | `https://github.com/1rua/hermes-gateway-plugin` |
| `sha` | `3b113cadc0526f0475233e7c41790d4a8fdd9fc7` |
| `category` | `platform` |
| `tier` | `community` |

## 锁定提交上的校验结果

条目锁定插件 `2.1.2`，替换会返回固定命令且无法识别宿主包装回调的 `9d642b4`。
新版本透传 Hermes 原生停止路径，并从当前宿主注册表发现命令、别名、插件及技能。

在固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90` 上调用
`hermes plugins validate .` 使用的同一实现 `validate_plugin_dir`：13 项检查均通过。

```
ok: True
checks: 13
python dependencies: 2 installable from pyproject
capability probe: register() ran in isolation
security scan: caution (existing historical log contains invisible Unicode)
```

`capability probe` 是关键项：它真实加载插件并执行 `register(ctx)`，因此通过即证明插件在
隔离环境中可被宿主加载。

插件常规测试 417 项通过；真实 Hermes 集成测试 82 项通过；共享协议向量 70/70。
具体覆盖范围与环境限制见[修复记录](../../../../reviews/2026-10-17-hermes-stop-command-catalog.zh-CN.md)。

## 依赖与环境变量

插件运行时不要求任何必需环境变量（`requires_env` 为空）。以下为可选项：

- `OPEN_ANDROID_GATEWAY_HOST` / `OPEN_ANDROID_GATEWAY_PORT`：监听地址与端口，默认 `0.0.0.0:8045`
- `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT`：直接指定已就位的 Gateway Protocol 契约目录，供离线部署使用
- `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE`：主密钥文件位置（Hermes 不向插件暴露
  Secret Store，见该插件仓引用的 ADR 0023 / 0048）

## 与目录政策的关系

该插件以独立仓库发布，未修改 Hermes 任何 in-tree 代码，符合
`plugins/AGENTS.md` 中"第三方产品插件作为 standalone plugin repo 交付"的要求。

## 审阅请注意

- 插件在加载时按 `contract-pin.json` 锁定提交获取 Gateway Protocol 契约，宿主无需预装任何
  依赖；契约不随插件分发，避免出现第二份可能与手机端漂移的副本
- 插件清单声明的协议版本与核心实现在加载时做一致性校验，不一致会拒绝加载

## 本地复现

```bash
git clone https://github.com/1rua/hermes-gateway-plugin
cd hermes-gateway-plugin
git checkout 3b113cadc0526f0475233e7c41790d4a8fdd9fc7
hermes plugins validate .
```
