package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.ShieldFundsInfoProvider
import co.electriccoin.zcash.ui.screen.home.shieldfunds.ShieldFundsInfo

class ShieldFundsFromMessageUseCase(
    private val shieldFunds: ShieldFundsUseCase,
    private val navigationRouter: NavigationRouter,
    private val shieldFundsInfoProvider: ShieldFundsInfoProvider,
) {
    fun action(account: WalletAccount): suspend () -> Unit {
        val confirm = shieldFunds.confirm(account, closeCurrentScreen = false)
        return {
            if (shieldFundsInfoProvider.get()) {
                navigationRouter.forward(ShieldFundsInfo)
            } else {
                confirm()
            }
        }
    }
}
