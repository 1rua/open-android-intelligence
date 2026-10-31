# 模拟器隔离后端

本目录文档说明 Android 模拟器验收使用的受控后端夹具。原始夹具、日志、截图和测量数据仅保留本地，按用户要求不随 PR 提交。App、Core HTTP 服务、Hermes Session Adapter、签名认证、原生 SessionDB、事件保存和回放均使用仓库实现；只有宿主执行被受控程序替代，没有运行模型、Shell 或设备工具。

Core 和 Adapter 运行在不同操作系统进程，各自使用工作区 `tmp/emulator-acceptance/` 下的独立数据目录。没有读取现网配置、账号数据或密钥，也没有修改现网进程和网络。夹具源文件的摘要与实际运行文件一致，见 `backend-environment.json`。测试 APK 的业务提交为 `dda2a4dc8ece02289b3e4f54dab11f8515249707`，随后为定位创建问题另构建了只增加诊断日志的 APK；两版结果分别记录，不能混用摘要。

## 启动方式

以下启动命令仅适用于保留该夹具的当前本地工作区；新克隆不会包含被忽略的夹具和原始数据。可公开复跑的生产方法回归位于 Android 单测与已有 Core/Hermes 隔离测试中。

在仓库根目录运行。Python 环境须包含 Core、Adapter 及测试依赖；`PYTHONPATH` 包含 `oai-core/src`、`integrations/hermes-session-adapter/src`、`oai-core/tests` 和明确固定的 Hermes SDK 目录。本轮固定 Hermes SDK 修订为 `75b083e9399115b12513385252811f09c3503c90`。

分别在后台启动以下两个进程，标准输出重定向到工作区忽略目录：

```bash
python docs/reviews/evidence/2026-10-30-communication-reliability/emulator/backend_fixture.py \
  --role adapter --root tmp/emulator-acceptance/adapter

python docs/reviews/evidence/2026-10-30-communication-reliability/emulator/backend_fixture.py \
  --role core --root tmp/emulator-acceptance/core
```

监听地址均为 `127.0.0.1`。Core 端口 18845，Adapter 端口 18846，宿主控制端口 18847；可用命令行参数调整。Android 模拟器通过主机别名 `10.0.2.2:18845` 访问隔离 Core。控制接口不是产品协议，只用于本机受控故障场景。

- `GET /status`：读取输入总数、实际执行次数、完成/活动数量及真实 generation/clientMessageId。
- `POST /hold-next`：让下一轮在首内容事件产生后等待显式释放。
- `POST /release`：空对象释放当前持有轮次；提供 `generationId` 时只释放指定轮次。

正常消息生成两条累计内容事件并提交 completed。等待由事件控制，没有用固定睡眠来模拟修复或放宽产品超时。新会话身份由受控宿主创建并写入自己的原生 SessionDB，再由 Adapter 返回；App 不生成替代服务端身份。

## 证据解释

`observations.jsonl` 只记录方法、路由、HTTP 状态、错误类型、单调时间和真实身份，不记录凭据、请求正文或原始事件游标。HTTP 处理器耗时包含流观察存续时间，不能解释成受理耗时或单向网络延迟。Core、Adapter 与 Android 的未经校准时间戳不直接相减。

开始 App 消息 journey 前曾校准夹具：去除了测试宿主事件中不符合生产契约的额外字段，并使用原生历史写入返回的递增内容修订号；随后签名创建、`generation → delta → delta → done`、内容修订 `[1,2]` 及原生历史读回通过。校准期的空会话和烟测输入不计入 App 的无重复发送断言；每个 App 输入按原始 clientMessageId 单独核对 Core POST、Adapter POST 和宿主开始次数。

模拟器结果只证明所记录 Android API、AVD、APK 与隔离配置下的行为，不等同于真实模型、生产代理、物理手机或厂商 Doze 验收。
