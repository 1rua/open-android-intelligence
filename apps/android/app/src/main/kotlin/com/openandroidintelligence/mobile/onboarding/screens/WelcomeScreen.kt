package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.onboarding.components.OrbitalDiagram
import com.openandroidintelligence.mobile.onboarding.components.TelemetryRow
import com.openandroidintelligence.mobile.onboarding.components.halftone
import com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 欢迎与应用介绍页。
 *
 * 视觉语言是「复古未来主义航天工程平面设计」：
 * 上方一张工程制图式的轨道示意图（手机 ↔ Gateway 直连），中间超粗几何字标，
 * 下方一张参数表。**装饰全部依附真实信息**——参数表四行分别对应：
 * 支持的宿主、协议版本、凭据存储位置、设备能力默认策略，没有一行是填充文案。
 *
 * 布局要点：
 * - 内容区可滚动（小屏 + 大字体时参数表会超过一屏），主按钮固定在滚动区之外；
 * - 轨道图超出左右边界并裁剪，形成「图继续延伸到屏幕外」的工程图感；
 * - 全部字号用 sp、间距用 [Dimensions] 令牌，跟随系统字体缩放。
 */
@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val accent = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding-welcome"),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .widthIn(max = Dimensions.FormWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
        ) {
            // ===== 轨道示意 + 品牌字标 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WelcomeOrbitHeight),
                contentAlignment = Alignment.Center,
            ) {
                OrbitalDiagram(
                    modifier = Modifier
                        .fillMaxSize()
                        .halftone(alpha = 0.22f, dotSpacing = 7.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Text(
                            text = "OAI",
                            fontSize = 54.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-2.4).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // 图标上的那颗橙点：全页唯一的高饱和色，位置与图标一致（字标右上）。
                        Box(
                            modifier = Modifier
                                .padding(bottom = 34.dp)
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                    Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                    Text(
                        text = "OPEN ANDROID INTELLIGENCE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 3.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 左右端点标注：说明这条连线两端是什么，而不是纯装饰。
                Text(
                    text = "DEVICE",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = Dimensions.ScreenHorizontal),
                )
                Text(
                    text = "GATEWAY",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Dimensions.ScreenHorizontal),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = Dimensions.ScreenHorizontal),
            ) {
                // ===== Eyebrow 标签 =====
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Box(
                    modifier = Modifier
                        .width(Dimensions.SpaceMedium)
                        .height(Dimensions.StrokeHairline)
                        .background(accent),
                )
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                Text(
                    text = "UNIFIED ANDROID CLIENT",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.8.sp,
                    color = accent,
                )

                // ===== 主标题 =====
                Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
                Text(
                    text = "连接一个\nOAI Core",
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.2).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // ===== 定位说明 =====
                Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
                Text(
                    text = "一个手机连接入口，由 OAI Core 接入多个自托管 Agent。" +
                        "原生会话与设备插件独立管理，设备能力始终需要你的账号授权。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ===== 参数表：四行真实信息 =====
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimensions.StrokeHairline)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                TelemetryRow(
                    key = "Setup",
                    value = GatewayTutorials.ALL.joinToString(" · ") { it.shortName },
                )
                TelemetryRow(key = "Protocol", value = "Gateway Protocol v2.1 · 数据直连")
                TelemetryRow(key = "Storage", value = "凭据存于 Android Keystore · 自托管 Core")
                TelemetryRow(key = "Device", value = "设备能力默认拒绝 · 逐项授权")

                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
            }
        }

        // ===== 主操作：固定在滚动区之外，键盘/大字体下始终可见 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(surface)
                .navigationBarsPadding()
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceMedium,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onStart,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = Dimensions.FormWidth)
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)
                    .testTag("onboarding-welcome-start"),
            ) {
                Text(
                    text = "开始使用",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
            Text(
                text = "约 2 分钟 · 可随时跳过",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 轨道图高度：约等于原型 226dp，给字标与标注留出呼吸位。 */
private val WelcomeOrbitHeight = 226.dp
