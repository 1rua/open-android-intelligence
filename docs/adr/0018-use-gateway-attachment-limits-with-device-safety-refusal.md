---
status: superseded by ADR-0051
date: 2026-08-22
---

# 历史决策：使用 Gateway 附件限制并保留设备安全拒绝

本 ADR 已由 ADR-0051 取代，仅保留历史背景。

Open Android Intelligence 不规定统一的单文件或单消息业务大小上限，每个 Gateway 必须声明有限的大小、媒体类型、超时和临时保留限制，Android 在选择与上传前向用户展示。附件由用户通过系统选择器明确选择并流式上传；即使文件符合 Gateway 限制，Android 仍可因本机磁盘、内存、电量、流量策略、系统调度或用户取消而拒绝或中止，Gateway 不能用“无限”配置取消设备资源保护。
