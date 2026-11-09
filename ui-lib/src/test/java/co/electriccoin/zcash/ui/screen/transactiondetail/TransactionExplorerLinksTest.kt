package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoContainer
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionExplorerLinksTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rowCopiesAndExplorerOpensWithoutCopyingAgain() {
        var copies = 0
        var opens = 0
        showLink(onCopy = { copies++ }, onOpen = { opens++ })

        compose.onNodeWithText("ZEC deposit")
            .assert(hasCopyActionLabel())
            .performClick()
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(0, opens)
        }

        compose.onNodeWithContentDescription("Open ZEC deposit in explorer").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(1, opens)
        }
        // Only the row and explorer are actions; the copy glyph is decorative.
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun unsupportedExplorerKeepsAccessibleRowCopyAndHidesIdWithBalances() {
        var copies = 0
        showLink(balancesAvailable = false, onCopy = { copies++ }, onOpen = null)

        val abbreviatedId = stringResByTransactionId(TRANSACTION_ID, abbreviated = true)
            .getString(RuntimeEnvironment.getApplication())
        compose.onNodeWithText(abbreviatedId, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(TRANSACTION_ID, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Open ZEC deposit in explorer").assertDoesNotExist()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("ZEC deposit")
            .assert(hasCopyActionLabel())
            .performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }

    private fun showLink(
        balancesAvailable: Boolean = true,
        onCopy: () -> Unit,
        onOpen: (() -> Unit)?,
    ) {
        compose.setContent {
            ZcashTheme {
                CompositionLocalProvider(LocalBalancesAvailable provides balancesAvailable) {
                    TransactionDetailInfoContainer {
                        TransactionExplorerLinks(
                            listOf(
                                TransactionExplorerLink(
                                    label = stringRes("ZEC deposit"),
                                    transactionId = TRANSACTION_ID,
                                    onOpen = onOpen,
                                    onCopy = onCopy,
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    private fun hasCopyActionLabel() = SemanticsMatcher("copy action describes the transaction leg") {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config[SemanticsActions.OnClick].label == "Copy ZEC deposit ID"
    }

    private companion object {
        const val TRANSACTION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
