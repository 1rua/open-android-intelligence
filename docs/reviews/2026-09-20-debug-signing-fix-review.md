# 固定 Debug 签名修复（f489903）独立代码审查报告

- **日期**：2026-09-20
- **被审提交**：`f489903` 修复: 固定 Debug 签名，消除本机与 CI 打包签名不一致导致的覆盖安装失败
- **父提交**：`39690d7`
- **审查方式**：只读审查（未修改任何源码/配置；为取证在本机离线执行了 Gradle 构建与校验脚本，副作用见第七节）
- **审查环境**：Linux + JDK 17.0.19 + Gradle 8.12（离线、复用 `.toolchains/gradle-home` 缓存）+ `.toolchains/android-sdk`（build-tools 35.0.0）

---

## 一、审查范围（仅本提交 diff）

| 文件 | 行数/类型 | 变更 |
|---|---|---|
| `.github/workflows/android-apk.yml` | `+42..47` | 构建后新增硬失败签名校验步骤（在 artifact 上传与 Release 发布之前） |
| `.github/workflows/nightly-release.yml` | `+40..45` | 同上（在 release notes / tag / 发布之前） |
| `README.md` | `189..196` | 新增固定密钥与校验命令说明 |
| `apps/android/README.md` | `106..126` | 新增 "Debug signing" 章节 |
| `apps/android/app/build.gradle.kts` | `11..16`、`25..38`、`41..44` | `check()` 密钥存在性断言 + 覆写 `signingConfigs.debug` + 绑定 `buildTypes.debug` |
| `apps/android/app/keystore/README.md` | 新增 78 行 | 密钥参数、安全边界、校验与重建流程 |
| `apps/android/app/keystore/debug.keystore` | 新增 2650B 二进制 | PKCS12 调试密钥 |
| `apps/android/app/keystore/debug.keystore.fingerprint` | 新增 5 行 | 固定 SHA-256 指纹 |
| `apps/android/tools/verify-debug-signing.sh` | 新增 119 行 | 签名一致性校验脚本 |
| `tools/download-latest-apk` | `+177..185` | 下载后调用校验脚本（仅告警） |

---

## 二、结论速览

| 维度 | 结论 | 等级 |
|---|---|---|
| 1. 正确性 / 缺陷风险 | Gradle 侧签名配置正确且不依赖机器本地密钥（已实测 `signingReport` 与真实产物）；**校验脚本的 apksigner 回退分支在相对路径下失效**（P1） | 存在 1 项必须修 |
| 2. 安全性 | 入库的是口令公开的调试身份，**未夹带任何发布密钥**；release 变体完全不受影响（实测 `Config: null`）；指纹文件与密钥一致 | 可接受 |
| 3. 项目规范一致性 | 中文提交说明、相对路径、文档入库、不伪造实现、gitignore 均满足；密钥未被任何忽略规则命中 | 通过 |
| 4. 可验证性 | 校验脚本是**真门禁**（实测通过路径 exit 0、五类失败路径均 exit 1）；CI 步骤在 runner 上可真实跑通（v1 签名已由本次实测确认存在） | 通过（P1 修好后更稳） |

**总判断：可以接受（建议合并）。** 无阻断项；P1 属于本次新增脚本自身的功能缺陷，修复成本约 2 行，建议在合并前或紧随其后的提交中修掉，并按 P3 补上最小回归测试。

---

## 三、逐项结论与证据

### 3.1 正确性 / 缺陷风险

**(a) 所有 debug 变体都绑定仓库密钥，且不会回落到 `~/.android/debug.keystore` —— 已实测确认**

本机离线执行（等价于 CI 的配置阶段）：

```
./gradlew --offline :app:signingReport
```

输出（节选）：

```
Variant: fullDebug             Config: debug  Store: .../apps/android/app/keystore/debug.keystore  Alias: androiddebugkey
                               SHA-256: 37:D9:44:5F:AB:11:D8:27:B0:32:B8:4B:07:39:AC:B2:C3:3A:A8:AC:E1:F3:19:ED:20:A4:CC:61:26:24:CE:33
Variant: playDebug             Config: debug  Store: .../app/keystore/debug.keystore
Variant: fullDebugAndroidTest  Config: debug  Store: .../app/keystore/debug.keystore
Variant: playDebugAndroidTest  Config: debug  Store: .../app/keystore/debug.keystore
Variant: fullRelease           Config: null   Store: null
Variant: playRelease           Config: null   Store: null
```

