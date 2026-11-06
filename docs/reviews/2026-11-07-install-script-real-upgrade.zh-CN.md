# install.sh 已安装环境重复执行真实升级修复说明

日期：2026-11-07。工作分支：`interactive_setup_scripts_redesign`。

## 问题根因

检查 `scripts/install.sh`、`scripts/installer.py` 与 `oai-core/src/oai_core/scripts_runtime.py` 后发现，在已安装环境中重复运行 `install.sh` 时虽然提示“验证通过后将自动更新程序组件”，但实际上存在 4 处断点导致未完成真正升级：

1. **源码未更新**：
   - 通过 `curl -fsSL .../install.sh | bash` 重跑时，因目标目录中 `oai-core/pyproject.toml` 已存在，直接跳过了源码拉取并执行了本地旧版 `installer.py`。
   - 在已安装目录内直接执行 `./scripts/install.sh` 时，没有任何 `git fetch` / `git merge --ff-only` 拉取远端新提交的逻辑。
   - 从外部新源码目录执行 `./scripts/install.sh --dir <目标目录>` 时，`restore_source` 仅在目标目录缺失 `oai-core/pyproject.toml` 时才生效，导致目标目录源码保持旧版，且 `.venv` 可编辑包被指向外部路径。
2. **技能文件未同步**：已有 `core.json` 与 `start-core.sh` 的部署跳过了 `deploy_skills_to_agents`，新版 `oai-client` 技能不会更新。
3. **本机已安装的 AI 助手插件未同步升级**：已部署过 `oai-hermes-session-adapter`（含 `plugin.yaml`）或 DSH Profile 插件包的宿主环境，在重复执行 `install.sh` 时不会自动同步更新插件文件。
4. **正在后台运行的旧服务进程未重启**：若用户此前已启用 `oai-core.service` 后台运行，升级末尾默认选择 `[n]` 跳过重新生成自启配置时，运行中的旧进程不会重启，新代码无法生效。

## 修复方案与安全边界

本次修复严格遵守项目的管理认证边界——**在原管理员密码（及已开启的 2FA）验证通过前，绝不修改已有安装目录中的任何代码、配置、密钥或服务状态**：

1. **`scripts/install.sh`（在线管道升级暂存）**：
   - 当检测到目标目录已有安装且脚本通过标准输入（如 `curl | bash`，`script_dir` 为空）运行时，先将远端最新分支克隆到与目标同级的独立临时目录 `${target}.download.XXXXXXXX`，再携带 `OAI_TEMP_DOWNLOAD_SOURCE=1` 启动新包中的 `scripts/installer.py install --dir "$target"`。认证前目标安装目录零改动。
2. **`scripts/installer.py` 与 `oai_core.scripts_runtime`（双路径源码升级）**：
   - **外部源码同步（`--upgrade-source` / `_sync_install_source`）**：当外部安装源与目标目录不同且目标目录已有完整安装时，在管理员认证通过并获取配置锁后，按受控文件清单原子更新有变化的程序文件（严格跳过并保护 `runtime/`、`.venv/`、`.git/`、`core.json`、`start-core.sh`、主密钥与账号数据库），最后更新 `oai-core/pyproject.toml`，并将在线临时下载目录安全归档至 `runtime/upgrade-downloads/`（不使用永久删除命令）。
   - **本地 Git 仓库快进升级（`_upgrade_git_checkout`）**：当直接在本地 Git 部署目录运行 `install.sh` 时，认证通过后检查工作区状态；若无未提交修改且存在 `origin`，自动执行 `git fetch` 与 `git merge --ff-only` 更新到远端最新提交；若本地有未提交修改或网络离线，则保留现场并输出友好提示。
   - 源码更新后自动重载内存中的 `oai_core.configuration` 模块，并确保 `.venv` 的 `pip install -e` 始终绑定目标目录自身的 `./oai-core`。
3. **技能与已安装宿主插件同步升级（`_upgrade_installed_host_adapters`）**：
   - `deploy_skills_to_agents` 优先采用目标安装目录内的 `integrations/skills/oai-client`，并在已有部署升级时自动同步。
   - 若检测到本机 Hermes 已安装过 OAI 连接插件，自动调用 `install_hermes_adapter` 更新包与 `plugin.yaml`，并在 `hermes-gateway.service` 活跃时重启；若检测到本机 DSH Profile 已启用过 OAI 连接插件，自动调用 `install_dsh_adapter`（带原配置备份）同步更新。
4. **运行中后台服务自动重启（`_restart_active_service_if_owned`）**：
   - 升级末尾若未重新执行自启配置，自动核对当前加载的 Linux `oai-core.service`（或 macOS LaunchAgent）是否归属当前安装目录且处于运行态（`active`）；若匹配则自动执行 `daemon-reload` 与 `restart`，使新版本立即生效。

## 验证结果

- `bash -n scripts/install.sh scripts/setup.sh scripts/uninstall.sh`：通过。
- 在 `tests/test_install_source_recovery.py` 新增 3 项端到端升级回归测试：
  - `test_shell_stdin_upgrades_existing_installation_through_staging`（验证 `curl | bash` 在已安装目录下通过临时暂存目录拉取新包且认证前零改动）；
  - `test_repeat_install_with_upgrade_source_updates_code_skills_and_restarts_running_service`（验证错密拒绝、认证后同步新代码与 Skill、归档临时目录、绑定本地 `./oai-core` 及自动重启活跃 `oai-core.service`）；
  - `test_repeat_install_git_checkout_pulls_fast_forward_and_skips_dirty_worktree`（验证本地 Git 仓库快进拉取远端新版本及脏工作区保护）。
- 运行安装与配置全量回归测试集，全部通过。
