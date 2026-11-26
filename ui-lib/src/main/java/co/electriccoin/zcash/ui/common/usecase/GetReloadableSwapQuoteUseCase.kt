package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataReadUnavailableException
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class GetReloadableSwapQuoteUseCase(
    private val getSwapStatus: GetSwapStatusUseCase,
    private val metadataRepository: MetadataRepository? = null,
    private val accountDataSource: AccountDataSource? = null,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(depositAddress: String, accountKeyId: String? = null): Flow<SwapData> =
        if (accountKeyId == null) {
            observeForAccount(depositAddress, null)
        } else {
            requireNotNull(accountDataSource).selectedAccount
                .distinctUntilChangedBy { it?.sdkAccount?.accountUuid }
                .flatMapLatest { account ->
                    if (account != null && account.sdkAccount.accountUuid.toStorageKeyId() == accountKeyId) {
                        observeForAccount(depositAddress, account)
                    } else {
                        flowOf(SwapData(
                            SwapQuoteStatusData(isLoading = false, error = ActivityDetailUnavailableException()),
                            UnavailableReload,
                        ))
                    }
                }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeForAccount(depositAddress: String, account: WalletAccount?) =
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
                    .flatMapLatest {
                        if (account == null) getSwapStatus.observe(depositAddress)
                        else getSwapStatus.observeForAccount(depositAddress, account)
                    }

            val accepted = metadataRepository?.observeSwapMetadata()?.map { swaps ->
                swaps?.firstOrNull { it.depositAddress == depositAddress }
            } ?: flowOf(null)
            swapFlow.combine(accepted.onStart { emit(null) }) { status, saved ->
                val owner = account?.sdkAccount?.accountUuid
                val lookupUncertain = status.isLoading || status.error is MetadataReadUnavailableException
                val visible = saved?.takeIf {
                    lookupUncertain && owner != null && it.ownerAccount?.sdkAccount?.accountUuid == owner
                }
                SwapData(data = status.withAcceptedStatus(saved), handle = reloadHandle, presentation = visible)
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

internal data object UnavailableReload : ReloadHandle {
    override fun requestReload() = Unit
}

data class SwapData(
    val data: SwapQuoteStatusData,
    val handle: ReloadHandle,
    private val presentation: TransactionSwapMetadata? = null,
) {
    val status: SwapQuoteStatus? = data.status
    val isLoading: Boolean = data.isLoading
    val error: Exception? = data.error
    val savedMetadata = data.savedMetadata
    // Exact metadata stays the source of export and request authority.
    internal val displayedMetadata = savedMetadata ?: presentation
}
