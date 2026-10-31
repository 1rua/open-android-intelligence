# Journey：重开 App 进程后的会话恢复（原 APK）

## Results

### Action：停止 OAI 应用进程。 ✅
- **命令**：`adb -s emulator-5554 shell am force-stop com.openandroidintelligence.mobile`。停止前进程 PID 为 `3308`，没有清除应用数据。

### Action：按系统主页键进入桌面。 ✅
- **命令**：`adb -s emulator-5554 shell input keyevent KEYCODE_HOME`。

### Action：向上滑动打开系统应用列表。 ✅
- **命令**：`adb -s emulator-5554 shell input swipe 540 2140 540 700 700`。

### Action：点击应用列表中的 OAI。 ✅
- **命令**：确认图标位置后执行 `adb -s emulator-5554 shell input tap 910 1552`。

### Action：Verify：无需重新输入凭据即可进入已连接会话，历史不显示无法加载。 ✅
- **实际结果**：无需重新输入凭据；顶部显示“已连接”，历史消息可见，没有“暂时无法加载”。
- **边界**：同时出现“离线镜像”提示，单凭此页面不能证明实时流或服务器历史已恢复；后续由独立消息旅程验证。
