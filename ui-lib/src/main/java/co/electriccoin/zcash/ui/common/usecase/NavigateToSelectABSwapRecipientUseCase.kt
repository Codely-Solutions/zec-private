package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.screen.swap.ab.SelectABSwapRecipientArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

class NavigateToSelectABSwapRecipientUseCase(
    private val navigationRouter: NavigationRouter
) {
    private val pipeline = MutableSharedFlow<SelectSwapRecipientPipelineResult>()

    suspend operator fun invoke(network: String? = null): EnhancedABContact? {
        val args = SelectABSwapRecipientArgs(network = network)
        navigationRouter.forward(args)
        return when (val result = pipeline.first { it.args.requestId == args.requestId }) {
            is SelectSwapRecipientPipelineResult.Cancelled -> null
            is SelectSwapRecipientPipelineResult.Scanned -> result.contact
        }
    }
    suspend fun onSelectionCancelled(args: SelectABSwapRecipientArgs) {
        pipeline.emit(SelectSwapRecipientPipelineResult.Cancelled(args))
        navigationRouter.back()
    }

    suspend fun onSelected(contact: EnhancedABContact, args: SelectABSwapRecipientArgs) {
        if (args.network != null && !args.network.equals(contact.blockchain?.chainTicker, ignoreCase = true)) return
        pipeline.emit(SelectSwapRecipientPipelineResult.Scanned(contact = contact, args = args))
        navigationRouter.back()
    }
}

private sealed interface SelectSwapRecipientPipelineResult {
    val args: SelectABSwapRecipientArgs

    data class Cancelled(
        override val args: SelectABSwapRecipientArgs
    ) : SelectSwapRecipientPipelineResult

    data class Scanned(
        val contact: EnhancedABContact,
        override val args: SelectABSwapRecipientArgs
    ) : SelectSwapRecipientPipelineResult
}
