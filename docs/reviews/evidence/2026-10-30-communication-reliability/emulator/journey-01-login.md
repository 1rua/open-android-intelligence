# Journey：隔离 Core 登录

## 环境

- 工作树 HEAD：`f49d3d8d7c1df032e18c3c7fe471e90c817da61a`。
- APK：`apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`；SHA-256：`1afbb55747cd0d38e147dd50e3b6efc1fa129762fae1532feffa497729972a99`。
- APK 中发现构建提交号 `dda2a4dc8ece02289b3e4f54dab11f8515249707`；包名为 `com.openandroidintelligence.mobile`。
- 设备：`emulator-5554`，Android 15 / API 35，x86_64，KVM 加速；全新专用 AVD，通知权限已授权。
- 后端：独立 Core、Session Adapter、SQLite、Hermes HistoryPort 与受控执行器；HTTP 仅用于隔离测试，不证明生产链路安全。
- 发送前控制接口基线：`userCount=2`、`turnCount=1`、`liveTurns=0`、`completedCount=1`、`interruptedCount=0`。已有结果来自此前烟测。

## Results

### Action：在服务端地址输入框输入隔离 Core 地址。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 540 894`；检查布局显示地址输入框 `FOCUSED`；`adb -s emulator-5554 shell input text <隔离地址>`。
- **截图**：`01-login-entry.png`，仅本地留存。

### Action：Verify：界面显示 HTTP 未加密连接提示。 ✅
- **检查**：`android layout --device emulator-5554 --flat --pretty` 显示未加密连接提示，包括口令和消息可能被读取或篡改的说明。

### Action：在用户名输入框输入测试账号。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 540 1340`；确认对应输入框 `FOCUSED`；`adb -s emulator-5554 shell input text <测试账号>`。

### Action：在密码输入框输入测试密码。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 540 834`；确认密码输入框 `FOCUSED`；`adb -s emulator-5554 shell input text <测试密码>`。
- **说明**：账号和密码已脱敏，不将输入后的登录页截图归档。

### Action：点击“登录 OAI Core”。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 540 1124`。

### Action：Verify：登录成功，进入已连接的应用界面。 ✅
- **检查**：布局显示会话输入栏、新建对话入口，以及隔离 Core 的“已连接”状态。
- **说明**：登录后自动打开了先前烟测产生的会话；其中内容不作为本次消息验收结果。
