package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.provider.IsSwapRefundWarningDismissedStorageProvider
import co.electriccoin.zcash.ui.screen.swap.refundwarning.SwapRefundWarningArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * Warns about wrong-asset or wrong-network deposits under $300 before Swap and CrossPay review. The
 * "Don't show this message again" flag that suppresses it is kept per surface, so silencing the
 * warning on Swap leaves it armed for CrossPay.
 */
class NavigateToSwapRefundWarningUseCase(
    private val navigationRouter: NavigationRouter,
    private val isSwapRefundWarningDismissed: IsSwapRefundWarningDismissedStorageProvider,
) {
    private val pipeline = MutableSharedFlow<SwapRefundWarningPipelineResult>()

    /**
     * Guards against a second sheet being pushed while one is already awaiting its result, which
     * would leave the first caller suspended forever. A plain field needs no synchronisation: every
     * caller enters through a `viewModelScope` coroutine, so all reads and writes happen on Main.
     */
    private var pending: SwapRefundWarningArgs? = null
    private var hasUnscopedCaller = false

    /**
     * Suspends until the user resolves the warning: `true` means carry on to the quote, `false`
     * means the user backed out or a sheet was already pending. Returns `true` without showing
     * anything when the amount is at or above the threshold, or when the warning has already been
     * dismissed for good on this surface.
     *
     * The threshold is evaluated on the app's own pre-quote estimate of the USD value being sent,
     * which each surface derives differently: [SwapMode.EXACT_INPUT] is the ZEC amount-in times the
     * ZEC USD price, [SwapMode.FLEX_INPUT] the token amount-in times that token's USD price, and
     * [SwapMode.EXACT_OUTPUT] the destination amount times its USD price - the best estimate of the
     * amount-in available before a quote exists. A null [fiatAmount] means the app cannot price the
     * amount at all, which fails safe: the warning is shown rather than skipped.
     *
     * The suppression flag is persisted here rather than in the sheet, and only once a confirmed
     * result has actually reached the awaiting caller: a sheet restored after process death has
     * nobody left to resolve, and must not silence a warning whose Continue leads nowhere.
     */
    suspend operator fun invoke(fiatAmount: BigDecimal?, mode: SwapMode, sourceEntryId: String? = null): Boolean {
        val isAboveThreshold = fiatAmount != null && fiatAmount >= SWAP_REFUND_WARNING_THRESHOLD_USD
        if (isAboveThreshold || isSwapRefundWarningDismissed.get(mode)) return true
        return showWarning(mode, sourceEntryId)
    }

    /**
     * Shows the sheet and suspends until it resolves, or resolves `false` right away when another
     * sheet is still pending.
     *
     * The pipeline is subscribed before the screen is forwarded, so a result emitted as soon as the
     * screen appears can never be dropped (a bare SharedFlow emit with no collector is lost and
     * would hang the caller).
     */
    private suspend fun showWarning(mode: SwapMode, sourceEntryId: String?): Boolean {
        if (pending != null) return false

        val args = SwapRefundWarningArgs(mode = mode)
        pending = args
        hasUnscopedCaller = sourceEntryId == null
        val result =
            try {
                if (sourceEntryId == null) {
                    pipeline
                        .onSubscription { navigationRouter.forward(args) }
                        .first { it.args.requestId == args.requestId }
                } else {
                    showOwnedWarning(args, sourceEntryId)
                }
            } finally {
                pending = null
                hasUnscopedCaller = false
            }
        return when (result) {
            null, is SwapRefundWarningPipelineResult.Cancelled -> {
                false
            }

            is SwapRefundWarningPipelineResult.Confirmed -> {
                if (result.dontShowAgain) {
                    isSwapRefundWarningDismissed.store(mode, true)
                }
                true
            }
        }
    }

    private suspend fun showOwnedWarning(args: SwapRefundWarningArgs, sourceEntryId: String) = coroutineScope {
        val decision = async(start = CoroutineStart.UNDISPATCHED) {
            pipeline.first { it.args.requestId == args.requestId }
        }
        try {
            val opened = navigationRouter.commitFrom(sourceEntryId) {
                NavigationCommand.Forward(listOf(args))
            }
            val openedEntry = navigationRouter.activeEntry.value
            if (!opened || openedEntry?.refundWarningRequestId != args.requestId) return@coroutineScope null
            val dismissed = async { navigationRouter.activeEntry.first { it?.id != openedEntry.id } }
            try {
                select<SwapRefundWarningPipelineResult?> {
                    decision.onAwait { it }
                    dismissed.onAwait { null }
                }
            } finally {
                dismissed.cancel()
            }
        } finally {
            decision.cancel()
        }
    }

    /**
     * Hands the awaiting caller the user's Continue, together with the state of the
     * "Don't show this message again" checkbox for it to persist. The result is emitted before
     * anything else happens, so a Cancel racing this call cannot win the pipeline after the flag
     * has been written.
     */
    suspend fun onContinue(args: SwapRefundWarningArgs, dontShowAgain: Boolean) {
        finish(SwapRefundWarningPipelineResult.Confirmed(args = args, dontShowAgain = dontShowAgain))
    }

    suspend fun onCancel(args: SwapRefundWarningArgs) {
        finish(SwapRefundWarningPipelineResult.Cancelled(args))
    }

    private suspend fun finish(result: SwapRefundWarningPipelineResult) {
        val requestId = result.args.requestId
        if (hasUnscopedCaller && pending?.requestId == requestId) {
            pipeline.emit(result)
            navigationRouter.back()
        } else {
            val entry = navigationRouter.activeEntry.value
            if (entry?.refundWarningRequestId == requestId) {
                pipeline.emit(result)
                navigationRouter.commitFrom(entry.id) { NavigationCommand.Back }
            }
        }
    }
}

private sealed interface SwapRefundWarningPipelineResult {
    val args: SwapRefundWarningArgs

    data class Cancelled(
        override val args: SwapRefundWarningArgs
    ) : SwapRefundWarningPipelineResult

    data class Confirmed(
        override val args: SwapRefundWarningArgs,
        val dontShowAgain: Boolean
    ) : SwapRefundWarningPipelineResult
}

/** NEAR only refunds user error - a wrong address or a wrong network - above this USD value. */
val SWAP_REFUND_WARNING_THRESHOLD_USD = BigDecimal("300")

internal suspend fun NavigationRouter.awaitQuoteOrigin(sourceEntryId: String?): Boolean {
    if (sourceEntryId == null) return true
    val active = activeEntry.value
    return when {
        active == null -> false
        active.id == sourceEntryId -> true
        active.refundWarningRequestId != null ->
            withTimeoutOrNull(2.seconds) {
                activeEntry.first { it?.id != active.id }?.id == sourceEntryId
            } == true
        else -> false
    }
}
