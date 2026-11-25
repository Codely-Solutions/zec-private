package co.electriccoin.zcash.ui.screen.swap.quote

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.QuoteLowAmountException
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.getString
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import io.mockk.coEvery
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.provider.IndependentPriceProvider
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ViewModel-level counterpart of [SwapQuoteVMMapperTest]: the "Swap from" / "Pay from" row of a
 * live quote must name whichever account is actually selected, driven through the real
 * [SwapQuoteVMMapper] rather than a mock of it.
 *
 * Runs under Robolectric for the same reason as [SwapQuoteVMMapperTest]: the mapper resolves
 * `FiatCurrency.USD.symbol`, which needs real ICU currency data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwapQuoteVMTest {
    private val defaultUuid = AccountUuid.new(ByteArray(16) { it.toByte() })
    private val sdkAccount = Account.new(defaultUuid)
    private val destinationAsset = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theFromRowNamesTheSelectedLedgerAccount() =
        runTest {
            val vm = vm(account = ledger())
            collect(vm)

            assertEquals(R.string.accounts_ledger, fromRowTitle(vm))
        }

    @Test
    fun theFromRowNamesTheSelectedKeystoneAccount() =
        runTest {
            val vm = vm(account = keystone())
            collect(vm)

            assertEquals(R.string.accounts_keystone, fromRowTitle(vm))
        }

    @Test
    fun theFromRowNamesTheSelectedZashiAccount() =
        runTest {
            val vm = vm(account = zashi())
            collect(vm)

            assertEquals(R.string.accounts_zashi, fromRowTitle(vm))
        }

    @Test
    fun sheetDismissalCallbacksOnlyCancelOnce() =
        runTest {
            val cancelQuote = mockk<CancelSwapQuoteUseCase>(relaxed = true)
            val vm = vm(account = zashi(), cancelQuote = cancelQuote)
            collect(vm)
            val state = assertNotNull(vm.state.value)

            state.onBack()
            state.onBack()

            verify(exactly = 1) { cancelQuote() }
        }

    @Test
    fun rejectedDismissalCanBeRetriedOnTheSameReview() =
        runTest {
            val cancelQuote = mockk<CancelSwapQuoteUseCase> {
                coEvery { cancelFrom("quote", any()) } returnsMany listOf(false, true)
            }
            val vm = vm(account = zashi(), cancelQuote = cancelQuote, sourceEntryId = "quote")
            collect(vm)
            val state = assertNotNull(vm.state.value)

            state.onBack()
            state.onBack()
            state.onBack()

            coVerify(exactly = 2) { cancelQuote.cancelFrom("quote", any()) }
            verify(exactly = 0) { cancelQuote() }
        }

    @Test
    fun lowAmountErrorKeepsExactProviderThreshold() =
        runTest {
            val vm = vm(
                account = zashi(),
                quoteData = SwapQuoteData.Error(
                    mode = SwapMode.FLEX_INPUT,
                    exception = QuoteLowAmountException(
                        asset = SwapAssetTestFixture.zecAsset(),
                        amount = BigDecimal("12283949"),
                        amountFormatted = BigDecimal("0.12283949"),
                    ),
                ),
            )
            collect(vm)

            val state = assertNotNull(vm.state.value) as SwapQuoteState.Error
            val subtitle = state.subtitle.getString(RuntimeEnvironment.getApplication(), Locale.US)
            assertTrue(subtitle.contains("0.12283949 zec"), subtitle)
        }

    @Test
    fun checkingBlocksContinuationThenPoorQuoteUsesExplicitAction() = runTest {
        val decision = CompletableDeferred<IndependentPriceCheck>()
        val prices = mockk<IndependentPriceProvider> { coEvery { check(any(), any()) } coAnswers { decision.await() } }
        val submit = mockk<SubmitProposalUseCase>(relaxed = true)
        val vm = vm(zashi(), prices = prices, submit = submit)
        collect(vm)
        val checking = vm.state.value as SwapQuoteState.Success
        assertTrue(!checking.primaryButton.isEnabled)
        decision.complete(IndependentPriceCheck.Compared(BigDecimal("-20"), System.currentTimeMillis() + 60_000))
        val poor = vm.state.value as SwapQuoteState.Success
        assertTrue(poor.priceCheck?.isWarning == true)
        assertEquals("Swap anyway", poor.primaryButton.text.getString(RuntimeEnvironment.getApplication(), Locale.US))
        checking.primaryButton.onClick()
        coVerify(exactly = 0) { submit(any(), matchNullable { true }) }
        poor.primaryButton.onClick()
        coVerify(exactly = 1) { submit(any(), any()) }
    }

    @Test
    fun callbacksFromReplacedQuoteCannotProceed() = runTest {
        val quotes = MutableStateFlow<SwapQuoteData?>(SwapQuoteData.Success(FakeSwapQuote(
            destinationAsset = destinationAsset, mode = SwapMode.EXACT_INPUT,
        )))
        val submit = mockk<SubmitProposalUseCase>(relaxed = true)
        val vm = vm(zashi(), quotes = quotes, submit = submit)
        collect(vm)
        val old = vm.state.value as SwapQuoteState.Success
        quotes.value = SwapQuoteData.Success(
            FakeSwapQuote(destinationAsset = destinationAsset, mode = SwapMode.EXACT_INPUT)
        )
        old.primaryButton.onClick()
        coVerify(exactly = 0) { submit(any(), matchNullable { true }) }
        (vm.state.value as SwapQuoteState.Success).primaryButton.onClick()
        coVerify(exactly = 1) { submit(any(), refEq(quotes.value as SwapQuoteData.Success)) }
    }

    @Test
    fun missingAndReplacedLocalProposalsCannotBeReviewedOrApproved() = runTest {
        val quote = FakeSwapQuote(destinationAsset = destinationAsset, mode = SwapMode.EXACT_INPUT)
        val proposals = MutableStateFlow<TransactionProposal?>(null)
        val submit = mockk<SubmitProposalUseCase>(relaxed = true)
        val prices = mockk<IndependentPriceProvider> {
            coEvery { check(any(), any()) } returns IndependentPriceCheck.Unavailable
        }
        val publication = SwapQuoteData.Success(quote)
        val account = zashi()
        val vm = vm(account, proposals = proposals, quoteData = publication,
            submit = submit, prices = prices)
        collect(vm)
        kotlin.test.assertIs<SwapQuoteState.Error>(vm.state.value)
        coVerify(exactly = 0) { prices.check(any(), any()) }
        val sdkProposal = mockk<Proposal> { every { totalFeeRequired() } returns Zatoshi(10_000) }
        val prepared = mockk<SwapTransactionProposal> {
            every { this@mockk.quote } returns quote
            every { fundingAccount } returns account
            every { proposal } returns sdkProposal
        }
        proposals.value = prepared
        val reviewed = vm.state.value as SwapQuoteState.Success
        assertTrue(reviewed.primaryButton.isEnabled)
        proposals.value = null
        reviewed.primaryButton.onClick()
        coVerify(exactly = 0) { submit(any(), matchNullable { true }) }
        kotlin.test.assertIs<SwapQuoteState.Error>(vm.state.value)
        proposals.value = prepared
        (vm.state.value as SwapQuoteState.Success).primaryButton.onClick()
        coVerify(exactly = 1) { submit(prepared, refEq(publication)) }
    }

    @Test
    fun expiredReferenceBecomesUnavailableAndKeepsQuoteAvailable() = runTest {
        val prices = mockk<IndependentPriceProvider> {
            coEvery { check(any(), any()) } returns
                IndependentPriceCheck.Compared(BigDecimal("-20"), System.currentTimeMillis() - 1)
        }
        val vm = vm(zashi(), prices = prices)
        collect(vm)
        val state = vm.state.value as SwapQuoteState.Success
        assertTrue(state.primaryButton.isEnabled)
        assertTrue(state.priceCheck?.isWarning == false)
        assertFalse(state.priceCheck.showInline)
        assertEquals(
            "Price check unavailable",
            state.priceCheck.label.getString(RuntimeEnvironment.getApplication(), Locale.US),
        )
    }

    @Test
    fun slowCancellationCannotDelayConfirmationOrReplaceUnavailableWithALateWarning() = runTest {
        val prices = mockk<IndependentPriceProvider> {
            coEvery { check(any(), any()) } coAnswers {
                withContext(NonCancellable) { delay(12_000L) }
                IndependentPriceCheck.Compared(BigDecimal("-30"), System.currentTimeMillis() + 60_000)
            }
        }
        val vm = vm(zashi(), prices = prices)
        collect(vm)
        assertFalse((vm.state.value as SwapQuoteState.Success).primaryButton.isEnabled)

        testScheduler.advanceTimeBy(8_001L)
        testScheduler.runCurrent()
        val available = vm.state.value as SwapQuoteState.Success
        assertTrue(available.primaryButton.isEnabled)
        assertFalse(available.priceCheck?.isWarning ?: true)

        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        assertTrue((vm.state.value as SwapQuoteState.Success).primaryButton.isEnabled)
        assertFalse((vm.state.value as SwapQuoteState.Success).priceCheck?.isWarning ?: true)
    }

    @Test
    fun normalReferenceKeepsItsValueComparisonInDetails() = runTest {
        val prices = mockk<IndependentPriceProvider> {
            coEvery { check(any(), any()) } returns
                IndependentPriceCheck.Compared(BigDecimal("-2.5"), System.currentTimeMillis() + 60_000)
        }
        val vm = vm(zashi(), prices = prices)
        collect(vm)
        val notice = (vm.state.value as SwapQuoteState.Success).priceCheck ?: error("Missing comparison")
        assertFalse(notice.showInline)
        val row = assertNotNull(notice.detailItem)
        val result = (row.title as StyledStringResource.ByStringResource).resource
        assertEquals("2.50% less value", result.getString(RuntimeEnvironment.getApplication(), Locale.US))
    }

    private fun fromRowTitle(vm: SwapQuoteVM): Int {
        val state = assertNotNull(vm.state.value) as SwapQuoteState.Success
        return state.items
            .first()
            .title
            .resourceId()
    }

    private fun vm(
        account: WalletAccount,
        cancelQuote: CancelSwapQuoteUseCase = mockk(relaxed = true),
        prices: IndependentPriceProvider = mockk {
            coEvery { check(any(), any()) } returns IndependentPriceCheck.Unavailable
        },
        submit: SubmitProposalUseCase = mockk(relaxed = true),
        quotes: MutableStateFlow<SwapQuoteData?>? = null,
        quoteData: SwapQuoteData = SwapQuoteData.Success(
            quote = FakeSwapQuote(destinationAsset = destinationAsset, mode = SwapMode.EXACT_INPUT)
        ),
        proposals: MutableStateFlow<TransactionProposal?>? = null,
        sourceEntryId: String? = null,
    ): SwapQuoteVM {
        val quoteFlow = quotes ?: MutableStateFlow(quoteData)
        val proposalFlow = proposals ?: quoteFlow.map { data ->
            (data as? SwapQuoteData.Success)?.quote?.let { preparedQuote ->
                val sdkProposal = mockk<Proposal> { every { totalFeeRequired() } returns Zatoshi(1_000) }
                mockk<SwapTransactionProposal> {
                    every { quote } returns preparedQuote
                    every { fundingAccount } returns account
                    every { proposal } returns sdkProposal
                }
            }
        }
        return SwapQuoteVM(
            observeProposal =
                mockk {
                    every { observeNullable() } returns proposalFlow
                },
            observeSelectedWalletAccount =
                mockk {
                    every { this@mockk() } returns flowOf(account)
                },
            applicationStateProvider =
                mockk<ApplicationStateProvider> {
                    every { observeOnForeground() } returns emptyFlow()
                },
            swapRepository =
                mockk<SwapRepository>(relaxed = true) {
                    every { quote } returns
                        quoteFlow
                },
            cancelSwapQuote = cancelQuote,
            cancelSwap = mockk<CancelSwapUseCase>(relaxed = true),
            swapQuoteSuccessMapper = SwapQuoteVMMapper(),
            submitProposal = submit,
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            independentPrices = prices,
            sourceEntryId = sourceEntryId,
        )
    }

    private fun zashi(isSelected: Boolean = true) =
        ZodlAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            saplingAddress = "s",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun keystone(isSelected: Boolean = true) =
        KeystoneAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun ledger(isSelected: Boolean = true) =
        LedgerAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
            deviceIdentity = "tpk0-deadbeef",
            zip32AccountIndex = Zip32AccountIndex.new(0L),
        )

    private fun StringResource.resourceId(): Int = (this as StringResource.ByResource).resource

    private fun StyledStringResource.resourceId(): Int =
        (this as StyledStringResource.ByStringResource).resource.resourceId()

    private fun TestScope.collect(vm: SwapQuoteVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
    }
}
