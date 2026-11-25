package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow

class RescanQrUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke(sourceEntryId: String? = null) {
        val hasWallet = navigationRouter.whileAtEntry(sourceEntryId) {
            persistableWalletProvider.getPersistableWallet() != null
        } ?: return
        navigationRouter.commitFrom(sourceEntryId) {
            if (hasWallet) NavigationCommand.Replace(listOf(ScanArgs(ScanFlow.HOMEPAGE))) else NavigationCommand.Back
        }
    }
}
