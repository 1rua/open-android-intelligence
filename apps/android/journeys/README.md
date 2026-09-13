# Open Android Intelligence 自动化 Journey 测试套件

本目录包含为 Open Android Intelligence 项目工程化落地的自动化 Journey 描述符用例套件与调度配置，专供基于 Android 模拟器或实机的端到端（E2E）自动化测试与调度。

## 目录结构

```
apps/android/journeys/
├── README.md                              # 本说明文档
├── test_plan_manifest.json                # 标准测试执行清单 (TestPlanManifest)
├── validate_journeys.py                   # 用例与清单完整性校验脚本
├── prepare_environment.sh                 # 测试环境诊断与就绪验证脚本
│
├── MOD-01-gateway-login.journey.xml       # 核心模块 1: 网关连接与身份认证
├── MOD-02-workbench-timeline.journey.xml  # 核心模块 2: 对话主工作台与时间线交互
├── MOD-03-thread-drawer.journey.xml       # 核心模块 3: 会话与线程抽屉管理
├── MOD-04-composer-slash-commands.journey.xml # 核心模块 4: 消息编辑与快捷指令触发
├── MOD-05-attachment-management.journey.xml   # 核心模块 5: 多模态附件上传与管理
├── MOD-06-assistant-surface-selection.journey.xml # 核心模块 6: 浮动助理与屏幕圈选交互
├── MOD-07-platform-settings.journey.xml   # 核心模块 7: 平台设置与安全控制
├── MOD-08-audit-logging.journey.xml       # 核心模块 8: 平台审计日志与能力采集
│
├── EXC-01-network-offline.journey.xml     # 异常场景 1: 断网无连接
├── EXC-02-weak-network-latency.journey.xml# 异常场景 2: 弱网高延迟与加载响应
├── EXC-03-permission-denial.journey.xml   # 异常场景 3: 权限拒绝降级
├── EXC-04-background-lifecycle.journey.xml# 异常场景 4: 后台切换与生命周期恢复
├── EXC-05-rapid-repeated-clicks.journey.xml# 异常场景 5: 快速重复点击与防抖测试
├── EXC-06-empty-data-state.journey.xml    # 异常场景 6: 数据为空与空状态展示
├── EXC-07-illegal-input-boundary.journey.xml # 异常场景 7: 非法输入与边界值
└── EXC-08-api-timeout-error.journey.xml   # 异常场景 8: 接口超时与服务端故障响应
```

## 测试用例覆盖矩阵

### 1. 核心功能模块 (MOD-01 ~ MOD-08)

| 编号 | 模块名称 | 入口与核心动作 | 预期验收标准 |
|---|---|---|---|
| **MOD-01** | 网关连接与身份认证 | `MainActivity` 登录表单，输入 HTTPS 地址、账号与凭据，点击连接 | 握手成功，凭据加密安全落盘，跃迁至主工作台 |
| **MOD-02** | 对话主工作台与时间线 | `WorkbenchScreen`，浏览消息时间线，滑动交互 | 真实时间线渲染，M3 规范排版与系统动态配色，无假数据 |
| **MOD-03** | 会话与线程抽屉管理 | 顶部抽屉图标，展开侧边面板，新建会话或切换会话 | 抽屉平滑滑出，新建后工作台完全重置就绪，无草稿串扰 |
| **MOD-04** | 消息编辑与快捷指令 | 底部 `ComposerBar`，常规文本编辑与 `/` 快捷指令补全发送 | 指令菜单即时展现，消息原子投递，输入框重置 |
| **MOD-05** | 多模态附件上传与管理 | 附件加号按钮，唤起拍照/相册/文档选择面板，暂存与移除 | 走真实 SAF 契约与三步上传状态流转，支持卡片移除 |
| **MOD-06** | 浮动助理与屏幕圈选 | 顶部助理入口，展开浮动面板，停靠球悬浮与收起 | 浮动层几何连续、可打断，保持底层状态完好挂载 |
| **MOD-07** | 平台设置与安全控制 | 平台设置面板，切换主题模式、动态取色、减弱动画与紧急熔断 | 主题与动效即时生效，熔断动作立即切断插件并明确标红 |
| **MOD-08** | 平台审计日志与能力采集 | 设置中的审计记录区，查看本地单调递增审计事件流 | 真实记录所有关键调用事实，不可篡改，支持审计追溯 |

### 2. 核心异常场景 (EXC-01 ~ EXC-08)

| 编号 | 异常分类 | 触发场景与条件 | 预期验收标准 |
|---|---|---|---|
| **EXC-01** | 网络异常 | 断网、飞行模式或不可达网关 IP | 明确展示连接失败原因与重试按钮，不卡死、不崩溃 |
| **EXC-02** | 弱网抖动 | 高延迟网络 (RTT > 5000ms) 或丢包 | 呈现不定加载进度，保持 UI 线程响应，杜绝系统 ANR |
| **EXC-03** | 权限拒绝 | 用户拒绝相机或存储权限申请 | 优雅降级提示权限缺失，不抛出未捕获 SecurityException |
| **EXC-04** | 生命周期 | 输入长草稿时切至后台，再恢复回前台 | 草稿内容与会话状态完整保留，杜绝白屏重构 |
| **EXC-05** | 压力测试 | 100ms 间隔内连续点击发送按钮 5 次 | 防抖机制拦截重复点击，仅投递单条原子消息 |
| **EXC-06** | 数据边界 | 新建环境无任何历史数据 | 各页面诚实展现标准空状态说明，杜绝布局崩溃与白屏 |
| **EXC-07** | 输入边界 | 输入非 HTTPS 协议、畸形域名、超长字符或空字段 | 客户端即时高亮拦截，严禁发出非法网络包 |
| **EXC-08** | 服务故障 | 服务端返回 500/502/504 或 SSE 意外断连 | 正确解析错误协议码并在卡片展示，提供安全重连通道 |

## 验证与执行

1. **环境检查**：
   ```bash
   ./apps/android/journeys/prepare_environment.sh
   ```
2. **用例与清单校验**：
   ```bash
   python3 apps/android/journeys/validate_journeys.py
   ```
3. **构建待测 APK**：
   ```bash
   cd apps/android && ./gradlew :app:assembleFullDebug
   ```
   产物位于：`apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`
