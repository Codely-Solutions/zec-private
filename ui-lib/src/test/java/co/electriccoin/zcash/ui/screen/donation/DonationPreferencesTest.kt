package co.electriccoin.zcash.ui.screen.donation

import android.content.Context
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DonationPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun postponementPersistsAndExpiresAtCooldownBoundary() {
        val now = 1_000_000L
        assertTrue(DonationPreferences(context).mayInvite(now))
        DonationPreferences(context).postpone(now)
        val restored = DonationPreferences(context)
        assertFalse(restored.mayInvite(now))
        assertFalse(restored.mayInvite(now - 1))
        assertFalse(restored.mayInvite(now + DonationPolicy.COOLDOWN_MILLIS - 1))
        assertTrue(restored.mayInvite(now + DonationPolicy.COOLDOWN_MILLIS))
    }

    @Test
    fun permanentDeclineSurvivesRecreationAndLaterPostponement() {
        DonationPreferences(context).declinePermanently()
        DonationPreferences(context).postpone(1_000_000)
        assertFalse(DonationPreferences(context).mayInvite(1_000_000 + 10 * DonationPolicy.COOLDOWN_MILLIS))
    }
}
