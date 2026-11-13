---
status: accepted
date: 2026-08-24
---

# 使用明确插件生命周期并在 companion 故障时失败关闭

**2026-10-28 授权范围更新**：本 ADR 中“Gateway/配对 × 插件许可”保留为历史规则，不再适用。现行决策见 [ADR-0059](0059-android-is-the-sole-device-capability-authority.md)：Android 是唯一设备能力授权者，普通前台能力默认开启；后台同步是独立的 Android 本地许可。该规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；分支测试与真机验收状态见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。插件生命周期、伴随进程故障关闭、身份、审计及高风险确认规则继续有效。

插件将发现、下载、验证、安装、启用和 Gateway 授权分为不同状态，并明确表示等待更新批准、等待重新授权、缺少能力、宿主不兼容、companion 缺失、隔离、回滚和卸载。签名、摘要、作者、权限、网络或资源变化会阻止运行并要求重新批准；companion 缺失、崩溃、签名不符、版本不兼容或权限撤销时能力立即不可用，不自动切换提供者或重放高风险操作，恢复后必须重新验证身份、版本、权限和授权。
