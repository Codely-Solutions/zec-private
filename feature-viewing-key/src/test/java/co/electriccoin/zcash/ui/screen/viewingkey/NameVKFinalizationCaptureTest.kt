package co.electriccoin.zcash.ui.screen.viewingkey

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.AccountNameFieldTag
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKState
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKTag
import co.electriccoin.zcash.ui.screen.viewingkey.name.NameVKView
import org.json.JSONObject
import org.junit.Assume.assumeTrue
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
class NameVKFinalizationCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Keep the existing JUnit case identity for the regular theme rerun.
    @Test
    fun captureCommittedNameInAllThemesAndAtLargeFontScale() =
        captureCommittedName(listOf("light", "dark", "oled"), 1f)

    @Test
    fun captureLargeCommittedName() = captureCommittedName(listOf("dark"), 2f)

    private fun captureCommittedName(themes: List<String>, scale: Float) {
        val path = System.getProperty("private.review.captureDir")
        assumeTrue("Opt in with the private review capture directory", !path.isNullOrBlank())
        val directory = File(checkNotNull(path)).also { check(it.isDirectory || it.mkdirs()) }
        val app = RuntimeEnvironment.getApplication()
        val account = Account.new(AccountUuid.new(ByteArray(16)))
        val tip = Birthday("tip", null, app.getString(R.string.enrollment_committed_tip))
        val height = Birthday(
            "height",
            BlockHeight.new(3_000_000),
            app.getString(R.string.enrollment_committed_height, "3,000,000"),
        )
        val scenes = themes.flatMap { theme -> listOf(Scene(theme, scale, tip), Scene(theme, scale, height)) }
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(scale) }
        val active = mutableStateOf(scenes.first())
        compose.setContent {
            val scene = active.value
            key(scene) {
                ZcashTheme(
                    appearanceMode = if (scene.theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                    isOledEnabled = scene.theme == "oled",
                ) {
                    NameVKView(
                        NameVKState.create(
                            name = "Review Vault",
                            color = null,
                            isDuplicate = false,
                            isImporting = false,
                            committedBirthday = CommittedEnrollmentBirthday(scene.birthday.height, account),
                            onAvatarEditClick = {},
                            onValueChange = {},
                            onContinueClick = {},
                            onBack = {},
                        )
                    )
                }
            }
        }

        for (scene in scenes) {
            compose.runOnIdle { active.value = scene }
            val message = compose.onNodeWithText(scene.birthday.message).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            message.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                assertTrue(read(layouts))
            }
            val renderedScale = layouts.single().layoutInput.density.fontScale
            assertEquals(scene.scale, renderedScale)
            compose.onNodeWithTag(AccountNameFieldTag.FIELD).performScrollTo().assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
                .assertIsDisplayed().assertIsNotEnabled()
            val finish = compose.onNodeWithTag(NameVKTag.CONTINUE_BUTTON).performScrollTo()
                .assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText(app.getString(R.string.enrollment_finish_setup)).assertIsDisplayed()
            val node = finish.fetchSemanticsNode()
            assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
            val messageNode = message.fetchSemanticsNode()
            val messageFullyVisible = message.isDisplayed() &&
                messageNode.boundsInRoot.height == messageNode.size.height.toFloat()
            capture(directory, scene, renderedScale, if (messageFullyVisible) "screen" else "finish")
            if (!messageFullyVisible) {
                message.performScrollTo().assertIsDisplayed()
                capture(directory, scene, renderedScale, "birthday")
            }
        }
    }

    private fun capture(directory: File, scene: Scene, renderedScale: Float, state: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val background = bitmap.getPixel(0, 0)
            val hasContent = (0 until bitmap.height step PIXEL_SAMPLE_STEP).any { y ->
                (0 until bitmap.width step PIXEL_SAMPLE_STEP).any { x -> bitmap.getPixel(x, y) != background }
            }
            val name = "view-only-name-committed-${scene.birthday.label}-${scene.theme}-${scene.scale}-$state.png"
            val target = File(directory, name)
            check(target.createNewFile()) { "Capture already exists: $name" }
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            val receipt = JSONObject()
                .put("file", name)
                .put("screen", "view-only-name-committed-${scene.birthday.label}")
                .put("state", state)
                .put("theme", scene.theme)
                .put("requestedFontScale", scene.scale.toDouble())
                .put("fontScale", renderedScale.toDouble())
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("density", compose.density.density.toDouble())
                .put("expected", scene.birthday.message)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("validViewport", bitmap.width == VIEWPORT_WIDTH && bitmap.height == VIEWPORT_HEIGHT && hasContent)
                .put("sha256", digest.joinToString("") { "%02x".format(it) })
            File(directory, "captures.jsonl").appendText("$receipt\n")
            assertEquals(VIEWPORT_WIDTH, bitmap.width)
            assertEquals(VIEWPORT_HEIGHT, bitmap.height)
            assertTrue(hasContent)
        } finally {
            bitmap.recycle()
        }
    }

    private data class Birthday(val label: String, val height: BlockHeight?, val message: String)

    private data class Scene(val theme: String, val scale: Float, val birthday: Birthday)

    private companion object {
        const val PIXEL_SAMPLE_STEP = 8
        const val PNG_QUALITY = 100
        const val VIEWPORT_WIDTH = 1_080
        const val VIEWPORT_HEIGHT = 1_920
    }
}
