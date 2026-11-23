package co.electriccoin.zcash.ui.screen.scankeystone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.InvalidKeystoneSignInQRException
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneSignInRequestUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ScanKeystoneSignInRequestViewModel(
    private val parseKeystoneSignInRequest: ParseKeystoneSignInRequestUseCase
) : ViewModel() {
    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state =
        MutableStateFlow(
            ScanKeystoneState(
                progress = null,
                message = stringRes(R.string.scan_keystone_info),
            )
        )

    override fun onCleared() {
        parseKeystoneSignInRequest.close()
        super.onCleared()
    }

    fun beginPresentation(): String {
        validationState.value = ScanValidationState.NONE
        state.update { it.copy(progress = null) }
        return parseKeystoneSignInRequest.beginPresentation()
    }

    fun endPresentation(id: String) = parseKeystoneSignInRequest.endPresentation(id)

    fun onScanned(result: String, presentationId: String) =
        viewModelScope.launch {
            if (!parseKeystoneSignInRequest.isCurrentPresentation(presentationId)) return@launch
            try {
                val scanResult = parseKeystoneSignInRequest(result, presentationId)
                if (parseKeystoneSignInRequest.isCurrentPresentation(presentationId)) {
                    state.update { it.copy(progress = scanResult.progress) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidKeystoneSignInQRException) {
                if (parseKeystoneSignInRequest.isCurrentPresentation(presentationId)) {
                    validationState.value = ScanValidationState.INVALID
                }
            } catch (_: Exception) {
                // do nothing
            }
        }
}
