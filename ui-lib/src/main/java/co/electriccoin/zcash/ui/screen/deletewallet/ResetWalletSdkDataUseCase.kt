package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.eraseWalletlessSdkData
import co.electriccoin.zcash.ui.common.provider.requireClassicWalletFilesErased
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Called under Reset Zashi's single admitted, durably pending reset. */
class ResetWalletSdkDataUseCase(
    private val context: Context,
    private val coordinator: WalletCoordinator,
    private val walletProvider: PersistableWalletProvider,
) {
    internal var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pending: Deferred<Pair<ZcashNetwork, Boolean>>? = null

    suspend operator fun invoke() {
        val erase = pending ?: startErase() ?: return
        val (network, erased) = checkNotNull(withTimeoutOrNull(30.seconds) { erase.await() }) {
            "Still waiting for SDK wallet data erasure"
        }
        pending = null
        check(erased) { "Could not erase SDK wallet data" }
        // The SDK's Boolean follows both erases. Classic SDK success can still leave a database or sidecar.
        withContext(Dispatchers.IO) { requireClassicWalletFilesErased(context, network) }
    }

    private suspend fun startErase(): Deferred<Pair<ZcashNetwork, Boolean>>? {
        val walletNetwork = walletProvider.getPersistableWallet()?.network
        if (walletNetwork == null) {
            withContext(Dispatchers.IO) { eraseWalletlessSdkData(context, ZcashNetwork.fromResources(context)) }
            return null
        }
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            walletNetwork to coordinator.deleteSdkDataFlow().first()
        }.also { pending = it }
    }
}
