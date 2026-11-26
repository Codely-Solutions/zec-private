package co.electriccoin.zcash.ui.screen.exportdata

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.exportdata.view.ExportPrivateData
import co.electriccoin.zcash.ui.screen.exportdata.view.ExportPrivateDataScreenTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class ExportPrivateDataConsentLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTextShowsViewingKeyAccessAndRequiresExplicitConsent() {
        var confirmationCount = 0
        compose.setContent {
            ZcashTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    ExportPrivateData(
                        snackbarHostState = remember { SnackbarHostState() },
                        onBack = {},
                        onAgree = {},
                        onConfirm = { confirmationCount++ },
                    )
                }
            }
        }

        compose.onNodeWithTag(ExportPrivateDataScreenTag.WARNING_TEXT_TAG)
            .performScrollTo().assertIsDisplayed().assertTextContains("viewing keys", substring = true)
        val export = compose.onNodeWithText("Export Private Data")
        export.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ExportPrivateDataScreenTag.AGREE_CHECKBOX_TAG).performScrollTo().performClick()
        export.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmationCount) }
    }
}
