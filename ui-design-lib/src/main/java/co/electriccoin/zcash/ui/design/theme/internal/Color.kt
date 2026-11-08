@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.internal

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import co.electriccoin.zcash.ui.design.theme.ExtendedColors

// Static fallbacks also serve previews and callers outside composition.
internal val LightColorPalette = lightColorScheme()
internal val DarkColorPalette = darkColorScheme()
internal val OledColorPalette = DarkColorPalette.copy(background = Color.Black, surface = Color.Black)

internal val LightExtendedColorPalette = materialExtendedColors(LightColorPalette, dark = false)
internal val DarkExtendedColorPalette = materialExtendedColors(DarkColorPalette, dark = true)
internal val OledExtendedColorPalette = materialExtendedColors(OledColorPalette, dark = true)

@Suppress("UNUSED_PARAMETER")
internal fun materialExtendedColors(s: ColorScheme, dark: Boolean): ExtendedColors {
    val disabledContent = s.onSurface.copy(alpha = 0.38f)
    val disabledContainer = s.onSurface.copy(alpha = 0.12f)
    val appBar = DefaultTopAppBarColors(s.background, s.onSurface, s.onSurface, s.onSurfaceVariant, s.onSurface)
    fun button(container: Color, content: Color, stroke: Color) = DefaultButtonColors(
        containerColor = container,
        disabledContainerColor = disabledContainer,
        shadowColor = Color.Transparent,
        disabledShadowColor = Color.Transparent,
        textColor = content,
        disabledTextColor = disabledContent,
        strokeColor = stroke,
        disabledStrokeColor = disabledContainer,
        shadowStrokeColor = Color.Transparent,
        shadowDisabledStrokeColor = Color.Transparent,
    )
    return ExtendedColors(
        primaryColor = s.background,
        secondaryColor = s.onBackground,
        backgroundColor = s.background,
        gridColor = s.surfaceContainerLow,
        circularProgressBarSmall = s.primary,
        circularProgressBarSmallDark = s.primary,
        circularProgressBarScreen = s.primary,
        linearProgressBarTrack = s.surfaceContainerHighest,
        linearProgressBarBackground = s.primary,
        textPrimary = s.onSurface,
        textSecondary = s.inverseOnSurface,
        textDescription = s.onSurfaceVariant,
        textDisabled = disabledContent,
        textFieldHint = s.onSurfaceVariant,
        textFieldWarning = s.error,
        textFieldFrame = s.outline,
        textDescriptionDark = s.onSurface,
        layoutStroke = s.outlineVariant,
        layoutStrokeSecondary = s.outlineVariant,
        overlay = s.scrim.copy(alpha = 0.32f),
        overlayProgressBar = s.onPrimary,
        reference = s.primary,
        welcomeAnimationColor = s.surface,
        complementaryColor = s.primary,
        primaryDividerColor = s.outlineVariant,
        secondaryDividerColor = s.outlineVariant,
        tertiaryDividerColor = s.outlineVariant,
        panelBackgroundColor = s.surfaceContainerLow,
        panelBackgroundColorActive = s.surface,
        cameraDisabledBackgroundColor = s.scrim,
        cameraDisabledFrameColor = s.inverseOnSurface,
        historyBackgroundColor = s.surfaceContainerLow,
        historyMessageBubbleColor = s.secondaryContainer,
        historyMessageBubbleStrokeColor = s.outlineVariant,
        historyRedColor = s.error,
        historySyncingColor = s.surfaceContainerLow,
        primaryButtonColors = button(s.primary, s.onPrimary, s.primary),
        secondaryButtonColors = button(s.secondaryContainer, s.onSecondaryContainer, s.outline),
        tertiaryButtonColors = button(s.tertiaryContainer, s.onTertiaryContainer, s.tertiaryContainer),
        topAppBarColors = appBar,
        transparentTopAppBarColors = appBar.copyColors(containerColor = Color.Transparent),
    )
}

@Suppress("CompositionLocalAllowlist")
internal val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            primaryColor = Color.Unspecified,
            secondaryColor = Color.Unspecified,
            backgroundColor = Color.Unspecified,
            gridColor = Color.Unspecified,
            circularProgressBarScreen = Color.Unspecified,
            circularProgressBarSmall = Color.Unspecified,
            circularProgressBarSmallDark = Color.Unspecified,
            linearProgressBarTrack = Color.Unspecified,
            linearProgressBarBackground = Color.Unspecified,
            textPrimary = Color.Unspecified,
            textSecondary = Color.Unspecified,
            textDisabled = Color.Unspecified,
            textFieldHint = Color.Unspecified,
            textFieldWarning = Color.Unspecified,
            textFieldFrame = Color.Unspecified,
            textDescription = Color.Unspecified,
            textDescriptionDark = Color.Unspecified,
            layoutStroke = Color.Unspecified,
            layoutStrokeSecondary = Color.Unspecified,
            overlay = Color.Unspecified,
            overlayProgressBar = Color.Unspecified,
            reference = Color.Unspecified,
            welcomeAnimationColor = Color.Unspecified,
            complementaryColor = Color.Unspecified,
            primaryDividerColor = Color.Unspecified,
            secondaryDividerColor = Color.Unspecified,
            tertiaryDividerColor = Color.Unspecified,
            panelBackgroundColor = Color.Unspecified,
            panelBackgroundColorActive = Color.Unspecified,
            cameraDisabledBackgroundColor = Color.Unspecified,
            cameraDisabledFrameColor = Color.Unspecified,
            historyBackgroundColor = Color.Unspecified,
            historyRedColor = Color.Unspecified,
            historySyncingColor = Color.Unspecified,
            historyMessageBubbleColor = Color.Unspecified,
            historyMessageBubbleStrokeColor = Color.Unspecified,
            topAppBarColors = DefaultTopAppBarColors(),
            transparentTopAppBarColors = DefaultTopAppBarColors(),
            primaryButtonColors = DefaultButtonColors(),
            secondaryButtonColors = DefaultButtonColors(),
            tertiaryButtonColors = DefaultButtonColors(),
        )
    }
