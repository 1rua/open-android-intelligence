package com.openandroidintelligence.mobile.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.plugin.ui.*

/**
 * 原生插件界面（Trust Mode）的宿主外壳。
 *
 * 三条边界：
 * 1. **安全区**：`Scaffold` + `safeDrawingPadding()`，插件界面不再压进状态栏或手势条；
 *    这里的 `contentWindowInsets` 显式置零，避免与 `safeDrawingPadding` 重复扣一次。
 * 2. **恢复入口永远在宿主手里**：安全提示与「恢复宿主界面」按钮由宿主渲染，
 *    始终位于 guest 组合之外——插件界面再怎么画也覆盖不了这条退路。
 * 3. **高度有界**：宿主信息区自身可滚动（小屏/大字号时不裁切），插件表面则放在
 *    `weight(1f)` 的有界区域里。不能把 guest 放进 `verticalScroll`：插件可能渲染
 *    `LazyColumn`/自身滚动容器，无限高约束会直接触发 Compose 的测量崩溃。
 */
@Composable
fun NativePluginSurface(
    host: ProductionPluginHost,
    id: String,
    provider: NativeUiProvider,
    context: NativeUiContext,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 宿主安全提示：在插件画面之上如实说明「共享进程/UID」的后果与退出路径。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = Dimensions.ScreenHorizontal,
                        vertical = Dimensions.SpaceSmall,
                    ),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                SettingsSectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = LocalAppStateColors.current.warning,
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
                            Text(
                                text = "正在显示原生插件界面",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "该界面由已信任的原生插件渲染，与宿主运行在同一进程、共享同一 UID，" +
                                    "因此拥有与本应用相同的系统权限。请只在插件来源可信、且已开启开发者信任模式时使用；" +
                                    "随时可以返回宿主界面，插件设置与已装数据不受影响。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { host.selectNativeUi(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimensions.MinimumTouchTarget),
                ) {
                    Text("恢复宿主界面")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // guest 区域：有界高度，交给插件自己布局与滚动。
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                key(id) {
                    host.beginNativeFrame(id)
                    DisposableEffect(id) { onDispose { host.completeNativeFrame(id) } }
                    provider.Render(context)
                    SideEffect { host.completeNativeFrame(id) }
                }
            }
        }
    }
}
