package co.electriccoin.zcash.ui.screen.donation

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
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
// The Windows native shader compiler rejects Haze's progressive effect on API 34.
// This supported Android version renders the app's existing opaque header fallback.
@Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun referenceScreenRenders() {
        val scene = Scene("reset-pending", "light")
        show(mutableStateOf(scene))
        val knownText = text(R.string.wallet_reset_pending)
        val reference = compose.onNodeWithText(knownText).assertIsDisplayed().captureToImage()
        assertTrue(reference.width > 0 && reference.height > 0)
        capture(scene, "reference", knownText)
    }

    @Test
    fun captureUpdatedScenes() {
        captureScenes(listOf("light", "dark", "oled"), fontScale = 1f)
    }

    @Test
    fun captureLargeTextScenes() {
        captureScenes(listOf("dark"), fontScale = 2f)
    }

    private fun captureScenes(themes: List<String>, fontScale: Float) {
        // Dialog and tooltip windows create their own Compose owners from Android resources.
        compose.runOnUiThread {
            RuntimeEnvironment.setFontScale(fontScale)
            assertEquals(fontScale, compose.activity.resources.configuration.fontScale)
        }
        val scene = mutableStateOf(Scene("reset-retention", themes.first(), fontScale))
        show(scene)
        for (theme in themes) {
            for (screen in listOf("reset-retention", "reset-retention-off", "incoming-saved-pending")) {
                val current = Scene(screen, theme, fontScale)
                compose.runOnIdle { scene.value = current }
                val expected = when (screen) {
                    "incoming-saved-pending" -> text(R.string.private_swap_created_at)
                    else -> text(R.string.deleteWallet_metadataWarn2)
                }
                compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
                capture(current, "screen", expected)
            }
            val quote = Scene("price-warning", theme, fontScale)
            compose.runOnIdle { scene.value = quote }
            val warning = text(R.string.private_price_poor, "24.8")
            val market = text(R.string.private_price_reference)
            compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(market).assertDoesNotExist()
            capture(quote, "collapsed", warning)
            compose.onNodeWithText(text(R.string.private_quote_details)).performScrollTo().performClick()
            compose.onNodeWithText(market).performScrollTo().assertIsDisplayed()
            capture(quote, "expanded", market)
            compose.onNodeWithContentDescription(text(R.string.private_price_reference_help_label)).performClick()
            val source = text(R.string.private_price_reference_help, "Kraken")
            compose.onNodeWithText(source).assertIsDisplayed()
            capture(quote, "source", source)
        }
    }

    private fun show(active: State<Scene>) {
        compose.setContent {
            val scene = active.value
            key(scene) {
                ZcashTheme(
                    appearanceMode = if (scene.theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                    isOledEnabled = scene.theme == "oled",
                ) {
                    Box(Modifier.fillMaxSize().background(ZashiColors.Surfaces.bgPrimary)) {
                        when (scene.screen) {
                            "price-warning" -> AdditionalReviewScene(
                                scene.screen, {}, ZashiMainTopAppBarStateFixture.new(),
                            )
                            "incoming-saved-pending" -> IncomingSwapReviewScene(scene.screen)
                            else -> ProjectReviewScene(scene.screen)
                        }
                    }
                }
            }
        }
    }

    private fun text(id: Int, vararg arguments: String): String =
        RuntimeEnvironment.getApplication().getString(id, *arguments)

    private fun capture(scene: Scene, state: String, expected: String) {
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(expected).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
            assertTrue(getLayout(layouts), "Missing text layout for $expected")
        }
        val visibleFontScale = layouts.map { it.layoutInput.density.fontScale }.distinct().single()
        assertEquals(scene.fontScale, visibleFontScale, "Rendered font scale for $expected")
        // The viewport snapshot includes the dialog and tooltip windows as well as the host activity.
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val background = bitmap.getPixel(0, 0)
            val hasContent = (0 until bitmap.height step 8).any { y ->
                (0 until bitmap.width step 8).any { x -> bitmap.getPixel(x, y) != background }
            }
            val directory = System.getProperty("private.review.captureDir")?.let(::File)
                ?: File(checkNotNull(System.getenv("CODEX_GUARD_OUTPUT")), "captures")
            check(directory.isDirectory || directory.mkdirs())
            val name = "${scene.screen}-${scene.theme}-${scene.fontScale}-$state.png"
            val target = File(directory, name)
            check(target.createNewFile()) { "Capture already exists: $name" }
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            val receipt = JSONObject()
                .put("file", name)
                .put("screen", scene.screen)
                .put("state", state)
                .put("theme", scene.theme)
                .put("requestedFontScale", scene.fontScale.toDouble())
                .put("fontScale", visibleFontScale.toDouble())
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("density", compose.density.density.toDouble())
                .put("expected", expected)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("validViewport", bitmap.width == 1080 && bitmap.height == 1920 && hasContent)
                .put("sha256", digest.joinToString("") { "%02x".format(it) })
            File(directory, "captures.jsonl").appendText("$receipt\n")
            assertEquals(1080, bitmap.width)
            assertEquals(1920, bitmap.height)
            assertTrue(hasContent, "The captured viewport contains no visible content")
        } finally {
            bitmap.recycle()
        }
    }

    private data class Scene(val screen: String, val theme: String, val fontScale: Float = 1f)
}
