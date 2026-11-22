package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

interface PersistableWalletProvider {
    val persistableWallet: Flow<PersistableWallet?>
    val writeVersion: Long get() = 0L

    suspend fun store(persistableWallet: PersistableWallet)

    suspend fun storeIfCurrent(expected: PersistableWallet, replacement: PersistableWallet, version: Long): Boolean

    suspend fun getPersistableWallet(): PersistableWallet?

    suspend fun requirePersistableWallet(): PersistableWallet
}

class PersistableWalletProviderImpl(
    preferenceHolder: EncryptedPreferenceProvider,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) : PersistableWalletProvider {
    private val persistableWalletStorageProvider = PersistableWalletStorageProviderImpl(preferenceHolder)
    override val writeVersion get() = walletWriteAccess.token()

    override val persistableWallet: Flow<PersistableWallet?> =
        persistableWalletStorageProvider
            .observe()
            .map { wallet ->
                wallet?.copy(
                    seedPhrase = wallet.seedPhrase.copy(split = wallet.seedPhrase.split.map { it.trim() })
                )
            }

    override suspend fun store(persistableWallet: PersistableWallet) {
        walletWriteAccess.replaceWallet { persistableWalletStorageProvider.store(persistableWallet) }
    }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long
    ): Boolean {
        return walletWriteAccess.write(version) {
            val current = persistableWalletStorageProvider.get()
            if (current == null || current.network != expected.network || current.seedPhrase != expected.seedPhrase) {
                false
            } else {
                persistableWalletStorageProvider.store(replacement)
                true
            }
        } ?: false
    }

    override suspend fun getPersistableWallet() = persistableWalletStorageProvider.get()

    override suspend fun requirePersistableWallet() = checkNotNull(persistableWalletStorageProvider.get())
}

private interface PersistableWalletStorageProvider : NullableStorageProvider<PersistableWallet>

private class PersistableWalletStorageProviderImpl(
    override val preferenceHolder: EncryptedPreferenceProvider,
) : BaseNullableStorageProvider<PersistableWallet>(),
    PersistableWalletStorageProvider {
    override val default = PersistableWalletPreferenceDefault(PreferenceKey("persistable_wallet"))
}

private class PersistableWalletPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<PersistableWallet?> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider) =
        preferenceProvider.getString(key)?.let { PersistableWallet.from(JSONObject(it)) }

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: PersistableWallet?
    ) = preferenceProvider.putString(key, newValue?.toJson()?.toString())
}
