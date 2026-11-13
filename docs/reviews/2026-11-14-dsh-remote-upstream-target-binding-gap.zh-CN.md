---
status: blocked
issue: 188
fixed_upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
observed_upstream_commit: 25a81da11b667be8f61a8d3f676615d7ff38fd18
observed_at: 2026-11-14T02:30:47+08:00
---

# 公开委派目标绑定的上游缺口

本记录补充[验收台账](2026-11-14-dsh-remote-acceptance-index.zh-CN.md)中 P0 跨后端委派阻塞。
只读核对固定上游与本次观察到的 main；不改变 pin、不维护分叉、不新建上游 issue，也不扩张成所有上游文件的安全审查。

## 有界刷新证据

本次观察 main 为 `25a81da11b667be8f61a8d3f676615d7ff38fd18`，对固定
`ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7` ahead 15、behind 0。
使用 GitHub `compare` 与 `contents?ref=<SHA>` 只读核对以下五个关键文件的 Git blob：

| 固定路径 | 两提交相同的 Git blob |
| --- | --- |
| `packages/plugin/src/client-runtime.ts` | `833deb01ff224c598dbbc8621419d63a1de25901` |
| `packages/plugin/src/typert-gateway-contract.ts` | `e5df106f0acc070e1230b65b595aa33766d43491` |
| `packages/plugin/src/typert-gateway-switch.ts` | `e7916f9f9baee0dc73780277013bf24042322d3a` |
| `packages/plugin/src/acp/virtual-harness.ts` | `8b58401103b391fc0c3bc9a413d205c3b1380ee0` |
| `packages/plugin/src/acp/virtual-live.ts` | `0a04b201913a891715fb6ca87dc294d226e15ff3` |

这些文件与固定版本逐字节相同，当前 main 没有通过这五个接口解除已复现的 workspace/session
归属与 pending-open 关闭缺口。该结论不保证未来版本不变，也不表示其余 15 个提交都无价值。
即使允许升级，当前观察到的 main 仍没有可据此放行的安全委派目标绑定；不能把升级许可当作问题已经修复。

## 最小公开接口需求

后续需要上游提供可公开依赖的不可变 target lease 或等价原子接口，至少满足：

1. 句柄绑定当前账号授权 epoch、Host、backend、workspace 与原生 Session/Thread；显示名、临时连接 ID 或自报 JSON 不取代这些身份。
2. `invoke/stream` 在派发前原子核对该句柄仍有效；检查与真正分发之间不得留下可切换到同名 Session 的异步窗口。
3. 用户改选 workspace、退出、切账号/origin、后端或 Host 实例替换时，旧句柄立即失效；不得隐式退回本机 Agent。
4. 在途 stream 的 owner、打开中和已打开状态都受同一 lifetime 管理。pending-open 在关闭后才返回时，也必须释放原连接资源并拒绝迟到事件。
5. 失效后未发送操作明确拒绝；已发送但结果未知仍保持未知，不自动改投、重放或抢回旧选择。
6. 持久委派回执保存完整目标与原生任务身份；查询/取消原委派只能命中原目标，不向桌面当前新工作区发送取消。

未取得这种公开能力或能证明等价安全性的接口前，ACP/Codex 跨 workspace 委派保持 `BLOCKED`。
可以继续独立实现类型、协议、原生手机控制和已证明安全的窄范围，但不能宣称全部后端委派或全量迁移完成。
