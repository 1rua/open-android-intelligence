# SMS 独立模拟器准备与分阶段验收记录

## 范围和隔离

- 初始准备阶段仅建立新的、可被 ADB 访问的干净模拟器，并记录电话状态及短信能力；后续安装、许可、登录与生命周期步骤按追加授权分别记录。六项生命周期的最终结果和未覆盖边界见文末“5556 生命周期最终汇总”。
- 独立运行目录：`docs/plans/2026-11-01-global-device-authorization/runtime/sms-emulator/`；该目录被项目 `.gitignore` 忽略。运行数据、隔离 home、缓存和日志均放在此处，不使用 `/tmp` 作为运行目录。
- 复用已有的 `../../.toolchains/android-sdk`，未下载或升级 SDK、模拟器或系统镜像。
- 不操作现有 `emulator-5554`、其用户或账户，不停止或重启共享 ADB 服务。所有设备检查明确指定新序列号 `emulator-5556`。

## AVD 创建证据

- 标准 `avdmanager create avd` 创建：`oai_sms_api34_global_auth_20261101_v2`。
- 镜像：`system-images;android-34;google_apis;x86_64`；设备配置 `pixel_2`；配置文件记录 `hw.ramSize=2G`、`hw.gsmModem=yes`。
- 第一次创建尝试同时设置不同的 `ANDROID_SDK_HOME` 和 `ANDROID_USER_HOME`，工具报告无法定位 SDK；仅生成未注册的空目录。取消旧变量 `ANDROID_SDK_HOME` 后，以新名称成功创建。失败目录和日志均保留，未覆盖或删除。
- 启动前宿主机检查：5556/5557 未监听，KVM 可读写；既有共享 ADB 服务 PID 为 `26718`，可执行文件为 `/usr/bin/adb`。启动时显式指定同一 ADB 程序，避免使用另一套客户端影响该服务。
- 创建日志位于运行目录的 `logs/create-avd-v2.log`。

## 启动和检查结果

- **PASS：独立启动和 ADB 可用。** 新模拟器序列号 `emulator-5556`，宿主机模拟器 PID `2454491`，控制台/ADB 端口分别为 `5556`/`5557`；`adb get-state` 返回 `device`，`sys.boot_completed=1`。模拟器日志记录启动耗时 25,675 ms，未超过 90 秒的启动检查上限。
- **PASS：镜像和内存。** 运行中的系统为 Android API 34，x86_64 Google APIs 镜像；启动参数包含 `-memory 2048 -lowram`，系统内 `/proc/meminfo` 的 `MemTotal` 为 2,021,096 kB（可见值会少于配置值）。`-lowram` 是本次实际使用的模拟器启动参数：未加它的首次启动日志显示该模拟器把 2,048 MB 自动提高到 2,560 MB。
- **PASS：电话状态的基础能力可见。** 系统声明了 `android.hardware.telephony`、`calling`、`data`、`gsm` 等特性；`service check phone` 返回 `found`；SIM 状态为 `LOADED`，模拟网络类型为 `HSPA`。`READ_PHONE_STATE` 出现在系统危险权限定义中；这不表示已向任何应用授予权限。
- **PASS：短信服务及权限定义可见；实际收发未验证。** `service check isms` 返回 `found`；系统列出 `READ_SMS`、`RECEIVE_SMS` 和 `SEND_SMS` 权限定义。列出的系统特性中没有单独的 `android.hardware.telephony.messaging` 或短信特性条目；服务存在不能证明短信实际收发可用。
- **PASS：未改动既有实例的操作边界。** 验证时原共享 ADB 服务仍为 PID `26718`，现有 `5554`/`5555` 仍由原模拟器 PID `35387` 持有；新进程独立持有 `5556`/`5557`。没有对现有实例或其用户发送 ADB 命令，也未停止或重启共享服务。
- **范围限制：** 这是模拟器，不是实体手机。本轮没有安装应用、授予权限、登录 Core、收发短信或执行 SMS 场景矩阵；因此这些能力仍须后续单独验收。

## 启动异常和证据位置

