package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_FILE_IDENTIFIER_SIZE
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

interface AddressBookStorageProvider {
    fun getStorageFile(addressBookKey: AddressBookKey): File?

    fun getLegacyUnencryptedStorageTarget(): File

    fun getStorageTarget(addressBookKey: AddressBookKey): File

    fun deleteStorageFiles()
}

class AddressBookStorageProviderImpl(
    private val context: Context,
    private val directorySync: DirectorySync = OsDirectorySync(),
) : AddressBookStorageProvider {
    private var parentSynced = false
    override fun getStorageFile(addressBookKey: AddressBookKey): File? =
        File(getOrCreateAddressBookDir(), addressBookKey.fileIdentifier())
            .takeUnless { Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    override fun getLegacyUnencryptedStorageTarget(): File =
        File(context.noBackupFilesDir, LEGACY_UNENCRYPTED_ADDRESS_BOOK_FILE_NAME)

    override fun getStorageTarget(addressBookKey: AddressBookKey): File {
        val directory = getOrCreateAddressBookDir()
        return File(directory, addressBookKey.fileIdentifier())
    }

    private fun getOrCreateAddressBookDir(): File =
        File(context.filesDir, "address_book").also {
            if (!it.exists()) {
                Files.createDirectory(it.toPath())
            }
            if (!parentSynced) {
                directorySync.sync(context.filesDir)
                parentSynced = true
            }
        }

    override fun deleteStorageFiles() {
        val directory = getOrCreateAddressBookDir()
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Contact directory is unavailable" }
        val ownedName = Regex("zashi-address-book-[0-9a-f]{${ADDRESS_BOOK_FILE_IDENTIFIER_SIZE * 2}}(?:\\.tmp)?")
        val targets = directory.listFiles { file -> ownedName.matches(file.name) }
            ?: throw IOException("Could not list contact files")
        targets.forEach { target ->
            check(!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Contact file is a directory" }
            Files.deleteIfExists(target.toPath())
        }
        directorySync.sync(directory)
        val legacy = getLegacyUnencryptedStorageTarget()
        Files.deleteIfExists(legacy.toPath())
        directorySync.sync(checkNotNull(legacy.parentFile))
    }
}

private const val LEGACY_UNENCRYPTED_ADDRESS_BOOK_FILE_NAME = "address_book"
