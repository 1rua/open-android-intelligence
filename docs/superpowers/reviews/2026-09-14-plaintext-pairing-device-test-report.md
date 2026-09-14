# 明文配对（ADR 0047）真机/模拟器测试报告

日期：2026-09-14
范围：`apps/android` 的配对链路（登录/会话恢复/传输层）与设置页安全标注，对应 ADR 0047「允许使用明文 HTTP 完成 Gateway 配对，并如实标注未加密连接」。

## 1. 测试目标

1. 明文 `http://` 地址能通过配对门禁并真正发起连接尝试（不再被 HTTPS 强制拦下）。
2. 已固定 TLS 指纹的身份**不能**被降级到明文；HTTPS 仍必须协商出可用身份。
3. 界面如实呈现安全等级：明文持续告警、不出现「TLS 已固定」等失真标注。
4. 其他安全机制（SPKI 指纹核验、跳转不跟随、超时上限、Keystore 凭据、插件受控网络代理）保持原状。

## 2. 环境信息

| 项 | 值 |
| --- | --- |
| 主机 | Linux 7.1.6-1-cachyos，12 vCPU，shell = fish |
| JDK | OpenJDK 17.0.19 |
| Gradle/AGP | 仓库自带 `./gradlew`（AGP 8.9.2） |
| Android SDK | `<repo>/.toolchains/android-sdk` |
| adb | Android Debug Bridge 1.0.41 |
| 目标设备 | AVD `medium_phone`（emulator-5554），API 36，x86_64，`google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys` |
| 被测产物 | `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`（含 arm64-v8a/armeabi-v7a/x86/x86_64 原生库） |
| 基线提交 | `54463a0` |

> 说明：本次无物理真机（`adb devices` 为空，项目常用真机 `R52X909R9QT` 未连接），因此改用 x86_64 模拟器。模拟器与真机在 `usesCleartextTraffic`、TLS 信任库、`HttpURLConnection` 实现上同源，但**不覆盖** Keystore 硬件后端；Keystore 相关验证仍建议在真机补跑。

## 3. 执行步骤（可复现）

```bash
# 0) Gradle 环境（仓库路径含中文、系统 LANG=C，必须显式给 UTF-8，否则 JVM 报 Could not find or load main class）
cd <repo>/apps/android
env LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 \
    ANDROID_HOME=<repo>/.toolchains/android-sdk \
    GRADLE_USER_HOME=<repo>/.toolchains/gradle-home \
    ANDROID_USER_HOME=<repo>/.toolchains/android-user-home \
    ./gradlew <task> --console=plain

# 1) 启动模拟器（android CLI 需要 UTF-8，且需在非中文路径下执行，否则 JVM 因路径编码直接崩溃）
cd /tmp
env LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 \
    ANDROID_HOME=<repo>/.toolchains/android-sdk nohup android emulator start --cold medium_phone &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

# 2) JVM 单测
./gradlew :gateway-client:testDebugUnitTest :conversation-ui:testDebugUnitTest \
          :platform-kernel:testDebugUnitTest :app:testFullDebugUnitTest

# 3) 设备插桩测试
./gradlew :gateway-client:connectedDebugAndroidTest
./gradlew :platform-kernel:connectedDebugAndroidTest
./gradlew :app:connectedFullDebugAndroidTest

# 4) 界面实测（android CLI）
android install --apks=app/build/outputs/apk/full/debug/app-full-debug.apk --device=emulator-5554
adb shell am start -n com.openandroidintelligence.mobile/.MainActivity
android layout --device emulator-5554 --pretty -o /tmp/oai-login-layout.json
adb shell input tap 540 900                       # 聚焦「网关地址」
adb shell input keyevent 123                       # MOVE_END
adb shell input keyevent 67 67 67 67 67 67 67 67   # 删除预填的 https://
adb shell input text 'http://127.0.0.1:1'
android layout --device emulator-5554 --pretty -o /tmp/oai-login-http.json
```

## 4. 结果汇总

### 4.1 设备插桩测试（emulator-5554 / API 36 / x86_64）

| 模块 | 用例数 | 失败 | 错误 | 跳过 | 结果 |
| --- | --- | --- | --- | --- | --- |
| `gateway-client` | 15 | 0 | 0 | 0 | 通过 |
| `platform-kernel` | 7 | 0 | 0 | 0 | 通过 |
| `app`（full flavor） | 9 | 0 | 0 | 0 | 通过 |

结果 XML：`apps/android/<module>/build/outputs/androidTest-results/connected/.../TEST-*.xml`

### 4.2 JVM 单测（本次新增用例已覆盖）

- `gateway-client`：新增 `GatewayTransportSecurityTest` 13 例、`AccountProfileTest` 5 例，全部通过。
- 其中 `aPlaintextRequestReachesALoopbackGatewayAndReturnsItsResponse` 为**真实回环 socket 端到端**：通过真实 `GatewayTransport` 把请求体 POST 到 `127.0.0.1` 的明文 HTTP 服务并校验响应体，证明明文链路真的可用。
- 其余模块（`conversation-ui`、`platform-kernel`、`app`）BUILD SUCCESSFUL。

