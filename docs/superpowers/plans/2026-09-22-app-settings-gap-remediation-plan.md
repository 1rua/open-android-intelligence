# App 设置功能缺口多 Agent 并行修复方案

- 日期：2026-09-22
- 输入：`docs/superpowers/reviews/2026-09-22-app-settings-feature-gap-audit.md`（类别 1 × 5 / 类别 2 × 12 / 类别 3 × 19，另附「已验证正常」12 项与「未确认」4 项）
- 性质：**方案文档**。本文档不改任何源码、契约或 Schema，只定义后续修复工作的判定口径、分工、波次与验收口径
- 范围：Android 设置界面本体 + 宿主实现（`GatewayRuntime` / `PluginKernel` / 审计 / 授权）+ 插件侧设置 + Hermes 与 OpenClaw 双宿主
- 权威顺序：`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md` > `docs/contracts/gateway-protocol-v2.md` > `docs/contracts/device-plugin-package-v1.md` > `docs/adr/` > `CONTEXT.md` > UI 规格
- 复核状态：本文档写作前已用只读探索复核关键落点（见 §10 复核记录），审计结论无一被推翻

---

## 1. 分类标准与判定依据

### 1.1 三类问题的定义（继承审计 §0，原文照录）

| 类别 | 含义 | 典型证据形态 |
|---|---|---|
| 类别 1 完全未实现 | 有明确需求、入口或声明承载，但全仓不存在实现符号或无任何消费方 | 仅有解析器无渲染器；`build.gradle.kts` 不含该模块；`register/enable` 无生产调用点；无 Manifest 组件 |
| 类别 2 仅前端展示、缺后端逻辑或数据支持 | 界面与本地状态存在，但缺数据源、缺消费方或效果不落地 | 按钮点击必然 no-op；开关只写本地 holder；展示值来自本地自增；纯静态 `SettingsListItem`；无 UI 消费的 notice |
| 类别 3 契约已定义但未实现或与契约不符 | 契约 / Schema / ADR / 规格有明确要求，实现缺失、只实现一端或语义相反 | 契约端点三端皆无；能力位一端声明另一端未声明；同名字段语义不同；字段名不符导致解析退化 |

### 1.2 三问判定法（把定义变成可执行流程）

修复 Agent 拿到一条条目后，按序回答三个问题，用答案组合直接落类别：

```text
Q1 需求承载：是否存在规格 / 契约 / ADR / 文档自述 / UI 入口中的任一明确承载？
Q2 实现与消费：是否存在实现符号，且该符号存在「生产」消费方（非测试、非死代码）？
Q3 数据绑定：界面展示值是否绑定到真实数据源（非本地自增、非写死字面量）？
```

| Q1 | Q2 | Q3 | 判定 | 说明 |
|---|---|---|---|---|
| 有 | 无实现 或 无消费方 | — | **类别 1** | 需求在、实现不在 |
| 有 | 有实现有消费方 | 未绑定 / 绑定到本地自增 | **类别 2** | 界面在、数据在 |
| 有 | 有实现但语义相反 / 只实现一端 | — | **类别 3** | 契约在、实现偏 |
| 有 | 有实现有消费方 | 已绑定真实源 | **不在缺陷清单**（转 §5.6 基线回归） | — |
| 无 | — | — | **不在缺陷清单**，改按「文档/文案失实」或关闭 | 无需求承载即无缺口 |

判定必须落到「生产消费方」而非「符号存在」：`PluginInstaller`（`apps/android/plugin-package/src/main/kotlin/com/openandroidintelligence/plugin/pkg/PluginInstaller.kt:34`）符号存在但 `app/src/main` 零引用，仍判类别 1（1-1 旁证）。

### 1.3 证据分级（决定结论强度）

| 级别 | 形态 | 可作判定依据 | 要求 |
|---|---|---|---|
| **A** | `文件:行号` 直接命中并摘录原文 | ✅ 可单独定案 | 必须给出原文片段 |
| **B** | 符号名命中（可定位到定义） | ✅ 可单独定案 | 需附消费方检索结论 |
| **C** | 全仓检索 0 命中（反证缺失） | ✅ 可单独定案 | 必须写明检索词与检索范围 |
| **D** | 依赖推断 / 行为推断 | ❌ 仅作旁证 | 必须显式标注「推断，未确认」 |

仅 A/B/C 级证据可以定案或改判。D 级只能作为旁证，且不得单独支撑重分类。

### 1.4 归类争议裁决

同一条目同时满足多类时的**归类优先级**：

```text
类别 1（完全未实现） > 类别 3（契约不符） > 类别 2（缺后端/数据）
```

理由：按「修复成本最高、最根本」的一类归类，避免把「能力不存在」误记为「界面没接好」而只改文案了事。

**争议升级路径**：Agent 内无法定案 → 提交「归类争议回执」（含双方证据与建议类别）→ 主 Agent 裁决 → 更新 §2 看板 → 不得静默按自己的理解开工。

### 1.5 Cluster 分组（同根因条目强制同 Agent 处置）

以下条目共享同一根因，**必须作为一个整体处置**，不得拆给不同 Agent，也不得只修其中一条：

| Cluster | 条目 | 根因 | 主责 Agent |
|---|---|---|---|
| **C-A 屏幕圈选** | 1-3、2-3、3-16 | 截图来源不存在，导致能力、授权开关、设置文案三处同时失实 | A7（能力）+ A1（UI 接线） |
| **C-B 配对显示字段** | 2-6、2-7、2-8、3-10、3-11、3-12 | 本机合成值/本地自增冒充网关权威字段 | A1（依赖 A0 的 D2 / D5） |
| **C-C 会话终止语义** | 3-1、3-2、3-3、3-4、3-5、3-9 | 「退出登录 / 解除配对 / 配对建立」三类会话语义三端不齐 | A3 + A4 + A2（3-9）+ A1（UI） |
| **C-D 设备授权与设备请求** | 3-6、3-7、3-8、2-2 | 授权 revision 与设备请求执行通路未闭合 | A2 + A3 + A4 |
| **C-E 插件面** | 1-1、1-2、1-5、2-12、3-19 | 插件包能力在宿主侧空转 + 参考 manifest 不符 | A5 |
| **C-F 通知采集面** | 1-4、2-4 | 采集模块未装配、服务未声明、授权双轨 | A6（依赖 A0 的 D6） |
| **C-G 审计可信** | 2-9 | 无防篡改支撑，且「用户确认」从未写入 | A2（存储）+ A1（文案） |

### 1.6 重分类规则

执行期若**红测试无法复现审计结论**（见 §5.1），说明审计的静态推断可能有误。此时：

1. **禁止静默跳过或按自己理解改判**；
2. 提交「重分类回执」，必填：条目 ID、原类别、新类别、A/B/C 级证据、红测试为何无法复现、影响面（是否牵动 Cluster 内其它条目）；
3. 主 Agent 批准后更新 §2 看板，并在本文档追加修订记录（§10）。

---

## 2. 优先级排序规则

### 2.1 评分公式（可计算、可复核）

```text
R = 2·S + 2·U + 1.5·C + 1.5·B − 0.5·D
```

| 因子 | 含义 | 取值口径（0–3） |
|---|---|---|
| **S** 安全语义误导 | 界面向用户宣称了不存在或相反的安全/能力属性 | 3 = 宣称了**相反**的安全语义；2 = 开关/按钮暗示可操作但必然无效果；1 = 仅展示值失真；0 = 无 |
| **U** 用户可见错误 | 用户能直接观察到的错误 | 3 = 点击必错或必然 no-op 且无任何反馈；2 = 展示值恒为错误值；1 = 边界/偶发；0 = 无 |
| **C** 契约违背 | 与契约/Schema/ADR/规格的偏离程度 | 3 = 契约明确要求且三端皆缺或语义相反；2 = 单端半实现或字段名不符；1 = 仅呈现层不符；0 = 无契约约束 |
| **B** 阻塞度 | 对其它条目/Agent 的阻塞 | 3 = 阻塞 ≥3 条或其它 Agent 开工；2 = 阻塞 1–2 条；1 = 仅自身；0 = 无 |
| **D** 实现成本 | 反向因子（成本越高越往后排） | 3 = 需新增模块/Manifest/跨端协议/Schema；2 = 多文件重构；1 = 单文件改动；0 = 文案或死代码清理 |

**档位阈值**：

