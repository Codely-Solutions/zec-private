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

        hideBottomSheet()
        if (!canNavigate() || swapRepository.quote.value !== value) return
        navigationRouter.forwardFrom(sourceEntryId, canNavigate) {
            takeReviewRoute(value, consumeAtDelivery = sourceEntryId != null)
        }
    }

    private fun takeReviewRoute(value: SwapQuoteData, consumeAtDelivery: Boolean): Any? {
        if (!swapRepository.isReviewCurrent(value)) {
            if (value is SwapQuoteData.Success) swapRepository.clearIfCurrent(value)
            return null
        }
        return if (consumeAtDelivery && swapRepository.consumeReview(value) == null) {
            null
        } else {
            val mismatch = (value as? SwapQuoteData.Error)?.toMismatchArgs()
            mismatch?.also { swapRepository.clearQuote() } ?: SwapQuoteArgs
        }
    }
}
