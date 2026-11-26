package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorRoute
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
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
            coEvery { recoverFromSeedMismatch(any()) } returns Unit
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 1) { recoverFromSeedMismatch(any()) }
        }

    @Test
    fun recoveryFailureQueuesSupportAndDoesNotCrashTheCollector() =
        runTest {
            coEvery { recoverFromSeedMismatch(any()) } throws RuntimeException("reopen failed")
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 2) { recoverFromSeedMismatch(any()) }
            assertEquals(
                listOf(LaunchNavigationRequests.Kind.SEED_RECOVERY_ERROR),
                launchNavigation.changes.value.map { it.kind },
            )
        }

    @Test
    fun terminalFailureWaitsForWalletAccessAndSuccessfulNavigation() = runTest {
        coEvery { recoverFromSeedMismatch(any()) } throws SeedMismatchRecoveryException()
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
    fun resetInvalidatesARecoveryErrorThatHasNotReachedTheWallet() = runTest {
        coEvery { recoverFromSeedMismatch(any()) } throws SeedMismatchRecoveryException()
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
            coEvery { recoverFromSeedMismatch(any()) } throws CancellationException("cancelled")
            val job = backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            assertTrue(job.isCancelled)
            coVerify(exactly = 1) { recoverFromSeedMismatch(any()) }
            assertTrue(launchNavigation.changes.value.isEmpty())
        }

    @Test
    fun renewedMismatchWhileRereadingProducesOneSupportRoute() = runTest {
        val pending = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { recoverFromSeedMismatch(any()) } coAnswers {
            if (++reads == 1) pending.await() else throw SeedMismatchRecoveryException()
        }
        backgroundScope.launch { useCase() }
        runCurrent()
        isSeedMismatchState.value = true
        runCurrent()

        isSeedMismatchState.value = false
        runCurrent()
        isSeedMismatchState.value = true
        runCurrent()
        pending.complete(Unit)
        runCurrent()

        assertEquals(2, reads)
        assertEquals(listOf(LaunchNavigationRequests.Kind.SEED_RECOVERY_ERROR),
            launchNavigation.changes.value.map { it.kind })
    }
}
