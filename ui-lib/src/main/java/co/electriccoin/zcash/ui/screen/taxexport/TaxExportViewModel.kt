package co.electriccoin.zcash.ui.screen.taxexport

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.ExportTaxUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaxExportViewModel(
    getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val exportTax: ExportTaxUseCase,
    private val navigationRouter: NavigationRouter,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val exporting = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private var exportJob: Job? = null
    private var leaving = false
    private class Selection(val account: WalletAccount?)

    private val selectedAccount = getSelectedWalletAccount.observe()
        .distinctUntilChangedBy { it?.sdkAccount?.accountUuid }
        .map(::Selection)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<TaxExportState> =
        combine(selectedAccount, exporting, failed, appAccessSession.grant, walletWriteAccess.version) {
                selection, loading, error, grant, token ->
            createState(selection?.account, loading, error) {
                selectedAccount.value === selection && appAccessSession.permits(grant) &&
                    walletWriteAccess.permits(token)
            }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(null)
            )

    private fun createState(
        selectedAccount: WalletAccount?,
        loading: Boolean = false,
        error: Boolean = false,
        isAllowed: () -> Boolean = { false },
    ) =
        TaxExportState(
            exportButton =
                ButtonState(
                    text = stringRes(R.string.taxExport_download),
                    hapticFeedbackType = HapticFeedbackType.Confirm,
                    isEnabled = !loading && selectedAccount != null && isAllowed(),
                    isLoading = loading,
                    onClick = { selectedAccount?.let { onExportClick(it, isAllowed) } }
                ),
            onBack = ::onBack,
            error = stringRes(R.string.taxExport_error).takeIf { error },
            text =
                stringRes(
                    R.string.taxExport_desc,
                    when (selectedAccount) {
                        is KeystoneAccount -> stringRes(R.string.accounts_keystone)
                        is LedgerAccount -> stringRes(R.string.accounts_ledger)
                        is ViewOnlyAccount -> selectedAccount.name
                        else -> stringRes(R.string.accounts_zashi)
                    }
                )
        )

    private fun onExportClick(selectedAccount: WalletAccount, isAllowed: () -> Boolean) {
        if (leaving || !isAllowed() || !exporting.compareAndSet(expect = false, update = true)) return
        failed.value = false
        exportJob = viewModelScope.launch {
            try {
                exportTax(selectedAccount, sourceEntryId, isAllowed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed.value = true
            } finally {
                exporting.value = false
            }
        }
    }

    private fun onBack() {
        leaving = true
        exportJob?.cancel()
        navigationRouter.back()
    }
}
