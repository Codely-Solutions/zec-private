package co.electriccoin.zcash.ui.common.provider

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class MigrationWalletNoticeCleanupTest {
    @Test
    fun aWalletResetSweepLeavesNonMigrationNotificationsAlone() {
        fun entry(channel: String, tag: String, id: Int) = mockk<StatusBarNotification> {
            every { notification } returns mockk<Notification> { every { channelId } returns channel }
            every { this@mockk.tag } returns tag
            every { this@mockk.id } returns id
        }
        val manager = mockk<NotificationManager>(relaxed = true) {
            every { activeNotifications } returns arrayOf(
                entry(MigrationNotifier.CHANNEL_ID, "orphaned", 10),
                entry("migration_channel", "older", 11),
                entry("unrelated", "keep", 12),
            )
        }
        val context = mockk<Context> {
            every { getSystemService(NotificationManager::class.java) } returns manager
        }

        MigrationNotifier(context).cancelWalletMigrationNotices()

        verify(exactly = 1) { manager.cancel("orphaned", 10) }
        verify(exactly = 1) { manager.cancel("older", 11) }
        verify(exactly = 0) { manager.cancel("keep", 12) }
    }
}
