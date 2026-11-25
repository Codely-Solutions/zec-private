package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateUnavailable
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.retryConversionPreferenceRead
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateError
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.RefreshLock
import co.electriccoin.zcash.ui.common.wallet.StaleLock
import co.electriccoin.zcash.ui.common.wallet.WALLET_EXCHANGE_RATE_MAX_AGE
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface ExchangeRateRepository {
    val state: StateFlow<ExchangeRateState>

    fun refreshExchangeRateUsd()
}

class ExchangeRateRepositoryImpl(
    isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    preferredFiatProvider: PreferredFiatProvider,
    private val exchangeRateDataSource: ExchangeRateDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : ExchangeRateRepository {
    private val refreshPipeline = MutableSharedFlow<Unit>()

    private val isExchangeRateOptedIn =
        isExchangeRateEnabledStorageProvider
            .observe()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                initialValue = null
            )

    private val preferredFiat =
        preferredFiatProvider
            .observe()
            .map<FiatCurrency?, FiatCurrency?> { it ?: FiatCurrency.USD }
            .retryConversionPreferenceRead(null)
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                initialValue = null
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exchangeRateUsdInternal =
        combine(isExchangeRateOptedIn, preferredFiat) { optedIn, fiat -> optedIn to fiat }
            .flatMapLatest { (optedIn, fiat) ->
                if (optedIn == true && fiat != null) {
                    channelFlow {
                        var cache = ExchangeRateResult(ObserveFiatCurrencyResult(isLoading = true))
                        launch {
                            refreshPipeline
                                .onStart { emit(Unit) }
                                .flatMapLatest {
                                    flow {
                                        emit(cache.copy(observed = cache.observed.copy(isLoading = true)))
                                        emit(
                                            ExchangeRateResult(
                                                observed =
                                                    cache.observed.copy(
                                                        isLoading = false,
                                                        currencyConversion =
                                                            exchangeRateDataSource
                                                                .getExchangeRate(fiat)
                                                    )
                                            )
                                        )
                                    }.catch { error ->
                                        if (shouldFallBackToSynchronizerRoute(VersionInfo.IS_CMC_AVAILABLE, fiat)) {
                                            synchronizerProvider.getSynchronizer().refreshExchangeRateUsd()
                                            emitAll(
                                                exchangeRateDataSource
                                                    .observeSynchronizerRoute()
                                                    .map { ExchangeRateResult(observed = it) }
                                            )
                                        } else {
                                            emit(
                                                cache.copy(
                                                    observed = cache.observed.copy(isLoading = false),
                                                    error = classifyExchangeRateError(error)
                                                )
                                            )
                                        }
                                    }
                                }.collect {
                                    cache = it
                                    send(cache)
                                }
                        }
                        awaitClose()
                    }
                } else {
                    flowOf(ExchangeRateResult())
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(
                    USD_EXCHANGE_REFRESH_LOCK_THRESHOLD,
                    replayExpiration = Duration.ZERO,
                ),
                initialValue = ExchangeRateResult(ObserveFiatCurrencyResult(isLoading = true))
            )

    private val usdExchangeRateTimestamp =
        exchangeRateUsdInternal
            .map {
                it.observed.currencyConversion?.timestamp
            }.distinctUntilChanged()

    private val refreshExchangeRateUsdLock =
        RefreshLock(
            timestampToObserve = usdExchangeRateTimestamp,
            lockDuration = USD_EXCHANGE_REFRESH_LOCK_THRESHOLD
        )

    private val staleExchangeRateUsdLock =
        StaleLock(
            timestampToObserve = usdExchangeRateTimestamp,
            lockDuration = WALLET_EXCHANGE_RATE_MAX_AGE,
            onRefresh = { expiredTimestamp ->
                // The combined UI state may still hold its previous value when collection resumes.
                val observed = exchangeRateUsdInternal.value.observed
                if (!observed.isLoading && observed.currencyConversion?.timestamp == expiredTimestamp) {
                    refreshPipeline.emit(Unit)
                }
            }
        )

    override val state: StateFlow<ExchangeRateState> =
        combine(
            isExchangeRateOptedIn,
            exchangeRateUsdInternal,
            staleExchangeRateUsdLock.state,
            refreshExchangeRateUsdLock.state,
            preferredFiat,
        ) { isOptedIn, exchangeRate, isStale, isRefreshEnabled, expectedCurrency ->
            createState(isOptedIn, exchangeRate, isStale, isRefreshEnabled, expectedCurrency)
        }.distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5.seconds, replayExpiration = Duration.ZERO),
                initialValue =
                    createState(
                        isOptedIn = isExchangeRateOptedIn.value,
                        exchangeRate = exchangeRateUsdInternal.value,
                        isStale = false,
                        isRefreshEnabled = false,
                        expectedCurrency = preferredFiat.value,
                    )
            )

    private fun createState(
        isOptedIn: Boolean?,
        exchangeRate: ExchangeRateResult,
        isStale: Boolean,
        isRefreshEnabled: Boolean,
        expectedCurrency: FiatCurrency?,
    ): ExchangeRateState =
        when (isOptedIn) {
            true -> {
                ExchangeRateState.Data(
                    isLoading = expectedCurrency != null && exchangeRate.observed.isLoading,
                    isStale = expectedCurrency != null && isStale,
                    isRefreshEnabled = expectedCurrency != null && isRefreshEnabled,
                    currencyConversion = expectedCurrency?.let { exchangeRate.observed.currencyConversion },
                    expectedCurrency = expectedCurrency ?: FiatCurrency.USD,
                    error = expectedCurrency?.let { exchangeRate.error },
                    onRefresh = ::refreshExchangeRateUsd
                )
            }

            false -> {
                ExchangeRateState.OptedOut
            }

            null -> {
                ExchangeRateState.OptIn
            }
        }

    override fun refreshExchangeRateUsd() {
        scope.launch {
            val value = state.value
            if (value is ExchangeRateState.Data && value.isRefreshEnabled && !value.isLoading) {
                refreshPipeline.emit(Unit)
            }
        }
    }
}

