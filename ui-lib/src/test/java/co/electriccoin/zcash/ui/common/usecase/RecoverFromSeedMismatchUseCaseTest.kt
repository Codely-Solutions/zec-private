package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecoverFromSeedMismatchUseCaseTest {
    private val wallet = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("example.invalid", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet,
    )
    private val access = WalletWriteAccess()
    private val walletProvider = RecordingWalletProvider(wallet, access)
    private val walletCoordinator = mockk<WalletCoordinator>()
    private val preferenceRecovery = mockk<SdkEncryptedPreferenceRecoveryProvider>(relaxed = true)
    private val recovery = RecoverFromSeedMismatchUseCase(walletProvider, walletCoordinator, access, preferenceRecovery)

    private suspend fun useCase() = recovery(access.token())

    @Test
    fun successfulEraseRePersistsOnlyAgainstTheOriginalWalletAndVersion() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flow {
            assertTrue(walletProvider.replacements.isEmpty())
            emit(true)
        }

        useCase()

        verify(exactly = 1) { walletCoordinator.deleteSdkDataFlow() }
        assertEquals(
            listOf(wallet.copy(walletInitMode = WalletInitMode.RestoreWallet)),
            walletProvider.replacements,
        )
    }

    @Test
    fun noWalletStoredIsANoOp() = runTest {
        walletProvider.persistableWallet.value = null

        useCase()

        verify(exactly = 0) { walletCoordinator.deleteSdkDataFlow() }
        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun failedEraseDoesNotPersistAndThrowsExplicitError() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)

        assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun unreadablePreferencesRefuseDetachedEraseWithoutRepairingOrReplacingWallet() = runTest {
        coEvery { preferenceRecovery.checkReadableForErase() } throws IllegalStateException("Unreadable fixture")

        assertEquals(1, assertFailsWith<SeedMismatchRecoveryException> { useCase() }.failedAttempts)
        verify(exactly = 0) { walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { preferenceRecovery.ensureReadable() }
        assertEquals(wallet, walletProvider.persistableWallet.value)
        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun repeatedFailuresAreCappedAndStopHittingTheSdk() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)

        repeat(5) {
            assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        }

        verify(exactly = 3) { walletCoordinator.deleteSdkDataFlow() }
        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun replacementWalletGetsItsOwnRecoveryAttempts() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)
        repeat(3) { assertFailsWith<SeedMismatchRecoveryException> { useCase() } }
        val old = access.token()
        access.replaceWallet { walletProvider.persistableWallet.value = wallet.copy() }

        recovery(old)
        val firstFailure = assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        assertEquals(1, firstFailure.failedAttempts)
        verify(exactly = 4) { walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun successAfterPriorFailuresResetsTheCounter() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(true)
        useCase()

        assertEquals(1, walletProvider.replacements.size)

        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        verify(exactly = 5) { walletCoordinator.deleteSdkDataFlow() }
        assertEquals(1, walletProvider.replacements.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedEraseOffersHelpButKeepsWalletReplacementFencedUntilCompletion() = runTest {
        val sdkResult = CompletableDeferred<Boolean>()
        every { walletCoordinator.deleteSdkDataFlow() } returns flow { emit(sdkResult.await()) }
        val delayed = CompletableDeferred<Unit>()
        val recovering = launch { recovery(access.token()) { delayed.complete(Unit) } }
        val replacing = launch { access.replaceWallet { walletProvider.persistableWallet.value = wallet } }
        try {
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(delayed.isCompleted)
            assertTrue(walletProvider.replacements.isEmpty())
            assertTrue(!replacing.isCompleted)
        } finally {
            sdkResult.complete(true)
        }
        recovering.join()
        replacing.join()
        assertEquals(wallet, walletProvider.persistableWallet.value)
        verify(exactly = 1) { walletCoordinator.deleteSdkDataFlow() }
    }

    private class RecordingWalletProvider(
        initial: PersistableWallet,
        private val access: WalletWriteAccess,
    ) : PersistableWalletProvider {
        override val persistableWallet = MutableStateFlow<PersistableWallet?>(initial)
        override val writeVersion get() = access.token()
        val replacements = mutableListOf<PersistableWallet>()

        override suspend fun store(persistableWallet: PersistableWallet): Unit =
            error("Recovery must compare the original wallet and version before storing")

        override suspend fun storeIfCurrent(
            expected: PersistableWallet,
            replacement: PersistableWallet,
            version: Long,
        ): Boolean {
            assertEquals(writeVersion, version)
            assertEquals(persistableWallet.value, expected)
            replacements += replacement
            persistableWallet.value = replacement
            return true
        }

        override suspend fun getPersistableWallet() = persistableWallet.value

        override suspend fun requirePersistableWallet() = checkNotNull(persistableWallet.value)
    }
}
