# OpenClaw 固定依赖证据续期审计（2026-11-09）

## 范围与结论

本次只复核 `docs/mvp/mvp-dependency-lock.md` 中的 `MVP-DEP-OPENCLAW` 行，基线为 `71e7d6cd2c72ac8f839692537418e23d4f2229f1`。未修改通信代码、锁校验器、CI、设备调用或其他依赖行。

续期结论：固定版本 `openclaw@2026.7.1-2` 的 npm 元数据、tarball、许可证、上游 tag 指向的 commit，以及原 `verify_command` 均已实际复核通过，可以按一个月周期续期。

审计时点为 `2026-11-08T16:20:39Z`，对应 `Asia/Shanghai` 的 `2026-11-09 00:20:39`。因此锁行使用 UTC：

- `reviewer_time`: `2026-11-08T16:20:39Z`
- `evidence_expires_at`: `2026-12-08T16:20:39Z`

## 原锁行与失败边界

原 row 固定：

- 版本：`openclaw@2026.7.1-2`
- tag：`v2026.7.1-2`
- commit：`0790d9f593ad30c940ed93b5872a8cf6d6f3cf8c`
- npm integrity：`sha512-ycF3yPcbjN6bUPeaUx6Mh6vze1hQWoD3CT/wWcmD7a8xaHHHRUaAlaq+lFxMHf1ssEgODVAwjlzYqp2twkYZ7g==`
- 许可证复核：`MIT; reviewed`
- 原复核时间：`2026-10-08T15:18:43Z`
- 原到期时间：`2026-11-08T15:18:43Z`
- verify command：`npm exec --yes --package=openclaw@2026.7.1-2 -- openclaw --version`

修改前的真实锁检查失败为：

```text
MVP dependency lock: FAIL (8 rows)
- MVP-DEP-OPENCLAW evidence is expired
```

这不是依赖内容变化，也不是通过调整校验时钟得到的结果。

## npm 官方元数据

使用固定 Node `v24.18.0`、npm `11.16.0`，从 npm 官方注册表重新查询：

```text
https://registry.npmjs.org/openclaw/2026.7.1-2
```

实测字段：

| 字段 | 结果 |
| --- | --- |
| `name` | `openclaw` |
| `version` | `2026.7.1-2` |
| `license` | `MIT` |
| `dist.integrity` | 与上方原 row 的 `sha512` 完全一致 |
| `dist.shasum` | `4583b987ea7277230ce1c7b2b8535d3e219f57ac` |
| `dist.tarball` | `https://registry.npmjs.org/openclaw/-/openclaw-2026.7.1-2.tgz` |
| `dist.unpackedSize` | `87477902` 字节 |
| `dist.fileCount` | `8550` |
| 运行依赖数量 | `56` |

npm 版本对象中没有 `gitHead` 字段；`npm view openclaw@2026.7.1-2 gitHead --json` 退出码为 0，但返回为空。本审计没有把该字段伪造成“已验证”。

## tarball 与许可证

从 npm 元数据给出的官方地址下载并保留 tarball：

- 文件：`logs/openclaw-2026.7.1-2-20261109/openclaw-2026.7.1-2.tgz`
- 大小：`19728152` 字节
- HTTP 响应头 `content-length`：`19728152`
- `sha512`（base64）：`ycF3yPcbjN6bUPeaUx6Mh6vze1hQWoD3CT/wWcmD7a8xaHHHRUaAlaq+lFxMHf1ssEgODVAwjlzYqp2twkYZ7g==`
- `sha256`：`5bb525f36f471a41239615d321c441778c7e1c007018ed6d84b795be77803276`
- `sha1`：`4583b987ea7277230ce1c7b2b8535d3e219f57ac`，与 npm `dist.shasum` 一致

tarball 内 `package/package.json`：

- `name`: `openclaw`
- `version`: `2026.7.1-2`
- `license`: `MIT`
- `package.json` SHA-256：`695b6ee36df7fc69606dc390cf97bb2ca809114337b18c573707637cd2a4e3db`

tarball 内 `package/LICENSE`：

- 文本开头为 `MIT License`
- 版权行 `Copyright (c) 2026 OpenClaw Foundation`
- LICENSE SHA-256：`73571b25326281d369087f469842c02444fe39faaecebda4d82ed21ff3a1c29d`

`npm exec` 实际安装后的 `package.json`、`LICENSE` 与上述 tarball 内容分别逐字节一致。

## 上游 tag 与 commit

直接查询官方 GitHub 仓库：

```text
https://github.com/openclaw/openclaw
```

`git ls-remote` 结果：

- `refs/tags/v2026.7.1-2`: `be8b8a9e8838f832e4fa47cde8bea0a33aec71ba`，为 annotated tag 对象
- `refs/tags/v2026.7.1-2^{}`: `0790d9f593ad30c940ed93b5872a8cf6d6f3cf8c`

