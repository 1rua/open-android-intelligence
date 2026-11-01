# Terra Android 全局授权运行验收

日期：2026-11-01。状态：部分真实模拟器运行已通过，完整矩阵未完成；物理真机未连接。
当前已通过TLS/登录CoreA、通话系统权限拒绝与允许、手动拒绝与超时；跨Core和新布局阶段结果见后文，不能概括为全矩阵通过。
执行模型：`gpt-6.1-terra`，由主流程指定；必须以 Android CLI/ADB 与 OAI CLI/MCP 实际调用验证。
矩阵依据[规格第十章](../superpowers/specs/2026-11-01-global-device-authorization.md)。

本次预检基线为 `dev@3c9b82d1ef18062fc7dd09546efe6136803079cc`，报告所在工作分支为 `feature/global-device-authorization-20261101`。预检只读取环境和设备状态；没有安装、启动或卸载 App，没有修改设备权限，没有查询短信或通知正文，也没有调用 Core 的设备能力。模拟器的 ADB 连接授权不等于 Android 系统权限或 App 本地授权。

## 环境清单

| 项目 | 状态 | 实测结果及边界 |
| --- | --- | --- |
| SDK / ADB | PASS | `adb version` 为 `1.0.41`，工具版本为 `37.0.0-android-tools`。主检出目录的 Android 本地配置指向仓库内 SDK，存在 Android API 36 平台；功能工作树的构建环境未验证。未执行构建。 |
| Android CLI | PASS | 沙箱内 `android --version` 因锁文件所在文件系统只读而失败；按环境权限要求在沙箱外重试同一命令成功，版本为 `1.0.16261425`。没有执行更新或安装。 |
| 模拟器连接 | PASS | ADB 发现一台 `device` 状态的模拟器，型号 `sdk_gphone64_x86_64`，Android 15 / API 35，`ro.kernel.qemu=1`。设备序列号不写入本报告。 |
| 实体手机连接 | BLOCKED | 本次 ADB 清单中没有已授权的实体手机。模拟器预检不构成实体手机验收。 |
| 模拟器现装 App | PASS | `com.openandroidintelligence.mobile`，`versionName=2.2.1`，`versionCode=1`，`minSdk=31`，`targetSdk=35`，存在 `DEBUGGABLE` 标记。安装、启动及新 APK 兼容性未测试。 |
| 模拟器权限状态 | PASS | 当前 Android 用户为 `0`；`READ_CALL_LOG=false`，`READ_SMS=false`，`POST_NOTIFICATIONS=true`；本 App 的通知监听服务和无障碍服务均未启用。这些是预检时的状态，不是授权测试通过结论。 |
| Core CLI / MCP 入口 | PASS | 本机 `oai` 帮助列出 `devices`、`capabilities`、`invoke`、`status`、`cancel`、`ack`、`mcp`；已安装的本地 Core 客户端包为 `0.2.0`，MCP Python SDK 可用。该包版本不代表待测 Core 服务版本。 |
| 测试 Core 连通性 | PENDING | 未启动测试 Core，也未使用现存默认凭据；实际 CLI/MCP 登录、设备发现、能力发现和调用均待测。项目侧准备隔离 Core A/B、测试账号及调用配置，不要求用户提供可由项目建立的环境。 |
| 新 APK / 运行矩阵 | PENDING | 等待新构建后再开始；需先记录来源提交、APK 哈希、签名、版本及安装兼容性。不得为安装测试卸载现有 App 或清除其数据。 |

`oai invoke --help` 当前未显示独立的 Agent 名称和操作原因选项；这只是现装 CLI 的帮助输出，不代表待测版本已支持或不支持完整契约。

## 实际预检命令与结果

以下命令确已执行。初次设备发现使用现有 ADB 服务的只读 `host:version`、`host:devices-l` 协议；下列普通 ADB 命令是随后对同一模拟器的复核。`-e` 选择模拟器；若未来存在多台模拟器，须先明确目标，不能把结果归到错误设备。

```bash
git status --short --branch
git log -1 --format='%H%n%cs%n%s'
adb version
ss -ltn '( sport = :5037 )'
android --version
adb devices -l
adb -e shell getprop ro.product.model
adb -e shell getprop ro.build.version.release
adb -e shell getprop ro.build.version.sdk
adb -e shell getprop ro.kernel.qemu
adb -e shell am get-current-user
adb -e shell dumpsys package com.openandroidintelligence.mobile | rg 'versionCode=|versionName=|pkgFlags='
adb -e shell dumpsys package com.openandroidintelligence.mobile | rg 'User 0:|android\.permission\.(READ_CALL_LOG|READ_SMS|POST_NOTIFICATIONS): granted='
adb -e shell settings get secure enabled_notification_listeners | awk -F: '{for (i = 1; i <= NF; i++) if ($i ~ /^com\.openandroidintelligence\.mobile\//) present=1} END {print present ? "enabled" : "disabled"}'
adb -e shell settings get secure enabled_accessibility_services | awk -F: '{for (i = 1; i <= NF; i++) if ($i ~ /^com\.openandroidintelligence\.mobile\//) present=1} END {print present ? "enabled" : "disabled"}'
env PYTHONDONTWRITEBYTECODE=1 oai --help
env PYTHONDONTWRITEBYTECODE=1 oai invoke --help
env PYTHONDONTWRITEBYTECODE=1 oai mcp --help
env PYTHONDONTWRITEBYTECODE=1 ~/.oai-core/.venv/bin/python -c 'import importlib.metadata as m, importlib.util; print("OAI Core package version=" + m.version("open-android-intelligence-core")); print("MCP Python SDK=" + ("available" if importlib.util.find_spec("mcp") else "not available"))'
```

