package com.openandroidintelligence.mobile.plugins

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import com.openandroidintelligence.plugin.ui.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.gateway.schema.Json
import kotlinx.coroutines.*

/** Signed card actions are authored by the host and pass through the current kernel grant. */
@Composable fun ProtectedConversationCards(host:ProductionPluginHost,accountId:String?,openSettings:()->Unit) {
    val revision by host.revision.collectAsState()
    val cards=remember(revision,accountId) { if(accountId==null) emptyList() else host.conversationCards() }
    val scope=rememberCoroutineScope()
    var plugin by remember(accountId) { mutableStateOf<String?>(null) }
    var capability by remember(accountId) { mutableStateOf("") }
    var parameters by remember(accountId) { mutableStateOf("{}") }
    var busy by remember(accountId) { mutableStateOf(false) }
    var result by remember(accountId) { mutableStateOf<String?>(null) }
    Column {
        cards.forEach { (id,card) -> key(id,card.id) {
            PluginDeclarativeUi(card,PluginUiActionSink { component,action,value ->
                when(action) {
                    UiActionId.REQUEST_GRANT,UiActionId.SELECT_PROVIDER -> openSettings()
                    UiActionId.INVOKE_CAPABILITY,UiActionId.REFRESH_CARD -> {
                        plugin=id;capability=host.entries(id).firstOrNull()?.key.orEmpty();parameters="{}"
                    }
                    UiActionId.SET_SETTING -> runCatching { host.setting(id,accountId ?: error("PAIRING_REQUIRED"),component,when(value) {
                        is PluginUiActionValue.ToggleValue -> value.checked
                        is PluginUiActionValue.ChoiceValue -> value.optionId
                        else -> error("UI_ACTION_INVALID")
                    }) }.onFailure { result="设置未保存，请检查当前插件授权。" }
                }
            })
        } }
        result?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    }
    plugin?.let { id -> AlertDialog(onDismissRequest={ if(!busy) plugin=null },title={Text("确认设备能力调用")},
        text={ Column {
            host.entries(id).forEach { entry -> TextButton(enabled=!busy,onClick={capability=entry.key}) { Text(entry.key) } }
            Text("当前选择：$capability")
            OutlinedTextField(parameters,{parameters=it},enabled=!busy,label={Text("参数 JSON")})
        } },confirmButton={ TextButton(enabled=!busy && capability.isNotBlank(),onClick={
            val account=accountId ?: return@TextButton
            val entry=host.entries(id).firstOrNull { it.key==capability } ?: return@TextButton
            val args=runCatching { Json.parse(parameters) }.getOrElse { result="参数 JSON 无效";return@TextButton }
            busy=true;scope.launch { try {
                val output=withContext(Dispatchers.IO) { host.invoke(entry.identity,account,entry.key,args,"ui_"+java.util.UUID.randomUUID()) }
                result=output.decodeToString().take(4000);plugin=null
            } catch(c:CancellationException) {throw c} catch(_:Exception) {result="调用未完成，请检查当前授权、系统权限和参数。"} finally{busy=false} }
        }) {Text(if(busy) "执行中" else "执行")}},dismissButton={TextButton(enabled=!busy,onClick={plugin=null}){Text("取消")}}) }
}
