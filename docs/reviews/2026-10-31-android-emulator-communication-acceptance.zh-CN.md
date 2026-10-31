# Android 模拟器通信验收与修复

已按用户要求，由调度配置为 **GPT-6.1 Terra** 的子 Agent 使用 Android CLI 在专用模拟器完成通信和生命周期验收。主 Agent 搭建隔离后端、核对跨层真实身份、采集线程栈、整合修复和复核像素。最终有界场景通过；物理手机、真实模型和生产部署仍未验收。

现网没有升级、重启或故障注入。按用户要求，原始日志、截图、XML、JSON 和压缩测量数据只保留本地；PR 只保留结论文档。此前新增的 122 个非文档证据文件已取消跟踪，约 18.8MB 原文件仍在磁盘，没有永久删除。

## 环境与版本

- 原专用 AVD `emulator-5554`，Android 15 / API 35、x86_64、KVM。保留原磁盘、账号和应用数据，未修改 GPU、动画或锁屏安全设置。
- APK 为 `fullDebug`，包名 `com.openandroidintelligence.mobile`，版本 `2.2.1`、versionCode `1`；不是正式发行升级验收。
- 真实 OAI Core 和真实 Hermes HTTP Session Adapter 分属独立 OS 进程，使用独立测试账号、SQLite、Hermes SessionDB 和 HistoryPort；仅 Agent 执行由受控程序替代，不调用实际模型。
- Android → Core 为签名 JSON HTTP，消息 POST 返回 SSE；Core → Adapter 消息返回 NDJSON。恢复沿用 generation、eventSync、游标和 ACK，未替换传输协议。
- 固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90`。

| 验证包 | 代码关系 | APK SHA-256 |
| --- | --- | --- |
| 首轮业务包 | `dda2a4dc` | `1afbb55747cd0d38e147dd50e3b6efc1fa129762fae1532feffa497729972a99` |
| 镜像锁和恢复提示包 | 构建时 `f49d3d8d` 加修复；源码摘要对应 `1d519a18` | `f29e714ab5c3a635ea61c941e558d4b8d8ee524ed0f34626cc871dfcbbd110a0` |
| 展示键包 | 构建时 `1d519a18` 加修复；源码摘要对应 `99f41bf3` | `9bd8b9d4c317c6a1a743d4b930bbbd6368d00554edaa4a0bae066d169a2840d3` |
| 最终来源修复包 | 构建时 `99f41bf3` 加修复；四个关键生产文件摘要对应 `3b07cb641e1982e55e95487a54b594bd00ef4d1b` | `d72906941d5aaf5becfb65e151076caef9f3cc38a4b444b0e5a4b470ed17fe48` |

APK 在提交前构建，不把 BuildConfig 的旧提交号改称后续提交。最终包逐项摘要与上述修复源码一致，ZIP 完整性和 APK v3 签名验证通过。

## 本轮确认的根因与修改

| 根因与证据 | 修改位置与行为 |
| --- | --- |
| ACK 确认先持镜像锁再等数据库锁，列表保存先持数据库锁再等同一镜像锁。真实线程栈闭合等待；点击创建进入本地保存后没有完成，也没有 Core POST。该反向锁顺序在基线源码中也存在。 | `EncryptedConversationMirror.saveNativeConversations` 统一为先镜像锁、后数据库事务；保持原事务和 ACK 边界。真实监视器回归旧版失败、新版通过。 |
| 冷启动先显示本地镜像，成功连接后仍残留“重新连接后可发送”提示。 | `GatewayRuntime` 在所有者校验后的连接成功边界，仅比较撤回专用离线提示；新的附件恢复告警保持。 |
| 同一输入先显示 local 行，再升级真实消息 ID，列表把它当移除和新增，旧完成层参与退出绘制。 | `TimelineDisplayModel` 记住无冲突用户输入首次展示键；`TimelineEntry` 真实消息对象和协议身份不变，多个真实行不合并，动画未关闭。 |
| 更深的布局诊断确认，过渡状态中 local 与权威行确实同时存在。`mergeLocalSendAtoms` 丢失临时来源标记，使本地完成行被当作第二条真实原生消息；相邻去重也仅看 pending 状态。 | `LocalSendAtom` 合并保留来源及已知 generationId；工作台相邻和全局去重统一优先明确权威行。不按正文或 ID 前缀猜身份。实际合并至工作台的红例旧版输出两行，修复后只留权威行。 |

详细证据：[镜像死锁](evidence/2026-10-30-communication-reliability/emulator/client-mirror-deadlock.zh-CN.md)、[展示身份](evidence/2026-10-30-communication-reliability/emulator/client-ui-key.zh-CN.md)、[临时来源与双行](evidence/2026-10-30-communication-reliability/emulator/client-provisional-row.zh-CN.md)。这些运行样本解释本次复现，不能直接解释所有生产现场症状。

新增创建点击、本地保存开始/完成、权威回执保存、UI 应用以及有界可见项诊断。诊断只包含身份、状态、修订、数量、单调时间和位置，不包含正文、凭据或原始游标。UI 已显示的旧卡死样本，Core/Adapter/宿主对应计数均为零，不能据气泡判断宿主已接收。

## 模拟器实际结果

| 场景 | 结果与证明范围 |
| --- | --- |
| 登录、新建和连续输入 | 权威会话 ID 保存后立即切换。两条用户正文与完整回复各一份；每条 Core POST、Adapter POST、宿主启动/完成各一次。 |
| 高频创建与快速切换 | 多会话创建/切换有界样本通过，见旅程 14；不代表持续压测或多 Agent 真机验证。 |
| 正常 HOME 返回 | 不需登录，历史完整，旧离线提示消失。 |
| 息屏、唤醒与新发送 | 真实显示器 OFF 后唤醒，保持账号；最终来源修复包两张像素截图与布局一致，第三条独立样本也通过。35 条 T9 快照没有同输入双用户行，三次权威身份升级各自 uiKey 不变，没有滚动、刷新或改图形设置清除残影。 |
| 执行中网络断开 | 先确认宿主 running，再关闭模拟器 Wi-Fi 与蜂窝；默认网络确认为 none、App 通道断开。宿主在断网期间完成，网络恢复后自动补回同一终态，原输入未重 POST，之后新输入可继续。 |
| 运行中 Core 强制退出与重启 | 只中断受校验的隔离 Core，Adapter 继续完成。Core 用同一持久目录重启后，App 无需登录、刷新或重开便补回原终态。Core T2 和宿主启动/完成各一次；中断流的 handler 结束日志缺失，没有误记为 POST=0。 |
| HOME 返回后停止 | 先核对同一 running generation，再单次点击停止。两段取消接口各一次、均 200，真实 SSE 终态 cancelled，宿主中断一次、活动轮次归零，正常完成数不增加。 |

最后来源修复只修改本地合并/展示与诊断，前述网络、Core 重启、取消链路没有改动，没有为无关模块反复跑相同真机旅程。旧版像素失败及夹具失败报告保留，不改写为通过。

复核入口：[最终正文](evidence/2026-10-30-communication-reliability/emulator/journey-17-final-messages.md)、[HOME](evidence/2026-10-30-communication-reliability/emulator/journey-18-final-home.md)、[网络恢复](evidence/2026-10-30-communication-reliability/emulator/journey-20-network-recovery.md)、[Core 重启](evidence/2026-10-30-communication-reliability/emulator/journey-21-core-restart.md)、[定向取消](evidence/2026-10-30-communication-reliability/emulator/journey-22-running-stop.md)、[最终像素与来源](evidence/2026-10-30-communication-reliability/emulator/journey-25-provenance-fix.md)。

## 自动化与性能口径

| 检查 | 最终结果 |
| --- | --- |
| 镜像锁回归 | 旧版 1 项失败，修复后完整镜像专项 7 项通过，重新打开后回执/ACK/列表一致 |
| 自动恢复专项 | 47 项通过，含离线提示红例和较新附件告警保留 |
| 展示身份专项 | 新增 5 项及既有执行展示 36 项通过 |
| 原稿来源与真实工作台去重 | 旧版真实两行红例失败；最终 Domain、去重和展示身份目标通过，多个真实 ID 和 local 名称的原生 ID 均保留 |
| `npm run test:fast` | 退出 0，226.248 秒，执行期间输入源码未变 |
| `npm run test:pr` | 退出 0，79.661 秒，执行期间输入源码未变 |
| Android 九模块 | 2403 项，0 失败、0 跳过 |
| Core/安装器、Hermes、DSH、测量器 | 最终门禁分别为 544 passed + 6 subtests + 1 skipped、143、37、25 passed；跳过为既有符号链接文件系统夹具 |

中途环境重置导致隔离服务和模拟器进程丢失，使用原持久目录/AVD 恢复，未当作产品失败或通过。另一次 Fast 因回收目录权限恢复为 0755、卸载器拒绝使用而失败；核对目录归属后收紧到 0700，不删除文件、不放宽安全检查，同一门禁通过。Robolectric 无法捕获真实退出 RenderNode，未用语义断言冒充像素覆盖。

一个锁修复包新建样本，Android 点击至 UI 应用约 555.8ms；两个普通输入请求入口至首内容约 130.8ms、172.0ms。它们在后台门禁负载下取得，样本不足，不能称 P95/P99 或生产性能保证。跨进程没有直接相减未经校准的时钟。

后端同环境 13,000 次前后对照与并发/事件循环指标仍按原固定提交及合成宿主范围解释，见[通信总报告](2026-10-30-communication-reliability.zh-CN.md)。本轮少量模拟器样本不替代该负载测试。

## 未覆盖的真实场景

物理手机、厂商 Doze/省电策略、长时间后台、真实模型、生产代理拓扑和运行进程实际加载版本/能力协商仍未验收。模拟器中网络、Core、执行与 UI 的证据分别说明，不把当前隔离通过扩大为生产整体验收；现网继续保持只读。
