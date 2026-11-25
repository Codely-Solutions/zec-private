package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.PreparationStep
import cash.z.ecc.android.sdk.TransferProposal
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.work.MigrationScheduler
import com.google.common.util.concurrent.SettableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class FinalizeMigrationScheduleUseCaseTest {
    private fun schedule() =
        MigrationSchedule(
            transfers =
                listOf(
                    TransferProposal(
                        id = 11L,
                        amountZatoshi = 100_000L,
                        anchorHeight = 100L,
                        nextExecutableAfterHeight = 200L,
                        expiryHeight = 300L,
                    )
                ),
            estimatedDurationHours = 1,
            proposalHandle = 0L,
        )

    private fun useCase(scheduler: MigrationScheduler = mockk(relaxed = true)) =
        FinalizeMigrationScheduleUseCase(
            migrationScheduler = scheduler,
            getOrchardMigrationSdk = mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk("fixture-owner") } returns mockk<OrchardMigrationSdk>(relaxed = true)
            },
            synchronizerProvider = mockk(relaxed = true),
            migrationLiveDriver = mockk(relaxed = true),
        )

    @Test
    fun invokeArmsTheMigrationWorkerChain() =
        runTest {
            // The single worker chain is armed at commit; its first run asks the engine
            // (nextStep/syncWakeupSchedule) and computes every subsequent wake itself. Nothing is
            // persisted app-side anymore — the engine's committed state is the only record.
            val scheduler = mockk<MigrationScheduler>(relaxed = true)

            useCase(scheduler)(schedule(), MigrationMode.AUTOMATIC, "fixture-owner")

            coVerify(exactly = 1) { scheduler.scheduleAndAwait("fixture-owner", any()) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailedEnqueueCannotAcknowledgeACommittedSchedule() = runTest {
        val account = "fixture-owner"
        val context = mockk<Context> {
            every { getSystemService(Context.ALARM_SERVICE) } returns null
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns mockk {
                every { edit() } returns mockk(relaxed = true)
            }
        }
        val acknowledgement = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation> { every { result } returns acknowledgement }
        val workId = MigrationScheduler.workId(account)
        val manager = mockk<WorkManager> {
            every { enqueueUniqueWork(workId, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) } returns operation
        }
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            supervisorScope {
                val result = async {
                    useCase(MigrationScheduler(context))(schedule(), MigrationMode.AUTOMATIC, account)
                }
                runCurrent()
                verify(exactly = 1) {
                    manager.enqueueUniqueWork(workId, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
                }
                assertFalse(result.isCompleted)

                val failure = IllegalStateException("synthetic enqueue failure")
                acknowledgement.setException(failure)
                assertEquals(failure.message, assertFailsWith<IllegalStateException> { result.await() }.message)
            }
        } finally {
            acknowledgement.cancel(false)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun `delay targets earliest step including preparations`() {
        val sched =
            MigrationSchedule(
                transfers =
                    listOf(
                        TransferProposal(
                            id = 4,
                            amountZatoshi = 1,
                            anchorHeight = 4219036,
                            nextExecutableAfterHeight = 4219108,
                            expiryHeight = 9_999_999
                        )
                    ),
                preparations =
                    listOf(
                        PreparationStep(
                            id = 1,
                            layer = 0,
                            index = 1,
                            broadcastHeight = 4219043,
                            dependsOn = emptyList()
                        )
                    ),
                estimatedDurationHours = 1,
                proposalHandle = 1,
            )
        val d =
            useCase().delayUntilFirstStep(
                sched,
                secondsPerBlock = 28,
                nu7ActivationHeight = null,
                tipHeight = 4219036
            )
        assertEquals((7 * 28).seconds, d) // 4219043-4219036 = 7 blocks
    }

    // ── NU7 characterization pins (MOB-2287): pre-NU7 behaviour at the 75 s/block rate. ──

    @Test
    fun `characterization pin of pre-NU7 75s - delay is block delta times 75 for a transfer-only schedule`() {
        // NU7: revisit - spans activation once helper is height-aware
        // schedule(): anchor 100, nextExecutableAfter 200 -> 100 blocks * 75 s.
        val d =
            useCase().delayUntilFirstStep(
                schedule(),
                secondsPerBlock = 75L,
                nu7ActivationHeight = null,
                tipHeight = 100L
            )
        assertEquals((100 * 75).seconds, d)
    }

    @Test
    fun `characterization pin of pre-NU7 75s - preparation before the first transfer wins`() {
        // NU7: revisit - spans activation once helper is height-aware
        val sched =
            schedule().copy(
                preparations =
                    listOf(
                        PreparationStep(id = 1, layer = 0, index = 0, broadcastHeight = 110L, dependsOn = emptyList())
                    )
            )
        val d =
            useCase().delayUntilFirstStep(
                sched,
                secondsPerBlock = 75L,
                nu7ActivationHeight = null,
                tipHeight = 100L
            )
        assertEquals((10 * 75).seconds, d)
    }

    @Test
    fun `characterization pin of pre-NU7 75s - earliest step already due yields zero delay`() {
        // NU7: revisit - spans activation once helper is height-aware
        val d =
            useCase().delayUntilFirstStep(
                schedule(),
                secondsPerBlock = 75L,
                nu7ActivationHeight = null,
                tipHeight = 250L
            )
        assertEquals(0.seconds, d)
    }

    @Test
    fun `characterization pin of pre-NU7 75s - empty schedule yields zero delay`() {
        val empty = MigrationSchedule(transfers = emptyList(), estimatedDurationHours = 0, proposalHandle = 0L)
        assertEquals(
            0.seconds,
            useCase().delayUntilFirstStep(
                empty,
                secondsPerBlock = 75L,
                nu7ActivationHeight = null,
                tipHeight = 0L
            )
        )
    }

    @Test
    fun `NU7 - delay splits at the activation height`() {
        // schedule(): anchor 100, first step at 200. Activation at 140: 39 blocks at 75 s, 61 at 25 s.
        val d =
            useCase().delayUntilFirstStep(
                schedule(),
                secondsPerBlock = 75L,
                tipHeight = 100L,
                nu7ActivationHeight = 140L,
            )
        assertEquals((39 * 75 + 61 * 25).seconds, d)
    }

    @Test
    fun `NU7 - a rate read past activation is not priced as a pre-NU7 rate for an anchor before it`() {
        // schedule(): anchor 100, first step at 200. Activation at 150, rate 25 s measured at the tip 160 (post-NU7).
        // Blocks 101..149 are pre-NU7 at 75 s, blocks 150..200 post-NU7 at 25 s.
        val d =
            useCase().delayUntilFirstStep(
                schedule(),
                secondsPerBlock = 25L,
                tipHeight = 100L,
                nu7ActivationHeight = 150L,
                rateHeight = 160L,
            )
        assertEquals((49 * 75 + 51 * 25).seconds, d)
    }

    @Test
    fun `NU7 - delay after activation uses the post-NU7 rate`() {
        val d =
            useCase().delayUntilFirstStep(
                schedule(),
                secondsPerBlock = 25L,
                tipHeight = 100L,
                nu7ActivationHeight = 50L,
            )
        assertEquals((100 * 25).seconds, d)
    }
}
