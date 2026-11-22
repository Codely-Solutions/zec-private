package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import java.io.IOException

class PersistServerSelectionUseCase(
    private val application: Application,
    private val walletServerSelectionProvider: WalletServerSelectionProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val getAutomaticEndpoint: GetAutomaticEndpointUseCase,
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
        if (!walletServerSelectionProvider.storeServerSelection(selection, version)) {
            throw IOException("The wallet changed before saving the server selection")
        }
    }

    private suspend fun validateServerEndpoint(endpoint: LightWalletEndpoint) =
        synchronizerProvider
            .getSynchronizer()
            .validateServerEndpoint(application, endpoint)
}
