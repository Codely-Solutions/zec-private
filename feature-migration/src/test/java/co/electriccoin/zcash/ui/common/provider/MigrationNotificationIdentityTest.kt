package co.electriccoin.zcash.ui.common.provider

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MigrationNotificationIdentityTest {
    @Test
    fun fullAccountActionDistinguishesLegacyHashCollision() {
        val first = "00000000000040008000000000000000"
        val second = "00000000000040008000000000002662"
        assertEquals(first.hashCode() and 0xFFFF, second.hashCode() and 0xFFFF)

        val progress = MigrationNotifier.EXTRA_OPEN_MIGRATION
        val runStep = MigrationNotifier.EXTRA_RUN_STEP
        assertNotEquals(migrationIntentAction(progress, first), migrationIntentAction(progress, second))
        assertNotEquals(migrationIntentAction(runStep, first), migrationIntentAction(progress, first))
        assertEquals("$runStep.$first", migrationIntentAction(runStep, first))
    }

    @Test
    fun terminalCompletionCanAlertWhenUpdatingTheProgressNotification() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<NotificationManagerCompat>(relaxed = true)
        val notification = mockk<Notification>()
        val owner = "00000000000040008000000000000001"
        mockkStatic(PendingIntent::class, NotificationManagerCompat::class)
        mockkConstructor(NotificationCompat.Builder::class)
        try {
            every { PendingIntent.getActivity(context, any(), any(), any()) } returns mockk()
            every { NotificationManagerCompat.from(context) } returns manager
            every { anyConstructed<NotificationCompat.Builder>().build() } returns notification

            MigrationNotifier(context).notifyMigrationComplete(owner)

            verify(exactly = 1) { manager.notify(owner, any(), notification) }
            verify(exactly = 0) { anyConstructed<NotificationCompat.Builder>().setOnlyAlertOnce(true) }
        } finally {
            unmockkConstructor(NotificationCompat.Builder::class)
            unmockkStatic(PendingIntent::class, NotificationManagerCompat::class)
        }
    }
}
