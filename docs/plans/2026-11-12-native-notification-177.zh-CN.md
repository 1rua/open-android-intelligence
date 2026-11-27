# 原生完成通知测试清理修复

关联 #177、PR178。基线 `2caabfb1206fd93cd0232171f0bf287fdfeea498`。

受控真实回环 HTTP/SSE、CoreRepo 和 Workbench 反馈环已证明：旧测试 fixture 仅取消、不等待 owner 完成，可能在通知环境重置后发布上一 Profile 的同步终态回调，导致下一测试的原 `single` 抛同一多匹配异常。最小修复仅保存 owner 并在原取消/回环关闭后等待其完成，不改生产通知、tag 或业务断言。

已验证：真实流受控红1失败；等待完成的双SDK整类含受控用例36通过；移除门闩/反射/DEBUG后的最小补丁原SDK34/36整类34通过。详见[诊断与边界](../reviews/2026-11-12-native-notification-177/diagnosis.zh-CN.md)和[固定计数](../reviews/2026-11-12-native-notification-177/public-result.json)。CI当次额外通知身份未捕获，不能将受控证据写成当次唯一来源。

独立 Standards/Spec、相关PR门禁及后续同一提交Full实际通过后，再按#177范围关闭；Full历史失败继续保留，不能以本机整类成功替代Full。
