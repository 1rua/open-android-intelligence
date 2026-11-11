# 备份合并时长摘要 Lint 修复计划

关联：Issue #159。基线：`26c0e5c92745e3374b88c293d27677f200efff0f`（`dev`）。

## 问题与红灯证据

`ci.yml` 运行 `43077533569` 的 Android 作业 `130721202177` 在
`:app:lintFullDebug` 失败，唯一错误为 `StringFormatMatches`：
`SettingsBackupPresentation.kt:11` 将 `delayMillis / 1000.0` 的 `Double`
传给 `strings_settings.xml:78` 的 `%1$s`。当前基线仍保留同样的调用与资源。
该 CI 已提供真实的原规则红灯，本轮不重复运行修复前全量构建。

## 变更范围

1. 在摘要展示边界将原除法结果显式转换为字符串，匹配现有 `%1$s`。
2. 保留资源文案、零值立即发送分支、缺省保持不变分支、小数显示与当前语言行为。
3. 新增聚焦摘要测试，覆盖缺省、零、整数秒、小数秒、毫秒边界及不同 locale；
   同时与修复前 `%s` 接收 `Double` 的真实资源格式化结果比较。
4. 不更改备份文件格式、导入导出逻辑、Lint 规则、基线或发布门禁。

## 验收

- 聚焦摘要测试通过，证明显示文本与修复前一致。
- `npm run test:fast`、`npm run test:pr` 按实际改动范围通过。
- `:app:lintFullDebug` 真实执行通过，不使用规则抑制。
- 提交固定 SHA 与草稿 PR，交由独立审查；本分支不执行合并或部署。

## 工具与构建边界

复用主工作区现有 JDK 21、Gradle 缓存、Android SDK 与 Robolectric 依赖。
如 lint 依赖内置插件产物，仅在核对源码相同后使用现有已验证签名包；
工具配置、依赖缓存和构建产物均不提交。


## 固定候选与最终本地证据

代码候选 `8d3ccf77` 仅一行显式转换，资源未变；三组断言迁入既有 `SettingsBackupScreenTest`。最终正确类已实际执行9项（6既有+3新增），0失败/0跳过，2m37s含编译，测试执行5.63秒。早期独立PresentationTest的3项成功保留为不同测试类证据，不替代最终9项。

Fast通过；`lintFullDebug`真实5m16s成功，0错误/77既有警告，原StringFormatMatches已消失，未新增抑制。原本地PR尝试46922的句柄与实际进程均已终止但日志没有最终摘要，因此记为未取得成功证据；固定类聚焦已补跑，远端必要PR门禁另按当前SHA核对，不将中断伪称通过。

无开发上下文独立审查Standards0/Spec0，实际JDK21与原XML的150020组格式比较0差异；原字符串资源blob保持。参见[独立报告](../reviews/2026-11-12-backup-lint-artifacts/lint-fix-independent-review.md)与[结构化证据](../reviews/2026-11-12-backup-lint-artifacts/results.json)。完整ci.yml合并后验证，旧失败保留。
