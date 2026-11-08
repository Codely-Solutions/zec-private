package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.UserManager
import android.widget.Toast
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.ui.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryClipboardWriteTest {
    private val clipboard = mockk<ClipboardManager>(relaxUnitFun = true)
    private val userManager = mockk<UserManager>()
    private val toast = mockk<Toast>(relaxUnitFun = true)
    private val context = mockk<Context>()

    @BeforeTest
    fun setUp() {
        mockkObject(AndroidApiVersion)
        every { AndroidApiVersion.isAtLeastTiramisu } returns true
        every { AndroidApiVersion.isAtLeastUpsideDownCake } returns true
        every { context.getSystemService(ClipboardManager::class.java) } returns clipboard
        every { context.getSystemService(UserManager::class.java) } returns userManager
        every { userManager.isAdminUser } returns false
        mockkStatic(Toast::class)
        every { Toast.makeText(context, R.string.general_copied_to_clipboard, Toast.LENGTH_SHORT) } returns toast
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun ownershipMetadataPreservesSensitiveFlagAndNonAdminFeedback() {
        val clip = slot<ClipData>()
        every { clipboard.setPrimaryClip(capture(clip)) } returns Unit
        val metadata = PersistableBundle().apply {
            putString("fake-owner-key", "fake-owner")
            putBoolean("android.content.extra.IS_SENSITIVE", false)
        }
        CopyToClipboardUseCase(context).copyWithMetadata("fake recovery text", metadata)
        assertEquals("fake-owner", clip.captured.description.extras?.getString("fake-owner-key"))
        assertTrue(clip.captured.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals("fake recovery text", clip.captured.getItemAt(0).text.toString())
        verify(exactly = 1) { toast.show() }
    }

    @Test
    fun existingOneArgumentApiKeepsSensitiveFlag() {
        val clip = slot<ClipData>()
        every { clipboard.setPrimaryClip(capture(clip)) } returns Unit
        CopyToClipboardUseCase(context)("fake existing caller")
        assertTrue(clip.captured.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test
    fun deniedRecoveryWriteReportsFailureOnceWithoutSuccessFeedback() {
        every { clipboard.setPrimaryClip(any()) } throws SecurityException("fake denial")
        var failures = 0
        val copied = copyRecoverySafely(
            write = { CopyToClipboardUseCase(context).copyWithMetadata("fake text", PersistableBundle()) },
            onDenied = { failures++ }
        )
        assertFalse(copied)
        assertEquals(1, failures)
        verify(exactly = 0) { toast.show() }
    }
}
