package co.electriccoin.zcash.ui.common.model.near

import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.subtle.Ed25519Verifier
import com.google.crypto.tink.util.Bytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** A verified original response. It carries private routing data and is shared only on request. */
@Serializable
data class NearSignedQuoteEvidence internal constructor(internal val originalJson: String) {
    init {
        require(originalJson.isNotEmpty() && originalJson.length <= MAX_QUOTE_EVIDENCE_BYTES &&
            originalJson.encodeToByteArray().size <= MAX_QUOTE_EVIDENCE_BYTES)
    }

    override fun toString() = "NearSignedQuoteEvidence([redacted])"
}

internal const val MAX_QUOTE_EVIDENCE_BYTES = 16 * 1024

// The provider SDK selects fields before spreading the request, quote, then timestamp and sorting the keys.
// https://github.com/defuse-protocol/one-click-sdk-typescript/blob/main/src/quote-signature.ts
private val signedRequestFields = setOf(
    "dry", "swapType", "slippageTolerance", "originAsset", "depositType", "destinationAsset", "amount",
    "refundTo", "refundType", "recipient", "recipientType", "deadline"
)
private val optionalRequestFields = setOf(
    "quoteWaitingTimeMs", "referral", "virtualChainRecipient", "virtualChainRefundRecipient", "customRecipientMsg"
)
private val signedQuoteFields = setOf(
    "amountIn", "amountInFormatted", "amountInUsd", "minAmountIn",
    "amountOut", "amountOutFormatted", "amountOutUsd", "minAmountOut"
)
private val optionalQuoteFields = setOf(
    "depositAddress", "depositMemo", "deadline", "timeWhenInactive", "timeEstimate", "refundFee", "withdrawFee"
)

internal fun verifiedNearQuoteEvidence(
    originalJson: String,
    managerPublicKey: String = NEAR_QUOTE_MANAGER_PUBLIC_KEY,
): NearSignedQuoteEvidence? {
    if (originalJson.length > MAX_QUOTE_EVIDENCE_BYTES ||
        originalJson.encodeToByteArray().size > MAX_QUOTE_EVIDENCE_BYTES
    ) return null
    return try {
        val response = requireNotNull(Json.parseToJsonElement(originalJson) as? JsonObject)
        val request = requireNotNull(response["quoteRequest"] as? JsonObject)
        val quote = requireNotNull(response["quote"] as? JsonObject)
        require(request["dry"] == JsonPrimitive(false))
        val signature = requireNotNull(response["signature"] as? JsonPrimitive)
        require(signature.isString)
        val key = Ed25519PublicKey.create(Bytes.copyFrom(decodeBase58(managerPublicKey)))
        Ed25519Verifier.create(key).verify(decodeBase58(signature.content), quoteMessage(response, request, quote))
        NearSignedQuoteEvidence(originalJson)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    }
}

private fun quoteMessage(response: JsonObject, request: JsonObject, quote: JsonObject): ByteArray {
    val signedFields = sortedMapOf<String, JsonPrimitive>()
    request.selectFields(signedFields, signedRequestFields, optionalRequestFields)
    quote.selectFields(signedFields, signedQuoteFields, optionalQuoteFields)
    signedFields["timestamp"] = requireNotNull(response["timestamp"] as? JsonPrimitive).also { require(it.isString) }
    val canon = JsonObject(signedFields).toString()
    return encodeBase58(MessageDigest.getInstance("SHA-256").digest(canon.encodeToByteArray())).encodeToByteArray()
}

private fun JsonObject.selectFields(
    signedFields: MutableMap<String, JsonPrimitive>,
    fields: Set<String>,
    optional: Set<String>,
) {
    for ((name, value) in this) {
        if (name in fields || name in optional) {
            val primitive = requireNotNull(value as? JsonPrimitive)
            val normalized = primitive.canonicalize()
            if (name in fields || normalized !in optionalSignedFieldEmptyValues) {
                signedFields[name] = normalized
            }
        }
    }
}

private val optionalSignedFieldEmptyValues = setOf(JsonNull, JsonPrimitive(false), JsonPrimitive(0), JsonPrimitive(""))

// The signed numeric fields in supported routes are small integers. Decline evidence for other
// numeric forms rather than hashing a spelling different from JavaScript's JSON.stringify.
private fun JsonPrimitive.canonicalize(): JsonPrimitive {
    if (isString || this == JsonNull || content == "true" || content == "false") return this
    require(content.length <= MAX_SIGNED_NUMBER_LENGTH)
    val integer = content.toBigDecimal().longValueExact()
    require(integer in -MAX_JS_SAFE_INTEGER..MAX_JS_SAFE_INTEGER)
    return JsonPrimitive(integer)
}

private fun decodeBase58(value: String): ByteArray {
    val encoded = value.removePrefix("ed25519:")
    require(encoded.length in 1..MAX_BASE58_SIGNATURE_LENGTH)
    val number = encoded.fold(BigInteger.ZERO) { number, char ->
        val digit = BASE58_ALPHABET.indexOf(char)
        require(digit >= 0)
        number * BASE58_RADIX + digit.toBigInteger()
    }
    val leadingZeroes = encoded.takeWhile { it == BASE58_ALPHABET.first() }.length
    val bytes = number.toByteArray()
    return ByteArray(leadingZeroes) + if (bytes.first() == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}

private fun encodeBase58(bytes: ByteArray): String = buildString {
    var number = BigInteger(1, bytes)
    while (number.signum() != 0) {
        val (quotient, remainder) = number.divideAndRemainder(BASE58_RADIX)
        append(BASE58_ALPHABET[remainder.toInt()])
        number = quotient
    }
    repeat(bytes.takeWhile { it == 0.toByte() }.size) { append(BASE58_ALPHABET.first()) }
    reverse()
}

private const val NEAR_QUOTE_MANAGER_PUBLIC_KEY = "ed25519:reYaWhvwu8Jzo3WUM3zhn6VrhuMEF4eADL17qtRVifc"
private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val MAX_BASE58_SIGNATURE_LENGTH = 88
private const val MAX_SIGNED_NUMBER_LENGTH = 32
private const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L
private val BASE58_RADIX = BASE58_ALPHABET.length.toBigInteger()
