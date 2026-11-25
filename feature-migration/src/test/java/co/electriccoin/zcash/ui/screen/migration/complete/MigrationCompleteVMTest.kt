package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.MutableLce
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetIronwoodBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import kotlin.test.assertFalse
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationTransfer
import kotlin.time.Instant
import kotlin.test.assertNull
import co.electriccoin.zcash.ui.design.R as DesignR
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.migrationMessageFor
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationCompleteVMTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun keystoneAccountWithMigratableResidualBalanceClearsPlanInsteadOfMarkingSeen() =
        runTest {
            // A residual at or above MIGRATION_RESIDUAL_MIN_ZATOSHI is genuinely migratable -- the
            // engine can propose another round for it, so onDone() must clear the plan (not mark
            // seen) and let the home banner re-offer that round.
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = MIGRATION_RESIDUAL_MIN_ZATOSHI + 500_000L,
                    router = router,
                )

            vm.state.value // force lazy init to run (StateFlow combine below reads loadLce)
            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 0) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithResidualExactlyAtResidualMinClearsPlanInsteadOfMarkingSeen() =
        runTest {
            // Boundary check: exactly-at-residual-min is still migratable (comparison is `>=`).
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = MIGRATION_RESIDUAL_MIN_ZATOSHI,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 0) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithZeroResidualBalanceMarksSeenInsteadOfClearing() =
        runTest {
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = 0L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun nonKeystoneAccountWithResidualBalanceMarksSeenInsteadOfClearing() =
        runTest {
            // Scope: hot-wallet multi-round continuation is deferred, so a non-Keystone account always
            // takes the terminal path regardless of residual balance.
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithDustResidualBelowThresholdMarksSeenInsteadOfClearing() =
        runTest {
            // Pins the fix for Task 3's bug: a bare `> 0L` check used to treat *any* nonzero residual
            // as "more rounds needed", incorrectly clearing the plan even for a genuinely-dust residual
            // well below the real completion threshold.
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI - 1L,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun keystoneAccountWithResidualExactlyAtThresholdMarksSeenInsteadOfClearing() =
        runTest {
            // Boundary check: exactly-at-threshold is still "done" (comparison is strictly `>`).
            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = MIGRATION_DUST_THRESHOLD_ZATOSHI,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(any(), true) }
            assertEquals(1, router.backToRootCount)
        }

    @Test
    fun onDoneAgreesWithHomeBannerForResidualInTheDustToResidualMinGap() =
        runTest {
            // Regression guard for the threshold discrepancy this test used to reproduce: onDone()'s
            // `moreRoundsNeeded` check used to compare the residual only against
            // MIGRATION_DUST_THRESHOLD_ZATOSHI (0.0001 ZEC) instead of MIGRATION_RESIDUAL_MIN_ZATOSHI
            // (0.01 ZEC) -- the actual engine minimum below which proposeMigrationTransfers() returns
            // NothingToMigrate. For a balance in the gap between the two (e.g. the live-observed
            // 0.005 ZEC / 500_000L zatoshi used by GetHomeMessageUseCaseMigrationTest's own
            // residue-band tests), that made onDone() and the home banner disagree for identical
            // input: onDone() left "seen complete" unset (expecting another round), while the home
            // banner correctly routed to the residue/lock flow for a balance the engine can never
            // actually turn into another round. Both decisions must now agree: this balance marks
            // the completion seen AND the home banner reports it complete.
            val residualInGapZatoshi = 500_000L
            check(residualInGapZatoshi > MIGRATION_DUST_THRESHOLD_ZATOSHI)
            check(residualInGapZatoshi < MIGRATION_RESIDUAL_MIN_ZATOSHI)

            val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
            val router = FakeNavigationRouter()
            val vm =
                vm(
                    seen = seen,
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = residualInGapZatoshi,
                    router = router,
                )

            advanceUntilIdle()
            invokeOnDone(vm)
            advanceUntilIdle()

            coVerify(exactly = 1) { seen.store(any(), true) }

            val homeMessage =
                migrationMessageFor(
                    sdkState = MigrationState.Complete,
                    snapshot = null,
                    hasSeenComplete = false,
                    orchardBalanceZatoshi = residualInGapZatoshi,
                )
            assertEquals(true, homeMessage?.isComplete)
        }

    @Test
    fun migrateAnywayOpensASeparateReviewForBothSpendableAccountKinds() = runTest {
        val accounts = listOf(
            mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true },
            mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
        )
        for (account in accounts) {
            val owner = account.sdkAccount.accountUuid.toStorageKeyId()
            val router = FakeNavigationRouter()
            val sdk = mockk<OrchardMigrationSdk>()
            val vm = vm(
                account = account,
                orchardBalanceZatoshi = 500_000L,
                router = router,
                getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                args = MigrationCompleteArgs(isResidueOnly = true),
            )
            advanceUntilIdle()
            invokeOnMigrateAnyway(vm)
            advanceUntilIdle()
            assertEquals(listOf<Any>(MigrationReviewArgs(MigrationMode.IMMEDIATE, owner)), router.forwardedRoutes)
            coVerify(exactly = 0) { sdk.proposeImmediateMigration() }
        }
    }

    @Test
    fun aCoveredCompletionCannotOpenAReviewAfterWaitingForCompletionEvidence() = runTest {
        val entry = "migration-complete"
        val router = FakeNavigationRouter(entry)
        val requested = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        var pending = false
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { getMigrationStateUnreconciled() } coAnswers {
                if (pending) {
                    requested.complete(Unit)
                    held.await()
                }
                MigrationState.Complete
            }
        }
        val vm = vm(
            account = mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true },
            orchardBalanceZatoshi = 500_000L,
            router = router,
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
            sourceEntryId = entry,
        )
        advanceUntilIdle()
        pending = true
        invokeOnMigrateAnyway(vm)
        requested.await()
        router.activeEntry.value = ActiveNavigationEntry("another-payment", null)
        held.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), router.forwardedRoutes)
    }

    @Test
    fun changedResidueRefreshesTheDisplayedAmountBeforeAnyNativeLockOrSweep() = runTest {
        val entry = "migration-complete"
        val router = FakeNavigationRouter(entry)
        val orchardBalance = MutableStateFlow(500_000L)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val heldLookup = CompletableDeferred<OrchardMigrationSdk>()
        val resolveSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk(any<String>()) } coAnswers { heldLookup.await() }
        }
        val vm = vm(
            account = mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true },
            orchardBalanceZatoshi = orchardBalance.value,
            orchardBalance = orchardBalance,
            router = router,
            args = MigrationCompleteArgs(isResidueOnly = true),
            sourceEntryId = entry,
            getOrchardMigrationSdk = resolveSdk,
            lockOrchardBalance = LockOrchardBalanceUseCase(resolveSdk),
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val old = assertNotNull(vm.state.value.content)
        old.onLockBalance()
        advanceUntilIdle()

        orchardBalance.value = 600_000L
        heldLookup.complete(sdk)
        advanceUntilIdle()
        assertEquals(stringRes(Zatoshi(600_000L)), vm.state.value.content?.remainingDust)
        old.onMigrateAnyway()
        advanceUntilIdle()
        coVerify(exactly = 0) { sdk.lockRemainingOrchardBalance() }
        coVerify(exactly = 0) { sdk.proposeImmediateMigration() }

        assertNotNull(vm.state.value.content).onLockBalance()
        advanceUntilIdle()
        coVerify(exactly = 1) { sdk.lockRemainingOrchardBalance() }
    }

    @Test
    fun aCompletedLockRequestLeavesLaterSpendableResidueActionable() = runTest {
        val orchardBalance = MutableStateFlow(500_000L)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val resolveSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk(any<String>()) } returns sdk
        }
        val account = mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true }
        val router = FakeNavigationRouter()
        val complete = vm(
            account = account,
            orchardBalanceZatoshi = orchardBalance.value,
            orchardBalance = orchardBalance,
            router = router,
            args = MigrationCompleteArgs(isResidueOnly = true),
            getOrchardMigrationSdk = resolveSdk,
            lockOrchardBalance = LockOrchardBalanceUseCase(resolveSdk),
        )
        backgroundScope.launch { complete.state.collect {} }
        advanceUntilIdle()
        assertNotNull(complete.state.value.content).onLockBalance()
        advanceUntilIdle()
        assertEquals(
            stringRes(DesignR.string.migrationComplete_lockRequestCompleted),
            complete.state.value.content?.lockNotice,
        )

        orchardBalance.value = 0
        advanceUntilIdle()
        assertNull(complete.state.value.content?.remainingDust)
        orchardBalance.value = 600_001
        advanceUntilIdle()
        val newBalance = assertNotNull(complete.state.value.content)
        assertEquals(stringRes(Zatoshi(600_001L)), newBalance.remainingDust)
        newBalance.onLockBalance()
        advanceUntilIdle()
        coVerify(exactly = 2) { sdk.lockRemainingOrchardBalance() }
    }

    @Test
    fun completionShowsOnlyMinedTransfersFromTheExactAccount() = runTest {
        val account = mockk<ZodlAccount>(relaxed = true) {
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16) { 4 }))
            every { isSpendable } returns true
        }
        val owner = account.sdkAccount.accountUuid.toStorageKeyId()
        val snapshot = mockk<GetMigrationSnapshotUseCase>()
        val first = crossing(1L, 200_000L, 1_785_281_502L)
        val second = crossing(2L, 300_000L, 1_785_281_622L)
        coEvery { snapshot(owner) } returns LiveMigrationSnapshot(listOf(first, second), emptyList(), 300L)
        val vm = vm(
            account = account,
            orchardBalanceZatoshi = 500_000L,
            router = FakeNavigationRouter(),
            args = MigrationCompleteArgs(accountKeyId = owner),
            getMigrationSnapshot = snapshot,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = assertNotNull(vm.state.value.content)
        assertEquals(stringRes(Zatoshi(500_000L)), state.totalTransferred)
        assertEquals(stringRes(formatMigrationDuration(120L, applyPrivacyFloor = false)), state.duration)
        coVerify(exactly = 1) { snapshot(owner) }
        collectJob.cancel()
    }

    @Test
    fun completionWithNoMinedAccountEvidenceOmitsTheHistoricalFigures() = runTest {
        val snapshot = mockk<GetMigrationSnapshotUseCase> {
            coEvery { this@mockk(any()) } returns LiveMigrationSnapshot(
                listOf(crossing(1L, 200_000L, null)), emptyList(), 300L,
            )
        }
        val vm = vm(
            account = mockk<ZodlAccount>(relaxed = true) { every { isSpendable } returns true },
            orchardBalanceZatoshi = 0L,
            router = FakeNavigationRouter(),
            getMigrationSnapshot = snapshot,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = assertNotNull(vm.state.value.content)
        assertFalse(state.canShowCompletion)
        assertNull(state.totalTransferred)
        assertNull(state.transfersProgress)
        assertNull(state.duration)
        collectJob.cancel()
    }

    @Test
    fun residueVariantReadsTheAccountIronwoodBalance() = runTest {
        val snapshot = mockk<GetMigrationSnapshotUseCase>()
        val vm = vm(
            account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
            orchardBalanceZatoshi = 500_000L,
            router = FakeNavigationRouter(),
            args = MigrationCompleteArgs(isResidueOnly = true),
            ironwoodBalanceZatoshi = 12_450_000L,
            getMigrationSnapshot = snapshot,
        )
        val collectJob = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(stringRes(Zatoshi(12_450_000L)), vm.state.value.content?.totalTransferred)
        coVerify(exactly = 0) { snapshot(any()) }
        collectJob.cancel()
    }

    private fun crossing(id: Long, amount: Long, minedAt: Long?) = LiveMigrationTransfer(
        id = id,
        index = 0,
        amountZatoshi = amount,
        scheduledHeight = 10L,
        scheduledAt = Instant.fromEpochSeconds(0),
        isSent = true,
        isProved = true,
        action = null,
        blocker = null,
        expiryAt = null,
        minedHeight = minedAt?.let { 11L },
        minedAt = minedAt?.let { Instant.fromEpochSeconds(it) },
    )

    @Test
    fun isResidueOnlyArgPropagatesThroughToState() =
        runTest {
            // MOB-1750: MigrationCompleteArgs.isResidueOnly must reach MigrationCompleteState
            // unchanged — this is what MigrationCompleteScreen branches its copy/summary card on.
            val vm =
                vm(
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = 500_000L,
                    router = FakeNavigationRouter(),
                    args = MigrationCompleteArgs(isResidueOnly = true),
                )

            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                true,
                vm.state.value.content
                    ?.isResidueOnly
            )
            collectJob.cancel()
        }

    @Test
    fun isResidueOnlyDefaultsToFalseForTheOriginalCelebrationVariant() =
        runTest {
            val vm =
                vm(
                    account = mockk<KeystoneAccount>(relaxed = true) { every { isSpendable } returns true },
                    orchardBalanceZatoshi = 500_000L,
                    router = FakeNavigationRouter(),
                    args = MigrationCompleteArgs(isResidueOnly = false),
                )

            val collectJob = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                false,
                vm.state.value.content
                    ?.isResidueOnly
            )
            collectJob.cancel()
        }

    @Test
    fun ledgerCompletionKeepsLocalLockWithoutOfferingAnUnsupportedSweep() = runTest {
        val lock = mockk<LockOrchardBalanceUseCase> { coEvery { this@mockk(any(), any()) } returns true }
        val vm = vm(
            account = mockk<LedgerAccount>(relaxed = true) { every { isSpendable } returns true },
            orchardBalanceZatoshi = 500_000L,
            router = FakeNavigationRouter(),
            args = MigrationCompleteArgs(isResidueOnly = true),
            lockOrchardBalance = lock,
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val state = assertNotNull(vm.state.value.content)
        assertFalse(state.canMigrateResidue)
        state.onLockBalance()
        advanceUntilIdle()
        coVerify(exactly = 1) { lock(any(), any()) }
    }

    @Test
    fun migrateAnywayOnAViewOnlyAccountIsRefusedWithoutSigning() =
        runTest {
            val router = FakeNavigationRouter()
            val sdk =
                mockk<OrchardMigrationSdk> {
                    coEvery { proposeImmediateMigration() } returns mockk(relaxed = true)
                }
            val vm =
                vm(
                    account = mockk<ViewOnlyAccount>(relaxed = true) { every { isSpendable } returns false },
                    orchardBalanceZatoshi = 500_000L,
                    router = router,
                    getOrchardMigrationSdk = mockk { coEvery { this@mockk(any<String>()) } returns sdk },
                    args = MigrationCompleteArgs(isResidueOnly = true),
                )

            advanceUntilIdle()
            invokeOnMigrateAnyway(vm)
            advanceUntilIdle()

            val migrateAnywayLce =
                MigrationCompleteVM::class.java
                    .getDeclaredField("migrateAnywayLce")
                    .apply { isAccessible = true }
                    .get(vm) as MutableLce<*>
            val error = assertNotNull(migrateAnywayLce.state.value.error)
            assertEquals(
                AccountOperationUnsupportedException.Kind.VIEW_ONLY,
                assertIs<AccountOperationUnsupportedException>(error.cause).kind
            )
            assertEquals(emptyList(), router.forwardedRoutes)
        }

    @Test
    fun completionForAStaleOwnerLeavesTheSelectedAccountsScheduleAndPreferencesAlone() = runTest {
        val reviewed = AccountUuid.new(ByteArray(16))
        val selected = AccountUuid.new(ByteArray(16) { 1 })
        val other = mockk<ZodlAccount> { every { sdkAccount } returns Account.new(selected) }
        val seen = mockk<HasSeenMigrationCompleteStorageProvider>(relaxed = true)
        val access = mockk<GetOrchardMigrationSdkUseCase>()
        val vm = vm(
            account = other,
            orchardBalanceZatoshi = 500_000L,
            router = FakeNavigationRouter(),
            args = MigrationCompleteArgs(accountKeyId = reviewed.toStorageKeyId()),
            getOrchardMigrationSdk = access,
            seen = seen,
        )
        advanceUntilIdle()
        invokeOnDone(vm)
        advanceUntilIdle()

        coVerify(exactly = 0) { access(any()) }
        coVerify(exactly = 0) { seen.store(any(), any()) }
    }

    private fun invokeOnDone(vm: MigrationCompleteVM) {
        val onDone = MigrationCompleteVM::class.java.getDeclaredMethod("onDone")
        onDone.isAccessible = true
        onDone.invoke(vm)
    }

    private fun invokeOnMigrateAnyway(vm: MigrationCompleteVM) {
        val onMigrateAnyway = MigrationCompleteVM::class.java.getDeclaredMethod("onMigrateAnyway", Long::class.java)
        onMigrateAnyway.isAccessible = true
        onMigrateAnyway.invoke(vm, 500_000L)
    }

    @Suppress("LongParameterList")
    private fun vm(
        seen: HasSeenMigrationCompleteStorageProvider = mockk(relaxed = true),
        account: WalletAccount,
        orchardBalanceZatoshi: Long,
        router: FakeNavigationRouter,
        args: MigrationCompleteArgs = MigrationCompleteArgs(),
        ironwoodBalanceZatoshi: Long = 0L,
        getMigrationSnapshot: GetMigrationSnapshotUseCase = mockk { coEvery { this@mockk(any()) } returns null },
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase =
            mockk {
                coEvery { this@mockk(any<String>()) } returns mockk(relaxed = true) {
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.Complete
                }
            },
        sourceEntryId: String? = null,
        orchardBalance: MutableStateFlow<Long> = MutableStateFlow(orchardBalanceZatoshi),
        lockOrchardBalance: LockOrchardBalanceUseCase = mockk(relaxed = true),
    ) = MigrationCompleteVM(
        args = args,
        getOrchardBalance =
            mockk<GetOrchardBalanceUseCase> {
                coEvery { this@mockk(any<AccountUuid>()) } coAnswers { Zatoshi(orchardBalance.value) }
                every { observe(any<AccountUuid>()) } returns orchardBalance.map { Zatoshi(it) }
            },
        getIronwoodBalance =
            mockk<GetIronwoodBalanceUseCase> {
                coEvery { this@mockk(any<AccountUuid>()) } returns Zatoshi(ironwoodBalanceZatoshi)
            },
        hasSeenMigrationCompleteStorageProvider = seen,
        getSelectedWalletAccount =
            mockk<GetSelectedWalletAccountUseCase> {
                coEvery { this@mockk() } returns account
            },
        navigationRouter = router,
        errorStateMapper = mockk<ErrorMapperUseCase>(relaxed = true),
        getOrchardMigrationSdk = getOrchardMigrationSdk,
        getMigrationSnapshot = getMigrationSnapshot,
        lockOrchardBalance = lockOrchardBalance,
        sourceEntryId = sourceEntryId,
    )

    private class FakeNavigationRouter(entryId: String? = null) : NavigationRouter {
        override val activeEntry = MutableStateFlow(entryId?.let { ActiveNavigationEntry(it, null) })
        var backToRootCount = 0
        val forwardedRoutes = mutableListOf<Any>()

        override fun forward(vararg routes: Any) {
            forwardedRoutes.addAll(routes)
        }

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() {
            backToRootCount++
        }

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
