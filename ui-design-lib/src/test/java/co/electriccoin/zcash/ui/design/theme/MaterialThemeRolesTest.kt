package co.electriccoin.zcash.ui.design.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import co.electriccoin.zcash.ui.design.theme.colors.materialZashiColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialThemeRolesTest {
    @Test
    fun oledChangesThePageWithoutFlatteningInteractiveSurfaces() {
        val dark = darkColorScheme()
        val oled = dark.withOledBackground(true)
        assertEquals(Color.Black, oled.background)
        assertEquals(Color.Black, oled.surface)
        assertEquals(dark.surfaceContainerHigh, oled.surfaceContainerHigh)
        assertEquals(dark.primary, oled.primary)
        assertEquals(dark, dark.withOledBackground(false))
    }

    @Test
    fun enabledButtonAndInputContentRemainsReadableAcrossFallbackModes() {
        listOf(lightColorScheme(), darkColorScheme(), darkColorScheme().withOledBackground(true))
            .forEachIndexed { index, scheme ->
                val colors = materialZashiColors(scheme, index != 0)
                val pairs = listOf(
                    colors.Btns.Primary.btnPrimaryBg to colors.Btns.Primary.btnPrimaryFg,
                    colors.Btns.Secondary.btnSecondaryBg to colors.Btns.Secondary.btnSecondaryFg,
                    colors.Btns.Tertiary.btnTertiaryBg to colors.Btns.Tertiary.btnTertiaryFg,
                    colors.Surfaces.bgPrimary to colors.Text.textPrimary,
                )
                pairs.forEach { (background, foreground) ->
                    val bright = maxOf(background.luminance(), foreground.luminance())
                    val dim = minOf(background.luminance(), foreground.luminance())
                    assertTrue((bright + 0.05f) / (dim + 0.05f) >= 4.5f, "Low contrast in mode $index")
                }
                assertTrue(colors.Btns.Tertiary.btnTertiaryBg.alpha > 0f, "A button became bare text")
            }
    }
}
