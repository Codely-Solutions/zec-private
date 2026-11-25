package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

interface IsExchangeRateEnabledStorageProvider : NullableBooleanStorageProvider

class IsExchangeRateEnabledStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
) : IsExchangeRateEnabledStorageProvider {
    private val newProvider = NewIsExchangeRateEnabledStorageProvider(preferenceHolder)

    override suspend fun get(): Boolean? {
        cleanUpLegacyPreference()
        return runCatchingRecoverable { newProvider.get() }.getOrElse { false }
    }

    override suspend fun store(amount: Boolean) {
        cleanUpLegacyPreference()
        newProvider.store(amount)
    }

    override fun observe(): Flow<Boolean?> =
        channelFlow {
            launch {
                newProvider.observe().recoverableReads().collect { read -> send(read.getOrElse { false }) }
            }
            launch { cleanUpLegacyPreference() }
        }

    override suspend fun clear() {
        preferenceHolder().remove(LEGACY_KEY)
        newProvider.clear()
    }

    private suspend fun cleanUpLegacyPreference() {
        runCatchingRecoverable {
            val preferences = preferenceHolder()
            if (preferences.hasKey(LEGACY_KEY)) preferences.remove(LEGACY_KEY)
        }
    }

    private companion object {
        val LEGACY_KEY = PreferenceKey("EXCHANGE_RATE_OPTED_IN")
    }
}

private class NewIsExchangeRateEnabledStorageProvider(
    override val preferenceHolder: StandardPreferenceProvider,
) : BaseNullableBooleanStorageProvider(key = PreferenceKey("EXCHANGE_RATE_OPTED_IN_2")),
    IsExchangeRateEnabledStorageProvider