- 首次后台启动的 PID `2448562` 在完成启动前退出；日志没有给出明确退出原因，不能据此断言根因。初始尝试、日志及其状态均保留；未清理 AVD 或执行 `-wipe-data`。随后使用独立进程会话启动同一尚未完成初次启动的 AVD 并成功，未添加账户或应用。
- 首次重试的后台检查器因运行目录变量未导出而退出；修正后按本次模拟器启动时刻计算剩余时间，启动检查在 90 秒期限内完成。检查器错误和修正后的输出均保留。
- `logs/emulator.log`、`logs/boot-check.log`：首次进程证据；`logs/emulator-retry.log`：成功启动日志；`logs/boot-check-retry.log`：首次检查器错误；`logs/boot-check-live.log` 与 `.exit`：有界检查结果。路径均相对于上面的隔离运行目录。

## 只读复核命令

以下命令只针对新模拟器；`/usr/bin/adb` 与已运行的共享 ADB 服务使用同一程序：

```bash
/usr/bin/adb -s emulator-5556 get-state
/usr/bin/adb -s emulator-5556 shell getprop sys.boot_completed
/usr/bin/adb -s emulator-5556 shell getprop ro.build.version.sdk
/usr/bin/adb -s emulator-5556 shell cat /proc/meminfo | head -1
/usr/bin/adb -s emulator-5556 shell pm list features | rg 'telephony|sms|messaging'
/usr/bin/adb -s emulator-5556 shell pm list permissions -g -d | rg 'READ_PHONE_STATE|READ_SMS|RECEIVE_SMS|SEND_SMS'
/usr/bin/adb -s emulator-5556 shell service check phone
/usr/bin/adb -s emulator-5556 shell service check isms
/usr/bin/adb -s emulator-5556 shell getprop gsm.sim.state
/usr/bin/adb -s emulator-5556 shell getprop gsm.network.type
ps -o pid,ppid,pgid,sid,stat,etime,args -p 26718 -p 35387 -p 2454491
ss -ltnp '( sport = :5037 or sport = :5554 or sport = :5555 or sport = :5556 or sport = :5557 )'
```

进程号是本次检查时的快照，后续可能变化；请以实际进程和端口归属为准。

## 追加：App 和测试包安装

- 目标仅为 `emulator-5556` 的用户 `0`；安装前确认当前用户为 `0`，App 和测试包均未安装，不进行覆盖安装。
- 用户指定的 App APK SHA-256：`9b3216be2395fe8730ac173168c835be9f745dda84f74839ef859c2d07078dcb`；设备已安装 APK 的 SHA-256 与之相同。
- 测试 APK SHA-256：`f98279993f11e5e44486574cae217e097fedcee5f481d5383c842eabc818c9f0`；设备已安装 APK 的 SHA-256 与之相同。
- 两个 APK 通过 `apksigner verify`，签名证书 SHA-256 均为 `37d9445fab11d827b032b84b0739acb2c33aa8ace1f319ed20a4cc612624ce33`。包名分别为 `com.openandroidintelligence.mobile` 和 `com.openandroidintelligence.mobile.test`。
- 本记录核验了用户指定的产物哈希和签名；未根据哈希单独证明构建来源提交。安装和设备哈希原始记录保存在本模拟器忽略的运行目录 `logs/` 中。

## 追加：已有合成样本的单次 MANUAL 查询

- requestId：`devreq_9818b5c7-80bd-43c8-af01-60f3e0fa6a07`，终态 `succeeded`，仅返回 `1` 条。
- 专用发送方：`15550102202`；固定合成正文：`OAI-SAMPLE-SMS-5556-20261101 synthetic only`。
- 复用原参数：`receivedAfter=2026-11-01T05:02:22Z`、`limit=1`，发送方和 query 标记严格限定上述样本；当前 CLI capabilities 已核验 schema 和 READY。
- 旧幂等键经 Core 元数据只读检查无请求；新键只提交一次。调用者声明 `TerraSMS`，目的 `读取专用样本`。新模拟器真实 MANUAL 卡点击“仅允许本次”，未选择临时许可或更改模式。
- 本轮未注入、未查询其他号码、未操作 5554。原始响应和脱敏固定样本证据保存在忽略的 `runtime/sms-emulator/sms-manual-sample-20261101/` 目录。

