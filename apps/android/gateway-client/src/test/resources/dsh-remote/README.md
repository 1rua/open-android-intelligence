# DSH Remote 固定互操作向量

来源为 `liguobao/ds-harness-remote`，固定提交
`ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，协议版本为 v1。

`fixtures/` 下所有文件均从该提交逐字节复制，不改写输入、预期输出或上游 README。
`SHA256SUMS` 记录原始文件摘要，测试同时校验摘要、fixture 格式和清单覆盖。
上游为 MIT 许可，完整版权与许可保存在相邻 `LICENSE.upstream`。

向量中的私钥、公钥、token、账号和设备 ID 全部是上游公开的合成测试材料。
它们只用于确定性互操作测试，严禁作为运行身份使用；不包含用户凭据。

密码执行使用 `org.signal.forks:noise-java:0.1.1`，固定套件
`Noise_IK_25519_ChaChaPoly_SHA256`。测试专用临时密钥通过内部入口注入，
生产入口只采用库自身的随机临时密钥。通过向量只证明固定字节互操作，
不代表真实 Host、Android 真机、四路径或线上部署已经验收。

上游对应路径：

- `fixtures/crypto/v1/noise-ik.json`：双方静态键、临时键、prologue、握手和传输密文。
- `fixtures/protocol/v1/manifest.json`：协议套件和格式版本。
- `packages/crypto/src/noise.ts`：固定密码套件、prologue 和对端公钥核对。
- `packages/protocol/src/index.ts`：控制帧、账号注册、能力协商和 DSHF 分块。
- `packages/protocol/src/wire-json.ts`：重复键与错误脱敏规则。
