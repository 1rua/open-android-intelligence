# 连接可靠性：模拟器与部署验收记录

记录时间：2026-11-03，Asia/Shanghai。工作树基线为
`77f8136b4ffd6a71ec86452f05eaecc0dfdcaa0d`，工作树另有未提交的并行修改；该 SHA
不是待测 APK 或运行中 Core 已加载修复的证明。

## 当前结论

- 本轮因没有已连接真机，只完成模拟器网络预检，真机验收仍待设备可用。此前两次设备枚举均为空；该结果只说明当时 ADB 没有列出设备，不推断手机实际网络状态。
- 已在独立 Android 15 / API 35 模拟器中确认至本机既有 Core 的 TCP 可达，未认证 HTTP 请求返回 `401 Unauthorized`。这不是登录、配对、WS/SSE、Native 同步或修复通过的证据。
- 截至 02:38，当前工作树的 `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk` 不存在，新建模拟器也未安装 OAI 应用。没有安装旧 APK、没有执行应用级场景。
- 已识别本机 `oai-core.service` 与其监听进程，但尚未确认它就是本次验收指定的完整部署路径、实际加载的修复版本、反向代理拓扑及可执行的恢复方案。因此没有重启 Core、改代理、发送消息或提交设备请求。
- 本轮没有真机、真实模型或应用级模拟器验收通过的结论。下面所有故障场景均为**待验收**。

## 已执行的只读核对

以下为本轮实际执行的主要命令。设备序列仅使用本次新建的模拟器标识；未读取或记录凭据、用户消息、通知正文和原始游标。

| 时间 | 命令或检查 | 实际结果与范围 |
| --- | --- | --- |
| 02:23—02:25 | `adb version`；`android --version` | ADB `1.0.41` / `37.0.0-android-tools`；Android CLI `1.0.16261425`。CLI 在沙箱内打开用户目录锁文件失败，经授权在沙箱外查询版本成功，没有更新 CLI。 |
| 约 02:25:08 | `adb devices -l` | 沙箱内设备列表为空。该环境看不到 USB 总线，不能单凭此结果判断宿主机。 |
| 约 02:26:46 | `adb devices -l` | 经授权在沙箱外再次枚举，设备列表为空。随后使用独立模拟器进行网络预检；没有用户取消真机验收的范围变更。 |
| 02:26—02:31 | `systemctl --user --machine=djbd@.host list-units 'oai*' 'hermes*' '*gateway*' --type=service --all --no-legend --no-pager` | 用户服务中 `oai-core.service` 与 `hermes-gateway.service` 均为 `active/running`。这是服务管理器状态，不证明版本及应用会话。 |
| 02:26—02:31 | `env XDG_RUNTIME_DIR=/run/user/1000 systemctl --user show oai-core.service --property=Id,LoadState,ActiveState,SubState,MainPID,ExecMainStartTimestamp,FragmentPath,UnitFileState --no-pager` | Core 服务主进程为 PID `914`，启动时间为 2026-11-03 00:14:43 CST。只记录服务元数据，没有读取启动环境或配置私密值。 |
| 02:26—02:31 | `ss -lntp '( sport = :11451 or sport = :8792 )'` | `oai` PID `914` 监听本机 Core 端口 `11451`；`hermes` PID `912` 监听本机回环端口 `8792`。端口和进程匹配不等于已验证 Core 到 Adapter 的会话。 |
| 约 02:34:48 | `curl --connect-timeout 2 --max-time 5 --silent --show-error --output /dev/null --write-out 'HTTP=%{http_code}\n' http://127.0.0.1:11451/open-android-intelligence/v2/negotiate` | 本机 GET 返回 `HTTP=401`，未携带认证信息，也未读取响应正文。 |
| 02:30—02:35 | `android emulator list`；检查本地 SDK 和系统镜像 | 默认 AVD 列表为空，默认 AVD 目录链接的目标不存在；本地存在 API 35 x86_64 镜像，宿主机 KVM 可用。没有搜索其他磁盘或修改旧 AVD。 |
| 02:35—02:38 | `adb -s emulator-5560 get-state`；`adb -s emulator-5560 shell getprop sys.boot_completed`；`adb -s emulator-5560 shell getprop ro.build.version.release`；`adb -s emulator-5560 shell getprop ro.build.version.sdk`；`adb -s emulator-5560 emu avd name` | `device`、启动完成 `1`、Android `15`、API `35`；AVD 为 `oai-connection-acceptance-20261103`。 |
| 约 02:37:14 | `adb -s emulator-5560 shell toybox nc -z -w 3 10.0.2.2 11451` | TCP 检查退出码 `0`。`10.0.2.2` 是模拟器访问宿主机的地址，不是对外部署地址。 |
| 约 02:37:14 | `adb -s emulator-5560 shell 'printf "GET /open-android-intelligence/v2/negotiate HTTP/1.1\r\nHost: 10.0.2.2:11451\r\nConnection: close\r\n\r\n" \| toybox nc -w 3 -W 5 10.0.2.2 11451 \| toybox head -n 1'` | 模拟器收到 `HTTP/1.1 401 Unauthorized`。只保留状态行；没有发送凭据或读取正文。 |
| 约 02:38:40 | `adb -s emulator-5560 shell dumpsys deviceidle get deep`；`adb -s emulator-5560 shell pm path com.openandroidintelligence.mobile`；检查上述 APK 路径 | 当前深度休眠状态为 `ACTIVE`；应用未安装；APK 路径不存在。`ACTIVE` 不是 Doze 测试通过。 |
| 完成检查后 | `adb -s emulator-5560 emu kill` | 只停止本次新建的模拟器，AVD 文件保留；未停止或重启 Core/Adapter。 |

