package cash.z.ecc.sdk.extension

import cash.z.ecc.android.sdk.model.Zatoshi

val Zatoshi.Companion.ZERO: Zatoshi
    get() = Zatoshi(0)

const val FIAT_ZATOSHI_INCREMENT = 5_000L

fun Zatoshi.floor(): Zatoshi = Zatoshi(value - value % FIAT_ZATOSHI_INCREMENT)

val Zatoshi.Companion.typicalFee: Zatoshi
    get() = Zatoshi(TYPICAL_FEE)

private const val TYPICAL_FEE = 100000L
