package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable

class OptInExchangeRateAndTorUseCase(
    private val navigationRouter: NavigationRouter,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
) {
    suspend operator fun invoke(optIn: Boolean, sourceEntryId: String? = null) {
        val grant = appAccessSession.grant.value
        val generation = walletWriteAccess.token()
        val canSave = { appAccessSession.permits(grant) && walletWriteAccess.permits(generation) }
        val saved = walletWriteAccess.write(generation) {
            if (!canSave() || !navigationRouter.isForegroundAtEntry(sourceEntryId)) return@write false
            runCatchingRecoverable { isTorEnabledStorageProvider.store(optIn) }.getOrElse {
                error("Could not save the Tor protection setting")
            }
            true
        }
        if (saved == true) navigationRouter.stageResultFrom(sourceEntryId, canSave) { NavigationCommand.Back }
    }
}
