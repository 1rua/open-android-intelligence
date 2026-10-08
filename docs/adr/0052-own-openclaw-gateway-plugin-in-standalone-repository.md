---
status: accepted
date: 2026-10-08
---

# 将 OpenClaw Gateway 插件源码维护在独立仓库

## 决策

OpenClaw Gateway 插件的唯一源码仓为 [`1rua/openclaw-gateway-plugin`](https://github.com/1rua/openclaw-gateway-plugin)。应用主仓不保留第二份插件实现；主仓通过 `openclaw-plugin-pin.json` 固定插件版本、标签和完整提交 SHA，并在 CI 中检出该提交。

Gateway Protocol 的唯一契约源仍是应用仓库的 `gateway-contract/`。插件仓通过 `contract-pin.json` 固定应用仓完整提交，保存由该提交生成的契约快照，并在 CI 中逐文件对照 pin。OpenClaw 的 Git 安装需要可执行 JavaScript，因此插件仓同时保存由 TypeScript 源码生成的 `runtime/`；CI 重建它并检查生成结果与源码一致。Gateway 启动和插件加载不下载契约。

应用主仓 CI 使用锁定的插件运行产物执行 OpenClaw 向量 runner，并将其与 Hermes Python runner 的结果逐项比较。OpenClaw 插件仓 CI 独立执行类型检查、插件单测、契约快照检查、构建和固定宿主版本的原生安装与加载检查。

## 版本与发布

- 插件包版本、Gateway Protocol 版本和 OpenClaw host API 版本分别维护。首个插件包标签为 `v1.0.0`，协议保持 `2.1.0`，当前测试的 host API 范围保持 `2026.7.1`。
- 核心 Gateway Schema 或一致性输入变化时，先更新插件仓的 `contract-pin.json` 与生成快照，再更新主仓的插件提交 pin；涉及核心 Schema 摘要变化时，Android、Hermes 和 OpenClaw 必须同步升级。
- OpenClaw 宿主发行版与本项目插件仓是两个独立依赖，分别记录在 MVP dependency lock 中。

## 后果

两仓不再共享原子提交。主仓通过不可变插件 SHA、契约内容比较、构建产物重建检查和跨宿主一致性向量守住兼容边界。插件源码路径、测试入口和安装方法以独立仓 README 为准；历史审查记录保留原路径以维持历史证据的准确性。
