# 附件上传媒体类型与 Android 默认头修复记录

日期：2026-10-12。用户在安装 Android `f82fc33` 后反馈，本机和服务器 Gateway 的附件仍无法上传，界面显示“无法上传，请移除后重新选择 / 附件完整性校验失败”。用户另已确认侧边栏退出和真实消息回复恢复；消息流式回复由同轮独立工作继续处理。

## 根因与原修复的边界

本轮已通过真实 Android 平台 HTTP 实现和 Hermes 生产 HTTP 入口复现新的附件断点。`AttachmentUploader` 为原始字节 PUT 提供了 `Content-Length` 和 `Digest`，却未提供创建附件时声明的 `Content-Type`。Android 的 `HttpURLConnectionImpl` 在允许携带正文的请求上自动补入 `application/x-www-form-urlencoded`。Hermes 将这一实际请求头与附件创建记录中的 `mediaType` 对照，不一致时拒绝请求。

具体调用链：

1. `GatewayAttachmentDraftCoordinator.uploadStaged` 使用本地加密暂存记录中的实际长度、SHA-256 和可重开解密流创建 `GatewayRequestBody`。
2. `AttachmentUploader.upload` 创建附件时传入所选文件的媒体类型，上传时却遗漏对应头。
3. `GatewayTransport.writeStreamBody` 将已经解密的同一正文写入 Android 原生 HTTP 连接；平台默认媒体类型随请求发送。
4. Hermes `Attachments.begin_content_upload_stream` 比较媒体类型、声明长度和摘要。媒体类型不一致同样返回稳定码 `ATTACHMENT_DIGEST_MISMATCH`。
5. Android 的错误信封解析保留 `ATTACHMENT_UPLOAD_FAILED:ATTACHMENT_DIGEST_MISMATCH`。协调器将包含 `DIGEST_MISMATCH` 的异常置为终态，界面将其展示为“附件完整性校验失败”。

因此，截图中的“完整性”文案不能单独证明正文 SHA-256 错误。新回归证明：正文、长度和 SHA-256 完全正确时，自动补入的错误媒体类型仍可触发这个提示。

此前 `InvalidAlgorithmParameterException` 的修复仍有效：每块加密由提供者生成 IV，原 V1 格式、AAD、标签和解密方式保留。本轮没有改变加密代码。读流也已有 `verifiedEof` 防护，重复读取 EOF 不会再次计算已消费的摘要；真实上传不依赖 `available` 或 `mark`。

## 一手来源与独立差分

