# 屏幕手动控制剩余诊断

状态：task-011 未完成，当前没有实际控制允许 PASS。最新已发布UI修正及文档为c0779957/ff720cde，屏幕源码已单独保存为仅本地分支`feature/global-device-authorization-screen-wip-20261101`，提交`c71735b0daf325ab24cc9856b8da1917183a5b06`，未推送、未进入PR；交付工作树已回到原feature分支。

## 已确认的失败层次

1. 旧包的屏幕版本绑定所有无障碍事件；真实本机确认卡出现和消失后，原expectedRevision失效。已先建立红回归，不关闭版本与权限保护。
2. 774620诊断包中，reader正常移除本机窗口并发行版本后，不足1秒的状态栏迟到CONTENT事件导致control在弹卡前拒绝；应用窗口没有同区间事件。
3. 3b32目标约束补丁中，preflight的revision/epoch/hash/fingerprint均匹配并出现真实控制卡535，批准后dismiss_history、dismiss_equivalence和dismiss_application仍失败。现有日志不能区分是活动焦点尚未恢复、窗口集合变化，还是应用完整指纹变化，不能继续凭推测放宽。

## 本轮动作

新增application_equivalence各条件的布尔值与窗口ID/类型/层级/活动/焦点/边界数字，执行前复查记录generation/display/capture匹配布尔。日志不记录指纹值、屏幕正文、节点或凭据，原放行条件不变。

screen-control完整定向和APK构建退出0；诊断App SHA-256为2ad99dae1f55092aa51e73763a8bc4b452a6a155a054ecf59c354b98311e8e75，测试包db709b77ee888368ef12a5f55cd39bc6cd2aeac288cc924c19552e59449d03c3，沿用原debug签名。该新版诊断包未安装、未执行；原拟Terra真实确认卡reader→立即control链尚未进行。不得把构建通过写成运行通过。

## 完成标准

必须由日志确认具体失败条件，建立覆盖该条件的最小回归，并由Terra实测真实批准后performed及页面效果；目标真实变化、撤销、取消、连接/租约失效仍拒绝。普通读取、坐标/系统节点及其他动作不能为让back通过而被伪造、自动降级或扩大。原GLOBAL矩阵的点击/滑动、系统撤销和并发/生命周期要求仍逐项验收。


## 安装环境边界纠正

Terra预检确认emulator-5554的user0和user10共用设备级APK程序路径。此前“仅user10覆盖安装”只限定安装启用/操作的数据用户，不能理解为程序版本在不同用户间隔离。此次2ad99d诊断包在安装前停止，没有新安装或请求，不将该阶段写成运行通过。

此前拟将新包诊断改至emulator-5556/user0；用户要求本轮收尾后，该安装与请求计划已停止，没有在5556执行新诊断。后续继续时需重新核对设备归属、源码及包摘要。原5554现有结果仍分别归属实际已装哈希；不得以新包摘要重标旧结果，也不通过读取原user0私有数据来验证。此切换仅为验收环境隔离，不改变产品授权设计。

最终交接见[收尾报告](2026-11-01-global-device-authorization-closeout.zh-CN.md)。本轮只保留检查点，不继续扩大修复或放宽屏幕保护。
