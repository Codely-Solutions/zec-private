package co.electriccoin.zcash.ui.common.repository

import android.os.SystemClock
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.resolveIsEndpointCustom
import co.electriccoin.zcash.ui.common.datasource.resolveIsServerSelectionAutomatic
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.provider.rawWalletBalances
import co.electriccoin.zcash.ui.common.provider.retainWhileWalletExists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

interface AutomaticServerRepository {
    val isServerAutomatic: Flow<Boolean>

    suspend fun isServerAutomatic(): Boolean

    suspend fun isServerCustom(): Boolean

    fun init()
}

class AutomaticServerRepositoryImpl(
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val applicationStateProvider: ApplicationStateProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val lightWalletEndpointProvider: LightWalletEndpointProvider,
    private val walletServerSelectionProvider: WalletServerSelectionProvider,
    private val evaluationInterval: EvaluationInterval,
    private val walletTransport: WalletTransportAccess = WalletTransportAccess(),
) : AutomaticServerRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isAppInTransactionState: Boolean
        get() =
            zashiProposalRepository.isShieldSubmissionActive ||
                zashiProposalRepository.transactionProposal.value != null ||
                zashiProposalRepository.submitState.value != null ||
                keystoneProposalRepository.transactionProposal.value != null ||
                keystoneProposalRepository.submitState.value != null ||
                ledgerProposalPipeline.transactionProposal.value != null ||
                ledgerProposalPipeline.submitState.value != null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val walletBalances =
        synchronizerProvider
            .rawWalletBalances()
            .retainWhileWalletExists(persistableWalletProvider)

    override val isServerAutomatic: Flow<Boolean> =
        walletServerSelectionProvider.serverSelection
            .filterNotNull()
            .map { it.isAutomatic() }
            .distinctUntilChanged()

    override suspend fun isServerAutomatic(): Boolean =
        walletServerSelectionProvider.getServerSelection().isAutomatic()

    private fun WalletServerSelection?.isAutomatic() =
        resolveIsServerSelectionAutomatic(
            isAutomaticPreference = this?.isAutomatic,
            currentEndpoint = this?.endpoint,
            knownEndpoints = lightWalletEndpointProvider.getEndpoints()
        )

    // Manual mode alone doesn't mean the endpoint is custom, since manual mode also covers pinning
    // one of our own bundled servers — see resolveIsEndpointCustom.
    override suspend fun isServerCustom(): Boolean {
        val selection = walletServerSelectionProvider.getServerSelection() ?: return false
        return !selection.isAutomatic() &&
            resolveIsEndpointCustom(selection.endpoint, lightWalletEndpointProvider.getEndpoints())
    }

    override fun init() {
        observeSwitchCandidates(applicationStateProvider.observeOnForeground()).launchIn(scope)
    }

    /**
     * The automatic-selection lane. Every foreground edge benchmarks the bundled servers through the SDK's
     * hysteresis policy, and a newer edge cancels an in-flight evaluation. A switch candidate is applied
     * only once the first local balance snapshot exists: a different endpoint rebuilds the Synchronizer,
     * and UI consumers retain that snapshot through the replacement.
     *
     * Nothing here may throw out of the flow, the upstream foreground signal included. This lane is
     * launched once for the lifetime of the process on a scope with no exception handler, so a single
     * failure - an unreachable server, a prefs read, a rejected argument - would otherwise take automatic
     * selection down until the next process start, and reach the thread's uncaught handler on its way out.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeSwitchCandidates(foregroundEdges: Flow<Unit>): Flow<LightWalletEndpoint> =
        foregroundEdges
            .catch { Twig.error(it) { "Automatic server: the foreground signal failed" } }
            .filter { evaluationInterval.hasElapsed() }
            .mapLatest { guarded("evaluate a server switch") { resolveSwitchCandidate() } }
            .filterNotNull()
            .onEach { candidate ->
                val endpoint = candidate.endpoint
                guarded("switch to ${endpoint.host}:${endpoint.port}") { applyServerSwitch(candidate) }
            }.map { it.endpoint }

    private suspend fun <T> guarded(
        what: String,
        block: suspend () -> T
    ): T? =
        runCatching { block() }
            .getOrElse {
                if (it is CancellationException) throw it
                Twig.error(it) { "Automatic server: failed to $what" }
                null
            }

    /**
     * Waits for the first local balance snapshot before releasing a candidate, but not indefinitely. In the
     * unhealthy case the wallet's current server has already failed two evaluations and is plausibly the
     * reason no snapshot exists, so the failover that matters most cannot be gated forever on the sync it
     * is meant to repair.
     */
    internal suspend fun resolveSwitchCandidate(): ServerSwitchCandidate? {
        val candidate = evaluateServerSwitch() ?: return null
        val endpoint = candidate.endpoint
        val balances = withTimeoutOrNull(BALANCE_SNAPSHOT_TIMEOUT) { walletBalances.filterNotNull().first() }
        if (balances == null) {
            Twig.warn {
                "Automatic server: no local balance snapshot within $BALANCE_SNAPSHOT_TIMEOUT, applying the " +
                    "switch to ${endpoint.host}:${endpoint.port} anyway"
            }
        } else {
            Twig.info { "Automatic server: local balance snapshot present, applying the switch" }
        }
        return candidate
    }

    @Suppress("ReturnCount")
    internal suspend fun evaluateServerSwitch(): ServerSwitchCandidate? {
        val version = walletServerSelectionProvider.writeVersion
        val current = walletServerSelectionProvider.getServerSelection() ?: return null
        if (!current.isAutomatic() || isAppInTransactionState) return null
        val candidates = lightWalletEndpointProvider.getEndpoints()
        if (candidates.isEmpty()) return null
        val synchronizer = synchronizerProvider.getSynchronizerOrNull() ?: return null
        if (isAppInTransactionState) return null
        return try {
            synchronizer.evaluateServerSwitch(
                current = current.endpoint,
                candidates = candidates,
                fetchThreshold = 5.seconds,
                blocksToFetch = 1
            )?.let { ServerSwitchCandidate(it, current, version) }
        } finally {
            evaluationInterval.markAttempted()
        }
    }

    /**
     * The SDK is told about the switch only once it has actually been applied: a declined or failed switch
     * that confirmed anyway would clear the SDK's consecutive-failure count and start its cooldown, leaving
     * a wallet on a dead server to earn its way out from scratch.
     *
     * The Synchronizer is taken before the endpoint is written, because writing it tears the current
     * Synchronizer down and rebuilds it. Which instance receives the confirmation does not matter - the
     * hysteresis state it updates is process-wide and outlives any single Synchronizer.
     */
    private suspend fun applyServerSwitch(candidate: ServerSwitchCandidate) {
        val endpoint = candidate.endpoint
        if (isAppInTransactionState || endpoint !in lightWalletEndpointProvider.getEndpoints()) return
        val synchronizer: Synchronizer? = synchronizerProvider.getSynchronizerOrNull()
        walletTransport.ifIdleSwitch {
            if (isAppInTransactionState) return@ifIdleSwitch
            currentCoroutineContext().ensureActive()
            Twig.info { "Automatic server: switching to ${endpoint.host}:${endpoint.port}" }
            val applied = withContext(NonCancellable) {
                walletServerSelectionProvider.storeServerSelection(
                    WalletServerSelection(endpoint, isAutomatic = true),
                    version = candidate.version,
                    expected = candidate.current,
                ).also { if (it) walletTransport.retire(synchronizer) }
            }
            if (applied) synchronizer?.confirmServerSwitch(endpoint)
        }
    }
}

