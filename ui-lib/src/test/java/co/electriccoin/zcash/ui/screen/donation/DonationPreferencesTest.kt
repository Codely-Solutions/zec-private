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

    @Test
    fun introductionCanBeClaimedOnlyOnceAcrossRecreation() {
        assertTrue(DonationPreferences(context).claimAutomaticIntroduction())
        assertFalse(DonationPreferences(context).claimAutomaticIntroduction())
    }

    @Test
    fun permanentDeclineAlsoSuppressesAutomaticIntroduction() {
        DonationPreferences(context).declinePermanently()
        assertFalse(DonationPreferences(context).claimAutomaticIntroduction())
    }

    @Test
    fun homeEntrySnapshotWaitsForSuccessfulActivityOnALaterVisit() {
        val preferences = DonationPreferences(context)
        val entryEligibility = preferences.mayInviteOnHomeEntry(1_000_000)
        preferences.recordSuccessfulTransaction()
        assertFalse(entryEligibility)
        assertTrue(DonationPreferences(context).mayInviteOnHomeEntry(1_000_000))
    }

    @Test
    fun displayingInvitationStartsCooldownWithoutRequiringDismissal() {
        val preferences = DonationPreferences(context)
        preferences.recordSuccessfulTransaction()
        preferences.recordInvitationDisplayed(1_000_000)
        assertFalse(DonationPreferences(context).mayInviteOnHomeEntry(1_000_000))
        assertTrue(preferences.mayInviteOnHomeEntry(1_000_000 + DonationPolicy.COOLDOWN_MILLIS))
    }

    @Test
    fun replayedDonationHistoryDoesNotExtendCooldownOrQualifyActivity() {
        val preferences = DonationPreferences(context)
        val completedAt = 1_000_000L
        preferences.recordDonationCompleted(completedAt, now = completedAt)
        preferences.recordDonationCompleted(completedAt - 1, now = completedAt - 1)
        preferences.recordDonationCompleted(completedAt, now = completedAt)
        assertFalse(preferences.mayInvite(completedAt + DonationPolicy.COOLDOWN_MILLIS - 1))
        assertTrue(preferences.mayInvite(completedAt + DonationPolicy.COOLDOWN_MILLIS))
        assertFalse(preferences.mayInviteOnHomeEntry(completedAt + DonationPolicy.COOLDOWN_MILLIS))
    }

    @Test
    fun historicalDonationCannotShortenRecentInvitationCooldown() {
        val preferences = DonationPreferences(context)
        preferences.recordInvitationDisplayed(2_000_000)
        preferences.recordDonationCompleted(1_000_000, now = 1_000_000)
        assertFalse(preferences.mayInvite(1_000_000 + DonationPolicy.COOLDOWN_MILLIS))
    }

    @Test
    fun donationCooldownDefersIntroductionWithoutConsumingIt() {
        val preferences = DonationPreferences(context)
        preferences.recordDonationCompleted(1_000_000, now = 1_000_000)
        assertFalse(preferences.claimAutomaticIntroduction(1_000_000))
        assertTrue(preferences.claimAutomaticIntroduction(1_000_000 + DonationPolicy.COOLDOWN_MILLIS))
        assertFalse(DonationPreferences(context).claimAutomaticIntroduction(1_000_000 + DonationPolicy.COOLDOWN_MILLIS))
    }
    @Test
    fun confirmedDonationGetsFullCooldownFromObservationRatherThanMinedTime() {
        val preferences = DonationPreferences(context)
        preferences.recordDonationCompleted(1_000_000, now = 2_000_000)
        assertFalse(preferences.mayInvite(2_000_000 + DonationPolicy.COOLDOWN_MILLIS - 1))
        assertTrue(preferences.mayInvite(2_000_000 + DonationPolicy.COOLDOWN_MILLIS))
        preferences.recordDonationCompleted(1_000_000, now = 3_000_000)
        assertTrue(preferences.mayInvite(2_000_000 + DonationPolicy.COOLDOWN_MILLIS))
    }

}
