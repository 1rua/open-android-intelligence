# 固定 ACP 投影关闭风险观察

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
