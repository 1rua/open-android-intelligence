# OAI 当前 OpenClaw 实现固定版本计划

关联 Issue #170。日期：2026-11-12。分支基线为最新 dev `dabfdbe35d52f4ca11ba2cfeaaff38cdfddd2ebb`；它包含原指定 `7e4c5e820ae1ca0476a78c032d3aa1792bfa6226`，后续差异仅为已合并 Android 菜单清理，当前契约未变。

外仓 canonical 为 `1rua/openclaw-android-gateway`。PR #4 经独立 Standards / Spec 两轴审查，已 squash 合并至 main `e73ddfe1b333cfe0f69c4ea519db3553b056381a`。集成必须固定这一不可变合并 SHA，不引用分支或曾经的 PR head。

## 变更边界

- 保持原 `tools/check-openclaw-plugin-pin.py` 的严格 `EXPECTED_REPOSITORY=1rua/openclaw-gateway-plugin`，保留 GitHub 可重定向的旧 repository 名；文档准确记录 canonical 身份，不因此次契约同步扩大重命名范围。
- 根 `openclaw-plugin-pin.json` 只将 ref / revision 从旧 c7c087b9 改为 e73ddfe，版本保持真实 package / manifest 的 1.1.0。
- 使用现有锁字段生成算法同步 `MVP-DEP-OPENCLAW-PLUGIN` 行和内容摘要，不更改其他依赖或 checker，不伪造验证完成或延长无关证据。
- 将外仓合并 tree 与审查候选全 tree 比较；归档两轴审查、八份日志 EOF 归一与最终合并证据到 OAI docs。
- 不修改生产运行逻辑、Android 数据、模型或宿主配置。本轮只草稿 PR，不合并。

## 验证与交付

1. 原 pin 对新检出应因 revision 不符失败，记录红反馈。
2. 更新后通过原实现 pin checker、当前契约 pin checker、OpenClaw 自身 contract:check。
3. 执行锁字段单元测试、原 lock:check、pin checker 测试和 CI 规划工具相关测试。
4. 使用 e73ddfe 的真实生成 runtime，配对固定历史 Hermes 解释器，对当前 OAI 完整共享 vectors 运行两边并逐条比较 ID、operation、resultHash；不能用历史自洽或单边 70 项替代跨宿主结果。
5. 全部证据入 `docs/reviews/`；固定提交推送 PR 后交独立审查。此轮不重复 Android 全量构建，不宣称真实设备验收。
