package co.electriccoin.zcash.ui.screen.donation

import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DonationPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun suggestionsDefaultToZeroAndPersistExactPercent() {
        assertEquals(BigDecimal.ZERO, DonationPreferences(context).percent)
        DonationPreferences(context).percent = BigDecimal("1.23456789")
        assertEquals(BigDecimal("1.23456789"), DonationPreferences(context).percent)
    }

    @Test
    fun invalidStoredPercentDefaultsToZeroAndInvalidWritesAreRejected() {
        val storage = context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE)
        for (value in listOf("invalid", "-1", "101")) {
            storage.edit().putString("percent", value).commit()
            assertEquals(BigDecimal.ZERO, DonationPreferences(context).percent)
        }
        for (value in listOf("-0.01", "100.01")) {
            assertFailsWith<IllegalArgumentException> { DonationPreferences(context).percent = BigDecimal(value) }
        }
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
