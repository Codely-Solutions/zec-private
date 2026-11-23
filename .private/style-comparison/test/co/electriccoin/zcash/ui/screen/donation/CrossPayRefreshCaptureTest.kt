package co.electriccoin.zcash.ui.screen.donation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.screen.pay.PayTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w360dp-h640dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossPayRefreshCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureRefreshedFields() = capture(AppearanceMode.LIGHT, 1f)

    @Test
    fun captureLargeRefreshedFields() = capture(AppearanceMode.DARK, 2f)

    private fun capture(theme: AppearanceMode, scale: Float) {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(scale) }
        assertEquals(scale, compose.activity.resources.configuration.fontScale)
        val active = mutableStateOf("recipient-pay-refresh-fiat")
        compose.setContent {
            key(active.value) {
                ZcashTheme(appearanceMode = theme) {
                    Box(Modifier.fillMaxSize().background(ZashiColors.Surfaces.bgPrimary)) {
                        ComparisonScreens(active.value)
                    }
                }
            }
        }
        for ((owner, token, fiat) in listOf(Triple("fiat", "25", "100"), Triple("token", "50", "150"))) {
            val screen = "recipient-pay-refresh-$owner"
            compose.runOnIdle { active.value = screen }
            assertField(PayTag.PAY_AMOUNT_TOKEN_FIELD, token)
            assertField(PayTag.PAY_AMOUNT_FIAT_FIELD, fiat)
            compose.onNodeWithTag(PayTag.PAY_REVIEW_BUTTON).assertIsDisplayed()
            val name = "$screen-${theme.name.lowercase()}-$scale-screen.png"
            saveFrame(name)
        }
    }

    private fun assertField(tag: String, expected: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        val displayed = assertNotNull(node.config.getOrNull(SemanticsProperties.EditableText)).text
        assertEquals(0, BigDecimal(displayed).compareTo(BigDecimal(expected)))
    }

    private fun saveFrame(name: String) {
        val bitmap = assertNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = System.getProperty("private.review.captureDir")?.let(::File)
                ?: File(checkNotNull(System.getenv("CODEX_GUARD_OUTPUT")), "captures")
            check(directory.isDirectory || directory.mkdirs())
            val target = File(directory, name)
            check(target.createNewFile())
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertEquals(1080, bitmap.width)
            assertEquals(1920, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
