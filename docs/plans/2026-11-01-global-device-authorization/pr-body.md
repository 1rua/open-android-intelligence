## 变更

同一 Android 安装切换 Core 或账号时，旧按配对存储会加载另一份设备许可或迁移状态。本次改为安装实例全局策略，Core 只保留合法设备访问、协议、路由和可靠交付职责。

```text
CLI / MCP 声明名称与目的 + 合法设备凭据
  → Core 校验与路由
    → Android 全局模式 / 数据范围 / 系统权限 / 临时批准
      → 插件执行与结果前复核
        → Core 结果与消费 ACK

短信 / 通知系统事件
  → 独立范围与稳定端点白名单
    → Android 加密队列
      → Core 60 秒短暂路由缓冲
        → 接收端消费 ACK
```

- 原子清理旧配对能力键与迁移标记，保留账号、身份密钥、会话、附件、系统权限和插件安装状态。新能力默认每次手动，主动推送默认关闭。
- 逐能力总开关及手动／只读／自动模式；本次或5/30/60分钟精确范围批准，取消、撤销、到期、进程重启失效。
- 短信与通知独立推送、跨Core稳定路由白名单、逐端去重/ACK/撤回，后台恢复和全账号退出门槛。
- 屏幕读/截图/控制分开，控制FIFO独占30秒租约、续租累计5分钟，排队和系统动作前复核。
- 文档、ADR、CONTEXT、协议和oai-client说明同步。

## 证据

- 修改前源码事实：授权存储键包含Core地址、账号、安装实例；确认只限前台30秒；声明与推送生产入口未闭合。
- 当前自动化及设备证据以 `docs/reviews/2026-11-01-global-device-authorization.zh-CN.md`、`docs/reviews/2026-11-01-global-device-authorization-coverage.zh-CN.md` 和 `docs/reviews/2026-11-01-global-device-authorization-terra.zh-CN.md`为准。
- `npm run test:fast`、`npm run test:pr` 均最终退出0；Core634通过（1跳过）、Hermes原生143、DSH37；协议179项及类型检查通过。
- App最新1300项通过、0失败/错误/跳过；受影响Android模块全部通过。独立Core撤销/取消/pending45项通过。
- Terra已实际安装并启动隔离模拟器user10中的新版App；已复现登录失败并以真实Android探针定位到测试CA信任缺失，debug配置修复后同一探针OK(1 test)并真实UI登录CoreA成功；双Core/能力完整矩阵仍未通过；没有连接实体手机，**物理真机验收BLOCKED，不能按已完成验收合并**。

## 合并影响

设备授权、请求执行、主动推送和封闭Schema协商均受影响。Core hash已同步升级，旧客户端与新Core需明确报告不兼容；不能降级回旧账号grant。

旧Android授权键迁移不自动逆转；回滚需经核验的一致备份，不通过清空数据、重建账号或替换密钥恢复。实体手机与尚未运行矩阵保持明确待验收。

实现提交 `17895223c16fc84dfbe7c889943a608f7fbaa095`，运行验收修复提交 `1680c04e`；最新本地APK SHA-256 `5affe22f0918e869539428f0a673491209fecedfdbf6fc059bcefcf0787e19ea`。

目标为`dev`，保留PR Review，不自动合并。

### 当前验收与CI阻塞

- 实体手机未连接，物理验收BLOCKED。模拟器已完成CoreA登录，完整权限矩阵仍未完成，禁止按全部验收通过合并。
- 源码提交17895223的Core3.11/3.12、Hermes、DSH CI成功；Android有一项WebSocket测试报告测试开始前异步异常，正在保留完整报告定位，不跳过断言。
- APK产物任务最初因Maven镜像HTTP502失败，已只重试失败任务一次；最新head应单独核对。

后续已验证：debug测试CA真实TLS探针先失败后通过并成功登录CoreA；通话记录系统权限拒绝/允许、App手动拒绝和确认超时实测通过；真实CLI/MCP端点注册与发现、未认证401、独立凭据端点互访403通过。
现场发现确认按钮重叠及终态进度残留，已补丁和回归，最终head设备触摸复测进行中。

已补充模拟器跨Core证据：同一安装Core A→B→A时通话总开关、MANUAL和系统权限保持；CoreB请求到达App且CLI取消得到cancelled；布局修复包真实触摸拒绝返回LOCAL_USER_REJECTED。最终Core快照终态字段与完整推送矩阵仍待复测，物理手机仍BLOCKED。
