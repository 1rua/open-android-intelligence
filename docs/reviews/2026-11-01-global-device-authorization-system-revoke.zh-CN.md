# 5554 用户 10 无障碍系统撤销与恢复

## 范围与结论

本次在 `emulator-5554` 的当前 Android 用户 `10` 上，使用当时设备已安装的 `67d7d533...4124fa68` 应用包，验证 OAI 无障碍服务经系统界面关闭后的屏幕能力拒绝，以及系统服务重新开启后的能力状态。没有安装或替换 APK，没有操作用户 `0` 或 `emulator-5556`。

系统撤销这一独立权限门禁的观测结果符合预期：关闭后两项屏幕能力均报告缺少 Android 权限，唯一一次屏幕读取提交被拒绝；恢复服务后，两项能力重新报告 `READY`。本次不证明屏幕读取或控制操作在恢复后实际执行成功，也不覆盖 task-011 的手动控制失败。

## 实际过程

1. 系统设置的 OAI 服务开关原为开启；官方能力查询中的 screen-reader 和 screen-control 均为 `READY`。[系统原状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/system-detail-before-private.json)；[原能力状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/before-capabilities.json)。
2. 通过 Android 系统无障碍设置及其确认对话框关闭 OAI 服务，界面中的服务开关随后未选中；两项能力均变为 `ANDROID_PERMISSION_REQUIRED`。[关闭后系统状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/system-off-private.json)；[关闭后能力状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/off-capabilities.json)。
3. 仅提交一次 screen-reader 请求，参数为 `operation=snapshot`、`maxNodes=8`、`maxDepth=8`、`maxBytes=4096`。调用被能力检查拒绝，错误为 `ANDROID_PERMISSION_REQUIRED`，原因是 `ACCESSIBILITY_SERVICE_UNAVAILABLE`；响应没有 requestId，未产生本轮待批准请求，也未点击批准或拒绝卡片。[调用错误](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/off-reader-invoke-stderr-private.txt)。
4. 通过 Android 系统界面及其确认对话框恢复此前开启的 OAI 服务；最后一次系统界面记录显示开关已选中，两项能力再次为 `READY`。恢复后没有再提交读取或控制请求。[恢复后系统状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/system-restored-private.json)；[恢复后能力状态](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/restored-capabilities.json)。

## 策略与证据边界

- 撤销前的界面记录显示：screen-reader 总开关开启、模式为每次手动；screen-control 总开关开启、模式为允许只读。本次没有人为修改这两项策略；**恢复后没有重新进入策略页面复核，因此长期策略恢复后的实际状态尚未验证**。[reader 撤销前策略](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/reader-policy-before-private.json)；[control 撤销前策略](../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-accessibility-revoke-user10-20261101/control-policy-before-private.json)。
- 服务已恢复的结论依据 2026-11-01 22:19 左右保存的界面和能力证据；本次文档收尾未再连接设备，不能代替之后的实时状态检查。唯一提交在创建 requestId 前被拒绝，故没有本轮由该提交产生的活跃请求；未检查其他会话的请求。
- 本轮没有启动持续日志观察任务。文档收尾时本机仍有先前已存在的 `OaiE2E` 日志进程，并非本轮启动，未予停止。
- 上述原始界面和能力证据位于 Git 忽略的 `runtime/evidence/`，不随普通提交自动分发。本报告不包含屏幕节点正文或认证凭据。
