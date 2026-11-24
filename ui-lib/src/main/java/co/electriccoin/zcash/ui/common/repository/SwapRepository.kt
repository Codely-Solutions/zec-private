package co.electriccoin.zcash.ui.common.repository

import android.os.SystemClock
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.ui.common.datasource.AssetNotFoundException
import co.electriccoin.zcash.ui.common.datasource.NEAR_SWAP_PROVIDER
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapPriceMarkets
import co.electriccoin.zcash.ui.common.model.isZCashAsset
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.near.requireMatchingAsset
import co.electriccoin.zcash.ui.common.model.near.requireQuoteMatchesUserAmount
import co.electriccoin.zcash.ui.common.model.near.bindsReviewedFundingTerms
import co.electriccoin.zcash.ui.common.model.near.requireNoAffiliateFees
import co.electriccoin.zcash.ui.common.model.near.requireSimpleChainRouting
import co.electriccoin.zcash.ui.common.model.near.matchesReviewedIdentity
import co.electriccoin.zcash.ui.common.model.swapQuoteMismatchSignal
import co.electriccoin.zcash.ui.common.provider.SwapAssetCacheProvider
import co.electriccoin.zcash.wallet.foundation.RequestGeneration
import co.electriccoin.zcash.wallet.foundation.RequestId
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

@Suppress("TooManyFunctions")
interface SwapRepository {
    val assets: StateFlow<SwapAssetsData>

    val quote: StateFlow<SwapQuoteData?>

    fun requestRefreshAssets()

    suspend fun requestRefreshAssetsOnce()

    fun requestExactInputQuote(
        amount: BigDecimal,
        address: String,
        refundAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal
    )

    fun requestExactOutputQuote(
        amount: BigDecimal,
        address: String,
        refundAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal
    )

    fun requestFlexInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        destinationAddress: String,
        originAsset: SwapAsset,
        slippage: BigDecimal,
        owner: IncomingSwapOwner? = null,
    )

    @Throws(ResponseException::class)
    suspend fun submitDepositTransaction(txId: String, transactionProposal: SwapTransactionProposal)

    @Throws(
        ResponseException::class,
        AssetNotFoundException::class,
        SwapAssetsUnavailableException::class,
        IllegalArgumentException::class
    )
    suspend fun checkSwapStatus(swapMetadata: TransactionSwapMetadata): SwapQuoteStatus

    fun clear()

    fun clearIfCurrent(expected: SwapQuoteData.Success): Boolean

    fun clearQuote()

    /** Admit only the current request after its local funding preparation succeeds. */
    fun markReviewReady(result: SwapQuoteData): Boolean

    /** One owner may navigate for a prepared result, including after a cancel sheet closes. */
    fun consumeReview(expected: SwapQuoteData? = null): SwapQuoteData?

    /** Recheck after suspending between a navigation claim and opening Review. */
    fun isReviewCurrent(result: SwapQuoteData): Boolean
}

data class IncomingSwapOwner(val account: WalletAccount, val walletWriteToken: Long)

sealed interface SwapQuoteData {
    data class Success(
        val quote: SwapQuote,
        val incomingOwner: IncomingSwapOwner? = null,
        private val fundingClock: Clock = Clock.System,
        private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    ) : SwapQuoteData {
        private var fundingDeadlineElapsed: Long? = null
        private var fundingExpired = false

        @Synchronized
        internal fun canFund(
            now: kotlin.time.Instant = fundingClock.now(),
            elapsed: Long = elapsedRealtime(),
        ): Boolean {
            val remaining = quote.deadline - now
            val previous = fundingDeadlineElapsed
            val stop = elapsed + remaining.inWholeMilliseconds.coerceIn(0, Long.MAX_VALUE - elapsed)
            val deadline = previous?.coerceAtMost(stop) ?: stop
            fundingDeadlineElapsed = deadline
            fundingExpired = fundingExpired || remaining <= kotlin.time.Duration.ZERO || elapsed >= deadline
            return !fundingExpired
        }
    }

