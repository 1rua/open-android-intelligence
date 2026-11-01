# R7 旧版本迁移验收准备：旧 APK 来源与隔离条件

日期：2026-11-01。范围：GLOBAL-02 为主，关联 GLOBAL-01、03 及 R7 身份、业务数据、系统权限保留要求。

**结论：已找到可作为按 Core/账号 grant 基线的真实 v2 App APK；尚未证明存在由旧 App 实际操作产生、可安全复用的旧授权样本，也未执行 v1/v2 → v4 真实覆盖升级。R7 保持未完成。** 当前只准备旧包和执行条件，不创建或启动第三个模拟器。

## 1. 本次边界与证据口径

- 在 `feature/global-device-authorization-20261101` 工作树只读核对，起始 HEAD 为 `ff720cde6b2057725925d24ea95403bb54a5f22c`；旧版源码比较固定为 `dev@3c9b82d1ef18062fc7dd09546efe6136803079cc`。
- 唯一写集是本文。未修改源码、运行状态、数据库或偏好设置，未操作任何设备，未运行 Gradle，未安装、卸载、清数据、提交或推送。
- 5554 的原 user0 含私人数据；同一设备多用户共享 APK 代码，因此增加测试用户不能隔离旧 APK 的覆盖影响。5556 的合成 user0 已有身份并用于其他验收，本轮同样不可覆盖、降级或清理。此处沿用用户给定的环境边界，没有重新访问设备确认状态。
- 本文的“已确认”是本地归档与源码事实；“已有运行记录”引用先前验收，不计作本次重新运行；“执行条件”是后续方案，不表示已部署。

需求依据：[规格 GLOBAL-02 与历史授权清理](../superpowers/specs/2026-11-01-global-device-authorization.md)、[R7 尚待验收项](2026-11-01-global-device-authorization-open-acceptance.zh-CN.md)、[既有验收矩阵](2026-11-01-global-device-authorization-terra.zh-CN.md)。

## 2. 四个归档 APK 的只读核验

归档根目录为 `docs/plans/2026-11-01-global-device-authorization/runtime/`。所有路径均相对本工作树；下面的链接相对本文。

实际使用已有的 `../../.toolchains/android-sdk/build-tools/35.0.0/aapt`、同目录 `apksigner` 和 `sha256sum` 核验；工具路径以本工作树根目录为基准。四包的 `aapt dump badging`、`aapt dump xmltree`、`apksigner verify --verbose --print-certs` 均退出 0；SHA-256 另以命令复核。包内 DEX 只在内存读取指定类的字段、常量和方法名，没有解压写盘，也没有执行 App。

四包共同属性：

| 项目 | 核验结果 |
| --- | --- |
| 包名 | `com.openandroidintelligence.mobile` |
| Manifest 版本 | `versionCode=1`，`versionName=2.2.1`；不能仅凭这两个值区分授权模型 |
| SDK / ABI | minSdk 31，targetSdk 35；含 `x86_64`、`x86`、`arm64-v8a`、`armeabi-v7a` |
| 构建类型 | 包内 `BuildConfig` 为 `full` / `debug`，渠道 `development`；Manifest 的 `debuggable=true` |
| 签名 | 四包均通过 APK Signature Scheme v3 验签，单一 signer，证书 SHA-256 相同 |
| 证书 SHA-256 | `37d9445fab11d827b032b84b0739acb2c33aa8ace1f319ed20a4cc612624ce33` |

证书摘要是公开签名标识，不是私钥。这里的 APK 签名 v3 与下表的授权模型 v3 是不同概念。

| 归档 APK | 字节数 | 包内 `BuildConfig.GIT_COMMIT` | 包内授权模型与持久化标识 | R7 用途 |
| --- | ---: | --- | --- | --- |
| [installed-apk-20261101/base.apk](../plans/2026-11-01-global-device-authorization/runtime/installed-apk-20261101/base.apk) | 72,701,367 | `99f41bf31b5896ac36fd931fea6c7d259da347af` | `CURRENT_AUTHORIZATION_MODEL=2`；旧 `prefix`、`load/save/clear`、`snapshotLegacyScopes` 方法及旧 scope 标记常量存在 | **唯一已确认的旧 v2 候选 App** |
| [apk-bc40483e2a6f/app-full-debug.apk](../plans/2026-11-01-global-device-authorization/runtime/apk-bc40483e2a6f/app-full-debug.apk) | 70,725,906 | `2e6a799ca5456125720e27353719ba898a6fc4dc` | 模型 **3**；`GLOBAL=installation_capability_policy_v3`，存在 `migrateInstallationPolicy` | 已是全局授权实现，不能作为旧按账号 grant 基线 |
| [apk-092c600ef79b/app-full-debug.apk](../plans/2026-11-01-global-device-authorization/runtime/apk-092c600ef79b/app-full-debug.apk) | 70,710,490 | 同上 `2e6a799c…` | 模型 **3**；同样存在全局策略常量与迁移方法 | 同上；目录名也不能替代源码来源证明 |
| [sms-emulator/ui-upgrade-1ec8841c-20261101/old-installed-app.apk](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/ui-upgrade-1ec8841c-20261101/old-installed-app.apk) | 73,483,105 | `1199eb700f25320750a99f79731846aab8c8750a` | 模型 **3**；存在全局策略与迁移方法 | 是既有 v3 → v4 链路的旧包，不是 v1/v2 旧授权包 |