关键输出：ADB 设备状态为 `device`；Android 版本 `15`、API `35`；App 版本 `2.2.1`、`DEBUGGABLE`；两项特殊服务均输出 `disabled`。`android --version` 在默认沙箱中失败，沙箱外重试输出 `1.0.16261425`。`oai` 命令仅查看帮助和本地包元数据，未读取或输出任何凭据。

## 完整运行矩阵

`PENDING` 表示尚未执行，不等于通过；运行后根据实际步骤、结果和条件改记 `PASS`、`FAIL` 或 `BLOCKED`。模拟器和实体手机独立记录。当前实体手机尚未连接，物理矩阵全部BLOCKED；模拟器已运行若干分项，但未完成的整行仍标PENDING并说明已通过子项。

| 验收项目 | 模拟器 | 实体手机 | 运行时检查 |
| --- | --- | --- | --- |
| 通话记录 | PENDING（系统拒绝/允许、App拒绝和超时PASS；读取与模式待测） | BLOCKED | 系统读取权限、三级授权、允许与拒绝；只使用专用测试记录。 |
| 短信被动查询 | PENDING | BLOCKED | 系统权限、查询范围、三级授权与拒绝；不读取真实个人短信。 |
| 短信主动推送 | PENDING | BLOCKED | 默认关闭、范围与接收端点、独立于被动查询授权。 |
| 通知查询与推送 | PENDING（OS通知双端投递/正文/逐端ACK PASS；被动范围与撤销待测） | BLOCKED | 真实通知监听设置、测试应用来源范围、测试标题及正文、推送授权。 |
| 屏幕读取与截图 | PENDING | BLOCKED | 读取和截图范围、系统 MediaProjection 同意及撤销；只展示专用测试内容。 |
| 屏幕控制 | PENDING | BLOCKED | 真实无障碍设置、点击与滑动、只读禁止控制、独占锁、FIFO、租约与过期。 |
| 三级授权切换 | PENDING | BLOCKED | 每次手动、允许只读和自动允许，写入或控制操作不得错误继承只读许可。 |
| 临时授权 | PENDING | BLOCKED | 单次、5、30、60 分钟；范围约束、撤销、取消、到期和重启后不延长。 |
| Core 切换与离线配置 | PENDING（A→B→A设置保持、B取消PASS；离线管理待测） | BLOCKED | 同一 App 切换 Core A/B，授权模式和总开关不变，离线仍可管理，恢复后无旧 grant 阻断。 |
| 跨账号与多 Agent | PENDING（双端独立凭据/403、未认证401 PASS；临时共享待测） | BLOCKED | 使用合法设备访问关系；验证旧账号级 grant 不拦截、未授权访问仍拒绝、临时授权与端点白名单行为正确。 |
| 加密离线队列 | PENDING（2/4→2/3→1/2逐端ACK PASS；断网/恢复/到期待测） | BLOCKED | 断网补发、每端点状态、事件 ID 去重、ACK、过期、容量限制、端点移除和撤销。 |
| 后台与登录生命周期 | PENDING | BLOCKED | 后台、息屏、网络断开恢复、进程回收、全部退出登录、重新登录；不假定强行停止后可自动运行。 |
| 迁移和请求竞争 | PENDING | BLOCKED | 旧状态清理后的默认模式、恢复幂等、执行前及敏感结果交付前的授权复核，取消和重试不重复执行。 |

## 后续执行与证据

1. 项目侧准备隔离 Core A/B、测试账号和稳定接收端点；核对设备与 Core 之间的合法绑定，不输出凭据。测试账号不得代替系统授权，也不得扩大其他账号的会话或历史访问范围。
2. 接到新 APK 后核对版本、哈希和签名；在不清除现有账号、密钥、历史和插件数据的前提下选择可安全安装的测试设备。旧版迁移场景使用专门准备的测试环境。
3. 准备专用通话记录、短信、测试应用通知和测试屏幕；系统特殊授权通过真实系统界面操作，不使用 `pm grant` 等命令冒充用户。实体短信和通话结论只依据实际实体设备数据，模拟器注入不能冒充实体手机证据。
4. CLI/MCP 先发现实际能力和参数，再提交真实请求，关联 `requestId`、本地确认、最终状态与 ACK；测试重试、拒绝和撤销时分别核对设备侧及 Core 侧结果。ADB 仅用于测试驱动和观察，不作为普通 Agent 拥有的权限。
5. 逐项记录实际命令、Android/App/Core 版本、步骤、结果、脱敏截图、必要的受控日志及复测记录。长时授权和队列到期按真实时间验证；不能完成的项目如实保留未通过状态。

