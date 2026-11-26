package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.global.WalletMigrationSyncGate
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.migration.MigrationSyncedHook
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

interface SynchronizerProvider {
    val error: StateFlow<SynchronizerError?>

    val synchronizer: StateFlow<Synchronizer?>

    val isSeedMismatch: StateFlow<Boolean>

    /** True after wallet-global activity for this engine's observed synced state was recorded. */
    fun hasRecordedSync(engine: Synchronizer): Boolean = false

    /**
     * Get synchronizer and wait for it to be ready.
     */
    suspend fun getSynchronizer(): Synchronizer

    /**
     * Returns null if there is no persistable wallet, otherwise waits for the loaded synchronizer.
     */
    suspend fun getSynchronizerOrNull(): Synchronizer?

    suspend fun getVotingWalletDbPath(): String

    fun resetSynchronizer()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SynchronizerProviderImpl(
    private val walletCoordinator: WalletCoordinator,
    private val persistableWalletProvider: PersistableWalletProvider,
    // Lazy on purpose: the hook's implementation resolves (via AccountDataSource) back to
    // SynchronizerProvider — eager constructor injection forms a Koin resolution cycle that
    // crashes startup with a StackOverflowError (caught on-emulator 2026-07-28). Resolved at
    // first hook fire instead, when the graph is fully built.
    private val migrationSyncedHook: Lazy<MigrationSyncedHook>,
    migrationSyncGate: WalletMigrationSyncGate,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    provisioning: WalletProvisioning? = null,
) : SynchronizerProvider {
    private val engineError = MutableStateFlow<SynchronizerError?>(null)
    override val error = combine(engineError, migrationSyncGate.error) { engine, migration ->
        migration ?: engine
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val isSeedMismatch: StateFlow<Boolean> = walletCoordinator.isSeedMismatch

    @Volatile
    private var recordedSync: Synchronizer? = null

    override fun hasRecordedSync(engine: Synchronizer): Boolean =
        recordedSync === engine && walletCoordinator.synchronizer.value === engine

    @OptIn(ExperimentalCoroutinesApi::class)
    override val synchronizer: StateFlow<Synchronizer?> =
        walletCoordinator
            .synchronizer
            .flatMapLatest { synchronizer ->
                channelFlow {
                    if (synchronizer != null) {
                        val pipeline = initializeErrorHandling(synchronizer)

                        launch {
                            pipeline.collect { new ->
                                engineError.update { new }
                            }
                        }
                    }

                    send(synchronizer)
                    awaitClose {
                        synchronizer?.onProcessorErrorHandler = null
                        synchronizer?.onProcessorErrorResolved = null
                        synchronizer?.onSetupErrorHandler = null
                        synchronizer?.onChainErrorHandler = null
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = walletCoordinator.synchronizer.value
            )

    init {
        if (provisioning != null) {
            scope.launch(Dispatchers.IO) {
                synchronizer.flatMapLatest { engine ->
                    engine?.accountsFlow?.filterNotNull()?.map { engine to it } ?: emptyFlow()
                }.collectLatest { (engine, accounts) ->
                    repeat(2) { attempt ->
                        val confirmation = runCatchingRecoverable {
                            provisioning.confirm(engine, accounts, walletCoordinator.keySource, isCurrentEngine = {
                                synchronizer.value === engine && walletCoordinator.synchronizer.value === engine
                            })
                        }
                        if (confirmation.isSuccess) return@collectLatest
                        if (attempt == 0) delay(1.seconds)
                    }
                    Twig.warn { "Wallet provisioning confirmation is still pending" }
                }
            }
        }
        val pendingReconciliation = Channel<Synchronizer>(Channel.CONFLATED)
        scope.launch {
            for (engine in pendingReconciliation) {
                if (walletCoordinator.synchronizer.value === engine) {
                    runCatchingRecoverable { migrationSyncedHook.value.onSynced() }.onFailure {
                        Twig.warn { "Migration sync hook failed (${it.javaClass.simpleName})" }
                    }
                }
            }
        }
        scope.launch {
            walletCoordinator.synchronizer
                .flatMapLatest { engine -> engine?.status?.map { engine to it } ?: emptyFlow() }
                .distinctUntilChanged()
                .collect { (engine, status) ->
                    recordedSync = null
                    if (status == Synchronizer.Status.SYNCED && walletCoordinator.synchronizer.value === engine) {
                        val outcome = runCatchingRecoverable { lastNetworkActivity.stampNow() }.onFailure {
                            Twig.warn { "Wallet network activity unavailable (${it.javaClass.simpleName})" }
                        }
                        if (outcome.isSuccess || lastNetworkActivity.latestObserved != null) {
                            recordedSync = engine
                        }
                        pendingReconciliation.trySend(engine)
                    }
                }
        }
    }

    override suspend fun getSynchronizer(): Synchronizer =
        withContext(Dispatchers.IO) {
            synchronizer
                .filterNotNull()
                .first()
        }

    override suspend fun getSynchronizerOrNull(): Synchronizer? =
        withContext(Dispatchers.IO) {
            if (persistableWalletProvider.getPersistableWallet() == null) {
                null
            } else {
                getSynchronizer()
            }
        }

    override suspend fun getVotingWalletDbPath(): String =
        getSynchronizer().getWalletDbPathForVoting()

    override fun resetSynchronizer() {
        walletCoordinator.resetSynchronizer()
    }

    private fun initializeErrorHandling(synchronizer: Synchronizer): Flow<SynchronizerError?> {
        val pipeline = MutableStateFlow<SynchronizerError?>(null)

        // synchronizer.onCriticalErrorHandler = { error ->
        //     Twig.error { "WALLET - Error Critical: $error" }
        //     pipeline.update { SynchronizerError.Critical(error)}
        //     false
        // }
        synchronizer.onProcessorErrorHandler = { error ->
            Twig.error { "Wallet processor failed" }
            pipeline.update { SynchronizerError.Processor(error) }
            true
        }
        synchronizer.onProcessorErrorResolved = {
            Twig.error { "WALLET - Processor error resolved" }
            pipeline.update { null }
        }
        synchronizer.onSetupErrorHandler = { error ->
            Twig.error { "Wallet synchronizer setup failed" }
            pipeline.update { SynchronizerError.Setup(error) }
            false
        }
        synchronizer.onChainErrorHandler = { x, y ->
            Twig.error { "WALLET - Error Chain: $x, $y" }
            pipeline.update { SynchronizerError.Chain(x, y) }
        }

        return pipeline
    }
}

/**
 * MOB-1723: retains the last non-null value of UI-facing wallet-derived state across the gap in
 * which an automatic server switch (or any other rebuild) tears down and reconstructs the
 * Synchronizer, so screens do not collapse to a loading state mid-rebuild. Clears to null as soon
 * as the wallet is removed, so a deleted wallet's state is never shown. This retains plain data
 * only — never the Synchronizer instance itself, whose close() cancels its coroutineScope and
 * disposes its clients, making a retained instance a dead object with silently-frozen flows.
 *
 * MOB-1664 is the balance-flash precedent this generalizes: [Synchronizer.walletBalances] resets
 * to `null` on every rebuild, which used to flash balance displays to 0.000 for the few seconds a
 * new instance takes to re-establish itself.
 *
 * The retained value lives in this operator's [kotlinx.coroutines.flow.scan] accumulator, which is
 * per-collection: under [kotlinx.coroutines.flow.stateIn] it must be paired with
 * [kotlinx.coroutines.flow.SharingStarted.Eagerly], never `WhileSubscribed` — a `WhileSubscribed`
 * restart tears down that collection and starts a fresh one, discarding the retained value and
 * re-emitting the seed null.
 */
internal fun <T : Any> Flow<T?>.retainWhileWalletExists(
    persistableWalletProvider: PersistableWalletProvider
): Flow<T?> =
    combine(
        this,
        persistableWalletProvider.persistableWallet.map { it != null }.distinctUntilChanged(),
    ) { current, hasWallet ->
        current to hasWallet
    }.scan(null as T?) { retained, (current, hasWallet) ->
        resolveRetained(retained = retained, current = current, hasWallet = hasWallet)
    }

internal fun <T : Any> resolveRetained(
    retained: T?,
    current: T?,
    hasWallet: Boolean,
): T? =
    when {
        !hasWallet -> null
        current != null -> current
        else -> retained
    }

/**
 * The raw per-instance wallet balances, keyed by account. Null while the synchronizer is absent or
 * has not loaded a balance snapshot yet. Retention is the caller's choice — this is the shared seam
 * every balance-observing use case reads from instead of copying its own
 * `synchronizer.flatMapLatest { it?.walletBalances ?: flowOf(null) }`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun SynchronizerProvider.rawWalletBalances(): Flow<Map<AccountUuid, AccountBalance>?> =
    synchronizer.flatMapLatest { it?.walletBalances ?: flowOf(null) }
