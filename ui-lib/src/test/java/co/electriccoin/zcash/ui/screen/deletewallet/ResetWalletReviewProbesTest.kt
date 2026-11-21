package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResetWalletReviewProbesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun failedSdkErasePreservesPreferencesAndRetrySkipsDeletedFiles() = runTest {
        val fixture = Fixture()
        every { fixture.walletCoordinator.deleteSdkDataFlow() } returns flowOf(false)

        assertFailsWith<IllegalStateException> { fixture.reset(keepFiles = false) }
        assertEquals(ResetStage.ERASE_SDK, fixture.progressStore().getStage())
        coVerify(exactly = 0) { fixture.standardPrefs.clearPreferences() }
        coVerify(exactly = 0) { fixture.encryptedPrefs.clearPreferences() }

        every { fixture.walletCoordinator.deleteSdkDataFlow() } returns flowOf(true)
        fixture.newReset()(keepFiles = true)

        coVerify(exactly = 1) { fixture.addressBookRepository.delete() }
        coVerify(exactly = 1) { fixture.metadataRepository.delete() }
        coVerify(exactly = 1) { fixture.standardPrefs.clearPreferences() }
        coVerify(exactly = 1) { fixture.encryptedPrefs.clearPreferences() }
        assertNull(fixture.progressStore().getStage())
    }

    @Test
    fun resetWaitsForBothFileDeletionsBeforeErasingSdkOrPreferences() = runTest {
        val fixture = Fixture()
        val metadataStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.metadataRepository.delete() } coAnswers {
            metadataStarted.complete(Unit)
            allowDeletion.await()
        }

        val operation = launch { fixture.reset(keepFiles = false) }
        metadataStarted.await()

        coVerify(exactly = 1) { fixture.addressBookRepository.delete() }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.encryptedPrefs.clearPreferences() }
        allowDeletion.complete(Unit)
        operation.join()

        coVerify(exactly = 1) { fixture.encryptedPrefs.clearPreferences() }
    }

    @Test
    fun fileDeletionFailurePreventsSdkEraseAndCanBeRetried() = runTest {
        val fixture = Fixture()
        coEvery { fixture.metadataRepository.delete() } throws IOException("Synthetic deletion failure")

        assertFailsWith<IOException> { fixture.reset(keepFiles = false) }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        assertNull(fixture.progressStore().getStage())

        coEvery { fixture.metadataRepository.delete() } returns Unit
        fixture.reset(keepFiles = false)
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun preferencesCanRetryAfterRestartWithoutRequiringSdkOrWalletKeys() = runTest {
        val fixture = Fixture()
        coEvery { fixture.standardPrefs.clearPreferences() } returns false
        assertFailsWith<IllegalStateException> { fixture.reset(keepFiles = true) }
        coVerify(exactly = 0) { fixture.encryptedPrefs.clearPreferences() }

        coEvery { fixture.standardPrefs.clearPreferences() } returns true
        coEvery { fixture.encryptedPrefs.clearPreferences() } returns false
        assertFailsWith<IllegalStateException> { fixture.newReset()(keepFiles = false) }

        val resumed = fixture.newReset()
        assertTrue(resumed.isPending.value)
        assertEquals(ResetStage.CLEAR_PREFERENCES, fixture.progressStore().getStage())
        coEvery { fixture.encryptedPrefs.clearPreferences() } returns true
        resumed(keepFiles = false)

        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.addressBookRepository.delete() }
        coVerify(exactly = 0) { fixture.metadataRepository.delete() }
        assertNull(fixture.progressStore().getStage())
    }

    private inner class Fixture {
        val walletCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }
        private val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } coAnswers { awaitCancellation() }
        }
        val standardPrefs = mockk<PreferenceProvider> {
            coEvery { clearPreferences() } returns true
        }
        val encryptedPrefs = mockk<PreferenceProvider> {
            coEvery { clearPreferences() } returns true
        }
        private val standardProvider = mockk<StandardPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns standardPrefs
        }
        private val encryptedProvider = mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns encryptedPrefs
            coEvery { purgeQuarantine() } returns true
        }
        val addressBookRepository = mockk<AddressBookRepository>(relaxed = true)
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        private val context = mockk<Context> {
            every { noBackupFilesDir } returns directory.root
        }

        fun progressStore() = ResetProgressStore(context, DirectorySync {})

        val reset = newReset()

        fun newReset() = ResetZashiUseCase(
            walletCoordinator = walletCoordinator,
            synchronizerProvider = synchronizerProvider,
            standardPreferenceProvider = standardProvider,
            encryptedPreferenceProvider = encryptedProvider,
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            addressBookRepository = addressBookRepository,
            metadataRepository = metadataRepository,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = mockk(relaxed = true),
            progressStore = progressStore(),
        )
    }
}
