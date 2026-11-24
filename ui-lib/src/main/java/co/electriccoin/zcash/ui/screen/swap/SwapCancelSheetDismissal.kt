package co.electriccoin.zcash.ui.screen.swap

import kotlinx.coroutines.CompletableDeferred

internal class SwapCancelSheetDismissal {
    private var pendingHide: CompletableDeferred<Unit>? = null

    var isPending: Boolean = false
        private set

    fun onOpen() {
        pendingHide = CompletableDeferred()
    }

    suspend fun awaitHidden(startHiding: () -> Unit) {
        val hide = checkNotNull(pendingHide)
        isPending = true
        startHiding()
        hide.await()
    }

    suspend fun awaitClosed() {
        pendingHide?.await()
    }

    fun onHidden() {
        if (!isPending) return
        isPending = false
        val hidden = pendingHide
        pendingHide = null
        hidden?.complete(Unit)
    }
}
