# Android Local-First 与消息同步修复记录

日期：2026-11-03（Asia/Shanghai）。实施基线：`733c6a3079cfc33ad3f6f7aa01b7bf242b782ee4`。对照 main：`d88fffed134f57c5cf6ca4733e65b61f6e13120d`。

## 范围与证据等级

保留冷启动新对话页。以 Account / Core / Agent / Conversation 隔离的本地镜像独立支撑浏览，远端同步在后台进行。保留可靠发送、事件持久化后 ACK、现有 UI 布局与未涉及文案。

- 静态事实：已有源码与历史可确认的行为。
- 自动化复现：必须记录实际失败命令、断言和修复后通过结果。
- 设备验收：必须绑定 APK 来源与摘要，单独记录模拟器和真机范围。

## 测试边界与执行顺序

用户已确认 Repository 公开接口、Controller 用户动作/状态流、Compose 真实输入与预览三层边界；每个行为逐条执行 red → green。Terra 准备隔离 API 35 模拟器和合成数据，设备证据另记。

## 已确认静态调用链

以下描述修复前基线；对应回归与修复结果见后文。

1. RemoteBridge.status 失败撤回能力 → Core discovery 返回离线描述 → CoreAgentConversationRepository 在 !list 时返回默认权威空列表 → WorkbenchController.refreshThreads 替换本地可见列表。
2. WorkbenchController.openThread 清空活动消息并设 Loading → restoreLocalTimeline 异步 IO → 网络补历史。基线没有近期会话内存快照直接恢复。
3. rememberDecodedPreview 在 bytes 引用变化时初始化 null → 后台解码；ImageAttachmentCard 在 null 时立即呈现不可用占位。
4. Composer 字符串同步无草稿代次；失败回填只判断当前为空，恢复重试只判断正文相同；后续测试已复现旧提交覆盖用户新草稿。

## main 与 dev 的历史对照

精确对照 `d88fffed` 与 `733c6a30`，没有使用本机陈旧的 main 分支名推断行为。

- main 的 `WorkbenchController.openThread()` 1424–1452 行已经清空当前消息并设置 Loading；因此“切换先 Loading”并非全部由 Core 重构首次引入，不能用 main 代码存在声称旧体验已实测通过。
- `3c9b82d1`（2026-11-01）引入 `canReadConversations`，原意是延迟远端发现前的读取；本修复只将该门禁保留在远端阶段，本地读取不受其限制。
- 原生镜像自 `b7ab791a`（2026-10-28）起接入，之后 `2b00e112` 和 `e53ed6ea` 补充持久事件与观察恢复。当前 dev 不能继续按“native persistence 为空”修复；缺口是列表权威性、稳定本地入口与连接状态耦合。
- main 也已有异步图片解码；本次依据真实失败测试修正加载态，没有将所有闪烁笼统归因于同一个历史提交。

## 自动化复现与修复

### 列表：非权威空结果不能擦除镜像（已复现并通过）

公开入口：`WorkbenchController.state` 与仓储列表响应。已保存列表包含 `cached`，随后接收 `authoritative=false` 的空页。

- Red：`:conversation-ui:testDebugUnitTest --tests '*WorkbenchLocalFirstTest.aNonAuthoritativeEmptyListCannotEraseTheReadableLocalList'` 实际执行失败，断言 `expected:<[cached]> but was:<[]>`；1 项执行、1 项失败，31 秒。本机日志名 `list-authority-red.log`；失败判据如本项所列，测试源码随仓库交付。
- 最小修复：非权威页只补齐已知列表，只有真实权威完整页可以表达缺席与删除。
- Green：同一测试在修复后实际通过；`list-authority-green-resumed.log`，退出码 0。首次 green 尝试曾被未完成的并行 Compose 测试编译阻断，该次不是行为验证。


### 切换：内存命中无需等待 IO（已复现并通过）

