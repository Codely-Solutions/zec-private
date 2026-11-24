package co.electriccoin.zcash.ui.screen.swap

import kotlinx.coroutines.CompletableDeferred

internal class SwapCancelSheetDismissal {
    private var pendingHide: CompletableDeferred<Unit>? = null

    val isPending: Boolean get() = pendingHide != null

    suspend fun awaitHidden(startHiding: () -> Unit) {
        val hide = CompletableDeferred<Unit>()
        pendingHide = hide
        try {
            startHiding()
            hide.await()
        } finally {
            if (pendingHide === hide) pendingHide = null
        }
    }

    fun onHidden() {
        pendingHide?.complete(Unit)
    }
}
