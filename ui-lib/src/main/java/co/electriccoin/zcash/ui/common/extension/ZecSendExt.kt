package co.electriccoin.zcash.ui.common.extension

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.sdk.extension.floor
import co.electriccoin.zcash.ui.common.model.zcash.MAX_ZATOSHI_SUPPLY

fun ZecSend.totalAmount() = amount + (proposal?.totalFeeRequired() ?: Zatoshi(0))

internal fun Zatoshi.normalizedSendAmount(fiatInput: Boolean): Zatoshi? {
    if (value !in 0L..MAX_ZATOSHI_SUPPLY) return null
    return if (fiatInput) floor().takeIf { it.value > 0L } else this
}
