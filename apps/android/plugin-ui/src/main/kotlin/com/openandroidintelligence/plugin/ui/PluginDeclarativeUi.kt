package com.openandroidintelligence.plugin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 宿主注入的动作出口（边界：渲染器自身不得有任何存储/网络副作用）。
 *
 * 每个交互只提交结构化 action ID 与已验证的值（契约 §8:245），
 * 由平台内核在 sink 的实现方再次执行授权检查。
 */
fun interface PluginUiActionSink {
    fun onAction(componentId: String, action: UiActionId, value: PluginUiActionValue)
}

/** 动作载荷的封闭集合：组件种类决定载荷种类，渲染器不做自由文本透传。 */
sealed interface PluginUiActionValue {
    /** toggle 提交的新开关值。 */
    data class ToggleValue(val checked: Boolean) : PluginUiActionValue

    /** select 提交的选项 id。 */
    data class ChoiceValue(val optionId: String) : PluginUiActionValue

    /** permission-request / capability-picker 提交的能力标识。 */
    data class CapabilityValue(val capability: String) : PluginUiActionValue

    /** 无载荷动作（button）。 */
    data object NoneValue : PluginUiActionValue
}

/**
 * 渲染前的最终安全闸（fail closed，契约 §8:247 与 §5:156）。
 *
 * [UiComponent]/[UiContribution] 是公开数据类，可绕过 [DeclarativeUiSchema.parse]
 * 直接构造。渲染器入口因此重新断言：深度/数量上限、动作-组件白名单组合、
 * 文本与能力字段无禁词且不超长。任何违规抛 [UiRejected] 并拒绝整个
 * contribution，绝不降级成文本或忽略。未知组件种类由 sealed 穷尽 when 阻断：
 * 新增组件种类而未同步渲染器时编译期即失败。
 */
object PluginDeclarativeUiPolicy {
    fun validateForRendering(contribution: UiContribution) {
        DeclarativeUiSchema.checkText(contribution.id, "id")
        contribution.title?.let { DeclarativeUiSchema.checkText(it, "title") }
        validateComponent(contribution.root, depth = 1, budget = ComponentBudget())
    }

    private class ComponentBudget {
        private var remaining = DeclarativeUiSchema.MAX_COMPONENTS
        fun take(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    private fun validateComponent(component: UiComponent, depth: Int, budget: ComponentBudget) {
        if (depth > DeclarativeUiSchema.MAX_DEPTH) throw UiRejected("UI_TOO_DEEP")
        if (!budget.take()) throw UiRejected("UI_TOO_MANY_COMPONENTS")

        component.action?.let { action ->
            val kind = kindOf(component)
            if (action !in ALLOWED_ACTIONS.getValue(kind)) {
                throw UiRejected("UI_ACTION_NOT_ALLOWED:${kind.id}:${action.id}")
            }
        }

        when (component) {
            is UiComponent.Section -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                for (child in component.children) validateComponent(child, depth + 1, budget)
            }

            is UiComponent.Text -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                DeclarativeUiSchema.checkText(component.value, "value")
            }

            is UiComponent.Status -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                DeclarativeUiSchema.checkText(component.value, "value")
            }

            is UiComponent.Toggle -> component.label?.let {
                DeclarativeUiSchema.checkText(it, "label")
            }

            is UiComponent.Select -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                DeclarativeUiSchema.checkText(component.value, "value")
                for (option in component.options) {
                    DeclarativeUiSchema.checkText(option.id, "option.id")
                    DeclarativeUiSchema.checkText(option.label, "option.label")
                }
            }

            is UiComponent.Button -> component.label?.let {
                DeclarativeUiSchema.checkText(it, "label")
            }

            is UiComponent.PermissionRequest -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                DeclarativeUiSchema.checkCapability(component.capability)
            }

            is UiComponent.CapabilityPicker -> {
                component.label?.let { DeclarativeUiSchema.checkText(it, "label") }
                DeclarativeUiSchema.checkCapability(component.capability)
            }
        }
    }

    private fun kindOf(component: UiComponent): UiComponentKind = when (component) {
        is UiComponent.Section -> UiComponentKind.SECTION
        is UiComponent.Text -> UiComponentKind.TEXT
        is UiComponent.Status -> UiComponentKind.STATUS
        is UiComponent.Toggle -> UiComponentKind.TOGGLE
        is UiComponent.Select -> UiComponentKind.SELECT
        is UiComponent.Button -> UiComponentKind.BUTTON
        is UiComponent.PermissionRequest -> UiComponentKind.PERMISSION_REQUEST
        is UiComponent.CapabilityPicker -> UiComponentKind.CAPABILITY_PICKER
    }
}

/**
 * 把一个已解析的 [UiContribution] 渲染为 Compose 组件树（宿主唯一入口）。
 * 宿主负责解析、状态回灌（重建 contribution）与动作授权；渲染器无状态。
 */
