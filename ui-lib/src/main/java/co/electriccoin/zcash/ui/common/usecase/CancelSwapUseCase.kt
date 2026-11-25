package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.common.repository.SwapRepository

class CancelSwapUseCase(
    private val swapRepository: SwapRepository,
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke() {
        swapRepository.clear()
        navigationRouter.backToRoot()
    }

    internal suspend fun cancelFrom(sourceEntryId: String): Boolean =
        navigationRouter.commitFrom(sourceEntryId) {
            swapRepository.clear()
            NavigationCommand.BackToRoot
        }

    suspend fun afterDismissal(
        canReturnToRoot: () -> Boolean,
        sourceEntryId: String? = null,
        dismiss: suspend () -> Unit,
    ) {
        if (!canReturnToRoot() || !navigationRouter.isAtEntry(sourceEntryId)) return
        swapRepository.clear()
        dismiss()
        if (canReturnToRoot() && swapRepository.quote.value == null) {
            navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.BackToRoot }
        }
    }
}