internal data class ServerSwitchCandidate(
    val endpoint: LightWalletEndpoint,
    val current: WalletServerSelection,
    val version: Long,
)

/**
 * Rate limit for the automatic-selection lane. `observeOnForeground()` fires on every foreground edge and a
 * full evaluation opens a connection to every bundled host, so an app the user keeps switching in and out
 * of would otherwise re-benchmark the whole list each time, for a decision that is almost always "stay".
 *
 * What is marked is the attempt, not the success. An evaluation that throws or is cancelled by the next
 * foreground edge did the network work all the same, and a user toggling foreground faster than an
 * evaluation completes would otherwise never let one finish and never be rate limited at all.
 */
class EvaluationInterval(
    private val minimumInterval: Duration,
    private val timeSource: TimeSource = ElapsedRealtimeTimeSource
) {
    @Volatile
    private var lastAttemptAt: TimeMark? = null

    fun hasElapsed(): Boolean = lastAttemptAt?.let { it.elapsedNow() >= minimumInterval } ?: true

    fun markAttempted() {
        lastAttemptAt = timeSource.markNow()
    }
}

/**
 * `TimeSource.Monotonic` is `System.nanoTime()`, which does not advance while the device is in deep sleep:
 * a phone asleep overnight accrues almost no elapsed time, so the morning foreground edge would still be
 * inside the evaluation interval and automatic selection would sit out the whole morning.
 * `SystemClock.elapsedRealtime()` counts sleep, which is what this wall-clock rate limit needs.
 */
object ElapsedRealtimeTimeSource : TimeSource {
    override fun markNow(): TimeMark = ElapsedRealtimeMark(SystemClock.elapsedRealtime())
}

private class ElapsedRealtimeMark(
    private val startedAt: Long
) : TimeMark {
    override fun elapsedNow(): Duration = (SystemClock.elapsedRealtime() - startedAt).milliseconds
}

internal val MINIMUM_EVALUATION_INTERVAL = 10.minutes

/**
 * Cap on the wait for the first local balance snapshot before a switch is applied.
 */
private val BALANCE_SNAPSHOT_TIMEOUT = 30.seconds
