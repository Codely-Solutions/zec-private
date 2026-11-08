package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipboardManager
import co.electriccoin.zcash.spackle.AndroidApiVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test

class RecoveryClipboardPlatformTest {
    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun modernAndroidClearsOwnedClip() {
        mockkObject(AndroidApiVersion)
        every { AndroidApiVersion.isAtLeastP } returns true
        val clipboard = mockk<ClipboardManager>(relaxUnitFun = true)
        clearRecoveryClipboard(clipboard)
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun api27ReplacesOwnedClipWithEmptyText() {
        mockkObject(AndroidApiVersion)
        every { AndroidApiVersion.isAtLeastP } returns false
        mockkStatic(ClipData::class)
        val empty = mockk<ClipData>()
        every { ClipData.newPlainText("", "") } returns empty
        val clipboard = mockk<ClipboardManager>(relaxUnitFun = true)
        clearRecoveryClipboard(clipboard)
        verify(exactly = 1) { clipboard.setPrimaryClip(empty) }
        verify(exactly = 0) { clipboard.clearPrimaryClip() }
    }
}