- 四个 debug 变体（含评审清单点名的 `fullDebugAndroidTest` / `playDebugAndroidTest`）全部指向 `apps/android/app/keystore/debug.keystore`，指纹与仓库指纹文件逐位一致。
- AGP 只会在 debug 签名配置的 `storeFile` 未显式指定时才去创建/使用 `~/.android/debug.keystore`；`build.gradle.kts:27-37` 把 `storeFile/storePassword/keyAlias/keyPassword` 四项全部写死，回退路径被彻底闭合；`build.gradle.kts:12-14` 的 `check()` 又在配置期对密钥缺失做硬失败，避免"静默换身份"。
- `build.gradle.kts:41-44` 的 `buildTypes.debug.signingConfig = signingConfigs.getByName("debug")` 属于冗余绑定（AGP 默认已经这么绑），行为无害，但注释"避免回落到机器本地随机调试密钥"把原因归到了绑定上——真正的闭环在 `signingConfigs.debug` 的四项赋值。属表述问题，非缺陷。
- **真实产物验证**：`./gradlew --offline :app:assembleFullDebug` 与 `:app:assembleFullDebugAndroidTest` 均构建成功；两个 APK 都含 `META-INF/CERT.SF` + `CERT.RSA`（v1），AGP 记录 `{"enableV1Signing":true,"enableV2Signing":true,"enableV3Signing":true}`，校验脚本对两者均 exit 0。

**(b) 脚本的健壮性（shell 引用/退出码/管道/空值）——总体良好**

- `set -euo pipefail`（`:17`）；所有参数展开都加了引号；`normalize/prettify` 用 `printf '%s'` 而非 `echo`，无 `-n`/转义歧义。
- 管道处都做了 `|| true`（`:60`、`:66`、`:80`、`:99`），不会因 `grep|head` 的 SIGPIPE（141）在 `set -e` 下误判。
- 空值处理正确：读不出证书时走 `:103-105` 显式失败，不会把空串与指纹误判为"相等"。
- `exec env LANG=C.UTF-8 ...` 的 locale 自举（`:22-27`）确有现实必要性：本机在 `LANG=C` 下 `jarsigner`/`./gradlew` 都因仓库路径含中文而报 `keystore load: /mnt/??????...` 与 `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`；脚本在同样 `LANG=C` 的 shell 中调用时**仍能正确读到指纹**（实测）。
- 脚本在 git 中权限为 `100755`（可直接执行），且从 `BASH_SOURCE` 推导路径（`:29-31`），不受调用方 cwd（CI 在仓库根、README 在仓库根）影响。

**(c) 缺陷 P1：apksigner 回退分支在相对路径下失效**

`apps/android/tools/verify-debug-signing.sh:97-99`：

```bash
APK_CERT="$(normalize "$( (cd "${TMPDIR:-/tmp}" && env LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  "$APKSIGNER" verify --print-certs "$APK") 2>/dev/null \
  | awk -F': ' '/SHA-256 digest/ {print $2; exit}' || true)")"
```

子 shell 先 `cd` 到临时目录，而 `$APK`（`:37` 默认值或第一个参数）通常是**相对路径**——正是两个 workflow（`android-apk.yml:46-47`、`nightly-release.yml:44-45`）与两份 README（`README.md:194-195`、`apps/android/README.md:120`）使用的形式。相对路径在 `cd` 之后失效，回退分支静默拿不到证书，最终以 `:103-105` 的"**读不出 APK 的签名者证书……或找不到 apksigner**"收场——即"我已经按报错提示给了 `APKSIGNER` 却仍报找不到 apksigner"。

复现（本机实测，`$APKSIGNER` 指向真实存在的 build-tools 35.0.0/apksigner）：

