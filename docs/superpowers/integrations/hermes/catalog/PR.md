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
| `sha` | `175ab18eabd58e7f8fd193c3e3a82c9b5b38126d` |
| `category` | `platform` |
| `tier` | `community` |

## 锁定提交上的校验结果

条目锁定插件 `2.2.0`，包含原生停止生成、动态命令目录、共享历史、文件夹/排序与原生归档/删除。
插件契约固定应用 PR #14 的提交，核心摘要为 `sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23`，需安装配套 APK。

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

Python 3.11 和 3.12 均有独立矩阵 407 项、真实原生矩阵 92 项通过，无跳过；全部 473 个测试的集合覆盖完整。
具体覆盖、打包验证及固定版本见[插件同步记录](../../../../reviews/2026-10-19-gateway-plugin-sync.zh-CN.md)。

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

- 契约按 `contract-pin.json` 的固定提交，由 setup/账号创建向导或 `contract sync` 获取并缓存；
  日常宿主启动不联网获取契约。契约不随插件分发，避免出现第二份可能与手机端漂移的副本
- 插件清单声明的协议版本与核心实现在加载时做一致性校验，不一致会拒绝加载

## 本地复现

```bash
git clone https://github.com/1rua/hermes-gateway-plugin
cd hermes-gateway-plugin
git checkout 175ab18eabd58e7f8fd193c3e3a82c9b5b38126d
hermes plugins validate .
```
