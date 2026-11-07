package co.electriccoin.zcash.ui.screen.donation

import android.content.Context
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy

/** Local discovery and suppression settings for manual donations. */
@Suppress("MagicNumber", "TooManyFunctions")
class DonationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE)

    /** Capture this once on home entry, before observing newly completed activity. */
    fun mayInviteOnHomeEntry(now: Long): Boolean =
        preferences.getBoolean("successful_activity", false) && mayInvite(now)

    fun mayInvite(now: Long): Boolean {
        val last = preferences.getLong("last_invitation", 0)
        return !preferences.getBoolean("never_ask", false) &&
            (last == 0L || (now >= last && now - last >= DonationPolicy.COOLDOWN_MILLIS))
    }

    /** Claim only when the introduction can actually be displayed. */
    fun claimAutomaticIntroduction(now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        if (!mayInvite(now) || preferences.getBoolean("introduction_shown", false)) {
            false
        } else {
            preferences.edit().putBoolean("introduction_shown", true).commit()
        }
    }

    /** Call only for a confirmed ordinary send or a completed swap, excluding donations. */
    fun recordSuccessfulTransaction() {
        if (!preferences.getBoolean("successful_activity", false)) {
            preferences.edit().putBoolean("successful_activity", true).apply()
        }
    }

    fun recordInvitationDisplayed(now: Long) = postpone(now)

    fun postpone(now: Long) = preferences.edit().putLong("last_invitation", now).apply()

    /** Use the confirmed completion time, so re-observing history cannot restart the cooldown. */
    fun recordDonationCompleted(completedAt: Long) = synchronized(LOCK) {
        val lastCompletion = preferences.getLong("last_donation_completion", 0)
        if (completedAt > lastCompletion) {
            preferences.edit()
                .putLong("last_donation_completion", completedAt)
                .putLong("last_invitation", maxOf(completedAt, preferences.getLong("last_invitation", 0)))
                .apply()
        }
    }

    fun declinePermanently() = preferences.edit().putBoolean("never_ask", true).apply()

    private companion object {
        val LOCK = Any()
    }
}
