# 应用更新检测实施与提交前验证

日期：2026-10-21。修改前基线：`c9ab5fdb02d17c32c08322591c5d95794e741552`。本报告记录本地提交前证据；完整 CI、正式签名发布和用户真机操作分别验收，不由本地定向测试代替。

## 完成范围

- 设置增加“应用更新”子页，可在正式版和预览版间切换并记住选择，进入页面自动检查，提供手动重查和系统浏览器发布页入口。
- 正式渠道检查正式版本；预览渠道检查开发滚动包、以后带版本号的测试包，以及后续可升级正式版。
- 发布页统一显示“开发预览测试版”，保留 `nightly` 标签和旧 APK 文件名。
- 使用实际版本码比较，核对分发渠道及包名，显示签名兼容性；没有发布、清单不完整、网络错误和限流均有独立结果，不伪装成已是最新版。
- 构建版本信息与发布清单共同生成；发布前读取真实 APK Manifest 和签名，再核对构建输出信息。GitHub Actions 中所有生成 App 的检出均使用完整历史。

功能规格见 [Android 应用更新渠道](../superpowers/specs/2026-10-21-app-update-channels.md)，字段与发布规则见 [App 更新发布元数据 v1](../contracts/app-update-release-v1.md)。

## 独立审查及修复

提交前独立子 Agent 审查发现两项 P2，并在修正后复核关闭，没有发现修复引入的重要缺陷：

1. 只取消协程不能停止阻塞式网络扫描。最初的 `HttpURLConnection.disconnect()` 在真实阻塞读取下仍会等待读取锁，测试实际复现。最终使用独立 OkHttp `Call.cancel()`，检查端口改为挂起函数，在分页、逐清单及正文读取中检查取消。两项真实 TCP 测试分别覆盖第二页与清单正文阻塞：取消后服务器收到连接关闭，没有业务结果返回，也没有继续请求其余页面或清单。实际依据见 [OkHttp 4.12.0 Call 契约](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt)。
2. 滚动发布默认并行上传 APK 与更新清单，可能先公开清单。现启用 `preserve_order: true`，APK 排在清单之前，确保 APK 上传失败时不会继续上传新清单；增加配置与顺序守卫，并验证两种回归变体均会失败。依据见 [发布 Action 官方实现](https://github.com/softprops/action-gh-release/blob/v2/src/main.ts)。

## 已执行的最小验证

本地最终定向 Gradle 成功，测试任务实际执行，49 项全部通过：

| 测试 | 通过数 |
| --- | ---: |
| `AppUpdateCheckerTest` | 20 |
| `AppUpdateViewModelTest` | 4 |
| `AppUpdateScreenTest` | 6 |
| `SettingsViewModelTest` | 16 |
| `SettingsHonestyCopyTest` | 2 |
| `DistributionVariantTest` | 1 |

```sh
cd apps/android
./gradlew :app:testFullDebugUnitTest \
  --tests 'com.openandroidintelligence.mobile.updates.*' \
  --tests 'com.openandroidintelligence.mobile.AppUpdateCheckerTest' \
  --tests 'com.openandroidintelligence.mobile.SettingsHonestyCopyTest' \
  --tests 'com.openandroidintelligence.mobile.SettingsViewModelTest' \
  --tests 'com.openandroidintelligence.mobile.DistributionVariantTest' \
  --no-daemon --max-workers=2 --console=plain
```

发布元数据 Python 测试 16 项全部通过，并已接入 CI：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s apps/android/tools -p 'test_app_release_metadata.py'
```

三份修改的工作流 YAML 解析及共 27 段 shell 语法检查通过。`git diff --check` 通过。新依赖的 App 运行配置解析成功。发布子任务还使用既有 APK 实际运行了 `aapt2`、`apksigner` 及错配拒绝流程；这只证明真实字段读取与拒绝旧包，不代表已生成此次新版 APK。

## 补充检查中的基线问题

额外执行历史 `test_transport_boundary.py` 时，14 项中有 4 项失败。为区分改动影响，使用修改前完整提交中的 Android 文件在独立临时快照中执行相同测试，失败项目完全相同，没有本次新增失败：

- `test_assistant_holder_exposes_only_the_default_assistant_entry_seam`
- `test_closed_contract_modules_are_registered_and_scanned`
- `test_main_apk_wires_closed_contract_libraries`
- `test_two_apk_manifests_have_distinct_packages_and_no_holder_network`

这些旧断言仍要求被后续架构取代的独立 APK 或旧模块装配，不属于本次更新功能的验收范围；本次没有修改旧测试或旧架构来掩盖失败。不得将这项补充检查描述为全绿。

## 远端及真机证据边界

更新核心曾直接请求公开 GitHub 源，当时得到访问限流，结果保持检查失败而非最新。成功、失败、分页和取消行为由真实本地 HTTP/TCP 夹具覆盖；发布后的实际公开清单仍须再读取核实。

推送后由 `ci.yml` 执行完整 Gradle 与 Node/Gateway 契约门禁，由 `android-apk.yml` 和开发预览发布工作流生成并交叉核验真实新版 APK。正式版和编号测试版继续依赖仓库已有私有发行签名配置，本次不创建正式发布标签。

真机由用户手动验证：打开更新入口、切换渠道并重启确认保存、断网后重查、打开准确发布页、同签名新版覆盖及账号与本地记录保留。开发预览与私有发行签名之间的覆盖兼容性不能用检测结果代替。
