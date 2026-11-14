# Remote 历史附件原件接线与验证边界

固定上游为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。
原文、MIT 许可证、逐文件 SHA-256 和 Git blob 登记在
[`media-upstream/source.json`](../../tools/remote/fixtures/media-upstream/source.json)。

## 真实接口

- Harness 工作区文件：`harness.remote.call` 的 `workspaceFiles/stat`、`workspaceFiles/readBytes`，参数为 `payload.args.workspaceFileScopeId`、`path` 与 `range`。
- Codex 工作区文件使用同一公开文件面，scope 为 `codex:<threadId>`；真实 Host 实现为 `CodexWorkspaceBridge`，不虚构 `codex.app` 文件方法。
- ACP 工作区文件：`agent.acp.call`，`method=dsh/toolCall`，携带原生 `sessionId`、`backend`、`endpoint`、`args`。公开文件 scope 为 `<backend>:<sessionId>`，Antigravity 使用 `acp:`。
- Harness 历史图片目录：`session/attachment`，`payload.args.request` 保留会话、附件 ID 和历史完整附件引用，见固定 `harness-alpha-client.ts` 第 881–899 行。
- Codex 历史图片目录：`codex.app.call` 的 `dsh/sessionAttachment`，参数为 `threadId`、`attachmentId`；大响应走现有官方 transfer。固定 `domain.ts` 第 273–281、1265–1290 行只回取投影中的图片引用，`codex-image-codec.ts` 第 4、61–82 行给出真实图片身份与 MIME/长度。
- ACP 已有消息内图片数据和受限工作区文件支持；没有确认到独立图片目录回取方法时明确不支持。

## 本次行为

`RemoteAuthenticatedMediaSource` 只登记认证仓储收到的历史附件和上传 commit 回执。
编码后的界面 ID 不授权任意路径；绝对路径只能归一到已选工作区内部，外链不会变成携带 Remote 凭据的 HTTP 请求。
每次读取检查账户代数、完整认证 peer、连接、Host、后端、工作区和会话归属。
文件逐块核对路径、版本、总长度、偏移、规范 Base64 和结束标志，最后重新 stat。
内联及目录图片核对声明 MIME/长度/摘要；无上游摘要的文件由加密缓存完整读取后计算真实 SHA-256。

缓存保留原有加密存储、删除修订屏障、离线原件和显式导出流程，移除旧 Gateway 的 HTTP metadata、grant 与 content 分支。
端点退休使旧缓存对象和旧导出流失效，重建的离线缓存仍可读取已保留原件。
本机已有加密文件格式恢复逻辑暂保留；未增加旧 Core 账号或密钥迁移。

## 可复现验证

```sh
node --experimental-vm-modules --test tools/remote/media-source.test.mjs
```

探针运行 Node 24，复用 `integrations/oai-android-mcp` 锁定的真实 zod 依赖。
它执行原始 Codex 工作区实现和真实文件 I/O、ACP 客户端工具到 Host 包装器与文件实现、Harness 公开客户端附件方法以及 Codex 图片编码器。
仅替换未使用的终端依赖、网络边界和 Session cwd 提供者；不证明真实 Host、加密网络载体或设备验收。

客户端回归为 `RemoteAuthenticatedMediaSourceTest`；缓存接线和真实 ContentResolver 导出回归为
`RemoteHistoryMediaCacheTest`，包括同 ID 修订更新、旧读流和导出提交失效、重启拒绝低序号历史、永久删除及提交前归档重绑，另保留 `EncryptedHistoryMediaCacheTest`、`PersistentHistoryMediaTest`、
`RevisionedMediaRecoveryTest`、`AndroidAttachmentDownloadsTest` 与 `SubmittedOriginalRecoveryTest`。
运行结果以当前提交的实际测试记录为准，不能把这些文件存在当通过。

## 待独立评审与整合

- 内容身份是认证会话和原始附件引用的规范摘要，不携带序号；最高确认修订与内容身份在同一原子加密记录中分别保存，相同内容推进水位不清原件。
- Harness Session V3 的同会话 durable seq 使用独立修订域；Codex、ACP 历史 seq 为重投影位置，不进行跨连接持久比较。
- 权威历史读取使用本连接的请求代数；只有该附件自读取开始后未被更新才接受完成结果，后发快照与实时内容阻止迟到历史回滚。冲突可通过新的权威重读恢复。
- 来源版本、水位及来源原件代数绑定在单条加密原子文档中，提交完成后再清旧字节；重启后同 Harness 修订域的较低 seq 被拒绝，已永久删除的原件不会因新引用复活。
- 每次原件读取及导出最终提交都与来源更新共用围栏，防止旧内容跨越修订后发布；新增 source/cache 层回归尚以实际运行结果为准。
- Android 编译需要与旧 Runtime 退役切片整合，旧专属 HTTP 测试由对应切片退役，不能加空实现兼容。
- 新探针、固定源码与 App 回归类须纳入最终智能测试分流及 CI 清单。
- 没有启动模拟器、真机或登录，没有真实 Host/模型、跨网络或设备验收结论。

## 第二轮独立评审修复

每次已接受的观察（包括内容相同的后发历史与实时确认）都推进连接内观察围栏，
但相同内容不改变原件身份、代数或字节。两个后端逆序回归覆盖后发历史/实时确认 A 后，先发旧历史 B 不得回滚。

来源状态使用 `source-state` 单条加密原子记录同时写内容身份、修订域、最高水位与来源代数。
原件读取代数包含该来源代数；在原子提交后、旧字节清理前中断时，旧元数据与新来源不匹配，
重开会拒绝读取旧原件，并允许同一合法新水位恢复下载。故障注入回归模拟此窗口，随后确认永久删除仍优先。
原来的分散本机记录只用于兼容读取，不继续分步写入。
