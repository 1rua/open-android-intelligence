# Journey：修复 APK 多次新建与切换

## Results

### Action：点击顶部“新建对话”。 ✅
### Action：Verify：进入一个新的空会话。 ✅
- **关联**：`conv_fc727ac8-44dc-4727-856e-1f5eedfbe0c0`，`native.creation_saved` 和 `create.ui_applied` 均出现。

### Action：再次点击顶部“新建对话”。 ✅
### Action：Verify：进入另一个新的空会话。 ✅
- **关联**：`conv_079de050-b4e5-4a46-a08b-5e6a339b5e03`，`native.creation_saved` 和 `create.ui_applied` 均出现；两个 ID 不同。

### Action：打开会话抽屉并选择此前含两次回复的会话。 ✅
### Action：Verify：该会话的两次 Agent 回复可见。 ✅
- **检查**：两条回复分别具有 `gen_33219c7426a5d07f31d617207f9d9a73d9d736ea` 与 `gen_29d3583d3977da86382fe99844057b12b42efdc6` 的完成状态。此前用户正文不可见的问题仍存在，不因本项通过而覆盖。

### Action：打开会话抽屉并选择刚创建的空会话。 ✅
### Action：Verify：显示所选空会话，不混入此前会话的回复。 ✅
- **边界**：执行了两次新建和一次往返切换；未把它描述为压力测试或并发快速切换验收。
