@file:Suppress("TooManyFunctions")

package co.electriccoin.zcash.ui.screen.exchangerate.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.ZashiTooltipBox
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.LottieProgress
import co.electriccoin.zcash.ui.design.component.ShimmerableText
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.fixture.ObserveFiatCurrencyResultFixture
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Clock

@Suppress("LongParameterList", "ComplexCondition")
@Composable
fun StyledExchangeBalance(
    zatoshi: Zatoshi?,
    state: ExchangeRateState,
    modifier: Modifier = Modifier,
    hiddenBalancePlaceholder: StringResource =
        stringRes(co.electriccoin.zcash.ui.design.R.string.general_hideBalancesMost),
    textColor: Color = ZashiColors.Text.textPrimary,
    style: TextStyle = ZashiTypography.textSm.copy(fontWeight = FontWeight.SemiBold)
) {
    when (state) {
        is ExchangeRateState.Data -> {
            if ((state.isStale && !state.isLoading) || (!state.isLoading && state.currencyConversion == null)) {
                Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                    ExchangeRateUnavailableButton(
                        textColor = textColor,
                        style = style,
                        modifier = if (state.isRefreshEnabled) Modifier.weight(1f, fill = false) else Modifier,
                    )
                    if (state.isRefreshEnabled) ExchangeRateRefreshButton(state, textColor)
                }
            } else {
                ExchangeAvailableRateLabelInternal(
                    style = style,
                    textColor = textColor,
                    zatoshi = zatoshi,
                    state = state,
                    hiddenBalancePlaceholder = hiddenBalancePlaceholder,
                    isHideBalance = LocalBalancesAvailable.current.not(),
                    modifier = modifier
                )
            }
        }

        is ExchangeRateState.OptIn -> {
            // do not show anything
        }

        ExchangeRateState.OptedOut -> {
            // do not show anything
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ExchangeAvailableRateLabelInternal(
    style: TextStyle,
    textColor: Color,
    zatoshi: Zatoshi?,
    state: ExchangeRateState.Data,
    hiddenBalancePlaceholder: StringResource,
    isHideBalance: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerableText(
            text =
                zatoshi?.let {
                    createExchangeRateText(state, hiddenBalancePlaceholder, it, isHideBalance)
                },
            shimmerText = "0.00",
            style = style,
            maxLines = 1,
            color = textColor,
        )

        val refreshTargetSize = 48.dp
        if (state.isLoading || zatoshi == null) {
            Box(modifier = Modifier.size(refreshTargetSize), contentAlignment = Alignment.Center) {
                LottieProgress()
            }
        } else {
            ExchangeRateRefreshButton(state, textColor)
        }
    }
}

@Composable
private fun ExchangeRateRefreshButton(state: ExchangeRateState.Data, textColor: Color) {
    IconButton(
        modifier = Modifier.size(48.dp),
        onClick = state.onRefresh,
        enabled = state.isRefreshEnabled,
    ) {
        Image(
            modifier = Modifier.size(16.dp),
            painter = painterResource(R.drawable.ic_exchange_rate_retry),
            contentDescription =
                stringResource(co.electriccoin.zcash.ui.design.R.string.currencyConversion_refreshAction),
            colorFilter =
                ColorFilter.tint(if (state.isRefreshEnabled) textColor else ZashiColors.Text.textDisabled),
        )
    }
}

@Composable
internal fun createExchangeRateText(
    state: ExchangeRateState.Data,
    hiddenBalancePlaceholder: StringResource,
    zatoshi: Zatoshi,
    isHideBalances: Boolean
): String {
    val currencySymbol = state.expectedCurrency.symbol
    val text =
        if (isHideBalances) {
            "${currencySymbol}${hiddenBalancePlaceholder.getValue()}"
        } else if (state.currencyConversion != null) {
            stringResByDynamicCurrencyNumber(
                amount =
                    zatoshi
                        .convertZatoshiToZec()
                        .multiply(
                            BigDecimal(state.currencyConversion.priceOfZec),
                            MathContext.DECIMAL128
                        ),
                ticker = currencySymbol,
                tickerLocation = TickerLocation.BEFORE
            ).getValue()
        } else {
            currencySymbol
        }
    return text
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExchangeRateUnavailableButton(
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)
    ZashiTooltipBox(
        modifier = modifier,
        state = tooltipState,
        tooltip = {
            StyledExchangeUnavailablePopup(
                onDismissRequest = {
                    scope.launch {
                        tooltipState.dismiss()
                    }
                },
            )
        }
    ) {
        TextButton(
            onClick = {
                scope.launch {
                    if (tooltipState.isVisible) tooltipState.dismiss() else tooltipState.show()
                }
            },
            colors = ButtonDefaults.textButtonColors(contentColor = textColor),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        ) {
            Text(
                text = stringResource(id = R.string.tooltip_exchangeRate_title),
                style = style,
                modifier = Modifier.weight(1f, fill = false),
                color = textColor
            )

            Spacer(modifier = Modifier.width(10.dp))

            Image(
                modifier = Modifier.align(Alignment.CenterVertically),
                painter = painterResource(R.drawable.ic_exchange_rate_info),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textColor)
            )
        }
    }
}

@PreviewScreens
@Composable
private fun DefaultPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ExchangeRateState.Data(
                            isLoading = false,
                            currencyConversion =
                                FiatCurrencyConversion(
                                    timestamp = Clock.System.now(),
                                    priceOfZec = 25.0
                                ),
                            onRefresh = {}
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun DefaultNoRefreshPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ExchangeRateState.Data(
                            isLoading = false,
                            currencyConversion =
                                FiatCurrencyConversion(
                                    timestamp = Clock.System.now(),
                                    priceOfZec = 25.0
                                ),
                            isRefreshEnabled = false,
                            onRefresh = {}
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun HiddenPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ExchangeRateState.Data(
                            isLoading = false,
                            currencyConversion =
                                FiatCurrencyConversion(
                                    timestamp = Clock.System.now(),
                                    priceOfZec = 25.0
                                ),
                            onRefresh = {}
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun HiddenStalePreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ExchangeRateState.Data(
                            isLoading = false,
                            isStale = true,
                            currencyConversion =
                                FiatCurrencyConversion(
                                    timestamp = Clock.System.now(),
                                    priceOfZec = 25.0
                                ),
                            onRefresh = {}
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state = ObserveFiatCurrencyResultFixture.new()
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun LoadingEmptyPreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state = ObserveFiatCurrencyResultFixture.new(currencyConversion = null)
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun LoadingStalePreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ObserveFiatCurrencyResultFixture.new(
                            isStale = true,
                            isLoading = true
                        )
                )
            }
        }
    }

@PreviewScreens
@Composable
private fun StalePreview() =
    ZcashTheme {
        BlankSurface {
            Column {
                StyledExchangeBalance(
                    modifier = Modifier,
                    zatoshi = Zatoshi(1),
                    state =
                        ObserveFiatCurrencyResultFixture.new(
                            isLoading = false,
                            isStale = true,
                        )
                )
            }
        }
    }