- Red：`WorkbenchLocalFirstTest.returningToAnAlreadyReadConversationPublishesMemoryBeforeAnyIo`，A→B→A 后同步状态仍为 Loading，断言 `expected:<[会话 A 的内容]> but was:<[]>`。`memory-timeline-red.log`，实际 1 项失败，33 秒。
- 修复：有界近期快照保留消息、正文和投递修订；离开会话时保存，返回时同步恢复。跨作用域不可复用，明确删除和非当前会话事件会失效旧快照，关闭时清理。
- Green：`memory-timeline-green.log`，包含已完成的两项 Local-First 测试，退出码 0。磁盘与远端合并继续后台执行。

### 列表：发现前读取持久镜像（已复现并通过）

- Red：有持久列表但无工作台 Checkpoint，Agent 尚未发现；`local-list-discovery-red-resumed.log` 实际断言失败，1 项失败，11 秒。
- 修复：新增本地列表读取端口（null 区别于已保存空快照），控制器先读本地，再单独检查远端读取能力；原生镜像与旧会话面都复用加密 SQLite。
- Green：`local-first-draft-green.log`、`local-first-controller-green.log` 已通过；后续组合定向 `local-first-ui-regression.log` 79 项、`local-first-ui-acceptance.log` 80 项通过，逐步扩展回归中。

### 草稿、发送与图片：实际失败判据

| 行为 | 实际修复前断言 | 修复 |
| --- | --- | --- |
| 用户清空新草稿后旧提交失败 | expected 空；actual `已经提交的旧文字` | 回填检查草稿修订，编辑器与控制器同步冻结并推进 epoch，旧回调失效 |
| 重试尚无远端回执 | 本地启动被标为 ACCEPTED | 保留 authoritative、Queue/Steer 模式及原客户端身份；未知结果先查询 |
| 显式空草稿重建 | 从旧发送回填文字 | draftExplicit 和每会话 composerDrafts 保存在加密 Checkpoint，旧记录兼容读取 |
| A 上传中切到 B | 原 A 提交未发送、预览丢失 | 冻结提交持有独立附件状态；上传完成仍按原会话、clientMessageId 提交 |
| 从空白页上传后打开 B | 消息发到 conv_b，B 出现 A 气泡 | 空白页拥有独立创建意图，不借后来的 activeThread；创建结果不夺取用户导航 |
| 用户明确取消上传 | `显式取消后上传完成也不能自动发送` | 同时撤销等待和恢复记录，保留下一份草稿 |
| 重建 B 只应恢复 B 附件 | expected attachment-b；actual attachment-a、attachment-b | 按持久会话附件 ID 选择恢复，不使用协调器全部 restoredDrafts |
| 原生实时两个真实 ID 共用 client | 实时入口吞掉一个真实 ID | 只替换明确本地占位；历史与实时保留相同身份集合 |
| 本地缩略图读取/异步解码中 | 合法 PNG 仍显示“图片预览不可用” | Pending/Ready/Missing/Failed 分离，已显示图片连续保留，真正失败仍显示既有错误 |
| 缩略图快照暂缺字节 | 已显示的 preview 变为 null | 临时缺省保留已有字节；相同内容复用；解码读取有预算的 owned 副本 |
| 迟到原生历史正文 | 远端修订3覆盖已保存实时修订5 | 先持久合并，仓储返回本地 canonical 页；正文、状态、generation 修订分别比较 |
| 两次完整列表乱序 | 旧空列表仍能成功落盘 | SQLite read epoch 屏障拒绝旧写入；实际完整权威空列表继续生效 |

所有上述文字均为合成测试数据。

审查后的补充回归另覆盖：空白页创建失败后冻结提交仍持久、A→B→A 后显式取消仍阻止发送、控制器重建显示冻结消息而不自动发出、旧未登记 Agent 不能通过首次认领取得原件。旧上传暂存按照该 Agent 镜像的可信 draft IDs 迁移，旧 key 必须已存在；不会为读取旧密文生成替代密钥。新增状态写盘失败保持旧 ACK 位置，未改既有 eventSync 页和 ACK 顺序。原图、预览、草稿、下载恢复使用同一个 Agent 子范围；旧原件除长度和摘要匹配外，还要求旧加密索引具有可靠的 Agent 来源；登记表不被视为完整旧分区清单，无来源的旧原件继续保留，不自动认领。旧上传暂存则仅按该 Agent 持久 Checkpoint 已保存的精确草稿 ID 迁移，保留原 draftId、remoteAttachmentId、clientAttachmentId 和旧加密源，不自动发送。

