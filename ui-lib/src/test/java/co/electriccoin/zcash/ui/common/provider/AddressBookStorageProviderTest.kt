package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressBookStorageProviderTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun interruptedDirectoryCreationRequiresParentSyncOnRetry() {
        var attempts = 0
        val storage = storage(DirectorySync {
            attempts += 1
            if (attempts == 1) throw IOException("Synthetic parent sync failure")
        })
        assertFailsWith<IOException> { storage.getStorageTarget(key(1)) }
        val recoveredTarget = storage.getStorageTarget(key(1))
        assertTrue(checkNotNull(recoveredTarget.parentFile).isDirectory)
        assertEquals(2, attempts)
    }

    @Test
    fun repeatedCleanupRemovesEarlierWalletFilesAndPreservesUnrelatedPaths() {
        val storage = storage(DirectorySync {})
        val old = storage.getStorageTarget(key(1)).apply { writeText("old encrypted fixture") }
        val current = storage.getStorageTarget(key(2)).apply { writeText("current encrypted fixture") }
        val partial = File(old.parentFile, old.name + ".tmp").apply { writeText("partial fixture") }
        val unrelated = File(old.parentFile, "unrelated").apply { writeText("keep") }
        val invalidName = File(old.parentFile, "zashi-address-book-note.tmp").apply { writeText("keep") }
        val nested = File(old.parentFile, "archive").apply { mkdir() }
        val nestedFile = File(nested, old.name).apply { writeText("keep") }
        val legacy = storage.getLegacyUnencryptedStorageTarget().apply { writeText("legacy fixture") }

        repeat(2) { storage.deleteStorageFiles() }

        listOf(old, current, partial, legacy).forEach { assertFalse(it.exists()) }
        listOf(unrelated, invalidName, nestedFile).forEach { assertEquals("keep", it.readText()) }
    }

    @Test
    fun ownedNameDirectoryFailsCleanupWithoutTraversingItsChildren() {
        val storage = storage(DirectorySync {})
        val ownedDirectory = storage.getStorageTarget(key(1)).apply { mkdir() }
        val child = File(ownedDirectory, "keep").apply { writeText("keep") }

        assertFailsWith<IllegalStateException> { storage.deleteStorageFiles() }
        assertTrue(ownedDirectory.isDirectory)
        assertEquals("keep", child.readText())
    }

    private fun storage(sync: DirectorySync): AddressBookStorageProviderImpl {
        val files = File(directory.root, "files").apply { mkdir() }
        val noBackup = File(directory.root, "no-backup").apply { mkdir() }
        val context = mockk<Context> {
            every { filesDir } returns files
            every { noBackupFilesDir } returns noBackup
        }
        return AddressBookStorageProviderImpl(context, sync)
    }

    private fun key(value: Byte) =
        AddressBookKey(SecretBytes.copyFrom(ByteArray(32) { value }, InsecureSecretKeyAccess.get()))
}
