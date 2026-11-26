package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataReadUnavailableException
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import co.electriccoin.zcash.ui.common.model.AndroidElapsedTimeSource
import co.electriccoin.zcash.ui.common.model.ElapsedTimeClock
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
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
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
    private val accounts: AccountDataSource? = null,
    private val accountSelection: SelectedAccountUUIDProvider? = null,
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
                val generation = initial?.walletWriteToken ?: walletWriteAccess.token()
                val owner = (account ?: initial?.ownerAccount)?.sdkAccount?.accountUuid
                val canRequest = runCatchingRecoverable { requestAdmission(generation, owner, canRefresh) }
                    .getOrElse { error ->
                        data.value = SwapQuoteStatusData(isLoading = false, error = error as Exception)
                        return@launch
                    }
                val loaded = runCatchingRecoverable {
                    check(canRequest()) { "Swap status refresh is no longer active" }
                    initial ?: metadataRepository.loadExpectedMetadata(
                        depositAddress, account, data, canRequest,
                    )
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
                        val (result, saved) = refresh(depositAddress, expectedMetadata, canRequest)
                        donationPreferences?.recordSuccessfulSettlement(saved)
                        val fundingUntil = result.fundingDeadline(requestStartedAt)
                        fundingExpiry?.cancelAndJoin()
                        data.update {
                            it.copy(
                                status = result,
                                savedMetadata = saved,
                                isLoading = false,
                                error = null,
                                isFundingWindowOpen = isFundingWindowOpen(fundingUntil),
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
                        if (!canRequest()) {
                            data.value = SwapQuoteStatusData(isLoading = false, error = e)
                            break
                        }
                        data.update { it.copy(isFundingWindowOpen = false) }
                        val latest = savedAfterFailure(expectedMetadata, e)
                        data.update { it.withRefreshFailure(e, latest) }
                    }
                    delay(30.seconds)
                }
            }

            awaitClose()
        }

    private suspend fun requestAdmission(
        generation: Long,
        owner: AccountUuid?,
        canRefresh: (() -> Boolean)?,
    ): () -> Boolean {
        val observer = currentCoroutineContext().job
        val selection = accountSelection?.captureSelection()
        return {
            val sameSelection = selection == null || checkNotNull(accountSelection).isCurrent(selection)
            val currentOwner = accounts?.allAccounts?.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid
            val ownerMatches = owner == null || accounts == null || currentOwner == owner
            observer.isActive && walletWriteAccess.permits(generation) && canRefresh?.invoke() != false &&
                sameSelection && ownerMatches
        }
    }

    private fun isFundingWindowOpen(deadline: Instant?): Boolean =
        deadline != null && deadline > fundingClock.now()

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

    private suspend fun refresh(
        depositAddress: String,
        expected: TransactionSwapMetadata,
        canRequest: () -> Boolean,
    ): Pair<SwapQuoteStatus, TransactionSwapMetadata> {
        val refresh = synchronized(refreshes) {
            refreshes.getOrPut(depositAddress, ::RefreshLock).also { it.users++ }
        }
        try {
            return refresh.mutex.withLock {
                check(canRequest()) { "Swap status refresh is no longer active" }
                val result = swapRepository.checkSwapStatus(expected, canRequest)
                check(canRequest()) { "Swap status refresh is no longer active" }
                val saved = metadataRepository.updateSwap(expected, result)
                    ?: throw SwapRefreshNotSavedException(
                        runCatchingRecoverable { metadataRepository.reloadSwapMetadata(expected) }.getOrNull()
                    )
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

private fun SwapQuoteStatus.fundingDeadline(requestStartedAt: Instant): Instant? =
    takeIf { status == SwapStatus.INCOMPLETE_DEPOSIT }?.observedAtProvider?.let { issuedAt ->
        // HTTP Date is only precise to one second. Start its conservative budget before the
        // request so network, parsing and persistence time cannot extend the funding window.
        minOf(deadline, requestStartedAt + (deadline - issuedAt - 1.seconds))
    }

private suspend fun MetadataRepository.loadExpectedMetadata(
    depositAddress: String,
    account: WalletAccount?,
    data: MutableStateFlow<SwapQuoteStatusData>,
    canRequest: () -> Boolean,
): TransactionSwapMetadata? {
    while (canRequest()) {
        try {
            val saved = getSwapMetadata(depositAddress, account)
            return saved.takeIf { canRequest() }
        } catch (e: MetadataReadUnavailableException) {
            data.update { it.copy(isLoading = false, error = e) }
            delay(1.seconds)
        }
    }
    return null
}

private fun TransactionSwapMetadata.matchesSavedStatus(saved: TransactionSwapMetadata): Boolean =
    this === saved || (statusUpdatedAt == saved.statusUpdatedAt && status == saved.status &&
        (executedAmountOut == null) == (saved.executedAmountOut == null) &&
        amountOutFormatted.compareTo(saved.amountOutFormatted) == 0 &&
        observedRefundAmount == saved.observedRefundAmount)

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

    internal fun withRefreshFailure(error: Exception, latest: TransactionSwapMetadata?): SwapQuoteStatusData =
        copy(
            isLoading = false,
            error = error,
            savedMetadata = latest ?: savedMetadata,
            status = status?.takeIf { latest == null || savedMetadata?.matchesSavedStatus(latest) == true },
        )

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
