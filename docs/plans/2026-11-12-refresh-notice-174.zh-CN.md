# 缺少刷新凭据通知的时序验证

关联 Issue：#174。固定基线：`83fbef075a494f0d4899cc22c08b4864c9edb3cd`。

最终Full第二轮在PlayDebug变体的 `GatewaySessionRefreshTest.withoutARefreshCredentialThePathSaysSoInsteadOfPretending:959` 失败。第一轮ColdStart197是不同问题，不合并其根因。

## 实施范围

1. 固定原方法和变体，确认 `Connected` 发布与初始化操作门禁释放的时序。
2. 用可控初始化门禁构造红环，区分测试过早读取与实际操作丢失。
3. 保留缺凭据可读说明和不发送HTTP续期两项业务断言，保持生产防重入和账号/密钥语义。
4. 最小修正通过聚焦和相关测试后，完成独立Standards/Spec审查及必要门禁，再验证最终Full。

## 完成条件

- [ ] 原失败根因可定位、受控红绿可重跑。
- [ ] 初始化期间合法操作不丢失，缺凭据时说明准确且零续期HTTP。
- [ ] 不增加任意延时或放宽预算，不以单项绿冒充Full通过。
- [ ] 独立评审与必要门禁通过。

当前仅为实施起点，尚未声明产品或测试已修复。
