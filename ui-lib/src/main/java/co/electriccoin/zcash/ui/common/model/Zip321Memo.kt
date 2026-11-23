package co.electriccoin.zcash.ui.common.model

import java.nio.charset.CharacterCodingException

private const val FIRST_OPAQUE_MEMO_MARKER = 0xf5
private const val EMPTY_MEMO_MARKER = 0xf6

/** ZIP 302 text presentation. Binary/future formats retain their bytes in the original URI. */
fun ByteArray.zip321MemoTextOrNull(): String? =
    when (val first = firstOrNull()?.toUByte()?.toInt()) {
        null -> ""
        EMPTY_MEMO_MARKER -> "".takeIf { drop(1).all { it == 0.toByte() } }
        else -> if (first >= FIRST_OPAQUE_MEMO_MARKER) null else decodeZip321MemoText()
    }

private fun ByteArray.decodeZip321MemoText(): String? =
    try {
        decodeToString(endIndex = indexOfLast { it != 0.toByte() } + 1, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
