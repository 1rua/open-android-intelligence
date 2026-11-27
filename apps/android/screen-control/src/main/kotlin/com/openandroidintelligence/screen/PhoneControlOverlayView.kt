package com.openandroidintelligence.screen

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.openandroidintelligence.control.remote.PhoneControlOverlayColors
import kotlin.math.roundToInt
import android.R.color as SystemColor

internal fun systemPhoneControlOverlayColors(context: Context): PhoneControlOverlayColors {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    fun color(light: Int, night: Int) = context.getColor(if (dark) night else light)
    if (Build.VERSION.SDK_INT >= 34) return PhoneControlOverlayColors(
        surfaceContainerHigh = color(SystemColor.system_surface_container_high_light, SystemColor.system_surface_container_high_dark),
        onSurface = color(SystemColor.system_on_surface_light, SystemColor.system_on_surface_dark),
        onSurfaceVariant = color(SystemColor.system_on_surface_variant_light, SystemColor.system_on_surface_variant_dark),
        primary = color(SystemColor.system_primary_light, SystemColor.system_primary_dark),
        onPrimary = color(SystemColor.system_on_primary_light, SystemColor.system_on_primary_dark),
        secondaryContainer = color(SystemColor.system_secondary_container_light, SystemColor.system_secondary_container_dark),
        onSecondaryContainer = color(SystemColor.system_on_secondary_container_light, SystemColor.system_on_secondary_container_dark),
        errorContainer = color(SystemColor.system_error_container_light, SystemColor.system_error_container_dark),
        onErrorContainer = color(SystemColor.system_on_error_container_light, SystemColor.system_on_error_container_dark),
    )
    // Android 12/13 提供动态色阶。错误容器使用 Material 3 的基准角色。
    return PhoneControlOverlayColors(
        surfaceContainerHigh = color(SystemColor.system_neutral1_50, SystemColor.system_neutral1_800),
        onSurface = color(SystemColor.system_neutral1_900, SystemColor.system_neutral1_100),
        onSurfaceVariant = color(SystemColor.system_neutral2_700, SystemColor.system_neutral2_200),
        primary = color(SystemColor.system_accent1_600, SystemColor.system_accent1_200),
        onPrimary = color(SystemColor.system_accent1_0, SystemColor.system_accent1_800),
        secondaryContainer = color(SystemColor.system_accent2_100, SystemColor.system_accent2_700),
        onSecondaryContainer = color(SystemColor.system_accent2_900, SystemColor.system_accent2_100),
        errorContainer = if (dark) 0xff8c1d18.toInt() else 0xfff9dedc.toInt(),
        onErrorContainer = if (dark) 0xfff9dedc.toInt() else 0xff410e0b.toInt(),
    )
}

internal class PhoneControlOverlayView(
    context: Context,
    colors: PhoneControlOverlayColors,
    title: String,
    detail: String?,
    decide: ((Boolean) -> Unit)?,
    stop: () -> Unit,
) : LinearLayout(context) {
    private val heading = TextView(context).apply {
        text = title
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(colors.onSurface)
    }
    private var detailView: ScrollView? = null
    private var allowButton: Button? = null
    private var denyButton: Button? = null
    private val stopButton = button(R.string.remote_phone_stop, colors.errorContainer, colors.onErrorContainer, stop)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = fill(colors.surfaceContainerHigh, 20)
        withoutShadow()
        clipChildren = true
        clipToPadding = true
        addView(heading)
        if (detail != null) {
            val scroll = ScrollView(context).apply {
                addView(TextView(context).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(colors.onSurfaceVariant)
                })
            }
            detailView = scroll
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels / 3).apply {
                topMargin = dp(8)
            })
        }
        if (decide != null) {
            allowButton = button(R.string.remote_phone_allow, colors.primary, colors.onPrimary) { decide(true) }
                .also(::addAction)
            denyButton = button(R.string.remote_phone_deny, colors.secondaryContainer, colors.onSecondaryContainer) { decide(false) }
                .also(::addAction)
        }
        addAction(stopButton)
    }

    fun showExecuting(title: String) {
        retireConfirmation()
        heading.text = title
    }

    fun deactivate() {
        retireConfirmation()
        stopButton.disableAction()
        visibility = GONE
    }

    private fun retireConfirmation() {
        allowButton?.let { it.disableAction(); removeView(it) }
        denyButton?.let { it.disableAction(); removeView(it) }
        detailView?.let(::removeView)
        allowButton = null
        denyButton = null
        detailView = null
    }

    private fun addAction(button: Button) {
        addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun button(label: Int, container: Int, content: Int, action: () -> Unit): Button = Button(context).apply {
        withoutShadow()
        backgroundTintList = null
        background = fill(container, 24)
        minHeight = dp(48)
        minimumHeight = dp(48)
        isAllCaps = false
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setText(label)
        setTextColor(content)
        var physicalDown = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> physicalDown = event.deviceId != 0 && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                MotionEvent.ACTION_CANCEL -> physicalDown = false
            }
            false
        }
        setOnClickListener {
            val allowed = physicalDown
            physicalDown = false
            if (allowed) action()
        }
    }

    private fun Button.disableAction() {
        setOnTouchListener(null)
        setOnClickListener(null)
        isClickable = false
        isEnabled = false
        cancelPendingInputEvents()
    }

    private fun View.withoutShadow() {
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        outlineProvider = null
    }

    private fun fill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