/**
 * Decides whether to fall back to the SDK synchronizer route when the CMC exchange-rate lookup
 * fails or is unavailable. The synchronizer route only provides a USD rate.
 *
 * - When CMC is not available it is the only rate source, so always fall back regardless of [fiat].
 * - When CMC is available, only fall back for USD; for any other fiat the synchronizer route
 *   cannot provide a matching rate.
 */
internal fun shouldFallBackToSynchronizerRoute(
    isCmcAvailable: Boolean,
    fiat: FiatCurrency,
): Boolean = !isCmcAvailable || fiat == FiatCurrency.USD

/**
 * Maps a CMC exchange-rate failure to the user-facing [ExchangeRateError], or `null` when the
 * failure is a transient connectivity problem that shouldn't surface a dedicated error sheet.
 *
 * - [ExchangeRateUnavailable] → CMC answered but the quote was missing (or no CMC key is
 *   configured) → [ExchangeRateError.CMC_EXCHANGE_RATE_UNAVAILABLE].
 * - Definitive server-side problems → [ExchangeRateError.CMC_SERVER_CONNECTION_ERROR]:
 *     - [ResponseException] → the server answered with an HTTP error (5xx, 401/403 auth, 404, …).
 *     - [UnknownHostException] → DNS can't resolve the host.
 *     - [ContentConvertException] (e.g. ktor's JsonConvertException) / [NoTransformationFoundException]
 *       → the server answered but the payload couldn't be parsed into the expected type.
 * - Everything else (socket/connect/request timeouts, generic [java.io.IOException] from a weak or
 *   dropped connection, …) is transient → `null`, so no error sheet is shown.
 */
internal fun classifyExchangeRateError(error: Throwable): ExchangeRateError? =
    when (error) {
        is ExchangeRateUnavailable -> ExchangeRateError.CMC_EXCHANGE_RATE_UNAVAILABLE
        is ResponseException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is UnknownHostException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is ContentConvertException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        is NoTransformationFoundException -> ExchangeRateError.CMC_SERVER_CONNECTION_ERROR
        else -> null
    }

/**
 * App-side wrapper pairing the SDK's [ObserveFiatCurrencyResult] with an app-level
 * [ExchangeRateError], since the SDK type cannot carry our error classification.
 */
private data class ExchangeRateResult(
    val observed: ObserveFiatCurrencyResult = ObserveFiatCurrencyResult(),
    val error: ExchangeRateError? = null,
)

private val USD_EXCHANGE_REFRESH_LOCK_THRESHOLD = 2.minutes
