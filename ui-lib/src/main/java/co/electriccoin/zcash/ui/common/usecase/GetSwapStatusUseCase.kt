package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

class GetSwapStatusUseCase(
    private val metadataRepository: MetadataRepository,
    private val swapRepository: SwapRepository,
    context: Context? = null,
) {
    private val donationPreferences = context?.let(::DonationPreferences)
    private val refreshes = mutableMapOf<String, RefreshLock>()

    suspend operator fun invoke(depositAddress: String) = observe(depositAddress).first { !it.isLoading }

    internal suspend fun refreshOnce(expected: TransactionSwapMetadata) =
        observe(expected.depositAddress, expected).first { !it.isLoading }

    fun observe(depositAddress: String): Flow<SwapQuoteStatusData> = observe(depositAddress, null)

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun observe(depositAddress: String, initial: TransactionSwapMetadata?): Flow<SwapQuoteStatusData> =
        channelFlow {
            val data = MutableStateFlow(SwapQuoteStatusData())

            launch {
                data.collect { send(it) }
            }

            launch {
                val expectedMetadata = initial ?: metadataRepository.getSwapMetadata(depositAddress)

                if (expectedMetadata == null) {
                    data.update {
                        it.copy(
                            isLoading = false,
                            error = IllegalStateException("No metadata for deposit addr")
                        )
                    }
                    return@launch
                }

                data.update { it.copy(savedMetadata = expectedMetadata) }

                while (true) {
                    try {
                        val (result, saved) = refresh(depositAddress, expectedMetadata)
                        if (result.status == SwapStatus.SUCCESS) {
                            donationPreferences?.recordSuccessfulTransaction()
                        }
                        data.update {
                            it.copy(
                                status = result,
                                savedMetadata = saved,
                                isLoading = false,
                                error = null
                            )
                        }
                        if (result.status.isTerminal) {
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val latest = (e as? SwapRefreshNotSavedException)?.savedMetadata
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

    private fun TransactionSwapMetadata.matchesSavedStatus(saved: TransactionSwapMetadata): Boolean =
        status == saved.status &&
            (executedAmountOut == null) == (saved.executedAmountOut == null) &&
            amountOutFormatted.compareTo(saved.amountOutFormatted) == 0

    private suspend fun refresh(
        depositAddress: String,
        expected: TransactionSwapMetadata,
    ): Pair<SwapQuoteStatus, TransactionSwapMetadata> {
        val refresh = synchronized(refreshes) {
            refreshes.getOrPut(depositAddress, ::RefreshLock).also { it.users++ }
        }
        try {
            return refresh.mutex.withLock {
                val result = swapRepository.checkSwapStatus(expected)
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

data class SwapQuoteStatusData(
    val status: SwapQuoteStatus? = null,
    val isLoading: Boolean = true,
    val error: Exception? = null,
    val savedMetadata: TransactionSwapMetadata? = null,
) {
    val originAsset = status?.originAsset
    val destinationAsset = status?.destinationAsset

    internal fun withSavedObservation(observed: TransactionSwapMetadata): SwapQuoteStatusData {
        val saved = savedMetadata ?: return copy(savedMetadata = observed)
        val previousAt = saved.statusUpdatedAt
        val observedAt = observed.statusUpdatedAt
        return when {
            !saved.matchesStoredSwap(observed) -> copy(status = null, savedMetadata = observed)
            previousAt != null && (observedAt == null || observedAt < previousAt) -> this
            saved.matchesDisplayStatus(observed) -> copy(savedMetadata = observed)
            else -> copy(status = null, savedMetadata = observed)
        }
    }

    private fun TransactionSwapMetadata.matchesDisplayStatus(other: TransactionSwapMetadata): Boolean =
        recordedStatus == other.recordedStatus && mode == other.mode &&
            executedAmountOut == other.executedAmountOut && quotedAmountOut == other.quotedAmountOut
}
