package co.electriccoin.zcash.ui.common.datasource

import android.content.Context
import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.provider.AddressBookProviderImpl
import co.electriccoin.zcash.ui.common.provider.AddressBookStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_ENCRYPTION_KEY_SIZE
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_V1
import co.electriccoin.zcash.ui.common.serialization.BaseSerializer
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptor
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptorImpl
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

class AddressBookDurabilityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun failureAfterWritingTemporaryBytesKeepsThePreviousBookAndIsRetryable() = runTest {
        val fixture = Fixture()
        fixture.provider.writeAddressBookToFile(fixture.file, oldBook, fixture.key)
        val before = fixture.file.readBytes()
        val encryptor = mockk<AddressBookEncryptor> {
            every { decrypt(fixture.key, any()) } answers { fixture.encryptor.decrypt(fixture.key, secondArg()) }
            every { encrypt(fixture.key, any(), any()) } answers {
                secondArg<OutputStream>().write("partial ciphertext".encodeToByteArray())
                throw IOException("Synthetic write failure")
            }
        }
        val failingSource = fixture.dataSource(encryptor)

        assertFailsWith<IOException> { failingSource.saveContact("New", "recipient", null, fixture.key) }
        assertContentEquals(before, fixture.file.readBytes())
        assertEquals(oldBook, failingSource.observe(fixture.key).filterNotNull().first())
        assertEquals(listOf(fixture.file.name), fixture.file.parentFile?.list()?.toList())

