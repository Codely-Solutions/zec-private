package co.electriccoin.zcash.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.sdk.type.ZcashCurrency
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.request.model.AmountState
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.QrCodeState
import co.electriccoin.zcash.ui.screen.request.model.Request
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.screen.request.view.RequestAmountView
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextField
import co.electriccoin.zcash.ui.screen.swap.ui.SwapAmountTextFieldState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
class AmountSwitchSemanticsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun requestAmountNamesAndOperatesBothSwitchDirections() {
        val currency = mutableStateOf(RequestCurrency.ZEC)
        val data = ExchangeRateState.Data(
            currencyConversion = FiatCurrencyConversion(Clock.System.now(), 25.0, FiatCurrency.USD),
            expectedCurrency = FiatCurrency.USD,
            onRefresh = {},
        )
        compose.setContent {
            ZcashTheme {
                RequestAmountView(
                    RequestState.Amount(
                        request = Request(
                            amountState = AmountState("2", currency.value, true),
                            memoState = MemoState.Valid("", 0, "2"),
                            qrCodeState = QrCodeState("", "2", memo = ""),
                        ),
                        exchangeRateState = data,
                        zcashCurrency = ZcashCurrency.ZEC,
                        onAmount = {},
                        onSwitch = { currency.value = it },
                        onBack = {},
                        onDone = {},
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.amount_switch_to_fiat))
            .assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(RequestCurrency.FIAT, currency.value) }
        compose.onNodeWithContentDescription(
            context.getString(R.string.amount_switch_to_asset, ZcashCurrency.ZEC.localizedName(context))
        ).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(RequestCurrency.ZEC, currency.value) }
    }

    @Test
    fun swapAmountProvidesANameForTheCurrencySwitchOnlyWhenAvailable() {
        val enabled = mutableStateOf(true)
        var switched = 0
        val name = context.getString(R.string.amount_switch_to_asset, "BTC")
        compose.setContent {
            ZcashTheme {
                SwapAmountTextField(
                    SwapAmountTextFieldState(
                        title = stringRes("From"),
                        error = null,
                        token = AssetCardState.Data(
                            token = stringRes("BTC"),
                            bigIcon = imageRes(R.drawable.ic_zec_round_full),
                            smallIcon = imageRes(R.drawable.ic_zec_round_full),
                            onClick = null,
                        ),
                        textFieldPrefix = null,
                        textField = NumberTextFieldState {},
                        secondaryText = stringRes("0 BTC"),
                        max = null,
                        isSwapChangeEnabled = enabled.value,
                        swapChangeDescription = stringRes(R.string.amount_switch_to_asset, "BTC"),
                        onSwapChange = { switched++ },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription(name).assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(1, switched)
            enabled.value = false
        }
        compose.onNodeWithContentDescription(name).assertDoesNotExist()
    }
}
