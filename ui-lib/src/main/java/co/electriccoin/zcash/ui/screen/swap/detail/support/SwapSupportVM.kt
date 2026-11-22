package co.electriccoin.zcash.ui.screen.swap.detail.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.SwapData
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SwapSupportVM(
    args: SwapSupportArgs,
    getReloadableSwapQuote: GetReloadableSwapQuoteUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendEmailUseCase: SendEmailUseCase,
    private val metadataRepository: MetadataRepository,
) : ViewModel() {
    val state: StateFlow<SwapSupportState?> =
        getReloadableSwapQuote
            .observe(args.depositAddress)
            .map { swapData ->
                SwapSupportState(
                    title = stringRes(R.string.reportSwap_title),
                    message = stringRes(R.string.reportSwap_msg),
                    reportIssueButton =
                        ButtonState(
                            text = stringRes(R.string.reportSwap_report),
                            isEnabled = swapData.hasReportDetails,
                            onClick = { onReportIssue(swapData) }
                        ),
                    onBack = ::onBack,
                    shareQuoteButton = swapData.savedMetadata
                        ?.takeIf { it.signedQuoteEvidence != null }
                        ?.let { saved ->
                            ButtonState(
                                text = stringRes(R.string.reportSwap_shareQuote),
                                style = ButtonStyle.SECONDARY,
                                onClick = { onShareOriginalQuote(saved) },
                            )
                        }
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private fun onBack() = navigationRouter.back()

    private fun onReportIssue(swapData: SwapData) {
        if (!swapData.hasReportDetails) return
        viewModelScope.launch {
            sendEmailUseCase.invoke(swapData)
            onBack()
        }
    }

    private val SwapData.hasReportDetails: Boolean
        get() = status != null || savedMetadata != null

    private fun onShareOriginalQuote(expected: TransactionSwapMetadata) {
        val owner = expected.ownerAccount?.sdkAccount?.accountUuid ?: return
        viewModelScope.launch {
            val evidence = runCatchingRecoverable {
                metadataRepository.reloadSwapMetadata(expected)?.signedQuoteEvidence
            }.getOrNull() ?: return@launch
            val selected = metadataRepository.getSwapMetadata(expected.depositAddress) ?: return@launch
            if (selected.ownerAccount?.sdkAccount?.accountUuid != owner ||
                selected.walletWriteToken != expected.walletWriteToken || selected.signedQuoteEvidence != evidence
            ) return@launch
            sendEmailUseCase.shareOriginalQuote(evidence)
        }
    }
}
