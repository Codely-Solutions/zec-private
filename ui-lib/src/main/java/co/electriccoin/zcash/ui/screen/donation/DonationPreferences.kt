package co.electriccoin.zcash.ui.screen.donation

import android.content.Context
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy

/** Local suppression settings for the optional donation invitation. */
@Suppress("MagicNumber")
class DonationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE)

    fun mayInvite(now: Long): Boolean {
        val last = preferences.getLong("last_invitation", 0)
        return !preferences.getBoolean("never_ask", false) &&
            (last == 0L || (now >= last && now - last >= DonationPolicy.COOLDOWN_MILLIS))
    }

    fun postpone(now: Long) = preferences.edit().putLong("last_invitation", now).apply()

    fun declinePermanently() = preferences.edit().putBoolean("never_ask", true).apply()
}
