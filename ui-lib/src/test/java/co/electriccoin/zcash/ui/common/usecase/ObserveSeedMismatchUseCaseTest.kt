package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * MOB-1397: [ObserveSeedMismatchUseCase] runs recovery on every `true` flip of
 * [SynchronizerProvider.isSeedMismatch], keeps collecting after a failed recovery, and routes a terminal
 * [SeedMismatchRecoveryException] to the error screen.
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
            coEvery { recoverFromSeedMismatch() } returns Unit
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 1) { recoverFromSeedMismatch() }
        }

    @Test
    fun recoveryFailureIsLoggedAndDoesNotCrashTheCollector() =
        runTest {
            coEvery { recoverFromSeedMismatch() } throws RuntimeException("erase failed")
            backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            coVerify(exactly = 2) { recoverFromSeedMismatch() }
            assertTrue(launchNavigation.changes.value.isEmpty())
        }

    @Test
    fun terminalFailureWaitsForWalletAccessAndSuccessfulNavigation() = runTest {
        val exception = SeedMismatchRecoveryException(3)
        coEvery { recoverFromSeedMismatch() } throws exception
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
            assertEquals(ErrorArgs.General(exception), navigateToError.argsFor(error))
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
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun resetInvalidatesARecoveryErrorThatHasNotReachedTheWallet() = runTest {
        coEvery { recoverFromSeedMismatch() } throws SeedMismatchRecoveryException(3)
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
     * [recoveryFailureIsLoggedAndDoesNotCrashTheCollector] a further flip must not retrigger recovery.
     */
    @Test
    fun cancellationExceptionIsNotSwallowedAndStopsTheCollector() =
        runTest {
            coEvery { recoverFromSeedMismatch() } throws CancellationException("cancelled")
            val job = backgroundScope.launch { useCase() }
            runCurrent()

            isSeedMismatchState.value = true
            runCurrent()
            isSeedMismatchState.value = false
            runCurrent()
            isSeedMismatchState.value = true
            runCurrent()

            assertTrue(job.isCancelled)
            coVerify(exactly = 1) { recoverFromSeedMismatch() }
            assertTrue(launchNavigation.changes.value.isEmpty())
        }

    @Test
    fun sdkClearingMismatchDuringEraseLetsEnteredRecoveryFinish() = runTest {
        val mismatch = MutableStateFlow(true)
        val entered = CompletableDeferred<Unit>()
        val finishErase = CompletableDeferred<Unit>()
        val coordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flow {
                entered.complete(Unit)
                mismatch.value = false
                finishErase.await()
                emit(true)
            }
        }
        val synchronizer = mockk<SynchronizerProvider> {
            every { isSeedMismatch } returns mismatch
        }
        val initial = PersistableWallet(
            network = ZcashNetwork.Testnet,
            birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
            endpoint = LightWalletEndpoint("example.invalid", 443, true),
            seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
            walletInitMode = WalletInitMode.ExistingWallet,
        )
        val wallet = RecoveryWallet(initial)
        val recover = RecoverFromSeedMismatchUseCase(wallet, coordinator)
        val observe = ObserveSeedMismatchUseCase(
            synchronizer, recover, navigateToError, launchNavigation, walletWriteAccess,
        )
        backgroundScope.launch { observe() }
        runCurrent()

        assertTrue(entered.isCompleted)
        assertEquals(WalletInitMode.ExistingWallet, wallet.requirePersistableWallet().walletInitMode)
        finishErase.complete(Unit)
        runCurrent()

        assertEquals(initial.copy(walletInitMode = WalletInitMode.RestoreWallet), wallet.requirePersistableWallet())
    }

    private class RecoveryWallet(initial: PersistableWallet) : PersistableWalletProvider {
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
            persistableWallet.value = replacement
            return true
        }

        override suspend fun getPersistableWallet() = persistableWallet.value

        override suspend fun requirePersistableWallet() = checkNotNull(persistableWallet.value)
    }
}