    data class Error(
        val mode: SwapMode,
        /**
         * The failure that ended the quote request. A [SwapQuoteMismatchException.Reported] carries the
         * report context of a rejected quote and routes to the mismatch sheet; every other failure stays
         * on the generic quote-error path.
         */
        val exception: Exception
    ) : SwapQuoteData

    data object Loading : SwapQuoteData
}

fun SwapTransactionProposal.requireFundingApproval(publication: SwapQuoteData.Success?) {
    check(publication != null && publication.quote === quote && publication.canFund()) {
        "The reviewed swap quote is unavailable or expired. Request a fresh quote before sending."
    }
}

data class SwapAssetsData(
    val data: List<SwapAsset>? = null,
    val zecAsset: SwapAsset? = null,
    val isLoading: Boolean = false,
    val error: Exception? = null,
    val recoveryAssets: List<SwapAsset>? = null,
) {
    val hasUsableLivePrices: Boolean
        get() =
            data?.any { asset -> asset.usdPrice?.let { it > BigDecimal.ZERO } == true } == true &&
                zecAsset?.usdPrice?.let { it > BigDecimal.ZERO } == true

    internal fun shouldRetryPrices(selectedAsset: SwapAsset?): Boolean =
        error != null || !isLoading && data != null &&
            (!hasUsableLivePrices || selectedAsset != null && selectedAsset.usdPrice?.signum() != 1)
}

/** Thrown by [SwapRepository.checkSwapStatus] when the supported-asset list could not be loaded. */
class SwapAssetsUnavailableException : Exception("Swap assets are unavailable")

