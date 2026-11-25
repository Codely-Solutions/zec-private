package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.NullableBooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONObject

interface PersistableWalletProvider {
    val persistableWallet: Flow<PersistableWallet?>
    val availability: Flow<StoredWalletAvailability>
        get() = persistableWallet.map { wallet ->
            if (wallet == null) StoredWalletAvailability.ABSENT else StoredWalletAvailability.PRESENT
        }
    val writeVersion: Long get() = 0L
    val readGeneration: StateFlow<Long> get() = initialWalletReadGeneration

    fun retryRead() = Unit

    suspend fun store(persistableWallet: PersistableWallet)

    /** Writes only while the persisted snapshot and wallet generation match. */
    suspend fun storeIfCurrent(expected: PersistableWallet, replacement: PersistableWallet, version: Long): Boolean

    suspend fun getPersistableWallet(): PersistableWallet?

    suspend fun requirePersistableWallet(): PersistableWallet
}

private val initialWalletReadGeneration = MutableStateFlow(0L).asStateFlow()

enum class StoredWalletAvailability {
    PRESENT,
    ABSENT,
    UNAVAILABLE,
}

data class WalletServerSelection(val endpoint: LightWalletEndpoint, val isAutomatic: Boolean?)

interface WalletServerSelectionProvider {
    val serverSelection: Flow<WalletServerSelection?>
    val writeVersion: Long

    suspend fun getServerSelection(): WalletServerSelection?

    suspend fun storeServerSelection(
        selection: WalletServerSelection,
        version: Long,
        expected: WalletServerSelection? = null,
    ): Boolean
}

class PersistableWalletProviderImpl(
    private val preferenceHolder: EncryptedPreferenceProvider,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
    resetProgressStore: ResetProgressStore? = null,
) : PersistableWalletProvider, WalletServerSelectionProvider {
    private val persistableWalletStorageProvider = PersistableWalletStorageProviderImpl(preferenceHolder)
    override val writeVersion get() = walletWriteAccess.token()

    private val readsSuppressed = resetProgressStore?.isSdkInputSuppressed

    private val readRequests = MutableStateFlow(0L)
    override val readGeneration = readRequests.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val records = combine(readsSuppressed ?: flowOf(false), readRequests) { suppressed, _ -> suppressed }
        .flatMapLatest { suppressed ->
            if (suppressed) {
                flowOf(Result.success(null))
            } else {
                persistableWalletStorageProvider.observe().map { Result.success(it) }.catch { failure ->
                    if (failure !is Exception || failure is CancellationException) throw failure
                    emit(Result.failure(failure))
                }
            }
        }

    override val availability = records.map { result ->
        when {
            result.isFailure -> StoredWalletAvailability.UNAVAILABLE
            result.getOrNull() != null -> StoredWalletAvailability.PRESENT
            else -> StoredWalletAvailability.ABSENT
        }
    }.distinctUntilChanged()

    override val persistableWallet: Flow<PersistableWallet?> =
        records.map { result ->
            val wallet = result.getOrNull()?.wallet
            wallet?.copy(
                seedPhrase = wallet.seedPhrase.copy(split = wallet.seedPhrase.split.map { it.trim() })
            )
        }.distinctUntilChanged()

    override val serverSelection =
        records.map { it.getOrNull()?.serverSelection() }.distinctUntilChanged()

    override fun retryRead() = readRequests.update { it + 1 }

    override suspend fun getServerSelection() = currentRecord()?.serverSelection()

    override suspend fun store(persistableWallet: PersistableWallet) {
        walletWriteAccess.replaceWallet { persistableWalletStorageProvider.store(WalletRecord(persistableWallet)) }
    }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long
    ): Boolean {
        return walletWriteAccess.write(version) {
            val current = persistableWalletStorageProvider.get()
            if (current == null || !current.wallet.matchesPersistedSnapshot(expected)) {
                false
            } else {
                persistableWalletStorageProvider.store(current.copy(wallet = replacement))
                true
            }
        } ?: false
    }

    override suspend fun storeServerSelection(
        selection: WalletServerSelection,
        version: Long,
        expected: WalletServerSelection?,
    ): Boolean =
        walletWriteAccess.write(version) {
            val current = persistableWalletStorageProvider.get()
            if (current == null || (expected != null && current.serverSelection() != expected)) {
                false
            } else {
                currentCoroutineContext().ensureActive()
                persistableWalletStorageProvider.store(
                    WalletRecord(
                        wallet = current.wallet.copy(endpoint = selection.endpoint),
                        isAutomatic = checkNotNull(selection.isAutomatic)
                    )
                )
                true
            }
        } ?: false

    override suspend fun getPersistableWallet() = currentRecord()?.wallet

    override suspend fun requirePersistableWallet() = checkNotNull(getPersistableWallet())

    private suspend fun currentRecord() =
        if (readsSuppressed?.value == true) null else persistableWalletStorageProvider.get()

    private suspend fun WalletRecord.serverSelection() =
        WalletServerSelection(
            endpoint = wallet.endpoint,
            isAutomatic = isAutomatic ?: LEGACY_SERVER_SELECTION_AUTOMATIC.getValue(preferenceHolder())
        )
}

private fun PersistableWallet.matchesPersistedSnapshot(expected: PersistableWallet): Boolean =
    network == expected.network && seedPhrase == expected.seedPhrase &&
        birthday == expected.birthday && endpoint == expected.endpoint

private val LEGACY_SERVER_SELECTION_AUTOMATIC = NullableBooleanPreferenceDefault(
    PreferenceKey("is_server_selection_automatic")
)

private const val SERVER_SELECTION_AUTOMATIC = "server_selection_automatic"

private data class WalletRecord(val wallet: PersistableWallet, val isAutomatic: Boolean? = null)

private class PersistableWalletStorageProviderImpl(
    override val preferenceHolder: EncryptedPreferenceProvider,
) : BaseNullableStorageProvider<WalletRecord>() {
    override val default = PersistableWalletPreferenceDefault(PreferenceKey("persistable_wallet"))
}

private class PersistableWalletPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<WalletRecord?> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider) =
        preferenceProvider.getString(key)?.let { serialized ->
            runCatchingRecoverable {
                val json = JSONObject(serialized)
                WalletRecord(PersistableWallet.from(json), json.opt(SERVER_SELECTION_AUTOMATIC) as? Boolean)
            }.getOrElse {
                // JSONObject and SDK parse errors can contain the original decrypted record.
                throw StoredWalletDecodeException()
            }
        }

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: WalletRecord?
    ) {
        val value = newValue?.let { record ->
            record.wallet.toJson().apply { put(SERVER_SELECTION_AUTOMATIC, record.isAutomatic) }.toString()
        }
        preferenceProvider.putStringWithRollback(key, value)
    }
}

internal class StoredWalletDecodeException : IllegalStateException("Could not read the stored wallet record")
