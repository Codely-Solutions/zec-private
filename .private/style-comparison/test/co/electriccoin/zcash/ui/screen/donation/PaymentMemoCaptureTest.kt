package co.electriccoin.zcash.ui.screen.donation

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiScreenDialogTag
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionTag
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentMemoCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturePaymentMemoStates() = capture(theme = "light", fontScale = 1f)

    @Test
    fun captureLargePaymentMemoStates() = capture(theme = "dark", fontScale = 2f)

    private fun capture(theme: String, fontScale: Float) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(fontScale) }
        val reviewScreen = "zip321-non-text-review"
        val errorScreen = "zip321-uneditable-memo"
        val active = mutableStateOf(reviewScreen)
        compose.setContent {
            key(active.value) {
                ZcashTheme(appearanceMode = if (theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK) {
                    Box(Modifier.fillMaxSize().background(ZashiColors.Surfaces.bgPrimary)) {
                        PaymentMemoReviewScene(active.value)
                    }
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        val memoText = context.getString(R.string.zip321_binary_memo, REVIEW_MEMO_BYTE_COUNT)
        val memo = compose.onNodeWithText(memoText).performScrollTo().assertIsDisplayed()
        val confirm = compose.onNodeWithTag(ReviewTransactionTag.REVIEW_TX_PRIMARY_BUTTON)
            .assertIsDisplayed().assertIsEnabled()
        assertTrue(memo.fetchSemanticsNode().boundsInRoot.bottom <= confirm.fetchSemanticsNode().boundsInRoot.top)
        saveFrame(reviewScreen, theme, fontScale, memoText)

        compose.runOnIdle { active.value = errorScreen }
        val errorText = context.getString(R.string.zip321_non_text_memo_edit_error)
        compose.onNodeWithTag(ZashiScreenDialogTag.CONFIRM).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(ZashiScreenDialogTag.DISMISS).assertDoesNotExist()
        saveFrame(errorScreen, theme, fontScale, errorText)
    }

    private fun saveFrame(screen: String, theme: String, fontScale: Float, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(expected).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val actualScale = layouts.single().layoutInput.density.fontScale
        assertEquals(fontScale, actualScale)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = System.getProperty("private.review.captureDir")?.let(::File)
                ?: File(checkNotNull(System.getenv("CODEX_GUARD_OUTPUT")), "captures")
            check(directory.isDirectory || directory.mkdirs())
            val name = "$screen-$theme-$fontScale-screen.png"
            val target = File(directory, name)
            check(target.createNewFile()) { "Capture already exists: $name" }
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            val sha256 = digest.joinToString("") { "%02x".format(it) }
            val background = bitmap.getPixel(0, 0)
            val hasContent = (0 until bitmap.height step 8).any { y ->
                (0 until bitmap.width step 8).any { x -> bitmap.getPixel(x, y) != background }
            }
            val validViewport = bitmap.width == 1080 && bitmap.height == 1920 && hasContent
            val receipt = JSONObject()
                .put("file", name)
                .put("screen", screen)
                .put("state", "screen")
                .put("theme", theme)
                .put("requestedFontScale", fontScale.toDouble())
                .put("fontScale", actualScale.toDouble())
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("density", compose.density.density.toDouble())
                .put("expected", expected)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("validViewport", validViewport)
                .put("sha256", sha256)
            File(directory, "captures.jsonl").appendText("$receipt\n")
            assertTrue(validViewport)
        } finally {
            bitmap.recycle()
        }
    }
}