@Suppress("TooManyFunctions")
class SwapRepositoryImpl(
    private val swapDataSource: SwapDataSource,
    private val swapAssetCacheProvider: SwapAssetCacheProvider,
) : SwapRepository {
    /**
     * Scope the background refresh/quote jobs run on. A test seam: unit tests replace it with a
     * test dispatcher before invoking any method, so the fire-and-forget jobs run deterministically.
     */
    @set:RestrictTo(RestrictTo.Scope.TESTS)
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override val assets = MutableStateFlow(SwapAssetsData())

    override val quote = MutableStateFlow<SwapQuoteData?>(null)

    private var refreshSession: AssetsRefreshSession? = null

    private var requestQuoteJob: Job? = null

    private val quoteGeneration = RequestGeneration()
    private var readyReview: SwapQuoteData? = null
    private var consumedReview: SwapQuoteData? = null

    override fun requestRefreshAssets() {
        getOrStartRefreshAssetsSession(keepRefreshing = true)
    }

    override suspend fun requestRefreshAssetsOnce() {
        while (true) {
            val session = getOrStartRefreshAssetsSession(keepRefreshing = false)
            session.firstRefresh.join()
            if (!session.firstRefresh.isCancelled) return
        }
    }

    /**
     * Both public refresh entry points share this job. Besides avoiding duplicate catalog calls,
     * hydrating here ensures an on-demand refresh can expose cached metadata just like navigation.
     */
    private fun getOrStartRefreshAssetsSession(keepRefreshing: Boolean): AssetsRefreshSession =
        synchronized(this) {
            val activeSession = refreshSession?.takeIf { it.job?.isActive == true }
            if (activeSession != null && !activeSession.firstRefresh.isCompleted) {
                activeSession.keepRefreshing = activeSession.keepRefreshing || keepRefreshing
                return@synchronized activeSession
            }

            // A completed first refresh means the periodic job is sleeping. A user-driven refresh
            // must fetch immediately instead of waiting for the next 30-second tick.
            val shouldKeepRefreshing = keepRefreshing || activeSession?.keepRefreshing == true
            activeSession?.job?.cancel()

            AssetsRefreshSession(keepRefreshing = shouldKeepRefreshing).also { session ->
                refreshSession = session
                val job =
                    scope
                        .launch {
                            hydrateAssetsFromCache()
                            do {
                                refreshAssetsInternal()
                                session.firstRefresh.complete(Unit)
                                val continueRefreshing =
                                    synchronized(this@SwapRepositoryImpl) {
                                        if (refreshSession === session && session.keepRefreshing) {
                                            true
                                        } else {
                                            if (refreshSession === session) refreshSession = null
                                            false
                                        }
                                    }
                                if (!continueRefreshing) break
                                delay(ASSET_REFRESH_INTERVAL)
                            } while (true)
                        }.also { launchedJob ->
                            // launch may be cancelled before its body is dispatched, so cleanup cannot
                            // rely on a finally block inside the coroutine.
                            launchedJob.invokeOnCompletion {
                                if (!session.firstRefresh.isCompleted) session.firstRefresh.cancel()
                            }
                        }
                session.job = job
            }
        }

    private class AssetsRefreshSession(
        val firstRefresh: CompletableDeferred<Unit> = CompletableDeferred(),
        var keepRefreshing: Boolean,
        var job: Job? = null,
    )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshAssetsInternal() {
        fun filterSwapAssets(assets: List<SwapAsset>) =
            assets
                .toMutableList()
                .apply {
                    removeIf {
                        val usdPrice = it.usdPrice
                        it.isZCashAsset || usdPrice == null || usdPrice <= BigDecimal.ZERO
                    }
                }.toList()

        assets.update { it.copy(isLoading = true, error = null) }
        try {
            val tokens = swapDataSource.getSupportedTokens()
            // Price filtering must not turn an ambiguous raw ID into a selectable identity.
            // Keep the raw rows for recovery, whose resolver also rejects duplicate IDs.
            val uniqueIds = tokens.groupingBy { it.assetId }.eachCount().filterValues { it == 1 }.keys
            val selectable = tokens.filter { it.assetId in uniqueIds }
            val filtered = filterSwapAssets(selectable)
            val zecAsset = SwapPriceMarkets.findNativeZec(selectable)
            try {
                // Historical identities must remain resolvable when a price or offer disappears.
                swapAssetCacheProvider.store(mergeRecoveryAssets(tokens, assets.value.recoveryAssets.orEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GlobalCrashReporter.reportCaughtException(e)
            }
            assets.update {
                it.copy(
                    data = filtered,
                    zecAsset = zecAsset,
                    error = null,
                    isLoading = false,
                    recoveryAssets = mergeRecoveryAssets(tokens, it.recoveryAssets.orEmpty()),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            assets.update { assets ->
                assets.copy(
                    isLoading = false,
                    // Cached metadata deliberately has no prices. Without an error here the CTA is
                    // disabled, loading has stopped, and there is no way for the user to retry.
                    error = e.takeUnless { assets.hasUsableLivePrices }
                )
            }
        }
    }

    private suspend fun hydrateAssetsFromCache() {
        if (assets.value.data != null) return
        val cachedAssets = swapAssetCacheProvider.get()
        if (cachedAssets.isEmpty()) return
        assets.update {
            it.copy(
                data = cachedAssets.filterNot(SwapAsset::isZCashAsset),
                zecAsset = SwapPriceMarkets.findNativeZec(cachedAssets),
                error = null,
                isLoading = true,
                recoveryAssets = cachedAssets,
            )
        }
    }

    override fun requestExactInputQuote(
        amount: BigDecimal,
        address: String,
        refundAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal
    ) {
        requestSwapFromZecQuote(
            amount = amount,
            address = address,
            mode = EXACT_INPUT,
            refundAddress = refundAddress,
            destinationAsset = destinationAsset,
            slippage = slippage
        )
    }

    override fun requestExactOutputQuote(
        amount: BigDecimal,
        address: String,
        refundAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal
    ) {
        requestSwapFromZecQuote(
            amount = amount,
            address = address,
            mode = EXACT_OUTPUT,
            refundAddress = refundAddress,
            destinationAsset = destinationAsset,
            slippage = slippage
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun requestFlexInputIntoZec(
        amount: BigDecimal,
        refundAddress: String,
        destinationAddress: String,
        originAsset: SwapAsset,
        slippage: BigDecimal,
        owner: IncomingSwapOwner?,
    ) {
        launchQuoteRequest { generation ->
            val catalog = assets.value
            val destinationAsset = catalog.zecAsset?.takeIf { it.isCanonicalNativeZec }
            if (destinationAsset == null) {
                publishQuote(generation, SwapQuoteData.Error(FLEX_INPUT, SwapAssetsUnavailableException()))
                return@launchQuoteRequest
            }
            var receivedQuote: SwapQuote? = null
            try {
                requireSelectableAsset(catalog.data, originAsset)
                val result =
                    swapDataSource.requestQuote(
                        swapMode = FLEX_INPUT,
                        amount = amount,
                        refundAddress = refundAddress,
                        originAsset = originAsset,
                        destinationAddress = destinationAddress,
                        destinationAsset = destinationAsset,
                        slippage = slippage
                    )
                currentCoroutineContext().ensureActive()
                receivedQuote = result
                requireQuoteMatchesUserAmount(
                    quoted = result.amountInFormatted,
                    requested = amount,
                    decimals = result.originAsset.decimals
                )
                requireSupportedSelectedAsset(
                    name = "originAsset",
                    type = SwapQuoteMismatchType.ORIGIN_ASSET,
                    catalog = assets.value,
                    selectedAsset = originAsset,
                    actual = result.originAsset
                )
                requireExpectedAsset(
                    name = "destinationAsset",
                    type = SwapQuoteMismatchType.DESTINATION_ASSET,
                    expected = destinationAsset,
                    actual = result.destinationAsset
                )
                requireMatchingAddress(
                    name = "refundAddress",
                    type = SwapQuoteMismatchType.REFUND_ADDRESS,
                    expected = refundAddress,
                    actual = result.refundAddress.address
                )
                requireMatchingAddress(
                    name = "destinationAddress",
                    type = SwapQuoteMismatchType.RECIPIENT_ADDRESS,
                    expected = destinationAddress,
                    actual = result.destinationAddress.address
                )
                publishQuote(generation, SwapQuoteData.Success(quote = result, incomingOwner = owner))
            } catch (e: SwapQuoteMismatchException) {
                currentCoroutineContext().ensureActive()
                GlobalCrashReporter.reportCaughtException(swapQuoteMismatchSignal(e))
                publishQuote(
                    generation,
                    SwapQuoteData.Error(
                        mode = FLEX_INPUT,
                        exception = e.withQuoteReportContext(receivedQuote, originAsset, destinationAsset)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishQuote(generation, SwapQuoteData.Error(FLEX_INPUT, e))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun requestSwapFromZecQuote(
        amount: BigDecimal,
        address: String,
        mode: SwapMode,
        refundAddress: String,
        destinationAsset: SwapAsset,
        slippage: BigDecimal
    ) {
        launchQuoteRequest { generation ->
            val catalog = assets.value
            val originAsset = catalog.zecAsset?.takeIf { it.isCanonicalNativeZec }
            if (originAsset == null) {
                publishQuote(generation, SwapQuoteData.Error(mode, SwapAssetsUnavailableException()))
                return@launchQuoteRequest
            }
            var receivedQuote: SwapQuote? = null
            try {
                requireSelectableAsset(catalog.data, destinationAsset)
                val result =
                    swapDataSource.requestQuote(
                        swapMode = mode,
                        amount = amount,
                        refundAddress = refundAddress,
                        originAsset = originAsset,
                        destinationAddress = address,
                        destinationAsset = destinationAsset,
                        slippage = slippage
                    )
                currentCoroutineContext().ensureActive()
                receivedQuote = result
                when (mode) {
                    EXACT_INPUT,
                    FLEX_INPUT -> {
                        requireQuoteMatchesUserAmount(
                            quoted = result.amountInFormatted,
                            requested = amount,
                            decimals = result.originAsset.decimals
                        )
                    }

                    EXACT_OUTPUT -> {
                        requireQuoteMatchesUserAmount(
                            quoted = result.amountOutFormatted,
                            requested = amount,
                            decimals = result.destinationAsset.decimals
                        )
                    }
                }
                requireExpectedAsset(
                    name = "originAsset",
                    type = SwapQuoteMismatchType.ORIGIN_ASSET,
                    expected = originAsset,
                    actual = result.originAsset
                )
                requireSupportedSelectedAsset(
                    name = "destinationAsset",
                    type = SwapQuoteMismatchType.DESTINATION_ASSET,
                    catalog = assets.value,
                    selectedAsset = destinationAsset,
                    actual = result.destinationAsset
                )
                requireMatchingAddress(
                    name = "destinationAddress",
                    type = SwapQuoteMismatchType.RECIPIENT_ADDRESS,
                    expected = address,
                    actual = result.destinationAddress.address
                )
                requireMatchingAddress(
                    name = "refundAddress",
                    type = SwapQuoteMismatchType.REFUND_ADDRESS,
                    expected = refundAddress,
                    actual = result.refundAddress.address
                )
                publishQuote(generation, SwapQuoteData.Success(quote = result))
            } catch (e: SwapQuoteMismatchException) {
                currentCoroutineContext().ensureActive()
                GlobalCrashReporter.reportCaughtException(swapQuoteMismatchSignal(e))
                publishQuote(
                    generation,
                    SwapQuoteData.Error(
                        mode = mode,
                        exception = e.withQuoteReportContext(receivedQuote, originAsset, destinationAsset)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishQuote(generation, SwapQuoteData.Error(mode, e))
            }
        }
    }

    override suspend fun submitDepositTransaction(txId: String, transactionProposal: SwapTransactionProposal) {
        swapDataSource.submitDepositTransaction(
            txHash = txId,
            depositAddress = transactionProposal.destination.address
        )
    }

    override suspend fun checkSwapStatus(swapMetadata: TransactionSwapMetadata): SwapQuoteStatus {
        val recovery = swapMetadata.requireNearRecovery()
        val result = checkStatusUsingCatalog(recovery.reference)
        require(result.depositAddress.address == recovery.reference) { "Swap status reference mismatch" }
        swapMetadata.reviewedIdentity?.let { expectedIdentity ->
            val response = requireNotNull((result as? NearSwapQuoteStatus)?.response?.quoteResponse)
            response.requireSimpleChainRouting()
            if (expectedIdentity.bindsReviewedFundingTerms()) response.requireNoAffiliateFees()
            require(response.matchesReviewedIdentity(expectedIdentity)) { "Swap status reviewed identity mismatch" }
            swapMetadata.quotedAmountOut?.let { approved ->
                require(result.quotedAmountOut.compareTo(approved) == 0) { "Swap status quoted output mismatch" }
            }
        }
        requireMatchingAsset(
            name = "origin",
            type = SwapQuoteMismatchType.ORIGIN_ASSET,
            expectedTokenTicker = swapMetadata.origin.tokenTicker,
            expectedChainTicker = swapMetadata.origin.chainTicker,
            actual = result.originAsset
        )
        requireMatchingAsset(
            name = "destination",
            type = SwapQuoteMismatchType.DESTINATION_ASSET,
            expectedTokenTicker = swapMetadata.destination.tokenTicker,
            expectedChainTicker = swapMetadata.destination.chainTicker,
            actual = result.destinationAsset
        )
        return result
    }

    private suspend fun checkStatusUsingCatalog(reference: String): SwapQuoteStatus =
        try {
            swapDataSource.checkSwapStatus(reference, getSupportedTokensForStatus())
        } catch (_: AssetNotFoundException) {
            // A partial historical cache must not prevent recovery when the live catalog returns.
            requestRefreshAssetsOnce()
            swapDataSource.checkSwapStatus(reference, getSupportedTokensForStatus())
        }

    /**
     * Resolves the supported-token list the status lookup needs to map asset ids back to [SwapAsset]s.
     * Uses the already-loaded [assets] when present, otherwise refreshes once on demand. The ZEC asset
     * (kept separately in [SwapAssetsData.zecAsset], filtered out of [SwapAssetsData.data]) is appended
     * so both sides of a swap resolve.
     */
    private suspend fun getSupportedTokensForStatus(): List<SwapAsset> {
        assets.value.statusAssets()?.let { return it }
        requestRefreshAssetsOnce()
        val refreshed = assets.firstOrNull { !it.isLoading }
        return refreshed?.statusAssets() ?: throw (refreshed?.error ?: SwapAssetsUnavailableException())
    }

    override fun clear() {
        synchronized(this) {
            refreshSession?.job?.cancel()
            refreshSession = null
        }
        assets.update {
            if (it.data == null) SwapAssetsData() else it.copy(isLoading = false, error = null)
        }
        clearQuote()
    }

    override fun clearIfCurrent(expected: SwapQuoteData.Success): Boolean = synchronized(this) {
        if (quote.value !== expected) return@synchronized false
        clear()
        true
    }

    /** Loading is visible before callers collect a result; old jobs cannot publish into a new request. */
    private fun launchQuoteRequest(block: suspend (RequestId) -> Unit) {
        val job =
            synchronized(this) {
                val generation = quoteGeneration.advance()
                requestQuoteJob?.cancel()
                quote.value = SwapQuoteData.Loading
                readyReview = null
                consumedReview = null
                scope.launch(start = CoroutineStart.LAZY) { block(generation) }.also { requestQuoteJob = it }
            }
        job.start()
    }

    private fun publishQuote(generation: RequestId, result: SwapQuoteData) {
        if (result is SwapQuoteData.Success) result.canFund(kotlin.time.Clock.System.now())
        synchronized(this) {
            if (quoteGeneration.isCurrent(generation)) {
                // The result can resume a collector that immediately prepares or consumes it.
                readyReview = result.takeIf { it is SwapQuoteData.Error }
                quote.value = result
            }
        }
    }

    override fun clearQuote() {
        synchronized(this) {
            quoteGeneration.advance()
            requestQuoteJob?.cancel()
            requestQuoteJob = null
            quote.value = null
            readyReview = null
            consumedReview = null
        }
    }

    override fun markReviewReady(result: SwapQuoteData): Boolean = synchronized(this) {
        if (!isReviewCurrent(result) || result === consumedReview) {
            return@synchronized false
        }
        readyReview = result
        true
    }

    override fun consumeReview(expected: SwapQuoteData?): SwapQuoteData? = synchronized(this) {
        readyReview?.takeIf { (expected == null || it === expected) && isReviewCurrent(it) }
            ?.also { readyReview = null; consumedReview = it }
    }

    override fun isReviewCurrent(result: SwapQuoteData): Boolean {
        if (quote.value !== result) return false
        return when (result) {
            is SwapQuoteData.Success -> assets.value.admits(result.quote)
            is SwapQuoteData.Error -> true
            SwapQuoteData.Loading -> false
        }
    }
}

private fun SwapAssetsData.admits(quote: SwapQuote): Boolean =
    admits(quote.originAsset) && admits(quote.destinationAsset)

private fun SwapAssetsData.admits(asset: SwapAsset): Boolean =
    if (asset.isZCashAsset) {
        asset.isCanonicalNativeZec && zecAsset?.isCanonicalNativeZec == true && zecAsset.assetId == asset.assetId
    } else {
        SwapPriceMarkets.reference(asset) != null && SwapPriceMarkets.findAsset(data, asset.assetId) != null
    }

private fun mergeRecoveryAssets(current: List<SwapAsset>, previous: List<SwapAsset>): List<SwapAsset> =
    current + previous.filter { old -> current.none { it.assetId == old.assetId } }

private val SwapAsset.isCanonicalNativeZec: Boolean
    get() = isZCashAsset && SwapPriceMarkets.reference(this) != null

private fun SwapAssetsData.statusAssets(): List<SwapAsset>? =
    recoveryAssets?.takeIf { tokens -> tokens.any { it.isZCashAsset } }
        ?: data?.let { tokens -> zecAsset?.let { tokens + it } }

val DEFAULT_SLIPPAGE = BigDecimal("2")

private val ASSET_REFRESH_INTERVAL = 30.seconds

/**
 * Asserts the quote's ZEC-side asset matches `expected` — an independent snapshot of the repository's
 * ZEC asset — a cross-check that the right ZEC asset was used. The user-selected side is validated more
 * strictly by [requireSupportedSelectedAsset].
 */
private fun requireExpectedAsset(
    name: String,
    type: SwapQuoteMismatchType,
    expected: SwapAsset?,
    actual: SwapAsset
) {
    if (expected == null) return
    requireMatchingAsset(
        name = name,
        type = type,
        expectedTokenTicker = expected.tokenTicker,
        expectedChainTicker = expected.chainTicker,
        actual = actual
    )
}

/**
 * Rechecks both sides against the current catalog before accepting the response, then checks the
 * response's asset against the selected route.
 *
 * A selection missing from the loaded list is a client-side staleness, not a request-vs-response
 * disagreement, so it stays a plain rejection; only the echo comparison reports a typed mismatch.
 */
private fun requireSupportedSelectedAsset(
    name: String,
    type: SwapQuoteMismatchType,
    catalog: SwapAssetsData,
    selectedAsset: SwapAsset,
    actual: SwapAsset
) {
    require(catalog.zecAsset?.isCanonicalNativeZec == true) { "Swap quote requires native ZEC" }
    val supported = requireSelectableAsset(catalog.data, selectedAsset)
    requireMatchingAsset(
        name = name,
        type = type,
        expectedTokenTicker = supported.tokenTicker,
        expectedChainTicker = supported.chainTicker,
        actual = actual
    )
}

private fun requireSelectableAsset(supportedAssets: List<SwapAsset>?, selectedAsset: SwapAsset): SwapAsset {
    require(SwapPriceMarkets.reference(selectedAsset) != null && !selectedAsset.isZCashAsset) {
        "Swap quote selection does not match a pinned counter-asset"
    }
    return requireNotNull(SwapPriceMarkets.findAsset(supportedAssets, selectedAsset.assetId)) {
        "Swap quote selection is unavailable in the current catalog"
    }
}

private fun requireMatchingAddress(
    name: String,
    type: SwapQuoteMismatchType,
    expected: String,
    actual: String
) {
    if (expected != actual) {
        throw SwapQuoteMismatchException.Rejected(
            type = type,
            message = "Swap quote address mismatch: expected $name=$expected but quote returned $actual"
        )
    }
}

/**
 * The same rejection in its reported form, carrying what the mismatch report needs — the quote id support
 * can hand the swap provider, the provider itself and both assets of the request — attached as a new
 * immutable rejection, leaving the thrown one untouched. This is the only place a
 * [SwapQuoteMismatchException.Reported] is built, so every rejection the mismatch sheet sees has all
 * three non-null. The quote only exists once the data source returned it, and whatever the data source
 * already resolved wins; a rejection that never saw a quote is reported against the only provider the app
 * swaps with.
 */
private fun SwapQuoteMismatchException.withQuoteReportContext(
    receivedQuote: SwapQuote?,
    originAsset: SwapAsset,
    destinationAsset: SwapAsset
): SwapQuoteMismatchException.Reported =
    withReportContext(
        depositAddress = depositAddress ?: receivedQuote?.depositAddress?.address,
        provider = provider ?: receivedQuote?.provider ?: NEAR_SWAP_PROVIDER,
        originAsset = originAsset,
        destinationAsset = destinationAsset
    )