[AOSP 的 HttpURLConnectionImpl 源码](https://android.googlesource.com/platform/external/okhttp/+/bad0a11146d43955d3f3b949aa277f0dd7cc3abb/okhttp-urlconnection/src/main/java/com/squareup/okhttp/internal/huc/HttpURLConnectionImpl.java) 明确规定：允许请求正文且尚无 `Content-Type` 时补入表单媒体类型。本机缓存的 Android 15 平台实现也经 `javap -private -c` 核对，存在同一分支。平台制品版本为 `15-robolectric-12650502-i7`，对应 SDK 35。

独立 Java 差分使用这一实际平台类，在回环 Socket 上发送相同的三个合成字节。请求一省略媒体类型，请求二只增加 `Content-Type: image/png`，实际收到：

```text
attempt=0 contentType=application/x-www-form-urlencoded bodyLength=3
response=200
attempt=1 contentType=image/png bodyLength=3
response=200
```

该差分只核对实际线上头和正文长度。协议拒绝及上传成功由下述真实 Gateway 回归负责。差分源码及原始输出分别保存在 [PlatformHeaderProbe.java](evidence/2026-10-12-attachment-streaming/PlatformHeaderProbe.java) 和 [android-platform-default-header-diff.txt](evidence/2026-10-12-attachment-streaming/android-platform-default-header-diff.txt)。从仓库根目录可使用相对路径定位平台缓存并运行：

```bash
platform_jar=$(rg --files --hidden --no-ignore apps/android/.gradle/robolectric-home/.m2 | rg '/15-robolectric-12650502-i7/android-all-instrumented-15-robolectric-12650502-i7.jar$')
probe_output=$(mktemp -d /tmp/oai-attachment-header-probe-XXXXXX)
javac -cp "$platform_jar" -d "$probe_output" docs/reviews/evidence/2026-10-12-attachment-streaming/PlatformHeaderProbe.java
java -cp "$probe_output:$platform_jar" PlatformHeaderProbe
```

平台缓存由使用 SDK 35 的 Robolectric 回归准备；这些缓存路径和临时输出仅服务于复现，不成为生产代码依赖。

## 实施

- `AttachmentUploader` 为内容上传显式提供 `Content-Type = SelectedAttachment.mediaType`，与同次创建记录保持一致。
- `GatewayConnectionFactory` 增加可选连接创建函数。生产默认仍调用 `URL.openConnection`，统一的协议、超时和禁止重定向策略仍由工厂设置。测试通过这一入口选择 Android 实际连接类，避免修改全局 URL 工厂。
- 新增 `AndroidAttachmentContentTypeTest`，使用实际 Android 网络类、生产 `GatewayTransport`、签名客户端、附件上传器和独立 Hermes 的生产流式入口。测试夹具的账号、密钥和服务在临时目录隔离，未操作用户 Gateway 账号。
- 在既有 `AttachmentUploaderTest.contentRequestCarriesContentLengthAndDigest` 中增加媒体类型断言。

本轮不修改核心 Schema 或附件加密格式，不需要密钥、账号、配对或密文迁移。

## 修复前后验证

先保持原上传器并执行新增类，得到 **4 项测试、3 项失败、0 错误、0 跳过**。跨块 JPEG、PDF 和空文件三项均抛出 `ATTACHMENT_UPLOAD_FAILED:ATTACHMENT_DIGEST_MISMATCH`；默认头负对照通过，证明相同正确字节只补媒体类型即可提交成功。原始 Gradle 记录为 [RED 日志](evidence/2026-10-12-attachment-streaming/android-platform-content-type-red.txt)，当时从 JUnit XML 读取的稳定码摘录见 [RED 详情](evidence/2026-10-12-attachment-streaming/android-platform-content-type-red-details.txt)。

生产上传器补充媒体类型后，以下 **29 项不同测试全部通过**：

| 测试类 | 项数 | 证明范围 |
|---|---:|---|
| `AndroidAttachmentContentTypeTest` | 4 | Android 平台默认头负对照，以及跨块 JPEG、PDF、空文件经过加密暂存、生产 HTTP 上传、提交校验和状态查询 |
| `AttachmentKeystoreRegressionTest` | 7 | 软件提供者模拟不可导出密钥和随机 IV 约束，独立读取、V1 兼容、范围隔离、篡改与截断拒绝 |
| `AttachmentStreamEndToEndTest` | 1 | 1、25、50 MiB 等级的合成内容经过加密暂存及真实 Socket 流上传，长度和摘要一致 |
| `AttachmentUploaderTest` | 9 | 上传顺序、已有状态、稳定尝试、长度、摘要、媒体类型与失败传播 |
| `EncryptedAttachmentStagingStoreTest` | 8 | 分块暂存、空文件、元数据、失败边界与清理 |

三个对应测试任务均实际执行，无 `FROM-CACHE` 或测试任务 `UP-TO-DATE`。关闭构建缓存、使用独立 Python 3.11，并显式指定独立插件源码与本仓契约；未运行完整 `check` 或 APK 构建。[GREEN 原始日志](evidence/2026-10-12-attachment-streaming/android-platform-content-type-green.txt) 和 [29 项结果详情](evidence/2026-10-12-attachment-streaming/android-platform-content-type-green-details.txt) 保留完整证据。

随后以 `OAI_REQUIRE_NATIVE_HERMES=1` 重新执行新增四项，加载真实 Hermes 宿主修订 `75b083e9399115b12513385252811f09c3503c90`，全部通过、无跳过。独立插件源码为 `746550a`，程序与运行修订 `d7ec5fb` 相同。该次测试任务真实重新执行，记录见 [原生宿主 GREEN 日志](evidence/2026-10-12-attachment-streaming/android-platform-content-type-native-green.txt) 和 [原生宿主结果详情](evidence/2026-10-12-attachment-streaming/android-platform-content-type-native-green-details.txt)。这四项重复执行用于证明宿主装载，不再累加进 29 项不同测试数。

定向验证命令的任务部分如下，插件和 Python 来源需显式设置为待核对的独立源码与环境：

```bash
./gradlew --offline --no-daemon \
  :app:testPlayReleaseUnitTest --tests '*AndroidAttachmentContentTypeTest' --tests '*AttachmentKeystoreRegressionTest' --tests '*AttachmentStreamEndToEndTest' \
  :gateway-client:testReleaseUnitTest --tests '*AttachmentUploaderTest' \
  :encrypted-store:testReleaseUnitTest --tests '*EncryptedAttachmentStagingStoreTest' \
  --no-build-cache --console=plain
```

## 证据边界与后续验收

这些测试已证明本轮媒体类型断点及源码修复，包含 Android 实际网络实现和真实 Hermes HTTP 入口。密钥约束测试使用软件提供者，不能当作本次手机硬件密钥库证据。隔离夹具的确定性 Agent 也不代表用户账号的真实模型验收。

完整 CI、对应签名 APK，以及新 APK 在本机与 `47.251.8.196` 上的选择附件、上传、发送和真实 Agent 接收结果由同轮交付继续记录。新 APK 实机反馈到达前，附件问题的手机闭环保持待验收状态。
