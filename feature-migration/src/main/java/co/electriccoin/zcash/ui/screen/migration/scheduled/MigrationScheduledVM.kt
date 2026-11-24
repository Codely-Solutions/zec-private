package co.electriccoin.zcash.ui.screen.migration.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationBlocker
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.migration.LiveMigrationSnapshot
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferBlocker
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsBackgroundExecutionAvailableProvider
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPczts
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.work.MigrationDriveOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import co.electriccoin.zcash.ui.design.R as DesignR

@Suppress("LongParameterList")
class MigrationScheduledVM(
    private val args: MigrationScheduledArgs,
    private val getMigrationSnapshot: GetMigrationSnapshotUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val isBackgroundExecutionAvailableProvider: IsBackgroundExecutionAvailableProvider,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val pendingKeystonePczts: PendingKeystoneMigrationPcztsRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val selectedAccount: GetSelectedWalletAccountUseCase,
    private val drive: MigrationDriveOnce,
) : ViewModel() {
    private val loadLce = mutableLce<LiveMigrationSnapshot?>()

    // True while a completed Keystone batch's accumulated signatures are still being applied,
    // stored, and finalized — the last leg of that flow (Tor submit, schedule storage) has no
    // other feedback surface once the QR scanning UI is gone, so this screen renders it instead.
    // Defaults true so the snapshot flow below never races the init{} check below it.
    val isFinalizing = MutableStateFlow(true)
    val failureSheet = MutableStateFlow<MigrationTransferFailureState?>(null)
    val finalizationError = MutableStateFlow<StringResource?>(null)
    val needsKeystoneSignatures = MutableStateFlow(false)
    val homeRecoveryMessage = MutableStateFlow<StringResource?>(null)

    init {
        viewModelScope.launch { finalizeIfPendingKeystoneBatch() }
        // Fires exactly once — isFinalizing only ever transitions true -> false, never back.
        viewModelScope.launch {
            isFinalizing.first { !it }
            if (homeRecoveryMessage.value == null) retrySnapshot()
        }
    }

    private suspend fun finalizeIfPendingKeystoneBatch() {
        try {
            val accountKeyId = args.accountKeyId
            val sched = pendingSchedule.peek(accountKeyId)
            val pending = pendingKeystonePczts.peek(accountKeyId)
            if (sched == null || pending == null) {
                // Hot-wallet (Zashi) path — FinalizeMigrationScheduleUseCase already ran before
                // navigating here, so the schedule is already committed. Nothing left to do.
                isFinalizing.value = false
                return
            }
            checkNotNull(drive.withExclusiveAccess { finalizeSignedBatch(accountKeyId, sched, pending) }) {
                "Another migration step is in progress"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            migrationLog("MigrationScheduled: finalizeIfPendingKeystoneBatch failed: ${e::class.simpleName}")
            showFinalizationError(stringRes(DesignR.string.migrationScheduled_finalizeErrorMessage))
        }
    }

    private suspend fun finalizeSignedBatch(
        accountKeyId: String,
        schedule: MigrationSchedule,
        pending: PendingKeystoneMigrationPczts,
    ) {
        check(pending.isFullySigned) { "Keystone signatures are incomplete" }
        check(selectedAccount().sdkAccount.accountUuid.toStorageKeyId() == accountKeyId) {
            "The migration schedule belongs to a different account"
        }
        val sdk = checkNotNull(getOrchardMigrationSdk(accountKeyId)) { "Migration account is unavailable" }
        if (sdk.getMigrationStateUnreconciled() !is MigrationState.InProgress) {
            returnToHomeForRecovery(accountKeyId, schedule, pending)
            return
        }
        val states = checkNotNull(sdk.getMigrationTransferStates()) { "Migration state is unavailable" }.transfers
        val remaining = pending.remainingScheduleSignatures(states)
        // Make all later signatures durable before persisting or broadcasting the split.
        // After process loss, the engine can drive a signed split without the in-memory batch.
        if (remaining.isNotEmpty()) sdk.storeSignedSchedulePczts(remaining)
        when (val splitResult = submitSplitIfAwaitingSignature(sdk, pending, states, accountKeyId)) {
            null, is TransferResult.Success -> Unit
            TransferResult.InvalidNote, TransferResult.Expired -> {
                returnToHomeForRecovery(accountKeyId, schedule, pending, migrationFailureMessage(splitResult))
                return
            }
            is TransferResult.NetworkError -> {
                showFinalizationError(migrationFailureMessage(splitResult))
                return
            }
        }
        // Mode doesn't affect this use case's behavior (only routes IMMEDIATE vs AUTOMATIC
        // before it), and MigrationScheduledArgs is only ever reached on the AUTOMATIC path.
        // startLiveDriverImmediately=false (MOB-1669): a large Keystone batch's whole
        // prove-ready note-split set would otherwise run through one blocking
        // finalizeReadyTransfers call in the live driver's very first iteration, right as
        // this Keystone ceremony finishes. The already-armed migrationScheduler job still
        // drives this plan forward — just not synchronously forced open here.
        finalizeMigrationSchedule(schedule, MigrationMode.AUTOMATIC, accountKeyId, startLiveDriverImmediately = false)
        pendingSchedule.clear(accountKeyId, schedule)
        pendingKeystonePczts.clear(accountKeyId, pending)
        isFinalizing.value = false
    }

    private suspend fun submitSplitIfAwaitingSignature(
        sdk: OrchardMigrationSdk,
        pending: PendingKeystoneMigrationPczts,
        states: List<MigrationTransferState>,
        accountKeyId: String,
    ): TransferResult? {
        val signedSplit = pending.accumulatedSplitSigned ?: return null
        val split = checkNotNull(states.singleOrNull { !it.isTransfer && it.prepLayer == 0 && it.prepIndex == 0 }) {
            "The reviewed note split is unavailable"
        }
        if (!split.needsSignature()) return null
        val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)
        val result = sdk.storeSignedNoteSplitPczt(Pczt(signedSplit), NetworkPrivacyOptions(useTor = useTor))
        if (result is TransferResult.Success) {
            synchronizerProvider.getSynchronizerOrNull()?.enhanceTransaction(result.txId)
        }
        return result
    }

    private fun PendingKeystoneMigrationPczts.remainingScheduleSignatures(
        states: List<MigrationTransferState>,
    ): List<Pair<Long, Pczt>> {
        val statesById = states.associateBy { it.id }
        fun awaiting(items: List<Pair<Long, ByteArray>>, isTransfer: Boolean): List<Pair<Long, Pczt>> =
            items.mapNotNull { (id, bytes) ->
                val state = checkNotNull(statesById[id]?.takeIf { it.isTransfer == isTransfer }) {
                    "A reviewed migration step is unavailable"
                }
                if (state.needsSignature()) id to Pczt(bytes) else null
            }
        return awaiting(accumulatedPrepSigned, isTransfer = false) +
            awaiting(accumulatedTransferSigned, isTransfer = true)
    }

    private fun MigrationTransferState.needsSignature(): Boolean =
        when (blocker) {
            MigrationBlocker.SIGNATURE -> true
            MigrationBlocker.DEPENDENCIES, MigrationBlocker.SCHEDULE, MigrationBlocker.ANCHOR_BOUNDARY -> false
            null -> {
                check(isSent || isProved || ready) { "The reviewed migration step cannot be reconciled" }
                false
            }
            else -> error("The reviewed migration step cannot be reconciled")
        }

    private fun returnToHomeForRecovery(
        accountKeyId: String,
        schedule: MigrationSchedule,
        pending: PendingKeystoneMigrationPczts,
        message: StringResource = stringRes(DesignR.string.migrationProgress_unavailable),
    ) {
        pendingSchedule.clear(accountKeyId, schedule)
        pendingKeystonePczts.clear(accountKeyId, pending)
        homeRecoveryMessage.value = message
        isFinalizing.value = false
    }

    private fun showFinalizationError(message: StringResource) {
        finalizationError.value = message
        failureSheet.value = MigrationTransferFailureState(
            message = message,
            onRetry = ::retryFinalization,
            onDismiss = { failureSheet.value = null },
        )
    }

    fun retryFinalization() {
        if (finalizationError.value == null) return
        finalizationError.value = null
        failureSheet.value = null
        viewModelScope.launch { finalizeIfPendingKeystoneBatch() }
    }

    fun retrySnapshot() = loadLce.execute {
        val nativeState = getOrchardMigrationSdk(args.accountKeyId)?.getMigrationStateUnreconciled()
        if (nativeState != null && nativeState !is MigrationState.InProgress) {
            homeRecoveryMessage.value = stringRes(DesignR.string.migrationProgress_unavailable)
            return@execute null
        }
        val snapshot = getMigrationSnapshot(args.accountKeyId)
        val needsSignatures = snapshot?.let {
            it.transfers.any { step -> step.blocker == MigrationTransferBlocker.SIGNATURE } ||
                it.preparations.any { step -> step.blocker == MigrationTransferBlocker.SIGNATURE }
        } ?: false
        needsKeystoneSignatures.value = needsSignatures
        snapshot.takeUnless { needsSignatures }
    }

    fun navigateBack() = navigationRouter.backToRoot()

    val state: StateFlow<LceState<MigrationScheduledState>> =
        loadLce.state
            .map { it.success }
            .map { snapshot ->
                if (snapshot == null) return@map null
                val total = snapshot.transfers.sumOf { it.amountZatoshi }
                val count = snapshot.totalCount
                val allScheduled =
                    (snapshot.transfers.map { it.scheduledAt } + snapshot.preparations.map { it.scheduledAt })
                val span =
                    (
                        (allScheduled.maxOrNull() ?: kotlin.time.Instant.DISTANT_PAST) -
                            (allScheduled.minOrNull() ?: kotlin.time.Instant.DISTANT_PAST)
                    ).inWholeSeconds
                val backgroundHint =
                    if (!isBackgroundExecutionAvailableProvider.isAvailable()) {
                        stringRes(DesignR.string.migrationScheduled_backgroundActivityHint)
                    } else {
                        null
                    }
                MigrationScheduledState(
                    totalAmount = stringRes(Zatoshi(total)),
                    transfersProgress = stringRes(DesignR.string.migrationScheduled_transfersProgressPending, count),
                    duration = stringRes(formatMigrationDuration(span)),
                    backgroundHint = backgroundHint,
                    onDone = ::onDone,
                )
            }.withLce(loadLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onDone() = navigationRouter.backToRoot()
}
