package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.NullableBooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

interface PersistableWalletProvider {
    val persistableWallet: Flow<PersistableWallet?>
    val writeVersion: Long get() = 0L

    suspend fun store(persistableWallet: PersistableWallet)

    /** Writes only while the persisted snapshot and wallet generation match. */
    suspend fun storeIfCurrent(expected: PersistableWallet, replacement: PersistableWallet, version: Long): Boolean

    suspend fun getPersistableWallet(): PersistableWallet?

    suspend fun requirePersistableWallet(): PersistableWallet
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
) : PersistableWalletProvider, WalletServerSelectionProvider {
    private val persistableWalletStorageProvider = PersistableWalletStorageProviderImpl(preferenceHolder)
    override val writeVersion get() = walletWriteAccess.token()

    override val persistableWallet: Flow<PersistableWallet?> =
        persistableWalletStorageProvider
            .observe()
            .map { record ->
                val wallet = record?.wallet
                wallet?.copy(
                    seedPhrase = wallet.seedPhrase.copy(split = wallet.seedPhrase.split.map { it.trim() })
                )
            }.distinctUntilChanged()

    override val serverSelection =
        persistableWalletStorageProvider.observe().map { it?.serverSelection() }.distinctUntilChanged()

    override suspend fun getServerSelection() = persistableWalletStorageProvider.get()?.serverSelection()

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

    override suspend fun getPersistableWallet() = persistableWalletStorageProvider.get()?.wallet

    override suspend fun requirePersistableWallet() = checkNotNull(getPersistableWallet())

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
        preferenceProvider.getString(key)?.let {
            val json = JSONObject(it)
            WalletRecord(PersistableWallet.from(json), json.opt(SERVER_SELECTION_AUTOMATIC) as? Boolean)
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
