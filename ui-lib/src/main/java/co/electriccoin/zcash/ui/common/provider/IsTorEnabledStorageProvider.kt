package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey

interface IsTorEnabledStorageProvider : NullableBooleanStorageProvider

class IsTorEnabledStorageProviderImpl(
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
        preferenceProvider.putString(key, newValue?.toString())
    }
}
