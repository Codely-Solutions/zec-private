package co.electriccoin.zcash.ui.common.model.near

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

private const val MAX_WIRE_LENGTH = 512
private const val MAX_WIRE_EXPONENT = 512
private const val MAX_PRECISION = 128
private const val MAX_COEFFICIENT_BITS = 426 // ceil(log2(10^128))
private const val MAX_SCALE = 320
private const val MAX_TOKEN_DECIMALS = 255
private const val INVALID_DECIMAL = "Invalid or oversized 1Click decimal"

/** Budget used by the 1Click API, including the minimum amount in a quote error. */
internal fun String.toNearProviderDecimalOrNull(): BigDecimal? {
    if (length > MAX_WIRE_LENGTH) return null
    val exponentIndex = indexOf('e', ignoreCase = true)
    val exponent = if (exponentIndex < 0) 0 else substring(exponentIndex + 1).toIntOrNull()
    return if (exponent != null && exponent in -MAX_WIRE_EXPONENT..MAX_WIRE_EXPONENT) {
        toBigDecimalOrNull()?.takeIf { it.fitsNearProviderBudget() }
    } else {
        null
    }
}

internal fun requireNearProviderDecimals(vararg amounts: BigDecimal?) {
    require(amounts.all { it == null || it.fitsNearProviderBudget() }) { INVALID_DECIMAL }
}

internal fun requireNearTokenDecimals(decimals: Int) {
    require(decimals in 0..MAX_TOKEN_DECIMALS) { "Unsupported 1Click token decimals" }
}

private fun BigDecimal.fitsNearProviderBudget(): Boolean {
    if (scale() !in (1 - MAX_PRECISION)..MAX_SCALE || unscaledValue().bitLength() > MAX_COEFFICIENT_BITS) {
        return false
    }
    // A negative scale appends zeros when a number is converted to atomic units or formatted in plain notation.
    val digits = precision()
    return digits <= MAX_PRECISION && digits - scale() <= MAX_PRECISION
}

internal object NearProviderDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NearProviderDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal =
        decoder.decodeString().toNearProviderDecimalOrNull() ?: throw SerializationException(INVALID_DECIMAL)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        requireNearProviderDecimals(value)
        encoder.encodeString(value.toPlainString())
    }
}
