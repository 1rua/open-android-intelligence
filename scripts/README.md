# Core 安装与配置

`install.sh` 安装 Core 并执行初始配置；`setup.sh` 修改已安装部署的配置。共享保存逻辑在 `core_setup.py`。

```sh
./scripts/install.sh
./scripts/setup.sh
```

配置变更前请停止正在运行的 Core 及其他配置写入者。脚本不会自动停止、重启服务，也不会启动真实 Agent 来验证连接。多文件提交需要依次替换文件；已经运行的服务不会自动获得新配置。

## 保存与保留

保存前检查监听地址、端口、新账号密码、原文件可读性和原主密钥，并使用与真实启动相同的 `build_registry` 验证 Agent 类型、ID、URL、凭据及账号映射。此检查不探测远端是否在线，也不保证输入的主机名能在本机绑定。

保存包含 `core.json`、`start-core.sh`、适配器 Token 文件及已发现的 Hermes、DSH 环境文件。候选文件先准备完成，再检查备份 SHA-256，写入恢复 journal，逐个原子替换；文件内容和 POSIX 目录均执行 `fsync`。Windows 无目录 `fsync` 接口，其断电持久性不作同等保证。

- 原配置路径、主密钥、账号密码和历史保留；不会重置旧账号密码或生成替代主密钥。
- 新账号在隔离目录准备，提交时才移入目标位置。失败候选保留供复核，不永久删除。
- 接入选“否”或空选择表示本次不新增，已有 Agent 仍保留。菜单显示“保留现有”；移除仍须管理员显式编辑 `core.json` 的 `agents` 数组，没有新增移除操作。
- 保存沿用菜单输入的 Hermes 账号；重复配置按最终 Core 映射同步宿主，保留多个账号及自定义宿主根目录。
- 原环境或凭据文件不可读时拒绝保存，不以空内容覆盖。

备份、候选文件和 journal 包含私密配置，仅供部署所有者访问，不应提交 Git 或公开传输。Token 与账号密码不会作为保存日志打印。

## 失败恢复

恢复入口为再次运行同一部署的 `setup.sh` 并保存。脚本获取同一部署的配置锁后，先恢复未完成的事务，再准备本次配置。

- 当前状态：`runtime/configuration-journal.json`。
- 每次事务的备份、候选和 journal 副本：`runtime/configuration-backups/<事务ID>/`。
- 原 JSON 另保留为 `runtime/configuration-backups/core-<事务ID>.json`。
- 正在提交的状态为 `applying`，结束状态为 `committed` 或 `rolled_back`。新生成的 `start-core.sh` 遇到未完成或损坏的 journal 会拒绝启动。

正常异常会自动恢复旧文件的内容与权限。中断后重跑先核对全部备份与当前文件 SHA-256，再恢复；新文件或新账号目录通过移动保留，不永久删除。恢复自身也可重跑。

如果备份损坏、文件被其他写入者修改，或权限故障阻止回滚，脚本停止并保留 journal。此时请继续停止服务，检查 journal 指向的备份与候选、恢复权限，并核对文件摘要；不要直接删除 journal、替换主密钥或重新创建旧账号。确认备份完整后重跑保存；无法确认时保留现场人工恢复。手动执行 `oai serve` 会绕过启动脚本检查，恢复完成前请勿使用。

旧版本启动脚本在首次升级提交前还没有 journal 检查，因此停止服务及其他写入者是升级前提。事务保证可恢复，不宣称多个文件对运行中的读者同时可见。

## 完成状态

- 返回 `0`：配置及本次请求的安装流程完成。
- 返回 `1` 或其他保存错误：配置未提交完成；旧文件已恢复，或 journal 记录了待恢复状态。不会打印保存成功。
- 返回 `2`：配置已提交，但可选 skill、插件或系统集成未全部完成；明确提示“部分完成”，按提示重试，不会声称完整向导全部完成。

可选 skill 同步不会删除旧目录；插件安装、CLI 和自启动步骤在配置提交后执行，其失败不回滚已经提交的配置。

## 配置回归

使用已有 Python 环境，针对性运行：

```sh
PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src \
  python -m pytest tests/test_core_setup_transaction.py \
  tests/test_install_script.py oai-core/tests/test_core_config.py -q -rs
```

`test_core_setup_transaction.py` 覆盖真实 Shell 保存入口、文件替换与加载故障、中断恢复、备份损坏、并发写入拒绝、畸形配置和保留语义。测试使用合成账号和隔离宿主目录。不能创建符号链接的文件系统会优先使用现有的无害解释器符号链接，并断言系统返回 `ELOOP`，证明拒绝发生在读取目标内容之前；两种真实链接均不可用时才明确报告跳过。文件权限测试通过不能替代符号链接实测。