```text
P0：R ≥ 13    用户可见错误或安全语义误导，必须最先修
P1：8 ≤ R < 13 能力缺口，影响产品完整性
P2：R < 8           一致性与清理
```

排序规则：先按档位（P0 > P1 > P2），同档内按 R 降序，R 相同则按条目 ID 升序。

### 2.2 36 项评分看板

> S/U/C/B/D 各列即 §2.1 的取值，R 为计算值。最后一列标注与审计 §5 原始建议的偏差及理由。

| ID | 条目 | S | U | C | B | D | **R** | 档位 | Cluster | 主责 | 与审计 §5 的偏差 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 3-1 | `revokeRefresh` 语义相反（退出登录被实现成解除配对） | 3 | 2 | 3 | 3 | 2 | **18.0** | P0 | C-C | A3+A4 | 一致（P0） |
| 3-2 | 解除配对独立管理端点三端皆无 | 3 | 1 | 3 | 3 | 3 | **15.5** | P0 | C-C | A0→A3+A4 | 一致（P0） |
| 3-6 | 设备授权变更契约未落地（`GRANT_STALE` / `pairing.grant.changed`） | 3 | 1 | 3 | 3 | 3 | **15.5** | P0 | C-D | A0→A2+A3+A4 | 一致（P1）→ 升 P0，阻塞 3-7/3-8 全组 |
| 2-2 | 「读取与发送短信」授权开关无生效通路 | 3 | 2 | 2 | 2 | 2 | **15.0** | P0 | C-D | A2+A1 | 一致（P0） |
| 3-16 | 设置页宣称圈选可用但实现恒 null | 3 | 2 | 2 | 1 | 0 | **14.5** | P0 | C-A | A1 | P1 → 升 P0：S=3 且 D=0（仅文案），性价比最高 |
| 2-1 | 「刷新网关凭据」必然 no-op 且 `_operationNotice` 无消费 | 2 | 3 | 1 | 2 | 1 | **14.0** | P0 | — | A1 | 一致（P0） |
| 3-7 | 设备请求执行通路半实现（传输是测试替身） | 2 | 1 | 3 | 3 | 2 | **14.0** | P0 | C-D | A2 | 一致（P1）→ 升 P0，与 3-6 同批 |
| 2-9 | 「不可篡改审计」表述无技术支撑 | 3 | 2 | 2 | 1 | 2 | **13.5** | P0 | C-G | A2+A1 | 一致（P0） |
| 3-9 | `error.code` 解析退化为状态码 | 1 | 3 | 3 | 1 | 1 | **13.5** | P0 | C-C | A2 | 一致（P0） |
| 3-15 | 设置界面无法呈现协商能力位 | 2 | 2 | 2 | 2 | 2 | **13.0** | P0 | — | A1 | P2 → 升 P0：它是 2-3/2-4/3-16 如实降级的前置 |
| 1-4 | 通知采集本地设置界面不存在、服务未声明 | 2 | 2 | 2 | 2 | 3 | **12.5** | P1 | C-F | A6 | 一致（P1，审计未单列） |
| 1-1 | 设置中的插件管理区域完全未实现 | 1 | 1 | 3 | 3 | 3 | **11.5** | P1 | C-E | A5 | 一致（P1） |
| 2-3 | 「屏幕上下文分析与圈选」授权开关不进内核 | 3 | 2 | 1 | 1 | 2 | **11.0** | P1 | C-A | A2+A1 | 一致（P0）→ 降 P1：真修复依赖 C-A 能力落地，先随 3-16 降级呈现 |
| 2-4 | 「系统通知推送」开关与查询门读两套授权 | 3 | 2 | 1 | 1 | 2 | **11.0** | P1 | C-F | A6+A2 | 一致（P0）→ 降 P1：真修复依赖 C-F 装配落地 |
| 1-3 | 屏幕圈选截图采集实现缺失 | 2 | 1 | 2 | 2 | 3 | **10.5** | P1 | C-A | A7 | 一致（P1） |
| 1-2 | 插件声明式设置项/状态卡片无渲染器 | 1 | 1 | 3 | 2 | 3 | **10.0** | P1 | C-E | A5 | 一致（P1） |
| 2-6 | 「本机授权版本 r{N}」来自本地自增 | 1 | 2 | 2 | 1 | 1 | **10.0** | P1 | C-B | A1 | 一致（P2）→ 升 P1，随 C-B 整组 |
| 2-7 | 「配对身份标识」为本机合成 ID | 1 | 2 | 2 | 1 | 1 | **10.0** | P1 | C-B | A1 | 同上 |
| 2-8 | 「配对摘要」双宿主均不下发 | 1 | 2 | 2 | 1 | 1 | **10.0** | P1 | C-B | A1 | 同上 |
| 3-12 | `pairingSummary` 无契约定义 | 1 | 1 | 2 | 2 | 1 | **9.5** | P1 | C-B | A0→A1 | 同上 |
| 3-5 | `session.revoked` 事件不经事件流 | 1 | 1 | 3 | 1 | 2 | **9.0** | P1 | C-C | A3+A4 | 一致（P1） |
| 3-8 | device `result` body 三端形状不一致 | 1 | 0 | 3 | 2 | 1 | **9.0** | P1 | C-D | A0→A2 | 一致（P1） |
| 3-10 | `grantRevision` 同名字段语义不符 | 1 | 1 | 2 | 2 | 2 | **9.0** | P1 | C-B | A0→A1 | 一致（P2）→ 升 P1，随 C-B 整组 |
| 3-11 | `pairingId` 同名字段语义不符 | 1 | 1 | 2 | 2 | 2 | **9.0** | P1 | C-B | A0→A1 | 同上 |
| 3-19 | 三个参考插件 manifest 与插件包契约 §5 不符 | 2 | 0 | 3 | 1 | 2 | **9.0** | P1 | C-E | A5 | 一致（P2）→ 升 P1：C=3 且是插件面其它工作的输入 |
| 2-5 | 开发者信任模式状态不持久化 | 2 | 1 | 1 | 1 | 1 | **8.5** | P1 | — | A2 | 审计未单列 → P1 |
| 3-13 | `message-batches-v1` / `generation-cancel-v1` 能力位半实现 | 1 | 1 | 2 | 1 | 1 | **8.0** | P1 | — | A0→A1+A3+A4 | 一致（P2）→ 升 P1，与 3-14/3-15 同批 |
| 3-3 | `POST /pairings/exchange` 三端未实现 | 1 | 0 | 3 | 1 | 3 | **6.5** | P2 | C-C | A0→A3+A4 | 一致（P1）→ 降 P2：无 UI 入口、无用户可观察影响 |
| 3-4 | `POST /sessions/device` 三端未实现 | 1 | 0 | 3 | 1 | 3 | **6.5** | P2 | C-C | A0→A3+A4 | 同上 |
| 2-11 | 熔断计数随子树销毁归零 | 1 | 2 | 0 | 0 | 1 | **5.5** | P2 | — | A1 | 一致（P2） |
| 3-14 | `newline-v1` / `conversation-mirror-v1` / `attachment-status-v1` 三端未声明 | 1 | 0 | 2 | 1 | 2 | **5.5** | P2 | — | A0→A1+A3+A4 | 一致（P2） |
| 1-5 | ADR 0042 原生插件 UI 扩展点不存在 | 1 | 0 | 2 | 1 | 3 | **5.0** | P2 | C-E | A5 | 一致（P2） |
| 2-12 | 渠道策略字段与 `:plugin-package` 依赖空转 | 1 | 0 | 1 | 1 | 1 | **4.5** | P2 | C-E | A5 | 一致（P2） |
| 2-10 | 「内核隔离原语」「硬上限」纯静态条目 | 1 | 1 | 0 | 0 | 0 | **4.0** | P2 | — | A1 | 一致（P2） |
| 3-17 | 设置应为 M3 底部弹层，实现为全屏浮层 | 0 | 1 | 1 | 1 | 2 | **4.0** | P2 | — | A1 | 一致（P2） |
| 3-18 | `AppDestination` 6 个目的地仅定义无引用 | 0 | 0 | 0 | 1 | 0 | **1.5** | P2 | C-E | A5 | 一致（P2） |

**档位汇总**（与 §2.1 阈值一致）：

| 档位 | 数量 | 条目 |
|---|---|---|
| P0 | 10 | 3-1、3-2、3-6、2-2、3-16、2-1、3-7、2-9、3-9、3-15（按 R 降序） |
| P1 | 17 | 1-4、1-1、2-3、2-4、1-3、1-2、2-6、2-7、2-8、3-12、3-5、3-8、3-10、3-11、3-19、2-5、3-13（按 R 降序） |
| P2 | 9 | 3-3、3-4、2-11、3-14、1-5、2-12、2-10、3-17、3-18（按 R 降序） |

