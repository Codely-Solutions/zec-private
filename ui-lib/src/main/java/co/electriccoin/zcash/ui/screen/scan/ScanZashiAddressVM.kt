package co.electriccoin.zcash.ui.screen.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.usecase.NavigateToRedeemGiftCardUseCase
import co.electriccoin.zcash.ui.common.usecase.OnAddressScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.OnZip321ScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ScanZashiAddressVM(
    private val args: ScanArgs,
    private val synchronizerProvider: SynchronizerProvider,
    private val zip321ParseUriValidationUseCase: Zip321ParseUriValidationUseCase,
    private val onAddressScanned: OnAddressScannedUseCase,
    private val zip321Scanned: OnZip321ScannedUseCase,
    private val navigateToRedeemGiftCard: NavigateToRedeemGiftCardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    val state = MutableStateFlow(ScanValidationState.NONE)

    private val mutex = Mutex()

    private var hasBeenScannedSuccessfully = false
    private var awaitingOverlayDismissal = false

    fun onResumed() {
        if (!awaitingOverlayDismissal) return
        awaitingOverlayDismissal = false
        state.value = ScanValidationState.NONE
    }

    private val acceptsScan get() =
        !hasBeenScannedSuccessfully && !awaitingOverlayDismissal && navigationRouter.isAtEntry(sourceEntryId)

    /**
     * Gift card links are handled first and open the redeem flow: a link carries a spending secret, so it must never
     * reach the ZIP-321 or address parsers, nor their logging.
     */
    fun onScanned(result: String) =
        viewModelScope.launch {
            mutex.withLock {
                if (acceptsScan) {
                    if (navigateToRedeemGiftCard.isGiftCardLink(result)) {
                        onGiftCardScanned(result)
                        return@withLock
                    }
                    runCatchingRecoverable {
                        val zip321ValidationResult = navigationRouter.whileAtEntry(sourceEntryId) {
                            zip321ParseUriValidationUseCase(result)
                        } ?: return@runCatchingRecoverable

                        when {
                            zip321ValidationResult is Zip321ParseUriValidation.Valid -> {
                                onZip321Scanned(zip321ValidationResult)
                            }

                            zip321ValidationResult is Zip321ParseUriValidation.SingleAddress -> {
                                validateAndDispatchAddress(zip321ValidationResult.address)
                            }

                            else -> {
                                validateAndDispatchAddress(result)
                            }
                        }
                    }
                }
            }
        }

    private suspend fun onGiftCardScanned(link: String) {
        hasBeenScannedSuccessfully = navigateToRedeemGiftCard.replaceWithRedeem(link.trim(), sourceEntryId)
        updateScanState()
    }

    private fun onInvalidScan() {
        hasBeenScannedSuccessfully = false
        state.update { ScanValidationState.INVALID }
    }

    private suspend fun onAddressScanned(
        result: String,
        addressValidationResult: AddressType
    ) {
        hasBeenScannedSuccessfully = onAddressScanned(result, addressValidationResult, args, sourceEntryId)
        updateScanState()
    }

    private suspend fun validateAndDispatchAddress(address: String) {
        val validation = navigationRouter.whileAtEntry(sourceEntryId) {
            synchronizerProvider.getSynchronizer().validateAddress(address)
        } ?: return
        if (validation is AddressType.Valid) onAddressScanned(address, validation) else onInvalidScan()
    }

    private suspend fun onZip321Scanned(zip321ValidationResult: Zip321ParseUriValidation.Valid) {
        hasBeenScannedSuccessfully = zip321Scanned(zip321ValidationResult, args, sourceEntryId)
        awaitingOverlayDismissal = !hasBeenScannedSuccessfully &&
            (sourceEntryId == null || !navigationRouter.isAtEntry(sourceEntryId))
        updateScanState()
    }

    private fun updateScanState() {
        state.value = if (hasBeenScannedSuccessfully || awaitingOverlayDismissal) {
            ScanValidationState.VALID
        } else {
            ScanValidationState.NONE
        }
    }

    fun onScannedError() =
        viewModelScope.launch {
            mutex.withLock {
                if (acceptsScan) {
                    state.update { ScanValidationState.INVALID }
                }
            }
        }

    fun onImageScanned(result: ImageToQrCodeResult) =
        viewModelScope.launch {
            mutex.withLock {
                if (acceptsScan) {
                    when (result) {
                        is ImageToQrCodeResult.SingleCode -> {
                            onScanned(result.text)
                        }

                        ImageToQrCodeResult.MultipleCodes -> {
                            hasBeenScannedSuccessfully = false
                            state.update { ScanValidationState.SEVERAL_CODES_FOUND }
                        }

                        ImageToQrCodeResult.NoCode -> {
                            hasBeenScannedSuccessfully = false
                            state.update { ScanValidationState.INVALID_IMAGE }
                        }
                    }
                }
            }
        }
}