APK 文件 SHA-256：

```text
installed-apk-20261101/base.apk
  d72906941d5aaf5becfb65e151076caef9f3cc38a4b444b0e5a4b470ed17fe48
apk-bc40483e2a6f/app-full-debug.apk
  bc40483e2a6fa9138c0cdce903855e515841816171c449de2d8106aa7b517387
apk-092c600ef79b/app-full-debug.apk
  092c600ef79b18cad454d6e8f221e3ee9f3fb751b0124de41230a0c1647406ab
sms-emulator/ui-upgrade-1ec8841c-20261101/old-installed-app.apk
  9b3216be2395fe8730ac173168c835be9f745dda84f74839ef859c2d07078dcb
```

### 2.1 APK 与源码对应关系的证据强度

1. **v2 候选：二进制模型证据强，两份关键源码一致。** `base.apk` 的实际 SHA 与[既有 Terra 归档记录](2026-11-01-global-device-authorization-terra.zh-CN.md)第 128 行一致，原记录说明它由当时已安装 App 拉取归档。包内提交 `99f41bf3…` 与固定基线 `3c9b82d1…` 的 `PairingGrantPersistence.kt`、`PairingGrantState.kt` 经 `git show` 逐字比较相同。两文件内容 SHA-256 分别为 `69ac3377f54e7a86a31f33598f0647b70761261999be527f025e4ad0a007e9a6`、`d7d63a35dc995bfae31dbf25482eb78c24d5c226d0eaac4d992873dee45c9e92`。因此可确认这是所需旧模型候选；没有完整构建时源码清单，不能据此宣称整包每个文件都精确等于 `dev@3c9b82d1`。
2. **`bc40483e…`：已有明确的未提交源码构建证据。** [pre-commit-provenance.json](../plans/2026-11-01-global-device-authorization/runtime/pre-commit-provenance.json) 的 `apk_sha256` 与实包相符，记录 `head=2e6a799c…`、`dirty=true`，并包含构建时文件摘要。其 `PairingGrantPersistence.kt` 摘要为 `4bfedd31d8e18a6ddfe49168be38e247be306c71c3ce52a7aec5ef0081dae9cc`，不同于该提交本身的旧 v2 文件。包内模型 3 与此相符。不能仅据 `BuildConfig.GIT_COMMIT` 将它归为 v2。
3. **`092c600e…`：二进制证明是 v3，完整构建来源不足。** 本次有限范围内没有找到与该 APK SHA 绑定的独立构建清单；包内提交相同不证明与上一包源码完全相同，也不影响其不能作为旧 v2 基线的判断。
4. **`old-installed-app.apk`：构建记录与文件身份可对应。** [apk-1199eb70.json](../plans/2026-11-01-global-device-authorization/runtime/apk-1199eb70.json) 的 commit、应用 APK SHA 和大小均与实包一致；对应提交源码及包内常量均为模型 3。[ui-scope-apk-source-manifest.json](../plans/2026-11-01-global-device-authorization/runtime/ui-scope-apk-source-manifest.json) 对应后续升级包 `1ec8841c…`，不是此旧包或 v2 候选的来源清单。

四个包均未检出本次检查的内嵌源码清单命名（`source-manifest`、`git.properties`、`build-info`、`commit`）。包内提交是来源线索，不能单独证明构建时无未提交改动。本次没有重新构建，也没有将来源推断写成可复现构建结论。

## 3. 与 `dev@3c9b82d1` 旧 v2 行为的比较

