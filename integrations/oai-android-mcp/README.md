# 手机 OAI MCP

该插件只暴露 `android_*` 手机能力，经已安装 DSH Remote 的公开 `runSessionTool` 连接
`harness / phone-capabilities`。不会选择电脑工作区，不提供跨 Agent 委派或任意 RPC。
协议基准为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。

将该目录作为 DSH Cordis 插件安装，配置 `privateConfigPath` 指向用户私有的 MCP 配置文件。
插件通过 DSH 注入的 `dshRemoteClient`、`connection`、`webServer` 注册已有本机服务的
`/oai-android-mcp/v1` 路由；生成目录权限为 `0700`、配置权限为 `0600`。
把生成的 `mcpServers.oai-android` 放入外部 Agent 的原生 MCP 配置。Stdio 只访问该桥，
不用复制 Remote token 或私钥。注销、切服务及卸载后需要重新生成配置。

调用 `android_list_devices` 确定手机目标，首次读树或截图可省略 taskSessionId 并返回新任务；
后续必须传同一 taskSessionId，动作同时传 snapshotId。等待手机批准时返回
`awaiting_confirmation`，通过 `android_request_result` 继续读取当前任务正文，
通过 `android_request_status` 查询最小持久回执，或 `android_cancel_request` 精确取消原请求。
停止观察、Stdio 退出和本机 HTTP 超时均不代表手机请求已取消。

短信发送及 SoM 仅在手机真实提供者、授权和本机确认落实后可用；当前缺少的能力返回明确错误。
`npm test` 使用真实 MCP SDK Stdio 客户端检验列工具与调用；平台数据使用隔离替身，不能作为真机验收。
