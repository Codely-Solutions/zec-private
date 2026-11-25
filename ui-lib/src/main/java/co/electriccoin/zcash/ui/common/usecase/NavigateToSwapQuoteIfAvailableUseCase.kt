package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.forwardFrom
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.screen.swap.mismatch.toMismatchArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs

class NavigateToSwapQuoteIfAvailableUseCase(
    private val swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke(
        canNavigate: () -> Boolean = { true },
        sourceEntryId: String? = null,
        hideBottomSheet: suspend () -> Unit,
    ) {
        val atSource = sourceEntryId == null || navigationRouter.activeEntry.value?.id == sourceEntryId
        val value = when {
            !atSource || !canNavigate() -> null
            sourceEntryId == null -> swapRepository.consumeReview()
            else -> swapRepository.quote.value
        }
        if (value == null || value == SwapQuoteData.Loading) return
        val mismatchArgs = (value as? SwapQuoteData.Error)?.toMismatchArgs()

        hideBottomSheet()
        if (!canNavigate() || swapRepository.quote.value !== value) return
        navigationRouter.forwardFrom(sourceEntryId, canNavigate) {
            when {
                !swapRepository.isReviewCurrent(value) -> {
                    if (value is SwapQuoteData.Success) swapRepository.clearIfCurrent(value)
                    null
                }
                sourceEntryId != null && swapRepository.consumeReview(value) == null -> null
                mismatchArgs == null -> SwapQuoteArgs
                else -> {
                    swapRepository.clearQuote()
                    mismatchArgs
                }
            }
        }
    }
}