| 调用 | 结果 |
|---|---|
| `APKSIGNER=<绝对路径> bash verify-debug-signing.sh apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`（相对路径） | exit 1，报"读不出 APK 的签名者证书"（**错误归因**） |
| `APKSIGNER=<绝对路径> bash verify-debug-signing.sh /mnt/.../app-full-debug.apk`（绝对路径） | exit 1，正确报"APK 签名与仓库固定调试密钥不一致：57:62:6D:9C:… vs 37:D9:44:5F:…" |

- **不构成假通过**（空证书仍走 fail-closed），当前 CI 主路径（v1 + keytool）也已实测跑通，因此**不阻断本次 CI**；但它使文档化的 `APKSIGNER` 逃生通道在文档化的调用方式下失效，且会把维护者引向错误方向（"以为 APK 没签名/没装 apksigner"）。
- 建议修法（2 行）：

```bash
APK="${1:-${ANDROID_DIR}/app/build/outputs/apk/full/debug/app-full-debug.apk}"
[ -f "$APK" ] || fail "找不到 APK：${APK}..."
# 统一成绝对路径：apksigner 回退分支会先 cd 到临时目录，相对路径届时失效。
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
```

  `tools/download-latest-apk:172` 已经有同款路径绝对化写法，改后风格也一致（该工具传的是绝对路径，所以自身未踩到该缺陷）。

**(d) 缺陷 P2（同类问题残留）：`:assistant-holder` 仍是"每机随机调试密钥"**

`apps/android/assistant-holder/build.gradle.kts:2` 也是 `com.android.application`（`applicationId = com.openandroidintelligence.assistant`，`:8`），但没有签名配置，其 debug APK 依旧用本机随机调试密钥。

- 现状风险评估：两个 APK 是**不同包名**，且当前两个 manifest 都**没有**自定义权限 / `sharedUserId` / `protectionLevel="signature"`（已 grep 确认），因此今天不会出现跨应用同签名约束被破坏。
- 残留影响：该模块调试包在"本机建一次、CI 建一次"时仍会复现同一句 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；而 `docs/superpowers/plans/2026-08-11-mvp-vertical-slice.md:249` 明确规划过 app 与 holder 之间的"signature IPC permission"，一旦落地，两把不同密钥会让该信任边界静默失效。另 `docs/superpowers/plans/2026-09-01-android-conversation-assistant-ui-full-refactor.md:1768` 已计划把 holder 改为 library（届时问题自然消失）。
- 建议：要么把调试签名配置抽成 `gradle/debug-signing.gradle.kts` 由两模块共用（顺带覆盖未来新模块），要么在计划做完 library 化之前立一个跟踪项并在 `apps/android/README.md:126` 后补一句"holder 未纳入固定签名"。

### 3.2 安全性

- **入库内容核查**：`keytool -list` 显示密钥库为 PKCS12、**仅 1 个条目** `androiddebugkey`（`PrivateKeyEntry`），`storepass/keypass = android`，证书主体 `CN=Android Debug, O=Android, C=US`，创建于 2026-09-20，有效期至 2054-02-05（=10000 天，与 `app/keystore/README.md:32` 一致），SHA-256 = `37:D9:44:5F:…` 与 `debug.keystore.fingerprint:5` **逐位一致**（同一次 `signingReport` 也复现了同一指纹）。**没有任何发布/上架密钥被夹带**（唯一条目就是调试身份；二进制内可读字符串无可疑身份信息）。
- **不会影响 release**：`fullRelease`/`playRelease` 实测 `Config: null`（无签名配置），release APK 仍是未签名产物，调试密钥不可能"顺带签了正式包"。`app/keystore/README.md:39-47` 也把边界写清楚了。
- **口径正确的既有生态实践**：口令公开的调试身份入库（AOSP 自带 debug 密钥、Flutter/RN 项目也提交 debug.keystore）属于标准做法，其价值是"身份稳定"，不提供任何防伪能力。README 明确写了"仅用于调试，禁止正式发布/上架"，`build.gradle.kts:10` 与 `apps/android/README.md:126` 也重复了该约束。
- **需要如实告知用户的取舍（非缺陷）**：密钥一旦公开，任何人都能构造出与已安装调试包**同签名**的更新包，Android 不会提示签名不匹配。相对修复前（每台机器/每次 CI 各自随机密钥，攻击者无法"静默覆盖"用户已装的包），这一步把"调试包身份"从私有变成了公开。缓解：调试/Nightly 包不作为正式分发渠道；不要把含登录态的调试包长期装在主力设备（`app/keystore/README.md:44-45` 已给出口径）。建议在 README 的 Security 段落补一句"调试包不提供任何真实性与防篡改保证，任何人可用同一公开密钥构造同名包"。
- **加固建议（P5）**：`.gitignore` 目前**没有**任何 `keystore/.jks` 规则（`git check-ignore` 对三个新文件均为"未忽略"，这是本提交能进 CI 的前提），但这也意味着未来有人误提交 `release.keystore`/`*.jks` 时没有兜底。建议加："`*.jks`、`*release*.keystore`、`*upload*key*`"，并显式注释调试密钥是唯一例外、理由指向 `apps/android/app/keystore/README.md`（若将来要写 `*.keystore` 宽规则，务必同时 `!apps/android/app/keystore/debug.keystore`）。另可在 CI 对任何 release 产物断言"指纹 ≠ 固定调试指纹"，把"release 永不用调试密钥"变成机器可查的约束。

