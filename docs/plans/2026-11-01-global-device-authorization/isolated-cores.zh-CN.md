# 隔离 Core 运行准备

本目录提供 Android 全局授权验收使用的本地 Core A/B 准备工具。2026-11-01 已启动两个隔离 Core 并验证 HTTPS 和未认证拒绝；**尚未证明 App 可以登录或执行设备请求**。实体手机与模拟器的验收结论分别记录在[Terra 报告](../../reviews/2026-11-01-global-device-authorization-terra.zh-CN.md)。

## 隔离内容

- `runtime/` 已被本目录的 `.gitignore` 忽略；账号数据库、随机口令、主密钥、证书、日志、临时文件和样本定义均放在其中，不提交或输出凭据。
- Core A 和 B 分别使用 `127.0.0.1:19841` 和 `127.0.0.1:19842`，各有独立的账号库、主密钥、测试 CA、服务器证书、HOME、临时目录及历史媒体目录。
- 每个 Core 均有 `terra_primary` 和 `terra_secondary` 两个专用测试账号；四个口令均独立随机生成。它们用于测试账号及连接边界，不是预先配对的设备或 Agent。
- `runtime/sample-plan.json` 仅定义合成通话、短信、通知和屏幕样本，并未写入设备；不采集或复制真实手机数据。
- 准备脚本从当时已提交的代码生成仅用于离线建号的快照，并记录提交号；它不是待测服务的运行版本。后续服务必须使用收到通知时的工作树代码和协议，记录对应提交及未提交修改。

脚本使用 Python 3.11 以上、Core 的 Python 依赖、`openssl`、`git` 和 `ss`；端口映射还需要 ADB。以下命令从仓库根目录执行，先选择已具备这些依赖的 Python 环境：

```bash
python3 docs/plans/2026-11-01-global-device-authorization/prepare-isolated-cores.py prepare
python3 docs/plans/2026-11-01-global-device-authorization/prepare-isolated-cores.py verify
python3 docs/plans/2026-11-01-global-device-authorization/prepare-isolated-cores.py reverse --serial "$EMULATOR_SERIAL"
python3 docs/plans/2026-11-01-global-device-authorization/test-prepare-isolated-cores.py -v
```

`prepare` 再次运行时只校验已有数据，不重新生成账号或密钥；如果发现未完成的运行目录则停止并保留现场。`verify` 仅用于服务未启动时：它核对忽略规则、目录权限、证书链、账号密码摘要和端口是否空闲。`reverse` 必须指定已授权模拟器，拒绝实体设备、冲突端口和不同的现有映射；相同映射不会重绑。

## 当前服务与重启

本次使用 [start-isolated-cores.py](start-isolated-cores.py) 对当前工作树 `oai-core/src` 与 `gateway-contract` 连续读取两次，确保一致后在 `runtime/source-snapshots/` 创建隔离源码快照。服务以快照启动，后续工作树修改不会自动进入运行进程；源码清单、摘要、配置、PID 和日志均保存在该目录或各 Core 的运行目录。脚本不会重启或覆盖已有监听服务。

2026-11-01 本次服务：Core A `127.0.0.1:19841`，PID `1568268`；Core B `127.0.0.1:19842`，PID `1568269`。两者源码清单摘要均为 `b7944927c56bfb549dd1bcbd82af8d2d7288130afc9278b47da16695fcd29df0`；Git HEAD `2e6a799ca5456125720e27353719ba898a6fc4dc` 之外包含未提交的工作树修改。摘要只覆盖源码与协议文件，不包括现有 Python 环境中的第三方依赖。使用各自 CA 访问设备清单均返回 `401 AUTHENTICATION_REQUIRED`，使用 A 的 CA 访问 B 被证书校验拒绝；这些不证明设备已配对或能执行能力。

后续收到重启通知时，先核对这些 PID、命令行和监听地址是否仍属于本测试实例，停止测试进程后保留 `runtime/core-a/` 和 `runtime/core-b/` 的账号库、密钥及证书；确认两个端口空闲，再执行：

