# OAI Android 设置系统重构 - 剩余任务清单

## ✅ 已完成 (Commits: 4个)

1. **P0: 修复设置页面动画方向** (commit: 7dc02e3c)
   - 从左向右 → 从右向左(符合 Android 规范)
   - 修复 AppTransitions.modalEnter/modalExit

2. **创建新的路由定义** (commit: 07fa393f)
   - 六大分类路由: 外观/连接/通知/能力/安全/高级
   - 旧路由兼容性标记(废弃但保留)

3. **公共组件和外观设置页面** (commit: d7f5759f)
   - SettingsComponents.kt: Scaffold/Column/SectionLabel
   - AppearanceSettingsScreen.kt: 完整外观设置功能

4. **安全与隐私设置页面** (commit: 9ba878ce)
   - SecuritySettingsScreen.kt: 开发者模式/插件安全/紧急停止

## ⏳ 进行中

- Workflow任务 (wet5vl6k2) 正在后台运行,创建其他页面文件

## 📋 剩余P0任务 (必须完成)

### 1. 创建剩余独立页面文件

需要创建的文件:
- [ ] `SettingsOverviewScreen.kt` - 主览页面(六大分类入口)
- [ ] `ConnectionSettingsScreen.kt` - 连接与账号
- [ ] `NotificationSettingsScreen.kt` - 通知与提醒
- [ ] `CapabilityAuthSettingsScreen.kt` - 设备能力与授权
- [ ] `AdvancedSettingsScreen.kt` - 高级设置与关于

优先级排序:
1. **SettingsOverviewScreen.kt** (最高) - 主入口,是其他页面的基础
2. **CapabilityAuthSettingsScreen.kt** - 复杂度最高,需整合多个子系统
3. **NotificationSettingsScreen.kt** - 需整合三套通知系统
4. **ConnectionSettingsScreen.kt** - 需拆分Agent管理
5. **AdvancedSettingsScreen.kt** - 相对简单,整合现有功能

### 2. 更新主 SettingsScreen.kt

任务:
- [ ] 删除已迁移的子页面函数
- [ ] 更新 NavHost 使用新的独立文件
- [ ] 保持旧路由兼容性处理
- [ ] 更新导入语句

关键点:
- 保持 ViewModel 和 UiState 不变
- 保持对话框逻辑(TrustMode/Emergency/Unpair)
- 保持 Snackbar 和权限请求逻辑

### 3. 移除纯中转页(settings/pairing)

任务:
- [ ] 删除 PairingSubScreen 函数
- [ ] 删除 PAIRING 路由的 composable 定义
- [ ] 添加旧路由重定向到 CAPABILITIES

### 4. 基本文案优化

已优化:
- ✅ 外观设置: "系统动态取色" → "跟随壁纸取色"
- ✅ 安全设置: "WASM 沙箱" → "隔离环境"

待优化:
- [ ] 连接设置: "Gateway" → "OAI Core"
- [ ] 能力设置: 移除技术术语(primitive, kernel)
- [ ] 通知设置: 统一命名(任务通知/会话气泡)

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

- **已创建文件**: 4/11 (36%)
- **已提交commits**: 4个
- **预估剩余时间**: 12-16小时
- **当前优先级**: P0任务

## 🎯 下一步行动

1. 等待 Workflow 完成(正在创建主览和其他页面)
2. 手动创建 CapabilityAuthSettingsScreen.kt(最复杂)
3. 创建 NotificationSettingsScreen.kt(整合三套系统)
4. 创建 ConnectionSettingsScreen.kt 和 AdvancedSettingsScreen.kt
5. 更新主 SettingsScreen.kt 集成所有新文件
6. 测试编译和功能完整性
7. 提交最终PR到 dev 分支

## 📝 备注

- 所有新文件都在 `settings/` 包下
- 旧路由保留兼容性(标记废弃)
- 功能不丢失,数据持久化不变
- Material 3 设计规范统一
- 文案自然化,消除技术术语
