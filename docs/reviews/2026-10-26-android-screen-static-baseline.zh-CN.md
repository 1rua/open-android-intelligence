# Android 屏幕能力：静态测试基线对照

基线为目标 `dev` 的提交 `736e458c481aa7a79b5e2d984efda7bcaf4c1287`。
本记录只说明 Python 静态检查的实际结果，不替代 Android 编译、框架单元测试或真机验证。

## 当前任务的检查结果

在屏幕能力 worktree 执行：

```sh
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
```

共运行 132 项，出现 11 个断言失败与 1 个异常。失败集中在既有助理入口、
短信/通话模块的源码与 Manifest 旧断言；异常来自发行元数据的完整 Git 历史检查。
新增加的屏幕包资产白名单已扩展为六项，相关打包测试没有失败。

进一步独立运行本次直接涉及的检查：

```sh
PYTHONPATH=apps/android/tools python3 -m unittest \
  test_control_ports_static test_capability_ports_static \
  test_bundled_plugins test_transport_boundary -q
```

结果为 **36 项全部通过**，覆盖控制接口、采集接口、六个签名插件资产与通信限制。

## 对照方式与结果

另外创建干净的 detached worktree，固定到上述 `dev` 提交。以同一 `python3`
重新执行当前工作区失败的 12 个完整测试 ID，得到 **相同的 11 个失败与 1 个异常**；
完整测试 ID 集合逐项相等，没有把“文件未修改”当作复现证据。

| 测试模块 | 在基线复现的完整方法名 |
| --- | --- |
| `test_assistant_handoff_static.AssistantHandoffStaticTest` | `test_holder_exposes_typed_handoff_without_uri_or_implicit_ipc` |
| `test_assistant_holder_attachments_static.AssistantHolderAttachmentStaticTest` | `test_capture_requires_read_grant_content_scheme_and_closed_count` |
| 同上 | `test_launch_payload_does_not_expose_uri_or_arbitrary_location` |
| `test_call_log_collector_static.CallLogCollectorStaticTest` | `test_call_log_collector_is_registered_with_only_approved_dependencies` |
| 同上 | `test_call_log_job_service_is_private_and_platform_bound` |
| 同上 | `test_call_log_module_is_in_the_root_forbidden_surface_scan` |
| 同上 | `test_call_log_scheduling_has_no_reboot_persistence_surface` |
| 同上 | `test_main_manifest_is_read_only_for_sms_and_call_log` |
| 同上 | `test_no_manifest_declares_forbidden_phone_permissions` |
| `test_sms_collector_static.SmsCollectorStaticTest` | `test_manifest_grants_read_only_and_protects_the_job_service` |
| 同上 | `test_sms_collector_is_registered_as_an_android_module` |
| `test_app_release_metadata.AppReleaseMetadataTest` | `test_prepare_cli_records_one_consistent_build_environment`（异常） |

最后一项另经直接执行 `app_release_metadata.py prepare` 确认，实际拒绝原因是
`Release builds require a complete Git history`。原 `dev` checkout 与任务 worktree
的 `git rev-parse --is-shallow-repository` 均返回 `true`；本次没有放宽发行检查。

本阶段没有改动上述助理 UI、短信/通话功能或它们的旧测试断言。此处保留这些未通过项，
供后续修复静态测试与当前架构之间的失配；不能将整套静态检查描述为全部通过。