未经新 APK 和实际请求验证，不得将以上矩阵标记为通过；模拟器、实体手机、自动化测试和 Core 调用证据必须分列。

## 隔离 Core 准备记录

2026-11-01，按后续授权完成离线环境准备，说明及待执行的启动命令见[隔离 Core 运行准备](../plans/2026-11-01-global-device-authorization/isolated-cores.zh-CN.md)。本次仅修改了模拟器的 ADB 反向端口映射，没有改变 Android 权限或安装包。

| 项目 | 状态 | 可复核结果 |
| --- | --- | --- |
| Core A/B 数据和账号 | PASS | 使用已提交的 `2e6a799ca5456125720e27353719ba898a6fc4dc` 快照离线创建；两套独立主密钥、各两个专用账号，随机口令互不相同，离线摘要核验通过。该快照不是未来启动的待测服务版本。 |
| 私密数据和样本定义 | PASS | `runtime/` 已忽略；运行目录权限 `700`，口令、主密钥和私钥权限 `600`。合成样本仅为定义，尚未注入或读取设备。 |
| 测试 TLS | PASS | 分别验证两套独立 CA 和证书；正确 CA 的本地内存握手成功，错误 CA 和主机名失败。初版证书缺少严格校验扩展，已保留并改用 `tls/v2/`；未验证 Android 对测试 CA 的信任。 |
| 模拟器反向端口 | PASS | `adb -s emulator-5554 reverse --list` 显示 `tcp:19841 -> tcp:19841`、`tcp:19842 -> tcp:19842`；执行前无已有映射，使用 `--no-rebind`。 |
| Core 服务 / 真实链路 | PENDING | `19841`、`19842` 当前没有监听者。尚未启动服务，尚未验证最新源码与离线账号库的兼容、Android TLS 信任、登录、配对、CLI/MCP 或设备请求。 |
| 实体手机 | BLOCKED | 本次没有实体手机；上述端口映射和证书测试均不能代替实体手机验收。 |

实际执行的准备入口为 `prepare-isolated-cores.py prepare`、`reverse --serial emulator-5554` 和 `test-prepare-isolated-cores.py -v`；后者 10 项通过，属于本机工具和 TLS 检查，不是 Android 业务运行通过。端口映射为本次授权后的设备状态变更，与前文只读预检阶段区分。

## 运行准备更新

2026-11-01 06:15 左右（Asia/Shanghai）复核：当前工作树的 HEAD 为 `2e6a799ca5456125720e27353719ba898a6fc4dc`，但 Android 和 Core 有并行修改，不能用该提交号单独标识待测源码。隔离环境的 `prepare-isolated-cores.py verify` 通过：四个专用账号、两套主密钥和 TLS 证书仍可离线核验；`runtime/` 仍被 Git 忽略，凭据文件权限为 `600`。模拟器的两条 ADB 反向端口映射仍存在，本机 `19841`、`19842` 均无监听。生产 Core/API 尚在定稿，未启动测试服务。

主 Agent 阶段报告最新 App 编译和一次单测已通过。本次没有重新构建，也没有取得该次命令退出码；功能工作树 `apps/android/app/build/outputs/` 下当时只有 `logs/`，尚未发现 APK。`app/build/test-results/testFullDebugUnitTest/` 在本次核对期间被并行任务更新：06:06:18 的六个 XML 合计 44 项、2 项失败；06:14:50 更新后的六个 XML 合计 44 项、0 失败、0 错误、0 跳过；06:18:23 又被更新为两个 XML，共 9 项、0 失败、0 错误、0 跳过。不能据此推断全部模块通过，也不能把旧失败作为当前结果；具体命令和源码范围以构建执行方记录为准。

### 模拟器系统权限界面

在现有 Android 15 / API 35 模拟器和已安装的 OAI `2.2.1` 上，通过系统应用详情进入“权限”页面，实际查看了通话记录和短信详情：二者均显示允许与不允许选项，当前选择“不允许”。通知监听特殊访问页面和 OAI 无障碍服务详情页均可进入，开关均为关闭。交互前后再次读取的状态一致：`READ_CALL_LOG=false`、`READ_SMS=false`、`POST_NOTIFICATIONS=true`，通知监听及无障碍服务均未启用；没有切换权限、采集短信/通知正文或执行设备能力请求。

只含系统设置的截图保存在已忽略的本地目录：

- [通知监听关闭](../plans/2026-11-01-global-device-authorization/runtime/evidence/system-permissions-20261101/notification-listener-off.png)
- [无障碍服务关闭](../plans/2026-11-01-global-device-authorization/runtime/evidence/system-permissions-20261101/accessibility-off.png)

实际入口使用 `adb -s emulator-5554 shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.openandroidintelligence.mobile`、`android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` 和 `android.settings.ACCESSIBILITY_SETTINGS`；使用 `android layout --device=emulator-5554 --flat --pretty` 确认页面后才点击入口。Android CLI 首次读取布局时自动在模拟器安装了辅助包 `com.android.cli.interact.instrumentation`；它不是 OAI 安装包，也未被卸载或用于代替用户授权。上述 UI 检查只证明旧 App 的系统入口和当前状态，不代表新版 App 的权限矩阵通过。

