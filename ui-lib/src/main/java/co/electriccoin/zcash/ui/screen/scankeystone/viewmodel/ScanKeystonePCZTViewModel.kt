package co.electriccoin.zcash.ui.screen.scankeystone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MissingKeystoneSigningPreparation
import co.electriccoin.zcash.ui.common.repository.ParsePCZTException
import co.electriccoin.zcash.ui.common.usecase.InvalidKeystonePCZTQRException
import co.electriccoin.zcash.ui.common.usecase.ParseKeystonePCZTUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class ScanKeystonePCZTViewModel(
    private val parseKeystonePCZT: ParseKeystonePCZTUseCase,
    private val navigateToError: NavigateToErrorUseCase,
    private val repository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter? = null,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val lostPreparation = MutableStateFlow(false)

    fun canScan(args: ScanKeystonePCZTRequest) = !lostPreparation.value &&
        repository.signingPreparationId() == args.preparationId

    fun observeCanScan(args: ScanKeystonePCZTRequest) =
        combine(repository.signingPreparationIds, lostPreparation) { _, _ -> canScan(args) }

    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state =
        MutableStateFlow(
            ScanKeystoneState(
                progress = null,
                message = stringRes(R.string.coinVote_delegationSigning_scanInstructions),
            )
        )

    fun onScanned(result: String, args: ScanKeystonePCZTRequest) =
        viewModelScope.launch {
            if (!canScan(args)) return@launch
            try {
                val scanResult = if (sourceEntryId == null) {
                    parseKeystonePCZT(result, args.preparationId)
                } else {
                    parseKeystonePCZT.scanFrom(result, args.preparationId, sourceEntryId)
                }
                state.update { it.copy(progress = scanResult.progress) }
            } catch (e: KeystoneFirmwareBelowMinimumException) {
                // MOB-1510: the QR decoded fine but the device firmware is too old — replace the
                // scan screen with the firmware-update sheet so closing it lands back on the sign
                // screen instead of the camera instantly re-scanning the same rejected QR.
                navigateToError(ErrorArgs.KeystoneFirmwareUpdateRequired(e)) { replace(it) }
            } catch (_: InvalidKeystonePCZTQRException) {
                validationState.update { ScanValidationState.INVALID }
            } catch (_: ParsePCZTException) {
                validationState.update { ScanValidationState.INVALID }
            } catch (_: MissingKeystoneSigningPreparation) {
                lostPreparation.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // do nothing
            }
        }

    override fun onCleared() {
        navigationRouter?.discardResultFrom(sourceEntryId)
        super.onCleared()
    }
}
