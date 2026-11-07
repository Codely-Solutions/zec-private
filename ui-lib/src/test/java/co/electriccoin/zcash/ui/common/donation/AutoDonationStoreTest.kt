package co.electriccoin.zcash.ui.common.donation

import android.content.Context
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDonationStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("private_auto_donation_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun legacyPercentageDoesNotAuthorizeSpending() {
        DonationPreferences(context).percent = BigDecimal.ONE
        assertNull(AutoDonationStore(context).consent())
    }

    @Test fun newAuthorizationHasFreshIdentityAndDisableSurvivesReload() {
        val store = AutoDonationStore(context)
        val first = store.authorize("account", BigDecimal("0.5"))
        assertEquals(first, AutoDonationStore(context).consent())
        val second = store.authorize("account", BigDecimal("0.5"))
        assertNotEquals(first.id, second.id)
        store.disable()
        assertNull(AutoDonationStore(context).consent())
    }

    @Test fun reservationSurvivesCrashBeforeResultAndCannotBeReenrolled() {
        val store = AutoDonationStore(context)
        store.enroll("sensitive-deposit", JSONObject().put("consent", "one"))
        assertNotNull(store.reserve("sensitive-deposit"))
        val reopened = AutoDonationStore(context)
        assertNull(reopened.reserve("sensitive-deposit"))
        reopened.enroll("sensitive-deposit", JSONObject().put("consent", "two"))
        assertNull(reopened.reserve("sensitive-deposit"))
        val keys = context.getSharedPreferences("private_auto_donation_v1", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.none { it.contains("sensitive-deposit") })
    }

    @Test fun transactionIdsStaySeparateFromReadableOutcome() {
        val store = AutoDonationStore(context)
        store.recordTransactionIds("swap", listOf("transaction-id"))
        store.outcome("swap", "Automatic donation outcome uncertain. Check Activity.")
        assertEquals("Automatic donation outcome uncertain. Check Activity.", store.latestOutcome())
        val prefs = context.getSharedPreferences("private_auto_donation_v1", Context.MODE_PRIVATE)
        assertEquals("[\"transaction-id\"]", prefs.getString("transactions:${store.hash("swap")}", null))
    }

    @Test fun unrelatedAndHistoricalSwapsHaveNoReservation() {
        assertNull(AutoDonationStore(context).reserve("never-enrolled"))
    }
}