**与审计 §5 的偏差说明（3 类、共 17 项调整）**：
- **升档 11 项**：3-16（S=3 且 D=0，一行文案即消除一个虚假能力宣称）、3-15（是 2-3/2-4/3-16 如实降级的前置）、3-6、3-7（阻塞整个 C-D 分组）、3-19（C=3 且是 C-E 的输入）、2-6、2-7、2-8、3-10、3-11、3-12（C-B 整组同根因，拆开修必然返工）。
- **降档 4 项**：2-3、2-4（真修复依赖 C-A / C-F 能力落地，在能力到位前只能先做降级呈现，故与 3-16 同批而不单独占 P0）、3-3、3-4（无 UI 入口、用户不可观察，纯协议补齐）。
- **新增入册 2 项**：1-4（审计列在类别 1 但未进其优先级清单）、2-5（审计列在类别 2 但未进其优先级清单）。

### 2.3 四项「未确认」的处置（审计 §6）

| 编号 | 未确认项 | 处置 | 归属 | 阻塞对象 |
|---|---|---|---|---|
| U1 | 「解除配对」端点、`pairingSummary`、device `result` 形状、`DELETE /sessions/current` 响应/错误码——契约只有自然语言 | **必须先裁决再实现**，产出 D1–D4（§3.5） | A0 | 3-1、3-2、3-8、3-9、2-8、3-12 |
| U2 | 登出端点签名强度（契约 §6.1 未明文豁免 `DELETE /sessions/current`） | 随 D4 一并裁决，裁决前不得自行放宽或收紧 | A0 | 3-1、3-9 |
| U3 | 审计「用户可以清除」约束对象不明（Hermes 有 `audit.py:135 purge`，OpenClaw 无） | 裁决 OpenClaw 是否补 `purge`；若契约无要求则两端都不做并在 UI 如实呈现 | A0 → A4 | 2-9 |
| U4 | 熔断计数重开面板实际显示值；`plugins/` 与 `plugins/dist/` 构建一致性 | **已复核澄清**（§10）：`plugins/dist/` 存在但被 `.gitignore:83` 忽略且为旧包名 `org.agentlife.*` 的陈旧产物，**不作为当前包名依据**；2-11 的 UI 实际值需真机验证 | A5（dist）、A1（2-11 真机） | 2-11、3-19 |

裁决原则：**契约缺口一律不得由执行 Agent 自行发明字段名或端点路径**。凡属 U1–U3 范围的，一律走 A0 裁决 → 写进 `gateway-contract/schemas/` 与向量 → 再开工。

---

## 3. Agent 职责边界与协作接口

### 3.1 划分原则：按文件所有权，不按问题类别

36 项中有 **14 项同时触及** `SettingsScreen.kt` / `SettingsViewModel.kt` / `PlatformSettingsEnvironment`（1-1、1-2、1-4、2-1、2-6、2-7、2-8、2-9、2-10、2-11、3-15、3-16、3-17、3-18）。若按问题类别分工，多个 Agent 必然互相踩文件。因此：

> **唯一划分依据 = 文件所有权。** 每个文件有且仅有一个 Owner Agent。跨 Agent 只能经由 §3.4 的约定接口交互。

三条推论：
1. **新增文件归属创建者**（含放在 `app/` 目录下的新文件）；
2. **需要接入设置骨架的能力**，由 Owner 交付自包含的可组合函数，A1 在 Wave 2「只加一行」路由/入口；
3. **双宿主按宿主切分**（A3 Hermes / A4 OpenClaw），文件零重叠，且天然对齐「端点须两端对等，否则算半实现」的架构要求。

### 3.2 八个 Agent 概览

| Agent | 名称 | 条目数 | P0 | 一句话职责 |
|---|---|---|---|---|
| **A0** | 契约裁决与 Schema 冻结 | 8（裁决） | 3-2、3-6 | 把 U1–U3 的契约缺口变成可机读 Schema + 向量，并一次性完成核心摘要三方同步 |
| **A1** | 设置骨架与运行时接线 | 13 | 2-1、3-16、3-15 | 独占 `app/**` 设置 UI 与运行时；负责所有降级呈现与最终接线 |
| **A2** | Android 内核与网关客户端 | 7 | 2-2、3-9、3-7 | 独占 `platform-kernel/**` + `gateway-client/**`：授权、审计存储、设备请求、错误解析 |
| **A3** | Hermes 宿主 | 6 | 3-1、3-2 | 独占 `integrations/hermes/**` |
| **A4** | OpenClaw 宿主 | 6 | 3-1、3-2 | 独占 `integrations/openclaw/**` |
| **A5** | 插件面 | 5 | — | 独占 `plugin-package` / `plugin-ui` / `plugins/**` + 新增插件管理界面文件 |
| **A6** | 通知采集面 | 2 | — | 独占 `notification-collector` / `policy-engine` + 新增通知设置界面文件（开工需 D6） |
| **A7** | 屏幕圈选 | 1（+2 联动） | — | 独占截图采集实现与 overlay 截图源接口（C-A 能力侧） |

> A0 不直接修业务缺陷，它的交付物是**裁决 + Schema + 向量 + 摘要同步**，是 A1–A7 的输入。

### 3.3 文件所有权矩阵

**独占**（唯一 Owner，其它 Agent 不得改动）：

