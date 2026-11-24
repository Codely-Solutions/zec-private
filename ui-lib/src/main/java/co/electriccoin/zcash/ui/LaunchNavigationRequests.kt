package co.electriccoin.zcash.ui

import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds sanitized app-launch requests until the corresponding screen graph is accessible. */
class LaunchNavigationRequests {
    private val pending = MutableStateFlow(emptyList<Request>())
    private var restoredExternalScans = false
    internal val changes = pending.asStateFlow()

    internal fun requestGiftScan() =
        offer(Kind.GIFT_SCAN, NavigationCommand.Forward(listOf(ScanGiftCardArgs(isFromExternalLink = true))))

    internal fun requestThirdPartyScan() =
        offer(Kind.THIRD_PARTY_SCAN, NavigationCommand.Forward(listOf(ThirdPartyScan)))

    internal fun saveExternalScans(): ArrayList<String> = pending.value.mapNotNullTo(ArrayList()) { request ->
        when (request.kind) {
            Kind.GIFT_SCAN, Kind.THIRD_PARTY_SCAN -> request.kind.name
            else -> null
        }
    }

    internal fun restoreExternalScans(saved: List<String>) {
        // The same process already owns the current queue, including requests it consumed after saving state.
        if (restoredExternalScans) return
        restoredExternalScans = true
        for (kind in saved) {
            when (kind) {
                Kind.GIFT_SCAN.name -> requestGiftScan()
                Kind.THIRD_PARTY_SCAN.name -> requestThirdPartyScan()
            }
        }
    }

    fun requestMigrationProgress(command: NavigationCommand.ReplaceAll) = offer(Kind.MIGRATION_PROGRESS, command)

    fun requestMigrationRecovery(command: NavigationCommand.ReplaceAll) = offer(Kind.MIGRATION_RECOVERY, command)

    fun clearMigrationRequests() {
        pending.update { queue ->
            queue.filterNot { it.kind == Kind.MIGRATION_PROGRESS || it.kind == Kind.MIGRATION_RECOVERY }
        }
    }

    internal suspend fun deliver(
        walletReady: Boolean,
        hasAppAccess: () -> Boolean,
        navigate: suspend (NavigationCommand) -> Unit,
    ) {
        for (request in pending.value) {
            if (!hasAppAccess()) return
            if (walletReady || request.kind == Kind.THIRD_PARTY_SCAN) {
                val queue = pending.value
                if (request in queue && pending.compareAndSet(queue, queue - request)) {
                    navigate(request.command)
                }
            }
        }
    }

    private fun offer(kind: Kind, command: NavigationCommand) {
        pending.update { queue ->
            val request = Request(kind, command)
            if (request in queue) queue else queue.filterNot { it.kind == kind } + request
        }
    }

    internal data class Request(val kind: Kind, val command: NavigationCommand)

    internal enum class Kind { THIRD_PARTY_SCAN, GIFT_SCAN, MIGRATION_PROGRESS, MIGRATION_RECOVERY }
}
