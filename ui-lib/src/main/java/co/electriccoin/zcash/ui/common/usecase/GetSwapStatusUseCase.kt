package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import co.electriccoin.zcash.ui.common.model.AndroidElapsedTimeSource
import co.electriccoin.zcash.ui.common.model.ElapsedTimeClock
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class GetSwapStatusUseCase(
    private val metadataRepository: MetadataRepository,
    private val swapRepository: SwapRepository,
    context: Context? = null,
    clock: Clock = Clock.System,
    timeSource: TimeSource = AndroidElapsedTimeSource,
) {
    private val donationPreferences = context?.let(::DonationPreferences)
    private val fundingClock = ElapsedTimeClock(clock, timeSource)
    private val refreshes = mutableMapOf<String, RefreshLock>()

    suspend operator fun invoke(depositAddress: String) = observe(depositAddress).first { !it.isLoading }

    internal suspend fun refreshOnce(expected: TransactionSwapMetadata) =
        observe(expected.depositAddress, expected).first { !it.isLoading }

    fun observe(depositAddress: String): Flow<SwapQuoteStatusData> = observe(depositAddress, null)

    internal fun observeForAccount(
        depositAddress: String,
        account: WalletAccount,
        canRefresh: (() -> Boolean)? = null,
    ): Flow<SwapQuoteStatusData> = observe(depositAddress, null, account, canRefresh)

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun observe(
        depositAddress: String,
        initial: TransactionSwapMetadata?,
        account: WalletAccount? = null,
        canRefresh: (() -> Boolean)? = null,
    ): Flow<SwapQuoteStatusData> =
        channelFlow {
            val data = MutableStateFlow(SwapQuoteStatusData())

            launch {
                data.collect { send(it) }
            }

            launch {
                val loaded = runCatchingRecoverable {
                    initial ?: metadataRepository.getSwapMetadata(depositAddress, account)
                }
                val expectedMetadata = loaded.getOrNull()

                if (expectedMetadata == null) {
                    data.update {
                        it.copy(
                            isLoading = false,
                            error = loaded.exceptionOrNull() as? Exception
                                ?: IllegalStateException("No metadata for deposit addr"),
                        )
                    }
                    return@launch
                }

                data.update { it.copy(savedMetadata = expectedMetadata) }

                var fundingExpiry: Job? = null
                while (true) {
                    try {
                        val requestStartedAt = fundingClock.now()
                        val (result, saved) = refresh(depositAddress, expectedMetadata, canRefresh)
                        donationPreferences?.recordSuccessfulSettlement(saved)
                        val fundingUntil = fundingDeadline(result, requestStartedAt)
                        fundingExpiry?.cancelAndJoin()
                        data.update {
                            it.copy(
                                status = result,
                                savedMetadata = saved,
                                isLoading = false,
                                error = null,
                                isFundingWindowOpen = fundingUntil != null && fundingUntil > fundingClock.now(),
                            )
                        }
                        fundingExpiry = fundingUntil?.let { deadline ->
                            launch {
                                waitForFundingExpiry(deadline)
                                data.update { it.copy(isFundingWindowOpen = false) }
                            }
                        }
                        if (result.status.isTerminal) {
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fundingExpiry?.cancel()
                        data.update { it.copy(isFundingWindowOpen = false) }
                        val latest = savedAfterFailure(expectedMetadata, e)
                        data.update { current ->
                            current.copy(
                                isLoading = false,
                                error = e,
                                savedMetadata = latest ?: current.savedMetadata,
                                status = current.status?.takeIf {
                                    latest == null || current.savedMetadata?.matchesSavedStatus(latest) == true
                                },
                            )
                        }
                    }
                    delay(30.seconds)
                }
            }

            awaitClose()
        }

    private fun fundingDeadline(status: SwapQuoteStatus, requestStartedAt: Instant): Instant? =
        status.takeIf { it.status == SwapStatus.INCOMPLETE_DEPOSIT }?.observedAtProvider?.let { issuedAt ->
            // HTTP Date is only precise to one second. Start its conservative budget before the
            // request so network, parsing and persistence time cannot extend the funding window.
            minOf(status.deadline, requestStartedAt + (status.deadline - issuedAt - 1.seconds))
        }

    private suspend fun waitForFundingExpiry(displayUntil: Instant) {
        var timeLeft = displayUntil - fundingClock.now()
        while (timeLeft.isPositive()) {
            // Re-evaluate device time on resume or after a clock correction.
            delay(minOf(timeLeft, 1.seconds))
            timeLeft = displayUntil - fundingClock.now()
        }
    }

    private suspend fun savedAfterFailure(
        expected: TransactionSwapMetadata,
        error: Exception,
    ): TransactionSwapMetadata? =
        if (error is SwapRefreshNotSavedException) {
            error.savedMetadata
        } else {
            runCatchingRecoverable { metadataRepository.reloadSwapMetadata(expected) }.getOrNull()
        }

    private fun TransactionSwapMetadata.matchesSavedStatus(saved: TransactionSwapMetadata): Boolean =
        this === saved || (statusUpdatedAt == saved.statusUpdatedAt && status == saved.status &&
            (executedAmountOut == null) == (saved.executedAmountOut == null) &&
            amountOutFormatted.compareTo(saved.amountOutFormatted) == 0 &&
            observedRefundAmount == saved.observedRefundAmount)

    private suspend fun refresh(
        depositAddress: String,
        expected: TransactionSwapMetadata,
        canRefresh: (() -> Boolean)?,
    ): Pair<SwapQuoteStatus, TransactionSwapMetadata> {
        val refresh = synchronized(refreshes) {
            refreshes.getOrPut(depositAddress, ::RefreshLock).also { it.users++ }
        }
        try {
            return refresh.mutex.withLock {
                check(canRefresh?.invoke() != false) { "Swap status refresh is no longer active" }
                val result = swapRepository.checkSwapStatus(expected, canRefresh)
                check(canRefresh?.invoke() != false) { "Swap status refresh is no longer active" }
                val saved = metadataRepository.updateSwap(expected, result)
                    ?: throw SwapRefreshNotSavedException(metadataRepository.reloadSwapMetadata(expected))
                result to saved
            }
        } finally {
            synchronized(refreshes) {
                if (--refresh.users == 0) refreshes.remove(depositAddress)
            }
        }
    }

    private class RefreshLock {
        val mutex = Mutex()
        var users = 0
    }

    private class SwapRefreshNotSavedException(val savedMetadata: TransactionSwapMetadata?) :
        IllegalStateException("Swap status refresh was not saved")
}

private fun DonationPreferences.recordSuccessfulSettlement(saved: TransactionSwapMetadata) {
    if (saved.status == SwapStatus.SUCCESS) recordSuccessfulTransaction()
}

data class SwapQuoteStatusData(
    val status: SwapQuoteStatus? = null,
    val isLoading: Boolean = true,
    val error: Exception? = null,
    val savedMetadata: TransactionSwapMetadata? = null,
    val isFundingWindowOpen: Boolean = false,
) {
    val originAsset = status?.originAsset
    val destinationAsset = status?.destinationAsset

    internal fun withSavedObservation(observed: TransactionSwapMetadata): SwapQuoteStatusData {
        val saved = savedMetadata ?: return copy(savedMetadata = observed)
        val previousAt = saved.statusUpdatedAt
        val observedAt = observed.statusUpdatedAt
        return when {
            !saved.matchesStoredSwap(observed) -> withoutLiveStatus(observed)
            previousAt != null && (observedAt == null || observedAt < previousAt) -> this
            previousAt == observedAt && saved.matchesDisplayStatus(observed) -> copy(savedMetadata = observed)
            else -> withoutLiveStatus(observed)
        }
    }

    private fun withoutLiveStatus(observed: TransactionSwapMetadata) =
        copy(status = null, savedMetadata = observed, isFundingWindowOpen = false)

    private fun TransactionSwapMetadata.matchesDisplayStatus(other: TransactionSwapMetadata): Boolean =
        recordedStatus == other.recordedStatus && mode == other.mode &&
            executedAmountOut == other.executedAmountOut && quotedAmountOut == other.quotedAmountOut &&
            observedRefundAmount == other.observedRefundAmount
}
