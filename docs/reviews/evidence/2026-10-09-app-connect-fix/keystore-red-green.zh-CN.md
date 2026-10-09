# 真实 Android 密钥库回归证据

日期：2026-10-09，时区 Asia/Shanghai。使用本轮在线手机，通过 ADB shell 的独立身份运行生产 `AesGcmEncryptedBlobStore` 编译类。探针不安装 APK、不启动或停止用户 App，不读取 App 文件、密钥、账号、刷新凭据或数据库，不清除日志。

探针源码与可重复运行命令位于仓库：

- `apps/android/tools/AndroidKeystoreBlobProbe.java`
- `apps/android/tools/verify-keystore-blob.sh`

执行前需要针对本次源码运行 `encrypted-store` 的最小编译或测试任务，避免使用旧编译产物。探针本身不执行 Gradle 构建。

```bash
bash apps/android/tools/verify-keystore-blob.sh <在线 ADB 设备地址或序列号>
```

## 修复前：失败已复现

以原生产加密类执行，退出码为 `1`：

```text
平台约束：拒绝调用方指定的加密 IV（InvalidAlgorithmParameterException）
失败：EncryptedBlobCorrupted
```

探针生成只属于 shell 身份的随机测试密钥别名，启用 `setRandomizedEncryptionRequired(true)`，先确认系统拒绝调用方指定的 IV，再调用生产类 `writePlaintext`。生产类确实将该拒绝转换为 `EncryptedBlobCorrupted`。探针结束时只撤销自身刚创建的测试密钥，未接触用户 App 身份下的任何密钥；临时编译产物和设备探针文件保留以供复核。

Android 官方 API 明确说明：启用随机化加密要求时，GCM 加密拒绝调用方提供的 IV；应不传 IV 初始化 `Cipher`，再读取系统生成的 `Cipher.getIV()`。参考：[KeyGenParameterSpec.Builder.setRandomizedEncryptionRequired](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setRandomizedEncryptionRequired(boolean))。

## 修复后：同一手机验证通过

先通过本次定向 Gradle 测试重新编译生产加密模块；`compileDebugKotlin`、`bundleLibCompileToJarDebug` 与 `bundleLibRuntimeToJarDebug` 实际执行，没有使用构建缓存。使用更新后的编译类执行同一探针，退出码为 `0`：

```text
平台约束：拒绝调用方指定的加密 IV（InvalidAlgorithmParameterException）
通过：真实 AndroidKeyStore、生产加密类、独立解密、IV 随机性、原信封兼容与篡改拒绝
```

已经核验：生产类在真实 AndroidKeyStore 中写入与读取、独立平台解密核对、重复加密的 IV 不相同、原 V1 信封仍可读取、篡改信封被拒绝。

本轮无线 ADB 端口多次改变；先通过本地 `_adb-tls-connect._tcp` 服务发现取得当前端口，再在同一命令进程中连接并执行探针。使用 SDK 自带 ADB 和独立服务端口 `5038`，没有关闭原 ADB 服务。重复验证可按当前环境设置 `OAI_ADB` 与 `OAI_ADB_SERVER_PORT`，不需要重新配对手机。

本探针验证密钥库与生产加密原语；它不等同于对生产账号完成 App 登录和 Gateway 事件流的端到端验证。实际登录流程由对应的运行时回归测试验证，部署后的生产账号验证另行记录。
