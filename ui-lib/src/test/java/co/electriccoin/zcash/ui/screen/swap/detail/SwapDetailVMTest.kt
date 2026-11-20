package co.electriccoin.zcash.ui.screen.swap.detail

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.ReloadHandle
import co.electriccoin.zcash.ui.common.usecase.SwapData
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.component.ZashiMessageState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SwapDetailVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Currency-number builders also resolve the default ticker position through Android ICU.
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(FiatCurrency.USD)
        Dispatchers.resetMain()
    }

    @Test
    fun pendingSavedSwapSurvivesTheFirstStatusFailure() = runTest {
        val saved = saved(SwapStatus.PENDING)
        val harness = harness(SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved))

        assertSaved(harness.state(), saved)
        assertEquals(stringRes(R.string.swapAndPay_status_pendingDeposit), harness.state().transactionHeader.title)
        assertNotNull(harness.state().infoFooter)
        assertNeutralRetry(harness)
    }

    @Test
    fun completedSavedSwapSurvivesTheFirstStatusFailure() = runTest {
        val saved = saved(SwapStatus.SUCCESS)
        val harness = harness(SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved))

        assertSaved(harness.state(), saved)
        assertNeutralRetry(harness)
        // A saved output amount alone does not establish a fresh donation basis.
        assertNull(harness.state().donationBasisZec)
    }

    @Test
    fun refreshingSavedDataKeepsItsAmountAndStatusVisible() = runTest {
        val saved = saved(SwapStatus.PROCESSING)
        val harness = harness(SwapQuoteStatusData(isLoading = true, savedMetadata = saved))

        assertSaved(harness.state(), saved)
        assertEquals(stringRes(R.string.swapToZec_swapProcessing), harness.state().transactionHeader.title)
        assertNull(harness.state().primaryButton)
    }

    @Test
    fun aFailedRefreshRetainsTheLastLiveOutputAndRecipient() = runTest {
        val live = status(SwapStatus.SUCCESS)
        val harness = harness(SwapQuoteStatusData(status = live, isLoading = false))
        val previous = harness.state()

        harness.emit(SwapQuoteStatusData(status = live, isLoading = false, error = unavailable()))
        runCurrent()

        assertEquals(previous.transactionHeader, harness.state().transactionHeader)
        assertEquals(previous.recipient.message, harness.state().recipient.message)
        assertEquals(SwapStatus.SUCCESS, harness.state().status.status)
        assertEquals(stringResByNumber(OUTPUT), harness.state().transactionHeader.amount)
        assertNull(harness.state().errorFooter)
        assertNotNull(harness.state().infoFooter)
        assertNeutralRetry(harness)
    }

    @Test
    fun retryRequestsReloadAndCanRecoverToLiveStatus() = runTest {
        val harness = harness(
            SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved(SwapStatus.PENDING))
        )

        assertNotNull(harness.state().primaryButton).onClick()
        verify(exactly = 1) { harness.reload.requestReload() }
        harness.emit(SwapQuoteStatusData(status = status(SwapStatus.SUCCESS), isLoading = false))
        runCurrent()

        assertEquals(SwapStatus.SUCCESS, harness.state().status.status)
        assertNull(harness.state().errorFooter)
        assertNull(harness.state().infoFooter)
        assertNull(harness.state().primaryButton)
    }

    @Test
    fun unknownSwapKeepsTheLookupErrorAndRetry() = runTest {
        val harness = harness(SwapQuoteStatusData(isLoading = false, error = unavailable()))

        assertNull(harness.state().status.status)
        assertNull(harness.state().transactionHeader.amount)
        assertNotNull(harness.state().errorFooter)
        assertNotNull(harness.state().primaryButton).onClick()
        verify(exactly = 1) { harness.reload.requestReload() }
    }

    @Test
    fun genuineFailedAndRefundedStatusesKeepTheirSupportMessages() = runTest {
        for ((settlement, type) in listOf(
            SwapStatus.FAILED to ZashiMessageState.Type.ERROR,
            SwapStatus.REFUNDED to ZashiMessageState.Type.WARNING,
        )) {
            val harness = harness(SwapQuoteStatusData(status = status(settlement), isLoading = false))

            assertEquals(settlement, harness.state().status.status)
            assertEquals(type, assertNotNull(harness.state().message).type)
            assertEquals(
                stringRes(R.string.errorPage_action_contactSupport),
                assertNotNull(harness.state().primaryButton).text,
            )
            harness.state().primaryButton!!.onClick()
            verify(exactly = 1) { harness.router.forward(SwapSupportArgs(DEPOSIT)) }
        }
    }

    @Test
    fun backRemainsAvailableWhileTheFirstStatusRequestIsLoading() = runTest {
        val harness = harness(SwapQuoteStatusData(isLoading = true))

        harness.state().onBack()

        verify(exactly = 1) { harness.router.back() }
        assertNull(harness.state().errorFooter)
    }

    private fun assertSaved(state: SwapDetailState, saved: TransactionSwapMetadata) {
        assertEquals(saved.status, state.status.status)
        assertEquals(stringResByNumber(saved.amountOutFormatted), state.transactionHeader.amount)
        assertEquals(saved.origin.tokenIcon, state.transactionHeader.icons.first())
        assertNull(state.errorFooter)
    }

    private fun assertNeutralRetry(harness: Harness) {
        assertNotEquals(ButtonStyle.DESTRUCTIVE1, assertNotNull(harness.state().primaryButton).style)
    }

    private fun TestScope.harness(initial: SwapQuoteStatusData): Harness {
        val reload = mockk<ReloadHandle>(relaxed = true)
        val upstream = MutableStateFlow(SwapData(initial, reload))
        val source = mockk<GetReloadableSwapQuoteUseCase> {
            every { observe(DEPOSIT) } returns upstream
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val mapper = spyk(CommonTransactionDetailMapper())
        // Quote-row mapping has separate mapper/rendering coverage; keep this fixture focused
        // on detail state, saved metadata and refresh actions.
        every {
            mapper.createTransactionDetailQuoteHeaderState(any(), any(), any())
        } returns
            SwapQuoteHeaderState(null, null)
        val vm = SwapDetailVM(
            source, SwapDetailArgs(DEPOSIT), router, mockk(relaxed = true), mapper, SwapSupportMapper()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        return Harness(vm, upstream, reload, router)
    }

    private data class Harness(
        val vm: SwapDetailVM,
        val upstream: MutableStateFlow<SwapData>,
        val reload: ReloadHandle,
        val router: NavigationRouter,
    ) {
        fun state(): SwapDetailState = assertNotNull(vm.state.value)
        fun emit(data: SwapQuoteStatusData) {
            upstream.value = SwapData(data, reload)
        }
    }

    private fun saved(status: SwapStatus) = TransactionSwapMetadata(
        depositAddress = DEPOSIT,
        provider = "test-provider",
        totalFees = Zatoshi(0),
        totalFeesUsd = BigDecimal.ZERO,
        lastUpdated = Instant.EPOCH,
        origin = SwapAssetTestFixture.simpleAsset(),
        destination = SwapAssetTestFixture.zecSimpleAsset(),
        mode = SwapMode.EXACT_INPUT,
        recordedStatus = status,
        amountOutFormatted = OUTPUT,
    )

    private fun status(value: SwapStatus): SwapQuoteStatus = mockk {
        every { originAsset } returns SwapAssetTestFixture.asset()
        every { destinationAsset } returns SwapAssetTestFixture.zecAsset()
        every { depositAddress } returns mockk { every { address } returns DEPOSIT }
        every { destinationAddress } returns mockk { every { address } returns "test-zec-recipient" }
        every { timestamp } returns Instant.EPOCH
        every { status } returns value
        every { isSlippageRealized } returns false
        every { maxSlippage } returns BigDecimal.ONE
        every { amountOutFormatted } returns OUTPUT
        every { quotedAmountOut } returns OUTPUT
        every { executedAmountOut } returns OUTPUT.takeIf { value == SwapStatus.SUCCESS }
        every { originTransactionIds } returns emptyList()
        every { destinationTransactionIds } returns emptyList()
        every { refundedFormatted } returns BigDecimal("0.001").takeIf { value == SwapStatus.REFUNDED }
    }

    private fun unavailable() = IllegalStateException("Status refresh unavailable")

    private companion object {
        const val DEPOSIT = "test-btc-deposit"
        val OUTPUT = BigDecimal("1.23456789")
    }
}
