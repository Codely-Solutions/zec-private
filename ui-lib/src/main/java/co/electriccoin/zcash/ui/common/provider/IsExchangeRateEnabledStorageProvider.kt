package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

interface IsExchangeRateEnabledStorageProvider : NullableBooleanStorageProvider {
    override suspend fun store(amount: Boolean) = store(amount, null)

    suspend fun store(amount: Boolean, currency: FiatCurrency?)

    suspend fun readSelection(): CurrencyConversionPreference
    fun observeSelection(): Flow<CurrencyConversionPreference>
}

data class CurrencyConversionPreference(val enabled: Boolean?, val fiat: FiatCurrency?)

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
                newProvider.observe().retryConversionPreferenceRead(false).collect { send(it) }
            }
            launch { cleanUpLegacyPreference() }
        }

    override suspend fun readSelection(): CurrencyConversionPreference {
        val fiatKey = PreferredFiatProviderImpl.KEY
        val values = preferenceHolder().getStringSnapshot(setOf(OPTED_IN_KEY, fiatKey))
        return CurrencyConversionPreference(
            enabled = values[OPTED_IN_KEY]?.toBooleanStrictOrNull(),
            fiat = parsePreferredFiat(values[fiatKey]) ?: FiatCurrency.USD,
        )
    }

    override fun observeSelection(): Flow<CurrencyConversionPreference> = flow {
        val preferences = preferenceHolder()
        emitAll(
            merge(preferences.observe(OPTED_IN_KEY), preferences.observe(PreferredFiatProviderImpl.KEY))
                .map { readSelection() }
                .distinctUntilChanged()
        )
    }.retryConversionPreferenceRead(CurrencyConversionPreference(enabled = false, fiat = null))

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

/** Leaves conversion off while a read is unavailable, then reopens the listener for live subscribers. */
private fun <T> Flow<T>.retryConversionPreferenceRead(fallback: T): Flow<T> = retryWhen { failure, attempt ->
    if (failure !is Exception || failure is CancellationException) return@retryWhen false
    emit(fallback)
    val multiplier = minOf(attempt, MAX_CONVERSION_READ_BACKOFF_STEPS - 1L).toInt() + 1
    delay(CONVERSION_READ_BACKOFF_STEP * multiplier)
    true
}

private val CONVERSION_READ_BACKOFF_STEP = 5.seconds
private const val MAX_CONVERSION_READ_BACKOFF_STEPS = 6
