package co.electriccoin.zcash.ui.screen.scan.thirdparty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.common.usecase.RescanQrUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThirdPartyScanViewModel(
    private val rescanQr: RescanQrUseCase,
    private val navigationRouter: NavigationRouter,
    private val sourceEntryId: String,
    private val walletRoutesAllowed: Boolean,
) : ViewModel() {
    val state =
        MutableStateFlow(
            ThirdPartyScanState(
                onScanClick = ::onScanClick,
                onBack = ::onBack
            )
        ).asStateFlow()

    private fun onScanClick() = viewModelScope.launch { rescanQr(sourceEntryId, walletRoutesAllowed) }

    private fun onBack() {
        viewModelScope.launch { navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back } }
    }
}
