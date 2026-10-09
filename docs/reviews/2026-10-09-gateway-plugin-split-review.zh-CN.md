# Gateway 插件拆仓后主仓审查

- 审查日期：2026-10-09（北京时间）。
- 当前提交：`6f923002b7a97c131da7621a91e310d2c80f94bc`。
- 迁移前基线：`95f2e1702a6e360b6779c6c69667d36a0e96d683`。
- 范围：Hermes/OpenClaw 拆仓提交及后续修补；当前主仓的 Android、契约、CI、插件权限与本地测试工具。
- 方法：独立迁移审查、独立 App 架构审查、独立安全审计，加主审查者的源码复核、最小本地验证及当前 CI 日志核对。
- 交付状态：审查完成，生产代码保持原样；问题尚未修复。完整安全读取并去重核对了 99 个当前跟踪文件，仓库共有 1,132 个跟踪文件，本报告不宣称全库穷尽覆盖。

## 结论

**两个生产插件的源码迁出已经完成，但依赖锁定、测试门禁和跨仓修复流程尚未闭环，不能认定为完整干净的迁移。**

Android 生产依赖中没有残留服务端插件源码导入；迁移范围内的核心 Schema、向量、分发注册表和契约源码均未变化。当前 App 内置核心摘要与本仓七个核心 Schema 的字节摘要一致。本轮本地 OpenClaw/Hermes 各 66 个向量和跨宿主比较均通过，当前提交的 CI 与 APK 构建也成功。因此，本次没有找到可直接归因于拆仓的 App 生产协议破坏。

与此同时，Android 互操作测试在当前 CI 中来自缓存，契约门禁自身测试实际失败；这些事实限制了 CI 绿灯能够证明的范围。当前 App 另有四项受保护插件安全边界问题，本地 E2E 工具有一项条件性的口令日志泄露问题。四项 App 安全问题的相关生产文件在迁移前后没有差异，属于此次审查发现的已有风险。

详细安全数据与源码证据见 [工具生成的安全报告](evidence/2026-10-09-gateway-plugin-split/security-report.md)。该报告是已完成扫描的导出副本，覆盖标记为部分，包含四项中等严重性问题和一项低严重性问题。

## 对 App 的实际影响

| 项目 | 本轮事实 | 可支持的结论 |
| --- | --- | --- |
| 生产依赖 | `apps/android/app/build.gradle.kts:88-106` 只组合本仓 Android 模块 | App 运行通过网络使用 Gateway，服务端源码迁出不会成为 APK 的源码依赖缺失 |
| 核心契约 | `git diff 95f2e17 HEAD -- gateway-contract/{schemas,vectors,src}` 及注册表没有差异 | 拆仓没有改变这些协议输入 |
| 手机摘要 | 独立重算等于 `SchemaContractHash.CORE` | 当前手机核心摘要与本仓契约一致 |
| 语言互操作 | OpenClaw/Hermes 各 66/66，跨宿主 3/3 | 当前两份被验证实现对这些向量结果一致 |
| APK 构建 | 当前提交 `android-apk.yml` 成功 | 当前 APK 可以编译，不能据此证明实机功能或权限安全 |
| Android HTTP 互操作 | 当前 CI 的 `conversation-data` 两个单元测试任务均 `FROM-CACHE` | 此次没有重新执行相应 Python fixture/HTTP 链路 |
| 真实设备与宿主 | 本轮没有启动设备登录、插件授权、真实宿主加载或生产服务 | 仍需以明确部署身份补充这些证据 |

独立重算的核心摘要：

```text
sha256:99309b87dec79f9c737b7d875a6e949ceffb6bb18b468d7ce98ca37d77dd3d50
```

后续改变核心 Schema 时，仍须同步更新 Android 与两个插件的契约版本和摘要；只更新一端会在认证前被拒绝。本次迁移没有产生该摘要变更。

## 已确认的安全问题

这里的 P1 表示应优先安排修复，P2 表示常规修复优先级；它们与安全严重性等级是不同维度。

### S1 · P1：清单中的文件引用可越出包目录，并导致宿主无界读取

- 严重性：中等；分类：`CWE-22`；置信度：高。
- 位置：`ProductionPluginHost.kt:89-95`、`:105-113`；`AlpVerifier.kt:406-423`；`PluginManagementScreen.kt:104-112`，分别位于 Android App 插件目录与 `plugin-package` 模块。
- 前提：用户安装作者有效签名的受保护 `.alp` 包；不需要开启原生信任模式、启用插件或授予配对能力。

