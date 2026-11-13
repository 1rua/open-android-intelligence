# 诊断 APK：新建阶段观察

- **版本区分**：覆盖安装包 SHA-256 `8e77dd7fc1d05f018166bcb49f40dbc93da67ab09659291fedcd728ed6ccd17e`，BuildConfig 为 `f49d3d8d`，含未提交的诊断日志差异；与原业务 APK `dda2a4dc`、SHA-256 `1afbb55747cd0d38e147dd50e3b6efc1fa129762fae1532feffa497729972a99` 分开报告。
- **操作**：`adb -s emulator-5554 install -r <诊断 APK>` 成功；强制停止并重开 App，保留应用数据；2026-10-31 03:58:31（Asia/Shanghai）点击一次顶部新建对话。
- **阶段**：`create.clicked` 显示 `closed=false`、`controllerActive=true`、`creatingThread=false`、`managingThreads=false`、`bootstrapping=false`。随后出现 `create.persistence_started`，`recoveryOnly=false`，`requestId=cconv_4750deb0baff408f89a0db97d0f5f0f0`。
- **尚未观察到**：`create.persistence_completed`、`create.ui_applied`、`native.creation_saved`；Core 创建会话 POST 计数保持 6。应用 PID 为 `5195`。
- **线程栈结果**：主 Agent 随后采集本次卡死进程的线程栈，确认 ACK 确认线程持有镜像锁并等待数据库锁，列表保存线程持有数据库锁并等待镜像锁，双方互相等待。该栈可解释本轮持久化未完成、未发出创建 POST 和会话未恢复的现象。
- **证据边界**：线程栈结论由主 Agent 提供，本执行者未独立读取原始栈；它解释的是本次卡死，不能据此认定所有同类失败均有相同原因。原始栈和完整诊断日志仅本地留存，不提交。修复及回归结果尚待提供。
