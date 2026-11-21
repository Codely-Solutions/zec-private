package co.electriccoin.zcash.ui.common.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** A reveal remains valid only for the app access grant in which it was approved. */
internal class SensitiveReveal(private val session: AppAccessSession) {
    private val revealedGrant = MutableStateFlow<AppAccessSession.Grant?>(null)

    val changes = combine(session.grant, revealedGrant) { active, revealed -> revealed.takeIf { it === active } }

    val isRevealed: Boolean
        get() = session.permits(revealedGrant.value)

    fun permits(grant: AppAccessSession.Grant?): Boolean =
        session.permits(grant) && revealedGrant.value === grant

    fun reveal(grant: AppAccessSession.Grant) {
        if (session.permits(grant)) revealedGrant.value = grant
    }

    fun hide() {
        revealedGrant.value = null
    }
}
