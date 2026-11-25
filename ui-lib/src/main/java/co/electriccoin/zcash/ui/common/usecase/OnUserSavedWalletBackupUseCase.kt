package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider

class OnUserSavedWalletBackupUseCase(
    private val navigationRouter: NavigationRouter,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider
) {
    suspend operator fun invoke(sourceEntryId: String? = null) {
        walletBackupFlagStorageProvider.store(true)
        navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
    }
}
