package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

interface IsTorEnabledStorageProvider : NullableBooleanStorageProvider {
    /** Read the persisted choice for wallet setup, before it is validated for active route use. */
    suspend fun getStoredMode(): Boolean? = get()
}

class IsTorEnabledStorageProviderImpl private constructor(
    private val settings: StoredTorPreference,
    private val transportGate: WalletSdkTransportGate?,
) : IsTorEnabledStorageProvider by settings {
    constructor(
        preferenceHolder: EncryptedPreferenceProvider,
        transportGate: WalletSdkTransportGate? = null,
    ) : this(StoredTorPreference(preferenceHolder), transportGate)

    override suspend fun get(): Boolean? = settings.get().also { transportGate?.torForRequest(it) }

    override suspend fun getStoredMode(): Boolean? = settings.get()
}

private class StoredTorPreference(
    override val preferenceHolder: EncryptedPreferenceProvider
) : BaseNullableStorageProvider<Boolean>(), IsTorEnabledStorageProvider {
    override val default: PreferenceDefault<Boolean?> = TorPreference
}

private object TorPreference : PreferenceDefault<Boolean?> {
    override val key = PreferenceKey("is_tor_Enabled")

    override suspend fun getValue(preferenceProvider: PreferenceProvider): Boolean? =
        when (preferenceProvider.getString(key)) {
            "true" -> true
            "false" -> false
            null -> null
            else -> error("Could not read the Tor protection setting")
        }

    override suspend fun putValue(preferenceProvider: PreferenceProvider, newValue: Boolean?) {
        preferenceProvider.putStringWithRollback(key, newValue?.toString())
    }
}
