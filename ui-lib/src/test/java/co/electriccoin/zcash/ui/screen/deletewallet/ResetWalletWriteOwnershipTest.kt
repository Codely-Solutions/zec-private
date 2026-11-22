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
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSourceImpl
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.MetadataProviderImpl
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepositoryImpl
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetWalletWriteOwnershipTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun metadataCannotRecreateDeletedFilesWhileResetIsErasingSdkOrAfterItCompletes() = runBlocking {
        val fixture = Fixture()
        val account = EditAccountFixtures.zodl()
        val key = MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32) { 1 }, InsecureSecretKeyAccess.get())))
        val storage = MetadataStorageProviderImpl(fixture.context, fixture.sync)
        val provider = MetadataProviderImpl(MetadataEncryptorImpl(MetadataSerializer()), fixture.sync)
        val metadata = MetadataRepositoryImpl(
            accountDataSource = mockk<AccountDataSource> {
                every { selectedAccount } returns MutableStateFlow<WalletAccount?>(account)
                every { allAccounts } returns MutableStateFlow<List<WalletAccount>?>(listOf(account))
                coEvery { getSelectedAccount() } returns account
            },
            metadataDataSource = MetadataDataSourceImpl(storage, provider, mockk()),
            metadataKeyStorageProvider = mockk<MetadataKeyStorageProvider> {
                coEvery { get(account.sdkAccount.accountUuid) } returns key
            },
            persistableWalletProvider = fixture.walletProvider,
            simpleSwapAssetProvider = mockk(),
            walletWriteAccess = fixture.writeAccess,
        )
        assertTrue(metadata.createOrUpdateTxNote("original-tx", "before reset"))
        assertEquals(1, storage.getStorageFiles(key).size)
        val sdkEraseEntered = CompletableDeferred<Unit>()
        val finishSdkErase = CompletableDeferred<Unit>()
        every { fixture.resetCoordinator.deleteSdkDataFlow() } returns flow {
            sdkEraseEntered.complete(Unit)
            finishSdkErase.await()
            emit(true)
        }
        val reset = fixture.reset(metadata)
        val operation = launch { reset(keepFiles = false) }
        try {
            withTimeout(10.seconds) { sdkEraseEntered.await() }
            assertEquals(ResetStage.ERASE_SDK, fixture.progress.getStage())
            assertTrue(storage.getStorageFiles(key).isEmpty())
            assertFalse(metadata.createOrUpdateTxNote("late-tx", "during reset"))
        } finally {
            finishSdkErase.complete(Unit)
        }
        operation.join()

        assertNull(fixture.progress.getStage())
        assertFalse(metadata.createOrUpdateTxNote("late-tx", "after reset"))
        assertTrue(storage.getStorageFiles(key).isEmpty())
    }

    @Test
    fun recoveryStartedBeforeResetCannotOverwriteAnExplicitRestoreOfTheSameWallet() = runBlocking {
        val fixture = Fixture()
        val original = PersistableWallet(
            network = ZcashNetwork.Testnet,
            birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
            endpoint = LightWalletEndpoint("original.example.invalid", 443, true),
            seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
            walletInitMode = WalletInitMode.ExistingWallet,
        )
        fixture.walletProvider.store(original)
        val recoveryEraseEntered = CompletableDeferred<Unit>()
        val finishRecoveryErase = CompletableDeferred<Unit>()
        val recoveryCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flow {
                recoveryEraseEntered.complete(Unit)
                finishRecoveryErase.await()
                emit(true)
            }
        }
        val recover = RecoverFromSeedMismatchUseCase(fixture.walletProvider, recoveryCoordinator)
        val recovery = launch { recover() }
        val replacement = original.copy(endpoint = LightWalletEndpoint("restored.example.invalid", 443, true))
        try {
            withTimeout(10.seconds) { recoveryEraseEntered.await() }
            fixture.reset()(keepFiles = true)
            assertNull(fixture.walletProvider.getPersistableWallet())
            fixture.walletProvider.store(replacement)
            assertEquals(replacement.endpoint, fixture.walletProvider.requirePersistableWallet().endpoint)
        } finally {
            finishRecoveryErase.complete(Unit)
        }
        recovery.join()

        assertNull(fixture.progress.getStage())
        assertEquals(replacement.endpoint, fixture.walletProvider.requirePersistableWallet().endpoint)
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
        private val preferences = AndroidPreferenceProvider(
            RuntimeEnvironment.getApplication().getSharedPreferences("reset-ownership", Context.MODE_PRIVATE),
            Dispatchers.Unconfined,
        )
        private val encryptedProvider = mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns preferences
            coEvery { purgeQuarantine() } returns true
        }
        val walletProvider = PersistableWalletProviderImpl(encryptedProvider, writeAccess)
        val resetCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }

        fun reset(metadata: MetadataRepository = mockk(relaxed = true)) = ResetZashiUseCase(
            walletCoordinator = resetCoordinator,
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
            addressBookRepository = mockk(relaxed = true),
            metadataRepository = metadata,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = mockk(relaxed = true),
            progressStore = progress,
            walletWriteAccess = writeAccess,
        )
    }
}
