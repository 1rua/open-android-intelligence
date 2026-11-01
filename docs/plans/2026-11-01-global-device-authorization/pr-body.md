## 变更

同一 Android 安装连接不同 Core 时，旧能力许可按 Core/账号分区，造成切换后状态不一致。本 PR 将持久能力策略收敛为安装实例单份，Core 继续负责合法设备绑定、路由和可靠交付。

```text
合法设备请求 → Core 认证与路由 → Android 总开关/三级模式/范围/系统权限 → 执行与交付前复核
原生短信/通知 → 独立本地推送范围 → 加密队列 → 已选稳定端点 → 逐端消费 ACK
```

- 保留账号、主密钥、配对、会话、附件和系统权限；幂等清理旧能力 grant，临时批准可撤销、到期或重启失效。
- 短信/通知的被动范围与主动推送独立，推送默认关闭；本地队列保留未确认数据、稳定事件 ID、端点代次和到期时间。
- 屏幕读取、截图、控制独立授权；控制使用 FIFO 租约并在最终副作用前复核。
- 用户追加要求已先写入 spec、CONTEXT、设置规格、指南和 AGENTS.md：通知复用应用列表的搜索/正选/反选；短信使用默认全部、关键词、电话号码；保留既有 UI 功能与文案。对应实现已完成、Fast/PR通过，新界面Terra已验证通知搜索/正反选/取消重开/同值保存，以及短信关键词实际过滤与元数据推送；其余完整矩阵继续分项复测。

## 验证与证据

- 旧基线：`dev@3c9b82d1`。`1199eb70` 的 Fast、PR 与全部远端检查通过；`97f601f7` 本地 Fast、PR 通过，但该 head 远端 Hermes native 与 Android Play 失败，不能称最新 CI 全绿。
- 最新本地补丁 Fast/PR 完整退出0：Core634（1项符号链接环境skip）、Hermes143、DSH37；Android App1347、gateway-client320、device-primitives55、platform-kernel83、conversation-ui684均0失败/跳过。通知与短信UI定向62项、最终迁移/过滤/队列60项、standalone49项、Play变体63项通过。两项CI时序问题均保留原上限/断言，修复夹具同步；源码head 1f338778远端Core3.11/3.12、Hermes、DSH、Android、APK/签名全部成功。
- Terra 模拟器已取得双 Core 切换保持、手动拒绝/取消/确认超时、5/30/60 分钟临时批准及自然到期、OS 通知双端投递/逐端 ACK/后台恢复、SMS 被动范围与元数据/主动推送、无障碍屏幕读取与 MediaProjection 截图分项证据。
- 新包保留SMS未ACK队列并完成真实1小时自然过期；被动关键词命中/不命中、MCP正文搜索拒绝及主动关键词metadata/有效未选端点0条通过。完整屏幕控制、生命周期与旧策略升级的运行验收仍有缺口；当前没有实体手机，模拟器和自动化不代表物理真机通过。

详见 [执行记录](../../reviews/2026-11-01-global-device-authorization.zh-CN.md)、[覆盖矩阵](../../reviews/2026-11-01-global-device-authorization-coverage.zh-CN.md)和 [Terra 报告](../../reviews/2026-11-01-global-device-authorization-terra.zh-CN.md)。

## 合并边界

目标分支 `dev`，保持 Review，自动合并关闭。授权与队列升级采用版本屏障，不认识新范围的旧版本停止敏感执行；升级与回退保留原状态，禁止重建身份/密钥或清空队列绕过。完整矩阵和最新 head 检查未闭合前，不按全部通过合并。
