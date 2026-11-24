package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.NoteSplitProposal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class ReviewedMigrationSchedule(
    val schedule: MigrationSchedule,
    val noteSplit: NoteSplitProposal? = null,
)

/** Process-local reviewed schedules retained through every signing round for the specified account. */
interface PendingMigrationScheduleRepository {
    fun set(accountKeyId: String, reviewed: ReviewedMigrationSchedule)

    fun set(accountKeyId: String, schedule: MigrationSchedule) = set(accountKeyId, ReviewedMigrationSchedule(schedule))

    fun get(accountKeyId: String): MigrationSchedule? = peek(accountKeyId)

    fun peek(accountKeyId: String): MigrationSchedule? = review(accountKeyId)?.schedule

    fun review(accountKeyId: String): ReviewedMigrationSchedule?

    fun clear()

    fun clear(accountKeyId: String, expected: MigrationSchedule)
}

class PendingMigrationScheduleRepositoryImpl : PendingMigrationScheduleRepository {
    private val pending = MutableStateFlow<Map<String, ReviewedMigrationSchedule>>(emptyMap())

    override fun set(accountKeyId: String, reviewed: ReviewedMigrationSchedule) {
        pending.update { it + (accountKeyId to reviewed) }
    }

    override fun review(accountKeyId: String): ReviewedMigrationSchedule? = pending.value[accountKeyId]

    override fun clear(accountKeyId: String, expected: MigrationSchedule) {
        pending.update { if (it[accountKeyId]?.schedule === expected) it - accountKeyId else it }
    }

    override fun clear() {
        pending.value = emptyMap()
    }
}
