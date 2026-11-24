package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class GetReloadableSwapQuoteUseCase(
    private val getSwapStatus: GetSwapStatusUseCase,
    private val metadataRepository: MetadataRepository? = null,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(depositAddress: String) =
        channelFlow {
            val requestSwipeReloadPipeline = MutableSharedFlow<Unit>()

            val reloadHandle =
                object : ReloadHandle {
                    override fun requestReload() {
                        launch {
                            requestSwipeReloadPipeline.emit(Unit)
                        }
                    }
                }

            val swapFlow =
                requestSwipeReloadPipeline
                    .onStart { emit(Unit) }
                    .flatMapLatest { getSwapStatus.observe(depositAddress) }

            val accepted = metadataRepository?.observeSwapMetadata()?.map { swaps ->
                swaps?.firstOrNull { it.depositAddress == depositAddress }
            } ?: flowOf(null)
            swapFlow.combine(accepted.onStart { emit(null) }) { status, saved ->
                SwapData(data = status.withAcceptedStatus(saved), handle = reloadHandle)
            }.collect { send(it) }

            awaitClose {
                // do nothing
            }
        }.distinctUntilChanged()

    private fun SwapQuoteStatusData.withAcceptedStatus(saved: TransactionSwapMetadata?): SwapQuoteStatusData {
        val previous = savedMetadata
        if (previous == null || saved == null) return this
        val owner = previous.ownerAccount?.sdkAccount?.accountUuid
        val sameOwner = owner != null && saved.ownerAccount?.sdkAccount?.accountUuid == owner
        val sameWallet = previous.walletWriteToken == saved.walletWriteToken
        return if (sameOwner && sameWallet && previous.matchesStoredSwap(saved)) withSavedObservation(saved) else this
    }
}

data class SwapData(
    val data: SwapQuoteStatusData,
    val handle: ReloadHandle
) {
    val status: SwapQuoteStatus? = data.status
    val isLoading: Boolean = data.isLoading
    val error: Exception? = data.error
    val savedMetadata = data.savedMetadata
}
