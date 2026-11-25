package co.electriccoin.zcash.global

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.WalletSdkRead
import co.electriccoin.zcash.ui.common.provider.recoverableReads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/** Delivers a read-verified transport choice before admitting its wallet to the SDK. */
class WalletSdkTransportGate {
    private val deliveredGeneration = MutableStateFlow<Long?>(null)
    private val withdrawnGeneration = MutableStateFlow<Long?>(null)
    private val failed = MutableStateFlow(false)
    val unavailable = failed.asStateFlow()

    internal fun isTransportVerified(generation: StateFlow<Long>): Flow<Boolean> =
        combine(generation, deliveredGeneration) { _, _ -> generation.value == deliveredGeneration.value }

    internal fun walletDecisions(wallets: Flow<WalletSdkRead?>, generation: StateFlow<Long>): Flow<WalletSdkRead> =
        combine(wallets, generation, deliveredGeneration) { read, _, _ ->
            val selected = generation.value
            val wallet = read?.takeIf { it.generation == selected && deliveredGeneration.value == selected }?.wallet
            WalletSdkRead(selected, wallet)
        }

    /** Only the coordinator's own wallet collector acknowledges delivery. */
    internal fun walletInput(decisions: Flow<WalletSdkRead>): Flow<PersistableWallet?> =
        decisions.transform { decision ->
            emit(decision.wallet)
            if (decision.wallet == null) withdrawnGeneration.value = decision.generation
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun transportInput(preference: Flow<Boolean?>, generation: StateFlow<Long>): Flow<Boolean?> =
        generation.flatMapLatest { selected ->
            preference.recoverableReads().map { Read(selected, it) }
        }.buffer(0).transform { read ->
            if (read.generation != generation.value) return@transform
            if (read.preference.isSuccess) {
                if (deliveredGeneration.value != read.generation) {
                    // The SDK combines these inputs independently. Withdraw its old wallet first.
                    awaitWalletWithdrawal(read.generation, generation)
                    if (read.generation != generation.value) return@transform
                }
                emit(read.preference.getOrThrow())
                if (read.generation == generation.value) {
                    deliveredGeneration.value = read.generation
                    failed.value = false
                }
            } else {
                deliveredGeneration.value = null
                failed.value = true
            }
        }

    private suspend fun awaitWalletWithdrawal(expected: Long, generation: StateFlow<Long>) {
        combine(generation, withdrawnGeneration) { current, withdrawn ->
            current != expected || withdrawn == expected
        }.first { it }
    }

    private data class Read(val generation: Long, val preference: Result<Boolean?>)
}
