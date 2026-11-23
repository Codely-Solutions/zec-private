package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.spackle.getInternalCacheDirSuspend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.AddressBookDataSourceImpl
import co.electriccoin.zcash.ui.common.provider.AddressBookKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.AddressBookProviderImpl
import co.electriccoin.zcash.ui.common.provider.AddressBookStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.AddressBookRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_ENCRYPTION_KEY_SIZE
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import co.electriccoin.zcash.ui.common.usecase.CACHE_SUBDIR
import co.electriccoin.zcash.ui.common.usecase.DeleteSharedQRImagesUseCase
import co.electriccoin.zcash.ui.common.usecase.VK_QR_FILENAME_PREFIX
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ResetWalletReviewProbesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun failedSdkErasePreservesPreferencesAndRetrySkipsDeletedFiles() = runBlocking {
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
    fun resetWaitsForBothFileDeletionsBeforeErasingSdkOrPreferences() = runBlocking {
        val fixture = Fixture()
        val metadataStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.metadataRepository.delete() } coAnswers {
            metadataStarted.complete(Unit)
            allowDeletion.await()
        }

        val operation = launch { fixture.reset(keepFiles = false) }
        try {
            withTimeout(10.seconds) { metadataStarted.await() }
            coVerify(exactly = 1) { fixture.addressBookRepository.delete() }
            verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
            coVerify(exactly = 0) { fixture.encryptedPrefs.clearPreferences() }
        } finally {
            allowDeletion.complete(Unit)
        }
        operation.join()

        coVerify(exactly = 1) { fixture.encryptedPrefs.clearPreferences() }
    }

    @Test
    fun fileDeletionFailurePreventsSdkEraseAndCanBeRetried() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.metadataRepository.delete() } throws IOException("Synthetic deletion failure")

        assertFailsWith<IOException> { fixture.reset(keepFiles = false) }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        assertEquals(ResetStage.DELETE_LOCAL_FILES, fixture.progressStore().getStage())
        coVerify(exactly = 0) { fixture.encryptedPrefs.clearPreferences() }

        coEvery { fixture.metadataRepository.delete() } returns Unit
        val resumed = fixture.newReset()
        assertTrue(resumed.isPending.value)
        resumed(keepFiles = true)
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 2) { fixture.addressBookRepository.delete() }
        coVerify(exactly = 2) { fixture.metadataRepository.delete() }
        assertNull(fixture.progressStore().getStage())
    }

    @Test
    fun preferencesCanRetryAfterRestartWithoutRequiringSdkOrWalletKeys() = runBlocking {
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

    @Test
    fun retentionThenOptOutRemovesBothWalletContactBooksAndOwnedArtifacts() = runBlocking {
        val fixture = Fixture()
        val firstWalletKey = addressBookKey(1)
        val currentWalletKey = addressBookKey(2)
        val storage = fixture.addressBookStorage()
        val source = addressBookSource(storage)
        val first = storage.getStorageTarget(firstWalletKey)
        val current = storage.getStorageTarget(currentWalletKey)
        source.saveContact("Retained Contact", "synthetic-first-recipient", null, firstWalletKey)

        fixture.newReset(fixture.contactRepository(source))(keepFiles = true)
        assertTrue(first.isFile)
        source.saveContact("Current Contact", "synthetic-current-recipient", null, currentWalletKey)
        val temporary = current.resolveSibling(current.name + ".tmp").apply { writeText("interrupted write") }
        val legacy = storage.getLegacyUnencryptedStorageTarget().apply { writeText("legacy export") }
        val unrelated = first.resolveSibling("unrelated.txt").apply { writeText("keep") }

        fixture.newReset(fixture.contactRepository(source))(keepFiles = false)

        listOf(first, current, temporary, legacy).forEach { assertFalse(it.exists(), it.name) }
        assertEquals("keep", unrelated.readText())
        verify(exactly = 2) { fixture.walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun optOutWithoutASynchronizerCachedKeyOrAccountStillErasesSdkAndPreferences() = runBlocking {
        val fixture = Fixture(synchronizerAvailable = false)
        val unavailableAccount = mockk<AccountDataSource> {
            every { zashiAccount } returns flowOf(null)
            coEvery { getZashiAccount() } coAnswers { awaitCancellation() }
        }
        val keys = mockk<AddressBookKeyStorageProvider>()
        val repository = fixture.contactRepository(
            addressBookSource(fixture.addressBookStorage()), unavailableAccount, keys
        )

        fixture.newReset(repository)(keepFiles = false)

        coVerify(exactly = 0) { unavailableAccount.getZashiAccount() }
        coVerify(exactly = 0) { keys.getAddressBookKey() }
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 1) { fixture.encryptedPrefs.clearPreferences() }
        assertNull(fixture.progressStore().getStage())
    }

    private fun addressBookKey(marker: Byte) = AddressBookKey(
        SecretBytes.copyFrom(ByteArray(ADDRESS_BOOK_ENCRYPTION_KEY_SIZE) { marker }, InsecureSecretKeyAccess.get())
    )

    @Test
    fun resetRemovesPriorProcessViewingKeySharesWithEitherRetentionChoice() = runBlocking {
        val fixture = Fixture()
        val shareDirectory = fixture.shareCacheDir()
        val unrelated = shareDirectory.resolve("unrelated.png").apply { writeText("keep") }
        val otherCacheFile = fixture.cache.resolve("unrelated.txt").apply { writeText("keep") }

        for (keepFiles in listOf(true, false)) {
            val orphan = shareDirectory.resolve("${VK_QR_FILENAME_PREFIX}synthetic.png").apply {
                writeText("synthetic export from an interrupted process")
            }

            fixture.newReset()(keepFiles)

            assertNull(fixture.progressStore().getStage())
            assertFalse(orphan.exists())
            assertEquals("keep", unrelated.readText())
            assertEquals("keep", otherCacheFile.readText())
        }
        coVerify(exactly = 1) { fixture.addressBookRepository.delete() }
        coVerify(exactly = 1) { fixture.metadataRepository.delete() }
    }

    @Test
    fun shareCacheDeletionFailureKeepsResetPendingAndRetryPreservesRetentionChoice() = runBlocking {
        val fixture = Fixture()
        val blocked = fixture.shareCacheDir().resolve("${VK_QR_FILENAME_PREFIX}blocked.png").apply { mkdir() }
        val blocker = blocked.resolve("child").apply { writeText("synthetic deletion blocker") }

        assertFailsWith<IOException> { fixture.reset(keepFiles = true) }
        assertEquals(ResetStage.ERASE_SDK, fixture.progressStore().getStage())
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.standardPrefs.clearPreferences() }

        assertTrue(blocker.delete())
        fixture.newReset()(keepFiles = false)

        assertFalse(blocked.exists())
        assertNull(fixture.progressStore().getStage())
        coVerify(exactly = 0) { fixture.addressBookRepository.delete() }
        coVerify(exactly = 0) { fixture.metadataRepository.delete() }
    }

    private fun addressBookSource(storage: AddressBookStorageProviderImpl): AddressBookDataSourceImpl {
        val sync = DirectorySync {}
        val serializer = AddressBookSerializer()
        val provider = AddressBookProviderImpl(AddressBookEncryptorImpl(serializer), serializer, sync)
        return AddressBookDataSourceImpl(storage, provider, sync)
    }

    private inner class Fixture(synchronizerAvailable: Boolean = true) {
        val walletCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }
        private val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } coAnswers {
                if (synchronizerAvailable) mockk<CloseableSynchronizer>(relaxed = true) else awaitCancellation()
            }
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
        private val files = directory.newFolder("files")
        val cache = directory.newFolder("cache")
        private val context = mockk<Context> {
            every { noBackupFilesDir } returns directory.root
            every { filesDir } returns files
            every { cacheDir } returns cache
        }

        private val writeAccess = WalletWriteAccess(progressStore())

        fun progressStore() = ResetProgressStore(context, DirectorySync {})

        fun addressBookStorage() = AddressBookStorageProviderImpl(context, DirectorySync {})

        suspend fun shareCacheDir() = context.getInternalCacheDirSuspend(CACHE_SUBDIR)

        fun contactRepository(
            source: AddressBookDataSourceImpl,
            accounts: AccountDataSource = mockk {
                every { zashiAccount } returns flowOf(null)
            },
            keys: AddressBookKeyStorageProvider = mockk(),
        ) = AddressBookRepositoryImpl(source, keys, accounts, mockk(), mockk(), writeAccess)

        val reset = newReset()

        fun newReset(repository: AddressBookRepository = addressBookRepository) = ResetZashiUseCase(
            walletCoordinator = walletCoordinator,
            synchronizerProvider = synchronizerProvider,
            standardPreferenceProvider = standardProvider,
            encryptedPreferenceProvider = encryptedProvider,
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            addressBookRepository = repository,
            metadataRepository = metadataRepository,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = mockk(relaxed = true),
            progressStore = progressStore(),
            deleteSharedQRImages = DeleteSharedQRImagesUseCase(context),
            walletWriteAccess = writeAccess,
        )
    }
}
