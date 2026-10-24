# 分支合并策略与 PR 来源分支安全校验设计与审查报告

## 1. 目标与背景

为了保障主分支（`main`）代码质量与发布流水线的安全性，规范仓库的分支流转模型，现建立严格的分支保护规则与合并校验策略：
- **目标一**：`main` 分支只能接受本仓库 `dev` 分支发起的 Pull Request；
- **目标二**：禁止直接 Push、强制推送（Force Push）或删除 `main` 分支；
- **目标三**：特性分支与修复分支（如 `feature/*`、`fix/*` 等）可正常向 `dev` 分支提交 PR，不受新增限制；
- **目标四**：所有针对 `main` 的 PR 必须通过 PR 来源分支校验（`PR Source Check`）以及现有必需 CI 检查（`Node.js & Gateway Conformance`、`Android Build & Check`），任何检查失败、缺失或未完成时均禁止合并；
- **目标五**：防止工作流被 PR 篡改、跳过或绕过，采用不可篡改的 `pull_request_target` 安全触发机制与多维身份验证。

---

## 2. 方案设计与防绕过机制

### 2.1 触发机制安全性（pull_request vs pull_request_target）

在 GitHub Actions 中：
- 普通的 `pull_request` 事件在 PR 来源分支（head）上下文中检出工作流。如果恶意分支修改或删除了工作流文件，Runner 会执行被篡改的工作流，存在检查被直接绕过的重大安全漏洞。
- 采用 **`pull_request_target`** 事件：
  1. 工作流文件始终从目标基准分支（`main`）读取并加载，PR 分支中的任何修改均无法篡改执行中的校验逻辑；
  2. 仅在目标分支为 `main` 时触发（`branches: [main]`），向 `dev` 提交的 PR 完全不触发此工作流，确保对日常开发分支零干扰；
  3. 采用内联脚本与零检出设计，无需运行 `actions/checkout`，执行耗时仅 1~2 秒，避免了克隆开销和依赖安全风险。

### 2.2 多维身份严格校验

校验模块（`tools/verify_pr_source.py` 与工作流内联逻辑）通过环境变量严格验证以下维度：
1. **目标分支校验**：`base.ref` 必须精确等于 `main`；
2. **来源分支校验**：`head.ref` 必须精确等于 `dev`；
3. **同仓库校验**：`head.repo.full_name` 必须等于 `base.repo.full_name`（即 `1rua/open-android-intelligence`），防止 Fork 仓库同名 `dev` 分支冒充；
4. **Fork 标志校验**：`head.repo.fork` 必须为 `false`；
5. **仓库 ID 校验**：`head.repo.id` 必须等于 `base.repo.id`。

### 2.3 注入防御（Injection-Proof）

所有 GitHub 上下文字段均通过 `env:` 映射为环境变量，不直接在 Shell 命令中进行文本插值，彻底杜绝命令注入与脚本逃逸风险。

---

## 3. GitHub Ruleset 配置规划

### 3.1 `main` 分支保护规则集（`main-branch-policy`）

- **目标范围**：`~DEFAULT_BRANCH`（即 `main` 分支）
- **Enforcement**：`Active`
- **Bypass 权限**：无（`bypass_actors: []`，任何人均不可越权绕过）
- **规则列表**：
  1. `deletion`：禁止删除分支；
  2. `non_fast_forward`：禁止强制推送；
  3. `pull_request`：强制必须通过 PR 合并，禁止直接 push；
  4. `required_status_checks`（必需状态检查）：
     - `PR Source Check`（来源分支校验）
     - `Node.js & Gateway Conformance`（现有 CI 检查 1）
     - `Android Build & Check`（现有 CI 检查 2）

### 3.2 `dev` 分支保护规则集（`dev` Ruleset 调整）

- 现有的 Ruleset 25771314 范围由包含 `~DEFAULT_BRANCH` 调整为**仅包含 `refs/heads/dev`**，剥离 main 分支的混合保护，避免规则冲突与对 feature 分支 PR 的误伤。

---

## 4. 本地测试与自动化验证

单元测试 `tools/test_verify_pr_source.py` 覆盖以下场景：
1. 合法场景：本仓库 `dev -> main` 校验通过；
2. 非法分支：`feature/* -> main` 校验被拒；
3. 非法目标：目标分支非 `main` 校验被拒；
4. 外部 Fork：来自 Fork 仓库的 PR 校验被拒；
5. Fork 标志篡改：Fork 标志为 true 时校验被拒；
6. 仓库 ID 不匹配：仓库 ID 篡改时校验被拒；
7. 缺失必要参数：空分支或空仓库参数时校验被拒。

测试执行指令：
```bash
python3 -m unittest discover -s tools -p 'test_*.py'
```
结果：全部测试通过。

---

## 5. 代码审查与优化迭代

- **审查执行**：由独立 Code Review 子 Agent 执行，涵盖代码规范、需求符合性、潜在缺陷、回归风险与安全审查 5 个维度。
- **审查结论**：审查通过（Pass）。未发现阻塞性漏洞或破坏性回归风险。
- **采纳优化**：在工作流触发器中补充 `edited` 事件类型。当开发者在 GitHub 页面上动态将 PR 目标分支从其他分支切换为 `main` 时，能够立即触发实时校验并给出即时阻断反馈。


