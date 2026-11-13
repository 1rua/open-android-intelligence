# Journey：最终 APK 正常工作台 HOME 返回

- **版本与前提**：同 journey-17 的最终 APK `f29e714a…b110a0`，API 35 原 AVD；会话 `conv_71b1e3a7-afb8-425d-901e-bf41f5ef100f` 已有两条完成消息。

## Results

### Action：按 HOME 并检查系统桌面。 ✅
- `adb -s emulator-5554 shell input keyevent KEYCODE_HOME`；布局确认当前窗口为 `Home`。

### Action：打开应用列表并点击 OAI。 ✅
- `adb -s emulator-5554 shell input swipe 540 2140 540 700 700`；读取布局确认 OAI 图标后点击 `910 1552`。

### Action：Verify：无需登录返回原会话，历史完整，没有过期离线提示或加载失败。 ✅
- 两条用户正文各出现一次，两个完整回复均在；完成标识仍为 `gen_7c38711e5757da153349d832236e95cdbc8b1d19` 和 `gen_6b156c94b48c9104f18ee73455c1684d6fe4853d`。
- 顶部已连接，没有登录表单、离线镜像提示或加载失败。截图已目视检查。
- `final-home-return.png`、`final-home-return.json`、`final-home-check.json`，仅本地留存。
- **边界**：一次正常短时后台返回，不代表长时间后台或厂商省电机制已验证。