### 快速接续

1. 保持现有账号、证书和映射；Core/API 定稿后，按[隔离 Core 运行准备](../plans/2026-11-01-global-device-authorization/isolated-cores.zh-CN.md)复核端口、启动指定源码，并记录进程归属、版本和实际 TLS 握手。现有测试 CA 能否被 Android App 信任仍需实测，不得关闭验证。
2. 待确定可安装的新版 APK 后核对来源、哈希、版本和签名，不卸载现有 App 或清除数据；再通过真实系统界面分别测试允许、拒绝及撤销，期间只使用专用样本。
3. 配对隔离 Core 后再从 CLI/MCP 发现实际能力并运行完整矩阵；保存 `requestId` 与脱敏证据。实体手机仍未连接，模拟器结果不得替代实体手机结论。

## 双 Core 启动及插件来源

2026-11-01 按后续授权启动 A/B，使用同一份包含当前未提交改动的源码快照：源码摘要 `b7944927c56bfb549dd1bcbd82af8d2d7288130afc9278b47da16695fcd29df0`，HEAD `2e6a799ca5456125720e27353719ba898a6fc4dc`。Core A PID `1568268`、端口 `19841`；Core B PID `1568269`、端口 `19842`，均只监听 `127.0.0.1`。各自 CA 下 HTTPS 请求均返回 `401 AUTHENTICATION_REQUIRED`；使用 A 的 CA 访问 B 返回证书验证错误。独立服务来源、配置、日志和重启约束见[隔离 Core 运行准备](../plans/2026-11-01-global-device-authorization/isolated-cores.zh-CN.md)。沙箱内第一次尝试的进程在命令结束后被回收；本轮服务经沙箱外重试并核对进程命令行，不使用首次 PID。

模拟器现装 `com.openandroidintelligence.mobile` 的 APK 通过 `adb shell pm path`、`adb pull` 保存至忽略目录，其 SHA-256 为 `d72906941d5aaf5becfb65e151076caef9f3cc38a4b444b0e5a4b470ed17fe48`。`apksigner verify` 验证单一 v3 签名，仓库 `verify-debug-signing.sh` 证实其证书与固定 debug 签名一致；仍需核对新版 APK 自身的签名与版本，才能判断实际覆盖安装。现装 APK 中没有 `assets/bundled-plugins/*.alp` 或其他 `.alp` 资产；现装 App 插件管理页显示“未安装任何插件”，[截图](../plans/2026-11-01-global-device-authorization/runtime/evidence/system-permissions-20261101/installed-plugins-empty.png)。没有卸载、清除数据或生成替代插件作者密钥。

随后从成功的开发发布任务 `41219517875` 的 `signed-plugins-1` 产物恢复六个原有签名包，来源提交 `3c9b82d1ef18062fc7dd09546efe6136803079cc`。本地核对六包 Ed25519 签名、作者一致性、文件摘要、插件 ID，清单和 schema 与当前工作树一致；六个包内 WASM 与当前本地构建输出不同，运行兼容性待测。以 Gradle 的 `:app` 项目目录为基准的 `OAI_BUNDLED_PLUGINS_DIR` 是 `../../../docs/plans/2026-11-01-global-device-authorization/runtime/official-signed-plugins-41219517875`。该来源是 CI 签名产物，**不是**从现装 APK 提取。

本机插件检查脚本及其五项测试覆盖完整包、篡改签名、篡改载荷、缺包和相对路径，均通过；源码快照的清单摘要、双配置隔离及禁止覆盖三项测试也通过。它们不替代 Android `AlpVerifier` 安装验证或模拟器业务验收。未进行实体手机验收。

## 接续状态

本次复核时，隔离 Core A/B 的 HTTPS 设备列表接口仍分别返回 `401 AUTHENTICATION_REQUIRED`，模拟器的两个反向端口映射仍在。工作树的 `oai-core/src/oai_core/gateway/core.py` 已与运行快照不同；本次没有重启服务，运行结果仍对应前述快照摘要。

模拟器支持最多四个 Android 用户；本次创建了独立测试用户 `10`（`Terra-OAI-Isolated`），没有删除该用户。当前仍为原用户 `0`；系统包状态显示 OAI 在用户 `0` 为 `installed=true`、在用户 `10` 为 `installed=false`。本次没有为用户 `10` 安装或注册应用，没有切换现有账号、退出登录、清除数据、配对或提交设备能力请求。

继续在独立用户运行 App 前，需要明确授权为用户 `10` 注册现有安装包，或使用另行准备好的隔离模拟器/用户。原用户会话与数据保持不动。插件目录仍为 `runtime/official-signed-plugins-41219517875/`，来源是上述 CI 签名产物，并非现装 APK 内提取。

## 用户 10 首次登录及 TLS 复核

本节是 2026-11-01 约 09:20 的后续记录；前述“用户 10 未安装”“当前用户 0”等均为较早状态，不代表本节执行时的设备状态。本轮仅检查首次登录和 TLS，不执行能力矩阵。

