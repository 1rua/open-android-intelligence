# 消息修复测试门禁的实际执行范围

新增的结构化工具测试依赖可选 Hermes Session Adapter。独立 Core 的 CI 只安装 Core，直接收集这两份测试会在导入时失败；原 Hermes 专项使用固定文件列表，又没有包含它们。`tools/smart-test.py` 虽然识别 `affects_hermes`，此前没有对应执行分支，单改 Adapter 可以得到未测试适配器的成功结果。

本步修复三个入口：独立 Core 作业排除两份可选适配器测试；固定 SDK 的 Hermes 作业显式执行两份新测试；本地 fast/pr 在 Adapter 受影响时执行八份 Hermes 专项，失败原样返回。没有显式固定 SDK 时，原生用例仍可能跳过，不能把跳过计为通过。

`tools/test_smart_test.py` 用两项回归验证 Adapter 独立改动确实触发专项且保留失败退出码，以及独立 Core 不收集可选 Adapter 测试。两项通过，CI 的 Core 作业也包含这份测试。

另外创建了不含 Hermes Adapter 的独立 Python 测试环境，确认 `find_spec("oai_hermes_adapter") is None` 后运行 Core 与安装器回归：**315 passed、8 skipped、2 failed**。两项失败均在合成 fixture 创建符号链接时得到 `FileNotFoundError`，尚未进入拒绝符号链接的业务断言；正常文件系统权限模式的单独重跑也同样失败。因此本环境不能完成这两项文件系统安全验证，不修改或跳过其安全断言。该结果证明修正后的独立测试集合可以收集和执行，不代表整套门禁已绿。

环境日志保存在已忽略的工具目录；最终 fast/pr、固定 SDK 专项和 Android 整合结果另记，测试范围有重叠，不累加为独立总数。
