package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow

class RescanQrUseCase(
    private val walletRepository: WalletRepository,
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke(sourceEntryId: String, walletRoutesAllowed: Boolean) {
        navigationRouter.commitFrom(sourceEntryId) {
            if (walletRoutesAllowed && walletRepository.secretState.value == SecretState.READY) {
                NavigationCommand.Replace(listOf(ScanArgs(ScanFlow.HOMEPAGE)))
            } else {
                NavigationCommand.Back
            }
        }
    }
}
