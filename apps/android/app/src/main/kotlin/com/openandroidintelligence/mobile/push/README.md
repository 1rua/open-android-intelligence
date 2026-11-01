# 安装实例主动推送接口

此目录拥有独立的短信/通知推送策略、加密队列、逐端消费确认，以及持久化的远端撤回工作。生产 Application、连接、JobScheduler 和系统事件装配由应用 composition 持有。

## 可直接接线的入口

```kotlin
runtime.onWorkAvailable = coordinator::kick
runtime.onRevocationAvailable = {
    scope.launch { coordinator.runCycle() }
    coordinator.kick()
}
```

一个有合法登录连接的有界任务应当先取回撤回工作，再采集和投递：

```kotlin
for (batch in runtime.pendingRevocations()) {
    transport.revoke(batch)
    runtime.acknowledgeRevocation(batch)
}
```

异常时保留批次，由系统任务重试；不得在 `finally` 中确认，不得因没有待发正文就提前退出。全部退出时不发网络请求，保留清理元数据和有效正文。重新登录后核验合法连接，再清理、采集、投递。

`PushRevocation(endpoint, eventIds)` 每批最多 128 个事件 ID，不能为空。完整端点包括 `coreInstanceId/accountScope/endpointId/generation`。`GatewayPushTransport.revoke(batch)` 总是发送 `{route,eventIds}`，不会省略事件范围而撤销整条路由。

## 持久化与确认

- `configure()` 同一次 AES-GCM 文件提交中删除不再允许的正文、记录对应端点和事件 ID；提交成功后才取消真实发送 Job，锁外通知 `onRevocationAvailable`。
- 撤回元数据不含短信、通知正文，不因退出、队列为空或进程重启丢弃。
- `pendingRevocations()` 返回独立的有界批次；接收批次之后新增的撤回 ID 不会被旧批次确认清除。
- `acknowledgeRevocation(batch)` 只删除该稳定端点、该批事件 ID，重复确认返回 `false`。
- 新事件入队预留收据及撤回元数据空间。清理元数据有独立硬上限，不依赖用户设置的正文容量。
- 新格式为 v4，读取 v1/v2/v3；迁移保留原事件、正文、稳定 ID、逐端收据、短信游标、撤回账本和原过期时间。v1/v2 没有撤回账本，读为清理队列为空，不重置待发送队列；旧明确关闭的 allSenders 空范围仍不放行。未来未知版本拒绝读取，不能用默认配置替换。
- 本地损坏、写失败和并发写入冲突停止出站，不生成替换密钥解读已有数据。

## 范围选择与实际过滤

通知复用原已安装应用选择器，支持搜索、正选、反选、草稿取消与保存，不设256个或其他自行指定的应用数量上限，空应用集合表示不推送任何通知；不可见或卸载的已选项保留至用户主动移除。选择器不修改被动通知策略。

短信范围为 `ALL`、`KEYWORDS` 或 `PHONE_NUMBERS`，新配置默认为全部，主动推送总开关仍默认关闭。关键词按正文做不区分大小写的字面子串匹配，多个条件取任一匹配；电话号码使用精确匹配以保持历史限制，关键词或号码模式条件为空时不放行。范围和正文开关独立：关键词只在本机判定，正文关闭时交付负载不包含正文。

采集入口、入队与每次交付/重投均复核当前范围。加密队列仅为关键词匹配保存本机证明，不将该证明发给 Core；范围变化后无法证明符合新条件的旧事件须撤回，不能推断允许。未匹配行可推进扫描游标，防止范围之外的短信卡住后续合法事件。

`configure(configuration, expectedConfiguration)` 在同一把锁下校验编辑时配置与当前配置一致；冲突不覆盖新配置，界面保留草稿并显示重载提示。账号登录、系统权限、端点白名单、正文许可和被动查询策略各自独立。

## 端点可用性与用户选择

认证发现只提供当前可用端点，不是用户授权账本：

- 活动列表缺席、租约到期或 Core 断线均暂停投递，保留原白名单和未确认队列。
- 只有完整 Core/账号/endpointId 一致且发现更高有效代次时，才认为原路由已被替换；仍不自动移除选择或清队列。
- 同一代次恢复后，重新核验端点并继续原收据或同 ID 投递。
- 新代次必须由用户显式选择；已有旧代次事件不自动改投新代次，同名接收端也不能代替旧身份。
- 用户本地撤销/移除、消费 ACK 及既定保留策略仍按各自规则清理；远端发现失败不调用 `configure()`。
- 当前 Core 过期后重新注册会推进代次。这种恢复需要手动重选，不承诺旧代次自动恢复。

对应 `PushEndpointAvailabilityTest` 通过实际 GatewayPushTransport 的认证发现响应解析验证，不把验证接口固定为 VALID。测试用例不等于真机或生产 Core 的完整链路验收。

## 通知登录门槛

生产 composition 必须显式安装 `NotificationCollectionAccess.install(isLoggedIn)`。该对象默认拒绝；回调应实时读取本机是否还有合法登录、通知能力是否启用，以及是否处于紧急停止状态。它不代替被动查询的数据范围，也不授予主动推送许可。

- 被动 `NotificationListenerSession.posted()` 接收延迟提取函数，登录门槛不成立时不会读取标题或正文。
- `onListenerConnected` 和 `captureActive()` 在系统快照读取前检查；被动快照逐条提取正文后再次检查，交付前再检查。
- 退出或门槛查询失败时，被动内存快照清空；已经进入安装实例加密队列的有效推送数据保留。
- 主动推送继续使用独立的 observer/runtime 范围和登录核验，不取决于被动 MANUAL 批准。
- 使用普通 Application 的通知 HTTP 测试也要显式安装测试登录回调，并在结束时恢复默认拒绝。

不启动 Gradle验证当前通知 service/session/source 编译与纯 JVM 行为：

```sh
python3 apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/run_push_unit_tests.py --notification-sources
```

证据写入 `apps/android/notification-collector/build/source-standalone-verification/`，不写 Gradle 报告。测试覆盖退出后不执行 raw factory、正文提取/系统快照期间退出不交付、登录恢复、门槛异常及主动/被动策略独立；不执行系统服务生命周期或 Manifest 验收。

## 验证与边界

不启动 Gradle、只复用已有工具链及编译好的支持模块，可从仓库根运行：

```sh
python3 apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/run_push_unit_tests.py
```

该脚本重新编译当前 runtime/codec/transport 和 JVM 测试，输出到 `apps/android/app/build/push-standalone-verification/`，不修改 Gradle 测试报告。它不执行 Compose、Android 系统事件源或真机测试。

真实 Core HTTP 撤回顺序测试：

```sh
PYTHONPATH=oai-core/src ../../.toolchains/approval-python/bin/python -m pytest -q \
  -c oai-core/pyproject.toml \
  apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/test_core_push_revocation.py
```

此命令复用仓库现有的真实 Core、签名客户端、隔离账号及接收端测试基础设施，不使用设备个人数据，也不能替代 Android 到 Core 的整机验收。Python 路径是当前工作树相对既有工具链的位置。

撤回协议还必须阻止“撤回先到、原投递后到”重新暂存正文：Core 需为明确撤回的事件 ID 留有界撤销标记，并在实际提交前校验。该条件由独立顺序测试验证；是否通过必须看最新测试输出，不能仅凭 HTTP 撤回返回成功宣称完成。

系统时钟回拨和进程重启不会直接清空未确认队列，也不会重写原事件的到期时间。
运行期按单调时间与持久观察锚维护过期，重投保留原事件与内容摘要；实际长期断电与跨重启时钟漂移仍受Android可观察时钟限制。
