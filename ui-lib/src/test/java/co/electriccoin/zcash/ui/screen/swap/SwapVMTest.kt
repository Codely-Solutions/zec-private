package co.electriccoin.zcash.ui.screen.swap

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapDirection
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapAssetPickerUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapInfoUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.ScanResult
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.info.SwapRefundAddressInfoArgs
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * [SwapVM] now owns the selected asset and slippage in its merged state: preselect applies only when
 * nothing is selected, the slippage/asset pickers round-trip their results back into the state, the
 * mode toggles, the quote request is pinned to the asset + slippage selected at click time, and the
 * cancel-confirmation flow (back while requesting, then confirm/dismiss) drives the cancel sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapVMTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    private companion object {
        const val BTC_ADDRESS = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"
        const val ETH_ADDRESS = "0xde709f2102306220921060314715629080e2fb77"
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(FiatCurrency.USD)
        Dispatchers.resetMain()
    }

    @Test
    fun preselectAppliedWhenNothingSelected() =
        runTest {
            val harness = harness(preselect = SwapAssetTestFixture.asset(tokenTicker = "btc"))

            harness.collectState(this)

            assertEquals("btc", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun slippagePickerResultAppliedToState() =
        runTest {
            val harness = harness(slippageResult = BigDecimal("7"))
            harness.collectState(this)

            harness.onSlippageClick(null)

            assertEquals(BigDecimal("7"), harness.capturedState.slippage)
            coVerify { harness.navigateToSlippage(BigDecimal("2"), null, any()) }
        }

    @Test
    fun assetPickerResultAppliedToState() =
        runTest {
            val asset = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
            val harness = harness(assetResult = asset)
            harness.collectState(this)

            harness.onSwapAssetPickerClick()

            assertEquals("eth", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun changeButtonTogglesMode() =
        runTest {
            val harness = harness()
            harness.collectState(this)

            assertEquals(SwapDirection.SWAP_INTO_ZEC, harness.capturedState.swapDirection)

            harness.onChangeButtonClick()

            assertEquals(SwapDirection.SWAP_FROM_ZEC, harness.capturedState.swapDirection)
        }

    @Test
    fun changeButtonIgnoredForViewOnlyAccount() =
        runTest {
            val harness = harness(account = account(isSpendable = false))
            harness.collectState(this)

            harness.onChangeButtonClick()

            assertEquals(SwapDirection.SWAP_INTO_ZEC, harness.capturedState.swapDirection)
        }

    @Test
    fun changeButtonIgnoredWhileAccountLoading() =
        runTest {
            val harness = harness(account = null)
            harness.collectState(this)

            harness.onChangeButtonClick()

            assertEquals(SwapDirection.SWAP_INTO_ZEC, harness.capturedState.swapDirection)
        }

    @Test
    fun switchingToViewOnlyAccountResetsModeToIntoZec() =
        runTest {
            val asset = SwapAssetTestFixture.asset(tokenTicker = "btc")
            val harness = harness(preselect = asset)
            harness.collectState(this)
            harness.onChangeButtonClick()
            assertEquals(SwapDirection.SWAP_FROM_ZEC, harness.capturedState.swapDirection)

            harness.selectedAccount.value = account(isSpendable = false)

            assertEquals(SwapDirection.SWAP_INTO_ZEC, harness.capturedState.swapDirection)
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)
            coVerify(exactly = 0) { harness.requestSwapQuote.requestExactInput(any(), any(), any(), any(), any(),
                any()) }
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), BTC_ADDRESS, asset, any(), any(), null)
            }
        }

    @Test
    fun switchingBackToSpendableAccountKeepsIntoZecAndAllowsToggle() =
        runTest {
            val harness = harness(account = account(isSpendable = false))
            harness.collectState(this)

            harness.selectedAccount.value = account(isSpendable = true)
            assertEquals(SwapDirection.SWAP_INTO_ZEC, harness.capturedState.swapDirection)

            harness.onChangeButtonClick()
            assertEquals(SwapDirection.SWAP_FROM_ZEC, harness.capturedState.swapDirection)
        }

    @Test
    fun requestQuotePinsSelectedAssetAndSlippage() =
        runTest {
            val asset = SwapAssetTestFixture.asset(tokenTicker = "btc")
            val harness = harness(preselect = asset)
            harness.collectState(this)

            harness.onAddressChange(BTC_ADDRESS)

            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)

            // Default mode is SWAP_INTO_ZEC -> flex input, pinned to the preselected asset + default slippage.
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(
                    amount = BigDecimal("1"),
                    refundAddress = BTC_ADDRESS,
                    selectedAsset = asset,
                    slippage = BigDecimal("2"),
                    canNavigateToSwapQuote = any(),
                    contactNetwork = null
                )
            }
        }

    @Test
    fun swappingFromZecRequestsExactInputQuote() =
        runTest {
            val asset = SwapAssetTestFixture.asset(tokenTicker = "btc")
            val harness = harness(preselect = asset)
            harness.collectState(this)

            harness.onChangeButtonClick() // SWAP_INTO_ZEC -> SWAP_FROM_ZEC
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)

            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestExactInput(
                    amount = BigDecimal("1"),
                    address = BTC_ADDRESS,
                    selectedAsset = asset,
                    slippage = BigDecimal("2"),
                    canNavigateToSwapQuote = any(),
                    contactNetwork = null
                )
            }
        }

    @Test
    fun slippageModeFollowsSwapDirection() =
        runTest {
            val harness = harness()
            harness.collectState(this)

            // SWAP_INTO_ZEC opens the slippage screen in flex-input mode.
            harness.onSlippageClick(null)
            coVerify { harness.navigateToSlippage(any(), any(), SwapMode.FLEX_INPUT) }

            // After flipping to SWAP_FROM_ZEC it opens in exact-input mode.
            harness.onChangeButtonClick()
            harness.onSlippageClick(null)
            coVerify { harness.navigateToSlippage(any(), any(), SwapMode.EXACT_INPUT) }
        }

    @Test
    fun pickingAbContactWithoutSelectedAssetSelectsOnlyCompatibleAsset() =
        runTest {
            val eth = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
            val harness =
                harness(
                    preselect = null,
                    assets = SwapAssetTestFixture.assetsData(data = listOf(eth)),
                    recipient = contactOnChain("eth")
                )
            harness.collectState(this)

            harness.onAddressBookClick()

            assertEquals("eth", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun pickingAbContactWithoutSelectedAssetKeepsMultipleAssetsExplicit() =
        runTest {
            val harness =
                harness(
                    preselect = null,
                    assets =
                        SwapAssetTestFixture.assetsData(
                            data =
                                listOf(
                                    SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth"),
                                    SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth"),
                                )
                        ),
                    recipient = contactOnChain("eth")
                )
            harness.collectState(this)

            harness.onAddressBookClick()

            // The contact supplies the network; choosing between its compatible assets stays explicit.
            assertNull(harness.capturedState.swapAsset)
        }

    // region quote-request guards

    @Test
    fun requestQuoteIgnoredWhenNoAssetSelected() =
        runTest {
            val harness = harness(preselect = null)
            harness.collectState(this)

            harness.onAddressChange(BTC_ADDRESS)

            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)

            assertNull(harness.capturedState.swapAsset)
            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun requestingQuoteTogglesRequestingFlag() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            val gate = harness.makeQuoteHang()

            harness.onAddressChange(BTC_ADDRESS)

            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)
            assertEquals(true, harness.capturedState.isRequestingQuote)

            gate.complete(Unit)
            assertEquals(false, harness.capturedState.isRequestingQuote)
        }

    @Test
    fun refundWarningCancelledSkipsQuoteAndClearsRequestingFlag() =
        runTest {
            val harness = harness(refundWarningResult = false)
            harness.collectState(this)

            harness.onAddressChange(BTC_ADDRESS)

            harness.onRequestSwapQuoteClick(BigDecimal("1"), BigDecimal("100"), BTC_ADDRESS)

            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }
            assertEquals(false, harness.capturedState.isRequestingQuote)
        }

    @Test
    fun refundWarningModeFollowsSwapDirection() =
        runTest {
            val harness = harness()
            harness.collectState(this)

            harness.onAddressChange(BTC_ADDRESS)

            harness.onRequestSwapQuoteClick(BigDecimal("1"), BigDecimal("100"), BTC_ADDRESS)
            coVerify(exactly = 1) {
                harness.navigateToSwapRefundWarning(fiatAmount = BigDecimal("100"), mode = SwapMode.FLEX_INPUT)
            }

            harness.onChangeButtonClick()
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), BigDecimal("50"), BTC_ADDRESS)
            coVerify(exactly = 1) {
                harness.navigateToSwapRefundWarning(fiatAmount = BigDecimal("50"), mode = SwapMode.EXACT_INPUT)
            }
        }

    // endregion

    @Test
    fun renderedQuoteCallbackRejectsAmountEditUntilCurrentButtonIsUsed() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.onAddressChange(BTC_ADDRESS)
            harness.onTextFieldChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
            val staleButton = assertNotNull(harness.vm.state.value?.primaryButton)

            harness.onTextFieldChange(NumberTextFieldInnerState.fromAmount(BigDecimal("2")))
            staleButton.onClick()
            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }

            assertNotNull(harness.vm.state.value?.primaryButton).onClick()
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(
                    BigDecimal("2"), BTC_ADDRESS, any(), any(), any(), null
                )
            }
        }

    @Test
    fun renderedQuoteCallbackRejectsNetworkEditWithSameEvmAddress() =
        runTest {
            val eth = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth", decimals = 18)
            val base = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "base", decimals = 18)
            val harness = harness(
                preselect = eth,
                assetResult = base,
                assets = SwapAssetTestFixture.assetsData(data = listOf(eth, base))
            )
            harness.collectState(this)
            harness.onAddressChange(ETH_ADDRESS)
            harness.onTextFieldChange(NumberTextFieldInnerState.fromAmount(BigDecimal.ONE))
            val staleButton = assertNotNull(harness.vm.state.value?.primaryButton)

            harness.onSwapAssetPickerClick()
            staleButton.onClick()
            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }

            assertNotNull(harness.vm.state.value?.primaryButton).onClick()
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(
                    BigDecimal.ONE, ETH_ADDRESS, base, any(), any(), null
                )
            }
        }

    // region cancel / back flow

    @Test
    fun backCancelsSwapWhenNoQuotePending() =
        runTest {
            val harness = harness()
            harness.collectState(this)

            harness.onBack()

            assertNull(harness.vm.cancelState.value)
            coVerify(exactly = 1) { harness.cancelSwap() }
        }

    @Test
    fun backWhileRequestingShowsCancelConfirmation() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.makeQuoteHang()
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)

            harness.onBack()

            assertNotNull(harness.vm.cancelState.value)
        }

    @Test
    fun backFromCancelConfirmationChecksForAvailableQuote() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            val gate = harness.makeQuoteHang()
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)
            harness.onBack() // requesting -> show cancel confirmation
            gate.complete(Unit) // request finishes -> no longer requesting, sheet still visible

            harness.onBack() // now resolves the cancel confirmation

            assertNull(harness.vm.cancelState.value)
            coVerify { harness.navigateToSwapQuoteIfAvailable(any()) }
        }

    @Test
    fun cancelConfirmationNegativeButtonCancelsSwap() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.makeQuoteHang()
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)
            harness.onBack()

            harness.vm.cancelState.value
                ?.negativeButton
                ?.onClick
                ?.invoke()
            mainDispatcher.scheduler.advanceUntilIdle() // let the 350ms dismiss delay elapse

            assertNull(harness.vm.cancelState.value)
            coVerify(exactly = 1) { harness.cancelSwap() }
        }

    @Test
    fun cancelConfirmationPositiveButtonChecksForQuote() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.makeQuoteHang()
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal("1"), null, BTC_ADDRESS)
            harness.onBack()

            harness.vm.cancelState.value
                ?.positiveButton
                ?.onClick
                ?.invoke()

            assertNull(harness.vm.cancelState.value)
            coVerify { harness.navigateToSwapQuoteIfAvailable(any()) }
        }

    // endregion

    // region currency type + preselectChain guards + input editing

    @Test
    fun swapCurrencyTypeTogglesTypeAndRebuildsAmount() =
        runTest {
            val harness = harness()
            harness.collectState(this)

            assertEquals(CurrencyType.TOKEN, harness.capturedState.currencyType)

            harness.onSwapCurrencyTypeClick(BigDecimal("5"))

            assertEquals(CurrencyType.FIAT, harness.capturedState.currencyType)
            assertEquals(0, BigDecimal("5").compareTo(harness.capturedState.amountTextState.amount))
        }

    @Test
    fun computedTokenInputKeepsDisplayedAndStoredAmountIdentical() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.onSwapCurrencyTypeClick(BigDecimal("5"))
            harness.onSwapCurrencyTypeClick(BigDecimal("0.123456789"))

            val amountState = harness.capturedState.amountTextState
            val expected = BigDecimal("0.12345678")
            assertEquals(0, expected.compareTo(amountState.amount))
            assertEquals(0, expected.compareTo(amountState.lastValidAmount))
            assertEquals(
                co.electriccoin.zcash.ui.design.util.stringResByNumber(
                    expected,
                    minDecimals = 0,
                    includeGroupingSeparator = false
                ),
                amountState.innerTextFieldState.value
            )
        }

    @Test
    fun computedZecInputUsesOriginPrecisionInsteadOfDestinationPrecision() =
        runTest {
            val asset = SwapAssetTestFixture.asset(decimals = 6)
            val harness = harness(preselect = asset, assets = SwapAssetTestFixture.assetsData(data = listOf(asset)))
            harness.collectState(this)
            harness.onChangeButtonClick()
            harness.onSwapCurrencyTypeClick(BigDecimal("5"))
            harness.onSwapCurrencyTypeClick(BigDecimal("0.123456789"))

            assertEquals(SwapDirection.SWAP_FROM_ZEC, harness.capturedState.swapDirection)
            assertEquals(0, BigDecimal("0.12345678").compareTo(harness.capturedState.amountTextState.amount))
        }

    @Test
    fun preselectChainSkippedWhenSwappingFromZec() =
        runTest {
            val harness =
                harness(
                    preselect = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
                    assets =
                        SwapAssetTestFixture.assetsData(
                            data =
                                listOf(
                                    SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
                                    SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
                                )
                        ),
                    recipient = contactOnChain("eth")
                )
            harness.collectState(this)

            harness.onChangeButtonClick() // SWAP_FROM_ZEC: chain preselection does not apply
            harness.onAddressBookClick()

            assertEquals("btc", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun preselectChainSkippedWhenChainUnchanged() =
        runTest {
            val harness =
                harness(
                    preselect = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
                    recipient = contactOnChain("btc")
                )
            harness.collectState(this)

            harness.onAddressBookClick()

            assertEquals("btc", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun preselectChainSkippedWhenContactHasNoChain() =
        runTest {
            val harness =
                harness(
                    preselect = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
                    recipient = EnhancedABContact(
                        AddressBookContact(
                            "Synthetic unscoped contact", BTC_ADDRESS, Instant.fromEpochMilliseconds(0), null
                        ),
                        null
                    )
                )
            harness.collectState(this)

            harness.onAddressBookClick()

            assertEquals("btc", harness.capturedState.swapAsset?.tokenTicker)
        }

    @Test
    fun scanResultPreservesRawRequestWithoutApplyingDestinationAmountToInput() =
        runTest {
            val harness =
                harness(
                    recipient = contactOnChain("btc"),
                    scanResult = ScanResult(
                        address = BTC_ADDRESS,
                        amount = BigDecimal("2.5"),
                        rawInput = "bitcoin:$BTC_ADDRESS?amount=2.5"
                    )
                )
            harness.collectState(this)
            harness.onAddressBookClick() // select a contact first, to prove the scan clears it

            harness.onQrCodeScannerClick()

            assertEquals("bitcoin:$BTC_ADDRESS?amount=2.5", harness.capturedState.addressText)
            assertNull(harness.capturedState.amountTextState.amount)
            assertNull(harness.capturedState.selectedContact)
            verify { harness.navigationRouter.back() }
        }

    @Test
    fun addressChangeClearsSelectedContact() =
        runTest {
            val harness = harness(recipient = contactOnChain("btc"))
            harness.collectState(this)
            harness.onAddressBookClick()
            assertNotNull(harness.capturedState.selectedContact)

            harness.onAddressChange("zs1-typed-address")

            assertEquals("zs1-typed-address", harness.capturedState.addressText)
            assertNull(harness.capturedState.selectedContact)
        }

    @Test
    fun assetPickerRemainsEditableAfterSelectingContact() =
        runTest {
            val eth = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
            val harness =
                harness(
                    assets = SwapAssetTestFixture.assetsData(data = listOf(eth)),
                    preselect = eth,
                    recipient = contactOnChain("eth")
                )
            harness.collectState(this)
            harness.onAddressBookClick()

            harness.onSwapAssetPickerClick()

            coVerify { harness.navigateToSwapAssetPicker() }
            coVerify { harness.navigateToSelectSwapRecipient(network = "eth") }
        }

    @Test
    fun addressClickForwardsRefundAddressInfoArgs() =
        runTest {
            val harness = harness(preselect = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"))
            harness.collectState(this)

            harness.onAddressClick()

            verify {
                harness.navigationRouter.forward(
                    SwapRefundAddressInfoArgs(tokenTicker = "btc", chainTicker = "btc")
                )
            }
        }

    // endregion

    @Test
    fun tokenNetworkChangeRetainsContactAndCorrectionQuotesWithoutLeavingForm() =
        runTest {
            val eth = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
            val btc = SwapAssetTestFixture.asset()
            val contact = contactOnChain("eth")
            val harness = harness(preselect = eth, recipient = contact)
            coEvery { harness.navigateToSwapAssetPicker(any()) } returnsMany listOf(btc, eth)
            harness.collectState(this)
            harness.onChangeButtonClick()
            harness.onAddressBookClick()

            harness.onSwapAssetPickerClick()
            assertEquals(contact, harness.capturedState.selectedContact)
            assertEquals(btc, harness.capturedState.swapAsset)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, ETH_ADDRESS)
            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestExactInput(any(), any(), any(), any(), any(), any())
            }

            harness.onSwapAssetPickerClick()
            assertEquals(contact, harness.capturedState.selectedContact)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, ETH_ADDRESS)
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestExactInput(BigDecimal.ONE, ETH_ADDRESS, eth, BigDecimal("2"), any(),
                    "eth")
            }
        }

    @Test
    fun callbackWithStaleAddressDoesNotRequestQuote() =
        runTest {
            val harness = harness()
            harness.collectState(this)
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, "stale-address")
            coVerify(exactly = 0) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun cancelledQuoteClearsLoadingAndAllowsCorrectedRetryInSameForm() =
        runTest {
            val harness = harness()
            var attempts = 0
            coEvery {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            } coAnswers {
                attempts++
                if (attempts == 1) throw CancellationException("Controlled test cancellation")
            }
            harness.collectState(this)
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, BTC_ADDRESS)
            assertEquals(false, harness.capturedState.isRequestingQuote)

            val corrected = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
            harness.onAddressChange(corrected)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, corrected)
            assertEquals(2, attempts)
            assertEquals(false, harness.capturedState.isRequestingQuote)
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), corrected, any(), any(), any(), null)
            }
        }

    @Test
    fun repeatedQuoteTapWhileRefundWarningPendingRequestsOnlyOnce() =
        runTest {
            val harness = harness()
            val warning = CompletableDeferred<Boolean>()
            coEvery { harness.navigateToSwapRefundWarning(any(), any()) } coAnswers { warning.await() }
            harness.collectState(this)
            harness.onAddressChange(BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, BTC_ADDRESS)
            harness.onRequestSwapQuoteClick(BigDecimal.ONE, null, BTC_ADDRESS)
            coVerify(exactly = 1) { harness.navigateToSwapRefundWarning(any(), any()) }

            warning.complete(true)
            coVerify(exactly = 1) {
                harness.requestSwapQuote.requestFlexInputIntoZec(any(), BTC_ADDRESS, any(), any(), any(), null)
            }
            assertEquals(false, harness.capturedState.isRequestingQuote)
        }

    private fun account(isSpendable: Boolean): WalletAccount {
        val sdkAccount = Account.new(AccountUuid.new(ByteArray(16)))
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return if (isSpendable) {
            ZodlAccount(
                sdkAccount, "synthetic-unified", "synthetic-transparent", "synthetic-sapling",
                zero, zero, zero, Zatoshi(0), true
            )
        } else {
            ViewOnlyAccount(
                sdkAccount, "synthetic-unified", "synthetic-transparent", zero, zero, zero, Zatoshi(0), true,
                stringRes("Synthetic view-only"), null, 0
            )
        }
    }

    private fun contactOnChain(chainTicker: String): EnhancedABContact =
        EnhancedABContact(
            contact = AddressBookContact(
                name = "Synthetic contact",
                address = if (chainTicker == "btc") BTC_ADDRESS else ETH_ADDRESS,
                lastUpdated = Instant.fromEpochMilliseconds(0),
                chain = chainTicker,
            ),
            blockchain = SwapAssetTestFixture.blockchain(chainTicker),
        )

    @Suppress("LongMethod")
    private fun harness(
        preselect: SwapAsset? = SwapAssetTestFixture.asset(),
        slippageResult: BigDecimal? = null,
        assetResult: SwapAsset? = null,
        recipient: EnhancedABContact? = null,
        scanResult: ScanResult? = null,
        assets: SwapAssetsData = SwapAssetTestFixture.assetsData(),
        refundWarningResult: Boolean = true,
        account: WalletAccount? = account(isSpendable = true)
    ): Harness {
        val navigateToSlippage =
            mockk<NavigateToSlippageUseCase> {
                coEvery { this@mockk.invoke(any(), any(), any()) } returns slippageResult
            }
        val navigateToSwapAssetPicker =
            mockk<NavigateToSwapAssetPickerUseCase> {
                coEvery { this@mockk.invoke(any()) } returns assetResult
            }
        val navigateToSelectSwapRecipient =
            mockk<NavigateToSelectABSwapRecipientUseCase> { coEvery { this@mockk.invoke(any()) } returns recipient }
        val navigateToScanAddress =
            mockk<NavigateToScanGenericAddressUseCase> { coEvery { this@mockk.invoke() } returns scanResult }
        val getPreselectedSwapAsset =
            mockk<GetPreselectedSwapAssetUseCase> {
                if (preselect != null) {
                    coEvery { this@mockk.invoke() } returns preselect
                } else {
                    // Never resolves -> the VM keeps `swapAsset` null (covers the no-asset-selected path).
                    coEvery { this@mockk.invoke() } coAnswers { awaitCancellation() }
                }
            }
        val navigateToSwapRefundWarning =
            mockk<NavigateToSwapRefundWarningUseCase> {
                coEvery { this@mockk.invoke(any(), any()) } returns refundWarningResult
            }
        val requestSwapQuote = mockk<RequestSwapQuoteUseCase>(relaxed = true)
        val cancelSwap = mockk<CancelSwapUseCase>(relaxed = true)
        val navigateToSwapQuoteIfAvailable = mockk<NavigateToSwapQuoteIfAvailableUseCase>(relaxed = true)
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val getSwapAssets =
            mockk<GetCuratedSwapAssetsUseCase> {
                every { observe() } returns MutableStateFlow(assets)
                every { this@mockk() } returns assets
            }
        val selectedAccount = MutableStateFlow(account)
        val getSelectedWalletAccount =
            mockk<GetSelectedWalletAccountUseCase> {
                every { observe() } returns selectedAccount
            }
        val mapper = SwapVMMapper()
        val swapRepository =
            mockk<SwapRepository>(relaxed = true) { every { this@mockk.assets } returns MutableStateFlow(assets) }

        val harness =
            Harness(
                navigateToSelectSwapRecipient = navigateToSelectSwapRecipient,
                navigateToSlippage = navigateToSlippage,
                navigateToSwapAssetPicker = navigateToSwapAssetPicker,
                navigateToSwapRefundWarning = navigateToSwapRefundWarning,
                requestSwapQuote = requestSwapQuote,
                cancelSwap = cancelSwap,
                navigateToSwapQuoteIfAvailable = navigateToSwapQuoteIfAvailable,
                navigationRouter = navigationRouter,
                selectedAccount = selectedAccount,
                assets = assets
            )

        harness.vm =
            SwapVM(
                getCuratedSwapAssetsUseCase = getSwapAssets,
                getSelectedWalletAccount = getSelectedWalletAccount,
                getPreselectedSwapAsset = getPreselectedSwapAsset,
                swapRepository = swapRepository,
                navigateToSwapInfo = mockk<NavigateToSwapInfoUseCase>(relaxed = true),
                cancelSwap = cancelSwap,
                navigationRouter = navigationRouter,
                requestSwapQuote = requestSwapQuote,
                navigateToSwapQuoteIfAvailable = navigateToSwapQuoteIfAvailable,
                swapVMMapper = mapper,
                navigateToScanAddress = navigateToScanAddress,
                navigateToSelectSwapRecipient = navigateToSelectSwapRecipient,
                navigateToSlippage = navigateToSlippage,
                navigateToSwapAssetPicker = navigateToSwapAssetPicker,
                navigateToSwapRefundWarning = navigateToSwapRefundWarning,
            )
        return harness
    }

    private class Harness(
        val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
        val navigateToSlippage: NavigateToSlippageUseCase,
        val navigateToSwapAssetPicker: NavigateToSwapAssetPickerUseCase,
        val navigateToSwapRefundWarning: NavigateToSwapRefundWarningUseCase,
        val requestSwapQuote: RequestSwapQuoteUseCase,
        val cancelSwap: CancelSwapUseCase,
        val navigateToSwapQuoteIfAvailable: NavigateToSwapQuoteIfAvailableUseCase,
        val navigationRouter: NavigationRouter,
        val selectedAccount: MutableStateFlow<WalletAccount?>,
        val assets: SwapAssetsData,
    ) {
        lateinit var vm: SwapVM

        // Read the VM's immutable snapshot and existing callback bundle directly. The production
        // mapper runs normally; no MockK proxy for its final UI-state return graph is required.
        // Reflection stays in this test fixture and fails immediately if either field is renamed.
        private val callbacks: SwapStateCallbacks by lazy {
            SwapVM::class.java.getDeclaredField("callbacks").apply { isAccessible = true }
                .get(vm) as SwapStateCallbacks
        }
        @Suppress("UNCHECKED_CAST")
        private val internalState: StateFlow<InternalStateImpl> by lazy {
            SwapVM::class.java.getDeclaredField("internalState").apply { isAccessible = true }
                .get(vm) as StateFlow<InternalStateImpl>
        }
        val capturedState: InternalState
            get() = internalState.value.copy(account = selectedAccount.value, swapAssets = assets)
        val onBack get() = callbacks.onBack
        val onSlippageClick get() = callbacks.onSlippageClick
        val onSwapAssetPickerClick get() = callbacks.onSwapAssetPickerClick
        val onSwapCurrencyTypeClick get() = callbacks.onSwapCurrencyTypeClick
        val onChangeButtonClick get() = callbacks.onChangeButtonClick
        val onAddressBookClick get() = callbacks.onAddressBookClick
        val onAddressChange get() = callbacks.onAddressChange
        val onQrCodeScannerClick get() = callbacks.onQrCodeScannerClick
        val onAddressClick get() = callbacks.onAddressClick
        val onTextFieldChange get() = callbacks.onTextFieldChange
        val onRequestSwapQuoteClick get() = callbacks.onRequestSwapQuoteClick

        /** Makes the next flex-input quote request hang so `isRequestingQuote` stays true. */
        fun makeQuoteHang(): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            coEvery {
                requestSwapQuote.requestFlexInputIntoZec(any(), any(), any(), any(), any(), any())
            } coAnswers { gate.await() }
            return gate
        }

        fun collectState(scope: TestScope) {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.cancelState.collect {} }
        }
    }
}