源码路径：[PairingGrantPersistence.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistence.kt)、[PairingGrantState.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)。链接打开当前工作树文件；下面的旧版行号必须用 `git show 3c9b82d1:<路径>` 读取，不能套用当前文件行号。

| 比较项 | 固定基线旧 v2 的源码事实 | 当前 v4 的源码事实与验收含义 |
| --- | --- | --- |
| 授权保存范围 | `PairingGrantState.kt:19–43`：`storageKey` 来自 Core URL、账号、安装 ID；`PairingGrantPersistence.kt:16–39,66`：按 `pairing_grant_v1.<storageKey>` 读取；模型常量在 `PairingGrantState.kt:80` 为 2 | 当前 binding 继续用于连接/请求归属，策略改为安装实例全局；不能删除账号或重建安装身份来代替迁移 |
| 旧版实际写入 | 旧 `PairingGrantPersistence.kt:42–55` 的 `save` 提交 revision、granted、screen_selection、authorization_model、disabled、legacy_projection_digest；`clear` 在 58–63 行保存撤销记录，不直接抹掉 scope | 应由旧 App 的正常功能操作产生这些键，之后仅只读证明其存在；直接写 DB/prefs 不属于真实旧用户样本 |
| 旧迁移与歧义保护 | 旧 `load:18–39` 识别无模型的 legacy scope，检测旧程序改写投影；`from/snapshotLegacyScopes:79–113` 记录安装模型和旧 scope；旧 `PairingGrantState.kt:196–200` 有本机复核入口 | v2 App 写出的正常 v2 状态不能冒充 v1 状态；本次四包没有确认到模型 v1 的真实旧 App |
| v1/v2 → v4 清理 | 旧版仍保留按 scope grant 与旧标记 | 当前 `PairingGrantPersistence.kt:75–116`：模型 0–2 路径汇总旧 disabled，revision 取最大值加 1，不导入旧正向 grant/后台授权，同一提交写入全局状态并移除旧前缀和旧 scope 标记；屏幕选择取旧值的保守交集 |
| v3 → v4 与幂等 | 不适用于旧按账号授权基线 | 当前 `PairingGrantPersistence.kt:78–95` 保留已有 v3 全局策略，提升版本屏障并清理残留旧键；不能用这一分支的已有 PASS 证明模型 0–2 的迁移分支实际运行 |
| 默认及失败处理 | 旧模型与当前全局默认不同 | 当前 [CapabilityAuthorization.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/CapabilityAuthorization.kt):8–17 定义新能力默认 MANUAL；持久化提交失败明确报错。真实升级仍需核对推送关闭、明确拒绝保留、无重复迁移和无旧请求继续执行，不能仅凭静态代码判 PASS |

[PairingGrantPersistenceTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistenceTest.kt):35–81、97–139 已有原子/幂等、提交失败恢复、未来模型拒绝、旧写入残留清理和 v3 保留测试的源码。它们使用人工准备的偏好设置和失败注入，属于自动化覆盖，不能替代旧 App 操作和覆盖安装记录。本轮只读这些测试源码，未运行测试。

## 4. 归档 runtime 是否已有真实旧样本

**有限范围内未证实有可直接用于 R7 的旧 v1/v2 授权样本。找到旧 App 与找到旧用户状态是两件事。**

- `installed-apk-20261101/` 只有归档 `base.apk`。APK 本身不含已安装实例的账号、grant、系统权限或 Android Keystore 身份。既有 Terra 记录还表明归档时 App 未安装插件，不能推断它已有非空旧授权状态。
- `sms-emulator/ui-upgrade-1ec8841c-20261101/` 的旧包实际是模型 3；已有 v3 → v4 的范围、队列和身份保留运行记录继续有效，但不补齐 v1/v2 分支。
- 上述目录的 `before-runtime.private.xml`、`after-runtime.private.xml` 都是 8 项运行身份偏好，存在安装 ID；没有授权模型键或 `pairing_grant_v1.*`。这不是 grant 偏好文件，不能据它们认定旧 grant 已迁移，也不能扩大推断设备里没有旧 grant。
- 对 runtime 归档目录仅作目录深度不超过 2 的文件名/结构核对，共 29 个目录；排除运行中的 Core、AVD/模拟器数据、state、home、cache、日志、UI、源码快照和派生产物。命中的 11 份归档 runtime/preferences XML 没有旧 grant 或全局策略键。未发现可追溯的旧 grant 偏好快照与旧 App 操作记录组合；结论只覆盖该有限范围，不声称已查遍 runtime 或设备。
- 不读取、展示或复制其中的账号、安装/设备/会话 ID、URL、令牌、主密钥和偏好值。5554 原 user0 是否保有旧样本不在本次读取范围内，不能取其私人状态作为补证捷径。

