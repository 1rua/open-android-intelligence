# 全局设备授权本轮交付与收尾

日期：2026-11-01，Asia/Shanghai。按用户“报告当前进度并做最后收尾”的要求，本轮停止新增设备操作与业务修复，整理已完成交付和未完成检查点。**UI-01 至 UI-10 已交付；原 GLOBAL 完整验收未完成，goal 保持 active。**

## 交付版本与 PR

- 原审计基线：`dev@3c9b82d1ef18062fc7dd09546efe6136803079cc`。
- 交付分支：`feature/global-device-authorization-20261101`；工作树：`.worktrees/global-device-authorization-20261101`。
- PR #71：`https://github.com/1rua/open-android-intelligence/pull/71`。本轮在线核对为 OPEN、非 Draft、目标 `dev`、未合并、自动合并关闭。
- 本次文档收尾前的远端 head：`ff720cde6b2057725925d24ea95403bb54a5f22c`。Core 3.11/3.12、Hermes、DSH、Android、APK、插件签名共七项 checks 全部 SUCCESS。对应 Actions Run 为 `41312482503`、`41312478792`。
- 此收尾提交只包含文档和证据索引。提交后形成的新 head 必须单独看待；上述七项成功仅属于 `ff720cde`，不能直接移用于新 head。本轮不等待或循环轮询远端流水线。

## 用户追加修改已完成

| 要求 | 已交付行为 | 证据与边界 |
| --- | --- | --- |
| 通知应用选择 | 沿用已安装应用列表，支持名称/包名搜索、正选/反选、全选/取消、草稿、错误重试和不可见项保留 | `1f338778`；定向、Full/Play 及 Terra 模拟器分项通过 |
| 短信范围 | 新配置默认全部，可选关键词或电话号码；被动读取与主动推送分别保存，正文外发开关独立 | `1f338778`；真实 CLI/MCP 关键词命中/不命中、正文边界和 OS 主动元数据通过；推送总开关仍默认关闭 |
| 应用数量 | 取消通知推送 UI、模型及 codec 的 256 应用数量上限，没有改成其他应用数量上限 | `c0779957`；1025 项 UI、257/1025/2049 项真实加密保存恢复通过，没有用虚构安装大量应用冒充设备验收 |
| 插件按钮 | 每个插件下方“恢复上一版本”按钮移除；底层历史包与回滚数据保留 | `c0779957`；多插件自动化和 Call Log 插件模拟器界面抽查，未逐插件遍历 |
| 主动推送菜单 | 复用既有设置子页容器及原导航四项转场，页面背景、标题栏、边距和返回与同级菜单一致 | `c0779957`；普通/减少动态两种模式、不同 presentation 自动化与 Terra 进入/顶栏返回录屏核验通过；没有自行定义新动画参数 |
| 文档规则 | 保留原 UI 功能与文案；禁止因功能变化擅自降级，禁止自行定义用户未明确要求的产品设计 | 规则已写入 spec、CONTEXT、设置规格、指南和 AGENTS.md；详见 [UI 保真记录](2026-11-01-device-scope-ui-preservation.zh-CN.md) |

`12d3f8f6`、`c18ffb70`、`ff720cde` 补充了对应运行证据。源码、测试、模拟器与物理手机结论分开记录。

## 自动化结果

- 最后一次完整本地 `npm run test:fast` 与 `npm run test:pr` 均退出 0，原始日志为 `../plans/2026-11-01-global-device-authorization/runtime/ui-followups-fast.log`、`ui-followups-pr.log`；日志与 XML 属于当时包含屏幕工作补丁的源码状态，不能冒称 PR 提交本身的测试数量。
- 该本地状态：App 1361、screen-control 44、device-primitives 61 项，均 0 失败、0 错误、0 跳过；Core 634 passed、1 项符号链接环境 skip、6 subtests，Hermes 143、DSH 37、通信测量 25 passed。
- 完整门禁后仅追加屏幕布尔/数字诊断，screen-control 44 项及 App/测试 APK 构建通过，10/10 生产源码与 manifest 匹配。诊断 App SHA-256 为 `2ad99dae1f55092aa51e73763a8bc4b452a6a155a054ecf59c354b98311e8e75`；**该诊断包未安装、未执行**。
- 本轮收尾只改文档，`npm run test:fast -- --since HEAD` 与 `npm run test:pr -- --since HEAD` 均退出 0；自动识别 11 份文档/任务状态文件，仅运行依赖锁与 noVpnSurfaceCheck，未重复全模块测试。9 份 Markdown、119 个相对链接、代码围栏、任务 JSON 和变更格式检查通过；暂存范围另在提交前核验。日志为 `runtime/closeout-docs-fast.log`、`runtime/closeout-docs-pr.log`。

## 最后一轮新增运行证据

### 临时许可

5556/user0、原 `d097…` 绑定，仅使用 2099 年起的空短信范围、limit 1；没有读取历史短信。

