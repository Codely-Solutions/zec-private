package co.electriccoin.zcash.ui.screen.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class HomeSeedMismatchNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aMismatchWaitsForTheUnlockedResumedHomeAndOffersSupport() {
        val access = mutableStateOf<AuthenticationUIState>(AuthenticationUIState.Required)
        val welcome = mutableStateOf(true)
        val resumed = mutableStateOf(Lifecycle.State.RESUMED)
        var opened = 0
        compose.setContent {
            ZcashTheme {
                SeedMismatchNotice(
                    mismatch = true,
                    authorized = allowMismatchNotice(access.value, welcome.value, resumed.value),
                    canDismiss = false,
                    onSupport = { opened++ },
                )
            }
        }

        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle {
            access.value = AuthenticationUIState.Successful
        }
        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle {
            welcome.value = false
        }
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Contact support").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            resumed.value = Lifecycle.State.STARTED
        }
        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle {
            resumed.value = Lifecycle.State.RESUMED
            access.value = AuthenticationUIState.Initial
        }
        compose.onNodeWithText(TITLE).assertDoesNotExist()
    }

    @Test
    fun theOrdinaryHomeHasNoNoticeAndADismissedMismatchCanResolve() {
        val mismatch = mutableStateOf(false)
        compose.setContent {
            ZcashTheme {
                SeedMismatchNotice(mismatch.value, authorized = true, canDismiss = true, onSupport = {})
            }
        }
        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle { mismatch.value = true }
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle { mismatch.value = false }
        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.runOnIdle { mismatch.value = true }
        compose.onNodeWithText(TITLE).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Wallet data unavailable"
    }
}
