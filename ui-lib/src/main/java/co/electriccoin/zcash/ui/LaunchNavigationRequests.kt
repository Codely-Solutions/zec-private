package co.electriccoin.zcash.ui

import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Holds sanitized app-launch requests until the corresponding screen graph is accessible. */
class LaunchNavigationRequests {
    private val pending = MutableStateFlow(emptyList<Request>())
    private val delivery = Mutex()
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

    internal fun restoreExternalScans(saved: List<String>): Boolean {
        // The same process already owns the current queue, including requests it consumed after saving state.
        if (restoredExternalScans) return false
        restoredExternalScans = true
        for (kind in saved) {
            when (kind) {
                Kind.GIFT_SCAN.name -> requestGiftScan()
                Kind.THIRD_PARTY_SCAN.name -> requestThirdPartyScan()
            }
        }
        return true
    }

    fun beginMigrationProgress(ownerKey: String, runStepPending: Boolean = false): MigrationNotice =
        MigrationNotice(ownerKey, runStepPending).also { notice ->
            pending.update { queue ->
                queue.filterNot { it.kind == Kind.MIGRATION_PROGRESS } + Request(Kind.MIGRATION_PROGRESS, null, notice)
            }
        }

    val resolvingMigrationNotice: MigrationNotice?
        get() = pending.value.firstOrNull { it.kind == Kind.MIGRATION_PROGRESS && it.command == null }?.migrationNotice

    fun finishMigrationProgress(notice: MigrationNotice, command: NavigationCommand.ReplaceAll?) {
        pending.update { queue ->
            queue.mapNotNull { request ->
                if (request.migrationNotice !== notice) request else command?.let { request.copy(command = it) }
            }
        }
    }

    internal val migrationNoticeToSave: SavedMigrationNotice?
        get() = pending.value.firstNotNullOfOrNull { request ->
            request.migrationNotice?.let {
                SavedMigrationNotice(it.ownerKey, request.command == null && it.runStepPending)
            }
        }

    internal val pendingMigrationLookup: SavedMigrationNotice?
        get() = resolvingMigrationNotice?.let { SavedMigrationNotice(it.ownerKey, it.runStepPending) }

    fun requestMigrationRecovery(ownerKey: String, command: NavigationCommand.ReplaceAll) =
        offer(Kind.MIGRATION_RECOVERY, command, ownerKey)

    fun clearMigrationRequests(ownerKey: String? = null) {
        pending.update { queue ->
            queue.filterNot { request ->
                when (request.kind) {
                    Kind.MIGRATION_PROGRESS -> ownerKey == null || request.migrationNotice?.ownerKey == ownerKey
                    Kind.MIGRATION_RECOVERY -> ownerKey == null || request.recoveryOwnerKey == ownerKey
                    else -> false
                }
            }
        }
    }

    internal suspend fun deliver(
        walletReady: Boolean,
        hasAppAccess: () -> Boolean,
        navigate: suspend (NavigationCommand, isCurrent: () -> Boolean) -> Boolean,
    ) = delivery.withLock {
        for (request in pending.value) {
            if (!hasAppAccess()) return@withLock
            val command = request.command ?: continue
            if (walletReady || request.kind == Kind.THIRD_PARTY_SCAN) {
                val isCurrent = { hasAppAccess() && pending.value.any { it === request } }
                if (isCurrent() && navigate(command, isCurrent)) {
                    pending.update { queue -> queue.filterNot { it === request } }
                }
            }
        }
    }

    private fun offer(kind: Kind, command: NavigationCommand, recoveryOwnerKey: String? = null) {
        pending.update { queue ->
            val request = Request(kind, command, recoveryOwnerKey = recoveryOwnerKey)
            if (request in queue) queue else queue.filterNot { it.kind == kind } + request
        }
    }

    class MigrationNotice internal constructor(val ownerKey: String, val runStepPending: Boolean)

    internal data class SavedMigrationNotice(val ownerKey: String, val runStepPending: Boolean)

    internal data class Request(
        val kind: Kind,
        val command: NavigationCommand?,
        val migrationNotice: MigrationNotice? = null,
        val recoveryOwnerKey: String? = null,
    )

    internal enum class Kind { THIRD_PARTY_SCAN, GIFT_SCAN, MIGRATION_PROGRESS, MIGRATION_RECOVERY }
}
