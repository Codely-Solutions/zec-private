package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.work.MigrationScheduler
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Preserves an existing wake or schedules the next transfer after a manual submission.
 * If every transfer was sent, the next wake checks mining after the SDK's privacy window.
 */
class ScheduleNextMigrationWindowUseCase(
    private val getMigrationSnapshot: GetMigrationSnapshotUseCase,
    private val migrationScheduler: MigrationScheduler,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
) {
    suspend operator fun invoke(accountKeyId: String? = null) {
        val owner = accountKeyId ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        val version = MigrationScheduler.wakeVersion(owner)
        val snapshot = getMigrationSnapshot(owner) ?: return
        val next = snapshot.nextPending
        val delay = when {
            next != null -> delayUntil(next.scheduledAt.epochSeconds)
            snapshot.isComplete && !snapshot.areAllMined -> getOrchardMigrationSdk(owner)?.privacySyncBufferDuration()
            else -> null
        } ?: return
        migrationScheduler.scheduleIfMissing(owner, delay, version)
    }

    private fun delayUntil(epochSeconds: Long): Duration {
        val remaining = epochSeconds - Clock.System.now().epochSeconds
        return if (remaining <= 0) 0.seconds else remaining.seconds
    }
}