| Agent | 独占路径 |
|---|---|
| A0 | `docs/contracts/**`、`gateway-contract/schemas/**`、`gateway-contract/vectors/**`、`gateway-contract/src/core-schema-hash.ts` |
| A1 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt`、`SettingsViewModel.kt`、`PlatformSettingsScreen.kt`、`PlatformSettingsBottomSheet.kt`、`MainActivity.kt`、`OpenAndroidIntelligenceApplication.kt`、`GatewayRuntime.kt`、`PairingGrantPersistence.kt`、`apps/android/app/src/test/**` |
| A2 | `apps/android/platform-kernel/**`、`apps/android/gateway-client/**`（含各自 `src/test` 与 `src/androidTest`） |
| A3 | `integrations/hermes/**` |
| A4 | `integrations/openclaw/**` |
| A5 | `apps/android/plugin-package/**`、`apps/android/plugin-ui/**`、`plugins/**` |
| A6 | `apps/android/notification-collector/**`、`apps/android/policy-engine/**` |
| A7 | `apps/android/capability-ports/**`（新增采集文件）、`apps/android/conversation-ui/**`（overlay 截图源接口） |

**共享**（改动须主 Agent 合并，禁止直接提交）：

| 路径 | 原因 | 现行约定 |
|---|---|---|
| `apps/android/app/src/main/AndroidManifest.xml` | A6（通知监听服务）、A7（MediaProjection 前台服务）都需声明组件，而文件归 A1 | 需求由 A6/A7 在回执里声明，A1 在 Wave 2 一次性写入 |
| `apps/android/app/build.gradle.kts` | A5（`:plugin-package` 去留）、A6（是否新增依赖，受 `ArchitectureBoundaryTest.kt:22-29` 约束） | 依赖变更须先有 A0 的 D6 裁决，由 A1 执行 |
| `apps/android/settings.gradle.kts` | 新增模块时 | 创建者声明，主 Agent 合并 |
| `gateway-contract/test/cross-host-conformance.test.ts` | 一致性套件入口，A0 加向量清单 | A0 独改，A3/A4 只保证 runner 通过 |
| `apps/android/conversation-ui/src/main/kotlin/.../components/SettingsComponents.kt` | 设置组件库，A1/A5/A6 都可能复用 | 只读复用；需改签名则提交回执，由主 Agent 指派 A1 改 |

**跨端同步点**（改一处必须同步三处，见 §5.4）：

| 落点 | 路径:行 |
|---|---|
| Android 摘要常量 | `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHash.kt:30` |
| OpenClaw 清单摘要 | `integrations/openclaw/plugin-manifest.json:6` |
| OpenClaw 计算调用 | `integrations/openclaw/adapter.ts:52` |
| Hermes 比对 | `integrations/hermes/open_android_intelligence_gateway/core.py:3135`（domain 定义 `:46`） |
| 一致性断言 | `integrations/openclaw/test/gateway-capabilities.test.ts:387` |

### 3.4 协作接口契约

**三条铁律**：

1. **不跨界改文件**：任何 Agent 需要别人文件里的东西，只能提回执，不能自己改；
2. **接口先行**：跨 Agent 交互只允许通过下列四类接口，不允许通过共享可变状态、全局变量或「先抄一份再改」；
3. **接线只加一行**：能力面（A5/A6/A7）交付的可组合函数，A1 只负责在 NavHost 加一条路由 + 概览加一个入口条目，不得改写其内部。

**四类约定接口**：

| 接口类型 | 约定形式 | 实例 |
|---|---|---|
| ① 可组合函数签名 | `@Composable fun XxxScreen(environment: PlatformSettingsEnvironment, onBack: () -> Unit)` 自包含，不反向依赖 `SettingsScreen` 内部符号 | A5 → `PluginManagementScreen`；A6 → `NotificationCollectionScreen` |
| ② environment 新字段 | 在 `PlatformSettingsScreen.kt:18-27` 的 `PlatformSettingsEnvironment` 追加字段 | A2 追加 `grantRevisionSource`；A6 追加 `notificationPolicyAuthority`；新增字段的**构造点**在 `OpenAndroidIntelligenceApplication.kt:59-67`（A1 独占） |
| ③ 能力提供者接口 | 定义在提供方模块，消费方只依赖接口 | A7 定义 `ScreenCaptureSource`（`capability-ports`），`conversation-ui` 的 `ScreenSelectionOverlay` 只依赖接口；null ⇒ 渲染不可用态（现有行为正确，不得改） |
| ④ Schema + 向量 | 契约变更只以 `gateway-contract/schemas/*` + `gateway-contract/vectors/*` 为准 | A0 产出，A2/A3/A4 消费 |

**接口变更回执**：任一 Agent 需要改动上述接口（尤其是 ② 的 `PlatformSettingsEnvironment`），必须在回执中列出「新增字段 + 类型 + 默认值 + 影响的下游构造点」，由主 Agent 排入 Wave 2 统一接线。

### 3.5 A0 的八项裁决（D1–D8）

A0 在 Wave 0 内产出下列裁决。每项裁决的产出物固定为：**契约文档补丁 + Schema def（如需）+ 向量用例 + 影响条目清单**。

| 裁决 | 主题 | 需定死的内容 | 影响条目 |
|---|---|---|---|
| **D1** | 解除配对的独立管理端点 | 端点路径、请求/响应 Schema、错误码；与 `DELETE /sessions/current?revokeRefresh=true` 的职责边界；撤销范围是否含「授权、队列、未确认附件」（契约 `:798`） | 3-1、3-2 |
| **D2** | `pairingSummary` | 保留并定义字段名与响应 Schema（进 `session.schema.json`），**或**裁定删除并让 UI 改文案。二选一，不得悬空 | 2-8、3-12 |
| **D3** | device `result` body 形状 | 统一为 `{ outcome, data? }` 或另定；`outcome` 闭集 | 3-8 |
| **D4** | `DELETE /sessions/current` | 响应 Schema、错误码；是否豁免契约 §6.1 的九 header 签名（U2） | 3-1、3-9 |
| **D5** | `grantRevision` / `pairingId` 权威口径 | 网关权威 vs 本地计数；UI 在无网关值时的降级呈现规则 | 2-6、2-7、3-10、3-11 |
| **D6** | app 与采集模块的依赖边界 | 是否放宽 `ArchitectureBoundaryTest.kt:22-29`（禁 `:notification-collector` 等 6 模块）；若放宽须补 ADR（现最大编号 0049 ⇒ 新 ADR 取 **0050**），否则改走接口模块注入 | 1-4、2-4 |
| **D7** | 能力位闭集补齐口径 | 3-13/3-14 是「双宿主补实现并声明」还是「客户端收敛声明」；未实现一律不得声明（契约 `:142`） | 3-13、3-14、3-15 |
| **D8** | 事件子 Schema | `session.revoked`、`pairing.grant.changed` 的载荷子 Schema（`event.schema.json:35` 要求缺子 Schema 必须失败关闭） | 3-5、3-6 |

**裁决执行约束**：
- 裁决结论优先写入契约文档与 Schema；Schema 无覆盖的部分（如管理端点路径）至少写进 `docs/contracts/` 并以向量锁死；
- **所有 Schema 改动必须在 Wave 0 内一次性完成**，Wave 0 结束后**冻结**（§7.1）；
- 若某项裁决在本仓库层面无法定案（如需要产品决策），A0 必须输出「暂缓 + 影响条目转入 P2 + 界面如实呈现不可用」，不得留空。

---

## 4. 依赖关系与并行执行策略

### 4.1 波次总览

```text
Wave 0   A0 契约裁决 + Schema 冻结（串行，唯一前置）
            │
            ▼
Wave 1   七个 Agent 并行：A1 / A2 / A3 / A4 / A5 / A6 / A7
          （各 Agent 内部按 §2 档位分批：P0 → P1 → P2）
            │
            ▼
Wave 2   主 Agent 按固定顺序集成接线（A1 执行 environment / 路由 / Manifest / Gradle）
            │
            ▼
Wave 3   验证与发布（最小测试 → 提交 → 子 Agent 复审 → CI 闭环 → 真机回归）
```

### 4.2 Wave 0：契约裁决与 Schema 冻结（串行）

- **参与者**：A0 单 Agent（可由主 Agent 兼任，但必须独立产出回执）
- **入口条件**：审计报告已定稿
- **出口条件（门禁）**：
  1. D1–D8 全部有书面结论（含「暂缓」）；
  2. 所有 `gateway-contract/schemas/*.schema.json` 改动已完成；
  3. 核心摘要已三方同步，`SchemaContractHash.kt:30`、`plugin-manifest.json:6`、`adapter.ts:52` 三处取值一致，且 `gateway-capabilities.test.ts:387` 通过；
  4. `npm run gateway:v2:conformance` 双宿主 resultHash 一致；
  5. 提交说明中写明摘要旧值 → 新值。
- **风险**：这是唯一会改变核心摘要的窗口，必须一次性做完（§7.1）。

### 4.3 Wave 1：七 Agent 并行

| Agent | 可开工条件 | 内部批次 | 完成标志 |
|---|---|---|---|
| A1 | Wave 0 出口 + D2/D5/D7 | P0：2-1、3-16、3-15 → P1：2-6、2-7、2-8、2-10 → P2：2-11、3-17、3-18 | 所有 P0 项有红→绿测试；接线需求回执已提交 |
| A2 | Wave 0 出口 + D3 | P0：2-2、2-9、3-9、3-7 → P1：2-3、2-5、3-6(后端)、3-8 → P2：— | 同上 |
| A3 | Wave 0 出口 + D1/D3/D4/D8 | P0：3-1、3-2 → P1：3-5、3-6(后端) → P2：3-3、3-4 | 同上 |
| A4 | Wave 0 出口 + D1/D3/D4/D8 | 同 A3（两端必须对等） | 同上，且与 A3 的 conformance 向量集合一致 |
| A5 | Wave 0 出口 + D7 | P1：1-1、1-2、3-19 → P2：1-5、2-12、3-18 | 同上 |
| A6 | Wave 0 出口 + **D6 必须先出** | P1：1-4、2-4 | 同上 |
| A7 | Wave 0 出口 | P1：1-3（能力） | 同上；向 A1 提交 `ScreenCaptureSource` 接口回执 |

**并行度说明**：A3 与 A4 文件零重叠可完全并行，但受「端点须两端对等」约束，二者必须在**同一批次内**完成同一组端点，不得一端先合一端后补（否则中间态即为半实现）。A5/A6/A7 与 A1 之间仅在 Wave 2 接线点相交，Wave 1 内无冲突。

### 4.4 Wave 2：集成接线（主 Agent 编排，A1 执行）

固定顺序，不得颠倒：

```text
① 合并 A0 → A3 → A4 → A5 → A6 → A7 → A2 → A1(接线)
```

理由：宿主端与能力面先落地，骨架最后接线，避免 A1 先引用尚不存在的入口。

接线动作清单（全部由 A1 执行，每项都是「只加一行」级别）：

| 序号 | 动作 | 目标文件 | 来源 Agent |
|---|---|---|---|
| W2-1 | `PlatformSettingsEnvironment` 追加字段 | `PlatformSettingsScreen.kt:18-27` | A2 / A6 / A7 |
| W2-2 | environment 构造点补齐实参 | `OpenAndroidIntelligenceApplication.kt:59-67` | A2 / A6 / A7 |
| W2-3 | `SettingsRoutes` 追加路由常量 | `SettingsScreen.kt:88-95` | A5 / A6 |
| W2-4 | `NavHost` 追加 `composable(...)` | `SettingsScreen.kt:138-218` | A5 / A6 |
| W2-5 | 概览追加入口条目 | `SettingsScreen.kt:374-631` 对应模块 | A5 / A6 |
| W2-6 | AndroidManifest 追加服务/权限声明 | `app/src/main/AndroidManifest.xml` | A6 / A7 |
| W2-7 | Gradle 依赖增删 | `app/build.gradle.kts:45-56` | A5 / A6（须有 D6） |
| W2-8 | 能力位透传到 `ConnectionPhase.Connected` 与 `SettingsUiState` | `GatewayRuntime.kt:60-68`、`SettingsViewModel.kt:21-58` | A2 + A1 |

### 4.5 Wave 3：验证与发布

```text
① 本机最小测试（§5.2）→ ② 中文 commit → ③ push → ④ 独立子 Agent 复审
   → ⑤ 修阻塞项并回到 ① → ⑥ 跟踪 CI 三门禁 → ⑦ 真机回归（§5.5）
```

### 4.6 冲突裁决规则

| 冲突类型 | 裁决规则 |
|---|---|
| 同文件不同函数 | 按**函数级所有权**划分；无法划分的由主 Agent 手工合并 |
| 接口签名分歧 | 以**契约/Schema**为准；契约无覆盖时以**被依赖方**（提供方）的签名为准 |
| 两端实现对等性分歧 | 以 `gateway-contract/vectors/*` 的向量为准，谁偏离向量谁改 |
| 与「已验证正常」12 项冲突 | **基线优先**：先保证 §5.6 的 12 项不回归，缺陷项让步（可降级为「如实呈现不可用」） |

---

## 5. 集成验证流程

### 5.1 TDD 红绿循环与 seam 声明

**审计为静态分析，36 条结论未经真机/联机验证。** 因此每项修复的开工动作固定为：

```text
① 声明 seam   → ② 写一条必然失败的红测试 → ③ 运行确认它失败
              → ④ 只写够让它变绿的实现 → ⑤ 运行确认变绿 → ⑥ 回执记录红/绿证据
```

**seam = 被测的公共边界**，测试只落在 seam 上，不落在内部实现上。各 Agent 的 seam 必须在开工回执中写明：

| Agent | 允许测试的 seam（公共接口） | 禁止 |
|---|---|---|
| A1 | `SettingsViewModel` 的公开方法与 `uiState` 派生属性；`PlatformSettingsBottomSheet`/`SettingsScreen` 的可组合函数签名 | 测私有组合函数、测 `NavHost` 内部结构 |
| A2 | `PairingGrantStateHolder` / `AndroidAuditStore` / `PluginKernel` 的公开方法；`DeviceRequestClient` 经 `DeviceRequestTransport` 接口；`GatewayAuthClient.authError` 的返回 | 直接测 `SharedPreferences` 文件内容、测私有字段 |
| A3 | HTTP 端点（请求/响应/错误码）、`DeviceRequestStore` 公开方法、审计产出 | 测私有 SQL |
| A4 | 同 A3（端点对等） | 同上 |
| A5 | `PluginInstaller` / `DeclarativeUiSchema.parse` / 新增 `PluginManagementScreen` 的公开入参 | 测渲染像素 |
| A6 | `NotificationRuntime` / `PersistentNotificationPolicyAuthority` 公开方法、新增界面的入参 | 依赖真实系统通知服务 |
| A7 | `ScreenCaptureSource` 接口与其 null 分支下 overlay 的不可用渲染 | 依赖真实 MediaProjection 投屏 |

**红测试的判定力要求**：测试必须**因为该缺陷而失败**，而不是因为编译不过、缺 fixture 或环境缺失。若写不出有判定力的红测试，说明该项不适合自动化 → 走重分类回执（§1.6）或转入真机人工验证清单（§5.5）。

**测试写法约定（本机已验证的既有约定，必须遵守）**：
- `conversation-ui` 用 JUnit 的 `assertX(message, actual)` 形式；
- 涉及控制器的测试结束必须 `controller.cancel()`；
- `advanceUntilIdle()` 会跑掉 watchdog delay，断言时序相关行为时不得滥用；
- JVM 单测无 `org.json`，走项目内的 `Json` / `JsonFields`；
- 时间戳一律用 `RequestSigner.formatTimestamp`；
- Kotlin 的 `onDecide = if (c) f else {}` 会被推断成 `Any`，必须写 `else { _: X -> }`。

### 5.2 本机最小测试（提交前必须跑）

> 本机**不跑全量 Gradle 编译与测试**（交 CI），只跑覆盖本次改动的最小测试。

```bash
# Android：单模块 + 单测试类（fish shell 用 env 前缀传参）
cd apps/android && env LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 ANDROID_HOME=$PWD/../../.toolchains/android-sdk GRADLE_USER_HOME=$PWD/.toolchains/gradle-home ANDROID_USER_HOME=$PWD/.toolchains/android-user-home ./gradlew --offline :conversation-ui:testDebugUnitTest --tests "<FQCN>" :app:compileFullDebugKotlin

# Android：其它模块把 :conversation-ui:testDebugUnitTest 换成对应模块，例如
#   :app:testDebugUnitTest / :platform-kernel:testDebugUnitTest / :gateway-client:testDebugUnitTest

# Node 契约侧
./tools/run-node24 npx vitest run gateway-contract/test/
./tools/run-node24 npx tsx integrations/openclaw/test/...   # 视改动范围

# 双宿主一致性（Wave 0 必跑）
npm run gateway:v2:conformance

# Hermes
cd integrations/hermes && python3 -m pytest -q
```

**提交前判定**：上述命令中**覆盖本次改动的那一条必须全绿**。测试存在失败、未执行或结果未知时，**禁止 `git commit` 与 `git push`**。

### 5.3 CI 三门禁

| 门禁 | 来源 | 命令 | 作用 |
|---|---|---|---|
| G1 契约一致性 | `.github/workflows/ci.yml` `gateway-and-protocol` | `npm run typecheck` → `npm run gateway:v2:conformance` → `npx vitest run gateway-contract/test/` | 双宿主 resultHash 一致 + Schema 自检 |
| G2 Android 全量 | 同上 `android` | `cd apps/android && ./gradlew check --stacktrace` | 四模块单测 + 架构/UI 守卫 |
| G3 APK 构建 | `.github/workflows/android-apk.yml` | `:app:assembleFullDebug` | 保证可安装 |

### 5.4 契约摘要三方同步门禁（最高危门禁）

核心摘要现值：`sha256:665df51661c11f9f770c11abb10b9be5fc343ffd41507e12625f3a011072cacb`。

**任何 `gateway-contract/schemas/*.schema.json` 的改动都会改变该摘要**，未同步升级的一端会被 `PROTOCOL_INCOMPATIBLE`(406) 拒绝、在重新构建前**完全无法登录**。

必检清单（每次 Wave 0 结束与每次涉及 Schema 的提交）：

- [ ] `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/schema/SchemaContractHash.kt:30` 的 `CORE` 已更新
- [ ] `integrations/openclaw/plugin-manifest.json:6` 的 `capabilitySchemaHash` 已更新
- [ ] `integrations/openclaw/adapter.ts:52` 计算源未被绕过
- [ ] `integrations/openclaw/test/gateway-capabilities.test.ts:387` 的断言通过
- [ ] Hermes `core.py:3135` 的比对通过（domain 定义 `core.py:46`）
- [ ] 提交说明中**显式写明**摘要旧值 → 新值
- [ ] 交付说明中说明「App 与插件必须升到同一版本」（`tools/download-latest-apk` 取与插件同提交的 APK）

### 5.5 真机回归清单（Wave 3）

自动化无法覆盖、必须真机人工验证的项（设备 `R52X909R9QT`，无 emulator）：

| # | 验证项 | 关联条目 | 判定 |
|---|---|---|---|
| R1 | 已连接状态下点「刷新网关凭据」：有可见反馈，不再是静默 no-op | 2-1 | 出现提示且/network 请求发生 |
| R2 | 登出/恢复失败时界面有 notice（不再静默） | 2-1 | 出现可读文案 |
| R3 | 触发熔断 → 关闭设置面板 → 重开：隔离计数不再显示「共隔离 0 个插件」 | 2-11 | 计数与内核 `isEmergencyStopped` 一致 |
| R4 | 三个设备能力开关在能力未装配时渲染为不可用或标注不可用，不得给出可操作暗示 | 2-2、2-3、2-4、3-16 | 目视 + 点击无副作用 |
| R5 | 通知监听服务在 Manifest 声明后，系统「通知使用权」设置页可跳转并可授予 | 1-4 | 系统设置页出现本 App |
| R6 | 屏幕圈选在有/无截图来源两种状态下的表现（无源必须明确不可用） | 1-3、3-16 | 与 `specs/2026-09-12-...md:55/75` 一致 |
| R7 | 设置页从概览进入各子页的转场与返回手势（M3 Sheet 改造后） | 3-17 | 符合 `specs/2026-09-12-...md:67` |
| R8 | 协商能力位在设置页可见，且与网关实际声明一致 | 3-15 | 与 `GET /negotiate` 响应逐项比对 |
| R9 | 解除配对与退出登录分别触发后，账号/设备状态符合 D1 裁决 | 3-1、3-2 | 服务端 `device_keys` 与 refresh 状态符合契约 `:796`/`:798` |

### 5.6 12 项「已验证正常」基线回归锁

审计 §4 的 12 项必须在全程保持通过，任何一项回归即视为阻塞（§4.6 基线优先原则）：

| # | 基线项 | 回归手段 | 覆盖状态 |
|---|---|---|---|
| B1 | 外观与动效三项偏好（主题/动态取色/减弱动态） | `SettingsViewModelTest` + 真机 R7 | 有单测 |
| B2 | 一键系统级安全熔断（内核行为真实） | `KernelIsolationTest` | 有单测 |
| B3 | 退出登录/解除配对主链路副作用真实 | G1 向量 + 真机 R9 | 有向量 |
| B4 | 审计存储与展示（落盘、30 天、不含正文） | `AuditSinkTest` | 有单测 |
| B5 | 传输安全拓扑 / 账号主体 / 节点地址 / 安全模式 | `SettingsViewModelTest` + 真机 | 有单测 |
| B6 | 屏幕圈选 overlay 的降级行为 | A7 红绿测试 | 有单测（改造后须仍绿） |
| B7 | 审批卡片能力位双端自洽 | G1 一致性 | 有向量 |
| B8 | 双宿主设备请求 claim/result 状态机 | G1 向量 | 有向量 |
| B9 | 收据六字段与 `tlsSpkiSha256` 命名 | `DeviceRequestClientTest` + G1 | 有单测+向量 |
| B10 | 基础能力位三端一致 | G1 | 有向量 |
| B11 | 三端本地审计保留期与不含正文 | 三端各自测试 | 有单测 |
| B12 | 设置组件库与设计令牌守卫 | `SettingsComponentsTest` + `MaterialTokenGuardTest` + `ThemeContrastTest` | 有单测（**禁用分支未覆盖**，见 §7.4） |

---

## 6. 进度跟踪机制

### 6.1 状态看板

**主看板 = 本文档 §2.2 的 36 行表格 + §9 的验收清单**，原地更新（不另建文件），保证「条目 ID ↔ 状态」双向可查。每条状态取自下列枚举：

```text
待裁决 → 已分派 → 红（测试已写且失败）→ 绿（测试通过）→ 已集成 → 已验证（真机/联机）→ 关闭
                                     ↘ 挂起（含原因）↗
                                     ↘ 重分类（含回执）→ 重新分派
```

流转规则：
- 进入「绿」必须附带测试名与命令输出摘要；
- 「已集成」只在 Wave 2 由主 Agent 标记；
- 「已验证」只在真机/联机跑过 R1–R9 后标记；
- **禁止从「已分派」直接跳到「关闭」**。

### 6.2 进度日志（跨会话可恢复）

- 位置：`docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-progress.txt`（append-only，**永不截断**）
- 格式（grep 友好，单行一条）：

```text
[2026-09-22T10:00:00+08:00] [A1] Starting [2-1] 刷新网关凭据 no-op (base=<commit>)
[2026-09-22T10:05:00+08:00] [A1] CHECKPOINT [2-1] step=2/4 "红测试 SettingsViewModelTest#refreshSession... 已失败"
[2026-09-22T10:12:00+08:00] [A1] ERROR [2-1] [TEST_FAIL] 绿测试失败，原因：GatewayRuntime 状态机断言
[2026-09-22T10:20:00+08:00] [A1] Completed [2-1] (commit abc1234)
[2026-09-22T10:21:00+08:00] [A1] STATS total=13 completed=1 failed=0 pending=12 blocked=0
```

类型：`Starting` / `CHECKPOINT` / `Completed` / `ERROR` / `ROLLBACK` / `RECOVERY` / `BLOCKED` / `STATS`。
错误分类：`ENV_SETUP` / `CONFIG` / `TASK_EXEC` / `TEST_FAIL` / `TIMEOUT` / `DEPENDENCY` / `SESSION_TIMEOUT`。

常用检索：

```bash
grep ERROR   docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-progress.txt
grep BLOCKED docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-progress.txt
grep "\[A3\]" docs/superpowers/plans/2026-09-22-app-settings-gap-remediation-progress.txt
```

**中断恢复**：新会话开始时先读进度日志尾部 + `git log --oneline -20` + `git diff --stat`，按「未提交改动 / 近期任务提交 / checkpoint」三项决定：无进展 → 重跑；有进展 → 跑 `validation` 命令判定；失败 → 回滚到该 Agent 自己分支的起始提交后重试。**回滚只针对自己的分支，禁止 `git reset --hard` 到包含他人提交的节点，禁止强推。**

### 6.3 Agent 回执（handoff）

每 Agent 在每个批次结束时产出 `docs/superpowers/handoffs/2026-09-22-app-settings-agent-a{n}.md`，必填字段：

| 字段 | 内容 |
|---|---|
| 批次 | Wave 号 + 本次 P0/P1/P2 批次 |
| 条目清单 | 本次处理的条目 ID 与最终状态 |
| seam 声明 | 每个条目的被测公共接口（§5.1） |
| 红测试证据 | 测试全限定名 + 失败原因摘录 |
| 绿测试证据 | 测试全限定名 + 通过命令 |
| 跨 Agent 接口变更 | 新增/修改的接口（可组合函数签名 / environment 字段 / 提供者接口 / Schema），含类型与默认值 |
| 接线需求 | 需要 A1 在 Wave 2 执行的动作（对照 §4.4 的 W2-1…W2-8） |
| 阻塞与升级 | 阻塞项、已尝试的处置、升级到的层级 |
| 剩余项 | 未完成条目与原因 |

### 6.4 三级阻塞升级

| 层级 | 触发条件 | 处置人 | 时限 |
|---|---|---|---|
| **L1 Agent 内** | 同一条目连续 2 次红→绿失败 | Agent 自查：换 seam、缩小切片 | 立即 |
| **L2 主 Agent** | L1 未解；或需要跨界改文件；或需要改共享文件（Manifest / Gradle / environment） | 主 Agent 裁决：指派 Owner 执行或调整切片；更新看板 | 当批次结束前 |
| **L3 契约/架构裁决** | 涉及 U1–U3 契约缺口；或需放宽 `ArchitectureBoundaryTest`；或需新增 ADR；或需改 Schema | 主 Agent 提交裁决回执 → A0 出裁决 → 必要时新增 ADR（现最大编号 0049，新 ADR 取 **0050**）→ 全员同步 | Wave 边界 |

**挂起不等于放弃**：挂起项必须在回执与看板中写明原因，且**不得阻塞同 Cluster 之外的其它条目**。

### 6.5 每波同步点

| 波次 | 同步检查（全部满足才进入下一波） |
|---|---|
| Wave 0 出口 | D1–D8 有结论；Schema 改动完成；摘要三方一致；`npm run gateway:v2:conformance` 双宿主 resultHash 一致 |
| Wave 1 出口 | 各 Agent P0 项全部「绿」；接线需求回执齐备；无未升级的 L2 阻塞 |
| Wave 2 出口 | W2-1…W2-8 全部执行；`./gradlew check`（本机最小集）通过；`ArchitectureBoundaryTest` / `MaterialTokenGuardTest` / `ThemeContrastTest` 全绿 |
| Wave 3 出口 | G1/G2/G3 全绿；R1–R9 真机验证完成；B1–B12 无回归；独立子 Agent 复审无阻塞项 |

---

## 7. 风险控制措施

| # | 风险 | 触发场景 | 对策 | 责任人 |
|---|---|---|---|---|
| **7.1** | **契约摘要漂移**（最高危）：改 Schema 后未同步 → 对端 `PROTOCOL_INCOMPATIBLE`(406)，整机无法登录 | 任何 `gateway-contract/schemas/*` 改动 | ① 所有 Schema 改动集中在 **Wave 0** 一次性完成，Wave 0 结束即**冻结**；② Wave 1 起禁止再改 Schema，确需再改必须重开窗口；③ 三方同步点全检（§5.4）；④ 提交说明写明旧值→新值；⑤ 交付说明要求 App 与插件同版本升级 | A0 + 主 Agent |
| **7.2** | **静态审计结论本身有误**：36 条中可能有推断失误 | 写不出有判定力的红测试 | 强制红测试先行；写不出即走重分类回执（§1.6），禁止静默跳过 | 各 Agent |
| **7.3** | **热点文件冲突**：`SettingsScreen.kt` 等被 14 项同时触及 | Wave 1 并行期 | 文件所有权矩阵（§3.3）+ 接线只加一行（§3.4）；能力面交付自包含可组合函数 | 主 Agent |
| **7.4** | **架构边界守卫**：`ArchitectureBoundaryTest.kt:22-29` 明文禁止 app 依赖 `:notification-collector`、`:sms-collector`、`:call-log-collector`、`:transport`、`:tailnet-core`、`:capability-sync-runtime` | A6 需在设置页集成通知采集 | 必须先有 A0 的 **D6** 裁决：**放宽断言 + 补 ADR 0050**，或**改走接口模块注入**。执行 Agent 不得自行放宽断言 | A0 → A6 |
| **7.5** | **UI 守卫**：`MaterialTokenGuardTest` 禁硬编码色与圆角闭集、`ThemeContrastTest` 禁 `Color(0x` | 任何新增设置界面/条目 | 只用 `MaterialTheme.colorScheme.*` 与 `Dimensions`/`AppRadius`/`MotionSpecs`；新增界面必须让两个守卫测试仍绿 | A1/A5/A6 |
| **7.6** | **组件点击门控语义**：`SettingsComponents.kt:164` 的 `onClick = if (enabled) … else null` 只在源码层保证，测试只覆盖 `enabled=true` | 新增禁用态条目 | 新增条目若为禁用态，必须补一条 `enabled=false` 的点击测试；`SettingsListItem` 的 `onClick = null` 表示纯展示 | A1 |
| **7.7** | **权限与隐私**：MediaProjection、通知监听属高敏能力 | A6 / A7 实现 | ① 必须走系统设置页跳转，由用户显式授予；② 未授予时界面如实呈现不可用；③ 禁止后台静默采集；④ 开关文案不得暗示已生效 | A6/A7 + 主 Agent |
| **7.8** | **虚假能力宣称**：修了一半的能力让界面继续宣称可用 | 任一条目降级处理 | 降级一律走「如实呈现不可用」，**禁止**用文案包装成可用；文案与实现状态不一致的条目不得标绿 | 全部 |
| **7.9** | **死代码清理误伤**：2-12（渠道策略字段、`:plugin-package` 依赖）、3-18（`AppDestination`）清理时删掉仍在用的符号 | P2 批次 | 清理前必须全仓检索消费方（含 `src/test`、`androidTest`、Gradle、XML、文档）；删除必须与调用方清除在同一提交内完成；**禁用 `rm`**，用 `trash-put` 或移动到 `/tmp/open-android-intelligence-trash/` | A5 + A1 |
| **7.10** | **两端不对等中间态**：A3 先合、A4 后补 → 中间态为半实现 | Wave 1 | A3/A4 必须在同一批次内完成同一组端点；合并顺序相邻（A3 → A4）；以向量为准判定谁偏离 | 主 Agent |
| **7.11** | **Git 回滚误伤他人提交** | 单项失败需回滚 | 每个 Agent 独立分支 `fix/settings-a{n}`（可配独立 worktree）；回滚只用 `git revert` 或回滚到**自己分支**的起始提交；**禁止 `git reset --hard` 到他人提交、禁止强推** | 各 Agent |
| **7.12** | **基线回归**：修缺陷时碰坏「已验证正常」12 项 | 全程 | §4.6 基线优先；每波同步点复检 B1–B12 | 主 Agent |

---

## 8. 分阶段交付物清单

### Wave 0 — 契约裁决与 Schema 冻结

- [ ] `docs/contracts/gateway-protocol-v2.md` 补丁：D1–D8 八项裁决写入对应章节（含 U2 的签名豁免裁定）
- [ ] `gateway-contract/schemas/*.schema.json`：新增/修订的 def（session 的 logout / unpair、device-request 的 claim / result、event 的两个子 Schema 等）
- [ ] `gateway-contract/vectors/*.json`：每个新 def 至少一正一负两条向量
- [ ] 核心摘要三方同步，`SchemaContractHash.kt:30` / `plugin-manifest.json:6` / `core.py:3135` 一致
- [ ] 提交说明写明摘要 `sha256:665df516…cacb` → 新值
- [ ] 交付物：本文档 §3.5 的裁决表填齐「结论」列
- [ ] 如 D6 判定需放宽依赖边界：新增 `docs/adr/0050-*.md`

### Wave 1 — 七 Agent 并行实现

| Agent | 交付物 |
|---|---|
| A1 | 2-1（真实 refresh 调用 + notice 有消费方）、3-16（圈选文案如实降级）、3-15（能力位进 `SettingsUiState`）、2-6/2-7/2-8（按 D5/D2 降级呈现）、2-10（静态条目转说明文本或真实绑定）、2-11（熔断计数持久来源）、3-17（M3 Sheet 切换或明确裁定）、3-18（死代码清理或接入） |
| A2 | 2-2（授权生效通路或如实降级）、2-3（screenSelection 进内核或如实降级）、2-5（信任模式持久化）、2-9（审计哈希链/只追加 + 「用户确认」写入 + 文案对齐）、3-9（`error.code` 解析）、3-7（生产 `DeviceRequestTransport` + `GatewayRuntime` 接线）、3-8（result body 形状对齐 D3） |
| A3 | 3-1（`revokeRefresh` 语义修正）、3-2（解除配对管理端点）、3-5（`session.revoked` 事件进入事件流）、3-6（授权 revision + `pairing.grant.changed` + `GRANT_STALE`）、3-3、3-4 |
| A4 | 与 A3 **逐项对等**的同名端点/事件/状态机 + U3 的 `purge` 裁定落地 |
| A5 | 1-1（插件管理区域）、1-2（声明式设置项/状态卡片渲染器与消费方）、1-5（ADR 0042 扩展点或明确裁定）、2-12（渠道策略字段消费或清理）、3-19（三份参考 manifest 对齐契约 §5 + 明确 `plugins/dist` 以源码为准） |
| A6 | 1-4（通知采集设置界面 + Manifest 声明 + 宿主装配）、2-4（授权与查询门同步） |
| A7 | 1-3（截图采集实现 + `ScreenCaptureSource` 接口 + overlay 接线），并向 A1 提交接口回执 |

每 Agent 另需交付：`docs/superpowers/handoffs/2026-09-22-app-settings-agent-a{n}.md`（§6.3）。

### Wave 2 — 集成接线

- [ ] W2-1…W2-8 八项接线动作全部执行（§4.4）
- [ ] `PlatformSettingsEnvironment` 新字段与构造点一致，编译通过
- [ ] 新增的 `PluginManagementScreen` / `NotificationCollectionScreen` 已挂到 `SettingsRoutes` 与 NavHost 与概览入口
- [ ] `AndroidManifest.xml` 组件声明齐备且未引入不需要的权限
- [ ] `app/build.gradle.kts` 依赖变更与 D6 裁决一致

### Wave 3 — 验证与发布

- [ ] 本机最小测试全绿（§5.2）
- [ ] 中文 commit（`新增:` / `修复:` / `优化:` / `重构:` / `文档:` / `清理:`）并 push
- [ ] 独立子 Agent 复审报告（维度：代码规范、架构一致性、契约完整性、潜在缺陷与回归风险）
- [ ] 复审阻塞项修复并回到最小测试重跑
- [ ] CI G1/G2/G3 全绿，失败必须修复后重新验证，不得在未修复状态下继续推送
- [ ] 真机 R1–R9 验证记录
- [ ] 基线 B1–B12 无回归确认
- [ ] 面向用户的交付说明（含「App 与插件须同版本升级」提示，若摘要有变）

---

## 9. 最终验收标准

> 全部可勾选、可机读。任一项未勾选即不得宣告完成。

### 9.1 条目闭环

- [ ] 36 项缺陷条目状态全部为「已验证」或「关闭」（关闭须附重分类回执或裁决结论）
- [ ] P0 共 10 项（3-1、3-2、3-6、2-2、3-16、2-1、3-7、2-9、3-9、3-15）**无一例外**
- [ ] 7 个 Cluster（C-A…C-G）各自整体闭环，不存在「只修了 Cluster 里的一条」
- [ ] 4 项未确认（U1–U4）全部有结论：U1–U3 由 D1–D4 裁决，U4 已复核澄清（§10）
- [ ] 每项都有对应的红测试 → 绿测试记录，测试落在 seam 上（§5.1）

### 9.2 契约与双宿主

- [ ] `npm run gateway:v2:conformance` 双宿主 resultHash 一致，向量全 pass
- [ ] `npx vitest run gateway-contract/test/` 全绿
- [ ] 核心摘要三方一致（`SchemaContractHash.kt:30` = `plugin-manifest.json:6` = `core.py` 比对通过）
- [ ] 端点两端对等：A3 与 A4 的端点/事件清单逐项相同，无单端实现
- [ ] 能力位声明符合契约 `:142`（未实现一律不得声明），D7 结论已落地到两端 manifest 与 `NegotiationClient`

### 9.3 Android

- [ ] `./gradlew check`（CI G2）全绿，四模块单测数不低于基线（19/35/142/204，共 400）
- [ ] `ArchitectureBoundaryTest`、`MaterialTokenGuardTest`、`ThemeContrastTest`、`DistributionVariantTest` 全绿
- [ ] `:app:assembleFullDebug`（G3）构建成功
- [ ] 全仓检索：`operationNotice` 有 UI 消费方；`dismissOperationNotice()` 有调用方或已删除
- [ ] 全仓检索：无新增死代码（`AppDestination`、未被构造的 `DistributionPolicy`、零读取的 `BuildConfig` 字段均已处置）

### 9.4 真机与体验

- [ ] R1–R9 全部执行并有结论（§5.5）
- [ ] 界面无虚假能力宣称：任何不可用的能力在设置页呈现为不可用或有明确说明
- [ ] 所有降级路径符合 `specs/2026-09-12-app-material-ui-and-motion.md:55/67/75`
- [ ] 高敏能力（通知监听、屏幕采集）均有系统设置页跳转 + 用户显式授予 + 未授予时不可用

### 9.5 流程

- [ ] 8 份 Agent 回执齐备，字段完整（§6.3）
- [ ] 进度日志连续，`grep BLOCKED` 无未处置项
- [ ] 每次涉及 Schema 的提交说明均写明摘要变化
- [ ] 独立子 Agent 复审无阻塞项
- [ ] 基线 B1–B12 无回归

---

## 10. 复核记录（写方案前的只读复核）

本方案写作前对关键落点做了独立复核，**审计结论无一被推翻**；以下为复核结论与需在执行期注意的事实澄清：

| # | 复核项 | 结论 |
|---|---|---|
| 1 | `plugins/dist/` 是否存在 | **存在**，但被 `.gitignore:83` 忽略；其中产物前缀为旧命名空间 `org.agentlife.*`，与源码 `build-references.ts:29/50/71` 及三份 manifest 的 `org.openandroidintelligence.*` 不一致 ⇒ **属陈旧产物，不作为当前包名依据**（U4 澄清） |
| 2 | `ArchitectureBoundaryTest.kt:22-29` 禁依赖范围 | 逐条 6 个模块：`:notification-collector`、`:sms-collector`、`:call-log-collector`、`:transport`、`:tailnet-core`、`:capability-sync-runtime`；该文件共 2 个测试方法（依赖断言 + `coreNavigationContainsOnlyThreeMainDestinations`） |
| 3 | `AppDestination.kt` | 6 个目的地（`ConversationHome`、`ConversationThread`、`GatewayManagement`、`AttachmentAndMedia`、`PlatformSettings`、`PluginManagement`）；全仓 7 处命中**全在文档**，代码零引用 ⇒ 死代码成立 |
| 4 | `GatewayRuntime` 的 notice | `_operationNotice` 定义 `:84`、暴露 `:85`、`dismissOperationNotice` `:86`、写入 `:150/191/197/275`；全仓 8 处命中均在 `GatewayRuntime.kt` 与审计文档 ⇒ **无 UI 消费方成立** |
| 5 | `refresh` 真实调用点 | `GatewayRuntime.kt:250`（在 `restoreSessionIfAvailable()` 内），与审计 `:249-255` 一致 |
| 6 | `NegotiationClient` 能力位 | `AUTH_FEATURES:128` = `["password","refresh"]`；`CONVERSATION_UI_FEATURES:144-150` = 5 项（`agent-command-catalog-v1`、`agent-command-new-v1`、`agent-approval-cards-v1`、`message-batches-v1`、`generation-cancel-v1`）；`deviceRequests` 在 `:111` 被解析为单字符串且无消费方 |
| 7 | `FloatingConversationPanel.kt` | `screenshot = null` 在第 **74** 行、`onCropConfirmed` 抛错在第 **73** 行（审计记为 `:70-75`，行号属同一区间，结论一致） |
| 8 | `readableFailure` 位置 | 不在 `SettingsComponents.kt`，而在 `conversation-ui/.../components/StateViews.kt:64` |
| 9 | ADR 编号 | `docs/adr/` 共 49 份，最大编号 **0049** ⇒ 新增 ADR 从 **0050** 起 |

---

## 附录 A：Agent × 条目分派总表

| Agent | P0 | P1 | P2 |
|---|---|---|---|
| A0 | 3-2、3-6（裁决） | 3-12、3-10、3-11、3-8（裁决） | 3-3、3-4（裁决） |
| A1 | 2-1、3-16、3-15 | 2-6、2-7、2-8 | 2-10、2-11、3-17、3-18 |
| A2 | 2-2、2-9、3-9、3-7 | 2-3、2-5、3-8 | — |
| A3 | 3-1、3-2 | 3-5、3-6 | 3-3、3-4 |
| A4 | 3-1、3-2 | 3-5、3-6、U3 | 3-3、3-4 |
| A5 | — | 1-1、1-2、3-19 | 1-5、2-12、3-18 |
| A6 | — | 1-4、2-4 | — |
| A7 | — | 1-3 | — |

> 同一条目出现在多个 Agent 行表示跨端/跨层协作（如 3-1 需 A3 与 A4 对等实现）；由主 Agent 按 §4.4 顺序合并。

## 附录 B：命令速查

```bash
# 本机 Android 最小测试（替换 <模块> 与 <FQCN>）
cd apps/android && env LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 ANDROID_HOME=$PWD/../../.toolchains/android-sdk GRADLE_USER_HOME=$PWD/.toolchains/gradle-home ANDROID_USER_HOME=$PWD/.toolchains/android-user-home ./gradlew --offline :<模块>:testDebugUnitTest --tests "<FQCN>" :app:compileFullDebugKotlin

# 契约侧
./tools/run-node24 npx vitest run gateway-contract/test/
npm run gateway:v2:conformance
./tools/run-node24 npm run typecheck

# Hermes
cd integrations/hermes && python3 -m pytest -q

# 提交（中文说明，涉及 Schema 必须写明摘要变化）
git add -A && git commit -m "修复: <简要描述>"
git push origin main
```

## 附录 C：相关文档指针

| 文档 | 作用 |
|---|---|
| `docs/superpowers/reviews/2026-09-22-app-settings-feature-gap-audit.md` | 本方案的输入，36 条缺陷的证据源 |
| `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md:102/112` | 插件管理与声明式 UI 的规格依据 |
| `docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md:55/67/75` | 圈选降级与 M3 Sheet 的 UI 规格依据 |
| `docs/contracts/gateway-protocol-v2.md` | 契约裁决的权威源 |
| `docs/contracts/device-plugin-package-v1.md:87-156/245` | 插件 manifest §5 与八类声明式组件 |
| `docs/adr/0034`、`0042`、`0044`、`0045`、`0046` | 涉及条目的决策依据 |