### 3.3 项目规范一致性（AGENTS.md）

- **中文提交说明**：满足（`修复: …`，正文中文、问题/改动结构清晰，且所述"四把不同指纹（63053148…/44c3143b…/9DC2913E…/57626D9C…）"可部分复核——本机 `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk` 实测为 `57626D9C…`、`play/debug/app-play-debug.apk` 实测为 `9DC2913E…`，即**同一台机器上两个旧产物确实互为不同身份**，问题前提成立）。
- **相对路径**（AGENTS.md:34-38）：新增脚本全部从 `BASH_SOURCE` 推导路径（`verify-debug-signing.sh:29-31`、`download-latest-apk:14`），无硬编码项目绝对路径；仅 `verify-debug-signing.sh:88` 以 `${HOME}/Android/Sdk` 作为 SDK 探测惯例路径，属工具约定而非项目路径引用，可接受。
- **不得伪造实现 / 门禁必须真实**（AGENTS.md:42-48）：校验脚本不是"永远通过"的装饰——五条失败路径实测均 exit 1（不同密钥签名；无签名文件；文件不存在；`KEYSTORE` 被替换；apksigner 回退读不出证书），通过路径实测 exit 0（见 3.4）。
- **文档入库**（AGENTS.md:47）：`app/keystore/README.md` 覆盖参数表、安全边界、校验方式、重建流程与"换密钥=换身份"的代价说明；根 README 与 `apps/android/README.md` 同步更新。两份 README 的语言与本文件既有语言/根 README 既有语言分别保持一致（`apps/android/README.md` 全文英文，其内部路径写法沿用仓库根相对路径，与 `:63-64/:83-84` 既有风格一致）。
- **Git 忽略规则**（AGENTS.md:68-80）：`git check-ignore -v` 对 `debug.keystore`、`debug.keystore.fingerprint`、`README.md` 三者均**未命中任何规则**；仓库无 `.gitattributes`/无 LFS，故 CI `actions/checkout` 拿到的是真实密钥而非指针文件。`**/build/`、`*.apk` 等规则不受影响。唯一需要"正名"的是 AGENTS.md:73 把"密钥"列为必须忽略类别，而本次**有意**入库一个调试密钥——理由充分且已在同一提交的 README 中论证，建议在 `.gitignore` 就地加一行注释指路，避免后续审查者/子代理误判为违规。
- **既有门禁不受影响**：`noVpnSurfaceCheck`（`apps/android/gradle/mvp-forbidden-surfaces.gradle.kts:188/201`）只扫描 `kt/java/xml`，二进制密钥与 shell 脚本不在扫描范围；`npm run mvp:lock:check` 是依赖台账校验、与新增文件无关；`ci.yml:62` 的 `./gradlew check` 会触发 `app/build.gradle.kts:12` 的存在性断言，密钥已入库故通过。

### 3.4 可验证性（CI 能否真跑通 / 是否存在假守卫）

