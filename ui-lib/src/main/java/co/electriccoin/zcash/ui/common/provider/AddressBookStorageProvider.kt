package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

interface AddressBookStorageProvider {
    fun getStorageFile(addressBookKey: AddressBookKey): File?

    fun getLegacyUnencryptedStorageTarget(): File

    fun getStorageTarget(addressBookKey: AddressBookKey): File
}

class AddressBookStorageProviderImpl(
    private val context: Context
) : AddressBookStorageProvider {
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
        File(context.filesDir, "address_book").also { Files.createDirectories(it.toPath()) }
}

private const val LEGACY_UNENCRYPTED_ADDRESS_BOOK_FILE_NAME = "address_book"
