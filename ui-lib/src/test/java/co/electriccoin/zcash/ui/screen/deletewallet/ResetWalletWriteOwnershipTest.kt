package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.AddressBookDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSourceImpl
import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.AddressBookKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.AddressBookProviderImpl
import co.electriccoin.zcash.ui.common.provider.AddressBookStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.MetadataProviderImpl
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.AddressBookRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.GiftCardRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepositoryImpl
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_ENCRYPTION_KEY_SIZE
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataSerializer
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.RecoverFromSeedMismatchUseCase
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetWalletWriteOwnershipTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun walletEditsCannotRecreateDeletedFilesDuringOrAfterReset() = runBlocking {
        val fixture = Fixture()
        val key = fixture.metadataKey
        val storage = fixture.metadataStorage
        val account = EditAccountFixtures.zodl()
        val owner = account.sdkAccount.accountUuid
        val metadata = fixture.metadataRepository(account)
        val contacts = fixture.contactRepository()
        val contactFile = fixture.contactStorage.getStorageTarget(fixture.contactKey)
        contacts.saveContact("Before reset", "original-recipient", null)
        assertTrue(metadata.createOrUpdateTxNote("original-tx", "before reset", owner, 0))
        assertTrue(contactFile.isFile)
        assertEquals(1, storage.getStorageFiles(key).size)
        val sdkEraseEntered = CompletableDeferred<Unit>()
        val finishSdkErase = CompletableDeferred<Unit>()
        every { fixture.resetCoordinator.deleteSdkDataFlow() } returns flow {
            sdkEraseEntered.complete(Unit)
            finishSdkErase.await()
            emit(true)
        }
        val reset = fixture.reset(metadata, contacts)
        val operation = launch { reset(keepFiles = false) }
        try {
            withTimeout(10.seconds) { sdkEraseEntered.await() }
            assertEquals(ResetStage.ERASE_SDK, fixture.progress.getStage())
            assertTrue(storage.getStorageFiles(key).isEmpty())
            assertFalse(contactFile.exists())
            assertFalse(metadata.createOrUpdateTxNote("late-tx", "during reset", owner, 0))
            assertFailsWith<IllegalStateException> { contacts.saveContact("During reset", "late-recipient", null) }
        } finally {
            finishSdkErase.complete(Unit)
        }
        operation.join()

        assertNull(fixture.progress.getStage())
        assertFalse(metadata.createOrUpdateTxNote("late-tx", "after reset", owner, 0))
        assertFailsWith<IllegalStateException> { contacts.saveContact("After reset", "late-recipient", null) }
        assertTrue(storage.getStorageFiles(key).isEmpty())
        assertFalse(contactFile.exists())
    }

    @Test
    fun aSurvivingMetadataObserverRereadsDiskAfterSameAccountSetup() = runBlocking {
        val fixture = Fixture()
        val original = originalWallet()
        fixture.walletProvider.store(original)
        val account = EditAccountFixtures.zodl()
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(account))
        val metadata = fixture.metadataRepository(account, accounts)
        val uuid = account.sdkAccount.accountUuid
        val collector = launch { metadata.metadata.collect {} }
        try {
            assertTrue(metadata.createOrUpdateTxNote("tx", "before reset", uuid, fixture.walletProvider.writeVersion))
            val saved = withTimeout(10.seconds) {
                metadata.metadata.first { it[uuid]?.accountMetadata?.annotations?.isNotEmpty() == true }.getValue(uuid)
            }

            fixture.reset(metadata)(keepFiles = false)
            accounts.value = null
            withTimeout(10.seconds) { metadata.metadata.first { it.isEmpty() } }

            val recovered = saved.copy(
                accountMetadata = saved.accountMetadata.copy(
                    annotations = saved.accountMetadata.annotations.map { it.copy(content = "after restore") },
                ),
            )
            val key = fixture.metadataKey
            val file = fixture.metadataStorage.getOrCreateStorageFile(key)
            fixture.metadataProvider.writeMetadataToFile(file, recovered, key)
            fixture.walletProvider.store(original)
            accounts.value = listOf(account)

            val refreshed = withTimeout(10.seconds) { metadata.metadata.first { uuid in it }.getValue(uuid) }
            assertEquals("after restore", refreshed.accountMetadata.annotations.single().content)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun resetDrainsAdmittedSeedRecoveryBeforeErasingAndAllowsAnExplicitRestore() = runBlocking {
        val fixture = Fixture()
        val original = originalWallet()
        fixture.walletProvider.store(original)
        val erase = PausedRecovery(fixture.walletProvider, fixture.writeAccess)
        val recovery = launch { erase.recover(fixture.writeAccess.token()) }
        val replacement = original.copy(endpoint = LightWalletEndpoint("restored.example.invalid", 443, true))
        val doReset = fixture.reset()
        try {
            withTimeout(10.seconds) { erase.started.await() }
            val reset = launch(start = CoroutineStart.UNDISPATCHED) { doReset(keepFiles = true) }
            withTimeout(10.seconds) { fixture.progress.isPending.first { it } }
            assertFalse(reset.isCompleted)
            verify(exactly = 0) { fixture.resetCoordinator.deleteSdkDataFlow() }
            erase.finish.complete(Unit)
            withTimeout(10.seconds) { reset.join() }
        } finally {
            erase.finish.complete(Unit)
        }
        recovery.join()

        assertNull(fixture.progress.getStage())
        assertNull(fixture.walletProvider.getPersistableWallet())
        fixture.walletProvider.store(replacement)
        assertEquals(replacement.endpoint, fixture.walletProvider.requirePersistableWallet().endpoint)
    }

    @Test
    fun seedRecoveryAwaitingItsWalletSnapshotCannotEraseAfterResetAndRestore() = runBlocking {
        val fixture = Fixture()
        fixture.walletProvider.store(originalWallet())
        val beforeReset = fixture.writeAccess.token()
        val captured = CompletableDeferred<Unit>()
        val returnSnapshot = CompletableDeferred<Unit>()
        val stagedRead = object : PersistableWalletProvider by fixture.walletProvider {
            override suspend fun getPersistableWallet(): PersistableWallet {
                val original = fixture.walletProvider.requirePersistableWallet()
                captured.complete(Unit)
                returnSnapshot.await()
                return original
            }
        }
        val coordinator = mockk<WalletCoordinator>()
        val recover = RecoverFromSeedMismatchUseCase(stagedRead, coordinator, fixture.writeAccess)
        val recovery = launch { recover(beforeReset) }
        val replacement = originalWallet().copy(endpoint = LightWalletEndpoint("new.example.invalid", 443, true))
        try {
            withTimeout(10.seconds) { captured.await() }
            fixture.reset()(keepFiles = true)
            fixture.walletProvider.store(replacement)
        } finally {
            returnSnapshot.complete(Unit)
        }
        recovery.join()
        verify(exactly = 0) { coordinator.deleteSdkDataFlow() }
        assertEquals(replacement, fixture.walletProvider.requirePersistableWallet())
    }

    @Test
    fun recoveryCannotOverwriteAnEndpointChangedDuringTheSameWalletGeneration() = runBlocking {
        val fixture = Fixture()
        val provider = fixture.walletProvider
        provider.store(originalWallet())
        val original = provider.requirePersistableWallet()
        val version = provider.writeVersion
        val replacement = original.copy(endpoint = LightWalletEndpoint("updated.example.invalid", 443, true))
        val erase = PausedRecovery(provider, fixture.writeAccess)
        val recovery = launch { erase.recover(version) }
        try {
            withTimeout(10.seconds) { erase.started.await() }
            assertTrue(provider.storeIfCurrent(original, replacement, version))
            assertEquals(version, provider.writeVersion)
            assertEquals(replacement.endpoint, provider.requirePersistableWallet().endpoint)
        } finally {
            erase.finish.complete(Unit)
        }
        recovery.join()

        assertEquals(replacement.endpoint, provider.requirePersistableWallet().endpoint)
    }

    @Test
    fun resetWaitsForAnAdmittedRecoveryWriteThenClearsIt() = runBlocking {
        val fixture = Fixture()
        fixture.walletProvider.store(originalWallet())
        val recoveryWriteEntered = CompletableDeferred<Unit>()
        val finishRecoveryWrite = CompletableDeferred<Unit>()
        fixture.beforePreferenceWrite = {
            recoveryWriteEntered.complete(Unit)
            finishRecoveryWrite.await()
        }
        val recoveryCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }
        val recover = RecoverFromSeedMismatchUseCase(fixture.walletProvider, recoveryCoordinator, fixture.writeAccess)
        val recovery = launch { recover(fixture.writeAccess.token()) }
        val reset = fixture.reset()
        try {
            withTimeout(10.seconds) { recoveryWriteEntered.await() }
            launch { reset(keepFiles = false) }
            withTimeout(10.seconds) { fixture.progress.isPending.first { it } }
            assertEquals(ResetStage.DELETE_LOCAL_FILES, fixture.progress.getStage())
            verify(exactly = 0) { fixture.resetCoordinator.deleteSdkDataFlow() }
        } finally {
            finishRecoveryWrite.complete(Unit)
        }
        recovery.join()
        withTimeout(10.seconds) { fixture.progress.isPending.first { !it } }

        assertNull(fixture.walletProvider.getPersistableWallet())
    }

    @Test
    fun aContactEditAwaitingAnAccountDoesNotBlockReset() = runBlocking {
        val fixture = Fixture()
        fixture.walletProvider.store(originalWallet())
        val lookupStarted = CompletableDeferred<Unit>()
        val resetCompleted = CompletableDeferred<Unit>()
        val contacts = fixture.contactRepository {
            lookupStarted.complete(Unit)
            awaitCancellation()
        }
        val writer = launch { contacts.saveContact("Pending edit", "synthetic-recipient", null) }
        try {
            withTimeout(10.seconds) { lookupStarted.await() }
            launch {
                fixture.reset(contacts = contacts)(keepFiles = false)
                resetCompleted.complete(Unit)
            }
            withTimeout(10.seconds) { resetCompleted.await() }
            assertTrue(writer.isActive)
            assertNull(fixture.progress.getStage())
            assertNull(fixture.walletProvider.getPersistableWallet())
            assertFalse(fixture.contactStorage.getStorageTarget(fixture.contactKey).exists())
        } finally {
            writer.cancelAndJoin()
        }
    }

    @Test
    fun failedPreparationKeepsResetPendingUntilRetryAndExplicitWalletSetupReopensWrites() = runBlocking {
        val fixture = Fixture()
        val original = originalWallet()
        fixture.walletProvider.store(original)
        val oldVersion = fixture.walletProvider.writeVersion
        coEvery { fixture.giftRepository.quiesceForWalletReset() } throws IOException("Synthetic preparation failure")

        assertFailsWith<IOException> { fixture.reset()(keepFiles = false) }
        assertEquals(ResetStage.DELETE_LOCAL_FILES, fixture.progress.getStage())
        verify(exactly = 0) { fixture.resetCoordinator.deleteSdkDataFlow() }
        assertFailsWith<IllegalStateException> { fixture.walletProvider.store(original) }
        assertFalse(fixture.walletProvider.storeIfCurrent(original, original, oldVersion))

        coEvery { fixture.giftRepository.quiesceForWalletReset() } returns Unit
        fixture.reset()(keepFiles = true)

        assertNull(fixture.progress.getStage())
        assertNull(fixture.walletProvider.getPersistableWallet())
        assertNull(fixture.writeAccess.write(fixture.walletProvider.writeVersion) { true })
        fixture.walletProvider.store(original)
        assertTrue(fixture.walletProvider.storeIfCurrent(original, original, fixture.walletProvider.writeVersion))
        assertFalse(fixture.walletProvider.storeIfCurrent(original, original, oldVersion))
    }

    @Test
    fun contactObserverFromAnEarlierWalletCannotMigrateLegacyFileAfterRestore() = runBlocking {
        val fixture = Fixture()
        val first = originalWallet()
        fixture.walletProvider.store(first)
        val oldObserver = fixture.contactSource.observe(fixture.contactKey)
        fixture.reset(contacts = fixture.contactRepository())(keepFiles = false)
        fixture.walletProvider.store(first)

        val timestamp = Instant.fromEpochMilliseconds(123)
        val book = AddressBook(
            lastUpdated = timestamp,
            contacts = listOf(AddressBookContact("Legacy contact", "legacy-recipient", timestamp, null)),
        )
        val legacy = fixture.contactStorage.getLegacyUnencryptedStorageTarget()
        legacy.outputStream().use { fixture.contactSerializer.serializeAddressBook(it, book) }
        assertNull(withTimeoutOrNull(1.seconds) { oldObserver.filterNotNull().first() })
        assertTrue(legacy.isFile)
        assertFalse(fixture.contactStorage.getStorageTarget(fixture.contactKey).exists())

        val restored = withTimeout(10.seconds) {
            fixture.contactSource.observe(fixture.contactKey).filterNotNull().first()
        }
        assertEquals(book, restored)
        assertFalse(legacy.exists())
    }

    private fun originalWallet() = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("original.example.invalid", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet,
    )

    private class PausedRecovery(provider: PersistableWalletProvider, access: WalletWriteAccess) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val recover = RecoverFromSeedMismatchUseCase(provider, mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flow {
                started.complete(Unit)
                finish.await()
                emit(true)
            }
        }, access)
    }

    private inner class Fixture {
        private val files = directory.newFolder("files")
        val context = mockk<Context> {
            every { filesDir } returns files
            every { noBackupFilesDir } returns directory.root
        }
        val sync = DirectorySync {}
        val progress = ResetProgressStore(context, sync)
        val writeAccess = WalletWriteAccess(progress)
        private val androidPreferences = AndroidPreferenceProvider(
            RuntimeEnvironment.getApplication().getSharedPreferences("reset-ownership", Context.MODE_PRIVATE),
            Dispatchers.Unconfined,
        )
        var beforePreferenceWrite: suspend () -> Unit = {}
        private val preferences = object : PreferenceProvider by androidPreferences {
            override suspend fun putString(key: PreferenceKey, value: String?) {
                beforePreferenceWrite()
                androidPreferences.putString(key, value)
            }

            override suspend fun putStringWithRollback(key: PreferenceKey, value: String?) {
                beforePreferenceWrite()
                androidPreferences.putStringWithRollback(key, value)
            }
        }
        private val encryptedProvider = mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns preferences
            coEvery { purgeQuarantine() } returns true
        }
        val walletProvider = PersistableWalletProviderImpl(encryptedProvider, writeAccess)
        val metadataKey = MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32) { 1 }, InsecureSecretKeyAccess.get())))
        val metadataStorage = MetadataStorageProviderImpl(context, sync)
        val metadataProvider = MetadataProviderImpl(MetadataEncryptorImpl(MetadataSerializer()), sync)
        val contactStorage = AddressBookStorageProviderImpl(context, sync)
        val contactKey = AddressBookKey(
            SecretBytes.copyFrom(ByteArray(ADDRESS_BOOK_ENCRYPTION_KEY_SIZE) { 1 }, InsecureSecretKeyAccess.get())
        )
        val contactSerializer = AddressBookSerializer()
        val contactSource = AddressBookDataSourceImpl(
            contactStorage,
            AddressBookProviderImpl(AddressBookEncryptorImpl(contactSerializer), contactSerializer, sync),
            sync,
            writeAccess,
        )
        val giftRepository = mockk<GiftCardRepository>(relaxed = true)
        val resetCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }

        fun metadataRepository(
            account: WalletAccount,
            accounts: MutableStateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account)),
        ) = MetadataRepositoryImpl(
            accountDataSource = mockk<AccountDataSource> {
                every { selectedAccount } returns MutableStateFlow<WalletAccount?>(account)
                every { allAccounts } returns accounts
                coEvery { getSelectedAccount() } returns account
            },
            metadataDataSource = MetadataDataSourceImpl(
                metadataStorage, metadataProvider, mockk(), walletWriteAccess = writeAccess,
            ),
            metadataKeyStorageProvider = mockk<MetadataKeyStorageProvider> {
                coEvery { get(account.sdkAccount.accountUuid) } returns metadataKey
            },
            persistableWalletProvider = walletProvider,
            simpleSwapAssetProvider = mockk(),
            walletWriteAccess = writeAccess,
        )

        fun contactRepository(lookup: (suspend () -> ZodlAccount)? = null): AddressBookRepository {
            val account = EditAccountFixtures.zodl()
            return AddressBookRepositoryImpl(
                addressBookDataSource = contactSource,
                addressBookKeyStorageProvider = mockk<AddressBookKeyStorageProvider> {
                    coEvery { getAddressBookKey() } returns contactKey
                },
                accountDataSource = mockk<AccountDataSource> {
                    every { zashiAccount } returns flowOf(account)
                    coEvery { getZashiAccount() } coAnswers { lookup?.invoke() ?: account }
                },
                persistableWalletProvider = walletProvider,
                blockchainProvider = mockk(),
                walletWriteAccess = writeAccess,
            )
        }

        fun reset(
            metadata: MetadataRepository = mockk(relaxed = true),
            contacts: AddressBookRepository = mockk(relaxed = true),
        ) = ResetZashiUseCase(
            resetWalletSdkData = sdkDataResetFixture(directory.root, resetCoordinator),
            synchronizerProvider = mockk {
                coEvery { getSynchronizer() } returns mockk<CloseableSynchronizer>(relaxed = true)
            },
            standardPreferenceProvider = mockk<StandardPreferenceProvider> {
                val preferences = mockk<PreferenceProvider> { coEvery { clearPreferences() } returns true }
                coEvery { this@mockk.invoke() } returns preferences
            },
            encryptedPreferenceProvider = encryptedProvider,
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            addressBookRepository = contacts,
            metadataRepository = metadata,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = giftRepository,
            progressStore = progress,
            deleteSharedQRImages = mockk(relaxed = true),
            deleteLegacySharedExports = mockk(relaxed = true),
            walletWriteAccess = writeAccess,
        )
    }
}