### 定向验证结果

- `identity-batch-final`：11 项，通过。
- `remote-bridge-final-green`：13 项，通过。
- `storage-agent-batch-final`：23 项，通过（NativeMirror、NativeLocalSnapshotRead、NativeMediaIsolation、NativeAgentsController）。
- `native-regression-repaired`：80 项，通过（含 CoreAgentRepository、ColdStart、AuthLifecycle、LocalConversationFirstFrame）。
- `local-first-ui-acceptance`：80 项，通过；覆盖读镜像、输入、消息、预览和滚动。
- `controller-final-acceptance`：91 项，通过；覆盖扩展的语音、显式取消、创建、关闭、上传切换与图片读取场景。

这些阶段结果不可累加为唯一用例总数；后续门禁绑定最终提交重新记录。早期编译错误、输入伪造不成立的“红灯”和旧 XML 残留均不计作症状复现。无论通过或失败，报告只采用实际执行的命令与退出结果。原始本机日志位于忽略的 evidence 目录，不作为仓库可点击交付；可复核的失败判据、命令和最终计数直接保存在本报告及测试源码。

## 性能与设备结果

基线专用 API 35 模拟器已复现正常 Agent 下线时列表 `1 → 0 → 1`，Core 仍可达。暂停进程的另一个时序未复现，不能混为同一结论。测量与前后对照由 Terra 记录在 [设备验收报告](2026-11-03-android-local-first-device-acceptance.zh-CN.md)。Activity 启动时间不计为消息首帧；首帧日志是 Compose 绘制完成，不宣称物理显示。

候选 1 为 `d7036614`。同一 API 35 模拟器、同一合成账号和会话的数据见 [可复核统计](artifacts/2026-11-03-android-local-first-sync/first-draw-summary.json)。全部分组样本较少，P95 尾部估计不稳定；不同来源和样本数不宣称严格基准排名。

| 来源 | 样本数 | 中位数 ms | 最近秩 P95 ms |
| --- | ---: | ---: | ---: |
| 基线 733c6a30＋相同观测补丁：local | 10 | 1484.31 | 2055.01 |
| 候选 1：local | 5 | 496.56 | 871.87 |
| 候选 1：memory | 5 | 43.09 | 54.49 |
| 候选 1：remote 首次读取 | 5 | 638.42 | 789.64 |

基线没有 memory 路径；remote 基线采样超时，暂无有效样本，不伪填对比数值。最终候选对追加故障边界的功能复验另记，不把候选 1 的测量冒充最终 APK 新测量。设备上构建 `403c5c58` 已看到 Core 离线的新图片缩略图和本地原图，原件证据见 [离线大图](artifacts/2026-11-03-android-local-first-sync/final-new-image-offline-open.png)。只证明该合成图片和时序，不等同真机或全过程无闪烁。

## 审查与回归风险

分别执行规范审查和需求一致性审查。规范审查指出证据链接落在忽略目录，已将可复核统计、脱敏采样和关键截图移入 `docs/reviews/artifacts/`；工具支持既有环境变量覆盖并通过语法和 12 项工具自测。

需求审查指出的上传创建失败丢恢复身份、回切取消遗漏等待项、交接新输入丢失、跨会话附件恢复、Queue 模式丢失及缺缩略图误报，均通过相应公开接口的实际失败测试锁定并修复。后续又补充“替换控制器已经发送后，旧编辑器转发不能复活草稿”的修订屏障；`8b89011d` 定向 39 项通过。没有放宽事件历史覆盖或 ACK 门禁来掩盖夹具问题。

