# App 更新发布元数据 v1

App 自身的版本检测使用 GitHub Release；它与设备插件的作者签名更新协议分别演进。发布流程不得收集账号、Gateway 地址、对话、附件或设备标识。

## 检测渠道与发布来源

- 正式版由 `main` 上合并的 PR 发布，Tag 为包版本生成的 `vX.Y.Z`，GitHub Release 的 `prerelease` 为 `false`，并设置为 latest。
- 开发预览版由 `dev` 上合并的 PR 或 `dev` push 发布，Tag 为 `vX.Y.Z-dev.<workflow run_number>`，GitHub Release 的 `prerelease` 为 `true`，并且不会成为 latest。
- 历史 `nightly` Release 仍可由旧版 App 更新检测读取；新的开发预览工作流不再创建或移动固定 `nightly` Tag。
- 正式发布说明直接取合并 PR 正文。手动正式发布会查找当前提交关联的已合并 main PR；找不到时停止发布。开发预览由 PR 触发时使用 PR 正文，push 触发时使用最新提交说明。

正式版使用不可调试的 `FullRelease` 和私有发行签名；开发预览版使用 `FullDebug` 和仓库固定的公开调试签名。签名不一致时 Android 无法原地覆盖安装，更新界面必须明确呈现这个限制。发布流程不卸载现有 App、不修改现有账号和设备数据。

## 构建版本与更新顺序

`apps/android/app/version.properties` 中的 `versionName` 是当前 Android App 包的唯一 `X.Y.Z` 来源，Gradle 从该文件设置 APK 的 `versionName`。`apps/android/tools/extract_package_version.py` 根据应用配置格式提取严格的 `X.Y.Z`；它也支持 Node/RN/Electron 的 `package.json`、Flutter `pubspec.yaml`、Gradle `versionName`、iOS `Info.plist` 与 Rust/Tauri `Cargo.toml`。正式和开发 Tag 均由提取出的包版本拼成，工作流不从 Git Tag 推导或进位 X.Y.Z。

开发预览 APK 的 `versionName` 为 `<包版本>-dev.<workflow run_number>`，正式 APK 的 `versionName` 就是包版本。重复运行产生不同的开发预览 Tag 与 Release，但同一提交的 `versionCode` 不变，因此同一源码的重复包不会被 App 更新检测当作可升级版本。

`versionCode = 完整 Git 历史提交数 × 10 + 渠道阶段`，其中开发构建为 `0`、开发预览为 `1`、正式版为 `2`。例如同一源码提交的开发版、开发预览版、正式版安装代码分别为 `3970`、`3971`、`3972`，下一源码提交的开发版为 `3980`。发布必须使用完整 Git 历史，浅克隆会立即失败。版本代码必须为 `1..2100000000` 范围内整数。

自动顺序适用于保留祖先历史的主线发布。发布维护分支时必须确认其历史提交数生成的版本代码高于需要被覆盖的旧安装；版本号用于展示和渠道识别，不能让名称更大的版本绕过较低安装代码造成降级。

构建前脚本 `apps/android/tools/app_release_metadata.py prepare` 调用包版本提取器生成内部构建信息，并设置 `OAI_VERSION_NAME`、`OAI_VERSION_CODE`、`OAI_UPDATE_CHANNEL`、`OAI_GIT_COMMIT`。Gradle 将这些值写入真实 APK 版本字段及 `BuildConfig.UPDATE_CHANNEL`、`BuildConfig.GIT_COMMIT`。本地构建仍由 Gradle 使用包配置版本和本地构建规则；正式发布工作流则使用同一提取器结果。

## 发布资源

每个支持更新检测的 Release 必须同时附带完整 APK 与 UTF-8 JSON 资源 `oai-update.json`：

```json
{
  "schemaVersion": 1,
  "versionName": "2.2.0-beta.1",
  "versionCode": 3971,
  "commit": "1234567890abcdef1234567890abcdef12345678",
  "channel": "preview",
  "applicationId": "com.openandroidintelligence.mobile",
  "distribution": "full",
  "assetName": "app-full-release.apk",
  "signingCertificateSha256": "abababababababababababababababababababababababababababababababab"
}
```

- `schemaVersion`：整数 `1`；未知格式停止解析。
- `versionName`：真实 APK 的可读版本字符串。
- `versionCode`：真实 APK 的整数安装版本代码；只有高于本机安装的代码才构成可升级候选。
- `commit`：完整四十位小写 Git SHA，标识构建源码。
- `channel`：`development`、`preview` 或 `stable`。
- `applicationId`：真实 APK 包名。
- `distribution`：`full` 或 `play`。当前流水线仅发布 `full`，不能将其当作 `play` 原地升级候选。
- `assetName`：同一 GitHub Release 中完整 APK 资源的精确文件名。
- `signingCertificateSha256`：真实 APK 当前签名证书的六十四位小写 SHA-256。

构建后脚本对照 AGP 的 `output-metadata.json` 核实包名、版本名、安装版本代码与 APK 文件名，再使用 Android SDK 的 `aapt2 dump badging` 从 APK 的二进制 Manifest 读取真实包名和版本，并执行 `apksigner verify --print-certs` 验证签名和提取证书。字段不一致、旧 APK 与新构建信息错配、签名无效、多个当前签名者、拆分 APK 或缺失 APK 均停止发布。资源 URL 由同一 Release 的 GitHub 资源列表获取，清单不提供任意下载地址。

旧 Release 没有此清单时，无法证明 APK 的安装版本代码和签名兼容性，不得仅根据标签名、发布页名称或发布时间将它宣称为可原地覆盖的新版本。

## 工作流与发布顺序

`.github/workflows/dev-release.yml` 只处理 `dev` 分支，使用 `dev-release` 并发组且不取消进行中的构建；允许多个发布运行排队，避免等待中的运行被新 push 替换。GitHub Actions 对每个并发组最多保留 100 个等待运行，超过上限时平台会取消新的等待运行。PR 合并还会产生 push 事件，工作流会识别并跳过这类重复 push，只让合并事件发布一次；直接 push 仍会发布。通知包签名在独立 job 中完成，APK 构建只下载签好的 `.alp`，不会接触原始插件签名密钥。`.github/workflows/release.yml` 只处理合并到 `main` 的 PR 或 main 上的手动触发。若正式 Tag/Release 已存在，工作流将 Tag 移到当前发布提交、替换同名资产并将 Release 转为正式 latest。`.github/workflows/android-apk.yml` 仅手动生成供下载的 Actions 构建产物，不发布 GitHub Release。

开发 Tag 携带运行编号，因此每个工作流运行都能创建独立发布资源。正式发布前会核对远端 main 仍指向本次构建提交，避免旧的排队构建覆盖较新的正式包。更新检测按清单中的 `versionCode` 判断是否可升级，不依赖 Release 发布时间。

## 最小验证命令

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s apps/android/tools -p 'test_app_release_metadata.py'
```

该测试覆盖真实仓库的构建前 CLI、三类渠道、包版本解析、版本代码顺序、非法版本、浅历史拒绝、APK 信息错配、签名验证失败和分支工作流守卫。完整 APK 的生成、真实 APK 签名验证及元数据交叉检查仍须由发布流水线完成，不能用脚本单测替代。
