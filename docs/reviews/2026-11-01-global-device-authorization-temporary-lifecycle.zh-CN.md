# 全局设备授权临时许可验收记录

## 范围与证据

- 验收日期：2026-11-01；下文时间均为北京时间（UTC+8）。设备仅为 `emulator-5556/user0`，使用原有绑定 `dev_d097914b-0267-486e-bedd-1ab83a3654f0`。
- 所有短信请求均使用 `org.openandroidintelligence.sms.query@1.0.0`，参数仅为 `{"receivedAfter":"2099-01-01T00:00:00Z","limit":1}`；未读取历史短信或注入短信。
- 以下标明“证据文件时间上界”的时刻是本机文件修改时间，不是服务端请求创建时间或精确点击时间。A 阶段未单独记录精确批准时间；B、C 的点击上下界和单调时钟记录在各自证据中。
- 相对链接指向本地被 Git 忽略的运行证据；其他工作树或克隆不会因提交本文而自动获得这些文件。界面状态和进程检查中未另存为文件的部分，依据本次操作时的工具输出记录，不作为可独立重放的截图证据。

## A：临时许可生效

原模式为 `READ_ONLY`，短信读取已开启；[修改前界面截图](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/a-before-read-only.png)。通过界面仅将短信查询模式改为 `MANUAL`。

| 请求 | 时间与操作 | 实测终态 |
| --- | --- | --- |
| `devreq_889c7ca4-32c2-43b7-9b59-543a0b4f92c7` | 等待确认的[证据文件时间上界为 22:02:10.817](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/a1-submit-sanitized.json)。真实确认卡的声明、2099 年起始时间与条数匹配，选择允许 5 分钟。批准发生在上述记录之后、[首次成功状态记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/a1-status-shape.json)的文件时间上界 22:03:10.473 之前；这是按操作顺序推定的区间，不是精确点击日志。 | [`succeeded`，0 条记录，无正文](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/a1-result-sanitized.json)。 |
| `devreq_7a3c6ca0-0e2a-47da-95b3-b9c0074382e2` | 相同参数、不同请求键和 Agent 声明；[结果文件时间上界为 22:03:42.063](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/a2-result-sanitized.json)。 | 未再次批准即 `succeeded`，0 条记录、无正文；结束时界面无确认卡。未进行连续录屏。 |

结论：本次五分钟临时许可在另一 Agent 声明的同范围请求中生效。

## B：主动撤销

第一次点击撤销的时间为 22:07:40.973–22:07:40.996；由于 A 的精确批准时刻未记录，无法排除许可已自然到期，因此不以该次点击单独判定撤销通过。随后重新真实授权并立即撤销：

| 请求或动作 | 时间与操作 | 实测终态 |
| --- | --- | --- |
| `devreq_11932a07-aca8-4b4c-b019-c6c9b8d94af4` | [等待确认记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-refresh-submit.json)的文件时间上界为 22:08:46.600；真实匹配卡选择允许 5 分钟，[点击上下界](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-refresh-allow-click.json)为 22:09:41.541–22:09:41.562。 | [`succeeded`，0 条记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-refresh-result.json)。 |
| 插件管理页“撤销临时授权” | [点击上下界](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-refresh-revoke-click.json)为 22:10:09.393–22:10:09.413；按单调时钟，距授权点击约 27.832–27.872 秒。 | 界面显示已撤销本机全部临时授权且长期设置保持原样。相同提示在第二次点击前已经可见，因此第二次撤销的判断不单独依赖提示。 |
| `devreq_7c9360c2-8937-4254-a37c-46800ef4afbf` | 同参数新请求，[等待确认记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-check-submit.json)的文件时间上界为 22:10:31.975；未批准。 | 进入 `awaiting_confirmation`；官方 CLI 取消后[确认 `cancelled`](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/b-check-final-status.json)。 |

结论：在重新授予的五分钟尚未到期时，撤销后同范围请求重新要求确认。

## C：突然进程死亡并重启

| 请求或动作 | 时间与操作 | 实测终态 |
| --- | --- | --- |
| `devreq_1a874cff-7ef5-49a1-8f55-269f240ac270` | [等待确认记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-allow-submit.json)的文件时间上界为 22:16:14.548；真实匹配卡允许 5 分钟，[批准点击上下界](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-allow-click.json)为 22:16:53.965–22:16:53.986。 | [`succeeded`，0 条记录，无正文](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-allow-result.json)。 |
| 进程终止及恢复 | 先 Home；核对目标应用命令行、PID `14169` 和 UID `10192` 后，仅使用同 UID `run-as` 对该 PID 执行 `kill -9`。[终止调用上下界](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-kill-time.json)为 22:17:46.685–22:17:46.703。操作时观察到旧 PID 消失；手动启动应用后，新 PID 为 `18795`、UID 仍为 `10192`。 | 未使用 `force-stop`、`killall` 或 root。PID 消失、新 PID 与命令行核对为当时工具输出，未单独保存原始输出文件。 |
| `devreq_5aa41307-7dee-4a21-8375-1de0f3021299` | 相同参数的新请求，[CLI 调用上下界](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-check-submit.json)为 22:18:39.693–22:18:49.781，距批准点击上界不到两分钟；未批准。 | 进入 `awaiting_confirmation`；官方 CLI 取消后[确认 `cancelled`](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-check-status.json)。 |

结论：上述受控的突然进程死亡和应用重启后，同范围请求重新要求确认，且验证发生在原五分钟许可内。此结果不证明系统 OOM 或设备重启场景通过。

## 最后状态与边界

- 通过真实界面将短信查询恢复为 `READ_ONLY`；界面显示被动范围仍为关键词 `OAI-SAMPLE-SMS-5556-20261101`，包含短信正文的开关关闭。这些是操作结束时的界面观察，未另存最终截图。
- 当时 `user0` 的 `READ_SMS` 仍为已授权；[原绑定](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/temp-revoke-restart-20261101/c-final-binding.json)在线，配对代次为 1。上述权限状态依据当时系统命令输出，未另存原始输出文件。
- 本次未操作主动推送设置；结束时未重新核验主动推送状态，不能将其表述为重新验收通过。未更换账号、重建配对或安装 APK。
- A、B、C 中本次发起的请求均已取得上述成功或取消终态；未启动持续后台观察进程。本文为离线整理，未再次查询设备或 Core，不能代表后续状态。
