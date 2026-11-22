package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.ui.common.model.AddressBook
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.provider.ADDRESS_BOOK_TEMP_FILE_SUFFIX
import co.electriccoin.zcash.ui.common.provider.AddressBookProvider
import co.electriccoin.zcash.ui.common.provider.AddressBookStorageProvider
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Clock
import kotlin.time.Instant

interface AddressBookDataSource {
    fun observe(key: AddressBookKey): Flow<AddressBook?>

    suspend fun saveContact(
        name: String,
        address: String,
        chain: String?,
        key: AddressBookKey
    )

    suspend fun updateContact(
        contact: AddressBookContact,
        name: String,
        address: String,
        chain: String?,
        key: AddressBookKey
    )

    suspend fun deleteContact(addressBookContact: AddressBookContact, key: AddressBookKey)

    suspend fun delete(key: AddressBookKey)
}

class AddressBookDataSourceImpl(
    private val addressBookStorageProvider: AddressBookStorageProvider,
    private val addressBookProvider: AddressBookProvider,
    private val directorySync: DirectorySync
) : AddressBookDataSource {
    private val mutex = Mutex()

    private val revision = MutableStateFlow(0L)
    private var legacyDirectorySynced = false

    override fun observe(key: AddressBookKey): Flow<AddressBook?> =
        revision
            .map<Long, AddressBook?> { mutex.withLock { getAddressBookInternal(key) } }
            .onStart { emit(null) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun saveContact(
        name: String,
        address: String,
        chain: String?,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        val replacement = newContact(name, address, chain)
        if (contacts.any { it.hasSameDetails(replacement) }) contacts else contacts + replacement
    }

    override suspend fun updateContact(
        contact: AddressBookContact,
        name: String,
        address: String,
        chain: String?,
        key: AddressBookKey
    ) = updateAB(key) { contacts ->
        val replacement = newContact(name, address, chain)
        val index = contacts.indexOf(contact)
        if (index >= 0) {
            contacts[index] = replacement
        } else {
            // A retry after directory sync failed can find the replacement already on disk.
            check(contacts.any { it.hasSameDetails(replacement) }) { "Contact changed before it could be updated" }
        }
        contacts
    }

    override suspend fun deleteContact(addressBookContact: AddressBookContact, key: AddressBookKey) =
        updateAB(key) { it.apply { remove(addressBookContact) } }

    override suspend fun delete(key: AddressBookKey) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = addressBookStorageProvider.getStorageTarget(key)
                Files.deleteIfExists(target.toPath())
                Files.deleteIfExists(File(target.parentFile, target.name + ADDRESS_BOOK_TEMP_FILE_SUFFIX).toPath())
                directorySync.sync(checkNotNull(target.parentFile))
                retireLegacy(addressBookStorageProvider.getLegacyUnencryptedStorageTarget())
                revision.update { it + 1 }
            }
        }
    }

    private fun getAddressBookInternal(addressBookKey: AddressBookKey): AddressBook {
        val encrypted = addressBookStorageProvider.getStorageFile(addressBookKey)
        val legacy = addressBookStorageProvider.getLegacyUnencryptedStorageTarget()
        val hasLegacy = !Files.notExists(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)
        return when {
            encrypted != null -> addressBookProvider.readAddressBookFromFile(encrypted, addressBookKey).also { book ->
                // The authenticated replacement must contain the complete legacy book before unlinking it.
                val legacyMatches = hasLegacy && runCatchingRecoverable {
                    addressBookProvider.readLegacyUnencryptedAddressBookFromFile(legacy)
                }.getOrNull() == book
                if (legacyMatches) {
                    directorySync.sync(checkNotNull(encrypted.parentFile))
                    retireLegacy(legacy)
                } else if (!hasLegacy && !legacyDirectorySynced) {
                    // An earlier unlink may have landed without the legacy directory flush.
                    syncLegacyDirectory(legacy)
                }
            }
            hasLegacy -> {
                val book = addressBookProvider.readLegacyUnencryptedAddressBookFromFile(legacy)
                writeToLocalStorage(book, addressBookKey)
                retireLegacy(legacy)
                book
            }
            else -> AddressBook(lastUpdated = getTimestampNow(), contacts = emptyList())
        }
    }

    private fun retireLegacy(file: File) {
        legacyDirectorySynced = false
        Files.deleteIfExists(file.toPath())
        syncLegacyDirectory(file)
    }

    private fun syncLegacyDirectory(file: File) {
        directorySync.sync(checkNotNull(file.parentFile))
        legacyDirectorySynced = true
    }

    private fun writeToLocalStorage(addressBook: AddressBook, key: AddressBookKey) {
        val target = addressBookStorageProvider.getStorageTarget(key)
        addressBookProvider.writeAddressBookToFile(target, addressBook, key)
    }

    private suspend fun updateAB(
        key: AddressBookKey,
        transform: (MutableList<AddressBookContact>) -> List<AddressBookContact>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val addressBook = getAddressBookInternal(key)
            val newAddressBook =
                AddressBook(
                    lastUpdated = getTimestampNow(),
                    contacts = transform(addressBook.contacts.toMutableList()).toList(),
                )
            writeToLocalStorage(newAddressBook, key)
            revision.update { it + 1 }
        }
    }
}

private fun newContact(name: String, address: String, chain: String?) =
    AddressBookContact(
        name = name.trim(),
        address = address.trim(),
        chain = chain?.trim()?.takeIf { it.isNotEmpty() },
        lastUpdated = getTimestampNow(),
    )

private fun AddressBookContact.hasSameDetails(other: AddressBookContact) =
    name == other.name && address == other.address && chain == other.chain

private fun getTimestampNow(): Instant =
    Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
