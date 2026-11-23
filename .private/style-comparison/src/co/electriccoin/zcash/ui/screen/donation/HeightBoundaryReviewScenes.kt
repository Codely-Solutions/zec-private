package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldParser
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.BlockHeightState
import co.electriccoin.zcash.ui.screen.common.BlockHeightView
import co.electriccoin.zcash.ui.screen.common.birthdayHeightOrNull
import co.electriccoin.zcash.ui.design.R as DesignR

internal const val HEIGHT_REVIEW_ACTION = "HEIGHT_REVIEW_ACTION"

@Composable
internal fun HeightBoundaryReviewScene(screen: String): Boolean {
    val minimum = VersionInfo.NETWORK.saplingActivationHeight.value
    val maximum = UInt.MAX_VALUE.toLong()
    val input = when (screen) {
        "restore-height-minimum" -> minimum.toString()
        "restore-height-fraction" -> "$minimum.5"
        "resync-height-maximum", "hardware-height-maximum" -> maximum.toString()
        "resync-height-overflow", "hardware-height-overflow" -> (maximum + 1).toString()
        else -> return false
    }
    val isRestore = screen.startsWith("restore")
    val isHardware = screen.startsWith("hardware")
    val title = when {
        isRestore -> R.string.root_existingWallet_restore
        isHardware -> null
        else -> R.string.resyncWallet_title
    }
    val action = when {
        isRestore -> R.string.root_existingWallet_restore
        isHardware -> R.string.keystone_addHWWallet_connect
        else -> DesignR.string.general_confirm
    }
    val normalized = ZashiNumberTextFieldParser.normalizeInput(input)
    val height = NumberTextFieldInnerState(
        innerTextFieldState = InnerTextFieldState(value = stringRes(normalized)),
        amount = ZashiNumberTextFieldParser.toBigDecimalOrNull(normalized),
    )
    BlockHeightView(
        BlockHeightState(
            title = title?.let { stringRes(it) },
            logo = DesignR.drawable.image_keystone.takeIf { isHardware },
            blockHeight = NumberTextFieldState(height, onValueChange = {}),
            primaryButton = ButtonState(
                stringRes(action),
                isEnabled = height.birthdayHeightOrNull() != null,
                onClick = {},
            ),
            secondaryButton = if (isRestore) {
                ButtonState(stringRes(R.string.restoreWallet_birthday_estimate), onClick = {})
            } else {
                null
            },
            dialogButton = IconButtonState(icon = R.drawable.ic_help, onClick = {}),
            onBack = {},
            primaryButtonTestTag = HEIGHT_REVIEW_ACTION,
        )
    )
    return true
}
