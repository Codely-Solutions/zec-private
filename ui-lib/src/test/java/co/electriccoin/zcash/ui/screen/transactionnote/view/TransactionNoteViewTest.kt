package co.electriccoin.zcash.ui.screen.transactionnote.view

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactionnote.model.TransactionNoteState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class TransactionNoteViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun autofocusWaitsForTheLoadedEditorAndRunsWhenItAppears() {
        val loading = TransactionNoteState(
            onBack = {}, title = stringRes("Transaction note"),
            note = TextFieldState(value = stringRes(""), isEnabled = false, onValueChange = {}),
            noteCharacters = stringRes("").withStyle(),
            primaryButton = null, secondaryButton = null, negative = null,
            loadMessage = stringRes("Loading note"),
        )
        val state = mutableStateOf(loading)
        var autofocus = 0
        compose.setContent {
            ZcashTheme { TransactionNoteView(state.value, onSheetOpen = { autofocus++ }) }
        }
        compose.onNodeWithText("Loading note").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, autofocus) }
        compose.runOnIdle {
            state.value = loading.copy(
                loadMessage = null,
                note = TextFieldState(value = stringRes("Stored note"), isEnabled = true, onValueChange = {}),
            )
        }
        compose.onNodeWithText("Stored note").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, autofocus) }
    }
}