以下均为本机实测（JDK 17 + 已验证的 APK 产物；与 runner 的 temurin 17 同大版本）：

| 场景 | 期望 | 实测 |
|---|---|---|
| 相对路径 + 真实构建 APK（`assembleFullDebug` 产物） | 通过 | **exit 0**，打印 `37:D9:44:5F:…` 两侧一致 |
| `env LC_ALL=C.UTF-8 bash …`（与 workflow 完全一致的调用） | 通过 | **exit 0** |
| `assembleFullDebugAndroidTest` 产物 | 通过 | **exit 0**（同一指纹；v1 存在） |
| 用另一把密钥 v1 签名（`jarsigner` 临时密钥库） | 失败 | **exit 1**，打印两枚指纹（`4F:6B:E8:08:…` vs `37:D9:44:5F:…`） |
| 无签名随机文件 | 失败 | **exit 1**（"读不出 APK 的签名者证书"） |
| 文件不存在 | 失败 | **exit 1**（"找不到 APK"） |
| `KEYSTORE=` 指向被替换的密钥库 | 失败 | **exit 1**（"仓库内调试密钥与固定指纹不一致（密钥疑似被替换过）"） |
| 与仓库指纹文件比对 | 一致 | `keytool -list -v` / `signingReport` / 指纹文件三处同为 `37:D9:44:5F…` |

