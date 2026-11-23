package co.electriccoin.zcash.ui.screen.viewingkey

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.viewingkey.importvk.ImportVKState
import co.electriccoin.zcash.ui.screen.viewingkey.importvk.ImportVKTag
import co.electriccoin.zcash.ui.screen.viewingkey.importvk.ImportVKView
import co.electriccoin.zcash.viewingkey.R
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The paste screen: Continue stays disabled until the field has text, and the QR button in the
 * field reports its click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportVKViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var text by mutableStateOf("")

    private var scans = 0
    private var theme by mutableStateOf("light")

    @Test
    fun continueIsDisabledUntilTheFieldHasText() {
        show()

        composeTestRule.onNodeWithTag(ImportVKTag.CONTINUE_BUTTON).assertIsNotEnabled()

        composeTestRule.onNode(isTextField).performTextInput(KEY)

        assertEquals(KEY, text)
        composeTestRule.onNodeWithTag(ImportVKTag.CONTINUE_BUTTON).assertIsEnabled()
    }

    @Test
    fun theScanButtonReportsItsClick() {
        show()

        composeTestRule.onNodeWithTag(ImportVKTag.SCAN_BUTTON).performClick()

        assertEquals(1, scans)
    }

    @Test
    fun thePasteButtonIsVisibleAndFillsTheField() {
        show()

        composeTestRule.onNodeWithTag(ImportVKTag.PASTE_BUTTON).assertIsDisplayed().performClick()

        assertEquals(KEY, text)
        composeTestRule.onNodeWithTag(ImportVKTag.CONTINUE_BUTTON).assertIsEnabled()
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
    fun captureViewOnlyImportThemes() {
        val directory = captureDirectory()
        show()
        for (mode in listOf("light", "dark", "oled")) {
            composeTestRule.runOnIdle { theme = mode }
            capturePaste(directory, fontScale = 1f)
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
    fun captureViewOnlyImportLargeText() {
        val directory = captureDirectory()
        composeTestRule.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        theme = "dark"
        show()
        capturePaste(directory, fontScale = 2f)

        composeTestRule.onNodeWithTag(ImportVKTag.PASTE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(ImportVKTag.CONTINUE_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        captureViewport(directory, "view-only-import-dark-2.0-continue")
    }

    private fun captureDirectory(): File {
        val path = System.getProperty("private.review.captureDir")
        assumeTrue("Opt in with the private review capture directory", !path.isNullOrBlank())
        return File(checkNotNull(path)).also { check(it.isDirectory || it.mkdirs()) }
    }

    private fun capturePaste(directory: File, fontScale: Float) {
        composeTestRule.onNodeWithTag(ImportVKTag.PASTE_BUTTON).performScrollTo().assertIsDisplayed()
        val label = RuntimeEnvironment.getApplication().getString(R.string.view_only_paste_button)
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(fontScale, layouts.single().layoutInput.density.fontScale)
        captureViewport(directory, "view-only-import-$theme-$fontScale-paste")
    }

    private fun captureViewport(directory: File, name: String) {
        composeTestRule.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            assertEquals(1080, bitmap.width)
            assertEquals(1920, bitmap.height)
            val output = File(directory, "$name.png")
            check(output.createNewFile()) { "Capture already exists: $name" }
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show() {
        composeTestRule.setContent {
            ZcashTheme(
                appearanceMode = if (theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                isOledEnabled = theme == "oled",
            ) {
                ImportVKView(
                    ImportVKState.create(
                        text = text,
                        isSubmitting = false,
                        onValueChange = { text = it },
                        onPasteClick = { text = KEY },
                        onScanClick = { scans++ },
                        onContinueClick = {},
                        onBack = {},
                    )
                )
            }
        }
    }

    /** The key field, found by its editable text. */
    private val isTextField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private companion object {
        const val KEY = "uview1fixture"
    }
}
