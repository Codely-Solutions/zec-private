package co.electriccoin.zcash.ui.screen.swap.quote

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.compose.ZashiTooltipBox
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.Spacer
import co.electriccoin.zcash.ui.design.component.ZashiAutoSizeText
import co.electriccoin.zcash.ui.design.component.ZashiIconButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import kotlin.math.ceil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuoteFieldHelp(help: SwapQuoteFieldHelp, tint: Color) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    ZashiTooltipBox(
        state = tooltip,
        tooltip = { PlainTooltip { Text(help.text.getValue(), style = ZashiTypography.textSm) } },
        anchor = {
            ZashiIconButton(
                state = IconButtonState(
                    icon = co.electriccoin.zcash.ui.design.R.drawable.ic_info,
                    contentDescription = help.label,
                    onClick = { scope.launch { tooltip.show() } },
                ),
                iconSize = 20.dp,
                iconTint = tint,
            )
        },
    )
}

@Composable
private fun QuoteInfoLabel(
    item: SwapQuoteInfoItem,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    val label = item.description.getValue()
    val help = item.help
    if (help == null) {
        Text(text = label, modifier = modifier, style = style, color = color)
    } else {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        BoxWithConstraints(modifier) {
            val available = (constraints.maxWidth - with(density) { quoteHelpWidth.roundToPx() }).coerceAtLeast(1)
            val measured = measurer.measure(label, style, constraints = Constraints(maxWidth = available))
            val labelWidth = ceil((0 until measured.lineCount).maxOf {
                measured.getLineRight(it) - measured.getLineLeft(it)
            }).toInt().coerceIn(1, available)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.width(with(density) { labelWidth.toDp() }),
                    style = style,
                    color = color,
                )
                QuoteFieldHelp(help, color)
            }
        }
    }
}

@Composable
fun SwapQuoteInfo(
    item: SwapQuoteInfoItem,
    descriptionStyle: TextStyle = ZashiTypography.textSm,
    descriptionFontWeight: FontWeight = FontWeight.Normal,
    descriptionColor: Color = ZashiColors.Text.textTertiary
) {
    val labelStyle = descriptionStyle.copy(fontWeight = descriptionFontWeight)
    if (item.layout == SwapQuoteInfoLayout.STACKED) {
        StackedSwapQuoteInfo(item, labelStyle, descriptionColor)
    } else if (item.help != null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val labelWidth = measurer.measure(item.description.getValue(), labelStyle, softWrap = false).size.width
            val titleStyle = ZashiTypography.textSm.copy(fontWeight = FontWeight.Medium)
            val titleWidth = measurer.measure(item.title.getValue(), titleStyle, softWrap = false).size.width
            val subtitleWidth = item.subtitle?.let {
                measurer.measure(it.getValue(), ZashiTypography.textXs, softWrap = false).size.width
            } ?: 0
            val helpWidth = with(LocalDensity.current) { quoteHelpWidth.roundToPx() }
            if (labelWidth + helpWidth + maxOf(titleWidth, subtitleWidth) > constraints.maxWidth) {
                StackedSwapQuoteInfo(item, labelStyle, descriptionColor)
            } else {
                InlineSwapQuoteInfo(item, labelStyle, descriptionColor)
            }
        }
    } else {
        InlineSwapQuoteInfo(item, labelStyle, descriptionColor)
    }
}

@Composable
private fun InlineSwapQuoteInfo(item: SwapQuoteInfoItem, descriptionStyle: TextStyle, descriptionColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuoteInfoLabel(
            item = item,
            style = descriptionStyle,
            color = descriptionColor,
            modifier = Modifier.weight(1f),
        )
        Column(
            horizontalAlignment = Alignment.End
        ) {
            SelectionContainer {
                ZashiAutoSizeText(
                    text = item.title.getValue(),
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color = ZashiColors.Text.textPrimary,
                    maxLines = 1
                )
            }
            if (item.subtitle != null) {
                SelectionContainer {
                    ZashiAutoSizeText(
                        text = item.subtitle.getValue(),
                        style = ZashiTypography.textXs,
                        color = ZashiColors.Text.textTertiary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Suppress("MagicNumber")
@Composable
private fun StackedSwapQuoteInfo(
    item: SwapQuoteInfoItem,
    descriptionStyle: TextStyle,
    descriptionColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        QuoteInfoLabel(
            item = item,
            modifier = Modifier.fillMaxWidth(),
            style = descriptionStyle,
            color = descriptionColor
        )
        Spacer(4.dp)
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = item.title.getValue(),
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    color = ZashiColors.Text.textPrimary
                )
                item.subtitle?.let { subtitle ->
                    Spacer(4.dp)
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = subtitle.getValue(),
                        style = ZashiTypography.textSm,
                        color = ZashiColors.Text.textTertiary
                    )
                }
            }
        }
    }
}

private val quoteHelpWidth = 48.dp
