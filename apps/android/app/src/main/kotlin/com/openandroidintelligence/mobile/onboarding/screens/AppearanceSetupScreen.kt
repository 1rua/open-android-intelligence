package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.onboarding.components.OnboardingStepHeader
import com.openandroidintelligence.mobile.settings.appearance.AppearanceEditorContent

@Composable
fun AppearanceSetupScreen(
    settings: AppearanceSettings,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("onboarding-appearance")) {
        OnboardingStepHeader(categoryTitle = "APPEARANCE", stepIndex = 2, totalSteps = 5, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .widthIn(max = Dimensions.FormWidth).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = Dimensions.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Text("PERSONALIZATION", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("外观选项", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.9).sp, color = MaterialTheme.colorScheme.onSurface)
            Text("选择适合你的界面主题与色彩风格", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Dimensions.SpaceSmall))
            AppearanceEditorContent(
                settings = settings,
                onSetTheme = onSetTheme,
                onSetDynamicColor = onSetDynamicColor,
                onSetPalette = onSetPalette,
                showAdvanced = false,
                allowPaletteWhileDynamic = true,
            )
            Spacer(Modifier.height(Dimensions.SpaceLarge))
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding().padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.SpaceMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onNext,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.widthIn(max = Dimensions.FormWidth).fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)
                    .testTag("onboarding-appearance-next"),
            ) {
                Text("下一步", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(Dimensions.SpaceSmall))
                Text("→", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