## 追加：READ_ONLY、发送方范围与元数据单回归

仅通过新模拟器 UI 修改短信 query：选择“允许只读”，发送方范围保存为 `15550102202`；最后关闭“包含短信正文”并保存。未修改定时、取消能力，未注入额外短信。共提交三个请求，未进行人工批准。

- PASS：`devreq_1fd4b447-5753-467d-8b3f-0d1929ad2b78`，`succeeded`，1 条，专用 sender 和固定 marker 均匹配；无需新人工卡。
- PASS：`devreq_427bfdce-0db3-4c2f-b1f7-86ebb64e082b`，越界 sender `15550103303`，`denied` / `LOCAL_DATA_SCOPE_DENIED`；未静默返回空数据。
- 元数据请求 `devreq_669e2332-ffaa-4369-891a-d84c2805bea4`：移除 `query` 后 `succeeded`，1 条，字段为 `id/read/sender/text/timestamp`。固定 marker 未出现；`text` 值为 null。正文内容缺失：`True`；严格“不含 text 字段”的结果形状检查：`False`。
- 原始结果只保存在忽略的 `runtime/sms-emulator/sms-readonly-scope-20261101/`；脱敏字段与各 requestId 同目录记录。没有操作 5554，也没有放宽发送方范围。

## 追加：SMS 独立主动推送 UI 准备

- PASS：未修改前，真实 UI 中短信主动推送关闭、通知主动推送关闭；初始队列为待发 0 条、接收端投递 0 次。
- 仅配置短信主动推送：开启；允许所有发送方关闭，范围保存为 `15550104404`；推送短信正文开启。被动 query 的 READ_ONLY、`15550102202` 和 includeContent=false 未操作。
- 队列保留期从 72 小时改为 UI 允许的最小值 1 小时；空队列下保存，重新打开确认保留期和短信范围均已持久保存。
- 初次打开没有已验证端点；重新打开时 Core 侧已出现 TerraSmsReceiver。通过本新设备绑定的官方 CLI endpoint list 与 UI 路由逐项匹配后，仅在短信区勾选 `endpoint_f3d570ce36e74457874a4a98b16ddda6`（第 1 代），通知端点未勾选、通知推送保持关闭。
- 配置后队列仍为 0 条 / 0 次投递，过期 0、容量拒绝 0；本轮未注入样本、未等待 receipt、未查询短信或操作 5554。脱敏配置证据位于忽略的 `runtime/sms-emulator/logs/sms-push-ui-preparation-evidence.json`。

## 追加：新 UI 草稿取消与被动关键词准备

- 升级后真实 UI 复核：被动 READ_ONLY、PHONE_NUMBERS=`15550102202`、正文关闭；主动 PHONE_NUMBERS=`15550104404`、正文开启、原接收端点第 1 代和 1 小时保留期均保留。
- 主动范围仅切换 ALL/KEYWORDS/PHONE_NUMBERS 草稿。空关键词显示“请至少填写一个关键词”，保存按钮呈灰色禁用；未点击保存。从变化后的关键词草稿退出再进入，原 PHONE_NUMBERS=`15550104404` 保留。
- 仅保存被动 KEYWORDS=`OAI-SAMPLE-SMS-5556-20261101`，正文仍关闭，READ_ONLY 不变；退出重进已确认持久保存。本轮无 Core 请求、无短信注入；真实关键词命中等待后续单独验收。
- 截图与自动化 UI 断言位于忽略的 `runtime/sms-emulator/ui-sms-keywords-20261101/`。补充原生层级读取退出 137，按原命令提权重试同样失败；空条件禁用依据 Android CLI 校验文案及实际灰色按钮截图，未伪报原生 enabled 属性检查通过。

## 追加：主动短信关键词与正文关闭 UI 准备

