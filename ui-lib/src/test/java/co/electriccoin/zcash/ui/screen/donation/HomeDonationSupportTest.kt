package co.electriccoin.zcash.ui.screen.donation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeDonationSupportTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        RuntimeEnvironment.getApplication().getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun introductionSurvivesInitialLifecycleSetupButDoesNotRepeatOnReturn() {
        var mounted by mutableStateOf(true)
        var state = HomeDonationSupportState()
        compose.setContent {
            if (mounted) state = rememberHomeDonationSupport(blocked = false, onDonate = {})
        }
        compose.runOnIdle {
            assertTrue(state.showIntroduction)
            assertFalse(state.showInvitation)
            mounted = false
        }
        compose.runOnIdle { mounted = true }
        compose.runOnIdle { assertFalse(state.showIntroduction) }
    }

    @Test
    fun ignoredInvitationRemainsVisibleForItsVisitAndIsSuppressedOnReturn() {
        DonationPreferences(RuntimeEnvironment.getApplication()).recordSuccessfulTransaction()
        var mounted by mutableStateOf(true)
        var state = HomeDonationSupportState()
        compose.setContent {
            if (mounted) state = rememberHomeDonationSupport(blocked = false, onDonate = {})
        }
        compose.runOnIdle {
            assertTrue(state.showInvitation)
            assertFalse(state.showIntroduction)
            mounted = false
        }
        compose.runOnIdle { mounted = true }
        compose.runOnIdle {
            assertFalse(state.showInvitation)
            assertFalse(state.showIntroduction)
        }
    }
}
