package co.electriccoin.zcash.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
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
import co.electriccoin.zcash.ui.screen.theme.darklook.ThemeDarkLookLoadView
import co.electriccoin.zcash.ui.screen.theme.darklook.ThemeDarkLookState
import co.electriccoin.zcash.ui.screen.theme.darklook.ThemeDarkLookView
import co.electriccoin.zcash.ui.screen.theme.settings.ThemeSettingsState
import co.electriccoin.zcash.ui.screen.theme.settings.ThemeSettingsLoadView
import co.electriccoin.zcash.ui.screen.theme.settings.ThemeSettingsView
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

    @Test
    fun aFailedAppearanceSaveShowsAnErrorWithSaveStillAvailable() {
        assertAnnouncedAppearanceFailure { failed, save ->
            ThemeSettingsView(
                ThemeSettingsState(
                    options = emptyList(),
                    saveButton = ButtonState(stringRes("Save"), onClick = save),
                    onBack = {},
                    hasSaveError = failed,
                )
            )
        }
    }

    @Test
    fun aFailedDarkLookSaveAnnouncesTheFailureAndKeepsSaveAvailable() {
        assertAnnouncedAppearanceFailure { failed, save ->
            ThemeDarkLookView(
                ThemeDarkLookState(
                    options = emptyList(),
                    saveButton = ButtonState(stringRes("Save"), onClick = save),
                    onBack = {},
                    hasSaveError = failed,
                )
            )
        }
    }

    @Test
    fun theAppearanceReadErrorShowsRetryAndClose() {
        assertAppearanceReadRecovery(
            view = { close, retry -> ThemeSettingsLoadView(false, close, retry) },
            assertBack = { compose.onNodeWithContentDescription(it).assertIsDisplayed().performClick() },
            backText = R.string.general_close,
        )
    }

    @Test
    fun theDarkLookReadErrorShowsRetryAndBack() {
        assertAppearanceReadRecovery(
            view = { back, retry -> ThemeDarkLookLoadView(false, back, retry) },
            assertBack = { compose.onNodeWithText(it).assertIsDisplayed().performClick() },
            backText = R.string.general_back,
        )
    }

    private fun assertAppearanceReadRecovery(
        view: @Composable (() -> Unit, () -> Unit) -> Unit,
        assertBack: (String) -> Unit,
        backText: Int,
    ) {
        val resources = RuntimeEnvironment.getApplication().resources
        var retries = 0
        var dismissals = 0
        compose.setContent { ZcashTheme { view({ dismissals++ }, { retries++ }) } }
        compose.onNodeWithText(resources.getString(R.string.theme_settings_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.wallet_initialization_retry))
            .assertIsDisplayed().performClick()
        compose.onNodeWithText(resources.getString(R.string.currencyConversion_saveBtn)).assertDoesNotExist()
        assertBack(resources.getString(backText))
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissals)
        }
    }

    private fun assertAnnouncedAppearanceFailure(view: @Composable (Boolean, () -> Unit) -> Unit) {
        val resources = RuntimeEnvironment.getApplication().resources
        val failed = mutableStateOf(false)
        var saves = 0
        compose.setContent {
            ZcashTheme { view(failed.value) { saves++ } }
        }

        val error = compose.onNodeWithText(resources.getString(R.string.appearance_save_failed))
        error.assertDoesNotExist()
        compose.runOnIdle { failed.value = true }
        error.assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, error.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
