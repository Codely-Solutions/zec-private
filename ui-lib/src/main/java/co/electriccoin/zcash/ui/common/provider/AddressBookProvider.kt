package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.serialization.ADDRESS_BOOK_SERIALIZATION_V2
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookEncryptor
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookSerializer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface AddressBookProvider {
    fun writeAddressBookToFile(
        file: File,
        addressBook: AddressBook,
        addressBookKey: AddressBookKey
    )

    fun readAddressBookFromFile(
        file: File,
        addressBookKey: AddressBookKey
    ): AddressBook

    /** Decodes legacy plaintext and normalizes it to the format used by encrypted storage. */
    fun readLegacyUnencryptedAddressBookFromFile(file: File): AddressBook
}

class AddressBookProviderImpl(
    private val addressBookEncryptor: AddressBookEncryptor,
    private val addressBookSerializer: AddressBookSerializer,
    private val directorySync: DirectorySync,
) : AddressBookProvider {
    override fun writeAddressBookToFile(
        file: File,
        addressBook: AddressBook,
        addressBookKey: AddressBookKey
    ) {
        val directory = checkNotNull(file.absoluteFile.parentFile)
        val tempFile = File(directory, file.name + ADDRESS_BOOK_TEMP_FILE_SUFFIX)
        try {
            FileOutputStream(tempFile).use { fileStream ->
                val stream = fileStream.buffered()
                addressBookEncryptor.encrypt(
                    key = addressBookKey,
                    outputStream = stream,
                    data = addressBook
                )
                stream.flush()
                fileStream.fd.sync()
            }
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            directorySync.sync(directory)
        } finally {
            tempFile.delete()
        }
    }

    override fun readAddressBookFromFile(
        file: File,
        addressBookKey: AddressBookKey
    ): AddressBook =
        file.inputStream().use { stream ->
            addressBookEncryptor.decrypt(
                key = addressBookKey,
                inputStream = stream
            )
        }

    override fun readLegacyUnencryptedAddressBookFromFile(file: File): AddressBook =
        file.inputStream().use { stream ->
            val legacy = addressBookSerializer.deserializeAddressBook(stream)
            require(stream.read() == -1) { "Unexpected trailing data in legacy address book" }
            legacy.copy(version = ADDRESS_BOOK_SERIALIZATION_V2)
        }
}

internal const val ADDRESS_BOOK_TEMP_FILE_SUFFIX = ".tmp"
