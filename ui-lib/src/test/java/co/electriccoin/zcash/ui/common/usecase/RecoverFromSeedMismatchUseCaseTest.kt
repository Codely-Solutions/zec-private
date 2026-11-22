package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class RecoverFromSeedMismatchUseCaseTest {
    private val wallet = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("example.invalid", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet,
    )
    private val walletProvider = RecordingWalletProvider(wallet)
    private val walletCoordinator = mockk<WalletCoordinator>()
    private val useCase = RecoverFromSeedMismatchUseCase(walletProvider, walletCoordinator)

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
    fun repeatedFailuresAreCappedAndStopHittingTheSdk() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)

        repeat(5) {
            assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        }

        verify(exactly = 3) { walletCoordinator.deleteSdkDataFlow() }
        assertTrue(walletProvider.replacements.isEmpty())
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

    @Test
    fun eraseTimeoutCountsAsFailedAttemptAndThrows() = runTest {
        every { walletCoordinator.deleteSdkDataFlow() } returns flow<Boolean> { delay(1.days) }

        assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        assertTrue(walletProvider.replacements.isEmpty())
    }

    private class RecordingWalletProvider(initial: PersistableWallet) : PersistableWalletProvider {
        override val persistableWallet = MutableStateFlow<PersistableWallet?>(initial)
        override val writeVersion = 42L
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
