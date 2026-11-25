package co.electriccoin.zcash.ui.screen.migration.review

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.NoteSplitProposal
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.MutableLce
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ReviewedMigrationSchedule
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationReviewVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun immediateConfirmOnZashiAccountHandsSweepProposalToSharedSendPipeline() =
        runTest {
            val proposal =
                mockk<Proposal> {
                    coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
                }
            val router = FakeNavigationRouter()
            val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true)
            val reviewed = MigrationSweepTransactionProposal(proposal)
            val account = spendable<ZodlAccount>()
            every { zashiProposalRepository.setMigrationSweepProposal(proposal) } returns reviewed
            val submitProposal = mockk<SubmitProposalUseCase>(relaxed = true)
            val vm =
                vm(
                    router = router,
                    getSelectedWalletAccount = selectedAccount(account),
                    getOrchardMigrationSdk = mockk {
                        val sdk = mockk<OrchardMigrationSdk> {
                            coEvery { proposeImmediateMigration() } returns proposal
                        }
                        coEvery { this@mockk(any<String>()) } returns sdk
                    },
                    zashiProposalRepository = zashiProposalRepository,
                    submitProposal = submitProposal,
                )

            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertNotNull(vm.state.value.content).onConfirm()
            advanceUntilIdle()
            collector.cancel()

            // IMMEDIATE confirm on a Zashi account no longer signs/broadcasts in this VM — it adopts the
            // send-max sweep proposal into the shared send pipeline (SubmitProposalUseCase owns biometrics,
            // broadcast, and the Transaction Progress navigation).
            coVerifyOrder {
                zashiProposalRepository.setMigrationSweepProposal(proposal)
                submitProposal(reviewed, reviewedOwner = account.sdkAccount.accountUuid)
            }
        }

    @Test
    fun immediateConfirmOnKeystoneAccountAdoptsIntoKeystoneRepositoryAndNavigatesToSign() =
        runTest {
            val proposal =
                mockk<Proposal> {
                    coEvery { totalFeeRequired() } returns Zatoshi(1_000L)
                }
            val router = FakeNavigationRouter()
            val adopted = MigrationSweepTransactionProposal(proposal)
            val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true) {
                every { setMigrationSweepProposal(proposal) } returns adopted
                every { transactionProposal } returns MutableStateFlow(adopted)
            }
            val vm =
                vm(
                    router = router,
                    getOrchardMigrationSdk = mockk {
                        val sdk = mockk<OrchardMigrationSdk> {
                            coEvery { proposeImmediateMigration() } returns proposal
                        }
                        coEvery { this@mockk(any<String>()) } returns sdk
                    },
                    keystoneProposalRepository = keystoneProposalRepository,
                    getSelectedWalletAccount =
                        mockk {
                            coEvery { this@mockk() } returns spendable<KeystoneAccount>()
                            every { observe() } returns
                                flowOf(spendable<KeystoneAccount>())
                        },
                )

            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertNotNull(vm.state.value.content).onConfirm()
            advanceUntilIdle()
            collector.cancel()

            coVerifyOrder {
                keystoneProposalRepository.setMigrationSweepProposal(proposal)
                keystoneProposalRepository.createPCZTFromProposal(any())
            }
            assertEquals(
                listOf<Any>(SignKeystoneTransactionArgs),
                router.forwardedRoutes,
            )
        }

    // Covers item 5 of the plan-update/expired-transfer fixes: restartCurrentMigrationStep()'s own
    // doc requires its returned schedule to go through this normal confirmation flow rather than
    // being discarded in favor of an independently re-proposed one.
    @Test
    fun automaticModeReusesPendingRestartScheduleInsteadOfProposingAFreshOne() =
        runTest {
            val router = FakeNavigationRouter()
            val restartSchedule =
                MigrationSchedule(
                    transfers =
                        listOf(
                            TransferProposal(
                                id = 100L,
                                amountZatoshi = 900_000L,
                                anchorHeight = 0L,
                                nextExecutableAfterHeight = 100L,
                                expiryHeight = 200L,
                            )
                        ),
                    estimatedDurationHours = 1,
                    proposalHandle = 0L,
                )
            val restartRepo =
                mockk<RestartMigrationScheduleRepository>(relaxed = true) {
                    every { consume(any()) } returns restartSchedule
                }
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
            val vm =
                vm(
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                    mode = MigrationMode.AUTOMATIC,
                    restartMigrationScheduleRepository = restartRepo,
                )
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                1,
                vm.state.value.content
                    ?.transfers
                    ?.size
            )
            coVerify(exactly = 0) { sdk.proposeMigrationTransfers(any()) }
            coVerify(exactly = 1) { restartRepo.consume(any()) }
            collectJob.cancel()
        }

    @Test
    fun automaticModeProposesFreshScheduleWhenNoRestartIsPending() =
        runTest {
            val router = FakeNavigationRouter()
            val freshSchedule =
                MigrationSchedule(
                    transfers =
                        listOf(
                            TransferProposal(
                                id = 200L,
                                amountZatoshi = 100_000L,
                                anchorHeight = 0L,
                                nextExecutableAfterHeight = 100L,
                                expiryHeight = 200L,
                            ),
                            TransferProposal(
                                id = 201L,
                                amountZatoshi = 200_000L,
                                anchorHeight = 0L,
                                nextExecutableAfterHeight = 200L,
                                expiryHeight = 300L,
                            ),
                        ),
                    estimatedDurationHours = 2,
                    proposalHandle = 0L,
                )
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { proposeMigrationTransfers(any()) } returns freshSchedule
                }
            val vm =
                vm(
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                    mode = MigrationMode.AUTOMATIC,
                )
            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                2,
                vm.state.value.content
                    ?.transfers
                    ?.size
            )
            coVerify(exactly = 1) { sdk.proposeMigrationTransfers(any()) }
            collectJob.cancel()
        }

    // Regression (root cause of the live "No pending migration proposal for this account — call
    // propose/prepare first" crash): on the Zashi in-process signing split path,
    // proposeMigrationTransfersFromSplit() MUST run before submitNoteSplit(). submitNoteSplit()
    // signs the split via the SDK's commit_or_reuse, which clears the in-memory migration-plan
    // cache the handle identifies — so deriving the from-split schedule AFTER submitting throws,
    // because the plan the handle points at is already gone. Mirrors MigrationKeystoneSignVM, which
    // likewise derives the from-split schedule before its first commit.
    @Test
    fun automaticConfirmWithNoteSplitDerivesScheduleFromSplitBeforeSubmittingIt() =
        runTest {
            val usk = mockk<UnifiedSpendingKey>()
            val router = FakeNavigationRouter("scheduled-review")
            val splitProposal =
                NoteSplitProposal(
                    outputNotes = listOf(100_000L),
                    fee = 1_000L,
                    proposalHandle = 42L,
                )
            val scheduleFromSplit =
                MigrationSchedule(
                    transfers =
                        listOf(
                            TransferProposal(
                                id = 300L,
                                amountZatoshi = 100_000L,
                                anchorHeight = 0L,
                                nextExecutableAfterHeight = 100L,
                                expiryHeight = 200L,
                            )
                        ),
                    estimatedDurationHours = 1,
                    proposalHandle = 42L,
                )
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
                    coEvery { isNoteSplitNeeded() } returns true
                    coEvery { prepareNoteSplit() } returns splitProposal
                    coEvery { proposeMigrationTransfersFromSplit(splitProposal) } returns scheduleFromSplit
                    coEvery { submitNoteSplit(splitProposal, usk) } returns
                        TransferResult.Success(TransactionId.new("splittx".toByteArray()))
                }
            val finalizeMigrationSchedule = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
            val vm =
                vm(
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                    zashiSpendingKeyDataSource = mockk { coEvery { getZashiSpendingKey() } returns usk },
                    finalizeMigrationSchedule = finalizeMigrationSchedule,
                    mode = MigrationMode.AUTOMATIC,
                    sourceEntryId = "scheduled-review",
                )

            val review = vm.currentReview()
            assertEquals(stringRes(Zatoshi(100_000L)), review.totalAmount)
            coVerify(exactly = 0) { sdk.submitNoteSplit(any(), any()) }
            review.onConfirm()
            advanceUntilIdle()

            // Schedule is derived from the split before the split is submitted (the engine's own
            // committed state is the recovery signal — no app-side write-ahead exists anymore).
            coVerifyOrder {
                sdk.proposeMigrationTransfersFromSplit(splitProposal)
                sdk.submitNoteSplit(splitProposal, usk)
            }
            val handoff = router.scopedCommands.single()
            assertEquals("scheduled-review", handoff.sourceEntryId)
            val route = assertIs<NavigationCommand.Forward>(handoff.buildCommand()).routes.single()
            assertIs<MigrationScheduledArgs>(route)
            assertTrue(router.forwardedRoutes.isEmpty())
        }

    @Test
    fun rejectsNegativeHandlesBeforeReviewAndRequiresApprovalForAReplacement() = runTest {
        val unusable = schedule(-1L, 400_000L)
        val first = schedule(1L, 100_000L)
        val replacement = schedule(2L, 200_000L)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
            coEvery { proposeMigrationTransfers() } returnsMany listOf(unusable, first, replacement)
            coEvery { signAndStoreMigrationSchedule(first, any()) } throws IllegalStateException("StalePlan")
        }
        val vm = vm(
            router = FakeNavigationRouter(),
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            mode = MigrationMode.AUTOMATIC,
        )
        val review = vm.currentReview()
        assertEquals(stringRes(Zatoshi(100_000L)), review.totalAmount)
        coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(any(), any()) }

        review.onConfirm()
        advanceUntilIdle()
        coVerify(exactly = 1) { sdk.signAndStoreMigrationSchedule(first, any()) }
        coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(replacement, any()) }

        val refreshed = vm.currentReview()
        assertEquals(stringRes(Zatoshi(200_000L)), refreshed.totalAmount)
        review.onConfirm()
        advanceUntilIdle()
        coVerify(exactly = 1) { sdk.signAndStoreMigrationSchedule(any(), any()) }
        refreshed.onConfirm()
        advanceUntilIdle()
        coVerify(exactly = 1) { sdk.signAndStoreMigrationSchedule(replacement, any()) }
    }

    @Test
    fun uncertainSplitContinuesTheExactCommittedPlanWithoutAnotherBroadcast() = runTest {
        val split = NoteSplitProposal(listOf(100_000L), 1_000L, 42L)
        val expected = schedule(42L, 100_000L)
        val nativeTransfer = mockk<cash.z.ecc.android.sdk.MigrationTransferState> {
            every { isTransfer } returns true
            every { id } returns 42L
            every { amountZatoshi } returns 100_000L
        }
        val state = MigrationState.InProgress(mockk(relaxed = true))
        var committed = false
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } coAnswers {
                if (committed) state else MigrationState.NotStarted
            }
            coEvery { isNoteSplitNeeded() } returns true
            coEvery { prepareNoteSplit() } returns split
            coEvery { proposeMigrationTransfersFromSplit(split) } returns expected
            coEvery { submitNoteSplit(split, any()) } coAnswers {
                committed = true
                TransferResult.NetworkError(retryable = true)
            }
            coEvery { getMigrationTransferStates() } returns
                cash.z.ecc.android.sdk.MigrationTransferStates(listOf(nativeTransfer), 100L)
        }
        val finalize = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
        val vm = vm(
            router = FakeNavigationRouter(),
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            mode = MigrationMode.AUTOMATIC,
            finalizeMigrationSchedule = finalize,
        )

        vm.currentReview().onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { sdk.submitNoteSplit(split, any()) }
        coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(any(), any()) }
        coVerify(exactly = 1) { finalize(expected, MigrationMode.AUTOMATIC, any(), false) }
    }

    @Test
    fun committedScheduleRetriesOnlyItsFinalizationAfterASchedulerFailure() = runTest {
        val reviewed = schedule(42L, 100_000L)
        val native = mockk<cash.z.ecc.android.sdk.MigrationTransferState> {
            every { isTransfer } returns true
            every { id } returns 42L
            every { amountZatoshi } returns 100_000L
        }
        var committed = false
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } coAnswers {
                if (committed) MigrationState.InProgress(mockk(relaxed = true)) else MigrationState.NotStarted
            }
            coEvery { proposeMigrationTransfers() } returns reviewed
            coEvery { getMigrationTransferStates() } returns
                cash.z.ecc.android.sdk.MigrationTransferStates(listOf(native), 100L)
            coEvery { signAndStoreMigrationSchedule(reviewed, any()) } coAnswers { committed = true }
        }
        var attempts = 0
        val finalize = mockk<FinalizeMigrationScheduleUseCase> {
            coEvery { this@mockk(reviewed, MigrationMode.AUTOMATIC, any(), false) } coAnswers {
                if (++attempts == 1) error("synthetic scheduler unavailable")
            }
        }
        val router = FakeNavigationRouter("automatic-review")
        val vm = vm(
            router = router,
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            mode = MigrationMode.AUTOMATIC,
            finalizeMigrationSchedule = finalize,
            sourceEntryId = "automatic-review",
        )

        val confirmation = vm.currentReview()
        confirmation.onConfirm()
        advanceUntilIdle()
        assertTrue(router.scopedCommands.isEmpty())
        confirmation.onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { sdk.signAndStoreMigrationSchedule(reviewed, any()) }
        coVerify(exactly = 2) { finalize(reviewed, MigrationMode.AUTOMATIC, any(), false) }
        val handoff = router.scopedCommands.single()
        assertEquals("automatic-review", handoff.sourceEntryId)
        assertIs<NavigationCommand.Forward>(handoff.buildCommand())
    }

    @Test
    fun automaticConfirmationDoesNotMutateTheWalletAfterItsReviewIsCovered() = runTest {
        val review = "automatic-review"
        val router = FakeNavigationRouter(review)
        val resolvedKey = CompletableDeferred<UnifiedSpendingKey>()
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
            coEvery { proposeMigrationTransfers() } returns schedule(7L, 90_000L)
        }
        val spendingKey = mockk<ZashiSpendingKeyDataSource> {
            coEvery { getZashiSpendingKey() } coAnswers { resolvedKey.await() }
        }
        val vm = vm(
            router = router,
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            zashiSpendingKeyDataSource = spendingKey,
            mode = MigrationMode.AUTOMATIC,
            sourceEntryId = review,
        )
        vm.currentReview().onConfirm()
        runCurrent()
        coVerify(exactly = 1) { spendingKey.getZashiSpendingKey() }
        router.activeEntry.value = ActiveNavigationEntry("scanner", null)
        resolvedKey.complete(mockk())
        advanceUntilIdle()

        coVerify(exactly = 0) { sdk.submitNoteSplit(any(), any()) }
        coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(any(), any()) }
        assertTrue(router.scopedCommands.isEmpty())
    }

    @Test
    fun torProtectedSoftwarePlanStopsBeforeAnUnsupportedSplit() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { isNoteSplitNeeded() } returns true
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
        }
        val tor = mockk<IsMigrationTorEnabledStorageProvider> {
            coEvery { this@mockk.get(any<String>()) } returns true
        }
        val router = FakeNavigationRouter()
        val vm = vm(
            router = router,
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            migrationTorEnabled = tor,
            mode = MigrationMode.AUTOMATIC,
        )

        vm.currentReview().onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { sdk.isNoteSplitNeeded() }
        coVerify(exactly = 1) { sdk.prepareNoteSplit() }
        coVerify(exactly = 0) { sdk.submitNoteSplit(any(), any()) }
        coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(any(), any()) }
        assertEquals(emptyList(), router.forwardedRoutes)
    }

    @Test
    fun newTorDraftCannotInheritAFormerDirectPreference() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { isNoteSplitNeeded() } returns true
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
        }
        val tor = mockk<IsMigrationTorEnabledStorageProvider> {
            coEvery { this@mockk.get(any<String>()) } returns false
        }
        val vm = vm(
            router = FakeNavigationRouter(),
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            migrationTorEnabled = tor,
            mode = MigrationMode.AUTOMATIC,
            draftTor = true,
        )

        vm.currentReview().onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { sdk.isNoteSplitNeeded() }
        coVerify(exactly = 0) { tor.get(any<String>()) }
        coVerify(exactly = 0) { tor.store(any<String>(), any()) }
        coVerify(exactly = 1) { sdk.prepareNoteSplit() }
    }

    @Test
    fun approvedTorReplacesTheDirectPreferenceForAReusedUnsignedKeystonePlan() = runTest {
        val account = spendable<KeystoneAccount>()
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val tor = mockk<IsMigrationTorEnabledStorageProvider>(relaxed = true) {
            coEvery { this@mockk.get(any<String>()) } returns false
        }
        val pending = PendingMigrationScheduleRepositoryImpl().apply {
            set(account.sdkAccount.accountUuid.toStorageKeyId(), ReviewedMigrationSchedule(schedule(1L, 200_000L)))
        }
        val vm = vm(
            pendingMigrationScheduleRepository = pending,
            router = FakeNavigationRouter(),
            getSelectedWalletAccount = mockk {
                coEvery { this@mockk() } returns account
                every { observe() } returns flowOf(account)
            },
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            migrationTorEnabled = tor,
            mode = MigrationMode.AUTOMATIC,
            draftTor = true,
        )

        vm.currentReview().onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { tor.store(any<String>(), true) }
        coVerify(exactly = 0) { tor.store(any<String>(), false) }
    }

    @Test
    fun immediateConfirmOnAViewOnlyAccountIsRefusedWithoutSigningOrAdopting() =
        runTest {
            val proposal = mockk<Proposal> { coEvery { totalFeeRequired() } returns Zatoshi(1_000L) }
            val router = FakeNavigationRouter()
            val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true)
            val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
            val submitProposal = mockk<SubmitProposalUseCase>(relaxed = true)
            val vm =
                vm(
                    router = router,
                    getSelectedWalletAccount = viewOnlySelected(),
                    getOrchardMigrationSdk = mockk {
                        val sdk = mockk<OrchardMigrationSdk> {
                            coEvery { proposeImmediateMigration() } returns proposal
                        }
                        coEvery { this@mockk(any<String>()) } returns sdk
                    },
                    zashiProposalRepository = zashiProposalRepository,
                    keystoneProposalRepository = keystoneProposalRepository,
                    submitProposal = submitProposal,
                )

            vm.currentReview().onConfirm()
            advanceUntilIdle()

            assertRefusedAsViewOnly(vm)
            coVerify(exactly = 0) { zashiProposalRepository.setMigrationSweepProposal(any()) }
            coVerify(exactly = 0) { keystoneProposalRepository.setMigrationSweepProposal(any()) }
            coVerify(exactly = 0) { keystoneProposalRepository.createPCZTFromProposal(any()) }
            coVerify(exactly = 0) { submitProposal(any(), any(), any(), any()) }
            assertEquals(emptyList(), router.forwardedRoutes)
        }

    /**
     * The screen proposes its schedule when it opens, so "without proposing" covers the confirm
     * itself: no note split is prepared or submitted and nothing is signed.
     */
    @Test
    fun automaticConfirmOnAViewOnlyAccountIsRefusedWithoutProposing() =
        runTest {
            val router = FakeNavigationRouter()
            val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
                coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
                coEvery { proposeMigrationTransfers() } returns schedule(1L, 200_000L)
            }
            val finalizeMigrationSchedule = mockk<FinalizeMigrationScheduleUseCase>(relaxed = true)
            val vm =
                vm(
                    router = router,
                    getSelectedWalletAccount = viewOnlySelected(),
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                    finalizeMigrationSchedule = finalizeMigrationSchedule,
                    mode = MigrationMode.AUTOMATIC,
                )

            vm.currentReview().onConfirm()
            advanceUntilIdle()

            assertRefusedAsViewOnly(vm)
            coVerify(exactly = 1) { sdk.isNoteSplitNeeded() }
            coVerify(exactly = 0) { sdk.prepareNoteSplit() }
            coVerify(exactly = 0) { sdk.proposeMigrationTransfersFromSplit(any()) }
            coVerify(exactly = 0) { sdk.submitNoteSplit(any(), any()) }
            coVerify(exactly = 0) { sdk.signAndStoreMigrationSchedule(any(), any()) }
            coVerify(exactly = 0) { finalizeMigrationSchedule(any(), any(), any(), any()) }
            assertEquals(emptyList(), router.forwardedRoutes)
        }

    private fun viewOnlySelected(): GetSelectedWalletAccountUseCase {
        val viewOnly = mockk<ViewOnlyAccount>(relaxed = true) { every { isSpendable } returns false }
        return selectedAccount(viewOnly)
    }

    /** The confirm action ended in the view-only refusal, read from the VM's private confirm LCE. */
    private fun assertRefusedAsViewOnly(vm: MigrationReviewVM) {
        val confirmLce =
            MigrationReviewVM::class.java
                .getDeclaredField("confirmLce")
                .apply { isAccessible = true }
                .get(vm) as MutableLce<*>
        val error = assertNotNull(confirmLce.state.value.error)
        assertEquals(
            AccountOperationUnsupportedException.Kind.VIEW_ONLY,
            assertIs<AccountOperationUnsupportedException>(error.cause).kind
        )
    }

    private suspend fun MigrationReviewVM.currentReview() =
        requireNotNull(state.first { state -> !state.isLoading && state.content != null }.content)

    private fun schedule(handle: Long, amount: Long) = MigrationSchedule(
        transfers = listOf(TransferProposal(
            id = handle, amountZatoshi = amount, anchorHeight = 10L,
            nextExecutableAfterHeight = 20L, expiryHeight = 30L,
        )),
        estimatedDurationHours = 1,
        proposalHandle = handle,
    )

    private fun vm(
        router: NavigationRouter,
        getSelectedWalletAccount: GetSelectedWalletAccountUseCase = selectedAccount(spendable<ZodlAccount>()),
        zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource =
            mockk {
                coEvery { getZashiSpendingKey() } returns mockk<UnifiedSpendingKey>()
            },
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase = mockk<GetOrchardMigrationSdkUseCase>(relaxed = true),
        keystoneProposalRepository: KeystoneProposalRepository = mockk(relaxed = true),
        zashiProposalRepository: ZashiProposalRepository = mockk(relaxed = true),
        submitProposal: SubmitProposalUseCase = mockk(relaxed = true),
        finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase = mockk(relaxed = true),
        migrationTorEnabled: IsMigrationTorEnabledStorageProvider = mockk(relaxed = true),
        mode: MigrationMode = MigrationMode.IMMEDIATE,
        pendingMigrationScheduleRepository: PendingMigrationScheduleRepository =
            PendingMigrationScheduleRepositoryImpl(),
        draftTor: Boolean? = null,
        restartMigrationScheduleRepository: RestartMigrationScheduleRepository =
            mockk<RestartMigrationScheduleRepository>(relaxed = true) { every { consume(any()) } returns null },
        synchronizerProvider: SynchronizerProvider = mockk(relaxed = true),
        sourceEntryId: String? = null,
    ) = MigrationReviewVM(
        args = MigrationReviewArgs(mode = mode, useTor = draftTor),
        getOrchardMigrationSdk = getOrchardMigrationSdk,
        pendingMigrationScheduleRepository = pendingMigrationScheduleRepository,
        pendingKeystone = mockk { every { peek(any()) } returns null },
        restartMigrationScheduleRepository = restartMigrationScheduleRepository,
        finalizeMigrationSchedule = finalizeMigrationSchedule,
        migrationTorEnabled = migrationTorEnabled,
        navigationRouter = router,
        exchangeRateRepository =
            mockk<ExchangeRateRepository>(relaxed = true) {
                every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
            },
        getSelectedWalletAccount = getSelectedWalletAccount,
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        zashiSpendingKeyDataSource = zashiSpendingKeyDataSource,
        biometricRepository = mockk<BiometricRepository>(relaxed = true),
        zashiProposalRepository = zashiProposalRepository,
        keystoneProposalRepository = keystoneProposalRepository,
        submitProposal = submitProposal,
        synchronizerProvider = synchronizerProvider,
        sourceEntryId = sourceEntryId,
    )

    private class FakeNavigationRouter(entryId: String? = null) : NavigationRouter {
        override val activeEntry = MutableStateFlow(entryId?.let { ActiveNavigationEntry(it, null) })
        val forwardedRoutes = mutableListOf<Any>()
        val scopedCommands = mutableListOf<NavigationCommand.ForEntry>()

        override fun forward(vararg routes: Any) {
            forwardedRoutes.addAll(routes)
        }

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) {
            (block(null) as? NavigationCommand.ForEntry)?.let(scopedCommands::add)
        }

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }

    private inline fun <reified T : WalletAccount> spendable(): T =
        mockk<T>(relaxed = true) { every { isSpendable } returns true }
}

private fun selectedAccount(account: WalletAccount): GetSelectedWalletAccountUseCase = mockk {
    coEvery { this@mockk() } returns account
    every { observe() } returns flowOf(account)
}