        val source = fixture.dataSource()
        source.saveContact("New", "recipient", null, fixture.key)
        val saved = source.observe(fixture.key).filterNotNull().first()
        assertEquals(listOf("First", "Second", "New"), saved.contacts.map { it.name })
    }

    @Test
    fun unreadableEncryptedBookIsKeptEvenWhenALegacyBookIsPresent() = runTest {
        val fixture = Fixture()
        val unreadable = byteArrayOf(1, 2, 3)
        fixture.file.writeBytes(unreadable)
        val legacyBytes = fixture.writeLegacy()

        assertFails { fixture.dataSource().saveContact("New", "recipient", null, fixture.key) }

        assertContentEquals(unreadable, fixture.file.readBytes())
        assertContentEquals(legacyBytes, fixture.legacy.readBytes())
    }

    @Test
    fun failedMigrationKeepsLegacyBytesAndRetryMigratesEveryContact() = runTest {
        val fixture = Fixture()
        val legacyBytes = fixture.writeLegacy()
        val encryptor = mockk<AddressBookEncryptor> {
            every { encrypt(any(), any(), any()) } throws IOException("Synthetic migration failure")
        }

        assertFailsWith<IOException> { fixture.dataSource(encryptor).observe(fixture.key).filterNotNull().first() }
        assertFalse(fixture.file.exists())
        assertContentEquals(legacyBytes, fixture.legacy.readBytes())

        val migrated = fixture.dataSource().observe(fixture.key).filterNotNull().first()
        assertEquals(oldBook.contacts, migrated.contacts)
        assertEquals(oldBook.contacts, fixture.provider.readAddressBookFromFile(fixture.file, fixture.key).contacts)
        assertFalse(fixture.legacy.exists())
    }

    @Test
    fun migrationRetiresLegacyPlaintextAfterPostRenameSyncFailure() = runTest {
        val fixture = Fixture()
        val legacyBytes = fixture.writeLegacy()
        val failingSync = DirectorySync { throw IOException("Synthetic directory sync failure after rename") }

        assertFailsWith<IOException> {
            fixture.dataSource(sync = failingSync).observe(fixture.key).filterNotNull().first()
        }
        assertEquals(oldBook, fixture.provider.readAddressBookFromFile(fixture.file, fixture.key))
        assertContentEquals(legacyBytes, fixture.legacy.readBytes())

        assertFailsWith<IOException> {
            fixture.dataSource(sync = failingSync).saveContact("New", "recipient", null, fixture.key)
        }
        assertEquals(oldBook, fixture.provider.readAddressBookFromFile(fixture.file, fixture.key))
        assertContentEquals(legacyBytes, fixture.legacy.readBytes())

        val orphan = fixture.file.resolveSibling(fixture.file.name + ".tmp").also { it.writeText("partial write") }
        val retry = fixture.dataSource()
        assertEquals(oldBook, retry.observe(fixture.key).filterNotNull().first())
        assertFalse(fixture.legacy.exists())
        retry.saveContact("New", "recipient", null, fixture.key)
        assertFalse(orphan.exists())
        val saved = retry.observe(fixture.key).filterNotNull().first()
        assertEquals(listOf("First", "Second", "New"), saved.contacts.map { it.name })
    }

    @Test
    fun failedLegacyDirectoryFlushIsRetriedAfterUnlink() = runTest {
        val fixture = Fixture()
        fixture.writeLegacy()
        val failingSync = DirectorySync { directory ->
            if (directory == fixture.legacy.parentFile) throw IOException("Synthetic legacy directory flush failure")
        }
        assertFailsWith<IOException> {
            fixture.dataSource(sync = failingSync).observe(fixture.key).filterNotNull().first()
        }
        assertEquals(oldBook, fixture.provider.readAddressBookFromFile(fixture.file, fixture.key))
        assertFalse(fixture.legacy.exists())
        assertFailsWith<IOException> {
            fixture.dataSource(sync = failingSync).saveContact("New", "recipient", null, fixture.key)
        }

        val synced = mutableListOf<File>()
        fixture.dataSource(sync = DirectorySync { synced += it }).saveContact("New", "recipient", null, fixture.key)
        assertEquals(listOf(fixture.legacy.parentFile, fixture.file.parentFile), synced)
    }

    @Test
    fun unrelatedLegacyBookIsPreservedAlongsideAuthenticatedEncryptedBook() = runTest {
        val fixture = Fixture()
        val bytes = fixture.writeLegacy()
        val differentBook = oldBook.copy(lastUpdated = Instant.fromEpochMilliseconds(124))
        fixture.provider.writeAddressBookToFile(fixture.file, differentBook, fixture.key)

        val source = fixture.dataSource()
        assertEquals(differentBook, source.observe(fixture.key).filterNotNull().first())
        source.saveContact("New", "recipient", null, fixture.key)
        assertContentEquals(bytes, fixture.legacy.readBytes())
    }

    @Test
    fun invalidLegacySuffixCannotAuthorizeUnlink() = runTest {
        val fixture = Fixture()
        val bytes = fixture.writeLegacy() + byteArrayOf(1)
        fixture.legacy.writeBytes(bytes)
        fixture.provider.writeAddressBookToFile(fixture.file, oldBook, fixture.key)

        assertEquals(oldBook, fixture.dataSource().observe(fixture.key).filterNotNull().first())
        assertContentEquals(bytes, fixture.legacy.readBytes())
    }

    @Test
    fun retryAfterDirectorySyncFailureDoesNotDuplicateAContact() = runTest {
        val fixture = Fixture()
        val failingSync = DirectorySync { throw IOException("Synthetic directory flush failure") }

        assertFailsWith<IOException> {
            fixture.dataSource(sync = failingSync).saveContact("New", "recipient", null, fixture.key)
        }

        val retry = fixture.dataSource()
        retry.saveContact("New", "recipient", null, fixture.key)

        assertEquals(listOf("New"), retry.observe(fixture.key).filterNotNull().first().contacts.map { it.name })
    }

    private inner class Fixture {
        val key = AddressBookKey(
            SecretBytes.copyFrom(ByteArray(ADDRESS_BOOK_ENCRYPTION_KEY_SIZE), InsecureSecretKeyAccess.get())
        )
        private val context = mockk<Context> {
            every { filesDir } returns directory.newFolder("files")
            every { noBackupFilesDir } returns directory.newFolder("no-backup")
        }
        private val storage = AddressBookStorageProviderImpl(context)
        private val serializer = AddressBookSerializer()
        val encryptor = AddressBookEncryptorImpl(serializer)
        private val sync = DirectorySync {}
        val file = storage.getStorageTarget(key)
        val legacy = File(context.noBackupFilesDir, "address_book")
        val provider = AddressBookProviderImpl(encryptor, serializer, sync)

        fun dataSource(encryptor: AddressBookEncryptor = this.encryptor, sync: DirectorySync = this.sync) =
            AddressBookDataSourceImpl(storage, AddressBookProviderImpl(encryptor, serializer, sync), sync)

        fun writeLegacy(): ByteArray =
            LegacyWriter().encode(oldBook).also { legacy.writeBytes(it) }
    }

    private class LegacyWriter : BaseSerializer() {
        fun encode(book: AddressBook): ByteArray =
            ADDRESS_BOOK_SERIALIZATION_V1.createByteArray() +
                book.lastUpdated.toEpochMilliseconds().createByteArray() +
                book.contacts.size.createByteArray() +
                book.contacts.fold(byteArrayOf()) { bytes, contact ->
                    bytes + contact.lastUpdated.toEpochMilliseconds().createByteArray() +
                        contact.address.createByteArray() + contact.name.createByteArray()
                }
    }

    companion object {
        private val timestamp = Instant.fromEpochMilliseconds(123)
        private val oldBook =
            AddressBook(
                lastUpdated = timestamp,
                contacts = listOf(
                    AddressBookContact("First", "first", timestamp, null),
                    AddressBookContact("Second", "second", timestamp, null)
                )
            )
    }
}
