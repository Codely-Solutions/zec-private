package co.electriccoin.zcash.ui.common.repository

import androidx.lifecycle.Lifecycle
import cash.z.ecc.android.sdk.Synchronizer
import androidx.lifecycle.LifecycleEventObserver
import co.electriccoin.zcash.global.WalletMigrationSyncGate
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
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
            )
        }
    }
}

internal suspend fun relaySynchronizerLifecycle(
    synchronizers: Flow<Synchronizer?>,
    applicationForeground: Flow<Boolean>,
    migrationBlocked: Flow<Boolean>,
) {
    // The SDK's migration pause guards bursts and resubmission. Foreground start is a separate API.
    combine(synchronizers, applicationForeground, migrationBlocked) { synchronizer, foreground, blocked ->
        synchronizer to (foreground && !blocked)
    }.distinctUntilChanged()
        .collect { (synchronizer, runInForeground) ->
            if (runInForeground) {
                synchronizer?.onForeground()
            } else {
                synchronizer?.onBackground()
            }
        }
}
