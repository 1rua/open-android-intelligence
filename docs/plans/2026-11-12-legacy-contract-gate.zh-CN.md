# 历史 Gateway 契约门禁修复计划

关联 Issue #158。工作树基于最新 dev，固定历史 Hermes Gateway 仍为 ebe527da2426dd25877dd335786e80cf85d1cdde，不改外部仓库 pin。

## 问题与正确验证边界

Full CI 把刻意冻结的历史 HTTP 夹具 pin f0b3f1bd 与当前契约强制相等，早于本轮 DSH 修复的设备授权契约变化使门禁恒失败。后续跨宿主 conformance 已显式用当前契约驱动历史 Gateway，Android 历史 HTTP 夹具也读取当前契约；这两条实际兼容性断言必须保留。

## 实施

1. 增加固定历史来源与契约自洽验证：检查插件 Git HEAD、已提交源码与 pin，导出其明确锁定的契约并校验导出完整性，再用真实历史 Gateway 执行该版本共享 vectors。
2. 当前 OpenClaw pin、当前契约 vectors、跨宿主结果比对、Android 历史 HTTP 业务仍执行，不能用历史基线成功代替当前兼容性。
3. 先补红回归，覆盖错 pin、错插件版本、源码脏改、导出缺失/污染及真实 vectors 失败均拒绝；修复后执行原失败场景和两种版本的真实契约反馈环。
4. 复用现有依赖、运行聚焦脚本及 Fast/PR 必要门禁，归档准确命令与结果；独立审查和最终 Full CI 由主任务集成。

当前状态：计划提交与 Draft PR 阶段，尚未实施；不改生产服务、身份、凭据或会话。
