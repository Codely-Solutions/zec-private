package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

interface IsExchangeRateEnabledStorageProvider : NullableBooleanStorageProvider {
    override suspend fun store(amount: Boolean) = store(amount, null)

    suspend fun store(amount: Boolean, currency: FiatCurrency?)
}

class IsExchangeRateEnabledStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
) : IsExchangeRateEnabledStorageProvider {
    private val newProvider = NewIsExchangeRateEnabledStorageProvider(preferenceHolder, OPTED_IN_KEY)

    override suspend fun get(): Boolean? {
        cleanUpLegacyPreference()
        return newProvider.get()
    }

    override suspend fun store(amount: Boolean, currency: FiatCurrency?) {
        cleanUpLegacyPreference()
        val values = buildMap {
            put(OPTED_IN_KEY, amount.toString())
            currency?.let { put(PreferredFiatProviderImpl.KEY, it.code) }
        }
        preferenceHolder().putStringsWithRollback(values)
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
        val OPTED_IN_KEY = PreferenceKey("EXCHANGE_RATE_OPTED_IN_2")
    }
}

private class NewIsExchangeRateEnabledStorageProvider(
    override val preferenceHolder: StandardPreferenceProvider,
    key: PreferenceKey,
) : BaseNullableBooleanStorageProvider(key = key)
