package co.electriccoin.zcash.ui.screen.pay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextField
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
