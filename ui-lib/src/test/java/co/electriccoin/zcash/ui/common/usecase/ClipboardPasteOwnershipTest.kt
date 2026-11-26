package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipboardManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardPasteOwnershipTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
    private val read = ReadClipboardTextUseCase(context)
    private val clear = ClearClipboardUseCase(context)

    @Test
    fun readingTheFirstItemOfACompositeClipCannotAuthorizeDeletingItsOtherItem() {
        val multi = ClipData.newPlainText("fixture", "accepted input")
        multi.addItem(ClipData.Item("other application data"))
        clipboard.setPrimaryClip(multi)

        val sample = assertNotNull(read())
        assertEquals("accepted input", sample.text)
        assertNull(sample.ownership)
        assertEquals("other application data", clipboard.primaryClip?.getItemAt(1)?.text)
    }

    @Test
    fun sameTextCopiedLaterRemainsAndAnUnchangedSensitiveItemIsCleared() {
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "accepted input"))
        val first = assertNotNull(read()?.ownership)
        val firstTimestamp = assertNotNull(clipboard.primaryClipDescription).timestamp
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "accepted input"))
        assertTrue(assertNotNull(clipboard.primaryClipDescription).timestamp > firstTimestamp)

        clear(first)
        assertEquals("accepted input", read()?.text)
        clear(assertNotNull(read()?.ownership))
        assertNull(clipboard.primaryClip)
    }

}
