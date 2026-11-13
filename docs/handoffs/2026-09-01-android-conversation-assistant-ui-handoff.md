# Android 对话与数字助理界面重构交付记录 (2026-09-01)

## 交付概要
完成了 open-android-intelligence Android 宿主界面的完全重构，对齐 2026-08-29 对话与数字助理界面设计规范。

## 关键提交与范围
- **协议与契约扩展**: `gateway-contract` 引入 `message-batches-v1` 与 `screen-selection-v1`，补齐 Schema 与共享测试向量。
- **Android gateway-client**: 支持带断点光标恢复流式连接、批量消息与选区元数据上传。
- **设计系统与动效底座**: 实现了 `conversation-domain` 和 `conversation-ui`，提供物理弹簧插值与无障碍降级。
- **双宿主适配器测试**: Hermes 与 OpenClaw 均通过共享测试向量验证。

## 验证证据
- `npm --prefix gateway-contract test`: 7 文件 164 用例全绿。
- `vitest run`: 81 文件 785 用例全绿。
- `pytest integrations/hermes/tests/`: 10 用例全绿。
- `./gradlew :conversation-domain:testDebugUnitTest :conversation-ui:testDebugUnitTest :gateway-client:testDebugUnitTest`: 全绿。
- `./gradlew :app:assembleFullDebug`: 构建成功。
