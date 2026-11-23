package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.SwapRepository

class CancelSwapUseCase(
    private val swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke() {
        swapRepository.clear()
        navigationRouter.backToRoot()
    }

    suspend fun afterDismissal(dismiss: suspend () -> Unit) {
        swapRepository.clear()
        dismiss()
        if (swapRepository.quote.value == null) navigationRouter.backToRoot()
    }
}
