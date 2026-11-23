package co.electriccoin.zcash.ui.screen.common

import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState

private val minimumBirthdayHeight = VersionInfo.NETWORK.saplingActivationHeight.value.toBigDecimal()
private val maximumBirthdayHeight = UInt.MAX_VALUE.toLong().toBigDecimal()

internal fun NumberTextFieldInnerState.birthdayHeightOrNull(): BlockHeight? {
    val height = amount?.takeUnless { innerTextFieldState.value.isEmpty() }
    return if (height != null && height in minimumBirthdayHeight..maximumBirthdayHeight) {
        try {
            BlockHeight.new(height.longValueExact())
        } catch (_: ArithmeticException) {
            null
        }
    } else {
        null
    }
}
