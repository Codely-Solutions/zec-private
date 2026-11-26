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
import co.electriccoin.zcash.ui.isForegroundAtEntry
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
import co.electriccoin.zcash.ui.common.repository.MigrationSendAuthorization
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.UUID
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
    sendAuthorization: MigrationSendAuthorization = MigrationSendAuthorization(),
) : ViewModel() {
    private val sendLce = mutableLce<Unit>()
    private val failure = MutableStateFlow<SendFailure?>(null)
    private val canOperate: (() -> Boolean)? =
        if (sourceEntryId == null) ({ true }) else sendAuthorization.take(args)
    private var fallbackId: String? = null

    private sealed interface SendFailure {
        fun message(): StringResource =
            when (this) {
                is Engine -> migrationFailureMessage(result)
                NotReady -> stringRes(DesignR.string.migrationSending_notReady)
            }

        data class Engine(
            val result: TransferResult
        ) : SendFailure

        data object NotReady : SendFailure
    }

    init {
        pendingMigrationTorFailureDecisionRepository.decision
            .filterNotNull()
            .filter { decision ->
                fallbackId != null && decision.accountKeyId == args.accountKeyId &&
                    decision.authorizationId == fallbackId
            }
            .onEach { decision ->
                if (sourceEntryId != null) {
                    navigationRouter.activeEntry.first { it?.id == sourceEntryId && it.hostResumed }
                }
                if (pendingMigrationTorFailureDecisionRepository.consume(decision)) {
                    fallbackId = null
                    sendLce.execute { sendOnce(decision.useTor, directConsent = true) }
                }
            }.launchIn(viewModelScope)
        send()
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

    private suspend fun sendOnce(useTor: Boolean, directConsent: Boolean = false) {
        val sdk = if (navigationRouter.awaitAuthorizedEntry(sourceEntryId, canOperate)) {
            getOrchardMigrationSdk(args.accountKeyId)
        } else {
            null
        }
        if (sdk == null) {
            failure.value = SendFailure.NotReady
            return
        }
        when (val o = sendWhenIdle(sdk, useTor)) {
            is TransferAttemptOutcome.Executed -> {
                when (val r = o.result) {
                    is TransferResult.Success -> reportSubmitted(r.txId)

                    // A NetworkError whose failure is specifically Tor-attributable is routed to its own
                    // sheet (offering "continue without Tor") instead of the generic "Couldn't Send" one,
                    // since the fix (drop Tor) differs from a real network outage.
                    is TransferResult.NetworkError -> {
                        if (r.isTorFailure && !directConsent) {
                            val token = UUID.randomUUID().toString()
                            fallbackId = token
                            openResult(MigrationTorFailureArgs(args.accountKeyId, token))
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
            else -> {
                if (!args.isRecoveryRetry) {
                    pendingMigrationTorFailureStorageProvider.store(args.accountKeyId, false)
                }
                failure.value = SendFailure.NotReady
            }
        }
    }

    private suspend fun sendWhenIdle(sdk: OrchardMigrationSdk, useTor: Boolean): TransferAttemptOutcome? {
        repeat(SEND_MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SEND_RETRY_DELAY_MS)
            val execution = migrationDriveOnce.withExclusiveAccess(args.accountKeyId) {
                // The whole readiness loop holds exclusive access, including when no send is due.
                Result.success(sendReadinessLoop(sdk, useTor))
            }
            if (execution != null) return execution.getOrThrow()
        }
        return null
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
        while (outcome !is TransferAttemptOutcome.Executed && attempt < SEND_MAX_ATTEMPTS) {
            if (attempt > 0) delay(SEND_RETRY_DELAY_MS)
            if (!canRetryTarget(sdk)) break
            if (!args.isRecoveryRetry || migrationDriveOnce.mayBroadcastCrossing(sdk)) {
                if (!canEnterSend()) return outcome
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (!canEnterSend()) return@withContext
                    val result = sdk.executeNextPendingTransfer(
                        NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true
                    )
                    if (args.isRecoveryRetry) migrationDriveOnce.recordCrossingSuccess(result)
                    outcome = result
                    val outcomeType = (outcome as? TransferAttemptOutcome.Executed)?.result ?: outcome
                    migrationLog("SendingVM: attempt=${attempt + 1} outcome=${outcomeType.javaClass.simpleName}")
                }
            }
            attempt++
        }
        return outcome
    }

    private fun canEnterSend(): Boolean {
        val selected = accounts?.allAccounts?.value?.firstOrNull { it.isSelected }
            ?.sdkAccount?.accountUuid?.toStorageKeyId()
        val ownerMatches = !args.isRecoveryRetry || selected == args.accountKeyId
        return ownerMatches && canOperate?.invoke() == true && navigationRouter.isForegroundAtEntry(sourceEntryId) &&
            migrationDriveOnce.permitsForeground(args.accountKeyId)
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
        return next?.transferId == args.retryTransferId
    }

    companion object {
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_DELAY_MS = 1500L
    }
}

private suspend fun NavigationRouter.awaitAuthorizedEntry(
    entryId: String?,
    permits: (() -> Boolean)?,
): Boolean {
    if (permits?.invoke() != true) return false
    if (entryId != null && !isForegroundAtEntry(entryId)) {
        activeEntry.first { it?.id == entryId && it.hostResumed }
    }
    return permits.invoke()
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