- 主动旧 PHONE_NUMBERS=`15550104404`、正文开启、原端点第 1 代与 1 小时保留期均保留；ALL/KEYWORDS/PHONE_NUMBERS 仅草稿切换，空关键词显示校验并禁用保存，未保存退出重进原号码范围不变。
- 随后仅通过真实 UI 保存主动 KEYWORDS=`OAI-SAMPLE-SMS-KEYWORD-PUSH-20261101`，关闭推送短信正文；重入确认主动仍开启，原 TerraSmsReceiver 端点第 1 代仍被选中、保留期仍 1 小时。
- 本轮未操作被动范围或账号、未注入新 SMS、未运行 Core 请求或 ACK；等待单独 observer 准备后再测试。证据位于忽略的 `runtime/sms-emulator/ui-sms-keywords-20261101/active-push-keyword-evidence.json`。

## 追加：主动关键词非匹配样本单回归

- 2026-11-01 18:42:07 上海时间真实 UI 刷新：已匹配 sender55 元数据事件的手机队列为待发1/投递1，累计过期1，待远端清理0。
- 18:43:02 仅一次 OS 注入 sender `15550106606`、正文 `OAI-UNMATCHED-SMS-20261101 synthetic only`，exit=0/OK；不匹配已保存的主动关键词。
- 2026-11-01T18:43:48+08:00 再点击一次真实“刷新队列状态”：待发 1 条，共 1 次接收端投递。已过期 1 条，容量拒绝 0 次。 待远端清理 0 次；离线时保留清理记录，恢复连接后重试。；队列未因非匹配样本增为2。
- 本轮未改scope、未ACK、未查询或新增其他样本，未操作5554。所有原始时间和UI记录保存在忽略的 `runtime/sms-emulator/sms-keyword-push-unmatched-20261101/` 及 `ui-sms-keywords-20261101/ui/`。

## 5556 生命周期最终汇总

以下时间均为 **2026年11月1日，Asia/Shanghai（UTC+08:00）**，仅对应 `emulator-5556 / user0`。使用的 App SHA-256 为 `1ec8841c6fc7957c2fd6cf0e0fa0f77dec3d63f2ccfa1936b8d204ad89a81e5a`；不把其他设备后续屏幕包或测试结果合并为本段证明。队列 `1/1` 表示待发1条、接收端投递1次；过期数和待远端清理数分别记录，单看总数不能确定某条短信的归属。

本段范围基线为：被动 `READ_ONLY`、`KEYWORDS=OAI-SAMPLE-SMS-5556-20261101`、`includeContent=false`；主动保持启用、`KEYWORDS=OAI-SAMPLE-SMS-KEYWORD-PUSH-20261101`、`includeContent=false`，原短信端点 `endpoint_f3d570ce36e74457874a4a98b16ddda6` 第1代被选中，保留期1小时。端点租约到期后的“不可用”提示与本机队列保留分别判断。以下链接仅指向本机被忽略的私密证据目录或脱敏记录；未把凭据、密钥材料或原始账号存储复制进报告。

### 1. 普通 am kill：未实际回收

- **实测时间与动作：** 18:47:42先记录范围和队列；18:49:04通过真实 Home 回到 NexusLauncher 后，仅执行一次 `adb -s emulator-5556 shell am kill --user 0 com.openandroidintelligence.mobile`。命令退出码0；18:49:06记录的两次进程检查中，旧 PID `11994` 都仍存在。
- **结果：** `BLOCKED_NOT_RECLAIMED`。重新打开为 `HOT`；18:54:23旧 PID仍为11994，队列仍为1/1、累计过期1、待远端清理0。两套范围、正文关闭和1小时保留期保持。
- **未覆盖：** 系统没有回收该进程，不能报告系统内存回收或 OOM 恢复通过。未追加 kill、force-stop、root 或全局杀进程操作。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/process-reclaim-once-20261101/)、[单次命令与 PID 结果](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/process-reclaim-once-20261101/kill-result.json)、[状态汇总](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/process-reclaim-once-20261101/lifecycle-stage-evidence.json)。

### 2. debug 同 UID 突然进程死亡与重启

