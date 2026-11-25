package co.electriccoin.zcash.ui.screen.error

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.errorCopy
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import co.electriccoin.zcash.ui.common.viewmodel.STACKTRACE_LIMIT
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.asPrivacySensitive
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class ErrorVM(
    args: ErrorArgs,
    private val navigateToErrorBottom: NavigateToErrorUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendEmailUseCase: SendEmailUseCase,
    private val viewOnlyContent: ViewOnlyContent,
    private val reportAccess: WalletReportAccess,
    private val route: ErrorRoute? = null,
) : ViewModel() {
    private val reportPermission = reportAccess.bind(viewModelScope)
    private val mutableState = MutableStateFlow(createState(args))
    val state: StateFlow<ErrorState> = mutableState.asStateFlow()

    init {
        if (args is ErrorArgs.SeedRecoveryNotice) {
            viewModelScope.launch {
                args.phase.collect {
                    if (it != SeedRecoveryPhase.FINISHED) {
                        mutableState.value = createSeedRecoveryErrorState(waiting = it == SeedRecoveryPhase.WAITING)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        route?.let(navigateToErrorBottom::clear)
        super.onCleared()
    }

    private fun onBack() = navigationRouter.back()

    private fun createState(args: ErrorArgs): ErrorState =
        when (args) {
            ErrorArgs.SeedRecoveryFailed -> createSeedRecoveryErrorState()
            is ErrorArgs.SeedRecoveryNotice -> createSeedRecoveryErrorState(
                waiting = args.phase.value == SeedRecoveryPhase.WAITING,
            )
            is ErrorArgs.SyncError -> createSyncErrorState(args)
            is ErrorArgs.ShieldingError -> if (args.error is SubmitResult.GrpcFailure) {
                createShieldingPendingState(args, args.error)
            } else {
                createShieldingErrorState(args)
            }
            is ErrorArgs.General -> createGeneralErrorState(args)
            is ErrorArgs.ShieldingGeneralError -> createGeneralShieldingErrorState(args)
            is ErrorArgs.KeystoneFirmwareUpdateRequired -> createKeystoneFirmwareUpdateRequiredState(args)
            is ErrorArgs.KeystoneAccountUnsupported -> createKeystoneAccountUnsupportedState(args)
        }

    private fun createSeedRecoveryErrorState(waiting: Boolean = false) = ErrorState(
        title = stringRes(
            if (waiting) R.string.wallet_seed_recovery_waiting_title else R.string.wallet_seed_recovery_failed_title,
        ),
        message = stringRes(R.string.wallet_seed_recovery_failed_message),
        positive = ButtonState(
            text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
            onClick = ::onBack,
        ),
        negative = ButtonState(
            text = stringRes(R.string.support_header),
            onClick = ::openBlankSupport,
        ),
        onBack = ::onBack,
    )

    private fun createSyncErrorState(args: ErrorArgs.SyncError): ErrorState {
        val migrationUnavailable = args.synchronizerError == SynchronizerError.MigrationStateUnavailable
        return ErrorState(
            title = stringRes(R.string.smartBanner_help_syncError_title),
            message = if (migrationUnavailable) {
                stringRes(R.string.migration_sync_state_unavailable)
            } else {
                stringRes(args.synchronizerError.getStackTrace(STACKTRACE_LIMIT).orEmpty())
            },
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative = if (migrationUnavailable) {
                ButtonState(text = stringRes(R.string.support_header), onClick = ::openBlankSupport)
            } else {
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args) }
                )
            },
            onBack = ::onBack,
        )
    }

    private fun openBlankSupport() {
        val allowed = reportAccess.bindToCurrentEntry(reportAccess.captureWallet())
        sendEmailUseCase.sendBlankSupport(allowed)
    }

    private fun createKeystoneAccountUnsupportedState(args: ErrorArgs.KeystoneAccountUnsupported) =
        ErrorState(
            title = stringRes(R.string.error_keystone_account_title),
            message = stringRes(R.string.error_keystone_account_message),
            positive =
                ButtonState(
                    text = stringRes(R.string.error_keystone_firmware_close),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )

    private fun createKeystoneFirmwareUpdateRequiredState(args: ErrorArgs.KeystoneFirmwareUpdateRequired) =
        ErrorState(
            title = stringRes(R.string.error_keystone_firmware_title),
            message =
                args.exception.detected?.let { detected ->
                    stringRes(
                        R.string.error_keystone_firmware_message,
                        detected.toString(),
                        KeystoneFirmwareVersion.MINIMUM_SUPPORTED.toString()
                    )
                } ?: stringRes(
                    R.string.error_keystone_firmware_message_legacy,
                    KeystoneFirmwareVersion.MINIMUM_SUPPORTED.toString()
                ),
            positive =
                ButtonState(
                    text = stringRes(R.string.error_keystone_firmware_close),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )

    private fun createShieldingErrorState(args: ErrorArgs.ShieldingError) =
        ErrorState(
            title = stringRes(R.string.shieldFunds_error_title),
            message =
                when (args.error) {
                    is SubmitResult.Error,
                    is SubmitResult.GrpcFailure -> {
                        stringRes(R.string.shieldFunds_error_gprc_message)
                    }

                    is SubmitResult.Failure -> {
                        stringRes(
                            R.string.shieldFunds_error_failure_message,
                            stringRes(
                                buildString {
                                    appendLine("Error code: ${args.error.code}")
                                    appendLine(args.error.description ?: "Unknown error")
                                }
                            )
                        )
                    }

                    is SubmitResult.Partial -> {
                        stringRes(
                            R.string.shieldFunds_error_failure_message,
                            args.error.statuses.joinToString()
                        )
                    }

                    is SubmitResult.Success -> {
                        stringRes("")
                    }
                },
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args) }
                ),
            onBack = ::onBack,
        )

    private fun createShieldingPendingState(
        args: ErrorArgs.ShieldingError,
        result: SubmitResult.GrpcFailure,
    ): ErrorState {
        val message = stringRes(
            when (result.reason) {
                SubmitResult.GrpcFailure.Reason.TIMEOUT -> R.string.send_pendingTimeoutInfo
                SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN -> R.string.send_pendingSdkStateInfo
                null -> R.string.send_pendingShieldingInfo
            }
        )
        return ErrorState(
            title = stringRes(R.string.send_pendingShieldingTitle),
            message = if (result.txIds.isEmpty()) message else stringRes(
                R.string.shielding_pending_with_ids,
                message,
                stringRes(R.string.send_confirmation_multiple_trx_failure_ids_title),
                stringRes(result.txIds.joinToString("\n")).asPrivacySensitive(),
            ),
            positive = ButtonState(
                text = stringRes(R.string.private_recovery_open_activity),
                onClick = { navigationRouter.replaceAll(ActivityHistoryArgs) },
            ),
            negative = if (result.reason == SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN) null else ButtonState(
                text = stringRes(R.string.send_report),
                onClick = { sendReportClick(args) },
            ),
            onBack = ::onBack,
        )
    }

    /**
     * [AccountOperationUnsupportedException] is a product limitation, not a failure — it gets the
     * copy of its kind instead of the generic title plus a stack trace.
     */
    private fun createGeneralErrorState(args: ErrorArgs.General): ErrorState {
        if (args.exception is UneditableZip321MemoException) return createUneditableZip321MemoState()
        val unsupported =
            (args.exception as? AccountOperationUnsupportedException)?.kind?.errorCopy(viewOnlyContent)
        return ErrorState(
            title = unsupported?.title ?: stringRes(R.string.error_general_title),
            message =
                unsupported?.message
                    ?: stringRes(
                        R.string.error_general_message,
                        stringRes(args.exception.stackTraceToString().take(STACKTRACE_LIMIT))
                    ),
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )
    }

    private fun createUneditableZip321MemoState() =
        ErrorState(
            title = stringRes(R.string.zip321_non_text_memo_title),
            message = stringRes(R.string.zip321_non_text_memo_edit_error),
            positive = ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                onClick = ::onBack,
            ),
            negative = null,
            onBack = ::onBack,
        )

    private fun createGeneralShieldingErrorState(args: ErrorArgs.ShieldingGeneralError) =
        ErrorState(
            title = stringRes(R.string.shieldFunds_error_title),
            message =
                stringRes(
                    R.string.shieldFunds_error_failure_message,
                    stringRes(args.exception.stackTraceToString().take(STACKTRACE_LIMIT))
                ),
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.send_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )

    private fun sendReportClick(args: ErrorArgs.ShieldingError) =
        shareReport { allowed ->
            when (val result = args.error) {
                is SubmitResult.Failure -> sendEmailUseCase(result, allowed)
                is SubmitResult.GrpcFailure -> sendEmailUseCase(result, allowed)
                is SubmitResult.Partial -> sendEmailUseCase(result, allowed)
                is SubmitResult.Error -> sendEmailUseCase(result, allowed)
                is SubmitResult.Success -> false
            }
        }

    private fun sendReportClick(args: ErrorArgs.SyncError) =
        shareReport { sendEmailUseCase(args.synchronizerError, it) }

    private fun sendReportClick(exception: Exception) = shareReport { sendEmailUseCase(exception, it) }

    private fun shareReport(send: suspend (() -> Boolean) -> Boolean) {
        val allowed = reportPermission()
        if (!allowed()) return
        viewModelScope.launch {
            if (send(allowed) && allowed()) navigationRouter.back()
        }
    }
}
