package co.electriccoin.zcash.ui.screen.migration.torfailure

import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationTorFailureVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Try again must NOT retry Tor in place (that would infinite-loop on a persistent outage) —
    // it dismisses the sheet and sends the user to the standard overdue/missed-transfer
    // resolution screen, without recording any decision on the in-memory retry repository.
    @Test
    fun tryAgainNavigatesToProgressScreenWithoutRecordingADecision() =
        runTest {
            val router = mockk<NavigationRouter>(relaxed = true)
            val decisionRepository = mockk<PendingMigrationTorFailureDecisionRepository>(relaxed = true)
            val vm = vm(router = router, decisionRepository = decisionRepository)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.state.value
                ?.onTryAgain
                ?.invoke()
            advanceUntilIdle()

            verify(exactly = 1) { router.replaceAll(MigrationProgressArgs("fixture-owner", offerRetry = true)) }
            verify(exactly = 0) { decisionRepository.set(any(), any(), any()) }
            collectJob.cancel()
        }

    @Test
    fun continueWithoutTorRecordsTheExactInMemoryAttempt() =
        runTest {
            val router = mockk<NavigationRouter>(relaxed = true)
            val decisionRepository = mockk<PendingMigrationTorFailureDecisionRepository>(relaxed = true)
            val vm = vm(router = router, decisionRepository = decisionRepository)
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.state.value
                ?.onContinueWithoutTor
                ?.invoke()
            advanceUntilIdle()

            verify(exactly = 1) {
                decisionRepository.set("fixture-owner", useTor = false, authorizationId = "current-attempt")
            }
            verify(exactly = 1) { router.back() }
            collectJob.cancel()
        }

    @Test
    fun aCoveredFailureSheetCannotAuthorizeDirect() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true) {
            every { activeEntry } returns MutableStateFlow(ActiveNavigationEntry("cover", "Other"))
        }
        val decisionRepository = mockk<PendingMigrationTorFailureDecisionRepository>(relaxed = true)
        val vm = vm(router, decisionRepository, sourceEntryId = "failure-sheet")
        val collected = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.state.value?.onContinueWithoutTor?.invoke()
        advanceUntilIdle()

        verify(exactly = 0) { decisionRepository.set(any(), any(), any()) }
        verify(exactly = 0) { router.back() }
        collected.cancel()
    }

    private fun vm(
        router: NavigationRouter,
        decisionRepository: PendingMigrationTorFailureDecisionRepository,
        sourceEntryId: String? = null,
    ) = MigrationTorFailureVM(
        navigationRouter = router,
        args = MigrationTorFailureArgs("fixture-owner", "current-attempt"),
        pendingMigrationTorFailureDecisionRepository = decisionRepository,
        sourceEntryId = sourceEntryId,
    )
}
