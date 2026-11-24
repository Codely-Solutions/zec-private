package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class UpdateSwapActivityMetadataUseCase(
    accountDataSource: AccountDataSource,
    private val metadataRepository: MetadataRepository,
    private val getSwapStatus: GetSwapStatusUseCase,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val active = MutableStateFlow<PollingSession?>(null)
    private val selectedAccounts = accountDataSource.selectedAccount
        .distinctUntilChangedBy { it?.sdkAccount?.accountUuid }
    private val awaitingSession = ConcurrentHashMap.newKeySet<Pair<AccountUuid, String>>()

    init {
        scope.launch {
            selectedAccounts
                .collectLatest { owner ->
                    if (owner != null) {
                        supervisorScope {
                            val session = PollingSession(owner, this)
                            active.value = session
                            try {
                                awaitCancellation()
                            } finally {
                                active.compareAndSet(session, null)
                            }
                        }
                    }
                }
        }
    }

    operator fun invoke(activity: ActivityData) {
        val (metadata, owner) = when (activity) {
            is ActivityData.BySwap -> activity.swap to activity.swap.ownerAccount?.sdkAccount?.accountUuid
            is ActivityData.ByTransaction -> activity.metadata.swapMetadata to activity.transaction.accountUuid
        }
        if (metadata != null && !metadata.status.isTerminal && owner != null) {
            enqueueWhenActive(owner, metadata.depositAddress)
        }
    }

    private fun enqueueWhenActive(owner: AccountUuid, depositAddress: String) {
        val request = owner to depositAddress
        if (!awaitingSession.add(request)) return
        scope.launch {
            try {
                val (selected, session) = combine(selectedAccounts, active, ::Pair).first { (account, polling) ->
                    account?.sdkAccount?.accountUuid != owner || polling?.account?.sdkAccount?.accountUuid == owner
                }
                if (selected?.sdkAccount?.accountUuid == owner) session?.enqueue(depositAddress)
            } finally {
                awaitingSession.remove(request)
            }
        }
    }

    private inner class PollingSession(val account: WalletAccount, private val scope: CoroutineScope) {
        private val pending = mutableSetOf<String>()
        private val requests = Mutex()

        @Synchronized
        fun enqueue(depositAddress: String) {
            if (pending.add(depositAddress)) {
                scope.launch { poll(depositAddress) }.invokeOnCompletion {
                    synchronized(this) { pending.remove(depositAddress) }
                }
            }
        }

        private suspend fun poll(depositAddress: String) {
            while (true) {
                val (pendingStatus, started) = requests.withLock {
                    val started = timeSource.markNow()
                    val keepPolling = runCatchingRecoverable {
                        val saved = metadataRepository.getSwapMetadata(depositAddress, account)
                        if (saved == null || saved.status.isTerminal) {
                            false
                        } else {
                            val status = getSwapStatus.refreshOnce(saved)
                            delay(SHARED_POLL_SPACING)
                            status.savedMetadata?.status?.isTerminal != true &&
                                status.status?.status?.isTerminal != true
                        }
                    }.getOrElse {
                        Twig.warn { "Activity status refresh failed: ${it::class.simpleName}" }
                        true
                    }
                    keepPolling to started
                }
                if (!pendingStatus) return
                delay(maxOf(SHARED_POLL_SPACING, POLL_INTERVAL - started.elapsedNow()))
            }
        }
    }

    private companion object {
        val POLL_INTERVAL = 15.seconds
        val SHARED_POLL_SPACING = 1.seconds
    }
}
