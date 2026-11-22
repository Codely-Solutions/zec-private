package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRow
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRow
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState
import co.electriccoin.zcash.ui.common.model.SwapStatus
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailRowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedStatusAndLabelRemainCompleteAtTwoHundredPercentText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ZcashTheme {
                    TransactionDetailSwapStatusRow(
                        state = TransactionDetailSwapStatusRowState(
                            title = stringRes("Saved status"),
                            status = SwapStatus.PENDING,
                            mode = TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC,
                        ),
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
        for (text in listOf("Saved status", "Pending deposit")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight, "$text must fit vertically")
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1), "$text must remain complete")
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line), "$text must not be ellipsized")
                // Native paragraph widths can differ from the integer layout width by a rounding pixel.
                assertTrue(layout.getLineRight(line) <= layout.size.width + 1f, "$text must fit horizontally")
            }
        }
        val label = compose.onNodeWithText("Saved status").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("Pending deposit").fetchSemanticsNode().boundsInRoot
        assertTrue(label.bottom <= status.top, "Large-text content must stack without overlap")
    }

    @Test
    fun savedUpdateRemainsCompleteAtLargeTextAndNarrowWidth() {
        val timestamp = "20 November 2026, 12:00"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.3f),
                LocalBalancesAvailable provides true,
            ) {
                ZcashTheme {
                    TransactionDetailInfoRow(
                        state = TransactionDetailInfoRowState(
                            stringRes("Last saved update"), stringRes(timestamp).withStyle()
                        ),
                        modifier = Modifier.width(360.dp),
                        wrapMessage = true,
                    )
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(timestamp).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow, "The saved update timestamp must remain complete")
    }
}
