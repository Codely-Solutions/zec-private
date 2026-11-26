package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.WalletSeedRecovery
import co.electriccoin.zcash.ui.common.provider.eraseResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    private val sdkRecovery = mockk<WalletSeedRecovery>()
    private val preferenceRecovery = mockk<SdkEncryptedPreferenceRecoveryProvider>(relaxed = true)
    private val recovery = RecoverFromSeedMismatchUseCase(walletProvider, sdkRecovery, access, preferenceRecovery)

    private suspend fun useCase() = recovery(access.token())

    @Test
    fun destructiveRecoveryExpiresAnImportedReceiptEvenWhenTheWalletGenerationStaysTheSame() = runTest {
        val originalGeneration = access.token()
        val pendingImport = assertNotNull(access.enrollmentToken())
        sdkRecovery.eraseResult {
            assertFalse(access.permitsEnrollment(pendingImport))
            true
        }

        useCase()

        assertEquals(originalGeneration, access.token())
        assertFalse(access.permitsEnrollment(pendingImport))
        assertTrue(access.permitsEnrollment(assertNotNull(access.enrollmentToken())))
    }

    @Test
    fun successfulEraseRequiresTheCurrentWalletRestoreIntentFirst() = runTest {
        sdkRecovery.eraseResult {
            assertEquals(WalletInitMode.RestoreWallet, walletProvider.replacements.single().walletInitMode)
            assertEquals(1, walletProvider.readRetries)
            true
        }

        useCase()

        coVerify(exactly = 1) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertEquals(
            listOf(wallet.copy(walletInitMode = WalletInitMode.RestoreWallet)),
            walletProvider.replacements,
        )
        assertEquals(1, walletProvider.readRetries)
    }

    @Test
    fun noWalletStoredIsANoOp() = runTest {
        walletProvider.persistableWallet.value = null

        useCase()

        coVerify(exactly = 0) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun aLegacyWalletWithNoBirthdayRestoresFromTheEarliestSupportedHeight() = runTest {
        walletProvider.persistableWallet.value = wallet.copy(birthday = null)
        sdkRecovery.eraseResult { true }

        useCase()

        val restored = walletProvider.replacements.single()
        assertEquals(wallet.network.saplingActivationHeight, restored.birthday)
        assertEquals(WalletInitMode.RestoreWallet, restored.walletInitMode)
        assertEquals(wallet.seedPhrase, restored.seedPhrase)
    }

    @Test
    fun failedEraseRetainsRestoreIntentAndThrowsExplicitError() = runTest {
        sdkRecovery.eraseResult { false }

        assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        assertEquals(WalletInitMode.RestoreWallet, walletProvider.replacements.single().walletInitMode)
    }

    @Test
    fun unreadablePreferencesRefuseDetachedEraseWithoutRepairingOrReplacingWallet() = runTest {
        coEvery { preferenceRecovery.checkReadableForErase() } throws IllegalStateException("Unreadable fixture")

        assertEquals(1, assertFailsWith<SeedMismatchRecoveryException> { useCase() }.failedAttempts)
        coVerify(exactly = 0) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        coVerify(exactly = 0) { preferenceRecovery.ensureReadable() }
        assertEquals(wallet, walletProvider.persistableWallet.value)
        assertTrue(walletProvider.replacements.isEmpty())
    }

    @Test
    fun recoveryRetriesAReadFailureBeforeEnteringTheSdkOnce() = runTest {
        coEvery { preferenceRecovery.checkReadableForErase() } throws IllegalStateException("Unavailable") andThen Unit
        sdkRecovery.eraseResult { true }

        useCase()

        coVerify(exactly = 2) { preferenceRecovery.checkReadableForErase() }
        coVerify(exactly = 1) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertEquals(WalletInitMode.RestoreWallet, walletProvider.persistableWallet.value?.walletInitMode)
    }

    @Test
    fun repeatedFailuresAreCappedAndStopHittingTheSdk() = runTest {
        sdkRecovery.eraseResult { false }

        repeat(5) {
            assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        }

        coVerify(exactly = 3) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertTrue(walletProvider.replacements.all { it.walletInitMode == WalletInitMode.RestoreWallet })
    }

    @Test
    fun replacementWalletGetsItsOwnRecoveryAttempts() = runTest {
        sdkRecovery.eraseResult { false }
        repeat(3) { assertFailsWith<SeedMismatchRecoveryException> { useCase() } }
        val old = access.token()
        access.replaceWallet { walletProvider.persistableWallet.value = wallet.copy() }

        recovery(old)
        val firstFailure = assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        assertEquals(1, firstFailure.failedAttempts)
        coVerify(exactly = 4) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
    }

    @Test
    fun successfulEraseStopsAdditionalAutomaticErasesForTheSameWalletSession() = runTest {
        sdkRecovery.eraseResult { false }
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        assertFailsWith<SeedMismatchRecoveryException> { useCase() }

        sdkRecovery.eraseResult { true }
        useCase()

        assertEquals(3, walletProvider.replacements.size)

        val error = assertFailsWith<SeedMismatchRecoveryException> { useCase() }
        assertEquals("Seed mismatch persisted after the completed SDK erase", error.message)
        coVerify(exactly = 3) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertEquals(3, walletProvider.replacements.size)
    }

    @Test
    fun restorePreparationExceptionsAreBoundedAndPreserveTheWallet() = runTest {
        walletProvider.failRestoreWrite = true
        var erases = 0
        sdkRecovery.eraseResult {
            erases += 1
            true
        }

        for (failure in listOf(1, 2, 3, 3)) {
            assertEquals(failure, assertFailsWith<SeedMismatchRecoveryException> { useCase() }.failedAttempts)
        }

        coVerify(exactly = 3) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
        assertEquals(0, erases)
        assertEquals(wallet, walletProvider.persistableWallet.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedEraseOffersHelpButKeepsWalletReplacementFencedUntilCompletion() = runTest {
        val sdkResult = CompletableDeferred<Boolean>()
        sdkRecovery.eraseResult { sdkResult.await() }
        val delayed = CompletableDeferred<Unit>()
        val recovering = launch { recovery(access.token()) { delayed.complete(Unit) } }
        val replacing = launch { access.replaceWallet { walletProvider.persistableWallet.value = wallet } }
        try {
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(delayed.isCompleted)
            assertEquals(WalletInitMode.RestoreWallet, walletProvider.replacements.single().walletInitMode)
            assertTrue(!replacing.isCompleted)
        } finally {
            sdkResult.complete(true)
        }
        recovering.join()
        replacing.join()
        assertEquals(wallet, walletProvider.persistableWallet.value)
        coVerify(exactly = 1) { sdkRecovery.eraseAndRestore(any(), any(), any()) }
    }

    private class RecordingWalletProvider(
        initial: PersistableWallet,
        private val access: WalletWriteAccess,
    ) : PersistableWalletProvider {
        override val persistableWallet = MutableStateFlow<PersistableWallet?>(initial)
        override val writeVersion get() = access.token()
        val replacements = mutableListOf<PersistableWallet>()
        var readRetries = 0
        var failRestoreWrite = false

        override fun retryRead() {
            assertEquals(WalletInitMode.RestoreWallet, replacements.last().walletInitMode)
            readRetries += 1
        }

        override suspend fun store(persistableWallet: PersistableWallet): Unit =
            error("Recovery must compare the original wallet and version before storing")

        override suspend fun storeIfCurrent(
            expected: PersistableWallet,
            replacement: PersistableWallet,
            version: Long,
        ): Boolean {
            assertEquals(writeVersion, version)
            assertEquals(persistableWallet.value, expected)
            check(!failRestoreWrite) { "Synthetic restore persistence failure" }
            replacements += replacement
            persistableWallet.value = replacement
            return true
        }

        override suspend fun getPersistableWallet() = persistableWallet.value

        override suspend fun requirePersistableWallet() = checkNotNull(persistableWallet.value)
    }
}
