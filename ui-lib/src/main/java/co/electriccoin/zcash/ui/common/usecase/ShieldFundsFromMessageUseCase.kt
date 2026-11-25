package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.ShieldFundsInfoProvider
import co.electriccoin.zcash.ui.screen.home.shieldfunds.ShieldFundsInfo

class ShieldFundsFromMessageUseCase(
    private val shieldFunds: ShieldFundsUseCase,
    private val navigationRouter: NavigationRouter,
    private val shieldFundsInfoProvider: ShieldFundsInfoProvider,
) {
    fun action(account: WalletAccount, sourceEntryId: String?): suspend () -> Unit {
        val confirm = shieldFunds.confirm(account, closeCurrentScreen = false, sourceEntryId)
        return {
            if (navigationRouter.isAtEntry(sourceEntryId)) {
                if (shieldFundsInfoProvider.get()) {
                    navigationRouter.stageResultFrom(sourceEntryId) {
                        NavigationCommand.Forward(listOf(ShieldFundsInfo))
                    }
                } else {
                    confirm()
                }
            }
        }
    }
}