## 5. 最小隔离迁移方案与尚缺条件

**本轮采用“只准备旧 APK、明确缺部署环境”的交付方式。** 当前 5554、5556 均不作为此次旧版部署目标；未另建、启动、安装到任何 AVD 或实体设备。

| 条件 | 当前可确认状态 | 后续执行条件 |
| --- | --- | --- |
| v2 旧 App | 已有经过核包、验签、SHA 和关键源码比较的 `base.apk` | 直接使用该只读归档作为旧版输入，无需为准备工作重新构建 |
| v1 旧 App / 样本 | 四包内未确认到 | 如要求分别验收 v1、v2，仍需另有可追溯 v1 真实版本和正常操作链；不得由写 JSON/prefs 代造 |
| 完全隔离的安装空间 | 未建立或核实 | 后续有明确执行安排时，使用独立干净 AVD 或专用隔离设备。AVD 至少 API 31、ABI 与包匹配；不得复用现有 5554/5556，也不得用同一设备新用户隔离 APK 版本 |
| 旧版正常登录与能力操作 | 旧版源码有真实登录绑定、设置保存路径；本轮未运行 | 使用专用合成账号和兼容旧版的独立测试 Core；需要插件的场景还须核实旧版可安装的可信插件。TLS、登录、配对和插件兼容性必须实际验证，不能默认旧版能连接当前测试 Core |
| 待升级的新包 | 本轮只核验四个指定旧包，未选择最终 v4 目标 | 执行前固定待验 v4 APK 的 SHA、构建源码清单、包名、签名和版本条件；不得以当前工作树 HEAD 代替 APK 来源 |
| 保留与安全对照 | 已有迁移源码及既往 v3 → v4 记录 | 在独立环境由旧 App 真实操作产生身份、业务数据、非空旧 scope grant、明确拒绝和系统权限；升级前后只保存脱敏相等性、数量、版本和操作结果 |

后续真实执行的最小顺序；本轮均未执行：

1. 先确认独立设备/AVD 的归属及空白状态，再首次安装旧 v2 `base.apk`。隔离环境首次建立专用合成身份是样本准备；从旧版登录开始到覆盖升级结束，不清数据、不卸载、不替换密钥、不重建身份或配对。
2. 通过旧 App 正常登录、绑定、现有设置或插件入口生成旧 scope grant、明确拒绝及需要保留的业务数据，并通过系统界面取得所需权限。只读记录模型 2、旧键数量/结构、版本及操作证据；不能为了凑非空样本直接写文件。若登录、可信插件或旧操作入口不可用，记录该具体阻塞。
3. 固定新 v4 APK 后，在这个独立环境保留数据覆盖升级。验证旧 scope grant 和标记清理、新手动默认与推送关闭、明确拒绝未扩大、身份/登录/会话/附件/插件/系统权限及明确范围保留；同一安装再次启动验证幂等。旧敏感请求的失效须由独立测试请求的完整链证明，不能引用别的样本拼接结果。
4. 提交失败恢复目前只有已有自动化设计证据。若独立环境无法安全、真实地覆盖该故障，继续注明未测；不新增产品迁移 UI，不为通过验收关闭保护，也不以文件注入冒充 App 故障恢复。模拟器未提供或未验证的 SIM、真实运营商等能力保持未验收，物理设备结果单列。

签名相同只满足覆盖安装的一项条件，不证明部署环境安全、旧版能够正常工作或数据保留成功。四个归档版本号相同也不能作为授权模型迁移完成的依据。

## 6. 交付状态与本次验证

- **准备已完成**：已区分一个真实模型 v2 候选和三个模型 v3 包，记录 SHA、签名、包内提交标识、关键源码一致性及来源清单的局限。
- **真实验收尚未运行**：本轮没有旧版安装、旧 UI 样本生成、v1/v2 → v4 覆盖升级、升级后数据/权限保留、旧敏感请求失效、幂等重启或真实故障恢复。R7 维持未完成；v3 → v4 已有 PASS 的适用范围不扩大。
- **交付校验范围**：仅对本文执行相对链接存在性、代码围栏配对、必要证据与状态表述检查，以及 `git diff --check`。不运行 Gradle 或项目测试门禁；这些文档检查不计作产品迁移测试。
- 本文保留在仓库内，由用户统一提交；没有新增其他文件，没有提交或推送。