- **不是假守卫**：无论 keytool 路径还是 apksigner 路径，比较对象都是签名者证书 SHA-256 与仓库指纹，"通过"只可能意味着该 APK 确实由固定密钥签发；所有异常分支均 fail-closed。
- **CI 可行性的关键前提已被确认**：脚本首选的 `keytool -printcert -jarfile` 需要 v1(JAR) 签名，而本仓库 `minSdk = 34`（此时 AGP 默认**不加** v1，本机旧产物就证实了这一点：`META-INF` 下无 `CERT.RSA`，导致脚本首轮只能报"读不出证书"）。本提交显式 `enableV1Signing = true`（`build.gradle.kts:34`）后，实测新产物含 `META-INF/CERT.SF`/`CERT.RSA`，keytool 路径因此在 runner 上成立；即便未来 v1 被关掉，ubuntu-latest 自带 `ANDROID_SDK_ROOT` 下的 build-tools/apksigner，回退分支（**修好 P1 后**）仍可兜底。
- workflow 步骤本身无路径/locale 隐患：校验步骤刻意在**仓库根**执行（`android-apk.yml:42-47`、`nightly-release.yml:40-45`），APK 相对路径与产物实际位置一致（实测同名文件确实生成在 `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`），两条流水线都把校验放在上传/发布**之前**且无 `continue-on-error`，属硬门禁。
- 残留的可验证性缺口（P3）：新增脚本**没有自动化回归测试**。本次 P1 恰好是"文档化的 `APKSIGNER` 用法从未被真实验证"的直接产物。建议按仓库既有 python 静态测试惯例（`apps/android/tools/test_*.py`）或一个独立 shell 测试补 3 个用例：①临时密钥 + `FINGERPRINT_FILE` 同步 → exit 0；②换密钥 → exit 1；③**相对路径 + `APKSIGNER` 桩 → 必须报"不一致"而不是"读不出证书"**（③即本次缺陷的回归护栏）。
- 附带说明（P4，建议级）：脚本对 `keytool` 是硬依赖（`:58` 直接失败），apksigner 探测只看 `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`~/Android/Sdk`（`:85-88`），不读本仓库自己的 `apps/android/local.properties`（`sdk.dir=../../.toolchains/android-sdk`）也不看 `PATH`。本机明明装了 build-tools 35.0.0/apksigner，脚本却在未显式传 `APKSIGNER` 时完全找不到——建议补 `${ANDROID_SDK_ROOT}` 之外的 `command -v apksigner` 与 `local.properties` 探测。

---

## 四、问题清单（按严重程度）

### [必须修 P1] 校验脚本的 apksigner 回退在相对路径下失效，且报错归因错误

- **位置**：`apps/android/tools/verify-debug-signing.sh:97-99`（配合 `:37`、`:83-101`、`:103-105`）
- **分级理由**：fail-closed，不产生假通过、不阻断当前 CI 主路径；但它是本次新增功能本身的缺陷，"按报错提示设置 `APKSIGNER`"这一文档化补救手段在文档化的相对路径用法下无效，会长期误导维护者。修复成本 2 行。
- **修复**：见 3.1(c) 的绝对化片段；顺手把 `:103-105` 的提示改为区分"未签名 / 找不到 apksigner / 回退执行失败"三种原因。

### [应当修 P2] `:assistant-holder` 未纳入固定调试签名（同类缺陷残留）

- **位置**：`apps/android/assistant-holder/build.gradle.kts:1-10`
- **分级理由**：今天不影响两个发布流水线（CI 不构建该模块），也不影响跨应用签名约束（当前无 signature 级权限/sharedUserId）；但同一台机器/不同 CI 运行仍会打出不同身份，且与"未来 app↔holder signature IPC"的规划存在冲突。
- **修复**：抽公共脚本插件（两个 application 模块共用固定调试签名），或在 README 明确标注为已知缺口并挂跟踪项。

### [建议 P3] 为 `verify-debug-signing.sh` 补最小回归测试

- **位置**：`apps/android/tools/`（建议新增 `test_verify_debug_signing.py` 或 `test_verify_debug_signing.sh`）
- **理由**：P1 是"没有测试的对外契约（参数形式 + 退出码 + 关键错误分支）被静默破坏"的典型案例。

### [建议 P4] SDK/apksigner 探测与 keytool 硬依赖

- **位置**：`apps/android/tools/verify-debug-signing.sh:58`、`:85-93`
- **修复**：增加 `command -v apksigner` 与 `local.properties` 中 `sdk.dir`（相对路径）探测；把"必须 JDK"的报错信息与 CI 现实（runner 一定有 JDK，本地可能只有 Android Studio 的 JBR）对齐。

### [建议 P5] 密钥泄漏面收敛与"release 不得使用调试密钥"的机器化断言

- **位置**：`.gitignore`、CI
- **修复**：`.gitignore` 增加 `*.jks` / `*release*.keystore` / `*upload*key*` 并注释调试密钥例外（引 `app/keystore/README.md`）；CI 对任何 release/发布产物断言指纹 ≠ 固定调试指纹。

### [建议 P6] 文档一致性细节

- `README.md:189-196`：正文路径基准是 `apps/android`（`app/keystore/debug.keystore`），而命令块路径基准是仓库根（`apps/android/tools/…`）。建议在代码块前写明"在仓库根目录执行"。
- 两份 README 都未点明**一次性迁移代价**：已安装过旧调试包的设备（旧包由随机密钥或旧 CI 密钥签发）仍需先卸载一次；`app/keystore/README.md:77-78` 只讲了"将来换密钥"的代价。建议补一句，避免用户误以为"升级后立刻可以覆盖安装"。
- `apps/android/README.md:120` 的 `[path/to/app-full-debug.apk]` 是占位符写法，命令块可直接执行会歧义，建议与根 README 一样给出具体路径。

### [信息 P7] 安全取舍需对用户明示

- 公开调试密钥意味着"调试包身份可被任何人伪造"（详见 3.2）。这不是本提交引入的违背，而是本提交明确采纳的取舍；建议在 `app/keystore/README.md` 的安全边界段落补一句"不构成任何真实性保证"，并在 Nightly 发布说明中提示"调试包请勿作为不可信更新渠道"。

---

## 五、是否可以接受：**可以接受（建议合并）**

- 修复目标已达成且经实证：四个 debug 变体全部使用仓库固定密钥（不再回落本地随机密钥），真实构建产物可被校验脚本判定为固定身份；release 变体完全不受影响；指纹文件、README 参数与密钥本体三方一致。
- 安全性判断：入库对象是口令公开的调试身份，不是发布密钥；未夹带其他密钥；不存在影响 release 的路径。属于生态通行做法，且提交内已给出清晰的安全边界文档。
- 规范一致性：中文提交说明、相对路径、文档入库、真实门禁、gitignore 全部满足。
- 唯一需要动作的是 **P1**（2 行修复 + 建议的 P3 回归用例）；它不影响 CI 的拦截正确性，因此不构成"不可接受"，但按仓库"修改自测与完整流程验证"的规范，它正是"文档化用法未被真实验证"的欠账，建议尽快补齐。P2 建议作为独立后续任务处理。

---

## 六、本次审查的副作用与可复现证据

**副作用（未触碰任何被跟踪文件）**：

- 在 `apps/android` 离线执行了 `:app:signingReport`、`:app:assembleFullDebug`、`:app:assembleFullDebugAndroidTest`，因此 `apps/android/app/build/**`（gitignore）中的 APK 已被本提交的新配置重新生成（含签名）；`.toolchains/gradle-home` 缓存有更新。
- 在 `/tmp/审查测试/` 下创建了临时夹具：`签名/app-full-debug.apk`（用仓库密钥 jarsigner 签名）、`签名/other.apk`（另一把临时密钥签名）、`other.keystore`、`unsigned.apk`。均为仓库外临时文件，可随时人工清理。
- 本报告文件：`docs/superpowers/reviews/2026-09-20-debug-signing-fix-review.md`。

**可复现的关键命令**：

```bash
# 1) 四个 debug 变体与两个 release 变体的签名配置
cd apps/android && ./gradlew --offline :app:signingReport

# 2) 真实产物 + 与 workflow 一致的调用
./gradlew --offline :app:assembleFullDebug
cd .. && env LC_ALL=C.UTF-8 bash apps/android/tools/verify-debug-signing.sh \
  apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk    # 期望 exit 0

# 3) P1 复现（$APKSIGNER 指向任一 build-tools/apksigner）
APKSIGNER=$PWD/.toolchains/android-sdk/build-tools/35.0.0/apksigner \
  bash apps/android/tools/verify-debug-signing.sh apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk
#   → exit 1 且报"读不出 APK 的签名者证书"（错误归因；根因见 P1）
# 换绝对路径再跑 → exit 1，报"签名与仓库固定调试密钥不一致"（正确）
```

**未覆盖事项**：未执行真机/模拟器安装验证（无设备），因此"覆盖安装成功"这一点只验证到"签名身份一致"这一必要前提；已知父提交上 `apps/android/tools` 的 2 + 9 个 python 用例失败与本提交无关（本提交未触碰任何 python 文件）。

---

## 七、后续处理（本次审查之后）

审查提出的问题已在紧随其后的提交中处理：

| 编号 | 处理 | 复核证据 |
|---|---|---|
| P1：校验脚本 apksigner 回退分支在相对路径下失效 | 已修：脚本把 APK / KEYSTORE / 指纹文件统一转成绝对路径（`abs_path`），并把"读不出签名者证书"的提示拆成"APK 读不出"与"环境缺校验工具"两种可操作原因 | 相对路径 + 强制 `APKSIGNER` 回退用例（`signed-v2only.apk`，无 v1 签名）exit 0 |
| P2：`:assistant-holder` 未纳入固定调试签名 | 已修：固定签名配置从 `app/build.gradle.kts` 收敛到根 `apps/android/build.gradle.kts` 的 `subprojects` 回调，所有 `com.android.application` 模块共用同一密钥；app 模块内重复配置已原子删除 | `./gradlew --offline :assistant-holder:signingReport` → `debug` 与 `debugAndroidTest` 均指向 `app/keystore/debug.keystore`，指纹 `37:D9:44:5F…` |
| 表述问题：`buildTypes.debug` 绑定注释的归因不准 | 已修：注释改为说明真正闭合回退路径的是 `signingConfigs.debug` 的四项赋值 | `apps/android/build.gradle.kts` |

本机真实产物复核：`:app:assembleFullDebug` 产出的 `app-full-debug.apk` 经校验脚本判定为固定身份
（`37:D9:44:5F:AB:11:D8:27:B0:32:B8:4B:07:39:AC:B2:C3:3A:A8:AC:E1:F3:19:ED:20:A4:CC:61:26:24:CE:33`），
说明"配置 → 真实构建 → 校验"链路已在本机闭环。
