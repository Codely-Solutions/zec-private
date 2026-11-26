package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.seedRecoveryFixture
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorRoute
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.SeedRecoveryPhase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * MOB-1397: [ObserveSeedMismatchUseCase] runs recovery on every `true` flip of
 * [SynchronizerProvider.isSeedMismatch], keeps collecting after a failed recovery, and routes a terminal
 * recovery failure to the error screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSeedMismatchUseCaseTest {
    private val isSeedMismatchState = MutableStateFlow(false)
    private val synchronizerProvider =
        mockk<SynchronizerProvider> {
            every { isSeedMismatch } returns isSeedMismatchState
        }
    private val recoverFromSeedMismatch = mockk<RecoverFromSeedMismatchUseCase>()
    private val navigateToError = NavigateToErrorUseCase(mockk())
    private val launchNavigation = LaunchNavigationRequests()
    private val walletWriteAccess = WalletWriteAccess()

    private val useCase =
        ObserveSeedMismatchUseCase(
            synchronizerProvider = synchronizerProvider,
            recoverFromSeedMismatch = recoverFromSeedMismatch,
            navigateToError = navigateToError,
            launchNavigation = launchNavigation,
            walletWriteAccess = walletWriteAccess,
        )

    @Test
    fun seedMismatchTriggersRecovery() =
        runTest {
            coEvery { recoverFromSeedMismatch(any(), any()) } returns Unit
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 1) { recoverFromSeedMismatch(any(), any()) }
        }

    @Test
    fun recoveryFailureQueuesSupportAndDoesNotCrashTheCollector() =
        runTest {
            coEvery { recoverFromSeedMismatch(any(), any()) } throws RuntimeException("erase failed")
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 2) { recoverFromSeedMismatch(any(), any()) }
            assertEquals(
                listOf(LaunchNavigationRequests.Kind.SEED_RECOVERY_ERROR),
                launchNavigation.changes.value.map { it.kind },
            )
        }

    @Test
    fun terminalFailureWaitsForWalletAccessAndSuccessfulNavigation() = runTest {
        coEvery { recoverFromSeedMismatch(any(), any()) } throws SeedMismatchRecoveryException(3)
        launchNavigation.beginMigrationProgress("unresolved-owner")
        launchNavigation.requestGiftScan()
        backgroundScope.launch { useCase() }
        runCurrent()

        isSeedMismatchState.value = true
        runCurrent()
        isSeedMismatchState.value = false
        var unlocked = false
        var attempts = 0
        var accepts = false
        suspend fun tryDelivery() = launchNavigation.deliver(
            walletReady = true, hasAppAccess = { unlocked },
        ) { command, current ->
            assertTrue(current())
            val error = assertIs<ErrorRoute>(assertIs<NavigationCommand.Forward>(command).routes.single())
            assertEquals(ErrorArgs.SeedRecoveryFailed, navigateToError.argsFor(error))
            attempts++
            accepts
        }

        tryDelivery()
        assertEquals(0, attempts)
        unlocked = true
        tryDelivery()
        assertFalse(launchNavigation.changes.value.isEmpty())
        accepts = true
        tryDelivery()
        tryDelivery()
        assertEquals(2, attempts)
        assertEquals(
            listOf(LaunchNavigationRequests.Kind.MIGRATION_PROGRESS, LaunchNavigationRequests.Kind.GIFT_SCAN),
            launchNavigation.changes.value.map { it.kind },
        )
    }

    @Test
    fun terminalFailureUpdatesTheDeliveredWaitingNoticeWithoutQueueingAnotherDialog() = runTest {
        val finish = CompletableDeferred<Unit>()
        coEvery { recoverFromSeedMismatch(any(), any()) } coAnswers {
            secondArg<() -> Unit>().invoke()
            finish.await()
            throw SeedMismatchRecoveryException(1)
        }
        backgroundScope.launch { useCase() }
        runCurrent()
        isSeedMismatchState.value = true
        runCurrent()
        var route: ErrorRoute? = null
        launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { command, _ ->
            route = assertIs<ErrorRoute>(assertIs<NavigationCommand.Forward>(command).routes.single())
            true
        }
        val notice = assertIs<ErrorArgs.SeedRecoveryNotice>(navigateToError.argsFor(checkNotNull(route)))
        assertEquals(SeedRecoveryPhase.WAITING, notice.phase.value)

        finish.complete(Unit)
        runCurrent()

        assertEquals(SeedRecoveryPhase.FAILED, notice.phase.value)
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun resetInvalidatesARecoveryErrorThatHasNotReachedTheWallet() = runTest {
        coEvery { recoverFromSeedMismatch(any(), any()) } throws SeedMismatchRecoveryException(3)
        backgroundScope.launch { useCase() }
        runCurrent()
        isSeedMismatchState.value = true
        runCurrent()
        assertFalse(launchNavigation.changes.value.isEmpty())
        walletWriteAccess.beginReset()

        launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { _, _ ->
            error("A previous wallet's failure must not be presented")
        }
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    /**
     * The collector's coroutine is cancelled by the propagated [CancellationException], so unlike
     * [recoveryFailureQueuesSupportAndDoesNotCrashTheCollector] a further flip must not retrigger recovery.
     */
    @Test
    fun cancellationExceptionIsNotSwallowedAndStopsTheCollector() =
        runTest {
            coEvery { recoverFromSeedMismatch(any(), any()) } throws CancellationException("cancelled")
            val job = backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            assertTrue(job.isCancelled)
            coVerify(exactly = 1) { recoverFromSeedMismatch(any(), any()) }
            assertTrue(launchNavigation.changes.value.isEmpty())
        }

    @Test
    fun aRenewedMismatchDuringEraseOffersSupportWithoutErasingAgain() = runTest {
        val mismatch = MutableStateFlow(true)
        val entered = CompletableDeferred<Unit>()
        val finishErase = CompletableDeferred<Unit>()
        var erases = 0
        val sdkRecovery = seedRecoveryFixture {
            erases += 1
            entered.complete(Unit)
            mismatch.value = false
            finishErase.await()
            true
        }
        val synchronizer = mockk<SynchronizerProvider> {
            every { isSeedMismatch } returns mismatch
        }
        val initial = existingTestWallet()
        val wallet = RecoveryWallet(initial)
        val recover = RecoverFromSeedMismatchUseCase(wallet, sdkRecovery, walletWriteAccess, mockk(relaxed = true))
        val observe = ObserveSeedMismatchUseCase(
            synchronizer, recover, navigateToError, launchNavigation, walletWriteAccess,
        )
        backgroundScope.launch { observe() }
        runCurrent()

        assertTrue(entered.isCompleted)
        assertEquals(WalletInitMode.RestoreWallet, wallet.requirePersistableWallet().walletInitMode)
        mismatch.value = true
        runCurrent()
        finishErase.complete(Unit)
        runCurrent()

        assertEquals(1, erases)
        assertEquals(initial.copy(walletInitMode = WalletInitMode.RestoreWallet), wallet.requirePersistableWallet())
        var delivered = false
        launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { command, _ ->
            val route = assertIs<ErrorRoute>(assertIs<NavigationCommand.Forward>(command).routes.single())
            assertEquals(ErrorArgs.SeedRecoveryFailed, navigateToError.argsFor(route))
            delivered = true
            true
        }
        assertTrue(delivered)
    }

    @Test
    fun restoreWriteFailureRefusesTheEraseAndOffersSupportBeforeDelay() = runTest {
        var eraseAttempts = 0
        val sdkRecovery = seedRecoveryFixture {
            eraseAttempts += 1
            true
        }
        val initial = existingTestWallet()
        val wallet = RecoveryWallet(initial, failRestoreWrite = true)
        val recover = RecoverFromSeedMismatchUseCase(wallet, sdkRecovery, walletWriteAccess, mockk(relaxed = true))
        val observe = ObserveSeedMismatchUseCase(
            synchronizerProvider, recover, navigateToError, launchNavigation, walletWriteAccess,
        )
        backgroundScope.launch { observe() }
        runCurrent()

        isSeedMismatchState.value = true
        runCurrent()

        assertEquals(initial, wallet.requirePersistableWallet())
        assertEquals(0, eraseAttempts)
        var delivered = false
        launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { command, _ ->
            val route = assertIs<ErrorRoute>(assertIs<NavigationCommand.Forward>(command).routes.single())
            assertEquals(ErrorArgs.SeedRecoveryFailed, navigateToError.argsFor(route))
            delivered = true
            true
        }
        assertTrue(delivered)
    }

    private fun existingTestWallet() = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("example.invalid", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet,
    )

    private class RecoveryWallet(
        initial: PersistableWallet,
        private val failRestoreWrite: Boolean = false,
    ) : PersistableWalletProvider {
        override val persistableWallet = MutableStateFlow<PersistableWallet?>(initial)

        override suspend fun store(persistableWallet: PersistableWallet): Unit =
            error("Recovery must use conditional storage")

        override suspend fun storeIfCurrent(
            expected: PersistableWallet,
            replacement: PersistableWallet,
            version: Long,
        ): Boolean {
            assertEquals(writeVersion, version)
            assertEquals(persistableWallet.value, expected)
            if (failRestoreWrite) error("Synthetic encrypted restore write failure")
            persistableWallet.value = replacement
            return true
        }

        override suspend fun getPersistableWallet() = persistableWallet.value

        override suspend fun requirePersistableWallet() = checkNotNull(persistableWallet.value)
    }
}
