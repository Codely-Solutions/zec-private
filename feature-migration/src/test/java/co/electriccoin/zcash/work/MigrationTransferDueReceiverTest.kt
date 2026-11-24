package co.electriccoin.zcash.work

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTransferDueReceiverTest {
    @Test
    fun inheritedAlarmCannotDeclareTheRearmedWorkerLateEarly() {
        val scheduled = 1_000_000L
        val due = scheduled + MigrationScheduler.WORKER_LATE_MARGIN.inWholeMilliseconds
        assertFalse(migrationWorkerIsLate(due, null, null))
        assertFalse(migrationWorkerIsLate(due - 1, scheduled, null))
        assertFalse(migrationWorkerIsLate(due, scheduled, scheduled - 30_000L))
        assertTrue(migrationWorkerIsLate(due, scheduled, scheduled - 30_001L))
        assertTrue(migrationWorkerIsLate(due, scheduled, null))
    }
}
