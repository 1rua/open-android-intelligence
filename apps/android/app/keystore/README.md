# 固定 Debug 签名密钥

本目录存放**唯一**的调试签名密钥，供本机开发、CI 打包与 GitHub Release 共用。

## 为什么要固定

AGP 的默认行为是让每个调试包都用“本机随机生成”的调试密钥签名，密钥落在
`~/.android/debug.keystore`（或 `ANDROID_SDK_HOME` 指向的目录）。这带来两个后果：

1. 每台机器有各自的密钥，A 机器打的包不能覆盖 B 机器打的包；
2. CI（GitHub Actions）每次运行的 runner 都是全新的，会现生成一份新密钥，
   因此**同一份代码连续两次构建出来的 APK 签名都不同**。

上述任一情况都会让 `adb install -r` 直接失败：

```
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.openandroidintelligence.mobile
signatures do not match previously installed version; ignoring!]
```

只能先卸载旧包（本地登录态、插件与设置一并清空）再安装。把调试密钥固定在仓库后，
本机、CI 与发布产物签名完全一致，可以直接覆盖安装。

## 密钥参数

| 项目 | 值 |
| --- | --- |
| 文件 | `apps/android/app/keystore/debug.keystore`（PKCS12） |
| 别名 alias | `androiddebugkey` |
| 库口令 storePassword | `android` |
| 密钥口令 keyPassword | `android` |
| 有效期 | 10000 天 |
| 证书主体 | `CN=Android Debug, O=Android, C=US` |
| 证书 SHA-256 指纹 | 见 `debug.keystore.fingerprint` |

使用方式写在 `apps/android/app/build.gradle.kts` 的 `signingConfigs`（`debug` 配置）
与 `buildTypes.debug` 中，不再依赖任何机器本地生成的调试密钥。

## 安全边界

调试密钥的口令是公开约定值，属于“公开但不保密”的测试身份，可以入库；它的作用是
**保证签名一致**，不提供任何安全性。请勿将其用于：

- 正式发布包（release 构建）或应用商店上架；
- 任何需要真实身份或防篡改保证的分发。

正式发布签名密钥必须单独保管（CI Secrets / 本地密钥库），绝不入库。

## 校验

任何 APK 都可以用同一份指纹校验签名身份：

```sh
# 校验默认路径的调试包
apps/android/tools/verify-debug-signing.sh
# 校验指定 APK
apps/android/tools/verify-debug-signing.sh /path/to/app-full-debug.apk
```

CI 在构建后、上传/发布前会执行同一个脚本，签名与固定指纹不符时直接失败。
`tools/download-latest-apk` 下载完也会跑一次校验并给出提示。

## 重新生成（仅在确实需要更换密钥时）

```sh
keytool -genkeypair -v \
  -keystore apps/android/app/keystore/debug.keystore \
  -storetype PKCS12 -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"

keytool -list -v -keystore apps/android/app/keystore/debug.keystore \
  -storepass android -alias androiddebugkey | grep SHA256
```

生成后必须把新的 `SHA256` 指纹写入 `debug.keystore.fingerprint`，否则 CI 会拒绝该构建。
此外要清楚代价：更换密钥等于换了应用身份，所有已装设备都需要先卸载旧包。
