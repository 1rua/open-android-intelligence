# 代码审查报告：恢复 Android APK 构建工作流自动触发与产物命名

- **审查对象**：提交 `52afa4fea23df7ffff1c533010377e399bb0794f`
- **提交信息**：`优化: 恢复 Android APK 构建工作流对 main 分支的自动触发`
- **变更文件**：
  - `.github/workflows/android-apk.yml`
  - `.gitignore`
- **审查日期**：2026-10-24
- **审查结论**：**通过（PASS）**

---

## 一、审查结论概要

本次提交针对此前重构中无意引入的构建流中断和产物命名变更进行了精准修复：
1. 正确恢复了 `.github/workflows/android-apk.yml` 对 `main` 分支代码推送（`push`）的自动触发机制。
2. 将构建产物名称重新标准化为稳定的 `app-full-debug`，成功解决了自动化下载工具 `tools/download-latest-apk` 无法下载构建产物的断流问题。
3. 在 `.gitignore` 中补充了 `oai-release.keystore`，防止开发者在本地测试正式签名时误将私有签名密钥提交入库。

变动范围聚焦，逻辑清晰，无新增 Bug 或回归风险，安全防护到位。

---

## 二、分维度详细审查

### 1. 代码规范符合性（Standards Conformance）

- **Git 提交信息规范**：
  - 提交说明使用中文，并采用项目规定的 `<类型>: <简要描述>` 格式（`优化: 恢复 Android APK 构建工作流对 main 分支的自动触发`），符合 `AGENTS.md` 规范。
- **YAML 语法与 Actions 配置规范**：
  - `.github/workflows/android-apk.yml` 语法严格符合 YAML 规范，缩进对齐，列表语法简洁（`branches: [ main ]`）。
  - `upload-artifact` 步骤结构完整，保留了必需的 `path`、`if-no-files-found` 和 `retention-days` 属性。
- **.gitignore 配置规范**：
  - 新增行 `oai-release.keystore` 位于文件末尾，符合 gitignore 标准语法（无前导斜杠，可对仓库内任意层级的同名文件生效）。

### 2. 需求符合性（Spec / Requirement Alignment）

- **自动触发恢复**：
  - 此前提交（`7df208ea`）中，触发器被精简为仅保留 `workflow_dispatch:`。
  - 本次提交补充了 `push: branches: [ main ]`，确保无论是直接推送还是 PR 合并到 `main` 分支，均能及时自动生成最新 APK。
- **产物名称标准统一**：
  - 此前提交将产物名动态化为 `app-full-debug-${{ github.run_number }}-${{ github.run_attempt }}`。
  - 本次提交将其恢复为固定的 `app-full-debug`，符合项目历史契约与设计规范。

### 3. 潜在的 Bug 与回归风险分析（Bug & Regression Risk）

- **自动化工具链兼容性（正向修复）**：
  - 工具 `tools/download-latest-apk` 第 18 行默认配置 `ARTIFACT="${ARTIFACT:-app-full-debug}"`，且在第 140–146 行通过 GitHub API 精确过滤 `select(.name == "${ARTIFACT}")`。
  - 此前动态命名的改动直接破坏了该工具的默认行为，导致开发者或自动化测试运行 `tools/download-latest-apk` 时必然报错退出。
  - 本次改动恢复标准名称后，工具链恢复正常闭环。
- **工作流并发与队列控制**：
  - 工作流保持并发组 `concurrency.group: android-apk-artifact` 且 `cancel-in-progress: false`。向 `main` 推送连续提交时，各次构建按序排队执行而不会被暴力取消，保障了各个提交均能产出对应的校验产物。
- **工作流互斥与边界**：
  - 本工作流与处理正式发布的 `release.yml`（由 `pull_request_target: closed` 触发，并发组为 `dev-release`）及预览发布的 `dev-release.yml` 保持清晰隔离，不会引发执行冲突。
- **文档一致性微小建议**：
  - 文档 `docs/contracts/app-update-release-v1.md` 第 60 行存在历史描述语句：“`.github/workflows/android-apk.yml` 仅手动生成供下载的 Actions 构建产物”。既然已恢复 `push` 自动触发，建议后续在维护相关文档时顺带更新该表述。

### 4. 安全合规（Security & Credentials）

- **私钥防泄漏保护**：
  - 正式发布工作流 `release.yml` 使用的环境变量指定临时密钥文件为 `${{ runner.temp }}/oai-release.keystore`。本地开发者在模拟正式构建时容易在项目目录下创建同名密钥。
  - 在 `.gitignore` 中忽略 `oai-release.keystore` 属于及时且必要的防泄露保护措施。
  - 仓库内既有的公开固定调试密钥（`apps/android/app/keystore/debug.keystore`）不受此规则影响。
- **最小权限与密钥隔离**：
  - 工作流顶层权限声明为只读 `permissions: contents: read`。
  - 设备通知插件签名密钥 `OAI_PLUGIN_SIGNING_KEY_BASE64` 仅在独立的 `sign-notification-package` 作业中获取，使用 `umask 077` 限制权限并在退出时清理；后续 `apk` 作业仅通过产物下载机制使用已签名的 `.alp` 文件，未接触任何签名私钥。
- **纵深防御建议（非阻塞性）**：
  - 当前规则仅排除了特定文件名 `oai-release.keystore`。建议后续可进一步补充通配规则（如 `*.jks`、`*release*.keystore` 并显式排除 `!apps/android/app/keystore/debug.keystore`），以提供更全面的密钥防泄漏保护。

---

## 三、验证结果记录

1. **发布工具与元数据测试**：
   - 运行 `python3 -m unittest discover -s apps/android/tools -p 'test_app_release_metadata.py'`：19 项全部通过。
   - 运行 `python3 apps/android/tools/test_extract_package_version.py`：12 项全部通过。
   - 运行 `python3 apps/android/tools/test_prepare_release_notes.py`：7 项全部通过。
2. **静态与语法检查**：
   - YAML 语法解析正常，触发规则与步骤配置无语法错误。
   - Git 状态检查确认工作区干净，`.gitignore` 生效。

