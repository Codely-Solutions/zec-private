package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.provider.CMCApiProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Clock

interface ExchangeRateDataSource {
    /**
     * Returns the exchange rate for [fiatCurrency] from CMC if [BuildConfig.ZCASH_CMC_KEY] is available.
     *
     * @throws ExchangeRateUnavailable when exchange rate is not found or server was down
     */
    @Throws(ExchangeRateUnavailable::class, ResponseException::class)
    suspend fun getExchangeRate(fiatCurrency: FiatCurrency): FiatCurrencyConversion

    fun observeSynchronizerRoute(permitsRateRequests: suspend () -> Boolean): Flow<ObserveFiatCurrencyResult>
}

class ExchangeRateUnavailable(
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

class ExchangeRateDataSourceImpl(
    private val cmcApiProvider: CMCApiProvider,
    private val synchronizerProvider: SynchronizerProvider,
) : ExchangeRateDataSource {
    override suspend fun getExchangeRate(fiatCurrency: FiatCurrency): FiatCurrencyConversion {
        @Suppress("ThrowsCount")
        suspend fun getCMCExchangeRate(apiKey: String): FiatCurrencyConversion {
            val exchangeRate =
                cmcApiProvider
                    .getExchangeRateQuote(apiKey = apiKey, fiat = fiatCurrency.code)
                    .data["ZEC"]
                    ?.quote
                    ?.get(fiatCurrency.code)
                    ?.price
            val price = exchangeRate ?: throw ExchangeRateUnavailable(message = "Exchange rate not found in response")
            return FiatCurrencyConversion(
                timestamp = Clock.System.now(),
                priceOfZec = price.toDouble(),
                fiatCurrency = fiatCurrency
            )
        }

        val cmcKey = BuildConfig.ZCASH_CMC_KEY.takeIf { it.isNotBlank() }

        return if (cmcKey != null) {
            getCMCExchangeRate(cmcKey)
        } else {
            throw ExchangeRateUnavailable(message = "CMC token not present")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSynchronizerRoute(permitsRateRequests: suspend () -> Boolean): Flow<ObserveFiatCurrencyResult> =
        synchronizerProvider.synchronizer.flatMapLatest { synchronizer ->
            if (synchronizer == null) return@flatMapLatest flowOf(ObserveFiatCurrencyResult(isLoading = false))
            synchronizer.status
                .map { it != Synchronizer.Status.STOPPED }
                .distinctUntilChanged()
                .transformLatest { ready ->
                    // Collecting the SDK's rate StateFlow can start a Tor price request on its own.
                    if (ready && permitsRateRequests() && synchronizerProvider.synchronizer.value === synchronizer) {
                        synchronizer.refreshExchangeRateUsd()
                        emitAll(synchronizer.exchangeRateUsd)
                    } else {
                        emit(ObserveFiatCurrencyResult(isLoading = false))
                    }
                }
        }
}
