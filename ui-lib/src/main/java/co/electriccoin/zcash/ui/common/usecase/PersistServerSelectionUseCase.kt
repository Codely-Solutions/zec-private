package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

class PersistServerSelectionUseCase(
    private val application: Application,
    private val walletServerSelectionProvider: WalletServerSelectionProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val getAutomaticEndpoint: GetAutomaticEndpointUseCase,
    private val walletTransport: WalletTransportAccess,
) {
    suspend fun persistAutomatic() {
        val version = walletServerSelectionProvider.writeVersion
        val endpoint = getAutomaticEndpoint()
        persistSelection(WalletServerSelection(endpoint, isAutomatic = true), version)
    }

    @Throws(PersistEndpointException::class)
    suspend fun persistManual(endpoint: LightWalletEndpoint) {
        val version = walletServerSelectionProvider.writeVersion
        when (val result = validateServerEndpoint(endpoint)) {
            ServerValidation.Valid -> persistSelection(WalletServerSelection(endpoint, isAutomatic = false), version)
            is ServerValidation.InValid -> throw PersistEndpointException(result.reason.message)
            ServerValidation.Running -> throw PersistEndpointException(null)
        }
    }

    private suspend fun persistSelection(selection: WalletServerSelection, version: Long) {
        val saved = walletTransport.ifIdleSwitch {
            val previous = walletServerSelectionProvider.getServerSelection() ?: return@ifIdleSwitch false
            val replaced = if (previous.endpoint != selection.endpoint) {
                val current = synchronizerProvider.synchronizer.value
                if (walletTransport.awaitingEndpointReplacement(current)) return@ifIdleSwitch null
                current
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            walletServerSelectionProvider.storeServerSelection(selection, version) {
                if (replaced != null) walletTransport.retire(replaced)
            }
        } ?: throw ServerSelectionWalletBusyException()
        if (!saved) {
            throw IOException("The wallet changed before saving the server selection")
        }
    }

    private suspend fun validateServerEndpoint(endpoint: LightWalletEndpoint) =
        walletTransport.whileOperating {
            val synchronizer =
                walletTransport.current(synchronizerProvider) ?: throw ServerSelectionWalletBusyException()
            synchronizer.validateServerEndpoint(application, endpoint)
        }
}

internal class ServerSelectionWalletBusyException : IOException("A wallet operation is still running")
