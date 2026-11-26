package co.electriccoin.zcash.ui.common.repository

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MigrationHandoffAccountGuardTest {
    @Test
    fun pendingScheduleReturnsNullForDifferentAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertNull(repo.get("accountB"))
    }

    @Test
    fun pendingScheduleReturnsValueForSameAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertSame(schedule, repo.get("accountA"))
    }

    @Test
    fun restartScheduleRequiresTheExactOwnerAndOrigin() {
        val repo = RestartMigrationScheduleRepositoryImpl()
        val reviewed = ReviewedMigrationSchedule(mockk())
        val neighboring = ReviewedMigrationSchedule(mockk())
        repo.set("accountA", "origin1", reviewed)
        repo.set("accountA", "origin2", neighboring)

        assertNull(repo.find("accountB", "origin1"))
        assertNull(repo.find("accountA", "unrelated-review"))
        assertSame(reviewed, repo.find("accountA", "origin1"))
        repo.clear("accountA", "origin1")
        assertNull(repo.find("accountA", "origin1"))
        assertSame(neighboring, repo.find("accountA", "origin2"))
        repo.clearOwner("accountA")
        assertNull(repo.find("accountA", "origin2"))
    }

    @Test
    fun keystonePcztsReturnsNullForDifferentAccount() {
        val repo = PendingKeystoneMigrationPcztsRepositoryImpl()
        val pczts =
            PendingKeystoneMigrationPczts(
                requestId = byteArrayOf(1),
                splitUnsignedPczt = null,
                transferUnsignedPczts = emptyList(),
            )
        repo.set("accountA", pczts)
        assertNull(repo.get("accountB"))
        assertSame(pczts, repo.get("accountA"))
    }

    // ---- peek() tests ----

    @Test
    fun pendingSchedulePeekMatchReturnsValueWithoutClearing() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        // peek must return the value without clearing it
        assertSame(schedule, repo.peek("accountA"))
        // a second peek on the same account still returns the value (not consumed)
        assertSame(schedule, repo.peek("accountA"))
        // get() also still finds it — confirming peek never mutated pending
        assertSame(schedule, repo.get("accountA"))
    }

    @Test
    fun pendingSchedulePeekMismatchReturnsNullWithoutClearing() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        // peek for a different account must return null …
        assertNull(repo.peek("accountB"))
        // … but must NOT clear the stored value — the right account can still retrieve it
        assertSame(schedule, repo.get("accountA"))
    }

    @Test
    fun consumingAnOldTorDecisionPreservesTheReplacement() {
        val repo = PendingMigrationTorFailureDecisionRepositoryImpl()
        repo.set("accountA", false)
        val first = checkNotNull(repo.decision.value)
        assertEquals("accountA", first.accountKeyId)
        assertEquals(false, first.useTor)
        repo.set("accountB", true)
        val second = checkNotNull(repo.decision.value)
        assertEquals(false, repo.consume(first))
        assertEquals("accountB", second.accountKeyId)
        assertEquals(true, second.useTor)
        assertEquals(true, repo.consume(second))
        assertNull(repo.decision.value)
    }
}
