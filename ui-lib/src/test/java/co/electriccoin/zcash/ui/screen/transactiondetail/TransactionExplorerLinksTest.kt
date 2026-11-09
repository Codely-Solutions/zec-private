package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
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
    fun wholeRowOpensExplorerWithoutDisplayingIdOrCopyAction() {
        var opens = 0
        showLinks(listOf(TransactionExplorerLink(stringRes("ZEC deposit"), TRANSACTION_ID, { opens++ })))

        compose.onNodeWithText("View ZEC deposit").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(TRANSACTION_ID, useUnmergedTree = true).assertDoesNotExist()
        val abbreviatedId = stringResByTransactionId(TRANSACTION_ID, abbreviated = true)
            .getString(RuntimeEnvironment.getApplication())
        compose.onNodeWithText(abbreviatedId, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy ZEC deposit ID", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Open ZEC deposit in explorer", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun multipleIdsInSameLegAreNumberedAndDuplicatesAreExcluded() {
        var firstOpens = 0
        var secondOpens = 0
        var duplicateOpens = 0
        showLinks(
            listOf(
                TransactionExplorerLink(stringRes("Bitcoin payment"), TRANSACTION_ID, { firstOpens++ }),
                TransactionExplorerLink(stringRes("Bitcoin payment"), SECOND_TRANSACTION_ID, { secondOpens++ }),
                TransactionExplorerLink(stringRes("Bitcoin payment"), TRANSACTION_ID, { duplicateOpens++ }),
            )
        )

        compose.onNodeWithText("View Bitcoin payment 1").assertExists()
        compose.onNodeWithText("View Bitcoin payment 2").performClick()
        compose.onNodeWithText("View Bitcoin payment 3").assertDoesNotExist()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals(0, firstOpens)
            assertEquals(1, secondOpens)
            assertEquals(0, duplicateOpens)
        }
    }

    @Test
    fun unsupportedExplorerHasNoNavigationRowOrAction() {
        showLinks(listOf(TransactionExplorerLink(stringRes("ZEC deposit"), TRANSACTION_ID, null)))

        compose.onNodeWithText("View ZEC deposit").assertDoesNotExist()
        compose.onNodeWithText("ZEC deposit").assertDoesNotExist()
        compose.onNodeWithText(TRANSACTION_ID, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun showLinks(links: List<TransactionExplorerLink>) {
        compose.setContent {
            ZcashTheme {
                TransactionExplorerLinks(links)
            }
        }
    }

    private companion object {
        const val TRANSACTION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SECOND_TRANSACTION_ID = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
