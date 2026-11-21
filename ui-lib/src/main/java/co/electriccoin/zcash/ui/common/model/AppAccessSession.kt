package co.electriccoin.zcash.ui.common.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The process-local authorization for wallet UI and actions. A relock revokes every outstanding grant. */
class AppAccessSession {
    private val activeGrant = MutableStateFlow<Grant?>(null)

    internal val grant = activeGrant.asStateFlow()

    internal fun permits(grant: Grant?): Boolean = grant != null && grant === activeGrant.value

    internal fun unlock() {
        if (activeGrant.value == null) activeGrant.value = Grant()
    }

    internal fun lock() {
        activeGrant.value = null
    }

    internal class Grant
}
