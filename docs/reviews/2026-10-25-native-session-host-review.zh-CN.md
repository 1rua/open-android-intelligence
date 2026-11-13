# 原生会话宿主兼容性复核

审查范围为统一 Core 的 Hermes/DSH 会话适配，不修改 OpenClaw，也不在宿主内增加设备请求调度。

## 实际接口与修复

- Hermes 原生 SDK 使用固定提交 `75b083e9399115b12513385252811f09c3503c90`，Gateway 包固定为 `b64dda00a0de9ff1a95a375dcf92f62b5a1027b3`。通过真实插件 manifest 解析、`PluginContext` 注册及 `SessionStore`/`SessionDB` 验证加载和会话生命周期。
- Hermes 标题原先只写 Gateway 展示记录，现通过账号已有 binding 解析原生数据库，使用 `set_session_title/get_session_title`；终端修改同样可见，不枚举其他账号的原生会话。
- Core 重新配对后的代次可能与独立 Hermes 旧账号库不同。发送元数据保留 Core 已验证的配对代次与授权版本，原生取消不再误报 `PAIRING_GENERATION_STALE`。
- 会话桥不提供 exec approval 决策通路，立即拒绝宿主审批并报告 `UNSUPPORTED_APPROVAL`，避免等待客户端无法处理的卡片。宿主不开放原 v2 设备 HTTP 路由、不注册旧设备工具。
- DSH 对照公开 npm `@deepseek-ai/dsh-session@0.0.1-rc.1`、`@deepseek-ai/dsh-session-query@0.0.1-rc.1`、`@deepseek-ai/dsh-api-session-controller@0.1.2-alpha.2` 核实。原生 `user/message` 的 `event.data` 是 `UserMessage` 本体，修复原先假定 `data.message` 导致的历史读取失败与 turn 关联超时，并兼容旧包装。
- DSH 的 `blocked`、`max-tokens`、`interrupted` 等非成功结束原因明确报错，不再宣告成功。

## 验证结果

```sh
HERMES_SDK_ROOT=.toolchains/hermes-native-sdk PYTHONPATH=oai-core/src python -m pytest oai-core/tests/test_hermes_native.py oai-core/tests/test_hermes.py oai-core/tests/test_dsh.py -q
npx vitest run integrations/dsh-session-adapter/adapter.test.js
```

结果：Python 8 项通过，DSH 11 项通过；`git diff --check` 通过。Python 仅有 aiohttp 请求存储键的非功能性警告。

原生 SDK 测试在独立子进程和临时 Hermes Home 中执行，覆盖真实注册、创建、历史、标题、归档、删除、账号隔离、消息生命周期、重新配对后的取消和不支持审批的拒绝。模型回调使用合成文本，未调用模型服务、读取真实用户会话或连接实体 Android。实际部署的版本、网络、真实模型与真机本地确认仍需在对应运行环境验收。
