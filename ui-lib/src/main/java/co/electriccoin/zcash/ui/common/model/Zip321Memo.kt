package co.electriccoin.zcash.ui.common.model

import java.nio.charset.CharacterCodingException

/** ZIP 302 text presentation. Binary/future formats retain their bytes in the original URI. */
fun ByteArray.zip321MemoTextOrNull(): String? {
    val first = firstOrNull()?.toInt()?.and(0xff) ?: return ""
    if (first == 0xf6 && drop(1).all { it == 0.toByte() }) return ""
    if (first >= 0xf5) return null
    val end = indexOfLast { it != 0.toByte() } + 1
    return try {
        decodeToString(endIndex = end, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

class UneditableZip321MemoException : Exception("A binary payment memo cannot be converted to editable text")
