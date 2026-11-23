package co.electriccoin.zcash.ui.fixture

import java.util.Base64

internal object Zip321MemoFixture {
    const val RECIPIENT = "zs1mrhc9y7jdh5r9ece8u5khgvj9kg0zgkxzdduyv0whkg7lkcrkx5xqem3e48avjq9wn2rukydkwn"
    const val TEXT = "Synthetic memo été"
    val binary = byteArrayOf(0xff.toByte(), 0x00, 0x34, 0x12)
    val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)

    fun uri(bytes: ByteArray? = null): String =
        "zcash:$RECIPIENT?amount=0.001" + (bytes?.let { "&memo=${encoded(it)}" } ?: "")

    fun encoded(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
