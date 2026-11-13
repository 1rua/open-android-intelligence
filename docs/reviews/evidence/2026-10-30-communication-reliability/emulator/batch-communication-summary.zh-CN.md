# 模拟器通信验收阶段小结

执行者按调度配置为 GPT-6.1 Terra。设备为原专用 AVD `emulator-5554`、Android 15 / API 35；本阶段 APK SHA-256 为 `f29e714ab5c3a635ea61c941e558d4b8d8ee524ed0f34626cc871dfcbbd110a0`，BuildConfig `f49d3d8d` 加未提交修复补丁。以下仅汇总已执行结果，不代替后续像素修复验收。

| 已通过项目 | 实际证据范围 | 独立报告 |
| --- | --- | --- |
| 新会话两条正文及回复 | 用户正文、完整回复各一次；每条 Core POST、Adapter POST、宿主启动、宿主完成均为 1 | journey-17-final-messages.md |
| 正常 HOME 返回 | 无需登录，原历史完整，无过期离线提示 | journey-18-final-home.md |
| 息屏唤醒后的通信 | 显示器实际 OFF 后唤醒；历史保留，新输入逐层请求及执行各一次，生成完成；像素判定单列为失败 | journey-19-final-screen.md |
| 运行中关闭模拟器全部网络并恢复 | 无默认网络且 App 通道已断；宿主离线期间完成，恢复后无需登录或刷新补回原终态，原输入不重发；恢复后还能发送 | journey-20-network-recovery.md |
| 运行中隔离 Core 崩溃重启 | 只中断校验过的隔离 Core，Adapter 保持运行；宿主完成后重启 Core，自动补回原任务。跨进程 Core T2 为 1，宿主启动及完成各 1；handler 结束记录缺失不能当作未收到 POST | journey-21-core-restart.md |
| HOME 返回后定向停止 | 当前运行身份一致才点击；Core/Adapter cancel 各 200，真实 done 回执 state=cancelled；宿主取消一次、interruptedCount 增 1、liveTurns 归 0 | journey-22-running-stop.md |

## 独立 UI 失败仍保留

息屏唤醒后发送的 `cm_2315eb14-92c1-4b26-9bfc-cc998094568e`，对应 `gen_fde3dd96b345a5816dccfdf1982b2a169c5e963f`：连续两张截图显示新用户气泡两份，布局树只列一份，后端请求及执行均各一次。此项像素验收未通过，不解释成传输重复，也不以其他通信通过覆盖。

`final-screen-send.png`、`final-screen-send-confirmed.png` 和原始布局/日志仅本地留存，不提交正文或凭据。代码侧正在准备最小 UI 修复；修复包尚未安装或验收，不能预先宣布绘制问题已解决。

## 当前交接与后续范围

最近一批结束时 `userCount=14`、`turnCount=7`、`completedCount=6`、`interruptedCount=1`、`liveTurns=0`；网络原设置已恢复，模拟器及隔离服务保留。本轮只补写小结，没有新 UI 操作。

新包就绪后，仅重跑息屏后发送的像素与语义布局对照、两条连续消息是否重复；按后续指令必要时切换会话刷新旧绘制层。不重跑已确认的网络、Core 恢复和取消场景，旧失败报告保留。两条消息旅程曾观察到未发送测试草稿残留，未盲目重发；本阶段不宣称草稿隔离验收通过。

受控宿主不是实际大模型，模拟器不是物理手机。未覆盖完整弱网矩阵、长时间后台、厂商 Doze、队列、多任务取消或真实模型工具链中断；不据此扩大验收结论。

后续来源修复包的完整有界像素复测已经完成并通过，见 journey-25-provenance-fix.md。本文件保留前一阶段的原始交接状态，不能将其中“尚未验收”当作最后版本结果。最终汇总见仓库 docs/reviews/2026-10-31-android-emulator-communication-acceptance.zh-CN.md。
