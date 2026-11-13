---
status: evidence-recorded
p0_status: unverified
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
integration_commit: 7bc02c5e14246d60c60a7c145f29e82a4b3f5f85
---

# Remote 原生依赖与 ABI 验证记录

本记录只登记目前能从仓库和本机依赖缓存确认的事实，不把依赖下载成功或 focused 测试通过解释为 Android/真机兼容完成。用户已明确本轮不进行真机测试。

## Noise

`apps/android/gateway-client/build.gradle.kts` 当前加入：

```kotlin
implementation("org.signal.forks:noise-java:0.1.1")
```

本机 Gradle 缓存中的固定输入（不属于仓库提交物）为：

| 文件 | SHA-256 | 来源 |
| --- | --- | --- |
| `noise-java-0.1.1.pom` | `4cac7f78a2c940aacb1d7ac7723f1040edab6c05b0c9cb7781a54cb63dfbe317` | `org.signal.forks:noise-java:0.1.1` |
| `noise-java-0.1.1.jar` | `2bbc531e5e31b3151269dbb7596548e3c884ded217ab6312c2d87591bfea543b` | `org.signal.forks:noise-java:0.1.1` |

POM 声明项目来源为 `signalapp/noise-java`、版本 tag `noise-java-0.1.1`，许可证字段为 MIT。仓库当前没有把该 POM/JAR 复制进源码或发布物；发布前仍需独立许可证清单、依赖锁定和 Android 产物审查。协议/Noise focused 测试已通过，但这只证明当前 JVM/Android unit test 组合的行为。

## WebRTC 候选

计划候选为 `io.github.webrtc-sdk:android:150.7871.04`。当前 `gateway-client` 未加入该运行依赖，仓库未记录其 POM/AAR SHA-256、传递依赖、许可证、ABI 清单或 16 KiB page-size 对齐结果。因此：

- WebRTC 选型状态：`UNVERIFIED`；没有把候选版本写入运行依赖。
- AAR/native ABI：`UNVERIFIED`；没有 arm64-v8a、armeabi-v7a、x86_64 产物清单或符号审查证据。
- Android 16 KiB page-size：`UNVERIFIED`；没有 Android 15+/16 KiB 环境加载、安装和运行证据。
- 真机、LAN/P2P/TURN/Relay：`BLOCKED`，等待用户提供专用手机、Host、origin 和后端环境。

## Android 兼容边界

当前根 Android 配置为 compileSdk 35；`gateway-client` 属于完整 APK 的 Android 12 runtime library，根配置因此给它 `minSdk=31`。这只是编译/打包配置事实，不等于 Noise 或未来 WebRTC 在所有 ABI、API、厂商系统和 16 KiB page-size 环境可运行。

P0 依赖门禁保持 `UNVERIFIED`。在取得 POM/AAR/许可证/ABI/16 KiB 与真实设备证据前，不得把 native transport 或 WebRTC 兼容性报告为已验证。
