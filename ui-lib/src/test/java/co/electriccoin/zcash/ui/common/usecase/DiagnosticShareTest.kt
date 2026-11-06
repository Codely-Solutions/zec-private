package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import android.content.Intent
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticShareTest {
    @Test(timeout = 30_000)
    fun diagnosticsRequireRecipientSelectionAndContainNoUpstreamAddress() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val useCase = SendEmailUseCase(context, mockk(), mockk())
        useCase(
            address = stringRes("user-selected"),
            subject = stringRes("Diagnostic report"),
            message = stringRes("Review before sharing")
        )
        val launched = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, launched.action)
        @Suppress("DEPRECATION")
        val shared = launched.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, shared.action)
        assertEquals("text/plain", shared.type)
        assertNull(shared.data)
        assertFalse(shared.hasExtra(Intent.EXTRA_EMAIL))
        assertEquals("Diagnostic report", shared.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
