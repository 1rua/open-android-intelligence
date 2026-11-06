# Hermes 注册失败修复记录（2026-11-06）

## 已验证的原因

本机 Hermes 服务存活，但 8792 会话桥未监听。已安装适配器与当前仓库内容相同，包含 `/handshake`；此处没有重复 DSH 缺少接口的缺陷。

2026-11-06 的启动日志明确报告：

```text
Failed to load plugin 'open-android-intelligence-gateway': Hermes allowlist contains an account absent from the existing account root
```

只读调用原账号发现函数得到 9 项，其中 8 项无法定位到对应账号数据库。配置生成与账号关联的两处代码都直接将 `*/gateway.sqlite` 的父目录名视作账号 ID；实际目录名是账号 ID 的 SHA-256 哈希，后端再次按账号 ID 哈希定位数据库，因此启动校验失败。

## 修复

[configuration.py](../../oai-core/src/oai_core/configuration.py) 中的 `_discover_hermes_native_accounts()` 现在以只读 SQLite 连接读取 `account_metadata.gateway_account_id`，并核对真实账号 ID 对应的存储路径。无法确认身份、元数据异常或属于自动专属空间的发现结果不加入普通宿主账号候选。配置中明确列出的合法现有账号继续保留。

`provision_host_agent_files()` 复用同一发现函数，使生成的 `OAI_HERMES_ACCOUNTS` 与账号关联使用相同的真实账号身份。

对同一本机数据运行修复后的只读函数，结果为 1 个真实账号、0 个无效账号。测试与检查未改写运行配置、账号数据、主密钥或历史。

## 简单验证

- 原本机只读复现：发现 8 个无效账号，断言失败。
- 修复后同一数据：无效账号数为 0，断言通过。
- 已有 `test_host_candidate_provisioning_and_auto_connect`：1 项通过，覆盖候选生成与 Core / Hermes 注册握手。
- 新增账号发现回归：3 项通过，覆盖真实身份恢复、哈希伪账号过滤、异常元数据与合成环境配置回写。
- 本次共执行 4 项定向自动测试，均通过；未运行全量构建。

## 交付边界

独立 DSH 修复 `de69f871ede2eb228896266a9d75f74b3a023f58` 已快进合并到其本地 `dev`。本次 Hermes 修复位于 OAI Core 的配置与发现逻辑，独立 Hermes 薄入口无需复制协议实现。运行环境尚需更新 Core 并通过管理员配置入口重新生成候选和接入。
