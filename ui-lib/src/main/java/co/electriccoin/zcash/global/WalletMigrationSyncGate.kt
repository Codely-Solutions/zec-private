package co.electriccoin.zcash.global

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlin.time.Duration.Companion.seconds

/** Keeps sync paused while the SDK's migration state is unreadable. */
class WalletMigrationSyncGate {
    private val failure = MutableStateFlow<SynchronizerError?>(null)
    val error = failure.asStateFlow()
    private val blocked = MutableStateFlow(true)
    val isBlocked = blocked.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(
        sdkWallet: Flow<PersistableWallet?>,
        resetProgressStore: ResetProgressStore,
        observe: (PersistableWallet) -> Flow<Boolean>,
    ): Flow<Boolean> = sdkWallet.flatMapLatest { wallet ->
        blocked.value = true
        failure.value = null
        if (wallet == null) flowOf(false) else resetProgressStore.observeMigrationSyncBlock {
            flow { emitAll(observe(wallet)) }
                .onEach { failure.value = null }
                .retryWhen { cause, attempt ->
                    if (cause is Exception && cause !is CancellationException && attempt < MAX_RETRIES) {
                        noteFailure(cause)
                        emit(true)
                        delay((attempt + 1).seconds)
                        true
                    } else {
                        false
                    }
                }.catch { cause ->
                    if (cause !is Exception || cause is CancellationException) throw cause
                    noteFailure(cause)
                    emit(true)
                }
                .onStart { emit(true) }
                .onEach { blocked.value = it }
        }
    }.distinctUntilChanged()

    private fun noteFailure(cause: Exception) {
        if (failure.value == null) {
            Twig.warn { "Wallet migration sync state unavailable (${cause.javaClass.simpleName})" }
        }
        failure.value = SynchronizerError.MigrationStateUnavailable
    }

    companion object {
        private const val MAX_RETRIES = 2L
    }
}
