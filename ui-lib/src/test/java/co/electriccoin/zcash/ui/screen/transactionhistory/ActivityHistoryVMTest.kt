package co.electriccoin.zcash.ui.screen.transactionhistory

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.mapper.ActivityMapper
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.ActivityData
import co.electriccoin.zcash.ui.common.usecase.FilteredActivities
import co.electriccoin.zcash.ui.common.usecase.GetFilteredActivitiesUseCase
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.GetTransactionFiltersUseCase
import co.electriccoin.zcash.ui.common.usecase.UpdateSwapActivityMetadataUseCase
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityHistoryVMTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val updateMetadata = mockk<UpdateSwapActivityMetadataUseCase>(relaxed = true)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() = runTest(dispatcher) {
        try {
            store.clear()
            viewModelJobs.joinAll()
        } finally {
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun incomingPendingSwapShowsReceivingProgressAndOpensSwapDetails() = runTest(dispatcher) {
        assertIncoming(
            SwapStatus.PENDING,
            R.string.transaction_history_swapping,
            R.drawable.ic_transaction_receive_pending,
            StringResourceColor.PRIMARY
        )
    }

    @Test
    fun incomingCompletedSwapShowsReceivedFundsAndOpensSwapDetails() = runTest(dispatcher) {
        assertIncoming(
            SwapStatus.SUCCESS,
            R.string.swapStatus_swapped,
            R.drawable.ic_transaction_received,
            StringResourceColor.PRIMARY
        )
    }

    @Test
    fun incomingFailedSwapKeepsFailureVisibleAndOpensSwapDetails() = runTest(dispatcher) {
        assertIncoming(
            SwapStatus.FAILED,
            R.string.swapStatus_swapFailed,
            R.drawable.ic_transaction_receive_failed,
            StringResourceColor.NEGATIVE
        )
    }

    @Test
    fun incomingRefundedSwapKeepsRefundVisibleAndOpensSwapDetails() = runTest(dispatcher) {
        assertIncoming(
            SwapStatus.REFUNDED,
            R.string.swapStatus_swapRefunded,
            R.drawable.ic_transaction_receive_failed,
            StringResourceColor.NEGATIVE
        )
    }

    @Test
    fun outgoingTransactionRemainsAvailableWhenTheUnreadMarkerCannotBeRead() = runTest(dispatcher) {
        val owner = EditAccountFixtures.zodl().sdkAccount.accountUuid
        val overview = mockk<TransactionOverview> {
            every { zip318Kind } returns Zip318Kind.NOT_CLASSIFIED
        }
        val transaction = SendTransaction.Success(
            id = TransactionId.new(ByteArray(32) { 1 }),
            amount = Zatoshi(123_456_789),
            timestamp = Instant.EPOCH,
            memoCount = 1,
            fee = Zatoshi(10_000),
            transactionOutputs = emptyList(),
            overview = overview,
            recipient = "fixture-recipient",
            accountUuid = owner,
        )
        val activity = ActivityData.ByTransaction(transaction, TransactionMetadata(false, false, null, null))
        val restoreTimestamp = mockk<RestoreTimestampDataSource> {
            coEvery { getOrCreate() } throws IOException("Unread storage temporarily unavailable")
        }
        val row = row(activity, restoreTimestamp)
        assertFalse(row.isUnread)
        val filteredRow = row(
            activity,
            restoreTimestamp,
            FilteredActivities(listOf(activity), unreadFilterApplied = true, unreadCutoff = Instant.EPOCH),
        )
        assertEquals(true, filteredRow.isUnread)
        coVerify(exactly = 1) { restoreTimestamp.getOrCreate() }

        assertEquals(transaction.id.txIdString(), row.key)
        assertEquals(stringRes(R.string.transaction_sent), row.title)
        assertEquals(R.drawable.ic_transaction_sent, row.bigIcon)
        val value = assertIs<StyledStringResource.ByStringResource>(row.value)
        assertEquals(stringRes("- ") + stringRes(transaction.amount), value.resource)
        assertEquals(StringResourceColor.PRIMARY, value.style.color)

        row.onClick()
        verify(exactly = 1) {
            router.forward(TransactionDetailArgs(transaction.id.txIdString(), owner.toStorageKeyId()))
        }
        confirmVerified(router)
    }

    private suspend fun assertIncoming(status: SwapStatus, title: Int, icon: Int, color: StringResourceColor) {
        val owner = EditAccountFixtures.zodl()
        val swap = TransactionSwapMetadata(
            depositAddress = "fixture-incoming-deposit",
            provider = "near",
            totalFees = Zatoshi(10_000),
            totalFeesUsd = BigDecimal("0.01"),
            lastUpdated = Instant.EPOCH,
            origin = SwapAssetTestFixture.simpleAsset(),
            destination = SwapAssetTestFixture.zecSimpleAsset(),
            mode = SwapMode.FLEX_INPUT,
            recordedStatus = status,
            amountOutFormatted = BigDecimal("1.23456789"),
            ownerAccount = owner,
        )
        val activity = ActivityData.BySwap(swap)
        val row = row(activity)

        assertEquals(swap.depositAddress, row.key)
        assertEquals(stringRes(title), row.title)
        assertEquals(icon, row.bigIcon)
        assertFalse(row.isUnread)
        assertFalse(row.isShielded)
        val value = assertIs<StyledStringResource.ByStringResource>(row.value)
        val savedAmount = stringResByCurrencyNumber(BigDecimal("1.23456789"), CURRENCY_TICKER)
        assertEquals(stringRes(R.string.private_activity_saved_amount, savedAmount), value.resource)
        assertEquals(color, value.style.color)

        row.onClick()
        verify(exactly = 1) {
            router.forward(SwapDetailArgs(swap.depositAddress, owner.sdkAccount.accountUuid.toStorageKeyId()))
        }
        confirmVerified(router)
        row.onDisplayed()
        verify(exactly = 1) { updateMetadata(activity) }
    }

    private suspend fun row(
        activity: ActivityData,
        restoreTimestamp: RestoreTimestampDataSource = mockk {
            coEvery { getOrCreate() } returns Instant.EPOCH
        },
        snapshot: FilteredActivities = FilteredActivities(listOf(activity)),
    ): ActivityState {
        val filters = TransactionFilterRepositoryImpl()
        val activities = mockk<GetFilteredActivitiesUseCase> {
            every { observe() } returns flowOf(snapshot)
        }
        val vm = ActivityHistoryVM(
            getFilteredActivities = activities,
            getTransactionFilters = GetTransactionFiltersUseCase(filters),
            transactionFilterRepository = filters,
            applyTransactionFulltextFilters = mockk(relaxed = true),
            activityMapper = ActivityMapper(),
            navigationRouter = router,
            resetTransactionFilters = mockk(relaxed = true),
            restoreTimestampDataSource = restoreTimestamp,
            updateSwapActivityMetadata = updateMetadata
        )
        viewModelJobs += vm.viewModelScope.coroutineContext.job
        store.put("history-${viewModelJobs.size}", vm)
        // The VM explicitly maps on Dispatchers.Default. Bound that real dispatcher without
        // advancing a virtual timeout ahead of its work.
        val state = withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.state.filterIsInstance<ActivityHistoryState.Data>().first() }
        }
        return state.items.filterIsInstance<ActivityHistoryItem.Activity>().single().state
    }
}