`provides[].schema` 只被要求是字符串，未验证其必须指向已验签的包内文件。宿主在安装刷新和冷启动的 `reload()` 中直接以该字符串构造文件并 `readText()`，发生在启用和授权检查之前。`ui.cards` 第一次解析有目录、普通文件和大小检查，但随后计算卡片 ID 时又未经这些检查读取一次。

因此，清单引用中的 `../` 可以到达包外文件。没有 EOF 的可访问设备流会在无界读取时耗尽资源；包外有效 JSON 也可进入能力 Schema。ZIP 项本身的防遍历和大小限制不能覆盖清单引用。签名证明作者身份，不能替代受保护模式的文件边界。

**验证层级：**主审查者复核了验证、安装、刷新和读取调用链；没有执行耗尽内存的载荷或实机安装，没有证明私钥或刷新凭据明文泄露。

**修复与验收：**验包阶段统一验证所有文件引用，要求安全的包内相对路径、签名文件索引、解析后目录包含关系、普通文件与读取上限；运行期沿用已验证资源。移除未经检查的第二次卡片读取。合法签名包引用包外 Schema/UI 时，应在安装前被拒绝。

### S2 · P1：远程读取被当作前台调用，绕过后台执行授权

- 严重性：中等；分类：`CWE-863`；置信度：高。
- 位置：`DeviceExecutionDriver.kt:83-91`；`ProductionPluginHost.kt:242-247`；`PluginKernel.kt:269-271`；`GatewayRuntime.kt:768-772`。
- 前提：Gateway 已配对并获得相应读取权限，App 处于后台但进程和会话仍然存活。

远程读取不进入前台确认分支。真正调用 `host.invoke(...)` 时省略 `background`，宿主默认 `false`，内核因此跳过以 `session.background` 为入口的后台授权检查。真实前后台回调存在，却只用于需要确认的请求。官方通知和通话记录插件声明未请求后台执行，仍可经过该路径被调用。

影响是把已授予的前台读取权限扩大到后台时机；已有插件启用、作者、提供者、系统权限、配对授权和期限检查仍然有效。

**验证层级：**生产调用链已复核；当前源码的纯内核探针确认没有后台 grant 时，真实后台标识得到 `backgroundAllowed=false`。没有执行设备后台远程读取闭环。

**修复与验收：**执行时基于真实生命周期传入后台状态，同时核清单后台请求、手机限制及该插件身份绑定的后台 grant；执行过程中转入后台时也要重新裁决。只授前台读取时，后台请求必须被拒绝且不得调用敏感原语。

### S3 · P1：插件联网未执行已声明的路径范围

- 严重性：中等；分类：`CWE-863`；置信度：高。
- 位置：`ProductionPluginHost.kt:290-315`；`MediatedNetworkProxy.kt:46-50`、`:112-125`；契约 `docs/contracts/device-plugin-package-v1.md:220-231`。
- 前提：受保护插件已安装、启用并获得联网原语授权。

签名网络规则有 `pathPrefix`，但宿主只提取主机和方法，`NetworkAllowlist` 没有路径字段，代理也不检查请求路径。声明只访问 `/v1/public/` 的插件实际可以向同一域名的其他路径发请求，重定向同样没有路径规则可执行。

**运行证据：**本轮直接编译当前 Kotlin 源码，真实代理将 `/private/admin` 交给本地替身传输层。没有联网或访问实际管理接口。精确主机、方法、443、TLS、重定向次数和流量预算仍然有效，因此不能把这项问题扩大为任意主机 SSRF。

**修复与验收：**完整保留网络规则，规范化路径后按路径段匹配前缀，每次重定向重复检查。范围外路径、点段和编码越界应在调用传输层之前被拒绝。

### S4 · P1：同作者更新扩大网络方法时，不要求重新批准

- 严重性：中等；分类：`CWE-863`；置信度：高。
- 位置：`PluginUpdatePolicy.kt:9-22`、`:60-85`；`AlpVerifier.kt:462-506`；`PluginInstaller.kt:51-68`；`ProductionPluginHost.kt:120`。
- 前提：已安装同作者插件，原联网能力、主机和资源范围已获授权，作者发布更高版本。

安全范围模型只记录 `networkHosts`，没有记录每条规则的方法和路径。原 GET 范围扩大到 POST，在其余字段不变时仍被判为 `AutoApply`，安装器不要求扩张确认。保留的作者指纹会使已启用插件继续启用，版本变化也不会自动清除身份绑定的旧授权；新代理却开始接受新增方法。