为进行此次检查，使用本地 SDK 的 `avdmanager create avd -n oai-connection-acceptance-20261103 -k 'system-images;android-35;google_apis;x86_64' --device pixel_2` 创建了独立 AVD，其注册及数据位于当前工作树被 Git 忽略的 `.toolchains/connection-acceptance-avd/`。启动时使用端口 `5560`、KVM、无窗口模式及禁用快照；它不是历史模拟器，也没有继承已有账号。模拟器已按上表停止，未删除文件。

## 后续验收的共同前提

1. 主流程完成本次修复的 `fullDebug` 构建，记录构建结果、源码提交及未提交变更摘要、APK SHA-256、包名、版本和签名；不能把旧 APK、旧提交号或单纯文件存在当作新构建证明。只在独立模拟器上安装核对过的 APK，不卸载或清除任何既有应用数据。
2. 重新启动上述专用 AVD，并通过 `adb -s emulator-5560 emu avd name` 确认身份后，所有 ADB 命令都显式带 `-s emulator-5560`；若端口已被占用，先确认身份并更换端口，不操作其他设备。需要用户手动登录时，使用可见的模拟器窗口；不在命令行、日志、截图或本文中记录密码、令牌及私密内容。
3. 由部署负责人确认模拟器实际连接的 Core 身份、服务/进程、监听及代理链、部署包与进程实际加载来源、账号和协商能力。当前 `401` 不能代替认证成功。需要已授权的专用测试账号、会话及低风险测试操作；不得借验收创建替代主密钥、修改绑定或迁移历史。
4. 先准备可复核的脱敏证据：时间、通道、连接状态、重试/执行次数、错误码及必要的测试 `requestId`、`clientMessageId`、`generationId`；不采集正文、通知、能力结果、认证头、签名或原始游标。缺少能核对的计数和身份时，只记未知，不推断成功或未执行。
5. 所有注入前确认影响范围、维护窗口、负责人、原始状态和具体恢复办法。网络与系统状态只改变专用模拟器；涉及真实 Core 或代理的变更，须先单独满足下述目标与恢复条件。身份不明、恢复不可验证或出现意外影响时停止并联系负责人。

复用独立 AVD 时，从本 worktree 根目录执行以下准备命令；实际启动前检查端口及可见窗口需求。模拟器运行时输出可能包含设备认证信息，不应写入共享日志。

```sh
export ANDROID_HOME="$(cd ../../.toolchains/android-sdk && pwd)"
export ANDROID_AVD_HOME="$PWD/.toolchains/connection-acceptance-avd"
mkdir -p "$ANDROID_AVD_HOME/runtime-tmp"
TMPDIR="$ANDROID_AVD_HOME/runtime-tmp" "$ANDROID_HOME/emulator/emulator" \
  -avd oai-connection-acceptance-20261103 -port 5560 \
  -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on \
  >/dev/null 2>&1 &
```

需要无窗口运行时增加 `-no-window`。这些命令只准备模拟器，不启动或修改 Core。

## 待验收场景

### 1. 10 分钟空闲：待验收

**条件**：完成共同前提，应用已认证并建立实际事件连接，记录开始时的 Platform 传输类型和 Native 同步状态。测试账号没有其他并发操作。

**步骤和判据**：保持应用前台及网络稳定至少 10 分钟，记录起止时间、WS/SSE 活动和连接所有者、Native 同步状态及重连次数；随后执行一次已授权的无敏感内容的只读检查。确认没有仅因空闲而长期错误显示离线、重复建立正式连接或丢失同步；Native 的健康状态须有自身证据，不能用 HTTP `401` 或 Platform 心跳代替。分别记录观察和恢复结果，不以模拟器结果推断厂商真机后台策略。

### 2. 网络中断与恢复：待验收

**条件**：确认专用模拟器当前默认网络、原始 Wi-Fi/飞行模式及恢复方法；测试请求和权威身份可核对。不得修改宿主机网络。

