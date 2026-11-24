package co.electriccoin.zcash.ui.common.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MigrationTorFailureDecision(val accountKeyId: String, val useTor: Boolean)

/** Delivers the explicit Tor choice to the migration send for the same account. */
interface PendingMigrationTorFailureDecisionRepository {
    val decision: StateFlow<MigrationTorFailureDecision?>

    fun set(accountKeyId: String, useTor: Boolean)

    fun consume(decision: MigrationTorFailureDecision): Boolean
}

class PendingMigrationTorFailureDecisionRepositoryImpl : PendingMigrationTorFailureDecisionRepository {
    private val pending = MutableStateFlow<MigrationTorFailureDecision?>(null)
    override val decision: StateFlow<MigrationTorFailureDecision?> = pending

    override fun set(accountKeyId: String, useTor: Boolean) {
        pending.value = MigrationTorFailureDecision(accountKeyId, useTor)
    }

    override fun consume(decision: MigrationTorFailureDecision): Boolean = pending.compareAndSet(decision, null)
}
