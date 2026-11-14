# 固定上游 Remote Server 回归夹具

这里保留固定提交 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7` 的最小 Server/Gateway/Store 与协议依赖源码，
原文按 SHA-256 核对，许可证随包保留。仅用于隔离网络回归，不进入 Android 生产包，不维护上游分叉。
Node 加载器只连接原文模块和官方 ws/zod 依赖，不改服务端版本判断、连接替换、转发或校验逻辑。

```sh
npm ci --prefix tools/remote-server
node --experimental-vm-modules tools/remote-server/fixed-server.mjs
```

进程输出一行本地临时地址与测试专用账号，测试关闭 stdin 后停止，临时状态安全移到忽略回收区。
Android 测试通过固定 Server 的真实 HTTP/Control WS 验证 Noise、版本协商和多 Client 隔离。
真实手机、真实 Host、四网络路径和部署验收仍单列。
