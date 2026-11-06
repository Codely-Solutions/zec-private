package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.Intent
import co.electriccoin.zcash.ui.util.EmailUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticShareTest {
    @Test
    fun diagnosticsRequireRecipientSelectionAndContainNoUpstreamAddress() {
        val context = mockk<Context>(relaxed = true)
        val launched = slot<Intent>()
        every { context.startActivity(capture(launched)) } returns Unit
        val useCase = SendEmailUseCase(context, mockk(), mockk())
        useCase(
            address = co.electriccoin.zcash.ui.design.util.stringRes("user-selected"),
            subject = co.electriccoin.zcash.ui.design.util.stringRes("Diagnostic report"),
            message = co.electriccoin.zcash.ui.design.util.stringRes("Review before sharing")
        )
        assertEquals(Intent.ACTION_CHOOSER, launched.captured.action)
        @Suppress("DEPRECATION")
        val shared = launched.captured.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, shared.action)
        assertEquals("text/plain", shared.type)
        assertNull(shared.data)
        assertFalse(shared.hasExtra(Intent.EXTRA_EMAIL))
        assertEquals("Diagnostic report", shared.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
