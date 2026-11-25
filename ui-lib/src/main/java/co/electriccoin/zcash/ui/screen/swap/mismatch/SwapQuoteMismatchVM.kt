package co.electriccoin.zcash.ui.screen.swap.mismatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.bindReportHandoff
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SwapQuoteMismatchVM(
    private val args: SwapQuoteMismatchArgs,
    private val navigationRouter: NavigationRouter,
    private val sendEmailUseCase: SendEmailUseCase,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
) : ViewModel() {
    val state: StateFlow<SwapQuoteMismatchState?> =
        combine(appAccessSession.grant, walletWriteAccess.version) { grant, token ->
            val isAllowed = { appAccessSession.permits(grant) && walletWriteAccess.permits(token) }
            SwapQuoteMismatchState(
                title = stringRes(R.string.swap_mismatch_title),
                paragraphs =
                    listOf(
                        stringRes(R.string.swap_mismatch_msg_1),
                        stringRes(R.string.swap_mismatch_msg_2),
                        stringRes(R.string.swap_mismatch_msg_3)
                    ),
                goBackButton =
                    ButtonState(
                        text = stringRes(R.string.swap_mismatch_goBack),
                        onClick = ::onBack
                    ),
                reportButton =
                    ButtonState(
                        text = stringRes(R.string.send_report),
                        isEnabled = isAllowed(),
                        onClick = { onReport(isAllowed) }
                    ),
                onBack = ::onBack
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun onBack() = navigationRouter.back()

    private fun onReport(isAllowed: () -> Boolean) {
        val canShare = navigationRouter.bindReportHandoff(isAllowed)
        viewModelScope.launch {
            if (canShare() && sendEmailUseCase(args, canShare) && canShare()) onBack()
        }
    }
}