- 五分钟真实批准后，不同 Agent 声明的同范围请求无须再次批准，均成功且 0 条。
- 22:09:41.541–22:09:41.562 重新授予五分钟；22:10:09.393–22:10:09.413 由用户入口撤销，相隔约 28 秒。后续同范围请求重新 `awaiting_confirmation`，未批准，取消终态为 `cancelled`。
- 22:16:53.965–22:16:53.986 再次授予五分钟；22:17:46.685–22:17:46.703 由同 UID `run-as` 精确终止已核对 PID。旧 PID 消失，重新启动后为新 PID；22:18:39–49 的同范围请求重新要求确认并取消，仍在原五分钟内。此项只证明受控突然进程死亡，不是系统 OOM 或整机重启。
- 结束时经真实 UI 恢复 SMS `READ_ONLY`、原关键词、正文关闭；`READ_SMS` 已授权、原 `d097…` 绑定在线。主动推送设置未再次核验。本轮全部相关请求成功或取消，没有新增持续观察进程。

详见 [临时许可记录](2026-11-01-global-device-authorization-temporary-lifecycle.zh-CN.md)。文件时间上界、精确点击时间和仅有工具输出的观察在原记录中分别注明。

### 系统无障碍撤销

5554/user10 经真实系统设置 ON→OFF→ON；关闭时 reader/control 均为 `ANDROID_PERMISSION_REQUIRED`。唯一 reader 提交在创建 requestId 前因 `ACCESSIBILITY_SERVICE_UNAVAILABLE` 被拒绝；恢复后两能力 `READY`。没有提交恢复后的读/控制请求，恢复后长期策略也未重开页面复核。

详见 [系统撤销记录](2026-11-01-global-device-authorization-system-revoke.zh-CN.md)。该项不代表其他系统权限撤销、MediaProjection 撤回或屏幕允许动作通过。

## 未完成项与安全保存

1. **screen task-011 仍 FAIL**：fresh reader 后真实“仅允许本次”的控制动作仍为 `SCREEN_STALE_REFERENCE`。最新 `3b32…` 运行链在批准前各条件匹配并显示真实卡片，批准后完整等价检查失败；还不能从旧日志确定具体是哪一个目标条件改变。reader、截图、只读拒绝及自动化通过均不能替代控制允许成功。
2. 未完成源码、测试、CONTEXT 和屏幕 ADR/指南共 18 文件已保存为仅本地分支 `feature/global-device-authorization-screen-wip-20261101`，提交 `c71735b0daf325ab24cc9856b8da1917183a5b06`，**未推送、未进入 PR**。备份 patch 位于被忽略的 `../plans/2026-11-01-global-device-authorization/runtime/screen-wip-closeout.patch`。其中审批审计测试只有诊断断言增强，未证明生产 NPE 根因。
3. 诊断包安装前发现 5554 的 user0/user10 共享 APK 程序路径，多用户只隔离数据，不能隔离程序版本。因此未覆盖安装；原拟改到 5556 的新诊断已随本轮收尾取消，没有新请求。后续从该本地分支及 [屏幕续接记录](2026-11-01-global-device-authorization-screen-continuation.zh-CN.md) 继续。
4. 通知被动查询独立范围、其余系统权限撤销及 AUTO/错误语义、主动队列真正断网/撤销/登录补发、息屏/Doze/系统实际回收、退出后在途执行停止、真实 v1/v2 授权迁移及物理手机完整矩阵仍有缺口，逐项见 [剩余验收复核](2026-11-01-global-device-authorization-open-acceptance.zh-CN.md)。临时许可和 Accessibility 新结果仅收窄各自缺口，不合并成整组 PASS。
5. 旧版准备已找到并核包验签一个真实授权模型 v2 APK，但没有执行 v1/v2→v4 覆盖升级；当前两个模拟器均未被降级。见 [旧版本准备](2026-11-01-global-device-authorization-legacy-validation-preparation.zh-CN.md)。
6. 未连接实体手机；本次只有自动化及模拟器证据。原 task-008/009/011 保持未完成；其余 10 个任务为已完成，整体 goal 仍 active，未缩减原验收标准。

## 环境交接

- 收尾不删除或重建账号、主密钥、设备绑定、配对、会话、历史、插件、系统权限及测试数据；旧 `c16…` 绑定没有被恢复成当前绑定。
- 5554 的无障碍恢复状态依据 22:19 左右的存证；5556 最后状态依据临时许可验收结束时的观察。没有把这些存证写成收尾后的实时快照。
- 既有隔离 Core 在最后端口核对时，127.0.0.1:19841/19842 仍 LISTEN；未擅自停止共享模拟器、Core 或先前已存在的 OaiE2E 日志进程。本轮没有新增持续观察进程，也不声称其他会话无活跃请求。
- runtime 内原始日志、界面、APK、凭据及测试数据保持 Git 忽略；提交的是可公开的脱敏报告和索引。原始 runtime 链接只在现工作树可用，不随克隆自动分发。
- 交付工作树回到 `feature/global-device-authorization-20261101`。PR 保持 Review，未合并；本地屏幕检查点保留供后续继续。