**步骤和判据**：在模拟器内临时断开实际承载连接的网络，并确认网络确已中断；恢复为原状态后观察 Platform、Native 的重连及待处理事件。以同一测试身份核对恢复前后的事件、回执和终态，确认未重复发送原请求；记录断开与恢复时刻、错误来源及最终状态。结束时再次确认模拟器网络回到原状。

### 3. Doze：待验收

**条件**：仅在专用模拟器执行，先确认没有需要保留的电池模拟覆盖；记录电池和 `adb -s emulator-5560 shell dumpsys deviceidle get deep` 的初始状态。不要改变真实设备的电池或省电设置。

**步骤和判据**：在确认恢复方式后，依次使用 `adb -s emulator-5560 shell dumpsys battery unplug`、`adb -s emulator-5560 shell dumpsys deviceidle force-idle`，再用 `get deep` 核实进入 `IDLE`。观察系统限制下的状态，不要求休眠期间始终实时在线。之后执行 `adb -s emulator-5560 shell dumpsys deviceidle unforce` 和 `adb -s emulator-5560 shell dumpsys battery reset`，确认回到原先状态，再唤醒应用核对补偿、终态及无重复执行。若初始存在电池覆盖，不直接使用 `reset`，须先由负责人确定如何恢复原覆盖。

### 4. 后台与返回前台：待验收

**条件**：记录已认证应用、当前会话及运行中测试任务的身份；约定后台持续时间，不进行强制停止或清除数据。

**步骤和判据**：执行 `adb -s emulator-5560 shell input keyevent KEYCODE_HOME`，保持后台至约定时长后，通过模拟器界面返回应用。比较后台前后的连接、会话、事件及权威终态，验证恢复后不重复提交、不丢失已持久化事实；同时记录操作系统是否暂停或回收进程。不要把用户强停与普通后台混为一谈。

### 5. 真实 Core 重启：待验收，当前未执行

**条件**：部署负责人确认 `oai-core.service` 确是模拟器所连的目标、目标运行版本和账号范围；确认无不允许中断的任务，记录维护窗口、影响范围和可执行恢复步骤，在既有任务授权内执行。先核对服务原有运行状态、启动时间和恢复后的只读检查方式；保留账号、主密钥、绑定、数据库和历史，不启动第二个竞争写入者。当前这些条件尚未闭合。

**步骤和判据**：条件全部满足后，由负责人对确认的用户级 Core 服务执行一次正常重启；不要同时重启 Adapter 或更改部署。核对服务再次 `active/running`、进程启动标识改变、原接口可达，再观察模拟器重连及同一会话的权威状态。对中断期间的请求按真实 ID 查询；未知结果保持未知，不重新提交。若服务不能恢复，立即停止其他注入，按预先确认的原服务恢复方案处理，不覆盖或重建数据。

### 6. 代理影响与 WS/SSE 恢复：待验收，当前未执行

**条件**：先确认代理确实处于待测应用到目标 Core 的路径上、代理负责人及原配置，验证可恢复方案和影响窗口；没有已确认的可控代理就标记阻塞。不得通过关闭证书验证或改用不安全连接绕过身份检查。

**步骤和判据**：在负责人控制下，仅对专用测试流量制造已约定的 WS 升级失败，并保留 SSE；记录实际回退和同步状态。恢复原代理配置后，确认原路径、证书校验及 SSE 连续性，再观察支持探测的客户端/服务是否按协商能力恢复 WS，核对无漏事件和重复正式订阅。旧部署不支持探测时应按实际能力记录，不把未支持当作通过。结束后由负责人确认代理与基线一致。

### 7. 响应丢失：待验收，当前未执行

**条件**：有专用测试账号和可安全丢弃单次响应的受控链路；可分别观察 Core 是否已持久受理、宿主/设备是否实际执行及回执状态。记录测试 `clientMessageId` 或 `requestId`，先确认对应恢复能力已协商。无法证明服务端是否受理时，不能把结果称为成功或未执行。

**步骤和判据**：先用无敏感内容的测试请求，精确在服务端已受理后的响应返回途中丢失一次响应；恢复链路后按原身份查询回执、事件与终态，核对宿主执行次数，不能换 ID 重新发送。对设备结果上传另做一条独立的低风险测试：在结果已持久保存但响应丢失后，只允许按原 `requestId` 补传同一结果，并以独立计数核对插件未再次执行。已开始但无法核实结果时保留 `outcome_unknown`；如没有可信执行计数或回执证据，记为未验收。完成后恢复测试链路并确认原代理配置。

## 交接

下一步由主流程提供已完成且可追溯的 APK，并确认目标 Core 的部署版本、代理路径和恢复负责人；再重启保留的独立 AVD，先完成认证及普通会话基线，之后按上述顺序逐项执行。真机场景仍属于验收范围，当前未完成；模拟器结果不能替代真机或厂商 Doze/后台验收。
