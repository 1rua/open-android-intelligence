# 稳定版发布工作流调整为手动触发审查报告

**审查目标分支**：`feature/manual-stable-release-action` 对比 `origin/dev`  
**审查结论**：**审核通过 (PASSED)**

---

### 一、审查维度详细评估

#### 1. 代码规范符合性
- **路径引用规范**：所有修改文件（工作流定义、Python 脚本、测试用例与文档契约）均采用规范的项目相对路径，未引入任何硬编码的绝对路径。临时构建目录统一使用 GitHub Actions 标准变量（如 `$RUNNER_TEMP`）。
- **Git 提交规范**：提交信息 `重构: 将稳定版发布工作流调整为仅手动触发` 严格遵循 `<类型>: <简要描述>` 的中文提交规范。

#### 2. 需求符合性
- **触发器变更**：`.github/workflows/release.yml` 已彻底移除 `pull_request_target` 自动触发事件，仅保留 `workflow_dispatch` 手动触发事件。
- **分支安全约束**：工作流中的全部 4 个 Job（`sign-notification-package`、`build-stable-apk`、`verify-stable-apk`、`publish-stable`）均配置了统一严密的 `if` 门禁条件：
  ```yaml
  if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'
  ```
  若在非 `main` 分支（如 `dev` 或其他特性分支）误触手动发布，所有 Job 将被自动跳过，有效杜绝非主分支误发布的隐患。
- **发布状态防抖与原子性保证**：在 `publish-stable` 阶段，保留了比对远端 `main` 分支最新 commit sha 与当前构建 commit sha 的双重校验，防止构建排队期间主分支代码推进导致的旧版本覆盖。

#### 3. 潜在 Bug 与回归风险
- **Checkout 与 Commit 引用一致性**：
  - 各 Job 的检出动作（`actions/checkout@v7`）已统一使用 `ref: ${{ github.sha }}`，移除了对已不存在的 `github.event.pull_request.merge_commit_sha` 的三元回退，且完全移除了不再需要的 `allow-unsafe-pr-checkout: true` 参数。
  - GitHub Release 发布配置中的 `target_commitish` 及各检查步骤的 `TARGET_SHA` 均精准绑定 `${{ github.sha }}`，代码引用清晰无歧义。
- **发布说明提取脚本（`prepare_release_notes.py`）**：
  - 逻辑收紧：将 PR 事件解析限定为 `expected_branch == "dev"`。当 `expected_branch == "main"` 且收到 `pull_request` 或 `pull_request_target` 时，会直接抛出 `ValueError("Unsupported release event")`，阻断非法分支/事件组合。
  - 手动发布适配：在 `expected_branch == "main"` 且事件为 `workflow_dispatch` 时，优先通过 GitHub API 检索并使用已合并到 `main` 的 PR 正文；若不存在关联 PR，则自动降级使用所选提交的原生 commit message，表现稳定符合预期。
- **自动化测试充分性**：
  - `apps/android/tools/test_app_release_metadata.py`：针对工作流结构、触发器（断言包含 `workflow_dispatch`、排除 `pull_request_target` 及 `allow-unsafe-pr-checkout`）、执行权限及顺序等进行了契约断言，19 项测试全部通过（`OK`）。
  - `apps/android/tools/test_prepare_release_notes.py`：新增了 `main` 分支拒绝 `pull_request_target` 的异常校验测试，结合既有的手动触发关联 PR 提取及 commit 信息降级测试，7 项测试全部通过（`OK`）。
- **文档契约同步**：
  - `docs/contracts/app-update-release-v1.md` 已准确同步更新，明确说明 `.github/workflows/release.yml` 仅支持在 `main` 分支上手动触发，保持了实现与规范的一致性。

#### 4. 安全性
- **敏感凭证防泄漏**：检查全部变更内容，未发现任何硬编码密钥、私钥或敏感信息泄露。敏感操作（如签名与 Release 创建）均使用标准 Secrets 与环境注入。
- **攻击面缩减**：完全移除 `pull_request_target` 触发与 PR 检出后，彻底消除了针对稳定版发布流水线的高权限 PR 注入风险。

---

### 二、审查最终结论

**审核通过 (PASSED)**。本次改动完全满足需求规范与安全性要求，已可安全合并至上游分支。
