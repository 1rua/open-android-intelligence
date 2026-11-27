# Core 独立化安全审查

基线：`f2c4a4eab081aa48fc0307c51f0f3c8754a5891d`；审查提交：`eb6798dabbe4acbe5a45bbfe1dce83131b3513cd`。审查者未编写本报告范围内实现；Hermes 可选包由其他审查者负责，不计入本结论。

审查范围为 Core 鉴权、设备队列、HTTP/SSE/WS、`oai-core/setup.py`、`scripts/core_setup.py` 与 install/setup 脚本。

## 结论

规范与需求两个维度均未发现本范围内新增的阻断问题。Core 明确声明第三方依赖，不再导入旧 Gateway 或 Hermes SDK；构建从仓库唯一契约目录生成 wheel/sdist 资产，启动只读本地契约。迁移后的队列、安全 Store 主要逻辑与旧固定提交逐项比较未变，传输不继承 Hermes 平台。

签名使用原 HTTP 字节与规范 target，重复安全头拒绝；持久 nonce 在签名通过后写入，重启仍拒绝重放。CLI 凭据绑定账号、设备、配对代数和授权版本；撤销及跨账号测试失败关闭。Claim/Result/ACK、取消、未知结果、队列恢复与事件游标复用同一 Core 状态机。

安装迁移保留原配置路径、既有账号密码、配对和主密钥；既有库缺原密钥即拒绝启动。旧启动脚本仅按固定文本格式读取 Token，不执行脚本；凭据独立保存为 0600，启动脚本无真实 Token。向导不会自动停旧 Gateway、启用新宿主或合并 Hermes 历史库。

## 本次验证

在该提交运行 `PYTHONPATH=oai-core/src .venv/bin/python -m pytest oai-core/tests/test_core_transport.py oai-core/tests/test_cli_mcp.py oai-core/tests/test_independence.py oai-core/tests/gateway_runtime tests/test_install_script.py -q`：**180 passed，26.63 秒**。覆盖原始字节、nonce 重启、凭据/授权撤销、账号隔离、队列及传输恢复、阻断 Hermes 导入、旧 Token 无执行迁移、权限拒绝与原密钥保留。

此为代码和本地自动化审查，不证明生产 Hermes 部署、真实模型服务或 Android 真机验收。其他审查者发现的会话后端独占连接恢复问题必须单独修复、复核并更新最终 SHA；本报告不替代该结论。
