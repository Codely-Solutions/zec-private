package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZashiAddressTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun normalTextKeepsBothEightCharacterEdges() {
        setField(fontScale = 1f)
        compose.onNode(hasSetTextAction()).assertTextEquals(ADDRESS.take(8) + "..." + ADDRESS.takeLast(8))
    }

    @Test
    fun largeTextWithTwoActionsUsesShorterEdgesAndFocusShowsTheExactAddress() {
        setField(fontScale = 2f)
        val field = compose.onNode(hasSetTextAction())
        val display = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val edges = display.split("...")
        assertEquals(2, edges.size)
        assertTrue(edges[0].length in 1..7)
        assertEquals(edges[0].length, edges[1].length)
        assertTrue(ADDRESS.startsWith(edges[0]))
        assertTrue(ADDRESS.endsWith(edges[1]))

        field.performClick()
        field.assertTextEquals(ADDRESS)
    }

    private fun setField(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ZcashTheme {
                    ZashiAddressTextField(
                        state = TextFieldState(stringRes(ADDRESS), onValueChange = {}),
                        modifier = Modifier.width(312.dp),
                        suffix = {
                            Row {
                                Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(4.dp))
                                Spacer(Modifier.size(36.dp))
                            }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val ADDRESS = "t1LHhDg2Bhe7t85w9f3RtpsdJxMJs2jELkA"
    }
}
