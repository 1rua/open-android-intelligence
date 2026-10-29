# OAI 设备能力参考与参数指南

本参考文档总结了 OAI Core 常见设备能力标识、适用场景与典型调用参数。

**2026-10-28 设备授权规则**：Android 是设备能力授权的唯一权威，已安装且启用的插件普通前台能力默认开启；本地显式开关、系统权限、后台许可、数据范围与必要敏感确认仍决定实际执行。Core 和 Agent Gateway 不另设账号能力开关。规则已在 `feature/android-device-authority-20261028`，待 PR 合入 `dev`；分支实现与验证见 [ADR-0059](../../../../docs/adr/0059-android-is-the-sole-device-capability-authority.md) 和[实施记录](../../../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。能力名称和参数始终以当前实际发现结果为准。

## 1. 基础设备信息与状态

### 设备发现
```bash
oai devices
```
返回值包含已绑定的设备基本属性（设备 ID、型号、连接状态等）。

### 能力发现
```bash
oai capabilities
```
返回一个 JSON 对象，结构通常如下：
```json
{
  "capabilities": [
    {
      "id": "org.openandroidintelligence.notifications.query",
      "version": "1.0.0",
      "parametersSchema": {
        "type": "object",
        "properties": {
          "limit": {"type": "integer", "minimum": 1, "maximum": 100},
          "packages": {"type": "array", "items": {"type": "string"}}
        }
      }
    }
  ]
}
```

---

## 2. 常见能力列表及参数范例

### 2.1 通知查询 (`org.openandroidintelligence.notifications.query`)
- **版本**: `1.0.0`
- **参数**:
  - `limit` (可选整数): 返回通知数量限制，例如 5。
  - `packages` (可选字符串数组): 过滤指定应用包名（如 `["com.tencent.mm"]`）。
  - `mode` (可选): `"on_demand"`。
- **调用示例**:
  ```bash
  oai invoke org.openandroidintelligence.notifications.query \
    --version 1.0.0 \
    --parameters '{"limit": 5}' \
    --idempotency-key "req-notif-$(uuidgen)" \
    --wait 10
  ```

### 2.2 短信查询 (`org.openandroidintelligence.sms.query`)
- **版本**: `1.0.0`
- **参数**:
  - `limit` (可选整数): 返回短信条数，例如 10。
  - `threadId` (可选字符串/整数): 会话 ID。
- **调用示例**:
  ```bash
  oai invoke org.openandroidintelligence.sms.query \
    --version 1.0.0 \
    --parameters '{"limit": 10}' \
    --idempotency-key "req-sms-$(uuidgen)" \
    --wait 15
  ```

### 2.3 屏幕快照与状态读取 (`org.openandroidintelligence.screen.capture`)
- **版本**: `1.0.0`
- **作用**: 获取当前屏幕界面截图或布局信息。
- **调用示例**:
  ```bash
  oai invoke org.openandroidintelligence.screen.capture \
    --version 1.0.0 \
    --parameters '{"format": "png"}' \
    --idempotency-key "req-screen-$(uuidgen)" \
    --wait 20
  ```

### 2.4 屏幕手势与交互操控
若手机端启用了无障碍或操控插件，支持点击、滑动、输入文本、返回等动作：
- **点击 (`org.openandroidintelligence.screen.tap`)**:
  - 参数: `{"x": 500, "y": 800}`
- **滑动 (`org.openandroidintelligence.screen.swipe`)**:
  - 参数: `{"startX": 500, "startY": 1200, "endX": 500, "endY": 300, "durationMs": 300}`
- **输入文字 (`org.openandroidintelligence.screen.input_text`)**:
  - 参数: `{"text": "Hello Android"}`
- **返回键 (`org.openandroidintelligence.screen.back`)**:
  - 参数: `{}`

---

## 3. 错误码与处理建议

| 错误码 | 原因说明 | 应对动作 |
| :--- | :--- | :--- |
| `LOGIN_REQUIRED` | 本地未保存有效凭据 | 执行 `oai login` 重新登录 |
| `CAPABILITY_NOT_FOUND` | 设备或 Core 未注册该能力 | 检查 `oai capabilities` 输出核对 ID |
| `CAPABILITY_DISABLED` | 本地能力关闭，或旧版本 Core 因账号策略拒绝；以实际返回原因核对 | 检查手机状态；旧 Core 的拒绝按[历史修复计划](../../../../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)记录，不以增设服务端授权解决；当前分支状态见[实施记录](../../../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md) |
| `GATEWAY_DISABLED`（旧能力目录状态） | e5 `dev` 基线的目录状态，表示旧账号策略拒绝，与当前授权规则不一致 | 记录版本和请求关联信息并核对 Core 是否已升级；该判断已在 feature 分支修复，详见[实施记录](../../../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；不要求旧 Gateway 能力 setup |
| `IDEMPOTENCY_CONFLICT` | 相同幂等键使用了不同的参数 | 复用相同参数重试，或生成新幂等键 |
| `AWAITING_APPROVAL` | 操作需要用户在手机屏幕上手动授权确认 | 提醒用户查看手机屏幕并确认授权 |
| `DEVICE_OFFLINE` | 手机客户端已断开与 Core 的长连接 | 提示用户检查手机网络及 OAI 运行状态 |
