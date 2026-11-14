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

## WebRTC 固定产物静态证据

已从旧协议工作区忽略的 `.worktrees/native-dependencies/` 只读复核已有缓存，没有重新下载 AAR。
固定输入为 `io.github.webrtc-sdk:android:150.7871.04`，发布来源为 Maven Central；AAR/POM 的本地 SHA-1
还与官方仓库同名 `.sha1` 响应逐一匹配。详细库摘要和 ELF 段结果见
[机器可读记录](2026-11-14-dsh-remote-native-dependency-evidence.json)。

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `android-150.7871.04.aar` | 49,068,339 字节 | `1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c` |
| `android-150.7871.04.pom` | 1,137 字节 | `c511dba5b204f6056530359f1d0a30a431a9c07946dbb73322db5ee4a1d71405` |

POM 没有声明传递依赖，许可证字段为 BSD-3-Clause。发布包装仓库 `webrtc-sdk/android` 的
`v150.7871.04` 标签固定到 `1d6a68a74162d3f218f9681d49c6b5eef8f02800`，仓库包装代码 LICENSE 为 MIT；
`Licenses/WEBRTC.md` 与 AAR manifest 则声明原生 WebRTC 的 BSD 许可及 PATENTS/AUTHORS 归属。
这两层许可不能合并成“所有内容均 MIT”。后续发布仍需收齐并分发原生与第三方 notices，当前并未将许可包装完成标为通过。

AAR 的文本 manifest 声明 `minSdkVersion=21`、`targetSdkVersion=23`；这不是本应用的目标 SDK 设置。
该 AAR 内含四种 ABI，各有一个 `libjingle_peerconnection_so.so`：

| ABI | 文件大小 | ELF PT_LOAD 对齐（全部 3 段） | 静态结论 |
| --- | --- | --- | --- |
| `arm64-v8a` | 12,266,048 字节 | `0x4000` | 64 位 LOAD 段为 16 KiB 对齐 |
| `x86_64` | 16,144,848 字节 | `0x4000` | 64 位 LOAD 段为 16 KiB 对齐 |
| `armeabi-v7a` | 6,791,820 字节 | `0x1000` | 32 位 LOAD 段为 4 KiB 对齐，不能宣称所有 ABI 同一结果 |
| `x86` | 12,798,344 字节 | `0x1000` | 32 位 LOAD 段为 4 KiB 对齐 |

静态检查通过 Python 标准库 `zipfile` 逐项读取 AAR，以 ELF class/endianness 解析 program header，
仅统计 `PT_LOAD.p_align`；没有抽取到生产目录或改变二进制。Android 官方 page-size 文档要求分别检查
ELF 和 APK ZIP 对齐，并运行 16 KiB 环境测试。因此这些结果只证明上述缓存 ELF 的静态段对齐，
不能证明最终 APK 的 ZIP 对齐、所有 native 代码假设或设备运行安全。

状态分层为：

- `VERIFIED`（静态）：固定 AAR/POM 的大小与摘要、官方 SHA-1 匹配、manifest 最低 API、四 ABI 与 ELF LOAD 段对齐。
- `VERIFIED`（来源声明）：固定包装 tag 的 MIT 与 WebRTC/POM 的 BSD 许可分层；不等于发布 notices 已完整收齐。
- `UNVERIFIED`（构建/运行）：APK 打包与 ZIP 对齐、native 加载、PeerConnection/DataChannel、Android API 31 与 16 KiB 环境运行。
- `BLOCKED`（专用环境）：真机、LAN/P2P/TURN/Relay、真实 Host 与后端互操作，等待用户稍后提供。

为实现已协商 PeerConnection 上的真实 DataChannel adapter，本阶段精确加入上述 WebRTC 运行依赖；
不改变 minSdk、ABI 过滤、用户界面或信令/穿透算法。适配层不得将测试替身接进生产装配。
`DataChannel.onMessage` 的 native buffer 在回调返回后不可继续引用，adapter 须在回调内复制；
发送失败和关闭/撤销必须如实反馈。API 行为仍需后续传输切片的针对性测试，不能由本报告代替。

## Android 兼容边界

当前根 library 配置为 compileSdk 35；`gateway-client` 属于完整 APK 的 Android 12 runtime library，
根配置因此给它 `minSdk=31`。这只是编译/打包配置事实，不等于 Noise 或 WebRTC 在所有 ABI、API、
厂商系统和 16 KiB page-size 环境可运行。

P0 整体仍被公开委派目标绑定阻塞；原生依赖的静态检查不能解除该阻塞，也不能把 native transport 或
WebRTC 运行兼容性报告为已验证。


## 随包通知与尚未闭合的来源映射

许可证原文现放在 `apps/android/gateway-client/src/main/assets/licenses/remote/`，沿用 Android library
默认 main asset 合并机制，覆盖 RTCH/串行发送的 DSH MIT、Noise MIT、WebRTC 包装 MIT，以及固定
包装仓库的 `Licenses/WEBRTC.md`（788,358 字节，包含 WebRTC BSD 和第三方 notices）。
来源与逐文件 SHA-256 见该目录 `sources.json`；不依赖不会进入 APK 的 `src/test/resources`。
未修改开源许可证界面及既有文案。

另外保存 WebRTC 官方 `branch-heads/7871` 在本次核查时固定的提交
`05e02da9d17f5844e84800fa524dd1eb57d7014c` 的 LICENSE、PATENTS、AUTHORS 原文。
该提交是官方 M150 源码证据，不把它伪称为发布 AAR 已确认的精确 native 构建提交。
精确 AAR→native 源码映射及最终 APK 资产/通知验证尚未完成，发布许可门禁保持 `UNVERIFIED`；
仅保存包装 POM 的 BSD 字段不足以解除这一缺口。
