package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationTransfer
import co.electriccoin.zcash.work.MigrationScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ScheduleNextMigrationWindowUseCaseTest {
    @Test
    fun theLastUnminedTransferKeepsAnOwnedCompletionWake() = runTest {
        val owner = "completion-fixture"
        val unmined = mockk<LiveMigrationTransfer> {
            every { isSent } returns true
            every { minedHeight } returns null
        }
        val snapshots = mockk<GetMigrationSnapshotUseCase> {
            coEvery { this@mockk(owner) } returns LiveMigrationSnapshot(listOf(unmined), emptyList(), 50)
        }
        val sdk = mockk<OrchardMigrationSdk> { coEvery { privacySyncBufferDuration() } returns 15.seconds }
        val engines = mockk<GetOrchardMigrationSdkUseCase> { coEvery { this@mockk(owner) } returns sdk }
        val scheduler = mockk<MigrationScheduler>(relaxUnitFun = true)
        ScheduleNextMigrationWindowUseCase(snapshots, scheduler, mockk(), engines)(owner)
        coVerify(exactly = 1) { scheduler.scheduleIfMissing(owner, 15.seconds, any()) }
        coVerify(exactly = 0) { scheduler.scheduleAndAwait(any(), any(), any()) }
    }
}
