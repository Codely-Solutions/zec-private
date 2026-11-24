package co.electriccoin.zcash.ui.screen.swap.detail

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapOutputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.SwapInputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.ActivityDetailUnavailableException
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.ReloadHandle
import co.electriccoin.zcash.ui.common.usecase.SwapData
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.ZashiMessageState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SwapDetailVMTest {
    private val harnesses = mutableListOf<Harness>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Currency-number builders also resolve the default ticker position through Android ICU.
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        harnesses.forEach(Harness::close)
        harnesses.clear()
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
    fun savedProblemStatusesOfferGenericSupportWithoutSignedEvidence() = runTest {
        val outcomes = listOf(SwapStatus.INCOMPLETE_DEPOSIT, SwapStatus.REFUNDED, SwapStatus.FAILED, SwapStatus.EXPIRED)
        for (status in outcomes) {
            val harness = harness(SwapQuoteStatusData(
                isLoading = false, error = unavailable(), savedMetadata = saved(status),
            ))
            val support = assertNotNull(harness.state().supportButton)
            assertEquals(stringRes(R.string.errorPage_action_contactSupport), support.text)
            support.onClick()
            verify(exactly = 1) { harness.router.forward(SwapSupportArgs(DEPOSIT)) }
            assertNeutralRetry(harness)
        }
    }

    @Test
    fun trackedOutgoingSwapShowsUnconfirmedFundingAndOffersStatusRetry() = runTest {
        val saved = saved(
            SwapStatus.PENDING,
            origin = SwapAssetTestFixture.zecSimpleAsset(),
            destination = SwapAssetTestFixture.simpleAsset(),
        ).copy(mode = SwapMode.EXACT_OUTPUT)
        val harness = harness(SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved))

        val detail = harness.state()
        assertEquals(stringRes(R.string.private_swap_funding_unconfirmed), detail.transactionHeader.title)
        assertEquals(TransactionDetailSwapStatusRowState.Mode.SWAP_FROM_ZEC, detail.status.mode)
        assertNull(detail.donationBasisZec)
        assertNeutralRetry(harness)

        harness.emit(SwapQuoteStatusData(
            isLoading = false, error = unavailable(), savedMetadata = saved.copy(fundingNotSubmitted = true),
        ))
        runCurrent()
        assertEquals(stringRes(R.string.private_swap_funding_not_sent), harness.state().transactionHeader.title)
        assertEquals(true, harness.state().status.fundingNotSubmitted)
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
        assertEquals(stringRes(R.string.transactionHistory_timestamp), harness.state().timestamp.title)
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
    fun savedIncomingSwapsKeepTheirOutputAndRecoverAfterARefreshFailure() = runTest {
        val zec = SwapCatalogTestFixture.nativeZec
        val executedOutput = BigDecimal("1.23456788")
        for (origin in SwapCatalogTestFixture.externalAssets) {
            val assetId = origin.assetId
            val saved = saved(
                SwapStatus.PROCESSING,
                SwapCatalogTestFixture.simple(origin),
                SwapCatalogTestFixture.simple(zec),
            )
            val loading = SwapQuoteStatusData(isLoading = true, savedMetadata = saved)
            val harness = harness(loading)

            val refreshing = harness.state(assetId)
            assertSaved(refreshing, saved, assetId)
            assertEquals(stringRes(R.string.private_swap_refreshing), refreshing.infoFooter, assetId)
            assertNull(refreshing.primaryButton, assetId)

            harness.emit(loading.copy(isLoading = false, error = unavailable()))
            runCurrent()

            val failedRefresh = harness.state(assetId)
            assertSaved(failedRefresh, saved, assetId)
            assertEquals(stringRes(R.string.private_swap_refresh_unavailable), failedRefresh.infoFooter, assetId)
            val retry = assertNotNull(failedRefresh.primaryButton, assetId)
            assertEquals(
                stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                retry.text,
                assetId,
            )
            assertEquals(ButtonStyle.SECONDARY, retry.style, assetId)
            retry.onClick()
            assertEquals(1, harness.reloadRequests(), assetId)

            harness.emit(
                loading.copy(status = status(SwapStatus.SUCCESS, origin, zec, executedOutput), isLoading = false)
            )
            runCurrent()

            val recovered = harness.state(assetId)
            assertEquals(SwapStatus.SUCCESS, recovered.status.status, assetId)
            assertEquals(stringRes(R.string.swapAndPay_status), recovered.status.title, assetId)
            assertEquals(stringResByNumber(executedOutput), recovered.transactionHeader.amount, assetId)
            assertEquals(origin.tokenIcon, recovered.transactionHeader.icons.first(), assetId)
            val quoteOrigin = assertNotNull(recovered.quoteHeader.from, assetId)
            assertEquals(origin.tokenIcon, quoteOrigin.bigIcon, assetId)
            assertEquals(origin.chainIcon, quoteOrigin.smallIcon, assetId)
            val quoteDestination = assertNotNull(recovered.quoteHeader.to, assetId)
            assertEquals(stringRes(zec.tokenTicker), quoteDestination.token, assetId)
            assertEquals(stringResByNumber(executedOutput), quoteDestination.amount, assetId)
            assertNull(recovered.errorFooter, assetId)
            assertNull(recovered.infoFooter, assetId)
            assertNull(recovered.primaryButton, assetId)
            harness.close()
        }
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
    fun savedExecutionRemainsVisibleWithSparseStatusAndDuringAnOutage() = runTest {
        val execution = BigDecimal("1.23456789")
        val saved = saved(SwapStatus.SUCCESS).copy(
            amountOutFormatted = execution,
            quotedAmountOut = OUTPUT,
            executedAmountOut = execution,
        )
        val sparse = status(SwapStatus.SUCCESS)
        every { sparse.executedAmountOut } returns null
        val harness = harness(SwapQuoteStatusData(status = sparse, isLoading = false, savedMetadata = saved))
        val detail = harness.state()
        assertEquals(stringRes(R.string.private_saved_provider_output), detail.transactionHeader.title)
        assertEquals(stringResByNumber(execution), detail.transactionHeader.amount)
        assertEquals(stringResByNumber(execution), detail.quoteHeader.to?.amount)
        assertEquals(stringRes(""), detail.quoteHeader.to?.fiatAmount)

        harness.emit(SwapQuoteStatusData(isLoading = false, savedMetadata = saved, error = unavailable()))
        runCurrent()
        assertEquals(detail.transactionHeader.amount, harness.state().transactionHeader.amount)
        assertEquals(detail.transactionHeader.title, harness.state().transactionHeader.title)
    }

    @Test
    fun sparseLiveQuoteDoesNotReplaceLegacySavedOutput() = runTest {
        val original = BigDecimal("1.23456789")
        val saved = saved(SwapStatus.SUCCESS).copy(amountOutFormatted = original)
        val sparse = status(SwapStatus.SUCCESS)
        every { sparse.executedAmountOut } returns null
        val harness = harness(SwapQuoteStatusData(status = sparse, isLoading = false, savedMetadata = saved))
        val detail = harness.state()
        val savedOutput = stringRes(R.string.private_swap_saved_output)
        assertEquals(savedOutput, detail.transactionHeader.title)
        assertEquals(stringResByNumber(original), detail.transactionHeader.amount)
        assertEquals(stringResByNumber(original), detail.quoteHeader.to?.amount)
        assertEquals(stringRes(""), detail.quoteHeader.to?.fiatAmount)
        assertEquals(sparse.destinationAsset.chainName + stringRes(" · ") + savedOutput, detail.quoteHeader.to?.chain)

        val reported = BigDecimal("2.23456789")
        val complete = status(SwapStatus.SUCCESS, output = reported)
        harness.emit(SwapQuoteStatusData(status = complete, isLoading = false, savedMetadata = saved))
        runCurrent()
        assertEquals(stringRes(R.string.private_provider_output), harness.state().transactionHeader.title)
        assertEquals(stringResByNumber(reported), harness.state().transactionHeader.amount)
    }

    @Test
    fun incomingDetailKeepsUsdWithItsQuotedOrExecutedOutput() = runTest {
        val fixture = SwapOutputProvenanceFixture
        val origin = SwapCatalogTestFixture.externalAssets.first()
        val destination = SwapCatalogTestFixture.nativeZec
        val harness = harness(SwapQuoteStatusData(isLoading = true))
        for ((hasAmount, hasUsd, expectedUsd) in listOf(
            Triple(false, true, fixture.quotedUsd),
            Triple(true, false, null),
            Triple(true, true, fixture.executedUsd),
        )) {
            val status = fixture.status(origin, destination, DEPOSIT, SwapType.FLEX_INPUT, hasAmount, hasUsd)
            harness.emit(SwapQuoteStatusData(status = status, isLoading = false))
            runCurrent()
            val output = assertNotNull(harness.state().quoteHeader.to)
            val label = if (hasAmount) R.string.private_provider_output else R.string.private_quoted_output
            val fiat = expectedUsd?.let { stringResByDynamicCurrencyNumber(it, FiatCurrency.USD.symbol) }
            assertEquals(destination.chainName + stringRes(" · ") + stringRes(label), output.chain)
            assertEquals(fiat ?: stringRes(""), output.fiatAmount)
        }
    }

    @Test
    fun incomingDetailPairsSparseInputAndUsdWithSavedOutputMetadata() = runTest {
        val origin = SwapCatalogTestFixture.externalAssets.first()
        val destination = SwapCatalogTestFixture.nativeZec
        val harness = harness(SwapQuoteStatusData(isLoading = true))
        for ((fields, amount, usd) in SwapInputProvenanceFixture.cases) {
            val status = SwapInputProvenanceFixture.status(origin, destination, DEPOSIT, SwapType.FLEX_INPUT, fields)
            harness.emit(SwapQuoteStatusData(status = status, isLoading = false))
            runCurrent()
            val input = assertNotNull(harness.state().quoteHeader.from)
            val inputNumber = (input.amount as StringResource.ByNumber).number as BigDecimal
            assertEquals(0, BigDecimal(amount).compareTo(inputNumber), fields)
            assertEquals(usd?.let { stringResByDynamicCurrencyNumber(BigDecimal(it), FiatCurrency.USD.symbol) }
                ?: stringRes(""), input.fiatAmount, fields)
            if (status.executedAmountIn == null) {
                val retained = saved(SwapStatus.SUCCESS).copy(executedAmountOut = BigDecimal("0.9"))
                harness.emit(SwapQuoteStatusData(status = status, isLoading = false, savedMetadata = retained))
                runCurrent()
                assertEquals(input.amount, harness.state().quoteHeader.from?.amount)
                assertEquals(input.fiatAmount, harness.state().quoteHeader.from?.fiatAmount)
            }
        }
    }

    @Test
    fun offlineRefundShowsItsSavedStatusUntilTheProviderSuppliesTheAmount() = runTest {
        val saved = saved(SwapStatus.REFUNDED)
        val harness = harness(SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved))
        assertEquals(SwapStatus.REFUNDED, harness.state().status.status)
        assertEquals(stringRes(R.string.private_swap_saved_status), harness.state().status.title)
        assertNull(harness.state().refundedAmount)

        val live = status(SwapStatus.REFUNDED)
        val amount = BigDecimal("0.001")
        every { live.refundedFormatted } returns amount
        harness.emit(SwapQuoteStatusData(status = live, isLoading = false, savedMetadata = saved))
        runCurrent()
        val refund = assertNotNull(harness.state().refundedAmount)
        assertEquals(stringResByCurrencyNumber(amount, live.originAsset.tokenTicker).withStyle(), refund.message)
    }

    @Test
    fun backRemainsAvailableWhileTheFirstStatusRequestIsLoading() = runTest {
        val harness = harness(SwapQuoteStatusData(isLoading = true))

        harness.state().onBack()

        verify(exactly = 1) { harness.router.back() }
        assertNull(harness.state().errorFooter)
    }

    @Test
    fun expiredAndSavedOnlyPendingDetailsKeepANonActionableDepositReference() = runTest {
        val copy = mockk<CopyToClipboardUseCase>(relaxed = true)
        val pending = status(SwapStatus.PENDING)
        every { pending.deadline } returns kotlin.time.Instant.parse("2000-01-01T00:00:00Z")
        val harness = harness(SwapQuoteStatusData(status = pending, isLoading = false), copy)

        val liveReference = assertNotNull(harness.state().depositTo)
        assertEquals(stringRes(R.string.private_swap_deposit_reference), liveReference.title)
        assertNull(liveReference.onClick)
        assertNull(liveReference.trailingIcon)

        harness.emit(
            SwapQuoteStatusData(isLoading = false, error = unavailable(), savedMetadata = saved(SwapStatus.PENDING))
        )
        runCurrent()
        val savedReference = assertNotNull(harness.state().depositTo)
        assertEquals(stringRes(R.string.private_swap_deposit_reference), savedReference.title)
        assertNull(savedReference.onClick)
        verify(exactly = 0) { copy(DEPOSIT) }
    }

    @Test
    fun stoppedDetailDiscardsItsPreviouslyRenderedFundingAdvice() = runTest {
        val status = SwapInputProvenanceFixture.status(
            SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
            SwapAssetTestFixture.zecAsset(),
            DEPOSIT,
            SwapType.FLEX_INPUT,
            """{"depositedAmount":"10000000"}""",
        ).let { live ->
            val returned = live.response.quoteResponse
            live.copy(response = live.response.copy(
                status = co.electriccoin.zcash.ui.common.model.near.SwapStatus.INCOMPLETE_DEPOSIT,
                quoteResponse = returned.copy(quote = returned.quote.copy(
                    deadline = kotlin.time.Instant.parse("2099-01-01T00:00:00Z"),
                )),
            ))
        }
        val harness = harness(SwapQuoteStatusData(
            status = status, isLoading = false, savedMetadata = saved(SwapStatus.INCOMPLETE_DEPOSIT)
                .copy(reviewedIdentity = "v2:fixture"),
            isFundingWindowOpen = true,
        ))
        assertNotNull(harness.state().message)
        harness.collector.cancel()
        advanceTimeBy(ANDROID_STATE_FLOW_TIMEOUT.inWholeMilliseconds + 1)
        runCurrent()
        assertNull(harness.vm.state.value)
    }

    @Test
    fun ownerRejectionClearsThePreviouslyVisibleDepositReference() = runTest {
        val harness = harness(
            SwapQuoteStatusData(isLoading = false, savedMetadata = saved(SwapStatus.PENDING)),
            accountKeyId = "fixture-owner",
        )
        assertNotNull(harness.state().depositTo)

        harness.emit(SwapQuoteStatusData(isLoading = false, error = ActivityDetailUnavailableException()))
        runCurrent()

        assertNull(harness.state().depositTo)
        assertNull(harness.state().supportButton)
        assertNotNull(harness.state().errorFooter)
    }

    private fun assertSaved(state: SwapDetailState, saved: TransactionSwapMetadata, assetId: String? = null) {
        assertEquals(saved.status, state.status.status, assetId)
        assertEquals(stringRes(R.string.private_swap_saved_status), state.status.title, assetId)
        assertEquals(stringRes(R.string.private_swap_created_at), state.timestamp.title, assetId)
        assertEquals(
            stringResByDateTime(saved.lastUpdated.atZone(ZoneId.systemDefault()), useFullFormat = true).withStyle(),
            state.timestamp.message,
            assetId,
        )
        assertEquals(stringResByNumber(saved.amountOutFormatted), state.transactionHeader.amount, assetId)
        assertEquals(saved.origin.tokenIcon, state.transactionHeader.icons.first(), assetId)
        assertNull(state.errorFooter, assetId)
    }

    private fun assertNeutralRetry(harness: Harness) {
        assertNotEquals(ButtonStyle.DESTRUCTIVE1, assertNotNull(harness.state().primaryButton).style)
    }

    private fun TestScope.harness(
        initial: SwapQuoteStatusData,
        clipboard: CopyToClipboardUseCase = mockk(relaxed = true),
        accountKeyId: String? = null,
    ): Harness {
        var requests = 0
        val reload = mockk<ReloadHandle> {
            every { requestReload() } answers { requests += 1 }
        }
        val upstream = MutableStateFlow(SwapData(initial, reload))
        val source = mockk<GetReloadableSwapQuoteUseCase> {
            every { observe(DEPOSIT, accountKeyId) } returns upstream
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val vm = SwapDetailVM(
            source, SwapDetailArgs(DEPOSIT, accountKeyId), router, clipboard,
            CommonTransactionDetailMapper(), SwapSupportMapper(), mockk()
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        return Harness(vm, upstream, reload, router, collector) { requests }.also(harnesses::add)
    }

    private data class Harness(
        val vm: SwapDetailVM,
        val upstream: MutableStateFlow<SwapData>,
        val reload: ReloadHandle,
        val router: NavigationRouter,
        val collector: Job,
        val reloadRequests: () -> Int,
    ) {
        fun state(assetId: String? = null): SwapDetailState = assertNotNull(vm.state.value, assetId)
        fun emit(data: SwapQuoteStatusData) {
            upstream.value = SwapData(data, reload)
        }

        fun close() {
            collector.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun saved(
        status: SwapStatus,
        origin: SimpleSwapAsset = SwapAssetTestFixture.simpleAsset(),
        destination: SimpleSwapAsset = SwapAssetTestFixture.zecSimpleAsset(),
    ) = TransactionSwapMetadata(
        depositAddress = DEPOSIT,
        provider = "test-provider",
        totalFees = Zatoshi(0),
        totalFeesUsd = BigDecimal.ZERO,
        lastUpdated = Instant.EPOCH,
        origin = origin,
        destination = destination,
        mode = SwapMode.FLEX_INPUT,
        recordedStatus = status,
        amountOutFormatted = OUTPUT,
    )

    private fun status(
        value: SwapStatus,
        origin: SwapAsset = SwapAssetTestFixture.asset(),
        destination: SwapAsset = SwapAssetTestFixture.zecAsset(),
        output: BigDecimal = OUTPUT,
    ): SwapQuoteStatus = mockk {
        every { originAsset } returns origin
        every { destinationAsset } returns destination
        every { depositAddress } returns mockk { every { address } returns DEPOSIT }
        every { destinationAddress } returns mockk { every { address } returns "test-zec-recipient" }
        every { timestamp } returns Instant.EPOCH
        every { status } returns value
        every { mode } returns SwapMode.FLEX_INPUT
        every { isSlippageRealized } returns false
        every { maxSlippage } returns BigDecimal.ONE
        every { amountOutFormatted } returns output
        every { amountInFormatted } returns BigDecimal.ONE
        every { amountInUsd } returns BigDecimal("30")
        every { amountOutUsd } returns BigDecimal("29")
        every { quotedAmountOut } returns OUTPUT
        every { executedAmountOut } returns output.takeIf { value == SwapStatus.SUCCESS }
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
