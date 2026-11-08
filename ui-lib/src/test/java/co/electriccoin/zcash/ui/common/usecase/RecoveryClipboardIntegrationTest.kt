package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryClipboardIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val foreground = MutableStateFlow(true)
    private lateinit var clipboard: ClipboardManager
    private lateinit var copyRecovery: CopyRecoveryToClipboardUseCase

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val context = RuntimeEnvironment.getApplication()
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        val applicationState = mockk<ApplicationStateProvider>()
        every { applicationState.isInForeground } returns foreground
        copyRecovery = CopyRecoveryToClipboardUseCase(context, applicationState, CopyToClipboardUseCase(context))
        scheduler.runCurrent()
    }

    @AfterTest
    fun tearDown() {
        // The production singleton owns an application-lifetime scope. Retire this test's instance explicitly.
        val scopeField = CopyRecoveryToClipboardUseCase::class.java.getDeclaredField("scope")
        scopeField.isAccessible = true
        (scopeField.get(copyRecovery) as CoroutineScope).cancel()
        scheduler.runCurrent()
        clipboard.clearPrimaryClip()
        Dispatchers.resetMain()
    }

    @Test
    fun ownedFakeClipExpiresAfterSixtySecondsInForeground() {
        copyRecovery("fake recovery phrase")
        scheduler.runCurrent()
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertNotNull(clipboard.primaryClipDescription!!.extras!!.getString(OWNER_KEY))
        advanceTime(59_999)
        assertEquals("fake recovery phrase", clipboard.primaryClip!!.getItemAt(0).text.toString())
        advanceTime(1)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun backgroundExpiryWaitsForForegroundReturn() {
        copyRecovery("fake birthday 123456")
        scheduler.runCurrent()
        foreground.value = false
        scheduler.runCurrent()
        advanceTime(60_000)
        assertTrue(clipboard.hasPrimaryClip())
        foreground.value = true
        scheduler.runCurrent()
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun newerExternalClipsSurviveIncludingSameTextWithoutOwnershipMetadata() {
        listOf("new fake external text", "fake recovery phrase").forEach { replacement ->
            copyRecovery("fake recovery phrase")
            scheduler.runCurrent()
            clipboard.setPrimaryClip(ClipData.newPlainText("external fake clip", replacement))
            advanceTime(60_000)
            assertEquals(replacement, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    private fun advanceTime(milliseconds: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
        scheduler.advanceTimeBy(milliseconds)
        scheduler.runCurrent()
    }

    private companion object {
        const val OWNER_KEY = "co.electriccoin.zcash.recovery.clipboard.OWNER"
    }
}
