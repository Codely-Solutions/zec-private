package co.electriccoin.zcash.ui.screen.qrcode

import androidx.annotation.DrawableRes
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource

sealed class QrCodeState {
    abstract val onBack: () -> Unit

    data class Loading(override val onBack: () -> Unit) : QrCodeState()

    data class Prepared(
        val title: StringResource,
        val formatterAddress: StyledStringResource,
        val walletAddress: WalletAddress,
        val onAddressCopy: (String) -> Unit,
        val onQrCodeShare: (String) -> Unit,
        override val onBack: () -> Unit,
    ) : QrCodeState() {
        fun toQrState(
            contentDescription: StringResource? = null,
            @DrawableRes centerImageResId: Int? = null
        ) = QrState(
            qrData = walletAddress.address,
            contentDescription = contentDescription,
            centerImage = centerImageResId
        )
    }
}