```bash
python3 docs/plans/2026-11-01-global-device-authorization/start-isolated-cores.py
```

重新记录新快照摘要及 PID，分别核实进程、监听地址和实际 TLS，再进行登录、配对及 CLI/MCP 调用。不得将端口监听或后台进程创建当作设备调用成功。CLI 客户端必须使用运行目录内各自的 `--client-config`，并在测试进程中显式信任对应 `tls/v2/ca-cert.pem`；不得读取或覆盖默认客户端凭据。

## 已签名插件包

模拟器现装 APK 不含 `.alp`，插件管理页面也显示未安装任何插件。随后从项目成功的开发发布任务 `41219517875`、提交 `3c9b82d1ef18062fc7dd09546efe6136803079cc` 的 `signed-plugins-1` 产物恢复六个官方签名包，保存在已忽略目录 `runtime/official-signed-plugins-41219517875/`；不是从现装 APK 提取，也没有生成作者密钥。

以 `apps/android` 为 Gradle 工程目录时，`OAI_BUNDLED_PLUGINS_DIR` 在 `:app` 项目中解析，给构建使用的值如下：

```bash
OAI_BUNDLED_PLUGINS_DIR=../../../docs/plans/2026-11-01-global-device-authorization/runtime/official-signed-plugins-41219517875
```

通过 [verify-recovered-plugins.py](verify-recovered-plugins.py) 核对了六个插件 ID、Ed25519 签名、作者一致性和包内文件摘要，当前清单和 schema 与仓库对应文件一致。各包 WASM 与当前本地构建输出不同，不能仅凭签名宣称适配当前工作树；必须在新版 APK 中确认包含正确包并运行设备验收。该脚本不替代 Android 的完整安装验签或运行测试。

## TLS 边界

两套测试 CA 彼此独立，服务器证书包含 `localhost` 和 `127.0.0.1`。本机已做严格证书链检查及内存 TLS 握手测试，验证错误 CA 和错误主机名被拒绝。这些检查没有经过 Android 或 Core 网络服务。

当前 App 走系统 TLS 信任链后才校验协商得到的证书公钥指纹；自建测试 CA 不会因此自动受 Android 信任。真实测试必须通过受支持的测试设备/构建信任配置建立证书信任，并实测握手和指纹；不得关闭证书或主机名验证，不得静默降级到 HTTP。此前缺少严格校验扩展的首版测试证书保留在忽略目录中，现有配置使用 `tls/v2/`。测试证书仅用于本地验收，过期后应停止并评估新的受控测试环境，不覆盖已有信任记录。

## Android自身TLS诊断探针

`CoreTlsConnectivityTest`在目标App的真实Android进程中通过同一GatewayTransport使用平台默认HTTPS，
不携带凭据、不申请设备权限，只检查隔离Core未认证接口是否返回401。
需要先构建并安装对应debug androidTest包，在隔离测试用户执行：

```bash
adb -e shell am instrument --user 10 -w \
  -e class com.openandroidintelligence.mobile.CoreTlsConnectivityTest \
  -e oaiCoreUrl https://127.0.0.1:19841 \
  com.openandroidintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

缺少参数会明确失败。探针不安装CA，不替换TrustManager，不关闭证书或主机名校验；
失败的异常类型用于区分网络/TLS原因，不能把该探针通过当作登录、配对或设备权限验收通过。

## 真实系统通知的合成样本

完成用户10的POST_NOTIFICATIONS和Notification Listener系统UI批准、手机独立推送范围/端点选择后，可运行：

```bash
adb -e shell am instrument --user 10 -w \
  -e class com.openandroidintelligence.mobile.AcceptanceNotificationSampleTest \
  -e oaiAcceptanceSample post \
  com.openandroidintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

此测试通过Android NotificationManager产生固定合成内容，不注入collector、队列或Core数据库。
只有真实Notification Listener收到OS事件、手机按独立范围入队、Core路由、CLI/MCP接收并消费ACK，才可称推送链路通过。
样本fixture的运行通过不能单独证明该业务链路。
