package co.electriccoin.zcash.ui.screen.swap

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapDirection
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.DEFAULT_SLIPPAGE
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The swap direction toggle is offered only for an account with spend authority: a view-only account may only swap
 * into ZEC, and while the selected account is still loading the toggle stays hidden rather than flashing in.
 */
class SwapVMMapperChangeModeTest {
    private val mapper = SwapVMMapper()

    @BeforeTest
    fun setUp() {
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(FiatCurrency.USD)
    }

    @Test
    fun spendableAccountShowsChangeModeButton() {
        val state = mapper.createState(internalState(account(isSpendable = true)), callbacks)

        val button = assertNotNull(state.changeModeButton)
        assertEquals(R.drawable.ic_swap_change_mode, button.icon)
    }

    @Test
    fun viewOnlyAccountHidesChangeModeButton() {
        val state = mapper.createState(internalState(account(isSpendable = false)), callbacks)

        assertNull(state.changeModeButton)
        assertEquals(SwapState.AddressLocation.TOP, state.addressLocation)
    }

    @Test
    fun unloadedAccountHidesChangeModeButton() {
        val state = mapper.createState(internalState(account = null), callbacks)

        assertNull(state.changeModeButton)
    }

    @Test
    fun fiatConversionFloorsTheExactTokenAmountAtAssetPrecision() {
        val asset = SwapAssetTestFixture.asset(
            decimals = 8,
            usdPrice = BigDecimal("1.00000000000000000000000000000000000001"),
        )
        val state = internalState(account(isSpendable = true)).copy(
            swapAsset = asset,
            currencyType = CurrencyType.FIAT,
            addressText = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu",
        )
        val requested = mutableListOf<BigDecimal>()
        val screen = mapper.createState(state, callbacks.copy(
            onRequestSwapQuoteClick = { amount, _, _ -> requested += amount },
        ))

        assertNotNull(screen.primaryButton).onClick()
        assertEquals(listOf(BigDecimal("0.99999999")), requested)
    }

    @Test
    fun typedOverprecisionIsVisibleAndCannotRequestAQuoteInEitherDirection() {
        val safeCallbacks = callbacks.copy(onRequestSwapQuoteClick = { _, _, _ -> error("Unexpected quote") })
        SwapDirection.entries.forEach { direction ->
            listOf("0.100000009", "0.000000009").forEach { text ->
                val input = internalState(account(isSpendable = true)).copy(
                    swapDirection = direction,
                    amountTextState = NumberTextFieldInnerState.fromAmount(BigDecimal(text)),
                    addressText = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu",
                )
                val screen = mapper.createState(input, safeCallbacks)
                assertEquals(
                    stringRes(co.electriccoin.zcash.ui.design.R.string.send_error_invalidAmount),
                    screen.amountTextField.error,
                )
                val quote = assertNotNull(screen.primaryButton)
                assertFalse(quote.isEnabled)
                quote.onClick()
            }
        }
    }

    private fun account(isSpendable: Boolean): WalletAccount =
        mockk {
            every { canSpend(any()) } returns true
            every { loadedBalances } returns null
            every { this@mockk.isSpendable } returns isSpendable
        }

    private fun internalState(account: WalletAccount?) =
        InternalStateImpl(
            account = account,
            swapAsset = SwapAssetTestFixture.asset(),
            currencyType = CurrencyType.TOKEN,
            amountTextState = NumberTextFieldInnerState.fromAmount(BigDecimal("1")),
            addressText = "",
            slippage = DEFAULT_SLIPPAGE,
            swapAssets = SwapAssetTestFixture.assetsData(),
            isRequestingQuote = false,
            selectedContact = null,
            swapDirection = SwapDirection.SWAP_INTO_ZEC,
            isEphemeralAddressLocked = false
        )

    private val callbacks =
        SwapStateCallbacks(
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSwapCurrencyTypeClick = {},
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _, _ -> },
            onTryAgainClick = {},
            onAddressChange = {},
            onTextFieldChange = {},
            onQrCodeScannerClick = {},
            onAddressBookClick = {},
            onDeleteSelectedContactClick = {},
            onBalanceButtonClick = {},
            onChangeButtonClick = {},
            onAddressClick = {},
        )
}
