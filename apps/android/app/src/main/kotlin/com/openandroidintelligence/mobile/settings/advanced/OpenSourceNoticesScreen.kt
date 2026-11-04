package com.openandroidintelligence.mobile.settings.advanced

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.components.SettingsCardHeader
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import java.io.IOException

/** 项目 LICENSE 和随 APK 分发的 KaTeX 资源各自的原文；不声称覆盖所有第三方依赖。 */
@Composable
internal fun OpenSourceNoticesScreen(presentation: SettingsPresentation, onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val notices = remember(assets) {
        listOf(
            Notice("Open Android Intelligence", readNotice(assets, "licenses/oai/LICENSE")),
            Notice("KaTeX 0.16.22", readNotice(assets, "katex/LICENSE")),
        )
    }
    SettingsPageScaffold(title = "开源许可证", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            Text(
                "此处展示项目许可证及随应用分发的 KaTeX 许可证原文；并非全部第三方依赖清单。",
                style = MaterialTheme.typography.bodyMedium,
            )
            notices.forEach { notice ->
                SettingsSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        SettingsCardHeader(notice.title)
                        Text(
                            text = notice.text ?: "无法读取此许可证，请查看项目仓库中的原文。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notice.text == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private data class Notice(val title: String, val text: String?)

internal fun readNotice(assets: AssetManager, path: String): String? = try {
    assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: IOException) {
    null
}