- 目标模拟器当前用户为 `10`，App 的 `appId=10209`。用户 10 此前已安装 App；按后续授权执行 `adb -s emulator-5554 install -r --user 10 apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk` 后成功，设备端 APK SHA-256 为 `c16db7ef1a0bbb0fb75ab249201a7a103d005c7757a32cff0f3eb5907a79b5f7`。覆盖安装更新了用户 0 和 10 共享的程序包；未卸载、清除数据或使用 `pm grant`。安装前后两用户的数据目录 inode 显示不变，但这不是对数据内容逐项核验。
- 两次登录尝试分别使用旧 APK `bc40483e2a6fa9138c0cdce903855e515841816171c449de2d8106aa7b517387` 和上述最终 APK，输入 Core A 地址与专用账号、密码；UI 均报“无法连接 Gateway。请检查地址与网络，并确认 App 与插件版本一致后重试；若持续失败，请查看 Gateway 服务与日志。”[最终包错误截图](evidence/2026-11-01-global-device-authorization/terra-final-login-failed-redacted.png)已遮挡账号。
- Android 日志中的首次连接开始分别为 `08:58:06.178`、`09:04:48.314`；操作主机记录的提交时刻分别约为 `08:58:08`、`09:04:50`，两者来自不同设备时钟。两次均只有 `transport.io_queued`、`transport.io_dequeued`、`transport.connect_started`，没有看到 `transport.connect_ready` 或请求体写入事件，`requestId=null`。原始日志分别保存在忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-02-login-logcat-private.txt` 和 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-final-login-logcat-private.txt`。日志未记录 `SSLHandshakeException`、`CertPathValidatorException` 等异常类型；不能仅凭日志缺失推断发生了哪一种异常。
- Core A 对应服务日志 `../plans/2026-11-01-global-device-authorization/runtime/core-a/service-20261101T000129Z-df92df96.log` 为 0 字节，不能据此证明服务器完全没有接收到 TLS 流量。本机使用 A 的 CA 验证 `https://127.0.0.1:19841/open-android-intelligence/core/v1/devices`，结果 `TLS_verify=0`、HTTP `401`；`adb reverse --list` 确认 `tcp:19841 -> tcp:19841`。
- 用户 10 系统的用户证书列表中已有 Core A 测试 CA，其 SHA-256 指纹与运行目录 CA 一致；[证书截图](evidence/2026-11-01-global-device-authorization/terra-core-a-user-ca-fingerprint.png)。未安装其他 CA。用户 10 已有 Chrome `124.0.6367.219`，使用无账号模式访问同一 HTTPS 设备列表，页面显示 `AUTHENTICATION_REQUIRED`，站点信息显示 `Connection is secure`；没有跳过证书警告。[页面截图](evidence/2026-11-01-global-device-authorization/terra-chrome-core-a-401.png)、[安全状态截图](evidence/2026-11-01-global-device-authorization/terra-chrome-core-a-secure.png)。浏览器页面截图不直接显示 HTTP 状态码，`401` 是本机严格 TLS 请求的实测结果。
- **结论边界**：上述结果证明本机及用户 10 的 Chrome 可以访问 Core A，不证明 App 使用相同的证书信任范围。App 默认信任设置导致证书失败仍是待验证假设；尚未捕获 App 的具体异常，登录、配对和能力发现均未完成。等待主流程提供哈希已核对的 Android TLS 探针后，再在用户 10 运行限定的无凭据测试。

本轮复核命令（未输出凭据）：

```bash
adb -s emulator-5554 shell am get-current-user
adb -s emulator-5554 reverse --list
curl --connect-timeout 3 --max-time 6 --silent --show-error --cacert docs/plans/2026-11-01-global-device-authorization/runtime/core-a/tls/v2/ca-cert.pem -o /dev/null -w 'HTTP=%{http_code} TLS_verify=%{ssl_verify_result}\n' https://127.0.0.1:19841/open-android-intelligence/core/v1/devices
adb -s emulator-5554 shell am start --user 10 -a android.intent.action.VIEW -d https://127.0.0.1:19841/open-android-intelligence/core/v1/devices -n com.android.chrome/com.google.android.apps.chrome.IntentDispatcher
android layout --device=emulator-5554 --flat --pretty
```

`docs/reviews/evidence/` 当前被 `.gitignore` 忽略；截图可在工作区查看，不会随普通 `git add` 自动加入提交。

## 真实Android TLS探针（失败证据）

由Terra在隔离用户10执行 `CoreTlsConnectivityTest`，测试报告1项、1失败。ADB命令返回0不代表JUnit通过。
App APK SHA-256 `fa03661d439fe1166f7a5f155bab85e95c217f97cf18919862f437a6ceb4f79a`，
androidTest APK `f8e7b8d7c420146591e00308bf9df76f7ee15923a932bd6a7b006cbbc1103542`，安装后哈希与签名已核对。
错误链 `SSLHandshakeException / CertificateException / CertPathValidatorException`，具体原因
`Trust anchor for certification path not found`。探针只访问隔离Core的未认证接口，未携带设备或账号凭据，未读敏感数据。
登录和能力发现仍未通过，待debug用户CA信任配置修复后复测。

