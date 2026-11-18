# 固定 Remote 原文回归

项目根目录执行 `npm run test:remote`，覆盖 ACP 投影三项观察、媒体原文与真实文件系统四项回归，
以及原工具互通的准备顺序与失败传播。需要 Node 24.18.0 和已安装的 `integrations/oai-android-mcp` 依赖。
媒体覆盖 Codex 工作区 stat/分块、路径与符号链接隔离、ACP 原客户端 scope、Harness attachment 和 Codex 图片身份；
不连接真实后端。`fixtures/media-upstream/source.json` 登记全部原文与原始许可，来源门禁逐字节核对 SHA-256、
Git blob 与目录清单；固定原文不参与本项目 TypeScript 编译，本仓库 TypeScript 准备器与门禁仍参与类型检查，
JavaScript 探针则由实际 Node 测试执行。

手机原生工具至 Kotlin 门面与控制总线使用 `npm run test:phone-mcp:interop`，见
[MCP 说明](../../integrations/oai-android-mcp/README.md)。PR/Full 运行该互通；Fast 只运行 Node 单元回归及本目录原文测试。

本目录保留固定上游 ACP 投影的三项隔离观察，不连接真实账号、Host、手机或后端。
它不是本客户端发布阻塞，也不证明新客户端的手动 workspace/对话切换已经通过验收。
后台跨 workspace Agent 委派已从本客户端范围取消；原目标绑定探针及其专属快照已安全移入忽略回收区。

```sh
node --experimental-vm-modules --test tools/remote/projection-scope.test.mjs
```

使用 Node 24.18.0，通过 Node 类型擦除与 VM 模块执行 `fixtures/dsh-remote/acp` 的固定上游原文，
只替换后端连接、会话内容和标题展示。`source.json` 记录来源、固定提交与 SHA-256，运行前逐份核对。
来源为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`；
`LICENSE` 保留原 MIT 文本。固定原文中的英文注释保留，不作为本仓库新增生产代码。

观察事实为：原始 ACP prompt 不用旧 workspaceId 阻止同名 Session 命中当前 workspace；
workspace follow 关闭后仍可能读到关闭前缓冲的 baseline/upsert。客户端手动切换应据此使用独立连接、
连接代际与旧流取消排空，并由实际客户端测试证明迟到事件不会更新新目标。
本目录的三项只维护这些上游事实，不扩展为后台委派接口、target lease 要求或额外发布门禁。
