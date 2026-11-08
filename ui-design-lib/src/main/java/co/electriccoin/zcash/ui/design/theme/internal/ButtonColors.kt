@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.internal

import androidx.compose.ui.graphics.Color

interface ButtonColors {
    val containerColor: Color
    val disabledContainerColor: Color
    val shadowColor: Color
    val disabledShadowColor: Color
    val textColor: Color
    val disabledTextColor: Color
    val strokeColor: Color
    val disabledStrokeColor: Color
    val shadowStrokeColor: Color
    val shadowDisabledStrokeColor: Color
}

internal data class DefaultButtonColors(
    override val containerColor: Color = Color.Unspecified,
    override val disabledContainerColor: Color = Color.Unspecified,
    override val shadowColor: Color = Color.Unspecified,
    override val disabledShadowColor: Color = Color.Unspecified,
    override val textColor: Color = Color.Unspecified,
    override val disabledTextColor: Color = Color.Unspecified,
    override val strokeColor: Color = Color.Unspecified,
    override val disabledStrokeColor: Color = Color.Unspecified,
    override val shadowStrokeColor: Color = Color.Unspecified,
    override val shadowDisabledStrokeColor: Color = Color.Unspecified,
) : ButtonColors
