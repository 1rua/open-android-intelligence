# OAI Android 设置系统重构 - 剩余任务清单

## ✅ 已完成 (Commits: 14个, 95%完成度)

### 核心文件创建 (9个)
1. **P0: 修复设置页面动画方向** (commit: 7dc02e3c)
   - 从左向右 → 从右向左(符合 Android 规范)
   - 修复 AppTransitions.modalEnter/modalExit

2. **创建新的路由定义** (commit: 07fa393f)
   - 六大分类路由: 外观/连接/通知/能力/安全/高级
   - 旧路由兼容性标记(废弃但保留)

3. **公共组件提取** (commit: d7f5759f)
   - SettingsComponents.kt: Scaffold/Column/SectionLabel
   - 从主文件提取可复用组件

4. **独立设置页面文件** (commits: 9ba878ce, b29f175c, 44043a25, bc383257, 6e711d10)
   - AppearanceSettingsScreen.kt: 外观与交互
   - SecuritySettingsScreen.kt: 安全与隐私
   - SettingsOverviewScreen.kt: 主览页面(六大分类入口)
   - ConnectionSettingsScreen.kt: 连接与账号
   - AdvancedSettingsScreen.kt: 高级设置与关于
   - NotificationSettingsScreen.kt: 通知与提醒
   - CapabilityAuthSettingsScreen.kt: 设备能力与授权

### 主文件集成 (5个commits)
5. **路由集成** (commits: 47506960, 4694466b)
   - 更新 APPEARANCE/GATEWAY/SECURITY 路由使用新文件
   - 添加 CAPABILITIES/NOTIFICATIONS_NEW/ADVANCED 路由
   - PAIRING路由重定向到CAPABILITIES(兼容性)

6. **代码清理** (commits: dc467bc4, cd789e62)
   - 删除已迁移的旧子页面函数(419行)
   - 添加公共组件导入
   - 删除重复的辅助函数
   - **代码精简**: 从1697行减少到1264行 (减少433行, 25.5%)

## ⏳ 进行中 (5%剩余)

## 📋 剩余P0任务 (必须完成, 约5%)

### 1. ~~创建剩余独立页面文件~~ ✅ 已完成
- ✅ `SettingsOverviewScreen.kt` - 主览页面
- ✅ `ConnectionSettingsScreen.kt` - 连接与账号
- ✅ `NotificationSettingsScreen.kt` - 通知与提醒
- ✅ `CapabilityAuthSettingsScreen.kt` - 设备能力与授权
- ✅ `AdvancedSettingsScreen.kt` - 高级设置与关于

### 2. ~~更新主 SettingsScreen.kt~~ ✅ 已完成
- ✅ 删除已迁移的子页面函数 (减少419行)
- ✅ 更新 NavHost 使用新的独立文件
- ✅ 保持旧路由兼容性处理
- ✅ 更新导入语句
- ✅ 删除重复的公共组件函数
- ✅ 保持 ViewModel 和 UiState 不变
- ✅ 保持对话框逻辑(TrustMode/Emergency/Unpair)
- ✅ 保持 Snackbar 和权限请求逻辑

### 3. ~~移除纯中转页(settings/pairing)~~ ✅ 已完成
- ✅ 删除 PairingSubScreen 函数
- ✅ PAIRING 路由重定向到 CAPABILITIES (兼容性保留)

### 4. ~~基本文案优化~~ ✅ 已完成

已优化:
- ✅ 外观设置: "系统动态取色" → "跟随壁纸取色"
- ✅ 安全设置: "WASM 沙箱" → "隔离环境"
- ✅ 连接设置: "Gateway" → "OAI Core"
- ✅ 连接设置: "SPKI Pinned" → "TLS证书固定"
- ✅ 通知设置: "系统通知推送" → "Agent任务通知"
- ✅ 通知设置: 统一命名(任务通知/会话气泡/实时任务状态)
- ✅ 能力设置: 按能力组织而非按插件
- ✅ 安全设置: "开发者信任模式" 保持(技术准确性)

## 📋 P1任务 (应该完成)

### 1. 设备能力按能力组织

现状: 混合在插件管理中
目标: 按能力独立呈现,插件作为"提供者"

待实现:
- [ ] 12种能力各有独立卡片
- [ ] 每个能力显示: 开关/授权模式/系统权限/提供者插件
- [ ] 主动推送独立子页

### 2. Agent 选择主览快捷访问

现状: 埋藏在 Core管理 → Agent列表
目标: 主览直接显示当前Agent

待实现:
- [ ] 主览"连接与账号"卡片显示当前Agent
- [ ] 点击进入独立的AgentManagement子页

### 3. 手机横屏宽度限制

现状: fillMaxSize() 横屏占满全屏
目标: 最大宽度 384dp

待实现:
- [ ] 在 PlatformSettingsBottomSheet 添加 widthIn(max = 384.dp)

### 4. 完整文案优化

待优化区域:
- [ ] 所有错误提示包含解决方案
- [ ] 危险操作明确警告
- [ ] 不可用状态说明原因

### 5. 交互反馈动画增强

待实现:
- [ ] 开关切换: scaleX/scaleY 动画
- [ ] 卡片按压: Ripple 效果
- [ ] 状态更新: AnimatedContent
- [ ] 列表展开: expandVertically + fadeIn

## 📋 P2任务 (可以推迟)

- [ ] 预测性返回实现(NavigationEvent库)
- [ ] 折叠屏和桌面模式优化
- [ ] 微交互细节打磨
- [ ] 文案多语言支持准备

## 🔧 技术债务

### 编译问题修复

待解决:
- [ ] 确保所有新文件可编译通过
- [ ] 修复缺失的导入语句
- [ ] 修复函数签名不匹配

### 测试验证

待执行:
- [ ] 运行 `./gradlew :app:assembleDebug` 验证编译
- [ ] 手动测试所有设置页面功能
- [ ] 验证旧路由兼容性
- [ ] 验证动画方向修复

## 📊 进度统计

- **已创建文件**: 10/10 (100%)
- **已提交commits**: 14个
- **代码精简**: 从1697行减少到1264行 (减少433行, 25.5%)
- **完成度**: 95%
- **当前优先级**: 验证和收尾

## 🎯 下一步行动

1. ✅ ~~创建所有独立页面文件~~
2. ✅ ~~更新主 SettingsScreen.kt 集成所有新文件~~
3. ✅ ~~删除已迁移的旧子页面函数~~
4. ✅ ~~添加公共组件导入~~
5. ⏳ 验证编译(Gradle不可用,需本地验证)
6. ⏳ 测试功能完整性
7. ⏳ 提交最终PR到 dev 分支

## 📝 备注

- 所有新文件都在 `settings/` 包下
- 旧路由保留兼容性(标记废弃)
- 功能不丢失,数据持久化不变
- Material 3 设计规范统一
- 文案自然化,消除技术术语