旧媒体安全边界：登记表无法证明覆盖升级前的全部 Agent 分区。无来源旧原件不因首次访问、相同摘要或当前唯一登记 Agent 而认领，原加密数据继续保留；此限制会影响更早图片在未联网时重新显示。上传恢复的精确草稿身份另有迁移，不混用历史原件的归属推断。

## 门禁与环境修复

- `test:fast` 在 `d7036614` 业务源码上完整通过：Core 692 passed、1 skipped、6 subtests；Android domain 38、UI 711、App 1418，均 0 失败，`noVpnSurfaceCheck` 通过。
- 最终 `test:pr` 在业务提交 `8b89011d` 上通过：Core 692 passed、1 skipped、6 subtests；domain 38、UI 715、App 1421，均 0 失败。已合并 dev 的 `5aff45af`（仅 AGENTS.md），不包含无关业务修改。可复核计数见 [自动化摘要](artifacts/2026-11-03-android-local-first-sync/automated-validation.json)。
- 第一轮卸载测试因本机回收目录为 0755 被安全校验拒绝，10 项失败；检查当前用户所有权及非符号链接后只收紧为 0700，保留全部内容；复核卸载测试 36 passed、1 skipped。
- 第二轮隔离 worktree 缺少六个未变更设备插件 WASM fixture，App 多项 FileNotFound；复用主仓现存产物并逐个核对 SHA-256，没有重建或修改插件。
- 旧 Core 无 eventSync 的兼容用例说明：宿主即使停止接受执行，仍可能有授权 history 能力；远端 timeline 不用 `online=false` 禁止真实历史读取，local-first 门禁仍不受远端影响。此用例与媒体专项共 9 项通过。

## 分批提交

| 提交 | 内容 | 已执行验证 |
| --- | --- | --- |
| `8b89011d` | 持久草稿状态版本阻断已冻结后旧编辑器转发 | 定向 39 项通过，最终门禁见摘要 |
| `22dadd24` | 依可信 Checkpoint 草稿 ID 迁移旧附件暂存，保留远端/上传身份及原件 | followup 747 项、最终 test:pr 通过 |
| `5e901e94` | 重建后展示持久冻结消息并保留恢复入口 | 30 项定向测试通过 |
| `d8c39344` | 无可靠来源旧原件拒绝认领 | NativeMediaIsolation 10 项通过 |
| `913d568f` | 保留创建失败的冻结上传并支持 A→B→A 后取消 | WorkbenchLocalFirst、PendingAttachmentSwitch、SendPersistence、SendRegression、VoiceSend，87 项，通过 |
| `7180c64a` | 本地优先、Agent 镜像与媒体子范围、会话草稿与权威读合并 | test:fast 通过 |
| `d7036614` | 编辑器 epoch、图片加载态与滚动锚点 | test:fast、test:pr 通过 |
| `efc3f93` | Remote 桥拒绝缺字段、错误类型响应，保留合法空列表/历史 | `PYTHONPATH=oai-core/src` 的 `test_generic_agent_plugin.py`，13 passed |
| `28808a4c` | Agent 暂时缺席与发现失败时保留选择身份，不伪装在线 | NativeAgentsControllerTest 全类 5 项，通过 |
| `35ba0409` | 发送原稿按真实消息 ID、账号内会话和 sender 匹配；歧义不选一；墓碑保护 | LocalSendIdentityConflictTest、LocalSendAtomIdentityTest、LocalSendAtomOrderTest，11 项，0 失败 |

必须显式设置 `PYTHONPATH=oai-core/src` 使用当前 worktree；未设置时现有 venv editable 指向主仓，实际出现过旧源码导致 4 项失败，该次不代表本补丁失败或通过。

## 交付与剩余风险

业务实现与本地自动化验证完成，按 feature 分支交付目标 dev 的 PR，不自动合并。真机、真实模型及生产部署尚未验证；没有修改或重启既有生产 Core/Agent。加密媒体无法唯一归属时保留旧文件且不自动迁移，符合隔离要求但不能承诺所有旧原件在未联网时重新出现。