- **实测时间与动作：** 18:58:00前确认 PID11994 的命令行精确属于本测试包，包 appId、run-as UID 和进程 UID 均为 `10192`（user0）；仅执行一次 `adb -s emulator-5556 shell run-as com.openandroidintelligence.mobile kill -9 11994`，退出码0，旧进程路径消失。
- **结果：** 本有界场景 `PASS`。系统在手动打开前已自行拉起新 PID `14169`；随后手动打开为 `WARM`。19:02:03复核原账号、安装/设备/profile身份、SMS插件及两套范围保留；原第1代端点仍选中但不可用，保留期仍1小时。刷新队列为1/1、累计过期1、待远端清理0。
- **未覆盖：** 这是专用 debug 进程突然终止后的恢复，不是系统 OOM、内存压力、Doze、系统 listener 重启或实体手机验证。未读取或解密设备私钥。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/sudden-process-death-20261101/)、[旧 PID 消失和自动重启记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/sudden-process-death-20261101/kill-result.json)、[恢复汇总](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/sudden-process-death-20261101/sudden-death-stage-evidence.json)。

### 3. 全部退出后本地管理与采集暂停

- **实测时间与动作：** 退出前通过 Android AccountManager 只读核对只有本测试 secondary profile，且对应当前账号和设备。19:06:54真实 UI 点击“退出登录”；没有点“移除”或“解除设备配对”。19:08:06确认界面提示“当前已退出全部账号：暂停采集和推送，保留配置与未过期队列”。
- **结果：** 本有界场景 `PASS`。原 profile、SMS插件和 `READ_SMS granted=true` 保留，退出态仍可进入设置、两套范围及推送页。19:09:35刷新队列为1/1、累计过期1、待远端清理0；原范围、正文关闭、端点第1代及保留期1小时保持。
- **暂停采集点验：** 19:10:44.600114至19:10:44.605782仅一次真实 OS 注入专用 sender `15550107707` 的匹配合成样本，命令退出码0、返回 `OK`。19:10:47刷新仍为1/1、累计过期1、待清理0，没有因该退出态样本增加队列。
- **未覆盖：** 这里只验证该次退出态匹配样本未新增队列；没有 ACK、重新登录、改范围或移除配对。后续是否补采 sender77，不能仅依据队列总数认定。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-collection-pause-20261101/)、[退出态与注入后队列汇总](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-collection-pause-20261101/signedout-pause-stage-evidence.json)、[退出暂停提示截图](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-collection-pause-20261101/ui/16-all-accounts-signedout-push-paused.png)。

### 4. 飞行模式离线管理、保留期还原与网络恢复

- **实测时间与系统状态：** 19:13:28记录初始状态：飞行模式关、Wi-Fi开、移动数据开。通过真实快捷设置开启飞行模式；19:17:56确认 Wi-Fi系统开关关闭、`Wifi is disabled`、`Active default network: none`，OAI保持全部退出态。
- **离线保存结果：** 19:20:45通过真实 UI 把保留期1小时保存为2小时；19:20:53再保存还原1小时。两次刷新均为队列1/1、累计过期1、待远端清理0。19:22:25完成被动和主动范围草稿切到“全部”后取消，重入仍保留各自原关键词及正文关闭；没有保存范围或正文变更。
- **恢复结果：** 19:23:40通过真实系统 UI 恢复原网络值：飞行模式关、Wi-Fi开、移动数据开；当时仍未登录。离线本地设置可访问、保存和还原的有限场景 `PASS`。
- **未覆盖：** ADB reverse未移除，不能据无线网络断开声称 Core loopback 地址不可达。没有官方单事件 expiry 只读入口，本轮未读取、解密或反射事件存储，不能把 UI 保留或保留期往返当作原 expiry 不延长的实测证据。既有 [clock测试](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntimeTest.kt) 和 [codec迁移测试](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/PushStateCodecMigrationTest.kt) 属于独立自动化证据层；本次文档归档未重跑它们。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-offline-management-20261101/)、[离线阶段汇总](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-offline-management-20261101/offline-stage-evidence.json)、[1→2→1小时记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-offline-management-20261101/retention-roundtrip.json)、[网络恢复记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/signedout-offline-management-20261101/network-restore-evidence.json)。

### 5. 普通密码登录产生新 device 的观察