## TLS 修复复测与通话能力拒绝路径

2026-11-01 用户 10：覆盖安装的 App APK SHA-256 为 `677155810ca6c9a957ee29c859d9318df6d806c7fc4d63289ebd4254b237ee1d`，测试 APK 为 `780ea9db6d46e393de7f1db953a13d3f978e3948e78abc38c3aba48516eca7c3`；两个设备端哈希及签名均核对。真实 `CoreTlsConnectivityTest` 输出 `OK (1 test)`，该测试通过同一 `GatewayTransport` 严格断言隔离 Core A 的未认证设备接口返回 `401`。完整输出位于忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-core-a-tls-probe-userca-20261101.txt`。

随后通过真实 App UI 使用隔离账号登录 Core A；Android 日志 `09:34:30.316` 出现 `session.ready`，之后 `GET` 请求 `reqce8f278b3d674e15b4ec` 返回 `200`。App 设置页显示“已连接”和证书指纹一致，但对话页仍显示“实时通道未建立”；本次未验证实时会话。日志原文位于忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-userca-login-logcat-private.txt`。通知权限通过系统弹窗授予，未使用 `pm grant`。

以下为当时系统权限被拒绝阶段的历史结果；后续已通过系统界面允许并继续复测，见下节。该阶段的 **BLOCKED** 不等于 App 审批卡片拒绝通过：

- Core A 运行进程为 `1722353`；其快照与工作树 `oai-core/src/oai_core/gateway/core.py` SHA-256 均为 `88f111d3d57a086f6ee516a0fbdf9d17f24340ef090d7c45396fe0c7b4f4cd64`，未重启服务或更改账号及密钥。
- 进入插件管理时，`Call Log Query` 已安装并启用，`org.openandroidintelligence.call-log.query@1.0.0` 总开关已开启，“每次手动”已选中。由于插件不是本轮新安装，当前页面不能单独证明初始默认值，也未重复安装或更改模式。当前 APK 内的通话插件与预先核验的官方签名产物 `call-log.alp` SHA-256 均为 `d88be159a6b86a79f99f68eb863e99171cf22246de6584be2b56d0695fef0c7d`；这只核对了 APK 所含文件，不单独证明已有安装实例的来源。
- 从插件页面发起系统权限检查，在 Android 系统弹窗选择拒绝读取通话记录。`dumpsys package` 的用户 10 状态显示 `READ_CALL_LOG: granted=false`；页面显示 `READ_CALL_LOG_REQUIRED`，且“每次手动”仍被选中。[系统弹窗](evidence/2026-11-01-global-device-authorization/terra-call-log-system-permission.png)、[拒绝后状态](evidence/2026-11-01-global-device-authorization/terra-call-log-system-denied-manual.png)。没有读取通话记录或触碰用户 0 的通话数据；这不代表用户 10 永远无法通过系统 UI 授权。
- 工作树 CLI 使用隔离 Core A 的 CA、专用账号和真实登录日志中的设备 ID 创建独立设备凭据，凭据文件仅保存在忽略的 `../plans/2026-11-01-global-device-authorization/runtime/core-a/terra-cli-user10.json`。`devices` 显示设备在线，`capabilities` 返回通话能力 `ANDROID_PERMISSION_REQUIRED / READ_CALL_LOG_REQUIRED`，未提供该能力的参数 schema。[脱敏发现记录](evidence/2026-11-01-global-device-authorization/terra-call-log-discovery-blocked.txt)。
- 因按本轮要求拒绝了必要的 Android 系统权限，且能力发现未提供可调用参数契约，本轮未猜测参数、未执行 `invoke`，没有生成 `requestId`，没有出现或点击 App 审批卡片，也未验证 `local_user_rejected` / `denied` 终态。待明确允许通过系统 UI 授权用户 10 的通话记录权限并再次发现能力后，才能另行验证 App 卡片拒绝；不能把系统权限拒绝记为 App 审批拒绝。

本节所引用的 `docs/reviews/evidence/` 路径仍被仓库忽略；如需提交证据须按仓库流程显式处理，原始日志和客户端凭据不得提交。

## 通话记录手动拒绝复测

2026-11-01，按后续明确指示，在 Android 用户 10 的系统弹窗选择允许 `READ_CALL_LOG`；系统状态为 `granted=true`，通话能力仍是“每次手动”。隔离 Core A CLI 随后发现 `org.openandroidintelligence.call-log.query@1.0.0` 状态为 `READY`，返回的 schema 允许 `limit` 和 `receivedAfter`。没有读取或输出通话记录内容。

第一次请求 `devreq_cd0bb3a0-b734-4ef7-9626-7c59ed592fb3` 在检查界面焦点时超时，终态为 `denied / LOCAL_CONFIRMATION_TIMEOUT`，**不是**手动拒绝；该请求不再重用。使用新幂等键重试相同能力，参数为 `{"limit":1,"receivedAfter":"2099-01-01T00:00:00Z"}`，调用方声明为 `Terra验收`、目的为 `拒绝专用通话记录测试请求`。

