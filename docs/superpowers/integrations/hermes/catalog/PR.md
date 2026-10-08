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
| `sha` | `a8753549d535f1ebec90831f616bec4cfef65ccb` |
| `category` | `platform` |
| `tier` | `community` |

## 锁定提交上的校验结果

条目锁定提交上的 `hermes plugins validate .` 实测输出（与本 PR 的 CI 门禁同一命令）：

```
ok: True
  ✓ manifest: plugin.yaml parses
  ✓ manifest fields: name, version, description present
  ✓ requires_hermes: spec >=0.20.0 parses
  ✓ loadable: entry: __init__.py
  ✓ python dependencies: 1 installable from pyproject
  ✓ capability probe: register() ran in isolation
  ✓ declared tools: matches registrations
  ✓ declared hooks: matches registrations
  ✓ declared middleware: matches registrations
  ✓ built-in tool collisions: no collisions
  ✓ security scan: safe
```

`capability probe` 是关键项：它真实加载插件并执行 `register(ctx)`，因此通过即证明插件在
隔离环境中可被宿主加载。

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
git checkout a8753549d535f1ebec90831f616bec4cfef65ccb
hermes plugins validate .
```
