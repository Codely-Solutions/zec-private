package co.electriccoin.zcash.ui.common.serialization

import co.electriccoin.zcash.ui.common.model.near.SwapStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object NearSwapStatusSerializer : KSerializer<SwapStatus> {
    override val descriptor = PrimitiveSerialDescriptor("SwapStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SwapStatus) = encoder.encodeString(value.apiValue)

    override fun deserialize(decoder: Decoder): SwapStatus {
        val decoded = decoder.decodeString()
        return SwapStatus
            .entries
            .firstOrNull { it.apiValue == decoded } ?: throw SerializationException("Unsupported 1Click status")
    }
}
