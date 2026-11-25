package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.model.FastestServersState
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.IsIronwoodAnnouncementShownStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.provider.eraseInactiveSdkData
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface WalletRepository {
    val walletInitialization: StateFlow<WalletInitializationState>

    val secretState: StateFlow<SecretState>

    val isIronwoodAnnouncementShown: StateFlow<Boolean?>

    val fastestEndpoints: StateFlow<FastestServersState>

    val walletRestoringState: StateFlow<WalletRestoringState>

    fun createNewWallet()

    fun continueWalletSetup()

    fun retryWalletInitialization()

    suspend fun markIronwoodAnnouncementShown()

    fun restoreWallet(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight,
        enableTor: Boolean = false
    ): Deferred<Unit>

    fun init()

    fun refreshFastestServers()
}

// Initialization admission and interrupted-setup recovery share the same wallet mutation boundary.
@Suppress("TooManyFunctions")
class WalletRepositoryImpl(
    configurationRepository: ConfigurationRepository,
    private val application: Application,
    private val lightWalletEndpointProvider: LightWalletEndpointProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val restoreTimestampDataSource: RestoreTimestampDataSource,
    private val walletRestoringStateProvider: WalletRestoringStateProvider,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider,
    private val isIronwoodAnnouncementShownStorageProvider: IsIronwoodAnnouncementShownStorageProvider,
    private val sdkEncryptedPreferenceRecoveryProvider: SdkEncryptedPreferenceRecoveryProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val resetProgressStore: ResetProgressStore,
) : WalletRepository {
    /**
     * Scope the repository's `stateIn`-shared flows run on. Captured by those flows at
     * construction time, so unlike [scope] it is never reassigned for tests — only exposed so
     * test teardown can cancel it too.
     */
    @VisibleForTesting
    internal val sharingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Serializes startup repair with wallet creation and restore.
     */
    private val walletMutation = Mutex()

    private val initialization = MutableStateFlow(WalletInitializationState.IDLE)
    private val startupRecovery = MutableStateFlow(WalletInitializationState.CHECKING)

    // Keep the accepted operation across failures, including failures after the wallet was stored.
    private val initializationLock = Any()
    private var acceptedInitialization: WalletInitializationOperation? = null
    private var activeInitialization: Deferred<Unit>? = null

    /**
     * Scope the repository's fire-and-forget jobs run on. A test seam: unit tests replace it
     * with a test dispatcher before invoking any method.
     */
    @set:RestrictTo(RestrictTo.Scope.TESTS)
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val refreshFastestServersRequest = MutableSharedFlow<Unit>()

    // Android can publish a preference value before the disk write finishes.
    private val onboardingStateBeforeCommit = MutableStateFlow<OnboardingState?>(null)
    private val storedByCurrentSetup = MutableStateFlow(false)

    private val onboardingState =
        flow {
            emitAll(
                StandardPreferenceKeys.ONBOARDING_STATE.observe(standardPreferenceProvider()).map { persistedNumber ->
                    OnboardingState.fromNumber(persistedNumber)
                }
            )
        }
            .combine(onboardingStateBeforeCommit) { state, _ ->
                onboardingStateBeforeCommit.value ?: state
            }

    private val onboardingWithWallet = onboardingState.combine(persistableWalletProvider.persistableWallet) {
        state, wallet -> state to (wallet != null)
    }

    override val walletInitialization =
        combine(
            initialization,
            startupRecovery,
            onboardingWithWallet
        ) { operation, recovery, (state, hasWallet) ->
            when {
                operation != WalletInitializationState.IDLE -> operation
                !hasWallet && state.requiresWalletlessRecovery() ->
                    recovery.takeUnless { it == WalletInitializationState.IDLE } ?: WalletInitializationState.CHECKING
                hasWallet && state != OnboardingState.READY -> WalletInitializationState.INCOMPLETE
                else -> WalletInitializationState.IDLE
            }
        }.stateIn(sharingScope, SharingStarted.Eagerly, WalletInitializationState.CHECKING)

    /**
     * READY additionally requires a stored wallet ([resolveSecretState]), because the plain
     * onboarding flag and the encrypted wallet store can diverge when the encrypted store is
     * recreated after provable corruption (MOB-1836). This flow never catches: a transient
     * Keystore failure surfacing here is left to crash the sharing coroutine, the same terminal
     * outcome the app already reaches today via [co.electriccoin.zcash.ui.common.provider.SynchronizerProvider]
     * collecting the same underlying store, rather than misrouting an intact-but-unreadable store
     * to onboarding.
     */
    override val secretState: StateFlow<SecretState> =
        combine(
            configurationRepository.configurationFlow,
            onboardingWithWallet,
            storedByCurrentSetup
        ) { config, (onboardingState, hasWallet), _ ->
            resolveSecretState(
                isConfigurationLoaded = config != null,
                onboardingState = onboardingState,
                hasWallet = hasWallet,
                storedByCurrentSetup = storedByCurrentSetup.value
            )
        }.stateIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = SecretState.LOADING
        )

    override val isIronwoodAnnouncementShown: StateFlow<Boolean?> =
        isIronwoodAnnouncementShownStorageProvider
            .observe()
            .stateIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private var previousFastestEndpoints: FastestServersState? = null

    @Suppress("ComplexCondition", "ReturnCount")
    @OptIn(ExperimentalCoroutinesApi::class)
    override val fastestEndpoints =
        refreshFastestServersRequest
            .onStart { emit(Unit) }
            .flatMapLatest {
                var synchronizerEmitted = false

                synchronizerProvider
                    .synchronizer
                    .mapLatest { synchronizer ->
                        val previousState = previousFastestEndpoints
                        val result =
                            if (synchronizer == null || (
                                    synchronizerEmitted &&
                                        !previousState?.servers.isNullOrEmpty() &&
                                        !previousState.isLoading
                                )
                            ) {
                                null
                            } else {
                                synchronizer
                            }

                        if (synchronizer != null) {
                            synchronizerEmitted = true
                        }

                        result
                    }
            }.flatMapLatest { synchronizer ->
                synchronizer
                    ?.getFastestServers(lightWalletEndpointProvider.getEndpoints())
                    ?.map {
                        when (it) {
                            FastestServersResult.Measuring -> {
                                previousFastestEndpoints?.copy(isLoading = true)
                                    ?: FastestServersState(servers = null, isLoading = true)
                            }

                            is FastestServersResult.Validating -> {
                                FastestServersState(servers = it.servers, isLoading = true)
                            }

                            is FastestServersResult.Done -> {
                                FastestServersState(servers = it.servers, isLoading = false)
                            }
                        }
                    } ?: flowOf(
                    previousFastestEndpoints ?: FastestServersState(
                        servers = emptyList(),
                        isLoading = false
                    )
                )
            }.onEach {
                previousFastestEndpoints = it
            }.stateIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = FastestServersState(servers = emptyList(), isLoading = true)
            )

    override val walletRestoringState: StateFlow<WalletRestoringState> =
        walletRestoringStateProvider
            .observe()
            .stateIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = WalletRestoringState.NONE
            )

    @Suppress("TooGenericExceptionCaught")
    override fun init() {
        scope.launch {
            try {
                walletMutation.withLock {
                    val version = persistableWalletProvider.writeVersion
                    val wallet = persistableWalletProvider.getPersistableWallet()
                    val onboarding = readOnboardingState()
                    migrateDecommissionedEndpointIfNeeded(wallet, version)
                    recoverMissingWalletIfNeeded(wallet, onboarding)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error { "Startup wallet consistency checks failed: ${e::class.simpleName}" }
            }
        }
    }

    private suspend fun migrateDecommissionedEndpointIfNeeded(wallet: PersistableWallet?, version: Long) {
        if (wallet == null) return
        if (wallet.endpoint.host in lightWalletEndpointProvider.getDecommissionedHosts()) {
            val replacement = wallet.copy(endpoint = lightWalletEndpointProvider.getDefaultEndpoint())
            persistableWalletProvider.storeIfCurrent(wallet, replacement, version)
        }
    }

    /** Called under [walletMutation] before setup can store a replacement wallet. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recoverMissingWalletIfNeeded(
        wallet: PersistableWallet?,
        onboardingState: OnboardingState
    ) {
        if (resetProgressStore.isPending.value || !onboardingState.requiresWalletlessRecovery() ||
            (onboardingState == OnboardingState.READY && wallet != null)
        ) return

        startupRecovery.value = WalletInitializationState.RUNNING
        try {
            if (onboardingState != OnboardingState.CLEARING_STALE_DATA) {
                persistOnboardingStateInternal(OnboardingState.CLEARING_STALE_DATA)
            }
            if (wallet == null) {
                sdkEncryptedPreferenceRecoveryProvider.ensureReadable()
                // Recheck after Keystore repair in case an external wallet writer won the race.
                if (persistableWalletProvider.getPersistableWallet() == null) {
                    eraseInactiveSdkData(application, ZcashNetwork.fromResources(application))
                }
            }
            currentCoroutineContext().ensureActive()
            persistOnboardingStateInternal(OnboardingState.NONE)
            startupRecovery.value = WalletInitializationState.IDLE
        } catch (e: CancellationException) {
            startupRecovery.value = WalletInitializationState.FAILED
            throw e
        } catch (e: Exception) {
            startupRecovery.value = WalletInitializationState.FAILED
            GlobalCrashReporter.reportCaughtException(IllegalStateException("Wallet data recovery failed"))
            throw e
        }
    }

    private suspend fun persistWalletInternal(persistableWallet: PersistableWallet) {
        storedByCurrentSetup.value = true
        persistableWalletProvider.store(persistableWallet)
    }

    override fun createNewWallet() {
        var candidate: PersistableWallet? = null
        initializeWallet(WalletInitializationRequest.Create) { stored, _ ->
            val accepted = candidate
            if (stored != null && (accepted == null || !stored.hasSameIdentityAs(accepted))) {
                throw WalletInitializationConflictException()
            }
            val zcashNetwork = ZcashNetwork.fromResources(application)
            val newWallet =
                stored ?: accepted ?: PersistableWallet.new(
                    application = application,
                    zcashNetwork = zcashNetwork,
                    endpoint = lightWalletEndpointProvider.getDefaultEndpoint(),
                    walletInitMode = WalletInitMode.NewWallet,
                )
            candidate = newWallet
            persistWalletInternal(newWallet)
            walletRestoringStateProvider.store(WalletRestoringState.INITIATING)
            persistOnboardingStateInternal(OnboardingState.READY)
        }
    }

    override fun continueWalletSetup() {
        initializeWallet(WalletInitializationRequest.Continue) { stored, version ->
            if (stored == null) throw WalletInitializationConflictException()
            finishExistingWalletInitialization(stored, version)
        }
    }

    override fun retryWalletInitialization() {
        synchronized(initializationLock) {
            val accepted = acceptedInitialization
            if (accepted != null && activeInitialization == null) {
                startWalletInitialization(accepted)
            } else if (startupRecovery.value == WalletInitializationState.FAILED) {
                init()
            }
        }
    }

    override suspend fun markIronwoodAnnouncementShown() {
        isIronwoodAnnouncementShownStorageProvider.store(true)
    }

    private suspend fun persistOnboardingStateInternal(onboardingState: OnboardingState) {
        onboardingStateBeforeCommit.value = when (onboardingState) {
            OnboardingState.READY -> OnboardingState.NONE
            OnboardingState.NONE -> OnboardingState.CLEARING_STALE_DATA
            OnboardingState.CLEARING_STALE_DATA -> OnboardingState.READY
            else -> null
        }
        StandardPreferenceKeys.ONBOARDING_STATE.putValue(
            preferenceProvider = standardPreferenceProvider(),
            newValue = onboardingState.toNumber()
        )
        onboardingStateBeforeCommit.value = null
    }

    private suspend fun readOnboardingState(): OnboardingState =
        onboardingStateBeforeCommit.value ?: OnboardingState.fromNumber(
            StandardPreferenceKeys.ONBOARDING_STATE.getValue(standardPreferenceProvider())
        )

    override fun refreshFastestServers() {
        scope.launch {
            if (!fastestEndpoints.value.isLoading) {
                refreshFastestServersRequest.emit(Unit)
            }
        }
    }

    override fun restoreWallet(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight,
        enableTor: Boolean
    ): Deferred<Unit> {
        val request = WalletInitializationRequest.Restore(network, seedPhrase, birthday, enableTor)
        var candidate: PersistableWallet? = null
        return initializeWallet(request) { stored, _ ->
            if (stored != null && (candidate == null || !request.matches(stored))) {
                throw WalletInitializationConflictException()
            }
            // Commit the accepted setting before the wallet can start a synchronizer.
            isTorEnabledStorageProvider.store(request.enableTor)
            val restoredWallet =
                stored ?: candidate ?: PersistableWallet(
                    network = network,
                    birthday = birthday,
                    endpoint = lightWalletEndpointProvider.getDefaultEndpoint(),
                    seedPhrase = seedPhrase,
                    walletInitMode = WalletInitMode.RestoreWallet,
                )
            candidate = restoredWallet
            persistWalletInternal(restoredWallet)
            walletRestoringStateProvider.store(WalletRestoringState.RESTORING)
            walletBackupFlagStorageProvider.store(true)
            restoreTimestampDataSource.getOrCreate()
            persistOnboardingStateInternal(OnboardingState.READY)
        }
    }

    private suspend fun finishExistingWalletInitialization(wallet: PersistableWallet, version: Long) {
        // A process can stop between the encrypted wallet write and onboarding metadata. Its seed already owns
        // this setup. Resume conservatively as a restore and preserve the existing backup flag.
        val replacement = wallet.copy(walletInitMode = WalletInitMode.RestoreWallet)
        if (!persistableWalletProvider.storeIfCurrent(wallet, replacement, version)) {
            throw WalletInitializationConflictException()
        }
        walletRestoringStateProvider.store(WalletRestoringState.RESTORING)
        restoreTimestampDataSource.getOrCreate()
        persistOnboardingStateInternal(OnboardingState.READY)
    }

    private fun initializeWallet(
        request: WalletInitializationRequest,
        operation: suspend (PersistableWallet?, Long) -> Unit
    ): Deferred<Unit> =
        synchronized(initializationLock) {
            val accepted = acceptedInitialization
            if (accepted != null && accepted.request != request) {
                CompletableDeferred<Unit>().apply { completeExceptionally(WalletInitializationConflictException()) }
            } else {
                activeInitialization ?: startWalletInitialization(
                    accepted ?: WalletInitializationOperation(request, operation)
                )
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun startWalletInitialization(accepted: WalletInitializationOperation): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        acceptedInitialization = accepted
        activeInitialization = result
        initialization.value = WalletInitializationState.RUNNING
        var failure: Exception? = null
        scope.launch {
            try {
                walletMutation.withLock {
                    val beforeRecovery = readOnboardingState()
                    recoverMissingWalletIfNeeded(persistableWalletProvider.getPersistableWallet(), beforeRecovery)
                    val version = persistableWalletProvider.writeVersion
                    val stored = persistableWalletProvider.getPersistableWallet()
                    if (readOnboardingState() != OnboardingState.READY || stored == null) {
                        accepted.operation(stored, version)
                    } else {
                        val request = accepted.request
                        if (request is WalletInitializationRequest.Restore && !request.matches(stored)) {
                            throw WalletInitializationConflictException()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletInitializationConflictException) {
                failure = e
            } catch (_: Exception) {
                // SDK and storage errors can contain sensitive material.
                Twig.error { "Wallet initialization failed; the accepted operation can be retried" }
                failure = IllegalStateException("Wallet initialization failed")
            }
        }.invokeOnCompletion { cause ->
            val error = failure ?: cause?.let { IllegalStateException("Wallet initialization was interrupted") }
            synchronized(initializationLock) {
                val canRetry = error != null && error !is WalletInitializationConflictException
                if (!canRetry) acceptedInitialization = null
                activeInitialization = null
                initialization.value =
                    if (canRetry) WalletInitializationState.FAILED else WalletInitializationState.IDLE
            }
            if (error == null) result.complete(Unit) else result.completeExceptionally(error)
        }
        return result
    }

    private class WalletInitializationOperation(
        val request: WalletInitializationRequest,
        val operation: suspend (PersistableWallet?, Long) -> Unit
    )

    private sealed interface WalletInitializationRequest {
        data object Create : WalletInitializationRequest

        data object Continue : WalletInitializationRequest

        data class Restore(
            val network: ZcashNetwork,
            val seedPhrase: SeedPhrase,
            val birthday: BlockHeight,
            val enableTor: Boolean
        ) : WalletInitializationRequest {
            fun matches(wallet: PersistableWallet): Boolean =
                network == wallet.network && seedPhrase == wallet.seedPhrase && birthday == wallet.birthday

            override fun toString() = "Restore"
        }
    }
}

private fun PersistableWallet.hasSameIdentityAs(other: PersistableWallet): Boolean =
    network == other.network && seedPhrase == other.seedPhrase && birthday == other.birthday

class WalletInitializationConflictException :
    IllegalStateException("A different wallet setup already owns initialization")

enum class WalletInitializationState {
    CHECKING,
    IDLE,
    INCOMPLETE,
    RUNNING,
    FAILED
}

/**
 * READY requires both the plain onboarding flag and a stored wallet, because the two live in
 * separate preference files and can diverge when the encrypted store is recreated after provable
 * corruption (MOB-1836).
 *
 * A stored wallet from an earlier setup must pass app-access authentication before onboarding
 * continues. A wallet stored by the current setup retains the first-run onboarding grant.
 */
internal fun resolveSecretState(
    isConfigurationLoaded: Boolean,
    onboardingState: OnboardingState,
    hasWallet: Boolean,
    storedByCurrentSetup: Boolean = false,
): SecretState =
    when {
        !isConfigurationLoaded -> SecretState.LOADING
        onboardingState == OnboardingState.READY && hasWallet -> SecretState.READY
        hasWallet && !storedByCurrentSetup -> SecretState.INCOMPLETE
        else -> SecretState.NONE
    }

private fun OnboardingState.requiresWalletlessRecovery() =
    this == OnboardingState.READY || this == OnboardingState.CLEARING_STALE_DATA