@Composable
fun PluginDeclarativeUi(
    contribution: UiContribution,
    onAction: PluginUiActionSink,
    modifier: Modifier = Modifier,
) {
    PluginDeclarativeUiPolicy.validateForRendering(contribution)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        contribution.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        RenderComponent(component = contribution.root, onAction = onAction)
    }
}

@Composable
private fun RenderComponent(component: UiComponent, onAction: PluginUiActionSink) {
    when (component) {
        is UiComponent.Section -> RenderSection(component, onAction)
        is UiComponent.Text -> RenderText(component)
        is UiComponent.Status -> RenderStatus(component)
        is UiComponent.Toggle -> RenderToggle(component, onAction)
        is UiComponent.Select -> RenderSelect(component, onAction)
        is UiComponent.Button -> RenderButton(component, onAction)
        is UiComponent.PermissionRequest -> RenderPermissionRequest(component, onAction)
        is UiComponent.CapabilityPicker -> RenderCapabilityPicker(component, onAction)
    }
}

/** section → 分组容器。 */
@Composable
private fun RenderSection(section: UiComponent.Section, onAction: PluginUiActionSink) {
    SettingsSectionCard {
        section.label?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Dimensions.SpaceTiny),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
            section.children.forEach { child -> RenderComponent(child, onAction) }
        }
    }
}

/** text → 只读文本行。 */
@Composable
private fun RenderText(text: UiComponent.Text) {
    SettingsListItem(
        headline = text.label ?: text.value,
        supporting = if (text.label != null) text.value else null,
    )
}

/** status → 状态行；severity 只影响取自 M3 色角色的颜色。 */
@Composable
private fun RenderStatus(status: UiComponent.Status) {
    val valueColor = when (status.severity) {
        UiSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        UiSeverity.WARN -> MaterialTheme.colorScheme.tertiary
        UiSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
    SettingsListItem(
        headline = status.label ?: status.value,
        supporting = status.value,
        trailing = {
            Text(
                text = status.value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
            )
        },
    )
}

/** toggle → 开关行；点击提交 set-setting，新值经 sink 交给宿主。 */
@Composable
private fun RenderToggle(toggle: UiComponent.Toggle, onAction: PluginUiActionSink) {
    val enabled = toggle.action != null
    SettingsListItem(
        headline = toggle.label ?: toggle.id,
        enabled = enabled,
        onClick = if (enabled) {
            {
                onAction.onAction(
                    componentId = toggle.id,
                    action = UiActionId.SET_SETTING,
                    value = PluginUiActionValue.ToggleValue(!toggle.value),
                )
            }
        } else {
            null
        },
        trailing = { Switch(checked = toggle.value, onCheckedChange = null, enabled = enabled) },
    )
}

/** select → 单选行组；选中项由宿主回灌的 value 驱动。 */
@Composable
private fun RenderSelect(select: UiComponent.Select, onAction: PluginUiActionSink) {
    Column {
        select.label?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Dimensions.SpaceTiny),
            )
        }
        select.options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onAction.onAction(
                            componentId = select.id,
                            action = UiActionId.SET_SETTING,
                            value = PluginUiActionValue.ChoiceValue(option.id),
                        )
                    }
                    .padding(vertical = Dimensions.SpaceTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.id == select.value, onClick = null)
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** button → 动作行；未声明 action 的按钮只展示、不可点。 */
@Composable
private fun RenderButton(button: UiComponent.Button, onAction: PluginUiActionSink) {
    val action = button.action
    SettingsListItem(
        headline = button.label ?: button.id,
        enabled = action != null,
        onClick = if (action != null) {
            {
                onAction.onAction(
                    componentId = button.id,
                    action = action,
                    value = PluginUiActionValue.NoneValue,
                )
            }
        } else {
            null
        },
    )
}

/** permission-request → 权限申请行；点击提交 request-grant 与能力标识。 */
@Composable
private fun RenderPermissionRequest(
    request: UiComponent.PermissionRequest,
    onAction: PluginUiActionSink,
) {
    val action = request.action
    SettingsListItem(
        headline = request.label ?: request.id,
        supporting = request.capability,
        enabled = action != null,
        onClick = if (action != null) {
            {
                onAction.onAction(
                    componentId = request.id,
                    action = action,
                    value = PluginUiActionValue.CapabilityValue(request.capability),
                )
            }
        } else {
            null
        },
    )
}

/** capability-picker → 能力选择行；点击提交 select-provider 与能力标识。 */
@Composable
private fun RenderCapabilityPicker(
    picker: UiComponent.CapabilityPicker,
    onAction: PluginUiActionSink,
) {
    val action = picker.action
    SettingsListItem(
        headline = picker.label ?: picker.id,
        supporting = picker.capability,
        enabled = action != null,
        onClick = if (action != null) {
            {
                onAction.onAction(
                    componentId = picker.id,
                    action = action,
                    value = PluginUiActionValue.CapabilityValue(picker.capability),
                )
            }
        } else {
            null
        },
    )
}