**运行证据：**临时编译当前 `AlpVerifier` 和 `PluginUpdatePolicy`，通过真实清单解析把同域 GET 改为 POST，版本 `1.0.0` 升到 `1.0.1`，实际分类为 `AutoApply`。该探针验证解析和分类，不代表完整签名安装或实际网络写操作。没有自动更新轮询也不消除问题，用户主动安装新版同样经过这项错误判断。

**修复与验收：**以完整规范化规则比较更新前后的安全范围，方法或路径扩张进入待批准状态；核对后台频率等其他契约安全维度。新增 POST 和扩大路径范围必须要求批准，缩小范围可以继续普通更新。

### S5 · P2：E2E 创建账号时将自定义口令打印到 stdout

- 严重性：低；分类：`CWE-532`；置信度：高。
- 位置：`e2e/android-cli/run-e2e-orchestrator.py:685-688`、`:588-590`。
- 前提：向编排器传入真实或复用口令，控制台或收集 stdout 的日志可被未获口令授权者读取。

完整账号创建命令包含口令，创建前直接 `print`。命令还保存在内存中的 `commands_executed`。默认公开测试口令不是新增秘密；当前总结 JSON 对阶段字段作了白名单选择，并没有导出完整命令，因此这里仅确认控制台/收集 stdout 的泄露路径。

**修复与验收：**日志仅保留操作和账号，命令展示和异常统一隐藏密码。使用合成标记测试 stdout、异常和展示记录均不含该标记。

## 迁移与验证流程的缺陷

### M1 · P2：Hermes 插件实现未锁定，无法可靠关联主仓发布

位置：`.github/workflows/ci.yml:46-53`、`:127-144`；`gateway-contract/tools/run-hermes-conformance.py:26-33`；`docs/mvp/mvp-dependency-lock.md:18`。

两个 CI 作业均无 `ref` 检出 Hermes 默认分支。契约 pin 验证“插件使用的契约是否与主仓相同”，没有锁定“本次 App 验证的插件实现”。现有 Hermes 依赖锁行锁的是宿主，不是新插件。Python runner 直接导入环境中的包，不用 `HERMES_PLUGIN_ROOT` 选择实现。

本次日志可恢复出两个作业实际都使用 `3e3b737333a803c92c29e1b6f22d25ca5bdb12fc`；这个事实排除了本次两作业取到不同版本，但后续相同主仓提交重跑仍可能取到别的实现。

修复：增加主仓维护的 Hermes 插件完整 SHA pin，由检出、runner、依赖记录和发布说明共同消费，实现 pin 与契约 pin 分别验证。

### M2 · P2：Android 测试缓存没有跟踪外部插件和契约文件内容

位置：`apps/android/conversation-data/build.gradle.kts:26-40`；`apps/android/gateway-client/build.gradle.kts:1-20`；`apps/android/gradle.properties:1-4`。

`HermesHttpInteropTest` 执行外部 fixture 和 Python 包、读取主仓契约；`SchemaContractHashTest`、`DispatchedSchemaVectorTest` 等读取模块外 Schema 和向量。Test 任务没有声明这些文件内容为输入，路径字符串不变时，旧结果可能继续复用。

当前 CI 实际显示 `conversation-data` debug/release 测试均 `FROM-CACHE`，证明本次没有执行相应 fixture/HTTP 链路。后续只改 Schema 且漏改手机摘要时，缺少契约文件输入的缓存也可能绕过手机摘要守卫；当前摘要独立核对相等，不能声称该故障已经发生。

修复：互操作任务先关闭缓存和 up-to-date 跳过以确保实际执行，或独立成拥有完整插件/契约/Python依赖指纹的任务。`gateway-client` 的 Test 声明相对 Android 根工程的 `../../gateway-contract/schemas`、`../../gateway-contract/vectors` 为输入。验证应在隔离副本温热缓存后，仅改 fixture/核心 Schema/registry，确认任务重跑，错误摘要被拒绝。

### M3 · P2：契约 pin 门禁自身测试失效，而且没有进入 CI

位置：`gateway-contract/tools/check-contract-pin.py:30`、`:96-99`；`test_check_contract_pin.py:53`、`:63-108`；`.github/workflows/ci.yml:99-106`。

脚本常量改名为 `DEFAULT_PLUGIN_ENV`，五项测试仍引用 `PLUGIN_ENV`。实际 pytest 为 **5 失败、3 通过**。即使只补常量，测试调用 `main()` 还会让 argparse 读取 pytest 的参数并退出为 2，不能以常量别名修复代替真正恢复测试。CI 当前只收集插件仓的 Python 测试，根 Node 测试也不会执行这组测试。

修复：显式 `main(argv=None)`，测试调用 `main([])`，更新常量引用和环境清理语义，并把门禁自身的 pytest 纳入主仓 CI。

