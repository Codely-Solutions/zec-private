package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationAdvanceResult
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.MigrationNextAction
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.ScheduleNextMigrationWindowUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import co.electriccoin.zcash.work.MigrationDriveOnce
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import co.electriccoin.zcash.ui.design.R as DesignR

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationSendingVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidNoteShowsRetryableFailureSheetWithMappedMessage() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.InvalidNote)
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)
            val collectJob = launch { vm.state.collect {} }

            advanceUntilIdle()

            val sheet =
                vm.state.value.content
                    ?.failureSheet
            collectJob.cancel()
            assertEquals(
                DesignR.string.migrationFailureMessage_invalidNote,
                (sheet?.message as? StringResource.ByResource)?.resource,
            )
            assertTrue(sheet != null)
        }

    @Test
    fun persistentNothingDueRetriesThenShowsNotReadySheet() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns TransferAttemptOutcome.NothingDue
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)
            val collectJob = launch { vm.state.collect {} }

            advanceUntilIdle()

            coVerify(exactly = 3) { sdk.executeNextPendingTransfer(any(), any()) }
            assertEquals(
                DesignR.string.migrationSending_notReady,
                (
                    vm.state.value.content
                        ?.failureSheet
                        ?.message as? StringResource.ByResource
                )?.resource,
            )
            collectJob.cancel()
        }

    @Test
    fun persistentAwaitingProofRetriesThenShowsNotReadySheet() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.AwaitingProof(1L)
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)
            val collectJob = launch { vm.state.collect {} }

            advanceUntilIdle()

            coVerify(exactly = 3) { sdk.executeNextPendingTransfer(any(), any()) }
            assertEquals(
                DesignR.string.migrationSending_notReady,
                (
                    vm.state.value.content
                        ?.failureSheet
                        ?.message as? StringResource.ByResource
                )?.resource,
            )
            collectJob.cancel()
        }

    @Test
    fun successReplacesSendingWithTheResult() =
        runTest {
            val txId = TransactionId.new("txid123".toByteArray())
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.Success(txId))
            val router = FakeNavigationRouter()
            vm(sdk = sdk, router = router)

            advanceUntilIdle()

            assertEquals<List<Any>>(listOf(MigrationSuccessArgs(txId.txIdString())), router.replacedRoutes)
        }

    @Test
    fun followUpFailuresKeepTheAcceptedTransactionAndDoNotRetrySubmission() = runTest {
        val txId = TransactionId.new("accepted-transaction".toByteArray())
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.Success(txId))
        }
        val marker = mockk<PendingMigrationTorFailureStorageProvider> {
            coEvery { store("fixture-owner", false) } throws IllegalStateException("synthetic storage failure")
        }
        val schedule = mockk<ScheduleNextMigrationWindowUseCase> {
            coEvery { this@mockk("fixture-owner") } throws IllegalStateException("synthetic scheduling failure")
        }
        val snapshot = mockk<GetMigrationSnapshotUseCase> {
            coEvery { this@mockk("fixture-owner") } throws IllegalStateException("synthetic SDK read failure")
        }
        val router = FakeNavigationRouter()
        val vm = vm(
            sdk = sdk,
            router = router,
            pendingMigrationTorFailureStorageProvider = marker,
            scheduleNextMigrationWindow = schedule,
            getMigrationSnapshot = snapshot,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals<List<Any>>(listOf(MigrationSuccessArgs(txId.txIdString())), router.replacedRoutes)
        assertEquals(null, vm.state.value.error)
        coVerify(exactly = 1) { schedule("fixture-owner") }
        coVerify(exactly = 1) { snapshot("fixture-owner") }
        coVerify(exactly = 1) { sdk.executeNextPendingTransfer(any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun torDialogDismissalLeavesSendingClosable() = runTest {
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false, isTorFailure = true))
        }
        val router = FakeNavigationRouter()
        val vm = vm(sdk = sdk, router = router, useTor = true)
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals<List<Any>>(listOf(MigrationTorFailureArgs("fixture-owner")), router.forwardedRoutes)
        assertTrue(vm.state.value.content?.canGoBack == true)
        vm.onBack()
        assertEquals(1, router.backCount)
        coVerify(exactly = 1) { sdk.executeNextPendingTransfer(any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun sendIsTriggeredAutomaticallyOnConstructionWithoutAnExternalCall() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.Success(TransactionId.new("txid456".toByteArray())))
            val router = FakeNavigationRouter()

            // No call to vm.send() anywhere in this test — construction alone must trigger it.
            vm(sdk = sdk, router = router)
            advanceUntilIdle()

            coVerify(exactly = 1) { sdk.executeNextPendingTransfer(any(), any()) }
        }

    @Test
    fun backNeverInterruptsAnActiveSend() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns TransferAttemptOutcome.NothingDue
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)

            vm.onBack()

            assertEquals(0, router.backCount)
        }

    @Test
    fun stateOnBackIsNoopWhileSendingIsInProgress() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            // A delay that never resolves within this test: keeps sendOnce() suspended mid-flight, so
            // the LCE stays loading and no failure sheet is ever shown, mirroring "actively sending".
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } coAnswers {
                delay(Long.MAX_VALUE / 2)
                TransferAttemptOutcome.Executed(TransferResult.Success(TransactionId.new("txid".toByteArray())))
            }
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)
            val collectJob = launch { vm.state.collect {} }

            // Don't advanceUntilIdle() — that fast-forwards virtual time and would resolve the huge
            // delay above too, completing the send. runCurrent() only runs already-ready continuations
            // (registering the in-flight job and emitting the first combine() value) without advancing
            // the virtual clock.
            runCurrent()

            val content = vm.state.value.content
            assertTrue(content != null)
            assertTrue(content.failureSheet == null)
            assertTrue(!content.canGoBack)
            content.onBack()

            assertEquals(0, router.backCount)
            collectJob.cancel()
        }

    @Test
    fun stateOnBackNavigatesBackOnceFailureSheetIsShowing() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.InvalidNote)
            val router = FakeNavigationRouter()
            val vm = vm(sdk = sdk, router = router)
            val collectJob = launch { vm.state.collect {} }

            advanceUntilIdle()

            assertTrue(
                vm.state.value.content
                    ?.failureSheet != null
            )
            vm.state.value.content
                ?.onBack
                ?.invoke()

            assertEquals(1, router.backCount)
            collectJob.cancel()
        }

    @Test
    fun pendingTorDecisionAtConstructionTimeIsUsedInsteadOfADuplicateDefaultSend() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.Success(TransactionId.new("txid".toByteArray())))
            val router = FakeNavigationRouter()
            val torDecisionRepository = PendingMigrationTorFailureDecisionRepositoryImpl()
            torDecisionRepository.set("fixture-owner", useTor = true)

            vm(sdk = sdk, router = router, torDecisionRepository = torDecisionRepository)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = true), useEstimatedTip = true)
            }
        }

    @Test
    fun otherAccountDecisionDoesNotOverrideTorOrConsumeTheChoice() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
            TransferAttemptOutcome.Executed(TransferResult.Success(TransactionId.new("txid".toByteArray())))
        val decisions = PendingMigrationTorFailureDecisionRepositoryImpl()
        decisions.set("other-owner", useTor = false)

        vm(sdk = sdk, router = FakeNavigationRouter(), torDecisionRepository = decisions, useTor = true)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = true), useEstimatedTip = true)
        }
        assertEquals("other-owner", decisions.decision.value?.accountKeyId)
    }

    @Test
    fun successfulSendClearsPendingTorFailureFlag() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.Success(TransactionId.new("txid789".toByteArray())))
            val router = FakeNavigationRouter()
            val pendingTorFailure = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)

            vm(sdk = sdk, router = router, pendingMigrationTorFailureStorageProvider = pendingTorFailure)
            advanceUntilIdle()

            coVerify(exactly = 1) { pendingTorFailure.store("fixture-owner", false) }
        }

    @Test
    fun notReadyAfterMaxAttemptsAlsoClearsPendingTorFailureFlag() =
        runTest {
            // Regression: NotReady (AwaitingProof/NothingDue after retries) means no send was even
            // attempted, so it can't be a (renewed) Tor failure — a stale flag left set here would
            // keep re-triggering CheckMigrationRecoveryUseCase's app-open Sending redirect for a
            // transfer that was never going to attempt a network send in the first place.
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns TransferAttemptOutcome.NothingDue
            val router = FakeNavigationRouter()
            val pendingTorFailure = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)

            vm(sdk = sdk, router = router, pendingMigrationTorFailureStorageProvider = pendingTorFailure)
            advanceUntilIdle()

            coVerify(exactly = 1) { pendingTorFailure.store("fixture-owner", false) }
        }

    @Test
    fun networkErrorDoesNotClearPendingTorFailureFlag() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false))
            val router = FakeNavigationRouter()
            val pendingTorFailure = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)

            vm(sdk = sdk, router = router, pendingMigrationTorFailureStorageProvider = pendingTorFailure)
            advanceUntilIdle()

            coVerify(exactly = 0) { pendingTorFailure.store(any<String>(), any()) }
        }

    @Test
    fun networkErrorWithIsTorFailureRoutesToTorFailureScreenEvenWhenLocalUseTorFlagIsFalse() =
        runTest {
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            // isTorFailure=true, but the vm() helper's isMigrationTorEnabledStorageProvider mock
            // always returns false for the local useTor flag — proves routing now follows the
            // result's own signal, not the interactive-attempt's local Tor setting.
            coEvery { sdk.executeNextPendingTransfer(any(), any()) } returns
                TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false, isTorFailure = true))
            val router = FakeNavigationRouter()

            vm(sdk = sdk, router = router)
            advanceUntilIdle()

            assertTrue(router.forwardedRoutes.any { it == MigrationTorFailureArgs("fixture-owner") })
        }

    @Test
    fun recoveryRetryUsesCurrentTransferAndTorChoice() = runTest {
        val (args, accounts) = recoveryOwner()
        val sdk = recoverySdk(nextTransferId = 1L)
        vm(sdk, FakeNavigationRouter(), useTor = true, args = args, accounts = accounts)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = true), useEstimatedTip = true)
        }
    }

    @Test
    fun recoveryRetryWaitsWhileTheCrossingQuietWindowIsUnavailable() = runTest {
        val (args, accounts) = recoveryOwner()
        val sdk = recoverySdk(nextTransferId = 1L)
        val pending = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)
        val vm = vm(
            sdk, FakeNavigationRouter(), args = args, accounts = accounts,
            quietWindowReady = false, pendingMigrationTorFailureStorageProvider = pending,
        )
        val observation = launch { vm.state.collect {} }
        advanceUntilIdle()

        coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any(), any()) }
        coVerify(exactly = 0) { pending.store(any<String>(), any()) }
        assertTrue(vm.state.value.content?.canGoBack == true)
        observation.cancel()
    }

    @Test
    fun ownerMustStillMatchAfterTheCrossingCheckSuspends() = runTest {
        val (args, accounts) = recoveryOwner()
        val sdk = recoverySdk(nextTransferId = 1L)
        val entered = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Boolean>()
        val drive = mockk<MigrationDriveOnce> {
            every { permitsForeground(any()) } returns true
            coEvery { withExclusiveAccess<Any?>(any(), any()) } coAnswers {
                secondArg<suspend () -> Any?>().invoke()
            }
            coEvery { mayBroadcastCrossing(sdk) } coAnswers {
                entered.complete(Unit)
                ready.await()
            }
        }

        vm(sdk, FakeNavigationRouter(), args = args, accounts = accounts, migrationDriveOnce = drive)
        entered.await()
        every { accounts.allAccounts } returns MutableStateFlow(emptyList())
        ready.complete(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any(), any()) }
    }

    @Test
    fun syncStartingDuringTheNativeTargetReadBlocksSubmission() = runTest {
        val (args, accounts) = recoveryOwner()
        val sdk = recoverySdk(nextTransferId = 1L)
        val transfers = sdk.getMigrationTransferStates()
        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        var syncing = false
        coEvery { sdk.getMigrationTransferStates() } coAnswers {
            readStarted.complete(Unit)
            finishRead.await()
            transfers
        }
        val drive = mockk<MigrationDriveOnce> {
            every { permitsForeground(any()) } returns true
            coEvery { withExclusiveAccess<Any?>(any(), any()) } coAnswers {
                secondArg<suspend () -> Any?>().invoke()
            }
            coEvery { mayBroadcastCrossing(sdk) } answers { !syncing }
        }

        vm(sdk, FakeNavigationRouter(), args = args, accounts = accounts, migrationDriveOnce = drive)
        readStarted.await()
        syncing = true
        finishRead.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any(), any()) }
    }

    @Test
    fun recoveryRetryLeavesReplacementTransferAndMarkerUntouched() = runTest {
        val (args, accounts) = recoveryOwner()
        val sdk = recoverySdk(nextTransferId = 2L)
        val pending = mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true)
        val vm = vm(
            sdk, FakeNavigationRouter(), args = args, accounts = accounts,
            pendingMigrationTorFailureStorageProvider = pending,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any(), any()) }
        coVerify(exactly = 0) { pending.store(any<String>(), any()) }
        assertTrue(vm.state.value.content?.canGoBack == true)
        collectJob.cancel()
    }

    @Test
    fun recoveryRetryDoesNotSendAfterAccountSwitch() = runTest {
        val (args, accounts) = recoveryOwner(selected = false)
        val sdk = recoverySdk(nextTransferId = 1L)
        vm(sdk, FakeNavigationRouter(), args = args, accounts = accounts)
        advanceUntilIdle()
        coVerify(exactly = 0) { sdk.executeNextPendingTransfer(any(), any()) }
    }

    private fun recoveryOwner(selected: Boolean = true): Pair<MigrationSendingArgs, AccountDataSource> {
        val account = AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val owner = mockk<WalletAccount> {
            every { sdkAccount } returns account
            every { isSelected } returns selected
        }
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(owner))
        }
        return MigrationSendingArgs(account.accountUuid.toStorageKeyId(), 1L, 100L) to accounts
    }

    private fun recoverySdk(nextTransferId: Long) = mockk<OrchardMigrationSdk> {
        coEvery { getMigrationTransferStates() } returns
            MigrationTransferStates(
                transfers = listOf(
                    MigrationTransferState(
                        id = 1L,
                        isTransfer = true,
                        isSent = false,
                        isProved = true,
                        scheduledHeight = 100L,
                        anchorBoundaryHeight = null,
                        ready = true,
                        action = MigrationNextAction.BROADCAST,
                    )
                ),
                tipHeight = 101L,
            )
        coEvery { nextStep() } returns MigrationAdvanceResult(MigrationAdvanceStep.Broadcast(nextTransferId), null)
        coEvery { executeNextPendingTransfer(any(), any()) } returns
            TransferAttemptOutcome.Executed(TransferResult.NetworkError(retryable = false, isTorFailure = false))
    }

    private fun vm(
        sdk: OrchardMigrationSdk,
        router: FakeNavigationRouter,
        getMigrationSnapshot: GetMigrationSnapshotUseCase =
            mockk {
                coEvery { this@mockk("fixture-owner") } returns null
            },
        torDecisionRepository: PendingMigrationTorFailureDecisionRepository =
            mockk {
                every { decision } returns MutableStateFlow(null)
            },
        pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider = mockk(relaxed = true),
        useTor: Boolean = false,
        quietWindowReady: Boolean = true,
        migrationDriveOnce: MigrationDriveOnce =
            mockk {
                every { permitsForeground(any()) } returns true
                coEvery { mayBroadcastCrossing(sdk) } returns quietWindowReady
                coEvery { recordCrossingSuccess(any()) } returns Unit
                coEvery { withExclusiveAccess<Any?>(any(), any()) } coAnswers {
                    secondArg<suspend () -> Any?>().invoke()
                }
            },
        args: MigrationSendingArgs = MigrationSendingArgs("fixture-owner"),
        accounts: AccountDataSource? = null,
        scheduleNextMigrationWindow: ScheduleNextMigrationWindowUseCase = mockk(relaxed = true),
    ) = MigrationSendingVM(
        args = args,
        getOrchardMigrationSdk =
            mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk(args.accountKeyId) } returns sdk
            },
        getMigrationSnapshot = getMigrationSnapshot,
        scheduleNextMigrationWindow = scheduleNextMigrationWindow,
        navigationRouter = router,
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        isMigrationTorEnabledStorageProvider =
            mockk<IsMigrationTorEnabledStorageProvider> {
                coEvery { this@mockk.get(args.accountKeyId) } returns useTor
            },
        pendingMigrationTorFailureDecisionRepository = torDecisionRepository,
        pendingMigrationTorFailureStorageProvider = pendingMigrationTorFailureStorageProvider,
        migrationDriveOnce = migrationDriveOnce,
        accounts = accounts,
    )

    private class FakeNavigationRouter : NavigationRouter {
        val forwardedRoutes = mutableListOf<Any>()
        val replacedRoutes = mutableListOf<Any>()
        var backCount = 0

        override fun forward(vararg routes: Any) {
            forwardedRoutes.addAll(routes.toList())
        }

        override fun replace(vararg routes: Any) {
            replacedRoutes.addAll(routes.toList())
        }

        override fun replaceAll(vararg routes: Any) = Unit

        override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

        override fun back() {
            backCount++
        }

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
