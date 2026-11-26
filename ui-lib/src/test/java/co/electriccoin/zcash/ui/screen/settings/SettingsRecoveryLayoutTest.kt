package co.electriccoin.zcash.ui.screen.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.RadioButtonState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerState
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerView
import co.electriccoin.zcash.ui.screen.chooseserver.ServerConnectionModeState
import co.electriccoin.zcash.ui.screen.chooseserver.ServerListState
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsLoadView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h850dp")
class SettingsRecoveryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTorReadErrorShowsRetryAndClose() {
        val resources = RuntimeEnvironment.getApplication().resources
        var retries = 0
        var closes = 0
        compose.setContent {
            ZcashTheme {
                TorSettingsLoadView(false, onBack = { closes++ }, onRetry = { retries++ })
            }
        }

        compose.onNodeWithText(resources.getString(R.string.tor_settings_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.wallet_initialization_retry))
            .assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(resources.getString(R.string.general_close))
            .assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, closes)
        }
    }

    @Test
    fun theManualBenchmarkCanRefreshAnEmptyResult() {
        val resources = RuntimeEnvironment.getApplication().resources
        var refreshes = 0
        compose.setContent {
            ZcashTheme {
                ChooseServerView(
                    ChooseServerState(
                        connectionMode = ServerConnectionModeState(
                            automatic = RadioButtonState(stringRes("Automatic"), isChecked = false, onClick = {}),
                            manual = RadioButtonState(stringRes("Manual"), isChecked = true, onClick = {}),
                        ),
                        fastest = ServerListState.Fastest(
                            title = stringRes("Fastest servers"),
                            servers = emptyList(),
                            retryButton = ButtonState(
                                stringRes(R.string.serverSetup_refresh),
                                onClick = { refreshes++ },
                            ),
                            isLoading = false,
                        ),
                        other = ServerListState.Other(stringRes("Other servers"), emptyList()),
                        saveButton = ButtonState(stringRes("Save"), onClick = {}),
                        dialogState = null,
                        onBack = {},
                    ),
                )
            }
        }

        compose.onNodeWithText(resources.getString(R.string.serverSetup_refresh)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }
}
