package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Process-local schedules retained through every signing round for the specified account. */
interface PendingMigrationScheduleRepository {
    fun set(accountKeyId: String, schedule: MigrationSchedule)

    fun get(accountKeyId: String): MigrationSchedule? = peek(accountKeyId)

    fun peek(accountKeyId: String): MigrationSchedule?

    fun clear()

    fun clear(accountKeyId: String, expected: MigrationSchedule)
}

class PendingMigrationScheduleRepositoryImpl : PendingMigrationScheduleRepository {
    private val pending = MutableStateFlow<Map<String, MigrationSchedule>>(emptyMap())

    override fun set(accountKeyId: String, schedule: MigrationSchedule) {
        pending.update { it + (accountKeyId to schedule) }
    }

    override fun peek(accountKeyId: String): MigrationSchedule? = pending.value[accountKeyId]

    override fun clear(accountKeyId: String, expected: MigrationSchedule) {
        pending.update { if (it[accountKeyId] === expected) it - accountKeyId else it }
    }

    override fun clear() {
        pending.value = emptyMap()
    }
}