GitHub tag API 显示该 tag 对象类型为 `commit`，目标 SHA 为原 row 的 commit，tag 签名验证结果为 `verified=true`、`reason=valid`。GitHub commit API 也返回原 commit，且 commit 签名验证有效。GitHub release API 的 tag 名和发布名称分别为 `v2026.7.1-2` 和 `openclaw 2026.7.1-2`。

补充观测：

- 上游 commit 的 `package.json` 版本为 `2026.7.1`。
- npm 发布包的版本为 `2026.7.1-2`。
- 两者的 `package.json` 仅差发布版本字段和 `@openclaw/ai` 的 `workspace:*` 发布改写；其余字段一致。
- 上游 commit 的 `LICENSE` 与 npm tarball 的 `LICENSE` 逐字节一致。

上述是事实记录。npm 没有公开 `gitHead`，因此不能声称 npm 元数据自身携带了 commit 绑定；本审计只确认了“签名 tag 指向原 row commit”和“已核验 npm 包/许可证与上游对应文件的关系”。

## 固定验证命令

实际执行：

```text
npm exec --yes --package=openclaw@2026.7.1-2 -- openclaw --version
```

环境为固定 Node `v24.18.0`、npm `11.16.0`。首次解析包声明的 56 个直接运行依赖时使用了后台 PTY，并复用现有 npm 缓存及 `prefer-offline`；首次完成时间约 2 分 6 秒。缓存建立后再次执行同一命令约 0.25 秒。

原始成功输出：

```text
OpenClaw 2026.7.1-2 (0790d9f)
exit_code=0
wrapper_exit=0
```

该结果只证明固定版本的 CLI 可以解析并报告版本，不代表完整依赖树安全审计、健康检查或设备端功能验收。

## 锁行完整性与测试

使用 `tools/check-dependency-lock.ts` 导出的 `canonicalEvidence` 和 `sha256Evidence` 重新计算：

- 原 row 的 canonical hash：`sha256:c11c0870242ab9d8c733e8642e0b7452e9b1edb181c3b0390938d7680c48f745`
- 新 row 的 canonical hash：`sha256:813f9cff7871272a77000abf03f343853d7500fdba2805ce600f74b3ac67d32f`
- canonical evidence 长度：`575` 字符

只改了 `reviewer_time`、`evidence_expires_at`、`integrity`。`official_reference`、`immutable_version`、`license_review`、`verify_command`、`status`、`blocks` 均保持原值。

验证结果：

```text
$ npm run mvp:lock:check
MVP dependency lock: PASS (8 rows)

$ ../../node_modules/.bin/vitest run tools/check-dependency-lock.test.ts
Test Files  1 passed (1)
Tests       9 passed (9)
```

`git diff --check` 也通过。

## 保留的原始证据

原始 npm 产物、元数据、GitHub API 响应、校验输出和测试日志保存在已被 `.gitignore` 忽略的目录：

```text
logs/openclaw-2026.7.1-2-20261109/
```

主要文件：

- `registry-openclaw-2026.7.1-2.json`：npm 官方版本元数据
- `openclaw-2026.7.1-2.tgz`：官方 npm tarball
- `tarball-headers.txt`、`tarball-hashes.txt`：tarball 响应头与哈希
- `package.json`、`LICENSE`：从已核验 tarball 提取的文件
- `git-ls-remote-tag.txt`、`github-tag-object.json`、`github-commit.json`、`github-release.json`：上游 tag/commit/发布记录
- `upstream-commit-package.json`、`upstream-commit-LICENSE`：上游 commit 对应文件
- `verify-command-success.log`、`npm-logs/`：固定命令的实际输出与 npm 调试日志
- `verification-assertions.log`：逐项断言结果
- `canonical-before.json`、`canonical-after.json`：锁行 canonical evidence
- `lock-check-before.log`、`lock-check-after.log`、`lock-tests.log`：修改前后锁检查与测试日志

其中 `verify-command.log` 和 `verify-command.pid` 是一次被命令容器回收的脱离终端尝试，未进入安装完成阶段，不用于成功证据；保留它们是为了审计完整的操作轨迹。

## 限制与未覆盖项

- 本轮没有修改、重算或复核其他七条依赖行。
- 本轮没有运行 Fast/PR 全流程；按要求等待主流程变基后再重跑。
- 本轮没有调用 CI、设备、远端服务或推送。
- 本轮没有对 `openclaw` 的完整传递依赖树做安全审计，也没有把 `npm exec --version` 扩大解释为运行时就绪证明。
- npm 官方版本对象没有 `gitHead` 字段；不能补造或声称该字段与 commit 相等。
