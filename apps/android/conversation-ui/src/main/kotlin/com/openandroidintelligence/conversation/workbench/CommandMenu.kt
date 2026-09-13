package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/** 目录只负责发现与填入；原始命令的含义始终由 Agent 解释。 */
@Composable
fun CommandMenu(catalogState: Loadable<AgentCommandCatalog>, query: String, onSelect: (String) -> Unit,
    onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    AnimatedVisibility(query.startsWith("/"), enter = fadeIn(MotionSpecs.fade(reduced)), exit = fadeOut(MotionSpecs.fade(reduced))) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth().padding(horizontal = Dimensions.SpaceMedium).heightIn(max = Dimensions.CommandMenuHeight)) {
            LoadableRegion(catalogState, "此 Gateway 没有提供命令目录，仍可原样发送", onRetry,
                modifier = Modifier.fillMaxWidth().heightIn(max = Dimensions.CommandMenuHeight), ready = { catalog ->
                    val prefix = query.substringBefore(' ')
                    val matched = catalog.commands.filter { it.command.startsWith(prefix, ignoreCase = true) }
                    if (matched.isEmpty()) Text("没有匹配的命令，仍可原样发送", Modifier.padding(Dimensions.SpaceMedium), style = MaterialTheme.typography.bodyMedium)
                    else LazyColumn {
                        items(matched, key = { it.command }) { command ->
                            Surface(onClick = { onSelect(command.command) }, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                ListItem(headlineContent = { Text(command.command, fontFamily = FontFamily.Monospace) },
                                    supportingContent = { Text(command.description) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                            }
                        }
                    }
                })
        }
    }
}
