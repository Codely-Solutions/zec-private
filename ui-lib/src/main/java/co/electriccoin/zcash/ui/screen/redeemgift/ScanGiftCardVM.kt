package co.electriccoin.zcash.ui.screen.redeemgift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.NavigateToRedeemGiftCardUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.redeemgift.paste.PasteGiftCardLinkArgs
import co.electriccoin.zcash.ui.screen.scan.ImageToQrCodeResult
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The first step of the redeem flow: scan a gift card QR code, pick an image of one, or go on to paste its link.
 * The current scanner can admit one navigation while its delivery is pending.
 */
class ScanGiftCardVM(
    args: ScanGiftCardArgs,
    private val navigateToRedeemGiftCard: NavigateToRedeemGiftCardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            ScanGiftCardState(
                validation = ScanValidationState.NONE,
                invalidQrText = stringRes(R.string.redeemGift_scan_invalid),
                infoText = if (args.isFromExternalLink) stringRes(R.string.redeemGift_scan_externalLink) else null,
                onBack = ::onBack
            )
        )

    val state: StateFlow<ScanGiftCardState> = mutableState.asStateFlow()

    private var hasBeenScannedSuccessfully = false
    private var isDelivering = false
    private val canAct get() =
        !hasBeenScannedSuccessfully && !isDelivering && navigationRouter.isAtEntry(sourceEntryId)

    fun onScanned(result: String) = viewModelScope.launch { handle(result) }

    /**
     * Opens the screen where the user pastes or types the gift card link; the scanner stays below it. Ignored once a
     * scanned link is taking the scanner's place.
     */
    fun onPaste() {
        viewModelScope.launch {
            if (canAct) {
                isDelivering = true
                try {
                    navigationRouter.commitFrom(sourceEntryId) {
                        NavigationCommand.Forward(listOf(PasteGiftCardLinkArgs))
                    }
                } finally {
                    isDelivering = false
                }
            }
        }
    }

    fun onScannedError() =
        viewModelScope.launch {
            if (canAct) setValidation(ScanValidationState.INVALID)
        }

    fun onImageScanned(result: ImageToQrCodeResult) =
        viewModelScope.launch {
            if (!canAct) return@launch
            when (result) {
                is ImageToQrCodeResult.SingleCode -> handle(result.text)
                ImageToQrCodeResult.MultipleCodes -> setValidation(ScanValidationState.SEVERAL_CODES_FOUND)
                ImageToQrCodeResult.NoCode -> setValidation(ScanValidationState.INVALID_IMAGE)
            }
        }

    private fun onBack() {
        viewModelScope.launch { navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back } }
    }

    private suspend fun handle(text: String) {
        if (!canAct) return
        if (navigateToRedeemGiftCard.isGiftCardLink(text)) {
            isDelivering = true
            try {
                hasBeenScannedSuccessfully =
                    navigateToRedeemGiftCard.replaceGiftCardScanWithRedeem(text.trim(), sourceEntryId)
                val validation = if (hasBeenScannedSuccessfully) ScanValidationState.VALID else ScanValidationState.NONE
                setValidation(validation)
            } finally {
                isDelivering = false
            }
        } else {
            setValidation(ScanValidationState.INVALID)
        }
    }

    private fun setValidation(validation: ScanValidationState) =
        mutableState.update { it.copy(validation = validation) }
}
