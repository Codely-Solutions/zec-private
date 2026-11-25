package co.electriccoin.zcash.ui.screen.pay

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetArgs
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetVM
import co.electriccoin.zcash.ui.screen.swap.SwapCancelView
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PayScreen(sourceEntryId: String) {
    val vm = koinViewModel<PayVM> { parametersOf(sourceEntryId) }
    val balanceVM =
        koinViewModel<BalanceWidgetVM> {
            parametersOf(
                BalanceWidgetArgs(
                    isBalanceButtonEnabled = true,
                    isExchangeRateButtonEnabled = false,
                    showDust = true
                )
            )
        }
    val appBarVM = koinViewModel<ZashiTopAppBarVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val balanceState by balanceVM.state.collectAsStateWithLifecycle()
    val appBarState by appBarVM.state.collectAsStateWithLifecycle()
    val cancelState by vm.cancelState.collectAsStateWithLifecycle()
    state?.let { payState ->
        val balanceButton = balanceState.button?.let { button ->
            button.copy(
                isEnabled = !payState.isRequestingQuote,
                onClick = { if (vm.canOpenSecondaryScreen()) button.onClick() },
            )
        }
        PayView(payState, balanceState.copy(button = balanceButton), appBarState)
    }
    BackHandler(state != null) { state?.onBack?.invoke() }
    SwapCancelView(cancelState, vm::onCancelSheetHidden)
}

@Serializable
data object PayArgs
