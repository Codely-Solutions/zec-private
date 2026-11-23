package co.electriccoin.zcash.ui.screen.donation

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
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
import co.electriccoin.zcash.ui.design.R as DesignR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeightBoundaryCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureHeightBoundaries() = capture(
        fontScale = 1f,
        scenes = listOf(
            Scene("restore-height-minimum", "light", R.string.root_existingWallet_restore, true),
            Scene("restore-height-fraction", "light", R.string.root_existingWallet_restore, false),
            Scene("resync-height-maximum", "dark", DesignR.string.general_confirm, true),
            Scene("resync-height-overflow", "dark", DesignR.string.general_confirm, false),
            Scene("hardware-height-maximum", "oled", R.string.keystone_addHWWallet_connect, true),
            Scene("hardware-height-overflow", "oled", R.string.keystone_addHWWallet_connect, false),
        ),
    )

    @Test
    fun captureLargeHeightBoundaries() = capture(
        fontScale = 2f,
        scenes = listOf(
            Scene("restore-height-minimum", "dark", R.string.root_existingWallet_restore, true),
            Scene("restore-height-fraction", "dark", R.string.root_existingWallet_restore, false),
        ),
    )

    private fun capture(fontScale: Float, scenes: List<Scene>) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(fontScale) }
        val current = mutableStateOf(scenes.first())
        compose.setContent {
            val scene = current.value
            key(scene) {
                ZcashTheme(
                    appearanceMode = if (scene.theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                    isOledEnabled = scene.theme == "oled",
                ) {
                    HeightBoundaryReviewScene(scene.screen)
                }
            }
        }
        val fieldTitle = RuntimeEnvironment.getApplication().getString(DesignR.string.restoreWallet_birthday_title)
        for (scene in scenes) {
            compose.runOnIdle { current.value = scene }
            val field = compose.onNodeWithText(fieldTitle).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val actualScale = layouts.single().layoutInput.density.fontScale
            assertEquals(fontScale, actualScale)
            val actionText = RuntimeEnvironment.getApplication().getString(scene.action)
            val action = compose.onNodeWithTag(HEIGHT_REVIEW_ACTION)
                .performScrollTo().assertIsDisplayed().assertTextEquals(actionText)
            if (scene.enabled) action.assertIsEnabled() else action.assertIsNotEnabled()
            saveFrame(scene, actionText, actualScale, fontScale)
        }
    }

    private fun saveFrame(scene: Scene, actionText: String, actualScale: Float, fontScale: Float) {
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = System.getProperty("private.review.captureDir")?.let(::File)
                ?: File(checkNotNull(System.getenv("CODEX_GUARD_OUTPUT")), "captures")
            check(directory.isDirectory || directory.mkdirs())
            val name = "${scene.screen}-${scene.theme}-$fontScale-action.png"
            val target = File(directory, name)
            check(target.createNewFile()) { "Capture already exists: $name" }
            target.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            val background = screenshot.getPixel(0, 0)
            val hasContent = (0 until screenshot.height step 8).any { y ->
                (0 until screenshot.width step 8).any { x -> screenshot.getPixel(x, y) != background }
            }
            val receipt = JSONObject()
                .put("file", name)
                .put("screen", scene.screen)
                .put("state", "action")
                .put("theme", scene.theme)
                .put("requestedFontScale", fontScale.toDouble())
                .put("fontScale", actualScale.toDouble())
                .put("width", screenshot.width)
                .put("height", screenshot.height)
                .put("density", compose.density.density.toDouble())
                .put("expected", actionText)
                .put("enabled", scene.enabled)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("validViewport", screenshot.width == 1080 && screenshot.height == 1920 && hasContent)
                .put("sha256", digest.joinToString("") { "%02x".format(it) })
            File(directory, "captures.jsonl").appendText("$receipt\n")
            assertEquals(1080, screenshot.width)
            assertEquals(1920, screenshot.height)
            assertTrue(hasContent, "The captured viewport contains no visible content")
        } finally {
            screenshot.recycle()
        }
    }

    private data class Scene(val screen: String, val theme: String, val action: Int, val enabled: Boolean)
}
