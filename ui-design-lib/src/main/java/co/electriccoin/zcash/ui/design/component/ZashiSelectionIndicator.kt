package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors

/** Passive indicator; the owning row supplies selection semantics and interaction. */
@Suppress("MagicNumber", "LongMethod")
@Composable
fun ZashiSelectionIndicator(
    isChecked: Boolean,
    isEnabled: Boolean = true,
    circular: Boolean = false,
    useDot: Boolean = false,
) {
    val (background, border, foreground) = selectionIndicatorColors(isChecked, isEnabled)
    Canvas(Modifier.size(if (circular) 20.dp else 16.dp)) {
        val unit = size.width / if (circular) 20f else 16f
        val inset = unit / 2f
        val bounds = Size(size.width - unit, size.height - unit)
        if (circular) {
            drawCircle(background, radius = size.width / 2f - inset)
            drawCircle(border, radius = size.width / 2f - inset, style = Stroke(unit))
        } else {
            val corner = CornerRadius(3.5f * unit)
            drawRoundRect(background, Offset(inset, inset), bounds, corner)
            drawRoundRect(border, Offset(inset, inset), bounds, corner, style = Stroke(unit))
        }
        if (isChecked) {
            if (useDot) {
                drawCircle(foreground, radius = 4f * unit)
            } else {
                val check = Path().apply {
                    if (circular) {
                        moveTo(5.333f * unit, 10f * unit)
                        lineTo(8.25f * unit, 12.917f * unit)
                        lineTo(14.667f * unit, 6.5f * unit)
                    } else {
                        moveTo(4f * unit, 8f * unit)
                        lineTo(6.5f * unit, 10.5f * unit)
                        lineTo(12f * unit, 5f * unit)
                    }
                }
                drawPath(
                    check,
                    foreground,
                    style =
                        Stroke(
                            width = (if (circular) 2f else 1.6666f) * unit,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                )
            }
        }
    }
}

private data class SelectionIndicatorColors(val background: Color, val border: Color, val foreground: Color)

@Composable
private fun selectionIndicatorColors(isChecked: Boolean, isEnabled: Boolean): SelectionIndicatorColors {
    val colors = ZashiColors.Checkboxes
    return when {
        !isEnabled && isChecked -> SelectionIndicatorColors(
            colors.boxOnDisabledBg, colors.boxOnDisabledStroke, colors.boxOnDisabledFg,
        )
        !isEnabled -> SelectionIndicatorColors(
            colors.boxOffDisabledBg, colors.boxOffDisabledStroke, colors.boxOnDisabledFg,
        )
        isChecked -> SelectionIndicatorColors(colors.boxOnBg, colors.boxOnBg, colors.boxOnFg)
        else -> SelectionIndicatorColors(colors.boxOffBg, colors.boxOffStroke, colors.boxOnFg)
    }
}
