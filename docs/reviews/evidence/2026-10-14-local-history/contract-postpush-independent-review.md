# 附件事件契约：推送后独立审查

审查日期：2026-10-14。

审查对象：`851abc44e7ebf5a5aa0ea7ffe311d59be1538058`，相对基线 `d0fbfaeb9c0acda04dbe72fa9306d9109c54bdf1` 的确切提交。该提交发布至 `codex/local-history-contract-20261014`，仅冻结契约，不是完整 App／插件发行版本。

审查者未参与实现；生产文件均通过 `git show`、提交间 diff 或该提交的 `git archive` 快照读取。余下未提交 Android 改动不计入本次审查。需求背景为用户批准计划、总架构、AGENTS、ADR 0053 及本地会话附件规格；按项目代码审查 Skill 的规范与规格两轴评估。

## 规范轴

**未发现本提交新增、可定位的 P1／P2。**

- 正式事件 Schema 与派发目录保持封闭结构。文本和附件分支均设置 `additionalProperties: false`，由 `type` 常量区分；未开放路径、URI 或字节字段。
- 附件身份、非负整数长度及带前缀的小写 SHA-256 都有验证规则；正式目录与夹具对 delta／completed 使用相同正文结构和派发摘要。
- 中文提交说明明确列出核心摘要变化、双方配套升级要求及 `PROTOCOL_INCOMPATIBLE` 的影响，满足本仓核心 Schema 提交规范。新增审查与冻结证据位于仓库文档目录。

## 规格轴

**在“契约冻结”范围内未发现本提交新增、可定位的 P1／P2。**

- 文本、纯附件及混合内容由同一封闭联合承载；空文本仍合法。附件元数据可补充，字节不进入事件，符合本轮用原件读取端口保存附件的方向。
- 新增共享向量包含纯附件正向样例，以及未知字段、负长度和非法摘要三个反向样例；两种正文事件使用相同结构。未把向量存在本身当作 Android 存储或流式 HTTP 的通过证明。
- 按确切提交的七份核心 Schema 字节独立重算，得到 `sha256:63223ba149355032800eeae8a9f0827d542bbde7ca9e4eeb19f93df30e7febde`，与提交内 Android `SchemaContractHash.CORE` 及证据清单一致。
- 提交先发布协同分支、稍后固定插件与 App 源码的交付顺序已在冻结说明中明确；该分支不触发只监听 main 的自动 APK 发布。因此此处不将尚待更新的插件固定版本、完整 Android 修改或最终 APK 当作本契约提交已交付的功能。

## 独立验证与证据归属

- 从该提交提取临时快照 `/tmp/oai-contract-independent-ksvjucno`，独立运行 `vitest run gateway-contract/test/schema-registry.test.ts gateway-contract/test/dispatched-schema-validator.test.ts`：两个文件、117／117 通过，退出码 0。未在活动 Android 工作树运行 Gradle。
- 核对提交内 `contract-source-and-results.json` 列出的六个源码 SHA-256：6／6 与实际提交 blob 一致。
- 对正式 delta／completed 正文定义额外检查 13 类非法输入，共 26 次全部拒绝：包括未知类型、缺附件身份、混合分支字段、非法身份、负数／小数／字符串长度、非法／无前缀摘要，以及字节、路径、URI 字段。
- 对新增四个共享向量，按生产验证器的“先验证事件外壳，再按可信事件类型验证 payload”边界独立检查：1 个接受、3 个拒绝，4／4 与声明结果一致。
- 本次独立 Node 验证使用已有依赖，实际版本为 Node `26.7.0`、Vitest `4.1.10`；不是按仓库锁文件重装后的 CI 工具链证据。

提交内 Android 日志的 **24 项应拆分解释**：14 项属于 Schema／派发向量／核心摘要；另外 10 项属于 RawHeaders／ResponseStreaming，在当时工作树执行，相关传输修改与新测试尚未包含在 `851abc44` 中。后 10 项不得归为该不可变提交自身的流式下载证明。建议完整交付文档直接写成“14 项契约＋10 项工作树传输”，避免把总数误读成同一提交的完整 Android 验证。

Hermes／OpenClaw 的 70 项共享向量结果属于提交前协同工作树的宿主验证，本审查未重新执行宿主进程或核对插件最终固定提交。本报告不证明最终三仓互通、Android 私有存储迁移、MediaStore、设备部署或完整 CI 已完成；这些须由后续固定源码和匹配 APK 的验证闭环提供。

规范轴：0 个 P1／P2；规格轴：0 个 P1／P2。证据说明存在上述数字归属澄清项，不作为生产缺陷报告。
