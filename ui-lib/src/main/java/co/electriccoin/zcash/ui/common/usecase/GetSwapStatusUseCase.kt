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

    @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    fun observe(depositAddress: String): Flow<SwapQuoteStatusData> =
        channelFlow {
            val data = MutableStateFlow(SwapQuoteStatusData())

            launch {
                data.collect { send(it) }
            }

            launch {
                val expectedMetadata = metadataRepository.getSwapMetadata(depositAddress)

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
                        val result = refresh(depositAddress, expectedMetadata)
                        if (result.status == SwapStatus.SUCCESS) {
                            donationPreferences?.recordSuccessfulTransaction()
                        }
                        data.update {
                            it.copy(
                                status = result,
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
                        data.update {
                            it.copy(
                                isLoading = false,
                                error = e,
                                savedMetadata = latest ?: it.savedMetadata,
                                status = it.status?.takeIf { live -> latest == null || live.matchesSavedStatus(latest) },
                            )
                        }
                    }
                    delay(30.seconds)
                }
            }

            awaitClose()
        }

    private fun SwapQuoteStatus.matchesSavedStatus(saved: TransactionSwapMetadata): Boolean =
        status == saved.status && amountOutFormatted.compareTo(saved.amountOutFormatted) == 0

    private suspend fun refresh(depositAddress: String, expected: TransactionSwapMetadata): SwapQuoteStatus {
        val refresh = synchronized(refreshes) {
            refreshes.getOrPut(depositAddress, ::RefreshLock).also { it.users++ }
        }
        try {
            return refresh.mutex.withLock {
                val result = swapRepository.checkSwapStatus(expected)
                if (!metadataRepository.updateSwap(expected, result)) {
                    throw SwapRefreshNotSavedException(metadataRepository.reloadSwapMetadata(expected))
                }
                result
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
}
