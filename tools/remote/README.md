# 固定 Remote 公开目标绑定探针

本目录提供 P0 的隔离执行证据，不连接真实 Remote 账号、Host、手机或后端。

```sh
node --experimental-vm-modules --test tools/remote/target-binding.test.mjs
```

使用仓库规定的 Node 24.18.0。探针以 Node 自带 TypeScript 类型擦除与 VM 模块执行
`fixtures/dsh-remote` 中未修改的固定上游源码；不重写目标选择、公开状态和分发算法。
只为网络、后端投影和未使用的外部依赖提供测试替身，并且通过公开的
`ctx.dshRemoteClient`/`ctx.typertGateway` 操作，不读取或修改运行时私有字段。

`source.json` 记录固定仓库/commit/路径/SHA-256，所有来源文件运行前核对摘要。
`LICENSE` 保留上游 MIT 文本及 `Copyright (c) 2026 DSH Remote contributors`；
上游源码中的原始注释与版权原样保留。来源为
`liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。

## 已证明与限制

- `invoke/stream` 同步捕获当前分发目标；派发后改选不将该次请求重定向到新目标。
- 只在开始前查询 status，随后存在异步间隙，会将相同 Session ID 的写入发给改选后的 Host。
  测试明确复现这一反例，不把“测试通过”解释为该用法安全。
- 状态最终检查与 invoke 之间没有异步间隙，可以保护该同步派发点的 Host 绑定；
  后验结果检查只能拒收失效观察，不能证明没有执行副作用。
- 同 Host、同 backend 换 workspace 并消费临时选择后，公开 status 可能完全一致。
  仅靠 status 不能证明 workspace 代际，生产 Bridge 必须拒绝缺少该证明的写入。
- 旧投影关闭只在测试替身边界验证；真实投影的缓冲事件排空、关闭时点、原生 Session ID
  还原、公开流所有者绑定仍待专门互操作证据。不能把旧流关闭通知的后验检查当作派发前原子租约。

当前 P0 的跨后端目标归属门禁保持未通过；原生原子 expectedTarget/租约接口并不存在。
本探针不访问 private hooks，也不据此扩写上游支持范围。真实 DSH 加载与各后端行为单列未验证。
