# SoM 图像捕获与本机窗口保护

本轮仅实现和验证 Android 本地源码、Robolectric 与替身帧路径，未在真实手机或日常 Host 运行。

## 官方接口依据

- [AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))：API 30 起获取指定显示屏的截图；没有按窗口排除浮层的承诺。
- [takeScreenshotOfWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))：API 34 起可捕获被无障碍浮层覆盖的目标窗口，不把上层浮层内容合入截图。
- [ScreenshotResult](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult)：时间戳使用 `SystemClock.uptimeMillis()`；不再需要 `HardwareBuffer` 时必须关闭。
- [FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)：窗口内容不进入截图或不安全显示；本轮保留此系统保护，同时对最终图像进行本地遮蔽。
- [MediaProjection](https://developer.android.com/media/grow/media-projection)：API 32 起等比居中可能产生留边；单应用捕获从 API 34 起提供。不能把旧版投影尺寸或默认显示大小直接当作目标窗口坐标。

## 实现边界

API 34 继续按实际目标窗口取帧，输出坐标偏移来自对应节点快照中的窗口边界。API 31–33 使用整屏无障碍取帧；不隐藏胶囊、不请求焦点、不用固定延迟推断合成结束。胶囊保持 `FLAG_SECURE`、不可聚焦和本机急停。窗口动画、窗口/按钮 Z 轴阴影、模糊与变换被禁用；保护快照检查当前属性与实际布局，保存服务存活期间已占用的整个顶部安全带。局部窗口移动或缩小不会立即丢弃旧遮蔽区域。

每帧绑定显示几何、旋转、完整节点快照、胶囊保护代次与真实捕获时间戳。初始布局未完成、保护代次变化、截图早于请求或最近本机布局、显示旋转/尺寸变化、帧像素尺寸与快照不符、节点截断时拒绝输出。服务存活期间发生旋转后，旧版保护历史不能核验成同一坐标系时保持不可用，直到新服务拥有者建立新历史；不会猜测旧区域应如何投影到新显示。

密码与本机窗口区域在标注后最后用不透明黑色覆盖；与这些区域相交的节点不编号。安全带覆盖整幅截图时拒绝，不能把全黑图冒充有效 SoM。无损 WebP 编码后重新解码核对尺寸。取消、授权变化或最终节点快照变化会拒绝交付并清零未发布编码字节，位图按所有权释放。

顶部安全带是本应用拥有且关闭外绘效果的窗口几何超集，不是对所有 OEM 合成器行为的绝对保证。没有公开可核验窗口几何、未知阴影/变换或第三方额外浮层时不声称安全排除。真实设备仍需验证系统 `FLAG_SECURE`、取帧实际尺寸、旋转与窗口效果；Robolectric 像素测试只证明本地最终遮蔽、映射、编码与拒绝逻辑。

## 验证记录

`1c77f327` 首轮执行 63 项，61 通过；真实位图/WebP 像素 20、API 路由 4、原生屏幕 4、节点后端 16、缓冲区 13 均通过，胶囊 2 项失败。`88ed4fc1` 将不可查询显示几何限制为截图拒绝、不再中断本机确认，复验像素 20 通过、胶囊 5/6；最后失败为 Robolectric 服务未提供显示几何。`3f802dab` 仅给该测试明确 `WindowMetrics`，胶囊 6/6 通过。以上是分提交的 focused 证据，不声称最新提交同轮执行过全部 63 项。

像素测试实际创建 Bitmap、生成并解码 WebP，检查密码/本机窗口黑色遮蔽、节点映射、窗口偏移、稳定旋转坐标、帧失效和取消后的回收；系统路由测试只给截图错误回调，不伪造硬件像素。未完成真实设备、合成器和跨进程取帧验收。
