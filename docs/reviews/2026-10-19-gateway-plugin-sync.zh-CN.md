# Gateway 插件同步

本轮把 [PR #14](https://github.com/1rua/open-android-intelligence/pull/14) 已验证的会话管理及评审修复纳入两个独立插件仓，并同步主仓的固定引用、安装说明与 CI。

Hermes 插件升级为 `2.2.0`，OpenClaw 插件升级为 `1.1.0`。两端契约均固定到主仓 `f0b3f1bd4f6f3301b820e38c9f3241bcac05cd10`，核心摘要为 `sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23`。后续主仓提交仅更新插件引用、CI 和交付文档，契约输入保持一致。

同步包含完整历史分页、账号级文件夹/分类/排序、原生会话归档及删除、同请求 ID 的待确认操作恢复、升级前原生删除识别，以及可信原生路由切换后的存在证据隔离。已有停止生成、动态命令、历史附件和账号隔离修复继续由原有测试验证。

主仓协议及 Android CI 直接检出插件固定提交。两个旧 patch 目录保留为历史基线回补工具，其 `base-pins.json` 独立于当前安装引用，避免对已内置修复的插件重复应用补丁。

## 验证与交付

| 插件 | 版本 | 固定提交 | 配套 PR |
| --- | --- | --- | --- |
| Hermes | 2.2.0 | `175ab18eabd58e7f8fd193c3e3a82c9b5b38126d` | [#3](https://github.com/1rua/hermes-gateway-plugin/pull/3) |
| OpenClaw | 1.1.0 | `c7c087b9fe28adcc658a1cbdb71689a377b65214` | [#2](https://github.com/1rua/openclaw-gateway-plugin/pull/2) |

Hermes 在 Python 3.11.17 与 3.12.14 下均通过 407 项独立测试和 92 项原生测试，无跳过。全部 473 个测试被两组覆盖，26 项旧会话兼容测试有意重复执行；官方宿主清单的 13 项检查、契约同步、sdist/wheel 构建及安装验证通过。原机制同步的 56 个契约文件与锁定提交逐字节一致。

OpenClaw 通过 37 文件、208 项测试，5 项 Python 契约测试、类型检查和快照重生成检查；包内 70 个向量通过。连续两次构建的 61 个运行文件与审查清单一致，在原生 OpenClaw `2026.7.1-2` 安装后也全部一致，加载状态为 `loaded`、diagnostics 为空。10 份 Schema、10 份向量和分派索引在主仓、源码快照和运行包之间原始字节相同。

主仓针对上述实际插件提交的验证全部通过：

| 检查 | 本轮结果 |
| --- | --- |
| 两端契约 pin、OpenClaw 插件 pin | 通过，检出 SHA、包版本及全部锁定契约输入匹配 |
| 主仓 Node 全套 | 113 个文件、1,010 项通过，包括锁文件同步与插件源码/runtime 回归 |
| 主仓 TypeScript | 通过 |
| 跨宿主一致性 | 两端各 70 个向量、3 项比较通过，使用全新结果目录 |
| 两组归档工具 | 会话管理 10 项、会话恢复 6 项通过 |
| Android 消费方 | 会话数据 Debug / Release 各 60 项、Full Debug HTTP 历史媒体与附件类型 5 项，共 125 项通过，无跳过、失败或错误 |

Android 互通使用 Python 3.11 和固定的新 Hermes 源码，显式禁用测试结果复用，Gradle 耗时 1 分 7 秒。原生历史、签名会话、消息事件和附件读取由实际 Python Gateway 通过 HTTP 提供。主仓 Android 业务源码和契约输入没有变化；前轮全量 3,992 项通过、6 项渠道条件跳过及 APK 记录仍见[评审复核记录](2026-10-19-pr14-review-followup.zh-CN.md)，不把本轮 125 项互通重跑写成重新执行全部 UI 测试。

独立仓验证详见 [Hermes 记录](https://github.com/1rua/hermes-gateway-plugin/blob/175ab18eabd58e7f8fd193c3e3a82c9b5b38126d/docs/reviews/2026-10-19-conversation-management-sync.md)、[OpenClaw 记录及原始证据](https://github.com/1rua/openclaw-gateway-plugin/blob/c7c087b9fe28adcc658a1cbdb71689a377b65214/docs/reviews/2026-10-19-shared-conversation-management.zh-CN.md)。消费方的固定输入和计数见[机器结果](evidence/2026-10-19-gateway-plugin-sync/consumer-results.json)。应用功能及评审修复已通过 [PR #14](https://github.com/1rua/open-android-intelligence/pull/14) 合并；本轮插件固定引用和 CI 更新由单独的后续 PR 交付。

新插件须与 PR #14 的 APK 配套安装。插件提交及 PR 不代表已合并到 `main` 或已发布版本标签；实际宿主升级需按主仓 README 的完整提交 SHA 安装并重启，然后由手机重新连接协商。Samsung Now Bar 的真机验收边界仍以[兼容性记录](2026-10-18-session-notifications-compatibility.zh-CN.md)为准。
