package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.screen.swap.mismatch.toMismatchArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs

class NavigateToSwapQuoteIfAvailableUseCase(
    private val swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke(canNavigate: () -> Boolean = { true }, hideBottomSheet: suspend () -> Unit) {
        val value = (if (canNavigate()) swapRepository.consumeReview() else null) ?: return
        val mismatchArgs = (value as? SwapQuoteData.Error)?.toMismatchArgs()

        hideBottomSheet()
        if (!canNavigate() || swapRepository.quote.value !== value) return
        if (mismatchArgs == null) {
            navigationRouter.forward(SwapQuoteArgs)
        } else {
            swapRepository.clearQuote()
            navigationRouter.forward(mismatchArgs)
        }
    }
}
