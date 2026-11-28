package com.openandroidintelligence.screen

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.ScreenBounds
import com.openandroidintelligence.control.ScreenNode

internal const val SYSTEM_UI = "com.android.systemui"
private const val NETWORK_SPEED_LABEL = "$SYSTEM_UI:id/network_speed_contentview"
private const val STATUS_BAR_ROOT = "$SYSTEM_UI:id/status_bar"
private const val TEXT_VIEW = "android.widget.TextView"

/** 三星状态栏网速标签独立刷新。只识别已确认的系统、窗口和无交互文字组合。 */
internal fun isStatusBarWindow(type: Int, packageName: String?, bounds: ScreenBounds, active: Boolean, focused: Boolean): Boolean =
    type == AccessibilityWindowInfo.TYPE_SYSTEM && packageName == SYSTEM_UI && bounds.top == 0 && !active && !focused

internal fun ScreenNode.isNetworkSpeedLabel(): Boolean =
    resourceId == NETWORK_SPEED_LABEL && className == TEXT_VIEW &&
        !clickable && !longClickable && !scrollable && !editable && !focused && !selected && !password

@Suppress("DEPRECATION")
internal fun AccessibilityEvent.isNetworkSpeedTextEvent(): Boolean {
    if (packageName?.toString() != SYSTEM_UI) return false
    if (eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
        (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || contentChangeTypes != AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)) return false
    val node = try { source } catch (_: RuntimeException) { null } ?: return false
    return try { node.windowId == windowId && node.isNetworkSpeedLabel() } catch (_: RuntimeException) { false }
    finally { node.recycle() }
}

@Suppress("DEPRECATION")
internal fun AccessibilityEvent.isStatusBarSubtreeEvent(): Boolean {
    if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        contentChangeTypes != AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE || packageName?.toString() != SYSTEM_UI) return false
    val node = try { source } catch (_: RuntimeException) { null } ?: return false
    return try {
        node.windowId == windowId && node.packageName?.toString() == SYSTEM_UI &&
            node.viewIdResourceName == STATUS_BAR_ROOT && node.className?.toString() == "android.widget.FrameLayout" &&
            !node.isClickable && !node.isLongClickable && !node.isScrollable && !node.isEditable && !node.isFocused
    } catch (_: RuntimeException) { false }
    finally { node.recycle() }
}

private fun AccessibilityNodeInfo.isNetworkSpeedLabel(): Boolean =
    windowId >= 0 && packageName?.toString() == SYSTEM_UI && viewIdResourceName == NETWORK_SPEED_LABEL &&
        className?.toString() == TEXT_VIEW && childCount == 0 && !isClickable && !isLongClickable &&
        !isScrollable && !isEditable && !isFocused && !isSelected && !isPassword
