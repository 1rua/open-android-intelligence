# Legacy Archived Modules / 历史归档模块目录

## 状态声明

本目录包含已归档并冻结的历史组件与废弃架构实现。所有目录均处于只读、冻结状态。

### 归档内容清单

1. **`legacy/android-tailnet/`**
   - 原 `apps/android/` 下的 `tailnet-core`、`transport`、`tailscale-companion`、`companion-bridge` 模块。
   - **归档原因**：早期基于 Tailnet/tsnet 虚拟组网方案已废弃，现行架构完全基于 Gateway Protocol v2.1 及模块化插件体系。

2. **`legacy/protocol/`**
   - 早期单体 P0a 协议定义与历史 schema。
   - **归档原因**：已被现行 `gateway-contract/` 协议取代。

3. **`legacy/bridge-contract/`**
   - 单体 Bridge 契约。
   - **归档原因**：仅用于 `legacy/bridge-runtime` 历史回溯与导出，生产运行时不再使用。

4. **`legacy/artifact-contract/`**
   - 早期附件传输契约。
   - **归档原因**：已被现行 Gateway 媒体协议取代。

5. **`legacy/mvp-contract/`**
   - 早期 MVP 传输契约与演进工具。
   - **归档原因**：其通话记录夹具已解耦自包含至 Android 测试资源，依赖锁门禁工具已提取至顶层 `tools/check-dependency-lock.ts`。

6. **`legacy/bridge-runtime/`**
   - 早期 v1 Bridge 运行时组件。

## 使用与维护约束

1. **严禁生产发布**：本目录下所有组件严禁引入生产主流程，仅用于向后兼容回溯与历史对照。
2. **严禁新增功能**：禁止在此添加任何新功能或扩展新特性。
3. **CI/构建脱钩**：现行 CI 构建与日常测试流水线不扫描或测试本目录内容，避免造成构建膨胀。
4. **统一新架构**：所有设备通信、插件交互与网关契约必须使用 `gateway-contract/` 与模块化插件架构规范。
