# SMS 独立模拟器准备记录

## 范围和隔离

- 目标仅为建立新的、可被 ADB 访问的干净模拟器，并记录电话状态及短信能力；不安装应用、不授予系统权限、不登录 Core、不执行短信收发矩阵。
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
