package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientArgs

class NavigateToSelectRecipientUseCase(
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke() {
        navigationRouter.forward(SelectABRecipientArgs)
    }
}
