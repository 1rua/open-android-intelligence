# 局域网连接与创建恢复修复记录

任务日期：2026-11-09，时区 Asia/Shanghai。固定起点为 `dev@f60f2af2ba0f353b9840814836b6431e08a3a86e`，关联 #104、#105、#106。

## 证据与修复范围

以 [USB 真机补充报告](2026-11-09-usb-phone-connection-creation-followup.zh-CN.md) 为现场依据，结合 [源码调查与受控反例](2026-11-09-lan-connection-session-creation-investigation.zh-CN.md) 及三个 issue 的完整附录实施修复。

| 问题 | 已确认事实 | 修复与正式回归目标 |
| --- | --- | --- |
| #105 | USB 三次点击均恢复同一旧创建身份，回执 404 后结束；本地另有未发送状态污染、初次导航竞争和反馈取消的失败反例 | 明确发送事实，保护不确定结果，修复导航与错误反馈 |
| #106 | 临时 Core 的真实 HTTP 实验确认，账号库写初始化争锁被转换成 401 | 认证读取不再重复写初始化；暂时存储失败准确分类且拒绝请求，解锁后原凭据恢复 |
| #104 | USB 有跨 WS/SSE 的 SocketException 和双 401；本地另有协商 500 恢复停滞的失败反例 | 分离 Agent 在线与传输同步状态，保留协商 HTTP 错误并恢复暂时故障 |

旧创建身份最初形成过程、USB 最初 TCP 断开方向、双 401 的现场内部原因及持锁事务尚未闭合。USB 窗口没有协商 500 证据，受控反例不能替代现场根因。

## 数据与授权边界

保留原账号、主密钥、设备绑定、会话身份和历史。确定未发送的请求可以首次发送；可能已经发送的请求只按原身份查询权威回执，404 不授权重复创建。旧持久状态缺少发送事实时采用保守恢复，不清除检查点绕过。

不改变 Android ↔ OAI Core ↔ Hermes Session Adapter 架构，不以重新登录、清数据或重建身份验收，不部署或注入实际服务故障。

## 执行与验收

三个子任务在独立 worktree 开发；创建相关文件由同一子任务维护，共享文件通过主工作区集成。先将 issue 探针变成正式红测，再逐项修复、执行定向回归，集成后执行 Fast 与 PR 门禁，并由无开发上下文的独立评审核对固定起点和 issue 覆盖。

开始时重新核对 ADB，没有已连接设备。用户随后明确本次暂不连接 USB 真机，验收待补。当前没有新增真机通过证据；后续设备验证必须注明实际 APK 提交、版本及签名来源，旧 APK 和旧日志不作为本次修复验收。

本记录将在集成后补充实际红绿结果、门禁、独立评审与剩余限制。

## 正式回归与当前结果

所有故障注入只使用合成账号、临时数据库、测试 HTTP 服务或可控传输，没有读取或修改实际账号数据库。

| 项目 | 修复前失败信号 | 修复后验证 |
| --- | --- | --- |
| #105 本机拒绝 | 离线首次 POST 为 0，在线后仍只查回执，不能首次创建 | 定向回归覆盖同身份首次发送及控制器/仓储重启 |
| #105 自动导航 | 初始列表在创建期间返回，成功回执不能导航新会话 | 覆盖两种返回顺序及真实用户导航优先 |
| #105 反馈显示 | 创建失败 300ms 后既无进度卡也无错误说明 | 使用真实 Compose 界面验证说明持续可见 |
| #104 列表刷新 | 200→500→200 导致在线 true→false→true，Native 同步始终 LIVE | 修复列表读取错误与 Agent 在线事实混合 |
| #104 协商续签 | Platform/Native 首次协商 500 后停止，普通恢复不再协商 | 保留结构化 HTTP 状态并覆盖恢复及安全阻断 |
| #104 独立 HTTP | 运行时一次 TCP 断响应覆盖 Hermes 权威在线事实 | 保留 HTTP 可达性独立状态并回归工作台投影 |
| #104 可选发现 | 模块搜索路径消失后列表 503、基本协商 500 | 真实 HTTP 与现有健康测试共 15 项通过，不伪造在线 |
| #106 普通认证 | 账户写锁期间有效签名只读请求实际 401，正式红测约 5.54 秒 | 去除重复写初始化后为 200，原凭据保留 |
| #106 必要迁移 | 迁移被临时写锁阻塞时被误报 401 | 受控 503，可重试，解锁后同请求恢复且再次重放仍拒绝 |
| #106 会话事实 | 只读事实层 SQLite BUSY 被误报撤销/401 | 暂时 BUSY/LOCKED 为 503，其他 SQLite 故障受控 500 |

#106 首轮新增测试 `oai-core/tests/test_authentication_storage.py` 12 项通过；迁移与设备保护组 67 项通过；真实 HTTP、WS、现代账号认证组 97 项通过。独立复核追加两条消息清理忙回归后，该文件共 14 项，追加验证结果见后文。命令使用已有 Python 测试环境，未安装新依赖：

