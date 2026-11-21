package co.electriccoin.zcash.app

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.provider.AddressBookProviderImpl
import co.electriccoin.zcash.ui.common.provider.OsDirectorySync
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptor
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.time.Instant

class ProjectReviewStorageTest {
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
