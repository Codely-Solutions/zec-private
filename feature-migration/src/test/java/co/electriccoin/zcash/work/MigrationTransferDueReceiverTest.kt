package co.electriccoin.zcash.work

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTransferDueReceiverTest {
    @Test
    fun persistedRemovalAndResetDeferAStaleFallbackInAFreshProcess() {
        val owner = "fixture-owner"
        val overdue = System.currentTimeMillis() - 300_000L
        val preferences = mockk<SharedPreferences> {
            every { getBoolean("removal_pending_$owner", false) } returnsMany listOf(true, false, false)
            every { getLong("next_wake_at_$owner", -1L) } returns overdue
            every { getLong("last_run_at_$owner", -1L) } returns -1L
        }
        val context = mockk<Context> {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns preferences
        }
        val intent = mockk<Intent> {
            every { getStringExtra(MigrationDueAlarmScheduler.EXTRA_ACCOUNT_KEY_ID) } returns owner
        }
        val pendingReset = MutableStateFlow(true)
        val reset = mockk<ResetProgressStore> { every { isPending } returns pendingReset }
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        startKoin {
            modules(module {
                single { reset }
                single { notifier }
            })
        }
        try {
            assertFalse(MigrationAccountRetirement.blocks(owner))
            MigrationTransferDueReceiver().onReceive(context, intent) // owner removal unresolved
            MigrationTransferDueReceiver().onReceive(context, intent) // reset still pending
            verify(exactly = 0) { notifier.notifyMigrationStepDue(owner) }

            pendingReset.value = false
            MigrationTransferDueReceiver().onReceive(context, intent)
            verify(exactly = 1) { notifier.notifyMigrationStepDue(owner) }
        } finally {
            stopKoin()
        }
    }

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
