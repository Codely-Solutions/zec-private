package co.electriccoin.zcash.ui.screen.signkeystonetransaction

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.util.StringResource

data class SignKeystoneTransactionState(
    val onBack: () -> Unit,
    val barTitle: StringResource,
    val title: StringResource,
    val subtitle: StringResource,
    val accountInfo: ZashiAccountInfoListItemState,
    val badgeText: StringResource,
    val qrData: String?,
    val generateNextQrCode: () -> Unit,
    val secondaryButton: ButtonState?,
    val positiveButton: ButtonState,
    val negativeButton: ButtonState,
    val preparation: KeystoneQrPreparation = KeystoneQrPreparation.READY,
    val retryButton: ButtonState? = null,
) {
    fun toQrState(
        contentDescription: StringResource? = null,
        centerImageResId: Int? = null,
    ): QrState {
        requireNotNull(qrData) { "The QR code data needs to be set at this point" }
        return QrState(
            qrData = qrData,
            contentDescription = contentDescription,
            centerImage = centerImageResId
        )
    }
}

enum class KeystoneQrPreparation { LOADING, ERROR, READY }

data class ZashiAccountInfoListItemState(
    @field:DrawableRes val icon: Int,
    val title: StringResource,
    val subtitle: StringResource,
)