### M4 · P2：SSH origin 被错误识别成不同仓库

位置：`gateway-contract/tools/check-contract-pin.py:49-57`、`:126-132`。

同一仓库的 SSH 地址被规范为 `1rua/open-android-intelligence`，HTTPS 地址却为 `github.com/1rua/open-android-intelligence`。使用 SSH clone 的开发者会被门禁拒绝，即使契约完全相同。当前 HTTPS CI 不触发。

修复：解析 scp 风格 SSH 地址时保留主机名，加入 SSH、HTTPS 和 `ssh://` 等价及不同主机不等价的测试。

### M5 · P2：网关缺陷仍在应用主仓工作区中修复

位置：`e2e/android-cli/run-e2e-orchestrator.py:376-417`、`:452-476`、`:647`、`:766`。

网关工单已指向 `hermes-gateway-plugin`，工作区分配、基线、回归和合并却仍使用主仓。只读 mock 探针确认，针对外部 `admin.py` 的工单仍从 App 的 `6f923...` 创建工作区，未读取 `HERMES_PLUGIN_ROOT`。修复者拿不到问题所属仓库，主仓 `npm test` 不能证明外部 Python 修复。

修复：让工单显式携带仓库归属，按仓选择基线、工作区、测试和集成目标，再更新主仓插件 pin。多仓修复尚未支持时应明确阻断该类工单。

### M6 · P2：干净环境安装指引漏装 Hermes 包

位置：`README.md:140-150`；`gateway-contract/tools/run-hermes-conformance.py:26-33`。

校验步骤安装根 npm 与 OpenClaw 后即跑双宿主套件，没有安装必需的 Hermes Python 包。干净环境的 Hermes import 会失败，单设插件根也不改变该 runner 的 Python 搜索路径。

修复：明确检出并安装锁定 Hermes 插件及其声明依赖，然后验证两端契约 pin 和一致性。插件仓 CLI 示例也应说明工作目录。

### M7 · P2：本地 E2E 的 Shell 拼接会改变账号口令或路径

位置：`e2e/android-cli/run-e2e-orchestrator.py:161`、`:227-246`、`:685-686`。

双引号字符串经 `shell=True` 执行，不能阻止变量和命令替换。无写入 argv 探针已确认合成口令 `Secret$USER` 会被 Shell 改变。本次新增了 `HERMES_PLUGIN_ROOT` 拼接；账号口令拼接在迁移前已经存在。

当前主入口只使用固定测试值，配置来自本地操作者，未建立远程或低权限攻击者到这些参数的有效数据流。因此保留它作为确定的工具正确性缺陷和条件性危险，没有把它列作当前 Android/Gateway 远程代码执行漏洞。

修复：账号 CLI 返回 argv 列表，所有账号和 status 操作使用现有 `shell=False` 分支，并配合 S5 隐藏日志口令。

## 清理与覆盖缺口

两个生产目录、根 `hermes-account.py` 和 `tsconfig.openclaw.json` 已从 Git 删除；共享测试已改为明确标识的 legacy 夹具。历史审查和历史迁移计划中的旧路径符合 ADR 0052 的证据保留要求，不计为活跃源码残留。

仍有低风险清理项：

- E2E `:84-86` 仍把已删除的 `integrations/hermes` 放入 Python 搜索路径。
- `docs/mvp/README.md:13` 写了不存在的 `gateway-contract/tools/check-openclaw-plugin-pin.py`，实际在根 `tools/`。
- `.gitignore` 仍含旧 Hermes 构建目录条目；新增代码和 CI 的部分英文注释未遵守中文注释约定。
- 总架构规格 `:231-232` 对 WebSocket 的相邻描述冲突；当前普通事件支持 WebSocket/SSE，而平台事件仅 SSE。

两项本地门禁限制尚未做临时内容注入复现，记录为覆盖缺口：OpenClaw 本地 pin 校验只核 HEAD/版本/入口而未核整个工作区内容；本地 smoke 缺少最新 CI 已补的未跟踪 runtime 检查。契约门禁的 `git diff` 不涵盖未跟踪新文件。这些不构成当前已证实的远程 App 漏洞。

已核对的安全反证：HTTP/SSE 和 WebSocket 在发送认证前核 TLS 身份；已保存 HTTPS 身份不能静默降级；插件存储同时绑定配对，正文数据有 Gateway/账号/安装 scope。未确认任意主机 SSRF、远程 App 执行、跨账号读取、明文刷新凭据泄露或私有正式签名身份披露。公开 Debug 身份和测试签名材料按实际用途区分。

## 验证记录

