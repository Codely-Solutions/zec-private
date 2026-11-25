package co.electriccoin.zcash.ui.common.repository

import androidx.lifecycle.Lifecycle
import cash.z.ecc.android.sdk.Synchronizer
import androidx.lifecycle.LifecycleEventObserver
import co.electriccoin.zcash.global.WalletMigrationSyncGate
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

interface ApplicationStateRepository {
    fun init()
}

class ApplicationStateRepositoryImpl(
    private val appLifecycle: Lifecycle,
    private val applicationStateProvider: ApplicationStateProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val migrationSyncGate: WalletMigrationSyncGate,
    private val resetProgressStore: ResetProgressStore,
) : ApplicationStateRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun init() {
        observeApplicationLifecycle()
        notifySynchronizerOnApplicationState()
    }

    private fun observeApplicationLifecycle() {
        appLifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                applicationStateProvider.onApplicationLifecycleChanged(event)
            }
        )
    }

    private fun notifySynchronizerOnApplicationState() {
        scope.launch {
            relaySynchronizerLifecycle(
                synchronizerProvider.synchronizer,
                applicationStateProvider.isInForeground,
                migrationSyncGate.isBlocked,
                resetProgressStore.isPending,
            )
        }
    }
}

internal suspend fun relaySynchronizerLifecycle(
    synchronizers: Flow<Synchronizer?>,
    applicationForeground: Flow<Boolean>,
    migrationBlocked: Flow<Boolean>,
    resetPending: Flow<Boolean>,
) {
    // Withhold app-requested starts while a migration or reset owns the wallet.
    combine(synchronizers, applicationForeground, migrationBlocked, resetPending) {
        sync, foreground, migration, reset ->
        sync to (foreground && !migration && !reset)
    }.distinctUntilChanged()
        .collect { (synchronizer, runInForeground) ->
            if (runInForeground) {
                synchronizer?.onForeground()
            } else {
                synchronizer?.onBackground()
            }
        }
}
