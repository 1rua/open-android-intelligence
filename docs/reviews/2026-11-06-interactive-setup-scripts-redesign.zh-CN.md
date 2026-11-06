# install 与 setup 交互式向导美化与文案优化

日期：2026-11-06。工作分支：`interactive_setup_scripts_redesign`。

## 背景与问题分析

在完成安全重构与本机插件安装链路修复后，`scripts/install.sh`、`scripts/setup.sh`、`scripts/installer.py`、`oai-core/src/oai_core/scripts_runtime.py` 和 `oai-core/src/oai_core/configuration.py` 虽然具备完整的权限校验与事务回滚保护，但在用户实际终端交互体验上存在三方面明显问题：

1. **视觉缺乏层次感**：终端输出全为无格式的纯文本行，缺少步骤进度提示、分区卡片边框和清晰的状态标记（进行中、成功、警告、错误），安装完成时也没有汇总卡片告知用户如何启动服务和如何在手机端连接。
2. **交互确认繁琐生硬**：常规操作要求用户手动拼写英文口令（如 `bootstrap`、`SAVED`、`STOP`、`restart`、`register`、`enable`、`disable` 或完整输入 Agent 标识），且在 `oai setup` 主菜单中一旦输错端口号或子菜单编号就会抛出 `ValueError` 直接退出整个配置向导。
3. **文案充斥 AI 味与技术术语堆砌**：大量内部工程术语直接暴露在用户界面中（如“配置事务已提交”、“账号关联按其隔离范围处理”、“受保护信任清单”、“恢复材料已保留”、“本安装辅助暂不支持操作其他用户的 Hermes 宿主”等），普通用户难以理解当前状态与下一步操作。

## 改造内容

### 1. 终端视觉美化与 4 步进度引导
- **自适应色彩与卡片排版**：在 `scripts/install.sh`、`scripts/setup.sh`、`scripts/installer.py`、`oai-core/src/oai_core/scripts_runtime.py` 和 `oai-core/src/oai_core/configuration.py` 中新增轻量终端排版辅助函数，自动检测 TTY 与 `NO_COLOR` 环境变量，在交互式终端展示圆角边框卡片（`╭─── ... ───╯`）与状态符号（`▸`、`✓`、`!`、`✗`），在非 TTY 管道中自动退化为纯文本。
- **四步清晰安装流程**：
  - `步骤 1/4 · 准备 Python 运行环境`（`scripts/installer.py`）
  - `步骤 2/4 · 创建首个管理员`（`oai_core.scripts_runtime._new_install`）
  - `步骤 3/4 · 备份管理员应急恢复码（仅显示一次）`（`oai_core.scripts_runtime.install`）
  - `步骤 4/4 · 后台自启与服务设置` 及最终 `🎉 OAI Core 安装已完成` 汇总卡片（展示安装目录、服务地址、启动命令、配置命令与手机 App 配对地址）。
- **命令行帮助**：为 `scripts/install.sh` 与 `scripts/setup.sh` 增加 `-h` / `--help` 参数说明。

### 2. 自然交互确认与菜单防退保护
- **默认回车确认（向后兼容旧口令）**：
  - `scripts_runtime._confirm` 支持直接按回车或输入 `y` / `yes` 确认，同时兼容原有的 `bootstrap`、`SAVED`、`STOP` 口令。
  - `configuration._manage_service`、`configuration._configure_agents`、`configuration._manage_security` 中的服务操作、Agent 连接确认、恢复码重新生成与 2FA 启停确认均支持直接按回车或输入 `y` / `yes`，同时兼容原有动作词（`start`/`stop`/`restart`、`<agent_id>`、`register`、`enable`/`disable`）。
  - 删除账号与重置他人密码仍保留输入目标用户名确认，防止误操作。
- **主菜单输入容错**：`configuration.run_setup` 捕获子菜单中的输入校验 `ValueError` 并输出友好提示，留在当前主菜单循环中，避免因单次输入失误导致整个配置会话中断。

### 3. 文案去术语化与通俗易懂改造
- 将 `configuration.py`、`scripts_runtime.py`、`hermes_installation.py`、`dsh_installation.py`、`agent_registration.py` 中的生硬工程黑话全面替换为通俗中文说明：
  - 将“配置事务已提交；账号、密码、配对和授权保持不变”改为“配置已安全保存生效；原有账号、密码和设备配对保持不变”。
  - 将“Agent 已通过真实握手并注册；账号关联按其隔离范围处理”改为“Agent 已通过真实握手并注册成功！现在可以在手机端选择并使用该助手了”，并在候选列表中直观标注 `account · 按账号独立会话` 与 `deployment · 全局共享会话`。
  - 将“请在 Hermes 的受保护信任清单中确认以下 Core 实例公钥”改为“首次连接 Hermes 时，系统已自动将当前 Core 服务端公钥写入信任列表（如需手动核对，公钥如下）”。
  - 将 `hermes_installation.py` 与 `dsh_installation.py` 中的“恢复材料已保留”、“本安装辅助暂不支持操作其他用户的 Hermes 宿主”等提示改为明确指出备份目录（`runtime/hermes-adapter-backups`、`runtime/dsh-adapter-backups`）和具体解决步骤的日常语言。
- 同步更新 `scripts/README.md`，补充四步安装流程、交互确认规范与设置面板说明。

## 测试与验证结果

1. **Shell 脚本语法检查**：
   - `bash -n scripts/install.sh scripts/setup.sh scripts/uninstall.sh`：通过（退出码 0）。
2. **安装、配置、插件安装与握手错误全集验证**：
   - 运行 `tests/` 全部测试及 `oai-core/tests/test_setup_plugin_installation.py`、`test_dsh_setup.py`、`test_dsh_installation.py`、`test_hermes_installation.py`、`test_agent_handshake_errors.py`、`test_core_config.py`，新增 `test_setup_menu_supports_default_confirm_and_recovers_from_invalid_input` 覆盖默认回车确认与主菜单异常恢复，共 **325 项测试全部通过**。
3. **依赖锁与静态门禁**：
   - `npm run mvp:lock:check` 与 `node --check integrations/dsh-session-adapter/index.js`：通过。
