package com.openandroidintelligence.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 设置底板包装器（Modal Bottom Sheet），内容主体由标准 M3 [SettingsScreen] 驱动。
 * 保留向后兼容调用，遵循统一的 Material 3 分组规范与 ViewModel 架构。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsBottomSheet(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = Dimensions.SpaceCompact),
            ) {
                Box(
                    modifier = Modifier.size(width = 32.dp, height = 4.dp),
                )
            }
        },
    ) {
        SettingsScreen(
            environment = environment,
            runtime = runtime,
            onBack = onDismissRequest,
        )
    }
}
