# 远程 Agent 控制 Android 后端修复与真机 APK 验收交接

记录日期为 2026 年 11 月 27 日（Asia/Shanghai）。任务关联 issue #248 与 PR #249，分支为 `feature/remote-android-control-chain`。真机验收使用与 PR 候选提交 SHA 一致的 `Android APK Artifact` 工作流产物。

## 协议与范围基准

- 上游协议基准固定为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，桌面安装联测对照 `v0.6.5`，DSH 固定组件对照 `0.2.2-rc.1`。[实施计划](../plans/2026-11-26-remote-agent-android-control-chain-fix.zh-CN.md) · [控制契约](../contracts/oai-control-rpc-v1.md)
- 实施范围覆盖 Android 网关后端（`apps/android/gateway-client`）、桌面本机 Android MCP 桥（`integrations/oai-android-mcp`）、固定上游跨进程联测脚本（`tools/remote-interop`）与相关文档。
- 身份边界保持独立 Client/Host 角色：扫码配对仅导入 Client，配对受限身份不触发 Host 补注册；控机从同账号授权的桌面入口打开手机虚拟工作区 `harness/phone-capabilities`，并在同一持久连接上验证原生 `remote_*` 与外部 Stdio `android_*`。

## 审查修复与真机重点

- 桥在生成 Stdio 配置前绑定当前登录身份。真机验收可在配置生成后、首次调用前登出并重新登录，确认旧配置失效，重新签发后再验证双入口。
- 桌面选中 Relay 后，手机可独立完成中继附着和 Noise 握手。设备密钥暂锁和注册响应体中断都应保留同账号的 Host 注册恢复机会。
- 任务移出目录后仍按原任务与请求回查手机回执。完整回执在内存中核对，同一任务中的产物重名保留原文件。

## 原候选的自动化证据

下表记录提交 `07aee9269bad334d0e3a42ac624418b075496dcf` 的验证结果。当前候选的 CI、评审与 APK 构建状态以 PR #249 的同 SHA 记录为准。

| 范围 | 落地实现与自动化验证结论 |
| --- | --- |
| 工作区打开与首帧订阅 | `PhoneVirtualHostRouter` 已实现 `workspace/create` 唯一虚拟路径幂等打开（返回共享工作区记录与 `created: false`）；`PhoneRemoteRpcHostHandler` 等待桌面事件订阅后再发送手机会话首帧。`PhoneVirtualHostRouterTest` 与 `PhoneRemoteRpcHostHandlerTest` 全部通过。 |
| Noise 握手与选路竞态 | `RemoteSignalService` 仅容忍当前已认证 Host 隧道中绑定正确的重复握手首帧，收敛选路与重连竞态。`RemoteSignalServiceTest` 全部通过。 |
| Host 首次注册恢复与角色隔离 | `RemoteServerApi.registerOwnedHost`、`RemoteCredentialVault` 密封原子提交结果（`HostRegistrationCommitResult`）与 `RemoteAccountRuntime` 补注册闭环已全部落地，同账号代次、并发唤醒退避、旧记录冲突/损坏终态持久化与单角色围栏隔离（`CONTROL_CONNECTION_FAILED` 不连带影响健康 Client）均已通过 `RemoteHostRegistrationRecoveryTest`、`RemoteCredentialVaultTest` 与 `RemoteServerApiTest` 回归。 |
| 回执预算与产物校验 | `PhoneControlBus` 按整条 Session V3 记录序列化后的 16,000 个 UTF-16 单元判断是否转入同任务私有产物，并在写入产物前拒绝保留前缀 `result-` 与重名覆盖。`PhoneReceiptBudgetTest` 与 `PhoneContentDeliveryTest` 全部通过。 |
| MCP 桥安全、发现与原请求回查 | `integrations/oai-android-mcp` 已补齐严格回环路由鉴权、`cordis.patch.yml` 默认停用与 Bundle 元数据、断线与换目标写入门禁、注销/换服旧授权失效（预取消与飞行中取消不误污染全局撤销态），以及历史缺失时按原请求回查 `control-bus`（仅完成态校验内层身份并保留手机终态/不重放语义）。真实 DSH `0.2.2-rc.1` 固定组件单测 **27/27 PASS（0 fail / 0 skip）**。 |
| 跨进程联测与全量门禁 | 已安装 `0.6.5` 运行时与默认路径下的 `OriginalPluginHostInteropTest` 双路径均 **1/1 PASS**；`npm run test:fast` **PASS**；完整 `npm run test:pr` **PASS**（覆盖全部直接/间接依赖模块、静态门禁与 `:app:testFullDebugUnitTest`，共 **351 suites / 3,460 tests / 0 failures / 0 errors**）。独立双轴（Spec 轴与 Standards 轴）代码复审结论为 **0 Blocker / 0 Major / 0 Minor**。 |

## 真机端到端验收步骤

1. **获取候选 APK**：在分支 `feature/remote-android-control-chain` 的 `Android APK Artifact` 工作流运行页下载 `app-full-debug` 产物并安装到测试真机。
2. **验证登录与 Host 在线状态**：在真机上完成同账号登录，确认 Host 设备完成注册并上报 `presence.online = true`，扫码配对场景继续保持仅授权 Client。
3. **验证双入口控机与回执闭环**：从同账号已授权的桌面入口打开目标手机工作区 `harness/phone-capabilities`，在同一持久连接上分别验证原生 `remote_*` 与外部 Stdio `android_*`：
   - 幂等打开工作区与创建独立任务会话，首帧事件有序到达；
   - 经手机确认的敏感/控制动作仅执行一次，断线或重连时不重复执行；
   - 长回执（超 16,000 UTF-16 单元）正确转入同任务私有产物并通过文件字节/校验和比对，状态未知时仅按原请求回查终态。
4. **验收后合并**：待真机端到端验收及远端 PR CI 全部通过后，由用户手动合并 PR #249。
