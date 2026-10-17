# Hermes 停止生成与动态命令修复

配套插件为 `1rua/hermes-gateway-plugin` 的 `2.1.2`，固定源码提交
`3b113cadc0526f0475233e7c41790d4a8fdd9fc7`。本次更新主仓的插件目录条目、安装说明和
两处 CI 输入，并将真实命令发现测试加入原生 SDK 回归组。

原目录条目锁定 `9d642b4f517243213cb3bfddddf3f4ae7b27339f`，源码同时存在两个问题：

- `core.py` 使用 `DEFAULT_COMMAND_CATALOG`，返回 `/new`、`/models`、`/status`、
  `/review`、`/gateway`、`/clear`、`/help` 固定清单。
- `adapter.py` 的 `_native_generation_canceller` 仅从 `_message_handler.__self__` 查找
  runner。Hermes 的真实作用域包装回调没有 `__self__`，因此无法识别原生取消入口，
  不会协商 `generation-cancel-v1`。

主仓原 CI 固定的 `03aaab2` 与插件原 HEAD `8299154` 内容相同，已经包含动态目录和
包装回调识别；本次未把该 CI 提交误判为旧固定清单的来源。Android 已有远端命令目录
加载、协商门控和取消请求接线，本次未修改其 Kotlin 实现。

Hermes 原生支持停止生成。固定 SDK 的 `/stop` 路径依次使用
`gateway/slash_commands.py::_handle_stop_command`、
`gateway/run_agent_cache.py::_interrupt_and_clear_session`，以及
`gateway/platforms/base.py::cancel_session_processing`。插件沿用硬中断、运行失效和
原生任务取消路径，并增加以下约束：

- 原生 `on_processing_start` 记录账号、会话、generation 和实际处理 Task；取消前后
  校验任务仍属于该会话，避免在中断等待期间误停已经接续的新任务。
- 运行中 Task 确认取消才返回 `CANCELLED`；正常完成返回 `ALREADY_COMPLETED`，取消
  被吞掉或超时返回 `OUTCOME_UNKNOWN`，不会谎报已停止。
- handler 移除或断线后撤销能力；运行时返回 `UNSUPPORTED` 时恢复原运行状态。

命令目录每次读取 Hermes 的 `COMMAND_REGISTRY`、网关可用性配置、真实别名与插件
注册表。技能在独立的 Android 平台上下文中发现，避免继承 Telegram 的禁用技能
缓存。当前 SDK 使用 Telegram 共用的技能发现接口，旧兼容 SDK 读取真实技能注册表
并按 Android 禁用配置过滤；保留完整名称，不套用 Telegram 菜单的数量或截断限制。
宿主发现异常会明确失败，目录内容变化会更新 `catalogVersion`。

本次按难度分配了两个 Sol agent，分别修复取消生命周期和命令发现；Luna agent
负责 spec、旧发布入口与兼容性复核，主 agent 负责整合、完整测试和配套固定提交。

验证结果如下，原始结果位于 [evidence/2026-10-17-hermes-stop-command-catalog](evidence/2026-10-17-hermes-stop-command-catalog/)：

| 检查 | 结果 |
| --- | --- |
| 插件 CI 常规组 | 417 passed，21 skipped；跳过的原生行为由下一组验证 |
| 固定 Hermes SDK 原生历史、任务、HTTP、流式传输与命令组 | 82 passed |
| Hermes 共享 Gateway 协议向量 | 70/70 pass |
| 原生停止与取消边界专项 | 36 passed |
| 真实技能与插件注册表测试，最早兼容/固定/最新三套 SDK | 各 4 passed |
| 插件固定契约与主仓契约比较 | 内容一致，保持 `4dd825a` 契约与核心摘要 |
| 官方 `validate_plugin_dir` | `ok: true`，13 项通过；历史日志不可见 Unicode 有既有 caution |

真实 SDK 来源为：最早验证基线 `b3aa561faffd64f05436e429a6415d175e534ec9`，
CI 固定 SDK `75b083e9399115b12513385252811f09c3503c90`，最新核查 SDK
`e31c674f7c523070f48f3baad0650c2fdd7ef6e5`。最早基线已确认具有原生任务生命周期
钩子和取消 API；命令测试同时覆盖了尚未拆分 `commands_platforms` 的旧布局。

回归反证包括：用 `9d642b4` 适配器运行真实 Hermes 作用域包装和签名 HTTP 取消测试，
得到 `UNSUPPORTED`；修复版得到 `CANCELLED`。对插件修复前的 `8299154` 执行新增目录
用例，会因缺少 `/reset` 和继承 Telegram 技能缓存而失败，修复版通过。

可按插件 CI 的分组复现。设置 `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 指向本仓
`gateway-contract`，设置 `HERMES_SDK_ROOT` 指向固定 SDK 后，在插件仓执行：

```bash
export HERMES_HOME="$(mktemp -d)"
export HERMES_TEST_ISOLATION="$HERMES_HOME"

python -m pytest tests \
  --ignore=tests/test_native_history_complete.py \
  --ignore=tests/test_native_history_projection.py \
  --ignore=tests/test_native_streaming_delivery.py

PYTHONPATH="$HERMES_SDK_ROOT" OAI_REQUIRE_NATIVE_HERMES=1 python -m pytest \
  tests/test_native_history_complete.py tests/test_native_history_projection.py \
  tests/test_native_streaming_delivery.py tests/test_native_turn_lifecycle.py \
  tests/test_native_turn_review_boundaries.py tests/test_host_command_catalog.py \
  tests/test_native_binding_http_failures.py tests/test_legacy_conversation_canonical.py \
  tests/test_android_echo_fixture.py
```

本地使用隔离 Python 3.12 环境，没有调用真实模型。环境缺少 Android SDK，因此没有
执行 Android Gradle 构建或设备 UI 验收。额外执行未修改的 Android Python 静态检查：
88 项中 16 项既有失败，涉及助手入口、依赖装配和双 APK 等断言；这些失败不在本次
Hermes 修复范围内。

旧目录或固定 ref 安装需要显式移动锁定：

```bash
hermes plugins install 1rua/hermes-gateway-plugin --force --ref 3b113cadc0526f0475233e7c41790d4a8fdd9fc7 --enable
hermes gateway restart
```

随后在手机重新连接 Gateway，重新协商 `generation-cancel-v1`；输入 `/` 刷新目录。
本次仅创建配套修复 PR，未合并分支或发布标签。
