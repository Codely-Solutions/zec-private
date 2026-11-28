package co.electriccoin.zcash.app

import android.content.pm.ApplicationInfo
import android.content.ContextWrapper
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.provider.AddressBookProviderImpl
import co.electriccoin.zcash.ui.common.provider.OsDirectorySync
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.MetadataProviderImpl
import co.electriccoin.zcash.ui.common.provider.MetadataStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.common.model.SimpleSwapAsset
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSourceImpl
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.model.metadata.AccountMetadataV3
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataSerializer
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptor
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.time.Instant

class ProjectReviewStorageTest {
    @Test
    fun absentWalletPreservesSdkStoresAcrossRecoveryReads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = ZcashNetwork.fromResources(context)
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(network == ZcashNetwork.Testnet)
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")

        val koin = GlobalContext.get()
        val wallet = koin.get<PersistableWalletProvider>()
        val prefs = koin.get<StandardPreferenceProvider>()()
        val repository = koin.get<WalletRepository>()
        val onboarding = StandardPreferenceKeys.ONBOARDING_STATE
        check(wallet.getPersistableWallet() == null)
        check(onboarding.getValue(prefs) == OnboardingState.NONE.toNumber())
        withTimeout(30_000) { repository.secretState.first { it == SecretState.NONE } }

        val root = File(context.noBackupFilesDir, "co.electricoin.zcash")
        val activeDb = File(root, "zcash_sdk_testnet_data.sqlite3")
        val activeWal = File(root, "${activeDb.name}-wal")
        val activeShm = File(root, "${activeDb.name}-shm")
        val legacyCache = context.getDatabasePath("ZcashSdk_testnet_Cache.db")
        val planPrefsName = "com.zodl.slipstream.submit_plan_${network.id}_zcash_sdk"
        val planFile = File(context.dataDir, "shared_prefs/$planPrefsName.xml")
        val staleFiles = listOf(activeDb, activeWal, activeShm, legacyCache, planFile)
        check(staleFiles.none(File::exists))