- **实际入口与时间：** 19:30:43在普通表单填写原 Core A 地址、secondary账号及现有私密口令，点击“登录 OAI Core”。没有点已保存profile行、“使用已配对设备密钥登录”、新邀请或配对入口。提交前后采集的界面未观察到“将新建device”的提示。原始 `login-submit-attempt.json` 中 `originalProfileUsed:true` 只表示沿用原账号资料，不表示点击了配对入口；本说明纠正该字段命名，不改写原始证据。
- **19:33:25身份结果：** 工作界面已进入；安装ID、账号、profile及原设备密钥加密文件SHA-256保持，但 `deviceId` 从 `dev_c16e3d46-108f-4fcb-833d-cf6d76487a5e` 变为 `dev_d097914b-0267-486e-bedd-1ab83a3654f0`。只读 CLI发现旧ID离线、新ID在线，`READ_SMS`仍获授。旧CLI配置未改为新device凭据，其旧绑定的READY不能冒充新绑定能力验收。
- **19:40:54队列结果：** 刷新为待发1/投递1、累计过期2、待远端清理1；1小时保留期及旧第1代路由选择保留，路由显示不可用。没有用改时钟、延长TTL或换路由维持计数。
- **结论与未覆盖：** 仅记录“普通密码新会话/新绑定观察”，**不能报原 c16 设备配对恢复通过**，也不能据此判断官方密钥入口失败或两种入口应完全同义。c16旧记录未被恢复或覆盖；入口语义、提示及设备ID生成原因交由源码审查。没有逐条读取 sender77 或旧事件，不能由总数确定补采归属或单个事件的到期原因。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/relogin-original-secondary-20261101/)、[实际密码入口提交记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/relogin-original-secondary-20261101/login-submit-attempt.json)、[身份差异与系统许可](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/relogin-original-secondary-20261101/identity-and-permission-evidence.json)、[19:40:54队列记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/relogin-original-secondary-20261101/current-queue-handoff-evidence.json)。

### 6. 当前 d097 绑定的官方设备密钥登录恢复

- **实际入口与时间：** 19:45:31刷新基线为1/1、累计过期2、待远端清理1；19:45:40仅通过UI“退出登录”退出当前d097账号。只输入原Core地址和账号名、密码框保持空白，于19:47:27准确点击“使用已配对设备密钥登录”，没有密码兜底、邀请或手动重新配对。
- **19:48:15身份结果：** 已进入工作界面，`d097→d097`；installation/account/device/profile四项及既有设备密钥加密文件SHA-256均保持。本次仅对**当前新绑定**的官方密钥恢复判为 `PASS`。
- **19:50:32至19:52:07保存状态复核：** 被动READ_ONLY和原被动关键词、主动启用和原主动关键词均保持，两者正文仍关闭。旧短信端点第1代仍选中、显示不可用；保留期仍1小时。19:52:07仅一次刷新为1/1、累计过期2、待远端清理1，与本次退出前一致。没有重选端点或把旧事件换到新route。
- **未覆盖：** **原 c16 未恢复、未尝试恢复**；不把此次d097成功回填为前一次密码路径或c16身份恢复通过。没有新增样本、ACK、系统回收、Doze或其他登录场景。19:52:07是最后一次本机实测快照，本次归档不执行新的设备操作。
- **证据：** [本机私密目录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/current-binding-device-key-login-20261101/)、[官方入口点击记录](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/current-binding-device-key-login-20261101/device-key-login-click.json)、[身份保持结果](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/current-binding-device-key-login-20261101/device-key-login-identity-result.json)、[脱敏阶段汇总](../plans/2026-11-01-global-device-authorization/runtime/sms-emulator/current-binding-device-key-login-20261101/device-key-recovery-stage-evidence.json)。

## 追加：生命周期之后自然到期终点

2026-11-01T20:11:49+08:00仅一次真实UI“刷新队列状态”：0条/0次投递、累计过期3、待远端清理2、容量拒绝0。与19:52:07仍1/1、过期2对照，最后有效事件到期后清除敏感队列，未ACK、未注入、未改scope/登录/端点。不可用旧路由的清理元数据保留，不能将待清理2描述为完整远端撤回成功。截图和本轮记录在忽略的runtime/sms-emulator/final-readonly-expiry-refresh-20261101/。此项不直接证明单事件expiry字段或系统时钟回拨场景。
