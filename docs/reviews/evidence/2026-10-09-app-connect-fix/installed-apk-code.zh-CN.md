# 当前手机已安装 APK 的加密代码核对

日期：2026-10-09，时区 Asia/Shanghai。只读读取手机当前安装的 `com.openandroidintelligence.mobile` 的 `base.apk`，没有安装、卸载、启动、停止或清除 App。

- APK 大小：`61,568,257` 字节。
- APK SHA-256：`6d358a31e0fcc3ccf8a8e7a9ea555f00896e934af38d3a393fc2a0119b6d9e23`。
- APK ZIP 结构检查通过。
- 使用 SDK 的 `apkanalyzer dex code` 检查实际 APK 字节码；没有用仓库版本号推测手机上的实现。

可重复核对命令，`OAI_INSTALLED_APK` 指向只读提取的 APK：

```bash
.toolchains/android-sdk/cmdline-tools/latest/bin/apkanalyzer dex code \
  --class com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStore \
  --method 'writePlaintext([B)V' "$OAI_INSTALLED_APK"

.toolchains/android-sdk/cmdline-tools/latest/bin/apkanalyzer dex code \
  --class com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider \
  --method 'getOrCreate()Ljavax/crypto/SecretKey;' "$OAI_INSTALLED_APK"
```

`writePlaintext` 的实际字节码依次执行：

1. 分配 12 字节 IV，调用 `SecureRandom.nextBytes`。
2. 构造 `GCMParameterSpec(128, iv)`。
3. 调用 `Cipher.init(int, Key, AlgorithmParameterSpec)`，明确将自行生成的 IV 传入加密初始化。
4. 将 `GeneralSecurityException` 转换为 `EncryptedBlobCorrupted`。

`AndroidKeystoreOutboxKeyProvider.getOrCreate` 的实际字节码明确调用 `KeyGenParameterSpec.Builder.setRandomizedEncryptionRequired(true)`。

因此，当前安装 APK 确实同时包含“密钥要求系统生成加密 IV”和“生产写入类传调用方 IV”的冲突实现。结合真实手机密钥库失败复现与实际 `GatewayRuntime` 登录调用链失败测试，可以确认这处本地初始化缺陷；不能将它误报成账号密码错误、TCP 不通或 SSE 已被服务端拒绝。

APK 和完整反编译输出只保留在独立临时目录，未作为构建产物提交到仓库。本文件仅记录可复查的非秘密代码摘要。