        check(root.isDirectory || root.mkdirs())
        check(legacyCache.parentFile!!.let { it.isDirectory || it.mkdirs() })
        for (database in listOf(activeDb, legacyCache)) {
            SQLiteDatabase.openOrCreateDatabase(database, null).use {
                it.execSQL("CREATE TABLE fixture (value TEXT)")
            }
        }
        activeWal.writeText("synthetic stale WAL")
        activeShm.writeText("synthetic stale shared memory")
        check(context.getSharedPreferences(planPrefsName, Context.MODE_PRIVATE).edit().putBoolean("fixture", true).commit())
        check(staleFiles.all(File::isFile))
        val original = staleFiles.associateWith(File::readBytes)
        try {
            repository.retryWalletRead()
            withTimeout(30_000) { repository.secretState.first { it == SecretState.UNAVAILABLE } }
            check(onboarding.getValue(prefs) == OnboardingState.NONE.toNumber())

            onboarding.putValue(prefs, OnboardingState.READY.toNumber())
            repository.init()
            check(wallet.getPersistableWallet() == null)
            check(onboarding.getValue(prefs) == OnboardingState.READY.toNumber())
            check(original.all { (file, contents) -> file.readBytes().contentEquals(contents) })
        } finally {
            check(context.deleteSharedPreferences(planPrefsName))
            check(staleFiles.filterNot { it == planFile }.all(File::delete))
            onboarding.putValue(prefs, OnboardingState.NONE.toNumber())
        }
    }

    @Test
    fun reloadingMetadataRequiresDirectoryDurability() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "metadata-reload-").toFile()
        try {
            val key = MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32), InsecureSecretKeyAccess.get())))
            val sync = OsDirectorySync()
            var fail = false
            val provider = MetadataProviderImpl(MetadataEncryptorImpl(MetadataSerializer()), DirectorySync {
                if (fail) throw IOException("Synthetic directory sync failure")
                sync.sync(it)
            })
            val source = MetadataDataSourceImpl(
                MetadataStorageProviderImpl(object : ContextWrapper(context) {
                    override fun getFilesDir() = directory
                }, sync),
                provider,
                object : SimpleSwapAssetProvider {
                    override fun get(tokenTicker: String, chainTicker: String): SimpleSwapAsset =
                        error("The synthetic note fixture has no swap assets")
                    override fun getCuratedSwapAssets(): List<SimpleSwapAsset> = emptyList()
                },
            )
            check(source.markTxMemoAsRead("synthetic-first", key))
            fail = true
            check(!source.markTxMemoAsRead("synthetic-second", key))
            check(runCatching { source.read(key) }.exceptionOrNull() is IOException)
            fail = false
            val recovered = checkNotNull(source.read(key))
            check(recovered.accountMetadata.read == listOf("synthetic-first", "synthetic-second"))
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun metadataCreationAndReplacementRequireDirectorySync() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "metadata-review-").toFile()
        try {
            val target = File(directory, "synthetic-metadata")
            val key = MetadataKey(listOf(SecretBytes.copyFrom(ByteArray(32), InsecureSecretKeyAccess.get())))
            val encryptor = MetadataEncryptorImpl(MetadataSerializer())
            val sync = OsDirectorySync()
            var fail = true
            var syncs = 0
            val provider = MetadataProviderImpl(encryptor, DirectorySync {
                syncs += 1
                if (fail) throw IOException("Synthetic directory sync failure")
                sync.sync(it)
            })
            val initial = MetadataV3(
                lastUpdated = java.time.Instant.ofEpochMilli(1),
                accountMetadata = AccountMetadataV3(bookmarked = emptyList(), read = emptyList(), annotations = emptyList())
            )
            val replacement = initial.copy(lastUpdated = java.time.Instant.ofEpochMilli(42))
            check(runCatching { provider.writeMetadataToFile(target, initial, key) }.exceptionOrNull() is IOException)
            check(provider.readMetadataFromFile(target, key) == initial)
            fail = false
            provider.writeMetadataToFile(target, initial, key)
            fail = true
            check(runCatching { provider.writeMetadataToFile(target, replacement, key) }.exceptionOrNull() is IOException)
            check(provider.readMetadataFromFile(target, key) == replacement)
            fail = false
            provider.writeMetadataToFile(target, replacement, key)
            check(syncs == 4)
            check(MetadataProviderImpl(encryptor, sync).readMetadataFromFile(target, key) == replacement)
            check(directory.list()?.toList() == listOf(target.name))
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun encryptedContactsSurviveFailedWriteThenReplaceAtomically() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet)
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")

        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "contact-review-").toFile()
        try {
            val target = File(directory, "synthetic-address-book")
            val key = AddressBookKey(SecretBytes.copyFrom(ByteArray(32), InsecureSecretKeyAccess.get()))
            val serializer = AddressBookSerializer()
            val encryptor = AddressBookEncryptorImpl(serializer)
            val sync = OsDirectorySync()
            val provider = AddressBookProviderImpl(encryptor, serializer, sync)
            val time = Instant.fromEpochMilliseconds(42)
            val first = AddressBook(lastUpdated = time, contacts = listOf(AddressBookContact("First", "test-1", time, null)))
            val next = first.copy(contacts = first.contacts + AddressBookContact("Second", "test-2", time, null))
            provider.writeAddressBookToFile(target, first, key)
            val originalCiphertext = target.readBytes()

            val failingEncryptor = object : AddressBookEncryptor by encryptor {
                override fun encrypt(key: AddressBookKey, outputStream: OutputStream, data: AddressBook) {
                    outputStream.write(byteArrayOf(1, 2, 3))
                    throw IOException("Synthetic write failure")
                }
            }
            val failed = runCatching {
                AddressBookProviderImpl(failingEncryptor, serializer, sync).writeAddressBookToFile(target, next, key)
            }.exceptionOrNull()
            check(failed is IOException)
            check(target.readBytes().contentEquals(originalCiphertext))
            check(provider.readAddressBookFromFile(target, key) == first)
            check(directory.list()?.toList() == listOf(target.name))

            provider.writeAddressBookToFile(target, next, key)
            check(provider.readAddressBookFromFile(target, key) == next)
            check(!target.readBytes().contentEquals(originalCiphertext))
            check(directory.list()?.toList() == listOf(target.name))
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
