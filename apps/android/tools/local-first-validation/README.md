# Local-First 隔离验收工具

本目录仅用于专用 API 35 模拟器和本地合成 Core/Adapter 数据，不连接生产账号，不运行真实模型。结果与边界见仓库 `docs/reviews/2026-11-03-android-local-first-device-acceptance.zh-CN.md`。

## 环境

- 在仓库内独立 worktree 运行；默认复用上两级 `.toolchains`。可用 `OAI_TOOLCHAINS_ROOT` 指定其他既有工具链目录，支持 `JAVA_HOME`、`ANDROID_HOME`、`GRADLE_USER_HOME` 覆盖；Build Tools 默认 35.0.0，可用 `OAI_BUILD_TOOLS_VERSION` 覆盖。
- 需要已有 JDK 21、项目 Gradle 缓存、Android SDK/API 35 系统镜像、KVM、ADB、Android CLI、Python 测试环境。工具不会为性能测试更换冷缓存。
- 完整 APK 与 App 门禁需要既有六个 WASM 插件 fixture；按仓库 CI 的锁定 Rust 工具链构建，或复用同源码、摘要已核对的产物。不要把缺 fixture 当作本次会话修复失败。
- `build/` 保存基线快照、合成服务状态、AVD 和本机日志，受仓库忽略规则保护。保留以便复核，不自动清理或覆盖。

## 执行

1. `bash apps/android/tools/local-first-validation/build-baseline.sh` 从固定 `733c6a30` 归档纯基线并构建；已有快照时拒绝覆盖。
2. `bash apps/android/tools/local-first-validation/run-emulator.sh` 启动专用 `oai-local-first-20261103`；端口占用时停止，不触碰其他设备。
3. `run-synthetic-core.py --help` 查看隔离服务参数；`seed-second-session.py`、`seed-remote-sessions.py` 只准备合成数据，并在 Adapter 活跃时拒绝写存储。
4. `build-measurement.sh` 只在独立基线副本应用 `baseline-measurement-v3.patch`，补丁摘要固定；该 APK 必须标记为基线加观测补丁。
5. `collect-first-draw.py --help` 查看操作参数。采集前核对 AVD、API 和设备内 APK 摘要；`local` 冷开、`memory` 会话回切、`remote` 首次非空历史各自采样，不冒用来源。
6. `summarize-first-draw.py` 按进程、会话和请求代次关联；中位数与最近秩 P95 只使用有效记录。少量样本照实输出，并警告尾部估计不稳定；基线没有 `memory` 路径。

测量终点是 Compose 列表绘制完成，不是物理显示、图片显示或 Activity 启动时间。可交付证据保存在 `docs/reviews/artifacts/2026-11-03-android-local-first-sync/`，原始视频和本机大日志不提交。

## 工具自测

```bash
python3 apps/android/tools/local-first-validation/test-collect-first-draw.py
python3 apps/android/tools/local-first-validation/test-summarize-first-draw.py
```

工具测试只证明目标选择与统计逻辑，不代表 App 功能或设备验收通过。
