package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.SwapOutputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.SwapInputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.GetTransactionDetailByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.ReloadHandle
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.design.R as DesignR
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailVMTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val clipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
    private val reload = mockk<ReloadHandle>(relaxed = true)
    private val overview = mockk<TransactionOverview> {
        every { zip318Kind } returns Zip318Kind.NOT_CLASSIFIED
    }
    private val sent = SendTransaction.Success(
        TransactionId.new(ByteArray(32) { 1 }), Zatoshi(123_456_789), Instant.EPOCH,
        0, Zatoshi(10_000), emptyList(), overview, "fixture-deposit"
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        unmockkObject(FiatCurrency.USD)
        Dispatchers.resetMain()
    }

    @Test
    fun ordinarySentDetailKeepsLocalAmountFeeAndTransactionId() = runTest(dispatcher) {
        val state = state(data(sent))
        assertEquals(stringRes(R.string.transaction_sent), state.header.title)
        assertEquals(stringRes(sent.amount, HIDDEN), state.header.amount)
        val info = assertIs<SendShieldedState>(state.info)
        assertFalse(info.isPending)
        assertEquals(stringRes(Zatoshi(10_000)), info.fee)
        assertEquals(stringResByTransactionId(sent.id.txIdString(), abbreviated = true), info.transactionId)
        assertNull(state.errorFooter)
        assertNull(state.infoFooter)
        assertEquals(listOf(sent.id.txIdString()), state.explorerLinks.map { it.transactionId })

        info.onTransactionIdClick()
        verify(exactly = 1) { clipboard(value = sent.id.txIdString()) }
    }

    @Test
    fun savedRefundOffersGenericSupportDuringAProviderOutage() = runTest(dispatcher) {
        val saved = swapMetadata(SwapStatus.REFUNDED)
        val observation = SwapQuoteStatusData(isLoading = false, error = IllegalStateException("fixture outage"))
        val detail = state(data(sent, saved, observation))
        val support = assertNotNull(detail.secondaryButton)
        assertEquals(stringRes(R.string.errorPage_action_contactSupport), support.text)
        support.onClick()
        verify(exactly = 1) { router.forward(SwapSupportArgs(saved.depositAddress)) }
    }

    @Test
    fun ordinaryReceivedDetailUsesTheLocalReceiptWithoutSwapControls() = runTest(dispatcher) {
        val received = ReceiveTransaction.Success(
            TransactionId.new(ByteArray(32) { 2 }), Zatoshi(123_000_000), Instant.EPOCH,
            0, emptyList(), overview, null, accountUuid = cash.z.ecc.android.sdk.model.AccountUuid.new(ByteArray(16))
        )
        val state = state(data(received))
        assertEquals(stringRes(R.string.transaction_received), state.header.title)
        assertEquals(stringRes(received.amount, HIDDEN), state.header.amount)
        val info = assertIs<ReceiveTransparentState>(state.info)
        assertFalse(info.isPending)
        assertEquals(stringResByTransactionId(received.id.txIdString(), abbreviated = true), info.transactionId)
        assertNull(state.primaryButton)
        assertNull(state.errorFooter)
        assertNotNull(state.secondaryButton)
    }

    @Test
    fun localSendFailureRemainsALocalTransactionFailure() = runTest(dispatcher) {
        val failed = SendTransaction.Failed(
            sent.id, sent.amount, null, 0, sent.fee, emptyList(), overview, sent.recipient
        )
        val state = state(data(failed))
        assertEquals(stringRes(R.string.transaction_history_sending_failed), state.header.title)
        assertEquals(stringRes(failed.amount, HIDDEN), state.header.amount)
        assertIs<SendShieldedState>(state.info)
        assertNull(state.errorFooter)
        assertEquals(stringRes(R.string.transactionHistory_saveAddress), state.primaryButton?.text)
    }

    @Test
    fun outgoingPendingProviderStatusRespectsLocalNoSubmissionAndExpiry() = runTest(dispatcher) {
        val failed = SendTransaction.Failed(
            sent.id, sent.amount, null, 0, sent.fee, emptyList(), overview, sent.recipient
        )
        val status = status(SwapStatus.PENDING, SwapAssetTestFixture.zecAsset(), SwapAssetTestFixture.asset())
        val local = swapMetadata(SwapStatus.PENDING)
        for (unsubmitted in listOf(true, false)) {
            val saved = local.copy(fundingNotSubmitted = unsubmitted)
            val detail = state(data(failed, saved, SwapQuoteStatusData(status, isLoading = false)))
            val info = assertIs<SendSwapState>(detail.info)
            val title = if (unsubmitted) R.string.private_swap_funding_not_sent else R.string.swapStatus_swapFailed
            assertEquals(stringRes(title), detail.header.title)
            assertEquals(if (unsubmitted) SwapStatus.PENDING else SwapStatus.FAILED, info.statusRow.status)
            assertEquals(unsubmitted, info.statusRow.fundingNotSubmitted)
            assertEquals(stringRes(R.string.private_swap_funding_status), info.statusRow.title)
            assertNull(detail.infoFooter)
        }
    }

    @Test
    fun unavailableOutgoingProviderKeepsLocalFundingDetailsAndRetriesLookup() = runTest(dispatcher) {
        val state = state(
            data(
                sent,
                swapMetadata(SwapStatus.PENDING),
                SwapQuoteStatusData(
                    isLoading = false,
                    error = IllegalStateException("fixture-provider-unavailable")
                )
            )
        )
        assertEquals(stringRes(R.string.swapStatus_swapping), state.header.title)
        assertEquals(stringRes(sent.amount, HIDDEN), state.header.amount)
        val info = assertIs<SendSwapState>(state.info)
        assertEquals(SwapStatus.PENDING, info.statusRow.status)
        assertEquals(stringRes(R.string.private_swap_saved_status), info.statusRow.title)
        assertFalse(info.statusRow.isLoading)
        assertNull(info.message)
        assertEquals(stringRes(Zatoshi(10_000)), info.totalFees)
        assertEquals(stringResByTransactionId(sent.id.txIdString(), abbreviated = true), info.transactionId)
        assertNull(info.donationBasisZec)
        assertNotNull(state.errorFooter)
        assertEquals(listOf(sent.id.txIdString()), state.explorerLinks.map { it.transactionId })
        assertEquals(
            stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
            state.primaryButton?.text
        )

        info.onTransactionIdClick()
        verify(exactly = 1) { clipboard(value = sent.id.txIdString()) }
        assertNotNull(state.primaryButton).onClick()
        verify(exactly = 1) { reload.requestReload() }
        verify(exactly = 0) { router.forward(any()) }
    }

    @Test
    fun outgoingSwapsKeepLocalFundingDetailsAndRecoverAfterAProviderOutage() = runTest(dispatcher) {
        val zec = SwapCatalogTestFixture.nativeZec
        for (destination in SwapCatalogTestFixture.externalAssets) {
            val assetId = destination.assetId
            var reloadRequests = 0
            val reloadHandle = mockk<ReloadHandle> {
                every { requestReload() } answers { reloadRequests += 1 }
            }
            val metadata = swapMetadata(
                SwapStatus.PENDING,
                SwapCatalogTestFixture.simple(zec),
                SwapCatalogTestFixture.simple(destination),
            )
            val unavailable = data(
                sent,
                metadata,
                SwapQuoteStatusData(isLoading = false, error = IllegalStateException("fixture-provider-unavailable")),
            ).copy(reloadHandle = reloadHandle)
            val upstream = MutableStateFlow(unavailable)
            val vm = viewModel(unavailable, upstream)
            val failedRefresh = assertNotNull(
                withTimeoutOrNull(10_000) { vm.state.first { it.info != null } },
                assetId,
            )

            assertLocalFunding(failedRefresh, assetId)
            val savedInfo = assertIs<SendSwapState>(failedRefresh.info, assetId)
            assertEquals(SwapStatus.PENDING, savedInfo.statusRow.status, assetId)
            assertEquals(stringRes(R.string.private_swap_saved_status), savedInfo.statusRow.title, assetId)
            assertFalse(savedInfo.statusRow.isLoading, assetId)
            assertNull(savedInfo.donationBasisZec, assetId)
            assertNotNull(failedRefresh.errorFooter, assetId)
            val retry = assertNotNull(failedRefresh.primaryButton, assetId)
            assertEquals(
                stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                retry.text,
                assetId,
            )
            retry.onClick()
            assertEquals(1, reloadRequests, assetId)

            val fresh = status(SwapStatus.SUCCESS, zec, destination)
            upstream.value = unavailable.copy(swap = SwapQuoteStatusData(fresh, isLoading = false))
            val recovered = assertNotNull(
                withTimeoutOrNull(10_000) {
                    vm.state.first { (it.info as? SendSwapState)?.statusRow?.status == SwapStatus.SUCCESS }
                },
                assetId,
            )

            assertLocalFunding(recovered, assetId)
            val liveInfo = assertIs<SendSwapState>(recovered.info, assetId)
            assertEquals(stringRes(R.string.swapAndPay_status), liveInfo.statusRow.title, assetId)
            assertFalse(liveInfo.statusRow.isLoading, assetId)
            val quoteDestination = assertNotNull(liveInfo.quoteHeader.to, assetId)
            assertEquals(destination.tokenIcon, quoteDestination.bigIcon, assetId)
            assertEquals(destination.chainIcon, quoteDestination.smallIcon, assetId)
            assertEquals(stringRes(destination.tokenTicker), quoteDestination.token, assetId)
            assertEquals(stringResByNumber(fresh.amountOutFormatted), quoteDestination.amount, assetId)
            assertNull(recovered.errorFooter, assetId)
            assertNull(recovered.primaryButton, assetId)
            store.clear()
        }
        verify(exactly = 0) { router.forward(any()) }
    }

    @Test
    fun outgoingPaymentsShowSavedTerminalOutcomesWithoutInventingRefundAmounts() = runTest(dispatcher) {
        val error = IllegalStateException("fixture-provider-unavailable")
        for (outcome in listOf(SwapStatus.SUCCESS, SwapStatus.REFUNDED)) {
            val metadata = swapMetadata(outcome).copy(mode = SwapMode.EXACT_OUTPUT)
            val detail = state(data(sent, metadata, SwapQuoteStatusData(isLoading = false, error = error)))
            val info = assertIs<SendSwapState>(detail.info)
            assertEquals(outcome, info.statusRow.status)
            assertEquals(stringRes(R.string.private_swap_saved_status), info.statusRow.title)
            assertFalse(info.statusRow.isLoading)
            assertNull(info.refundedAmount)
            assertNull(info.donationBasisZec)
            assertLocalFunding(detail, outcome.name)
            assertNotNull(detail.primaryButton)
            store.clear()
        }
    }

    @Test
    fun failedPollKeepsTheLastAcceptedStatusAndRetry() = runTest(dispatcher) {
        val zec = SwapCatalogTestFixture.nativeZec
        val destination = SwapCatalogTestFixture.externalAssets.first()
        val accepted = status(SwapStatus.PENDING, zec, destination)
        val saved = swapMetadata(SwapStatus.PENDING, SwapCatalogTestFixture.simple(zec))
        val detail = state(
            data(
                sent,
                saved,
                SwapQuoteStatusData(
                    status = accepted,
                    isLoading = false,
                    error = IllegalStateException("fixture-poll-unavailable"),
                    savedMetadata = saved,
                ),
            )
        )
        val info = assertIs<SendSwapState>(detail.info)
        assertEquals(SwapStatus.PENDING, info.statusRow.status)
        assertEquals(stringRes(R.string.private_swap_saved_status), info.statusRow.title)
        assertFalse(info.statusRow.isLoading)
        assertLocalFunding(detail, "failed poll")
        assertNotNull(detail.primaryButton).onClick()
        verify(exactly = 1) { reload.requestReload() }
    }

    @Test
    fun failedPollKeepsBothRetryAndSupportForAnIncompleteDeposit() = runTest(dispatcher) {
        val zec = SwapCatalogTestFixture.nativeZec
        val destination = SwapCatalogTestFixture.externalAssets.first()
        val accepted = status(SwapStatus.INCOMPLETE_DEPOSIT, zec, destination)
        val saved = swapMetadata(SwapStatus.INCOMPLETE_DEPOSIT, SwapCatalogTestFixture.simple(zec))
        val detail = state(
            data(
                sent,
                saved,
                SwapQuoteStatusData(
                    status = accepted,
                    isLoading = false,
                    error = IllegalStateException("fixture-poll-unavailable"),
                    savedMetadata = saved,
                ),
            )
        )

        assertEquals(SwapStatus.INCOMPLETE_DEPOSIT, assertIs<SendSwapState>(detail.info).statusRow.status)
        assertNotNull(detail.errorFooter)
        val retry = assertNotNull(detail.primaryButton)
        val support = assertNotNull(detail.secondaryButton)
        assertEquals(stringRes(DesignR.string.disconnectHWWallet_tryAgain), retry.text)
        assertEquals(stringRes(R.string.errorPage_action_contactSupport), support.text)
        retry.onClick()
        support.onClick()
        verify(exactly = 1) { reload.requestReload() }
        verify(exactly = 1) { router.forward(SwapSupportArgs("fixture-deposit")) }
    }

    @Test
    fun advancingSavedStatusUpdatesHeaderAndRowWhenRefreshIsUnavailable() = runTest(dispatcher) {
        val zec = SwapCatalogTestFixture.nativeZec
        val destination = SwapCatalogTestFixture.externalAssets.first()
        val cached = swapMetadata(SwapStatus.PENDING).copy(statusUpdatedAt = Instant.ofEpochSecond(1))
        val failedRefresh = SwapQuoteStatusData(
            status = status(SwapStatus.PENDING, zec, destination),
            isLoading = false,
            error = IllegalStateException("fixture-provider-offline"),
            savedMetadata = cached,
        )
        val opened = data(sent, cached, failedRefresh)
        val source = MutableStateFlow(opened)
        val vm = viewModel(opened, source)
        val before = vm.state.first { it.info is SendSwapState }
        assertEquals(stringRes(R.string.swapStatus_swapping), before.header.title)
        assertEquals(SwapStatus.PENDING, assertIs<SendSwapState>(before.info).statusRow.status)

        source.value = opened.copy(
            metadata = opened.metadata.copy(
                swapMetadata = cached.copy(
                    recordedStatus = SwapStatus.SUCCESS,
                    statusUpdatedAt = Instant.ofEpochSecond(2),
                )
            )
        )
        val after = withTimeout(10_000) {
            vm.state.first { it.header.title == stringRes(R.string.swapStatus_swapped) }
        }
        val visible = assertIs<SendSwapState>(after.info).statusRow
        assertEquals(stringRes(R.string.private_swap_saved_status), visible.title)
        assertEquals(SwapStatus.SUCCESS, visible.status)
    }

    @Test
    fun outgoingStatusWithoutASavedResultStopsLoadingAfterFailure() = runTest(dispatcher) {
        val metadata = swapMetadata(SwapStatus.PENDING).copy(
            recordedStatus = null,
            amountOutFormatted = BigDecimal.ZERO,
        )
        val loading = data(sent, metadata, SwapQuoteStatusData(savedMetadata = metadata))
        val upstream = MutableStateFlow(loading)
        val vm = viewModel(loading, upstream)
        val initial = assertIs<SendSwapState>(vm.state.first { it.info != null }.info)
        assertNull(initial.statusRow.status)
        assertTrue(initial.statusRow.isLoading)

        upstream.value =
            loading.copy(
                swap = SwapQuoteStatusData(
                    isLoading = false,
                    error = IllegalStateException("fixture-provider-unavailable"),
                    savedMetadata = metadata,
                )
            )
        val failed = vm.state.first { it.errorFooter != null }
        val info = assertIs<SendSwapState>(failed.info)
        assertNull(info.statusRow.status)
        assertFalse(info.statusRow.isLoading)
        assertEquals(stringRes(R.string.swapAndPay_status), info.statusRow.title)
        assertLocalFunding(failed, "unavailable")
        assertNotNull(failed.primaryButton)
    }

    @Test
    fun actualProviderFailureShowsFailureAndRoutesToSwapSupport() = runTest(dispatcher) {
        val remote = status(SwapStatus.FAILED, SwapAssetTestFixture.zecAsset(), SwapAssetTestFixture.asset())
        val state = state(data(sent, swapMetadata(SwapStatus.PENDING), SwapQuoteStatusData(remote, false)))
        assertEquals(stringRes(R.string.swapStatus_swapFailed), state.header.title)
        assertEquals(stringRes(sent.amount, HIDDEN), state.header.amount)
        val info = assertIs<SendSwapState>(state.info)
        assertEquals(SwapStatus.FAILED, info.statusRow.status)
        assertEquals(stringRes(R.string.swapAndPay_failedTitle), info.message?.title)
        assertNull(info.donationBasisZec)
        assertNull(state.errorFooter)
        assertEquals(stringRes(R.string.errorPage_action_contactSupport), state.primaryButton?.text)

        assertNotNull(state.primaryButton).onClick()
        verify(exactly = 1) { router.forward(SwapSupportArgs("fixture-deposit")) }
        verify(exactly = 0) { reload.requestReload() }
    }

    @Test
    fun outgoingDetailKeepsUsdWithItsQuotedOrExecutedOutput() = runTest(dispatcher) {
        val fixture = SwapOutputProvenanceFixture
        val origin = SwapCatalogTestFixture.nativeZec
        val destination = SwapCatalogTestFixture.externalAssets.first()
        val saved = swapMetadata(SwapStatus.SUCCESS, SwapCatalogTestFixture.simple(origin))
        for ((hasAmount, hasUsd, expectedUsd) in listOf(
            Triple(false, true, fixture.quotedUsd),
            Triple(true, false, null),
            Triple(true, true, fixture.executedUsd),
        )) {
            val status = fixture.status(
                origin, destination, saved.depositAddress, SwapType.EXACT_INPUT, hasAmount, hasUsd
            )
            val detail = state(data(sent, saved, SwapQuoteStatusData(status = status, isLoading = false)))
            val output = assertNotNull(assertIs<SendSwapState>(detail.info).quoteHeader.to)
            val label = if (hasAmount) R.string.private_provider_output else R.string.private_quoted_output
            val fiat = expectedUsd?.let { stringResByDynamicCurrencyNumber(it, FiatCurrency.USD.symbol) }
            assertEquals(destination.chainName + stringRes(" · ") + stringRes(label), output.chain)
            assertEquals(fiat ?: stringRes(""), output.fiatAmount)
            if (!hasAmount) {
                val sparse = SwapQuoteStatusData(status = status, isLoading = false, savedMetadata = saved)
                val legacy = assertIs<SendSwapState>(state(data(sent, saved, sparse)).info).quoteHeader.to
                assertEquals(stringResByNumber(saved.amountOutFormatted), legacy?.amount)
                assertEquals(stringRes(""), legacy?.fiatAmount)
            }
        }
    }

    @Test
    fun outgoingDetailPairsSparseInputAndUsdWithSavedOutputMetadata() = runTest(dispatcher) {
        val origin = SwapCatalogTestFixture.nativeZec
        val destination = SwapCatalogTestFixture.externalAssets.first()
        val saved = swapMetadata(SwapStatus.SUCCESS, SwapCatalogTestFixture.simple(origin))
        for (mode in listOf(SwapType.EXACT_INPUT, SwapType.EXACT_OUTPUT)) {
            for ((fields, amount, usd) in SwapInputProvenanceFixture.cases) {
                val status = SwapInputProvenanceFixture.status(origin, destination, saved.depositAddress, mode, fields)
                val detail = state(data(sent, saved, SwapQuoteStatusData(status = status, isLoading = false)))
                val input = assertNotNull(assertIs<SendSwapState>(detail.info).quoteHeader.from)
                val inputNumber = (input.amount as StringResource.ByNumber).number as BigDecimal
                assertEquals(0, BigDecimal(amount).compareTo(inputNumber), "$mode $fields")
                assertEquals(usd?.let { stringResByDynamicCurrencyNumber(BigDecimal(it), FiatCurrency.USD.symbol) }
                    ?: stringRes(""), input.fiatAmount, "$mode $fields")
                if (status.executedAmountIn == null) {
                    val retained = saved.copy(executedAmountOut = BigDecimal("0.9"))
                    val sparse = SwapQuoteStatusData(status = status, isLoading = false, savedMetadata = retained)
                    val row = assertIs<SendSwapState>(state(data(sent, retained, sparse)).info).quoteHeader.from
                    assertEquals(input.amount, row?.amount)
                    assertEquals(input.fiatAmount, row?.fiatAmount)
                }
            }
        }
    }

    private fun assertLocalFunding(state: TransactionDetailState, assetId: String) {
        assertEquals(stringRes(sent.amount, HIDDEN), state.header.amount, assetId)
        val info = assertIs<SendSwapState>(state.info, assetId)
        assertEquals(stringRes(Zatoshi(10_000)), info.totalFees, assetId)
        assertEquals(stringResByTransactionId(sent.id.txIdString(), abbreviated = true), info.transactionId, assetId)
        assertEquals(listOf(sent.id.txIdString()), state.explorerLinks.map { it.transactionId }, assetId)
    }

    private fun status(value: SwapStatus, origin: SwapAsset, destination: SwapAsset): SwapQuoteStatus =
        object : SwapQuoteStatus {
            override val originAsset = origin
            override val destinationAsset = destination
            override val depositAddress = DynamicSwapAddress("fixture-deposit")
            override val destinationAddress = DynamicSwapAddress("fixture-recipient")
            override val timestamp = Instant.EPOCH
            override val deadline = kotlin.time.Instant.fromEpochSeconds(0)
            override val status = value
            override val mode = SwapMode.EXACT_INPUT
            override val amountInFormatted = BigDecimal.ONE
            override val amountOutFormatted = BigDecimal("0.0001")
            override val executedAmountOut = amountOutFormatted.takeIf { value == SwapStatus.SUCCESS }
            override val amountInUsd = BigDecimal("30")
            override val amountOutUsd = BigDecimal("29")
            override val refundedFormatted: BigDecimal? = null
            override val depositedAmountFormatted = BigDecimal.ONE
            override val maxSlippage = BigDecimal.ONE
            override val isSlippageRealized = false
        }
    private fun swapMetadata(
        status: SwapStatus,
        origin: SimpleSwapAsset = SwapAssetTestFixture.zecSimpleAsset(),
        destination: SimpleSwapAsset = SwapAssetTestFixture.simpleAsset(),
    ) = TransactionSwapMetadata(
        depositAddress = "fixture-deposit",
        provider = "near",
        totalFees = Zatoshi(10_000),
        totalFeesUsd = BigDecimal("0.01"),
        lastUpdated = Instant.EPOCH,
        origin = origin,
        destination = destination,
        mode = SwapMode.EXACT_INPUT,
        recordedStatus = status,
        amountOutFormatted = BigDecimal("0.0001")
    )

    private fun data(
        transaction: Transaction,
        metadata: TransactionSwapMetadata? = null,
        swap: SwapQuoteStatusData? = null
    ) = DetailedTransactionData(
        transaction = transaction,
        memos = emptyList(),
        contact = null,
        metadata = TransactionMetadata(false, true, null, metadata, bookmarkWriteToken = 0),
        swap = swap,
        reloadHandle = reload,
        recipient = null
    )

    private suspend fun state(data: DetailedTransactionData): TransactionDetailState =
        withTimeout(10_000) { viewModel(data, flowOf(data)).state.first { it.info != null } }

    private fun viewModel(data: DetailedTransactionData, upstream: Flow<DetailedTransactionData>): TransactionDetailVM {
        val details = mockk<GetTransactionDetailByIdUseCase> {
            every { observe(data.transaction.id.txIdString()) } returns upstream
        }
        val vm = TransactionDetailVM(
            getTransactionDetailById = details,
            markTxMemoAsRead = mockk(relaxed = true),
            transactionDetailArgs = TransactionDetailArgs(data.transaction.id.txIdString()),
            copyToClipboard = clipboard,
            navigationRouter = router,
            sendTransactionAgain = mockk(relaxed = true),
            setTransactionBookmark = mockk(relaxed = true),
            mapper = CommonTransactionDetailMapper(),
            getSwapMessage = SwapSupportMapper()
        )
        store.put("detail", vm)
        return vm
    }
}