### 4.3 界面实测（android CLI + UI 树证据）

| 场景 | 输入 | 预期 | 实测（`android layout` 文本节点） | 结果 |
| --- | --- | --- | --- | --- |
| 明文地址 | `http://127.0.0.1:1` | 地址被接受，且出现未加密警告 | 字段值 `http://127.0.0.1:1`；出现「未加密连接（HTTP）：账号口令、消息与附件内容在传输途中可被读取或篡改，且无法核验 Gateway 身份。请仅在你信任的本机或局域网内使用，正式部署请改用 https://。」 | 通过 |
| HTTPS 对照 | `https://gateway.example.com` | 不出现未加密警告 | 未加密警告节点数 = 0；字段值 `https://gateway.example.com` | 通过 |
| 非法 scheme | `ftp://gateway.example.com` | 显示地址格式提示，且不发起请求 | 出现「请输入包含主机名的网关地址（http:// 或 https://）」 | 通过 |

## 5. 发现的问题、日志与修复

### P-1 模拟器上安装失败：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`

```
ErrorName: INSTALL_FAILED_UPDATE_INCOMPATIBLE
Failed to commit install session 320789855 with command package install-commit 320789855.
Error: INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.openandroidintelligence.mobile
signatures do not match newer version; ignoring!
> Task :app:connectedFullDebugAndroidTest FAILED
```

- 原因：该 AVD 上残留了**用另一套 debug keystore 签名**的同名包（历史会话安装），本次构建使用 `ANDROID_USER_HOME=<repo>/.toolchains/android-user-home` 的 keystore，签名不匹配。
- 处理：`adb uninstall com.openandroidintelligence.mobile`（`pm list packages -u` 确认残留状态）后重跑，安装成功。
- 结论：环境问题，非产品缺陷。复现时若遇到，先清理残留包即可。

### P-2 新增插桩用例污染共享 runtime 状态（真实缺陷，已修）

```
com.openandroidintelligence.mobile.CoreWithoutPluginsInstrumentedTest >
  freshInstallStartsWithAnHonestDisconnectedRuntime[medium_phone(AVD) - 16] FAILED
java.lang.AssertionError: expected:<Disconnected> but was:<Negotiating>
  at ...CoreWithoutPluginsInstrumentedTest.freshInstallStartsWithAnHonestDisconnectedRuntime(CoreWithoutPluginsInstrumentedTest.kt:36)
```

- 原因：新用例 `plainHttpPassesThePairingGate` 调用 `runtime.login("http://127.0.0.1:1", …)` 后只断言 `Negotiating` 就结束，未等待该次连接尝试结束；`resetFailure()` 只清理 `Failed`，未完成协程随后仍会写状态，导致后续用例读到 `Negotiating`。这是**测试相互污染**，不是被测代码行为错误。
- 修复：用例改为 `plainHttpPassesThePairingGateAndFailsOnTheConnectionInstead`，等待该次尝试 settle 成 `Failed`（上限 10 s，轮询 50 ms），并断言失败原因**不是** `AUTH_INVALID:url-scheme-required`（即确实通过了 scheme 门禁、失败发生在真实网络连接上），最后再 `resetFailure()`。修复后该套 9 例全绿。

### P-3 android CLI 在中文路径下崩溃（环境问题）

```
java.nio.file.InvalidPathException ... Malformed input or input contains unmappable characters: /mnt/??????/??????/open-android-intelligence
    at com.android.cli.ui.Context.<init>(Context.kt:38)
```

- 原因：`android` CLI 的 JVM 默认编码非 UTF-8，无法处理仓库中文路径。
- 处理：`cd /tmp` 并以 `LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` 运行。
- 结论：环境问题；已在 §3 的复现命令中固化。

## 6. 未覆盖与后续计划

1. **物理真机未跑**：`adb devices` 为空。建议在 `R52X909R9QT`（SM-X710/API 36）上补跑 `:gateway-client:connectedDebugAndroidTest` 与 `:app:connectedFullDebugAndroidTest`，覆盖 Keystore 硬件后端与真机平台网络策略。
2. **明文链路的真机端到端未跑**：本次端到端证据来自 JVM 回环 socket（真实 socket、真实传输实现），模拟器侧只验证了界面与平台开关断言，未在设备上起一个真实明文 Gateway 完成配对。后续可在模拟器内用 `adb reverse`/宿主明文服务补一轮真实配对。
3. **SQLite/事件流等旁路未受影响**：本次未改，沿用既有门禁基线（`apps/android/tools` 静态门禁 88 例中 9 例既存失败，与本次无关）。
4. **文档一致性**：ADR 0047 已明确取代 `2026-09-03-live-device-e2e-test-report.md` 中 D-004「默认只允许 HTTPS」的处置方向，其余要求（持续警告、身份如实呈现）继续有效；契约 §1、总规格 §4.4/§8、`CONTEXT.md` 已同步。
