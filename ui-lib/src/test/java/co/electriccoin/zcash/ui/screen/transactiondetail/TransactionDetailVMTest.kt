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
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
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
    fun ordinaryReceivedDetailUsesTheLocalReceiptWithoutSwapControls() = runTest(dispatcher) {
        val received = ReceiveTransaction.Success(
            TransactionId.new(ByteArray(32) { 2 }), Zatoshi(123_000_000), Instant.EPOCH,
            0, emptyList(), overview, null
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
        assertNull(info.status)
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
            assertNull(savedInfo.status, assetId)
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
                    vm.state.first { (it.info as? SendSwapState)?.status == SwapStatus.SUCCESS }
                },
                assetId,
            )

            assertLocalFunding(recovered, assetId)
            val liveInfo = assertIs<SendSwapState>(recovered.info, assetId)
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
    fun actualProviderFailureShowsFailureAndRoutesToSwapSupport() = runTest(dispatcher) {
        val remote = status(SwapStatus.FAILED, SwapAssetTestFixture.zecAsset(), SwapAssetTestFixture.asset())
        val state = state(data(sent, swapMetadata(SwapStatus.PENDING), SwapQuoteStatusData(remote, false)))
        assertEquals(stringRes(R.string.swapStatus_swapFailed), state.header.title)
        assertEquals(stringRes(sent.amount, HIDDEN), state.header.amount)
        val info = assertIs<SendSwapState>(state.info)
        assertEquals(SwapStatus.FAILED, info.status)
        assertEquals(stringRes(R.string.swapAndPay_failedTitle), info.message?.title)
        assertNull(info.donationBasisZec)
        assertNull(state.errorFooter)
        assertEquals(stringRes(R.string.errorPage_action_contactSupport), state.primaryButton?.text)

        assertNotNull(state.primaryButton).onClick()
        verify(exactly = 1) { router.forward(SwapSupportArgs("fixture-deposit")) }
        verify(exactly = 0) { reload.requestReload() }
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
        metadata = TransactionMetadata(false, true, null, metadata),
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
            flipTransactionBookmark = mockk(relaxed = true),
            mapper = CommonTransactionDetailMapper(),
            getSwapMessage = SwapSupportMapper()
        )
        store.put("detail", vm)
        return vm
    }
}
