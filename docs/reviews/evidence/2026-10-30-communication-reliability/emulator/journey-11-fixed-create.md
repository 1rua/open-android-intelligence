# Journey：修复 APK 重新新建会话

- **版本**：修复 APK SHA-256 `314687db64727ef06a98e9e98aff07285cbb9b282d5b4d5d0691c1f20dd9ba33`；HEAD `f49d3d8d` 加未提交修复和诊断补丁，不是原 APK。
- **前提**：`adb -s emulator-5554 install -r <修复 APK>` 成功，保留 App 数据；重新打开后无需手动登录。发送前后端基线为 `userCount=3`、`turnCount=1`、`completedCount=1`、`liveTurns=0`。
- **操作**：执行原新建旅程，点击顶部“新建对话”一次。
- **结果**：✅ Core 创建 POST 返回 200；日志依次出现 `create.persistence_completed`、`native.creation_saved`、`create.ui_applied`，界面显示空会话及输入栏。
- **关联**：请求 `cconv_29e2c8951f514a8a96a897957c327268`；权威会话 `conv_b06de779-4322-4c03-b8e8-ccaaccdc4d10`。
- **边界**：修复版仍可见旧的离线镜像操作提示；本步骤不证明后续消息发送、事件恢复或其他场景。原 APK 失败报告保留。