```bash
PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src python3 -m pytest -q \
  oai-core/tests/test_authentication_storage.py \
  oai-core/tests/gateway_runtime/test_review_boundaries.py \
  oai-core/tests/test_android_device_authority.py \
  oai-core/tests/gateway_runtime/test_attachment_policy.py

PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src python3 -m pytest -q \
  oai-core/tests/test_core_transport.py \
  oai-core/tests/test_authentication_storage.py \
  oai-core/tests/test_core_ws_reliability.py \
  oai-core/tests/test_android_account_auth.py
```

安装器现有 `tests/test_install_source_recovery.py::test_temp_download_upgrade_defers_archive_and_reindexes_oai_core_path` 通过，证明该受控场景下当前安装器进程能重索引模块来源并延后归档；它不能证明现场 systemd 进程和持久 bootstrap 的加载来源已验证。本轮未部署和重启现场服务，该项保持待补。

## 持久状态兼容性

新创建尝试明确保存未发送事实，在真实连接边界之前先保存“可能已发送”；存储失败时不放行连接。旧加密检查点缺少该字段时默认可能已发送，只允许原身份回执恢复。没有新的权威证据时，现场旧未知身份仍不能仅因 404 自动解锁。这是数据事实限制，修复不能通过换 ID、清检查点或清历史解决。

账户库新增持久 schema 完成标记。新建、旧库升级、未完成加密清理及历史布局迁移仍执行写初始化，全部完成后才写回标记；未知未来版本拒绝。设备授权模型与 writer guards 继续独立检查，原主密钥引用与历史顺序保护不变。

## 待补的现场验证

- 本次未连接 USB：创建成功、失败恢复、响应丢失、会话切换、前后台及 WS/SSE 恢复均未新增真机验收。
- 本次未部署：持续服务实际加载来源、空闲到期续签和同局域网长时间恢复仍需在指定部署窗口验证。
- 旧创建身份最初形成过程、最初 TCP 断开方、现场双 401 的具体内部原因及当时持锁者仍未闭合。
- 旧临时日志原文件不可用；原报告中的哈希只说明当时记录，不表示本次持有原始证据。

## 独立安全复核修正

额外独立 Core 复核发现：`message_scrub_pending` 恢复清理遇活动 WAL 读快照时，`MESSAGE_MIGRATION_BUSY` 已是业务错误，原新增存储边界仍会让它落入验证器通用捕获并误报 401。已增加两条真实 HTTP 正式回归并精确修正 `account-open` 阶段；其他业务错误不泛化转换。

修复前正式红测为 401；修复后原评审脚本得到忙时 503、释放读者后同一签名 200、再次重放 401。忙时保留清理待完成标记且不写入 schema 完成标记。认证存储与明文迁移定向选择 15 项通过、9 项未选。

#105 两轮定向绿色结果分别为 139 项（Gateway 52、UI 61、App 26）与 65 项（Gateway 38、UI 16、App 11）。两轮存在重叠，不能相加。新增 11 项真实组合测试还覆盖已保存权威回执重建后不再 POST/GET，以及真实发现 `200(create=false)→500→200(create=true)` 与创建恢复的联合链路。原 `NativeCreationRecoveryTest` 7 项全部保留通过。

#104 最终定向回归 67 项通过：Gateway 42（Platform 事件恢复 25、协商 17）、App 25（发现组合 1、双通道恢复与安全拒绝 4、Native 可靠性 19、实际 Runtime TCP 断响应 1）。可重试协商 500 后两通道均从 RECONNECTING 恢复 LIVE，协商次数从 1 增至 2；Platform 观察任务保持有效。明确拒绝的认证码和协议不兼容码即使出现在 5xx 正文中仍阻断。`AUTHENTICATION_STORAGE_UNAVAILABLE` 503 不触发续签，暂时故障恢复 200 后继续 LIVE。

## 整合门禁与独立评审

规范轴与需求轴分别由没有开发上下文的独立 Agent 审查 `f60f2af2...fe33f9dd` 完整业务差异，均为 0 项剩余实质发现；静态评审没有代替自动化测试。App 回归清单新增三类，`tools/test_ci_planning.py` 42 项通过。

Fast 首轮实际结果为 1201 项通过、21 项失败、2 项跳过，在 Core 阶段停止，未执行后续阶段。19 项失败是既有共享回收目录权限 0755 被安全规则拒绝（要求 0700）；另两项 Hermes 安装器测试分别缺少 CLI 的 `rich` 依赖，以及文件系统创建 `lib64` 符号链接返回 ENOENT。没有将这些失败标为通过，也没有为通过修改共享目录、跳过测试或放宽规则。后续环境复核和 PR 门禁结果会在完成后追加。
