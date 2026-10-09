# App 连接故障：部署环境只读核查

核查日期：2026-10-09，时区 Asia/Shanghai。本报告只记录本轮实际取得的非秘密摘要，不包含口令、令牌、公钥、签名材料、完整响应正文或原始用户配置。

## 工作区初始状态

- 主仓库 `main...origin/main` 有既存未跟踪的插件拆分审查报告及 `docs/reviews/evidence/`，未修改这些内容。
- 插件源码仓库 `main...origin/main` 在本轮开始时干净。
- 只读核查阶段未执行重置、清理、删除、提交、推送、安装 APK、重启服务、创建账号或更改账号与会话。

## 当前部署事实

| 核查项 | 本轮实际结果 | 取得方式 |
|---|---|---|
| Hermes | `0.21.3`，显示 upstream `75b083e9`，Python `3.11.15` | `hermes --version` |
| 插件源码仓库提交 | `7fc91869e0494316b78ef254e2560ff4556fb658` | `git rev-parse HEAD` |
| 已安装插件提交 | 与源码仓库相同 | 在 `~/.hermes/plugins/open-android-intelligence-gateway` 执行 `git rev-parse HEAD` |
| 已安装 App | 包名 `com.openandroidintelligence.mobile`，版本 `2.1.0` | 对在线设备读取 `dumpsys package` |
| 无线 ADB | 用户最后提供的地址当前在线，无需重新配对 | `adb devices -l` |
| ADB 服务发现 | 当前命令版本不支持 `adb mdns services` | 命令返回明确的不支持提示 |
| Gateway 监听 | `0.0.0.0:11451` | `ss -ltnp 'sport = :11451'` |
| 回环地址健康检查 | HTTP `200`，正文等于 `ok` | 使用只读 HTTP GET 请求 `/health` |
| LAN 地址健康检查 | HTTP `200`，正文等于 `ok` | 使用只读 HTTP GET 请求 `/health` |
| 现有服务 | journal 确认 `hermes-gateway.service` | 只读读取服务 journal 元数据 |

## 旧平台会话加载证据

读取 `~/.hermes/logs/gateway.log` 后，按时间与告警类型统计，得到：

| Gateway 启动时间（Asia/Shanghai） | 无法读入的 routing 条目 | 无法读入的 legacy session 条目 | 明确错误 |
|---|---:|---:|---|
| 2026-10-09 14:12:54 | 33 | 33 | `'open_android' is not a valid Platform` |
| 2026-10-09 18:50:28 | 33 | 33 | 同上 |
| 2026-10-09 19:53:24 | 33 | 33 | 同上 |

`routing` 是宿主 `state.db` 的路由加载路径；`legacy session` 是 `sessions.json` 的兼容导入路径。同一历史路由可能在两种存储中都有副本，因此这里的两列不能相加后称为 66 条独立历史会话。

旧 `~/.hermes/sessions/sessions.json` 文件仍存在；其 37 个顶层条目中，33 个路由键含旧平台名 `open_android`。只输出数量，未输出路由键、会话 ID 或会话正文。日志证明宿主在这些启动过程跳过加载，不能据此宣称历史数据已被物理删除。

## 手机现有数据保留情况

- `run-as com.openandroidintelligence.mobile` 可用于本轮只读核查。
- App 私有目录中仍有设备凭据与刷新凭据文件；只检查文件存在，没有读取或输出密钥与令牌内容。
- 本轮未修改手机账号、配对、会话、偏好设置、凭据文件或日志。

## 证据限制

- 本轮读取限定 `OaiE2E` 标签及所有可读取 logcat 缓冲区，均未取得用户先前观察到的 `session.ready` 记录。全缓冲区中出现的同名文本是 ADB 查询本身的系统记录，不能算作 App 的会话证据。没有使用 `logcat -c`。
- 可读取的 AndroidRuntime 历史崩溃发生在此前时段，无法与当前失败尝试对应；没有取得本次认证后的异常栈。
- Gateway 的文件日志和现有服务 journal 未提供本次认证后的 `/events`、`/events/ws`、HTTP `401`/`403` 或 `SESSION_REJECTED` 等逐请求诊断信号。没有找到这些日志，不能据此认定事件请求未发生。
- `/health` 成功只证明监听与网络可达，不能证明认证后的本地初始化、请求签名或事件流成功。
- 本轮没有重新登录、读取账号数据库、重放带凭据的请求、安装诊断 APK 或重启 Gateway。因此，具体导致 UI 失败的初始化阶段或事件流异常仍需由针对性的回归测试和后续脱敏阶段日志验证。