重试请求 `devreq_807c16e8-f620-4c4c-9c32-e1c356e9d955` 的真实 App 卡片展示了相同调用方、目的、能力和参数。因为“拒绝”按钮的点击区域与限时允许按钮重叠，使用 Tab 将键盘焦点明确定位到“拒绝”，再按 Enter 激活该按钮；不是依靠不明确的坐标点击。Android 日志按该请求 ID 记录 `claim=SUCCEEDED`、`authorization=LOCAL_USER_REJECTED`、`completed=denied`、`result=ACKNOWLEDGED`；CLI `status` 返回 `state=denied`、`result.outcome=denied`、`result.data.reason=LOCAL_USER_REJECTED`。响应同时保留 `localAuthorizationState=awaiting_confirmation`，这与终态并存，需单独核查字段含义，不改变实际拒绝结果。无设备结果正文返回。

[脱敏请求轨迹](evidence/2026-11-01-global-device-authorization/terra-call-log-manual-deny-trace.txt)；[审批卡片与按钮重叠截图](evidence/2026-11-01-global-device-authorization/terra-call-log-manual-request.png)拍摄的是第一次、后来超时的同参数请求，不冒充重试截图。重试请求状态与设备原始日志保存在忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-call-log-deny-retry-status-private.json` 和 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-call-log-deny-logcat-private.txt`。本次仅验证该通话能力的一次手动拒绝，不代表读取、其他能力或完整矩阵通过。

## 双 Core 与新按钮

2026-11-01 在用户 10 上，系统用户证书列表中已有 Core B 的测试 CA，指纹与隔离环境证书一致；没有重复安装。通过 App 从已保存的 Core A 切换并登录 Core B，再选择保存的 Core A 返回。Core B 下和切回 Core A 后，通话插件的总开关仍开启、“每次手动”仍被选中；Core B 下 `READ_CALL_LOG` 仍为 `granted=true`。这些是切换前后观察，不证明新安装能力的初始默认值。[Core B 设置截图](evidence/2026-11-01-global-device-authorization/terra-core-b-call-log-manual.png)。

Core B CLI 使用独立账号、设备凭据和 CA 发现通话能力 `READY`，提交参数 `{"limit":1,"receivedAfter":"2099-01-01T00:00:00Z"}` 后，请求 `devreq_00aeb0c8-6183-4c76-af82-b5ae43aa4972` 到达 [App 待确认卡片](evidence/2026-11-01-global-device-authorization/terra-core-b-call-log-pending.png)。未点击卡片的允许或拒绝；CLI 取消先返回 `cancel_requested`，随后 `status` 返回 `cancelled / DEVICE_REQUEST_CANCELLED`。本次未读取通话记录正文。

切回 Core A 后，覆盖安装按钮修复 APK `4db4fe68c0eb4312566d1a5d36d7f4bfab2491ea6179edc32b619ef778bd68fa`。真实界面中的四个允许按钮与拒绝按钮分开排列；触摸“拒绝”后，请求 `devreq_70edb26c-30f6-432d-b0eb-94b2ef99e0b4` 返回 `denied / LOCAL_USER_REJECTED`，Android 日志记录对应的 `authorization`、`completed` 和 `result=ACKNOWLEDGED`。[新按钮截图](evidence/2026-11-01-global-device-authorization/terra-new-dialog-separated.png)。该次触摸拒绝发生在此 APK 上，不冒充最终提交 APK 的复测。

上述 Core B 取消和 Core A 拒绝均发生在旧运行源码快照 `20261101T000129Z-df92df96` 上，其源码清单摘要为 `5d8f21d01d160bfa5b8a67f09110ba4174df81e5faa1ae93a30e165fa8035ebe`，不是最终提交 `1680c04e86d16b48b699935ce5a990d8b49f4b4e` 的 Core 运行证明。两个终态响应当时仍带 `localAuthorizationState=awaiting_confirmation`；不能据此宣称后续 Core 修复已在运行时复测。本次没有重启 Core A/B。

随后在用户 10 覆盖安装最终提交的 App 和测试 APK，设备端 SHA-256 分别为 `5affe22f0918e869539428f0a673491209fecedfdbf6fc059bcefcf0787e19ea` 和 `51a4fbce323c59f06482b471172ae3bd39c6c4e4014d46c7ba8eb33fda0461be`。安装后无需重新输入账号即可回到 Core A，用户 10 的通话权限仍为允许；没有卸载、清除数据或解除配对。Android 的应用程序包由用户 0 和 10 共享，覆盖安装更新的是共享程序包，不是仅更新用户 10 的代码；本记录不声称逐项校验了用户 0 数据。

## 通知推送配置与样本

在最终 App 上，通过用户 10 的 Android 系统设置允许 OAI 通知监听，系统开关显示已开启；`POST_NOTIFICATIONS` 此前已为允许。App 主动通知推送已启用，应用范围保存为 `com.openandroidintelligence.mobile`，包含正文开关为开启；短信推送没有开启。被动通知查询此前显示“每次手动”，本配置步骤没有修改它，也未在最终包上重新进入该页面验证。

