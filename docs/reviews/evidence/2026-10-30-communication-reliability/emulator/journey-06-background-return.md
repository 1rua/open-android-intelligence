# Journey：后台返回后恢复会话

## Results

### Action：按系统主页键将 App 置于后台。 ✅
- **命令**：`adb -s emulator-5554 shell input keyevent KEYCODE_HOME`。
- **检查**：系统桌面成为当前界面。

### Action：向上滑动打开系统应用列表。 ✅
- **命令**：`adb -s emulator-5554 shell input swipe 540 2140 540 700 700`。

### Action：点击应用列表中的 OAI。 ✅
- **命令**：检查布局中的 OAI 图标后执行 `adb -s emulator-5554 shell input tap 910 1552`。

### Action：Verify：无需重新登录即可回到会话，显示已连接，且历史不再提示无法加载。 ❌
- **实际结果**：无需重新登录即返回原会话界面，但顶部仍为“实时通道未建立”，历史仍提示“暂时无法加载”。
- **截图**：`06-background-return-failed.png`，仅本地留存。
- **边界**：该旅程从已经加载失败的会话开始，不代表正常运行中后台返回必然失败。
