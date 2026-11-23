package co.electriccoin.zcash.ui.screen.pay

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldDefaults
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber

private const val STACKED_AMOUNT_FONT_SCALE = 1.5f
private const val VERTICAL_CONVERSION_ROTATION_DEGREES = 90f

@Composable
internal fun PayAmountTextFields(token: NumberTextFieldState, fiat: NumberTextFieldState, ticker: StringResource?) {
    val gap = 12.dp
    if (LocalDensity.current.fontScale >= STACKED_AMOUNT_FONT_SCALE) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            TokenAmountField(token, ticker, Modifier.fillMaxWidth())
            ConversionIndicator(Modifier.rotate(VERTICAL_CONVERSION_ROTATION_DEGREES))
            FiatAmountField(fiat, Modifier.fillMaxWidth())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            TokenAmountField(token, ticker, Modifier.weight(1f))
            ConversionIndicator()
            FiatAmountField(fiat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TokenAmountField(state: NumberTextFieldState, ticker: StringResource?, modifier: Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    ZashiNumberTextField(
        modifier = modifier.testTag(PayTag.PAY_AMOUNT_TOKEN_FIELD),
        state = state,
        interactionSource = interactionSource,
        placeholder = if (focused) null else {
            { AmountPlaceholder((ticker ?: stringResByDynamicNumber(0)).getValue()) }
        },
    )
}

@Composable
private fun FiatAmountField(state: NumberTextFieldState, modifier: Modifier) {
    ZashiNumberTextField(
        modifier = modifier.testTag(PayTag.PAY_AMOUNT_FIAT_FIELD),
        state = state,
        placeholder = { AmountPlaceholder(FiatCurrency.USD.code) },
        prefix = {
            val hasValue = state.innerState.innerTextFieldState.value.getValue().isNotEmpty()
            Image(
                painter = painterResource(R.drawable.ic_send_usd),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (hasValue) ZashiColors.Inputs.Filled.text else ZashiColors.Inputs.Filled.iconMain
                )
            )
        }
    )
}

@Composable
private fun AmountPlaceholder(text: String) {
    ZashiNumberTextFieldDefaults.Placeholder(
        modifier = Modifier.fillMaxWidth(),
        style = ZashiTypography.textMd.copy(color = ZashiColors.Inputs.Default.text),
        fontWeight = FontWeight.Normal,
        text = text
    )
}

@Composable
private fun ConversionIndicator(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_send_convert),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color = ZcashTheme.colors.secondaryColor),
    )
}