通过 App 刷新端点并核对完整身份后，选择 Core `core_561a2b2cd2e94058b0082696a4ea1e54`、账号范围 `acct_745ae95a5983cd8097afc53b8d99a222` 下的 `TerraReceiverA / endpoint_9e385edc929a43439b67eb5aa70a13de / generation 1` 和独立的 `TerraReceiverB / endpoint_a10a55c3c9944092b44d3590afe05d70 / generation 1`；同名旧 B `endpoint_9b0c7a2e7cd242a9bac83901ac9df718` 未选择。启用后、发布样本前，通过 App 刷新队列显示待发 `0`、待投递 `0`；没有读取通知正文。

使用已安装的最终测试 APK，在用户 10 执行 `AcceptanceNotificationSampleTest` 的 `post` 分支：第一次命令开始的主机时间为 `2026-11-01 10:57:30 +0800`，输出 `OK (1 test)`；随后按紧急指示再次执行同一发布命令，也输出 `OK (1 test)`，第二次未记录具体时刻。两次执行不应合并表述成只发布一次；第一次原始输出位于忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-notification-sample-post-20261101.log`。本人未在样本发布后读取到有效的队列刷新数值，也未执行任何接收端 ACK。

主 Agent 后续报告：观察器已在两个接收端观察到样本事件 `push_7e267b912b1056ed53658b86e1ac20a8295cbe03d209a3ac3159e298fd8e0b79`，两个 delivery 不同且样本正文完整；其 UI 测量在 A ACK 前后为 `2` 个事件、`4` 次待投递变为 `2` 个事件、`3` 次待投递。这是主 Agent 提供的阶段证据，不是本记录作者独立读取的接收日志或最终 B ACK 结论，不能推断队列当前仍为该数值。

剩余矩阵：最终提交 APK 的按钮真实拒绝复测、新 Core 源码的终态字段、两端 ACK 全流程与队列清空、通话记录实际查询及合成数据过滤、短信、屏幕、撤销与离线恢复、实体设备均未由本轮完成；不得用上述局部结果替代完整验收。通知、短信与屏幕系统权限和本机范围仍分别管理。

## 主流程核对的临时许可计时结果与服务限制

原后台计时器日志最终`finished/PASS`，scope5为到期后延迟复验、scope30和scope60为按点击上界及真实期限排期；
60分钟请求`devreq_db56f068-6553-41fd-884b-d5e0b3235109`再次等待确认后取消，未自动执行。
[完整脱敏关键记录](2026-11-01-global-device-authorization-ttl-complete-evidence.txt)。主Agent核对的是先前由Terra启动的真实后台进程输出，
并未以主Agent新发请求冒充Terra设备测试。

手机子Agent执行后续SMS/屏幕步骤时模型服务503退出，因此完整运行矩阵保持未完成。
源码屏幕重连handle和预置入口修复已本地回归，最终新包运行待恢复Terra服务后复测；实体手机缺失继续BLOCKED。

## 屏幕控制只读拒绝与静态 reader 分项

Terra 在 emulator-5554/user10 上确认控制插件为 READ_ONLY。devreq_f4e2ea14-9d39-4a84-8b9a-5ddf2862f0b2 使用历史真实 revision 触发 CONTROL/back 卡片，真实点击拒绝后为 denied/LOCAL_USER_REJECTED，前后均为插件管理页；这项 PASS 不证明历史 revision 可用于允许执行。

插件管理页两次读取 devreq_5784f9c4-65aa-4929-bc84-8461cc63da0c、devreq_ebff0544-1e6d-4924-b012-44b81e151a8e 均 failed/SCREEN_CHANGED；参数 maxNodes=8、maxDepth=8、maxBytes=4096，分别 snapshot/state，无 revision。保留原保护后，静态 OAI 设置页单次读取 devreq_b8218221-bada-4839-a8e0-3f43cc558ab8 succeeded，8 节点、truncated=true，未输出正文。无控制允许、点击/滑动、系统撤销或并发租约运行通过结论。

## 新版范围界面升级、自然过期与关键词保存

5556新包SHA-256为1ec8841c6fc7957c2fd6cf0e0fa0f77dec3d63f2ccfa1936b8d204ad89a81e5a，测试包d5ee749ae541b9c88d1f03a05dca1411065824c29e4dc21171657fc4fa31ef6e。Terra已核对设备内哈希并覆盖安装，账号、系统权限和既有策略未清空。新包18:09:17刷新仍为未ACK队列1/1、expired0；18:12:43刷新为0/0、expired1、待远端清理0，未ACK/改scope/改时钟。

旧被动短信配置真实UI核对为READ_ONLY、电话号码15550102202、正文关闭；随后通过新UI将范围保存为关键词OAI-SAMPLE-SMS-5556-20261101，退出重进后KEYWORDS和正文关闭仍保留。推送范围未保存变更。本项仅证明本地保存与升级保留，真实关键词读取/拒绝以随后requestId结果为准。补充uiautomator检查两次退出137后停止，状态通过正常返回的android layout核验，不把失败命令伪报成功。