| 验证 | 结果 | 范围与局限 |
| --- | --- | --- |
| 当前 CI 37807071462 | 成功 | 双向量各 66/66；跨宿主 3；Node 105 文件、919 测试；插件工具 46；Hermes 312 通过、1 跳过；Android check/assemble 成功但有关测试来自缓存 |
| 当前 APK 37807070559 | 成功 | 构建证据 |
| 本地 OpenClaw pin/两端契约 pin | 通过 | OpenClaw 固定 `f4a04a28d4cc419de4c544d7c99bb08d62a3ee97`；两端契约内容相同 |
| 本地双端向量 | 各 66/66 | OpenClaw 同上；Hermes `3e3b737333a803c92c29e1b6f22d25ca5bdb12fc`，与当前 CI 的实际检出相同 |
| 本地跨宿主比较 | 3/3 | 重新生成结果后比较 |
| 本地契约与共享测试 | 8 文件、171 测试通过 | 六个 gateway-contract 测试文件加通知/SMS 共享测试；不宣称本地全量测试 |
| E2E 编排 unittest | 19/19 | 补齐插件路径与 jsonschema/cryptography/aiohttp 后通过；包含模拟和编排测试，不是实机 E2E |
| 契约 pin 自身 pytest | 3 通过、5 失败 | M3；原始输出已保留 |
| Android Python 静态守卫 | 88 项、15 失败 | 失败多为旧宿主装配/助理结构断言与现架构不符；这些套件仍不绿，未把它们自动解释为漏洞或归因于拆仓 |
| MVP 依赖锁 | 失败 | 8 行中 ANDROID/ARTIFACT/BRIDGE/HERMES/MODEL/TSNET 六行证据过期；未自动刷新证据 |
| 当前 Kotlin 边界探针 | 通过并确认错误行为 | 范围外同域路径进入替身传输、GET→POST 更新仍 AutoApply、无后台 grant 的真实后台判定拒绝；没有联网或恶意设备操作 |

当前 CI 链接：[完整 CI](https://github.com/1rua/open-android-intelligence/actions/runs/37807071462)、[APK 构建](https://github.com/1rua/open-android-intelligence/actions/runs/37807070559)。

本地 Python 为 3.14.6，Node 使用仓库已有 24.18.0；当前 CI 的 Node 固定 24.18.0，Python 作业分别为 3.12 和 3.11。本轮测试依赖位于隔离临时目录，未改全局 Python 环境，也未改兄弟插件仓。初次编排测试因缺包/依赖失败，补齐后通过，初次环境失败不作为迁移缺陷。

复现源码、[边界探针输出](evidence/2026-10-09-gateway-plugin-split/boundary-probe-output.txt)、[pin 测试输出](evidence/2026-10-09-gateway-plugin-split/contract-pin-tests.txt)、[OpenClaw 向量结果](evidence/2026-10-09-gateway-plugin-split/openclaw-conformance.txt)和 [Hermes 向量结果](evidence/2026-10-09-gateway-plugin-split/hermes-conformance.txt)均随报告保存在仓库。

在仓库根使用现有本地 Kotlin 编译器缓存运行纯源码探针，结果写入独立临时目录：

```bash
TASK_PROBE_DIR=$(mktemp -d /tmp/open-android-intelligence-review-probe.XXXXXX)
python3 docs/reviews/evidence/2026-10-09-gateway-plugin-split/run_boundary_probe.py "$TASK_PROBE_DIR"
```

探针使用缓存中的 Kotlin 2.0.21、JDK 与当前源码；不复用本仓陈旧的 `build/classes.jar`，不执行 Gradle 或真实网络请求。临时文件留存供复核。网络更新探针调用真实清单解析器和分类器，绕开了完整签名/安装流程，因此其证据限于解析及判定。

## 后续修复顺序

1. 修复 S1/S2 的文件边界与后台授权，S3/S4 的网络执行规则与更新批准。
2. 锁定 Hermes 实现，恢复 pin 自身测试，补齐 Android 缓存输入并取得一次明确执行的 HTTP 互操作证据。
3. 修复跨仓工作区归属、SSH 地址比较、安装指引、Shell argv 与口令日志。
4. 校准旧静态守卫和过期依赖证据，再以明确的 App/两插件部署版本补充设备及宿主闭环。

本轮仅新增审查文档和复现证据，没有修复生产实现或执行提交推送。存在失败门禁时，不能将本报告理解为发布通过。

扫描工具返回的四线程用量统计：总计 20,389,794 token，输入 20,280,416，其中缓存输入 19,509,376；这是工具归集值，不是重新估算。
