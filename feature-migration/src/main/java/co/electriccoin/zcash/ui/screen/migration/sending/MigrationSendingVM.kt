package co.electriccoin.zcash.ui.screen.migration.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.MigrationNextAction
import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.ScheduleNextMigrationWindowUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import co.electriccoin.zcash.work.MigrationDriveOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import co.electriccoin.zcash.ui.design.R as DesignR

class MigrationSendingVM(
    private val args: MigrationSendingArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getMigrationSnapshot: co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase,
    private val scheduleNextMigrationWindow: ScheduleNextMigrationWindowUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val migrationDriveOnce: MigrationDriveOnce,
    private val sourceEntryId: String? = null,
    private val accounts: AccountDataSource? = null,
) : ViewModel() {
    private val sendLce = mutableLce<Unit>()
    private val failure = MutableStateFlow<SendFailure?>(null)

    private sealed interface SendFailure {
        data class Engine(
            val result: TransferResult
        ) : SendFailure

        data object NotReady : SendFailure
    }

    private fun SendFailure.message(): StringResource =
        when (this) {
            is SendFailure.Engine -> migrationFailureMessage(result)
            SendFailure.NotReady -> stringRes(DesignR.string.migrationSending_notReady)
        }

    init {
        val hasPendingDecision =
            pendingMigrationTorFailureDecisionRepository.decision.value?.accountKeyId == args.accountKeyId
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .filter { it.accountKeyId == args.accountKeyId }
            .onEach { decision ->
                if (pendingMigrationTorFailureDecisionRepository.consume(decision)) {
                    sendLce.execute { sendOnce(decision.useTor) }
                }
            }.launchIn(viewModelScope)
        if (!hasPendingDecision) {
            send()
        }
    }

    val state: StateFlow<LceState<MigrationSendingState>> =
        combine(sendLce.state, failure) { send, f ->
            MigrationSendingState(
                failureSheet =
                    f?.let {
                        MigrationTransferFailureState(
                            message = it.message(),
                            onRetry = {
                                failure.value = null
                                send()
                            },
                            onDismiss = {
                                onBack()
                            },
                        )
                    },
                onBack = ::onBack,
                canGoBack = !send.loading,
            )
        }.withLce(sendLce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun send() = sendLce.execute {
        sendOnce(useTor = isMigrationTorEnabledStorageProvider.get(args.accountKeyId))
    }

    fun onBack() = sendLce.guardLoading {
        navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.Back }
    }

    private fun openResult(route: Any, replace: Boolean = false) = navigationRouter.stageResultFrom(sourceEntryId) {
        if (replace) NavigationCommand.Replace(listOf(route)) else NavigationCommand.Forward(listOf(route))
    }

    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    private suspend fun sendOnce(useTor: Boolean) {
        val sdk = getOrchardMigrationSdk(args.accountKeyId)
        if (sdk == null) {
            failure.value = SendFailure.NotReady
            return
        }
        // The engine-mutating send lives entirely under DRIVE_LOCK (via withExclusiveAccess) so it
        // can never race MigrationWorker/MigrationLiveDriver's own executeNextPendingTransfer call
        // for the same transfer (2026-08-06 DRIVE_LOCK bypass fix). Lock acquisition itself retries
        // on the SAME budget the readiness loop already used (no new retry concept) — once acquired,
        // the whole readiness loop runs under the one lock acquisition (not re-acquired per attempt),
        // so a background broadcast can't land in a gap between this screen's own attempts.
        var acquired = false
        var outcome: TransferAttemptOutcome? = null
        var lockAttempt = 0
        while (!acquired && lockAttempt < SEND_MAX_ATTEMPTS) {
            if (lockAttempt > 0) delay(SEND_RETRY_DELAY_MS)
            val result =
                migrationDriveOnce.withExclusiveAccess(args.accountKeyId) {
                    acquired = true
                    sendReadinessLoop(sdk, useTor)
                }
            if (result != null) outcome = result
            lockAttempt++
        }
        if (!acquired) {
            // Never got exclusive access — a background step held the lock for the whole retry
            // budget. Same handling as the pre-existing "not ready yet" path below: no send was
            // attempted, so this can't be a Tor failure.
            reportNotReady()
            return
        }
        // Everything below is a read, a WorkManager enqueue, or app-state/navigation — none of it
        // mutates the engine's send state, so it stays outside the lock (avoids needless LockBusy
        // churn against the worker/live-driver for callers that don't need to be excluded).
        when (val o = outcome) {
            is TransferAttemptOutcome.Executed -> {
                when (val r = o.result) {
                    is TransferResult.Success -> reportSubmitted(r.txId)

                    // A NetworkError whose failure is specifically Tor-attributable is routed to its own
                    // sheet (offering "continue without Tor") instead of the generic "Couldn't Send" one,
                    // since the fix (drop Tor) differs from a real network outage.
                    is TransferResult.NetworkError -> {
                        if (r.isTorFailure) {
                            openResult(MigrationTorFailureArgs(args.accountKeyId))
                        } else {
                            failure.value = SendFailure.Engine(r)
                        }
                    }

                    else -> {
                        failure.value = SendFailure.Engine(r)
                    }
                }
            }

            // NothingDue or AwaitingProof after max attempts: the transfer isn't ready yet — no
            // send was even attempted, so this can't be (another) Tor failure. Clear a pending
            // Tor-failure flag here too (CheckMigrationRecoveryUseCase already checks this before
            // routing here, but a user can also reach this screen by other means): otherwise it
            // would survive to keep re-triggering the app-open Sending redirect for a transfer
            // that was never going to attempt a network send in the first place.
            // The foreground sync + Lane A hook will prove it; the user can retry later.
            else -> reportNotReady()
        }
    }

    private suspend fun reportSubmitted(txId: TransactionId) {
        // Recovery bookkeeping can be retried by the driver. A failure here must keep the known broadcast result.
        migrationFollowUp("clear Tor failure") {
            pendingMigrationTorFailureStorageProvider.store(args.accountKeyId, false)
        }
        migrationFollowUp("schedule next transfer") { scheduleNextMigrationWindow(args.accountKeyId) }
        val snapshot = migrationFollowUp("read completion") { getMigrationSnapshot(args.accountKeyId) }
        val route =
            if (snapshot?.areAllMined == true) {
                MigrationCompleteArgs(accountKeyId = args.accountKeyId)
            } else {
                MigrationSuccessArgs(txId.txIdString())
            }
        openResult(route, replace = true)
    }

    private suspend fun reportNotReady() {
        if (!args.isRecoveryRetry) {
            pendingMigrationTorFailureStorageProvider.store(args.accountKeyId, false)
        }
        failure.value = SendFailure.NotReady
    }

    /**
     * Runs under [MigrationDriveOnce.withExclusiveAccess] so the background driver cannot
     * interleave with a retry or advance its proposed transfer.
     */
    private suspend fun sendReadinessLoop(
        sdk: OrchardMigrationSdk,
        useTor: Boolean,
    ): TransferAttemptOutcome? {
        var outcome: TransferAttemptOutcome? = null
        var attempt = 0
        while ((
                outcome == null ||
                    outcome is TransferAttemptOutcome.NothingDue ||
                    outcome is TransferAttemptOutcome.AwaitingProof
            ) &&
            attempt < SEND_MAX_ATTEMPTS
        ) {
            if (attempt > 0) delay(SEND_RETRY_DELAY_MS)
            if (!canRetryTarget(sdk)) break
            if (!args.isRecoveryRetry || migrationDriveOnce.mayBroadcastCrossing(sdk)) {
                withContext(NonCancellable) {
                    outcome = sdk.executeNextPendingTransfer(
                        NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true
                    )
                    val outcomeType = (outcome as? TransferAttemptOutcome.Executed)?.result ?: outcome
                    migrationLog("SendingVM: attempt=${attempt + 1} outcome=${outcomeType.javaClass.simpleName}")
                }
            }
            attempt++
        }
        return outcome
    }

    private suspend fun canRetryTarget(sdk: OrchardMigrationSdk): Boolean {
        if (!args.isRecoveryRetry) return true
        val ready =
            sdk.getMigrationTransferStates()?.transfers?.any {
                it.id == args.retryTransferId && it.isTransfer && !it.isSent &&
                    it.action == MigrationNextAction.BROADCAST &&
                    it.scheduledHeight == args.retryScheduledHeight &&
                    (it.anchorBoundaryHeight ?: MigrationSendingArgs.UNSET) == args.retryAnchorBoundaryHeight
            } == true
        val next = if (ready) sdk.nextStep()?.step as? MigrationAdvanceStep.Broadcast else null
        val selectedOwner = accounts?.allAccounts?.value?.firstOrNull { it.isSelected }
            ?.sdkAccount?.accountUuid?.toStorageKeyId()
        return next?.transferId == args.retryTransferId && selectedOwner == args.accountKeyId
    }

    companion object {
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_DELAY_MS = 1500L
    }
}

@Suppress("TooGenericExceptionCaught") // Ancillary storage, scheduler and SDK read failures leave the broadcast intact.
private suspend fun <T> migrationFollowUp(stage: String, block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        migrationLog("Migration follow-up ($stage) unavailable: ${e.javaClass.simpleName}.")
        null
    }
