package co.electriccoin.zcash.ui.screen.pay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberTextFieldEditEventsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun caretMovementIsDistinctFromEditingTextWithTheSameNumericValue() {
        val one = NumberTextFieldInnerState.fromAmount(BigDecimal.ONE)
        val initial = one.copy(innerTextFieldState = one.innerTextFieldState.copy(value = stringRes("1")))
        val active = mutableStateOf(initial)
        val updates = mutableListOf<NumberTextFieldInnerState>()
        compose.setContent {
            ZcashTheme {
                ZashiNumberTextField(NumberTextFieldState(innerState = active.value) {
                    updates += it
                    active.value = it
                })
            }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performSemanticsAction(SemanticsActions.SetSelection) { setSelection ->
            assertTrue(setSelection(1, 1, false))
        }
        compose.runOnIdle {
            assertEquals(BigDecimal.ONE, updates.last().amount)
            assertTrue(updates.last().isSelectionOnlyChange)
        }

        field.performTextReplacement("1.0")
        compose.runOnIdle {
            assertEquals(BigDecimal("1.0"), updates.last().amount)
            assertFalse(updates.last().isSelectionOnlyChange)
        }
    }

    @Test
    fun crossPayAmountsFitTheirEditableViewportsAtTwoHundredPercent() {
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }
        compose.setContent {
            ZcashTheme {
                Box(Modifier.width(312.dp)) {
                    PayAmountTextFields(
                        token = amountEndingAtCaret("33.33333333"),
                        fiat = amountEndingAtCaret("150.00"),
                        ticker = stringRes("BTC"),
                    )
                }
            }
        }
        assertAmountFits(PayTag.PAY_AMOUNT_TOKEN_FIELD, "33.33333333")
        assertAmountFits(PayTag.PAY_AMOUNT_FIAT_FIELD, "150.00")
    }

    private fun amountEndingAtCaret(text: String): NumberTextFieldState {
        val number = NumberTextFieldInnerState.fromAmount(BigDecimal(text))
        val inner = number.innerTextFieldState.copy(value = stringRes(text), selection = TextSelection.End)
        return NumberTextFieldState(innerState = number.copy(innerTextFieldState = inner)) {}
    }

    private fun assertAmountFits(tag: String, expected: String) {
        val matcher = hasAnyAncestor(hasTestTag(tag)) and hasSetTextAction()
        val field = compose.onNode(matcher, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
            assertTrue(getLayout(results))
        }
        val layout = results.single()
        val constraints = layout.layoutInput.constraints
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals(2f, layout.layoutInput.density.fontScale)
        // Compose measures a horizontally overflowing single-line field at infinite width.
        val neededWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue(constraints.hasBoundedWidth && neededWidth <= constraints.maxWidth, "$tag clips $expected")
    }
}
