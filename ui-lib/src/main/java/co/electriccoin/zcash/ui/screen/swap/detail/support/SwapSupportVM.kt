package co.electriccoin.zcash.ui.screen.swap.detail.support

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.AccountUuid
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SwapSupportVM(
    args: SwapSupportArgs,
    getReloadableSwapQuote: GetReloadableSwapQuoteUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendEmailUseCase: SendEmailUseCase,
    private val metadataRepository: MetadataRepository,
    private val appAccessSession: AppAccessSession,
    accountDataSource: AccountDataSource,
    private val walletWriteAccess: WalletWriteAccess,
) : ViewModel() {
    private class Selection(val account: AccountUuid?)

    private val selectedAccount = accountDataSource.selectedAccount
        .map { it?.sdkAccount?.accountUuid }
        .distinctUntilChanged()
        .map(::Selection)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<SwapSupportState?> =
        getReloadableSwapQuote
            .observe(args.depositAddress)
            .combine(appAccessSession.grant) { swapData, grant -> swapData to grant }
            .combine(selectedAccount) { (swapData, grant), selection ->
                val saved = swapData.savedMetadata
                val token = saved?.walletWriteToken
                val owner = saved?.ownerAccount?.sdkAccount?.accountUuid
                val isAllowed = {
                    token != null && owner != null && selection?.account == owner &&
                        selectedAccount.value === selection && walletWriteAccess.permits(token) &&
                        appAccessSession.permits(grant)
                }
                SwapSupportState(
                    title = stringRes(R.string.reportSwap_title),
                    message = stringRes(R.string.reportSwap_msg),
                    reportIssueButton =
                        ButtonState(
                            text = stringRes(R.string.reportSwap_report),
                            isEnabled = isAllowed(),
                            onClick = { onReportIssue(swapData, isAllowed) }
                        ),
                    onBack = ::onBack,
                    shareQuoteButton = swapData.savedMetadata
                        ?.takeIf { it.signedQuoteEvidence != null && grant != null }
                        ?.let { saved ->
                            ButtonState(
                                text = stringRes(R.string.reportSwap_shareQuote),
                                style = ButtonStyle.SECONDARY,
                                isEnabled = isAllowed(),
                                onClick = { onShareOriginalQuote(saved, isAllowed) },
                            )
                        }
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private fun onBack() = navigationRouter.back()

    private fun onReportIssue(swapData: SwapData, isAllowed: () -> Boolean) {
        viewModelScope.launch {
            if (isAllowed() && sendEmailUseCase(swapData, isAllowed)) onBack()
        }
    }

    private fun onShareOriginalQuote(expected: TransactionSwapMetadata, isAllowed: () -> Boolean) {
        val owner = expected.ownerAccount?.sdkAccount?.accountUuid ?: return
        viewModelScope.launch {
            if (!isAllowed()) return@launch
            val evidence = runCatchingRecoverable {
                metadataRepository.reloadSwapMetadata(expected)?.signedQuoteEvidence
            }.getOrNull() ?: return@launch
            if (!isAllowed()) return@launch
            val selected = metadataRepository.getSwapMetadata(expected.depositAddress) ?: return@launch
            if (selected.ownerAccount?.sdkAccount?.accountUuid != owner ||
                selected.walletWriteToken != expected.walletWriteToken || selected.signedQuoteEvidence != evidence
            ) return@launch
            if (isAllowed()) sendEmailUseCase.shareOriginalQuote(evidence)
        }
    }
}
