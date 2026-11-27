## Summary

```text
Core账号登录（可选2FA，默认关闭）
  ├─ 普通用户 → CLI / MCP → Core动态选设备 → Android最终授权
  └─ 管理员 → 限时管理会话 → 账号 / 配置 / 安装 / 服务

首次设备选择：显式目标 → 可信会话来源 → 有效默认 → 唯一在线
请求受理后：固定设备与配对代次，重试不改投
```

- 账号与单台设备解绑；无设备也可登录，短期访问凭据自动刷新、按安全事件撤销。
- 管理统一验证 Core 管理员账号；首次引导、旧账号指定验密迁移、最后管理员保护、审计和独立本机恢复码。
- `oai account create <username>` 等简化命令；setup 五项菜单，配置事务、备份、锁及失败恢复保留。
- Android 可选二次验证、旧设备私钥证明；Hermes 经实例公钥确认后自动创建账号专属原生空间，保持隔离。
- 更新安装、CLI、认证/路由 ADR 与 Agent 指南。

## Evidence

- **Before:** CLI 登录强制设备 ID；本机账号/配置写链只依赖本地确认；CLI Token 无账号级刷新。
  **After:** 真实 Core HTTP 无设备登录、MFA 开关/首次绑定、刷新/撤销和 CLI 管理回归通过。
- 提交前 `npm run test:pr`：Core/脚本 **860 通过**，Hermes **232 通过**，DSH **42 通过**，通信测量 **25 通过**；随后 Android 受本机文件系统限制失败，**整体准入不标通过**。
- 生产密钥装配的 Core→Hermes HTTP→固定 SDK SessionStore→重启隔离专项 **19 通过**；独立 Core wheel 实际启动及协议协商通过，无 Hermes。
- 后续已确认安全/升级阻断修复执行对应专项，不反复扩大全量。最后可信源码升级相关 **44 通过**；`git diff --check`、Shell 语法及依赖锁通过。
- Android：本机 Gradle 文件监视 NPE，禁用后中文 Kotlin 测试 class 写入 `Permission denied`；需要 CI 验证。协议 **176 通过 / 3 条跨宿主失败**，缺固定 OpenClaw 外部检出。真实 Android/ADB 设备调用未执行。
- 详细证据与边界：[交付记录](docs/reviews/2026-11-06-core-zero-config-delivery.zh-CN.md)。按用户要求停止扩展测试。

## Merge Danger

**Door:** one-way

账号安全模型迁移包含旧摘要退役和旧写入者屏障；回退必须同时考虑版本、完整快照和新安全事件，不能只回退包或替换主密钥。

**Blast Radius:** authentication

此 PR 先以草稿提交，**不请求直接合并**：Android CI/真机、完整生产升级恢复、身份库损坏修复仍需闭合。共享 DSH/Codex 不注入个人长期凭据；无法隔离回合时不自动继承设备身份。Hermes 首次跨域信任仍需管理员确认 Core 公钥，之后账号自动关联。Android 离线注销远端待同步和极端修复路径的剩余边界在交付记录中明确，不以本地退出屏障冒充远端即时撤销。
