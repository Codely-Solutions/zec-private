package co.electriccoin.zcash.ui.screen.pay

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.DEFAULT_SLIPPAGE
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.GiftCardSecretFixture
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
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
 * A gift card link typed or pasted into the pay address field shows the gift card error and keeps the review button
 * disabled. So does a saved contact whose address is a gift card link.
 */
class ExactOutputVMMapperGiftCardLinkTest {
    private val mapper = ExactOutputVMMapper()

    /** An account that can spend any amount, so only the address decides whether the button is enabled. */
    private val account =
        mockk<WalletAccount> {
            every { canSpend(any()) } returns true
            every { loadedBalances } returns null
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
    fun giftCardLinkShowsErrorAndDisablesReview() {
        GiftCardSecretFixture.all.forEach { (name, link) ->
            val state = mapper.createState(internalState(address = link), callbacks)

            assertEquals(stringRes(R.string.swap_error_giftCardLink), state.address.error, name)
            assertFalse(state.primaryButton?.isEnabled ?: true, name)
        }
    }

    @Test
    fun ordinaryAddressKeepsReviewEnabled() {
        listOf("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").forEach { address ->
            val state = mapper.createState(internalState(address = address), callbacks)

            assertNull(state.address.error, address)
            assertTrue(state.primaryButton?.isEnabled == true, address)
        }
    }

    @Test
    fun wrongNetworkShowsInlineErrorAndDisablesReview() {
        val state = mapper.createState(
            internalState(address = "0xde709f2102306220921060314715629080e2fb77"),
            callbacks
        )
        assertNotNull(state.address.error)
        assertFalse(state.primaryButton?.isEnabled ?: true)
    }

    @Test
    fun giftCardLinkContactDisablesReview() {
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

            val state = mapper.createState(internalState(address = "", selectedABContact = contact), callbacks)

            assertFalse(state.primaryButton?.isEnabled ?: true, name)
        }
    }

    @Test
    fun computedTokenInputMatchesRequestedAtomicAmount() {
        val asset = SwapAssetTestFixture.asset(usdPrice = BigDecimal.ONE, decimals = 8)
        val fiat = NumberTextFieldInnerState.fromAmount(BigDecimal("0.122839499"))
        val computed = mapper.createAmountInnerState(fiat, NumberTextFieldInnerState(), asset)
        val expected = BigDecimal("0.12283949")
        assertEquals(expected, computed.amount)
        assertEquals(
            stringResByNumber(expected, minDecimals = 0, includeGroupingSeparator = false),
            computed.innerTextFieldState.value
        )

        var requested: BigDecimal? = null
        val state = mapper.createState(
            internalState("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu").copy(asset = asset, amount = computed,
                fiatAmount = fiat),
            callbacks.copy(onRequestSwapQuoteClick = { amount, _, _ -> requested = amount })
        )
        assertNotNull(state.primaryButton).onClick()
        assertEquals(expected, requested)
    }

    @Test
    fun fiatEditUsesExactAtomicTokenAmount() {
        val asset = SwapAssetTestFixture.asset(usdPrice = BigDecimal.ONE, decimals = 6)
        var updated: NumberTextFieldInnerState? = null
        val state = mapper.createState(
            internalState("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu").copy(asset = asset),
            callbacks.copy(onTextFieldChange = { amount, _ -> updated = amount })
        )
        state.amountFiat.onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal("0.122839499")))
        val expected = BigDecimal("0.122839")
        assertEquals(expected, assertNotNull(updated).amount)
        assertEquals(
            stringResByNumber(expected, minDecimals = 0, includeGroupingSeparator = false),
            assertNotNull(updated).innerTextFieldState.value
        )
    }

    private fun internalState(
        address: String,
        selectedABContact: EnhancedABContact? = null,
    ) = InternalStateImpl(
        address = address,
        isABHintVisible = false,
        selectedABContact = selectedABContact,
        asset = SwapAssetTestFixture.asset(),
        amount = NumberTextFieldInnerState.fromAmount(BigDecimal("1")),
        fiatAmount = NumberTextFieldInnerState(),
        slippage = DEFAULT_SLIPPAGE,
        isRequestingQuote = false,
        account = account,
        swapAssets = SwapAssetTestFixture.assetsData(),
        isEphemeralAddressLocked = false
    )

    private val callbacks =
        ExactOutputStateCallbacks(
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _, _ -> },
            onTryAgainClick = {},
            onAddressChange = {},
            onTextFieldChange = { _, _ -> },
            onQrCodeScannerClick = {},
            onAddressBookClick = {},
            onDeleteSelectedContactClick = {},
        )
}
