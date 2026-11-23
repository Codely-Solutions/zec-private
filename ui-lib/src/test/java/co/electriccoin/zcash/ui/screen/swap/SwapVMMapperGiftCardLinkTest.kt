package co.electriccoin.zcash.ui.screen.swap

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapDirection
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.DEFAULT_SLIPPAGE
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.GiftCardSecretFixture
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A gift card link typed or pasted into the swap address field, in either direction, shows the gift card error and
 * keeps the quote button disabled. So does a saved contact whose address is a gift card link.
 */
class SwapVMMapperGiftCardLinkTest {
    private val mapper = SwapVMMapper()

    /** An account that can spend any amount, so only the address decides whether the button is enabled. */
    private val account =
        mockk<WalletAccount> {
            every { canSpend(any()) } returns true
            every { loadedBalances } returns null
            every { isSpendable } returns true
        }

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
    fun giftCardLinkShowsErrorAndDisablesQuote() {
        SwapDirection.entries.forEach { direction ->
            GiftCardSecretFixture.all.forEach { (name, link) ->
                val state = mapper.createState(internalState(addressText = link, direction = direction), callbacks)

                assertEquals(stringRes(R.string.swap_error_giftCardLink), state.address.error, name)
                assertFalse(state.primaryButton?.isEnabled ?: true, name)
            }
        }
    }

    @Test
    fun ordinaryAddressKeepsQuoteEnabled() {
        SwapDirection.entries.forEach { direction ->
            listOf("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu",
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").forEach { address ->
                val state =
                    mapper.createState(internalState(addressText = address, direction = direction), callbacks)

                assertNull(state.address.error, address)
                assertTrue(state.primaryButton?.isEnabled == true, address)
            }
        }
    }

    @Test
    fun unusablePricesLeaveTokenAndFiatInputsReadyForRefresh() {
        val base = internalState("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu")
        var refreshes = 0
        var requests = 0
        val handlers = callbacks.copy(
            onTryAgainClick = { refreshes++ },
            onRequestSwapQuoteClick = { _, _, _ -> requests++ },
        )
        val retry = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain)
        for (direction in SwapDirection.entries) {
            for (currency in CurrencyType.entries) {
                val entered = base.copy(swapDirection = direction, currencyType = currency)
                for (price in listOf(null, BigDecimal.ZERO, BigDecimal.ONE.negate())) {
                    val unavailable = SwapAssetTestFixture.asset("zec", "zec", usdPrice = price)
                    val form = mapper.createState(
                        entered.copy(swapAssets = entered.swapAssets.copy(zecAsset = unavailable)), handlers
                    )
                    val button = assertNotNull(form.primaryButton)
                    assertEquals(retry, button.text)
                    assertTrue(button.isEnabled)
                    button.onClick()
                    assertEquals(BigDecimal.ONE, form.amountTextField.textField.innerState.amount)
                }
                val ready = assertNotNull(mapper.createState(entered, handlers).primaryButton)
                assertEquals(stringRes(R.string.swapAndPay_getQuote), ready.text)
                assertTrue(ready.isEnabled)
                ready.onClick()
            }
        }
        assertEquals(12, refreshes)
        assertEquals(4, requests)
    }

    @Test
    fun wrongNetworkShowsInlineErrorAndDisablesQuoteInBothDirections() {
        SwapDirection.entries.forEach { direction ->
            val state = mapper.createState(
                internalState(addressText = "0xde709f2102306220921060314715629080e2fb77", direction = direction),
                callbacks
            )
            assertNotNull(state.address.error)
            assertFalse(state.primaryButton?.isEnabled ?: true)
        }
    }

    @Test
    fun giftCardLinkContactDisablesQuote() {
        GiftCardSecretFixture.all.forEach { (name, link) ->
            val contact =
                EnhancedABContact(
                    contact =
                        AddressBookContact(
                            name = "Card",
                            address = link,
                            lastUpdated = Instant.fromEpochMilliseconds(0),
                            chain = "btc"
                        ),
                    blockchain = SwapAssetTestFixture.blockchain("btc")
                )

            val state = mapper.createState(internalState(addressText = "", selectedContact = contact), callbacks)

            assertFalse(state.primaryButton?.isEnabled ?: true, name)
        }
    }

    private fun internalState(
        addressText: String,
        direction: SwapDirection = SwapDirection.SWAP_INTO_ZEC,
        selectedContact: EnhancedABContact? = null,
    ) = InternalStateImpl(
        account = account,
        swapAsset = SwapAssetTestFixture.asset(),
        currencyType = CurrencyType.TOKEN,
        amountTextState = NumberTextFieldInnerState.fromAmount(BigDecimal("1")),
        addressText = addressText,
        slippage = DEFAULT_SLIPPAGE,
        swapAssets = SwapAssetTestFixture.assetsData(),
        isRequestingQuote = false,
        selectedContact = selectedContact,
        swapDirection = direction,
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
