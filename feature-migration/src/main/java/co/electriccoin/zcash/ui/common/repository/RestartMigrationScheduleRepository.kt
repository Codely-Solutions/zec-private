package co.electriccoin.zcash.ui.common.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Retains the SDK's unconfirmed schedule for the originating recovery entry and account. */
interface RestartMigrationScheduleRepository {
    fun set(accountKeyId: String, recoveryId: String, reviewed: ReviewedMigrationSchedule)

    fun find(accountKeyId: String, recoveryId: String): ReviewedMigrationSchedule?

    fun replace(
        accountKeyId: String,
        recoveryId: String,
        previous: ReviewedMigrationSchedule,
        reviewed: ReviewedMigrationSchedule,
    ): Boolean

    fun clear(accountKeyId: String, recoveryId: String)

    fun clearOwner(accountKeyId: String)
}

class RestartMigrationScheduleRepositoryImpl : RestartMigrationScheduleRepository {
    private val pending = MutableStateFlow<Map<Pair<String, String>, ReviewedMigrationSchedule>>(emptyMap())

    override fun set(accountKeyId: String, recoveryId: String, reviewed: ReviewedMigrationSchedule) {
        pending.update { it + ((accountKeyId to recoveryId) to reviewed) }
    }

    override fun find(accountKeyId: String, recoveryId: String): ReviewedMigrationSchedule? =
        pending.value[accountKeyId to recoveryId]

    override fun replace(
        accountKeyId: String,
        recoveryId: String,
        previous: ReviewedMigrationSchedule,
        reviewed: ReviewedMigrationSchedule,
    ): Boolean {
        val key = accountKeyId to recoveryId
        val before = pending.getAndUpdate { state ->
            if (state[key] === previous) state + (key to reviewed) else state
        }
        return before[key] === previous
    }

    override fun clear(accountKeyId: String, recoveryId: String) {
        pending.update { it - (accountKeyId to recoveryId) }
    }

    override fun clearOwner(accountKeyId: String) {
        pending.update { entries -> entries.filterKeys { it.first != accountKeyId } }
    }
}
