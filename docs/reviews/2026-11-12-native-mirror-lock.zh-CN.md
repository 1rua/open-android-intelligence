# 原生镜像同数据库锁竞争修复证据

关联 #142 / PR #175。固定原基线 `83fbef075a494f0d4899cc22c08b4864c9edb3cd`；原实现提交 `b58ae704`，测试/清单补充 `7991ba88`；随后在 PR #176 合并基线 `a400ee88` 上原样重放三个提交，range-diff 全为 `=`。

## 已确认现场与实现范围

原隔离 Android 用户10的同一合成会话在 Core 重启后，两输入和回复各1、身份及历史保留，但全过程出现 checkpoint IO 失败后恢复。原已验 APK 的只读 JDI 捕获到 `SqliteConversationRecords.transaction:116` 的 `IOException → SQLiteDatabaseLockedException`；没有读取异常正文、账号凭据或消息内容。工作台和仓储各自持有镜像句柄并指向同一数据库，原实例 monitor 无法协调它们。

修复按实际 canonical 数据库路径共享进程内 monitor，覆盖首次打开、迁移、BEGIN 到 END、flush、close 和 wipe。实例强持锁、注册表仅弱持锁；不同数据库不互相串行。不改加密、身份或队列语义，不吞错，不添加无条件重试。**跨 Android 进程不共享 Java monitor，本 PR 不宣称跨进程锁闭合。**

## 分层证据

- `original-baseline-lock-red.xml`：早期基线单方法红测，1测试/1失败，失败包含 `IOException → SQLiteDatabaseLockedException`。
- `baseline-raw-sqlite-lock.xml` 与 `RawSqliteLockRedTest.kt`：固定原基线两个真实 SQLite helper 句柄，显式持写事务、竞争句柄 `busy_timeout=50ms`，独立探针1通过并捕获预期 SQLite 锁异常。该 probe 直接验证 SQLite 争锁，不通过镜像共享 Java 锁。
- `green-native.xml` 与 `green-scope.xml`：当前相关 Native19、scope6，全为0失败/错误/跳过。scope中的等待窗口证明同库 Java 入口串行和最终真实持久事实；不能单独把该绿测试与独立 raw probe 混写成同一个用例的完整 SQL 锁红绿。
- 最初相关六类 53/0/0 是独立本地轮次。远端首轮随后暴露回归清单漏登记及既有 checkpoint 测试自动恢复先于断言；两项失败保留，补充清单和恢复门禁后规划测试78通过，Native19/scope6重新通过。
- 首次完整类基线复跑有4项未抵达首事务闩锁，不计为目标 SQLite 异常红证据；首次 Robolectric 依赖429与一次离线jar引用错误均是环境失败，不计为业务红。

`summary.json` 固定各层数量。局部与门禁通过不等于原模拟器全过程完成；必须同签名保留数据覆盖后继续 TCP、Core/DSH 重启及最终 Full。此时 #142 与 DSH 总跟踪仍不应关闭。
