package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.typography.RobotoMonoFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography

/**
 * The single-line address field, which truncates the address around an ellipsis while it is not
 * focused. The keyboard is asked not to learn from what is typed, by [WithoutPersonalizedLearning].
 */
@Suppress("LongParameterList")
@Composable
fun ZashiAddressTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = ZashiTextFieldDefaults.innerModifier,
    textStyle: TextStyle =
        ZashiTypography.textMd.copy(
            fontWeight = FontWeight.Medium,
            fontFamily = RobotoMonoFontFamily
        ),
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = ZashiTextFieldDefaults.shape,
    contentPadding: PaddingValues =
        ZashiAddressTextFieldDefaults
            .contentPadding(leadingIcon, suffix, trailingIcon, prefix),
    colors: ZashiTextFieldColors = ZashiTextFieldDefaults.defaultColors()
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val measurer = rememberTextMeasurer()
    var contentWidth by remember { mutableIntStateOf(Int.MAX_VALUE) }

    val visualTransformation =
        remember(isFocused, contentWidth, textStyle, measurer) {
            if (isFocused) {
                VisualTransformation.None
            } else {
                ellipsisVisualTransformation { text ->
                    measurer.measure(text, style = textStyle, maxLines = 1, softWrap = false).size.width <= contentWidth
                }
            }
        }

    WithoutPersonalizedLearning {
        ZashiTextField(
            state = state,
            modifier = modifier,
            innerModifier = innerModifier,
            textStyle = textStyle,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            maxLines = 1,
            minLines = 1,
            singleLine = true,
            interactionSource = interactionSource,
            shape = shape,
            contentPadding = contentPadding,
            colors = colors,
            textContentModifier = Modifier.fillMaxWidth().onSizeChanged { contentWidth = it.width },
        )
    }
}

private const val ADDRESS_PREVIEW_EDGE_LIMIT = 8
private const val ADDRESS_PREVIEW_ELLIPSIS = "..."

private fun ellipsisVisualTransformation(fits: (String) -> Boolean) =
    VisualTransformation { text ->
        if (text.length <= ADDRESS_PREVIEW_EDGE_LIMIT * 2 && fits(text.text)) {
            TransformedText(text, OffsetMapping.Identity)
        } else {
            abbreviateAddress(text, fits)
        }
    }

private fun abbreviateAddress(text: AnnotatedString, fits: (String) -> Boolean): TransformedText {
    val edgeLimit = (text.length / 3).coerceAtMost(ADDRESS_PREVIEW_EDGE_LIMIT)
    fun preview(edge: Int) = text.take(edge).toString() + ADDRESS_PREVIEW_ELLIPSIS + text.takeLast(edge)
    val edge = (edgeLimit downTo 0).firstOrNull { fits(preview(it)) } ?: 0
    val shortened = preview(edge).takeIf(fits).orEmpty()
    val tailStart = edge + ADDRESS_PREVIEW_ELLIPSIS.length
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = 0

        override fun transformedToOriginal(offset: Int) = when {
            offset <= edge -> offset
            offset < tailStart -> edge
            else -> text.length - (shortened.length - offset)
        }.coerceIn(0, text.length)
    }
    return TransformedText(AnnotatedString(shortened), mapping)
}

object ZashiAddressTextFieldDefaults {
    @Composable
    fun contentPadding(
        leadingIcon: @Composable (() -> Unit)?,
        suffix: @Composable (() -> Unit)?,
        trailingIcon: @Composable (() -> Unit)?,
        prefix: @Composable (() -> Unit)?
    ) = PaddingValues(
        start = if (leadingIcon != null || prefix != null) 8.dp else 14.dp,
        end = if (suffix != null) 4.dp else 12.dp,
        top = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
        bottom = getVerticalPadding(trailingIcon, leadingIcon, suffix, prefix),
    )
}
