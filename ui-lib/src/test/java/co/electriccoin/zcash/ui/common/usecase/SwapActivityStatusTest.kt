package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.ActivityMapper
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SwapActivityStatusTest {
    private val id = TransactionId.new(ByteArray(32))
    private val deposit = "fixture-deposit"
    private val overview = mockk<TransactionOverview> {
        every { zip318Kind } returns Zip318Kind.NOT_CLASSIFIED
    }
    private val failed = SendTransaction.Failed(
        id, Zatoshi(100_000), null, 0, Zatoshi(10_000), emptyList(), overview, deposit
    )
    private val confirmed = SendTransaction.Success(
        id, failed.amount, Instant.EPOCH, 0, failed.fee, emptyList(), overview, deposit
    )

    @Test
    fun activityShowsSwappedWhenDetailsConfirmSuccessBeforeWalletSyncCatchesUp() = runTest {
        val storedSwap = swap(SwapStatus.PENDING)
        val metadata = MutableStateFlow(TransactionMetadata(false, true, null, storedSwap))
        val origin = SwapAssetTestFixture.zecAsset()
        val destination = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")
        val remote = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.SUCCESS
            every { mode } returns SwapMode.EXACT_INPUT
            every { amountOutFormatted } returns BigDecimal("0.0001")
            every { originAsset } returns origin
            every { destinationAsset } returns destination
        }
        val metadataRepository = mockk<MetadataRepository>(relaxed = true) {
            coEvery { getSwapMetadata(deposit) } returns storedSwap
            every { observeTransactionMetadata(failed) } returns metadata
            coEvery { updateSwap(storedSwap, any()) } answers {
                val status = secondArg<SwapQuoteStatus>()
                metadata.value = metadata.value.copy(swapMetadata = storedSwap.copy(recordedStatus = status.status))
                true
            }
        }
        val transactions = mockk<TransactionRepository> {
            every { observeTransaction(id.txIdString()) } returns flowOf(failed)
            coEvery { getMemos(failed) } returns emptyList()
            coEvery { resolveWalletAddress(deposit) } returns null
        }
        val contacts = mockk<AddressBookRepository> {
            every { observeContactByAddress(deposit) } returns flowOf(null)
        }
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(storedSwap) } returns remote
        }
        assertFailed(activity(failed, metadata.value.swapMetadata), stringRes(R.string.swapStatus_swapFailed))
        val details = GetTransactionDetailByIdUseCase(
            transactions, contacts, metadataRepository, GetSwapStatusUseCase(metadataRepository, swaps)
        ).observe(id.txIdString()).first { it.swap?.status != null }

        assertEquals(SwapStatus.SUCCESS, details.swap?.status?.status)
        assertEquals(SwapStatus.SUCCESS, storedSwap.outgoingStatus(failed, details.swap?.status?.status))
        coVerify(exactly = 1) {
            metadataRepository.updateSwap(storedSwap, remote)
        }
        val settled = activity(details.transaction, metadata.value.swapMetadata)
        assertEquals(stringRes(R.string.swapStatus_swapped), settled.title)
        assertEquals(R.drawable.ic_transaction_sent, settled.bigIcon)
        assertEquals(StringResourceColor.PRIMARY, settled.amountColor())
        assertEquals(settled.title, activity(confirmed, metadata.value.swapMetadata).title)
    }

    @Test
    fun providerFailureRemainsVisibleAfterTheFundingTransactionConfirms() {
        val metadata = swap(SwapStatus.FAILED)
        assertFailed(activity(confirmed, metadata), stringRes(R.string.swapStatus_swapFailed))
        assertEquals(SwapStatus.FAILED, metadata.outgoingStatus(confirmed, SwapStatus.FAILED))
    }

    @Test
    fun legacyMetadataWithoutARecordedStatusCannotOverrideALocalFailure() {
        assertFailed(activity(failed, swap(null)), stringRes(R.string.swapStatus_swapFailed))
        assertFailed(activity(failed, null), stringRes(R.string.transaction_history_sending_failed))
    }

    @Test
    fun crossPayUsesTheSameSettlementRule() {
        val metadata = swap(SwapStatus.SUCCESS).copy(mode = SwapMode.EXACT_OUTPUT)
        val paid = activity(failed, metadata)
        assertEquals(stringRes(R.string.swapStatus_paid), paid.title)
        assertEquals(R.drawable.ic_transaction_paid, paid.bigIcon)
        assertEquals(StringResourceColor.PRIMARY, paid.amountColor())
        val paymentFailed = activity(confirmed, metadata.copy(recordedStatus = SwapStatus.FAILED))
        assertEquals(stringRes(R.string.swapStatus_paymentFailed), paymentFailed.title)
        assertEquals(R.drawable.ic_transaction_pay_failed, paymentFailed.bigIcon)
        assertEquals(StringResourceColor.NEGATIVE, paymentFailed.amountColor())
    }

    private fun swap(status: SwapStatus?) =
        TransactionSwapMetadata(
            depositAddress = deposit,
            provider = "near",
            totalFees = Zatoshi(0),
            totalFeesUsd = BigDecimal.ZERO,
            lastUpdated = Instant.EPOCH,
            origin = SwapAssetTestFixture.zecSimpleAsset(),
            destination = SwapAssetTestFixture.simpleAsset(),
            mode = SwapMode.EXACT_INPUT,
            recordedStatus = status,
            amountOutFormatted = BigDecimal.ZERO
        )

    private fun activity(transaction: Transaction, swap: TransactionSwapMetadata?) =
        ActivityMapper().createTransactionState(
            ActivityData.ByTransaction(transaction, TransactionMetadata(false, true, null, swap)),
            Instant.EPOCH, {}, {}, {}
        )

    private fun assertFailed(activity: ActivityState, title: StringResource) {
        assertEquals(title, activity.title)
        assertEquals(R.drawable.ic_transaction_send_failed, activity.bigIcon)
        assertEquals(StringResourceColor.NEGATIVE, activity.amountColor())
    }

    private fun ActivityState.amountColor() = assertIs<StyledStringResource.ByStringResource>(value).style.color
}
