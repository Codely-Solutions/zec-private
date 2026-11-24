package co.electriccoin.zcash.ui.screen.transactiondetail

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.balances.LocalBalancesAvailable
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRow
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailState
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailView
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRow
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwap
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
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
    fun outgoingHeaderRendersTheDestinationUnitAndHidesAmountsForTerminalFailures() {
        val outgoing = TransactionDetailHeaderState(
            title = stringRes("Provider output"),
            amount = stringRes("0.1"),
            icons = listOf(
                imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_chain_zec),
                imageRes(R.drawable.ic_transaction_sent),
                imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_chain_zec),
            ),
            amountTicker = "BTC",
        )
        val current = mutableStateOf(outgoing)
        compose.setContent {
            CompositionLocalProvider(LocalBalancesAvailable provides true) {
                ZcashTheme { BlankSurface { TransactionDetailHeader(current.value) } }
            }
        }
        compose.onNodeWithText("0.1 BTC").assertIsDisplayed()
        compose.onNodeWithText("0.1 ZEC").assertDoesNotExist()
        capture("standalone-outgoing-header")
        compose.runOnIdle { current.value = outgoing.copy(title = stringRes("Swap refunded"), showAmount = false) }
        compose.onNodeWithText("Swap refunded").assertIsDisplayed()
        compose.onNodeWithText("0.1 BTC").assertDoesNotExist()
        capture("standalone-refund-header")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h800dp")
    fun expandedSavedRefundKeepsTheLabeledQuoteWithoutLiveDetails() {
        val app = RuntimeEnvironment.getApplication()
        val details = SwapDetailState(
            transactionHeader = TransactionDetailHeaderState(
                title = stringRes("Swap refunded"),
                amount = null,
                icons = listOf(
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    imageRes(R.drawable.ic_transaction_received),
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                ),
                showAmount = false,
            ),
            quoteHeader = SwapQuoteHeaderState(null, null),
            status = TransactionDetailSwapStatusRowState(
                stringRes("Saved status"),
                SwapStatus.REFUNDED,
                mode = TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC,
            ),
            depositTo = TransactionDetailInfoRowState(
                stringRes(R.string.private_swap_deposit_reference),
                stringResByAddress("0123456789abcdefghijklmnopqrstabcdefghij")
            ),
            recipient = TransactionDetailInfoRowState(stringRes("Live recipient")),
            quotedOutput = TransactionDetailInfoRowState(
                stringRes(R.string.private_quoted_output), stringRes("0.075 ZEC").withStyle()
            ),
            maxSlippage = TransactionDetailInfoRowState(stringRes("Live slippage")),
            timestamp = TransactionDetailInfoRowState(stringRes("Swap created"), stringRes("Today").withStyle()),
            message = null,
            errorFooter = null,
            infoFooter = null,
            primaryButton = null,
            onBack = {},
            showLiveDetails = false,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ZcashTheme { SwapDetailView(details, appBarState = null) }
            }
        }
        compose.onNodeWithText("0.075 ZEC").assertDoesNotExist()
        compose.onNodeWithText(app.getString(co.electriccoin.zcash.ui.design.R.string.general_more)).performClick()
        compose.onNodeWithText(app.getString(R.string.private_quoted_output)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.075 ZEC").assertIsDisplayed()
        assertCompleteText("0123456789...abcdefghij")
        compose.onNodeWithText("Live recipient").assertDoesNotExist()
        compose.onNodeWithText("Live slippage").assertDoesNotExist()
        capture("standalone-expanded-saved-refund")
    }

    @Test
    fun outgoingDetailsRenderSavedStatusAndUnavailableWithoutHidingFunding() {
        val saved = TransactionDetailSwapStatusRowState(
            stringRes(R.string.private_swap_saved_status),
            SwapStatus.REFUNDED,
        )
        val unavailable = TransactionDetailSwapStatusRowState(stringRes(R.string.swapAndPay_status), null)
        val status = mutableStateOf(saved)
        val theme = mutableStateOf("light")
        val fontScale = mutableStateOf(1f)
        var depositClicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                ZcashTheme(
                    appearanceMode = if (theme.value == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                    isOledEnabled = theme.value == "oled",
                ) {
                    BlankSurface {
                        SendSwap(
                            state = SendSwapState(
                                statusRow = status.value,
                                message = null,
                                quoteHeader = SwapQuoteHeaderState(null, null),
                                depositAddress = stringRes("Synthetic deposit").withStyle(),
                                totalFees = stringRes("0.0001 ZEC"),
                                recipientAddress = null,
                                transactionId = stringRes("Synthetic funding transaction"),
                                refundedAmount = null,
                                onTransactionIdClick = {},
                                onDepositAddressClick = { depositClicks++ },
                                onRecipientAddressClick = null,
                                maxSlippage = null,
                                note = null,
                                isSlippageRealized = false,
                                isPending = false,
                                completedTimestamp = stringRes("Synthetic timestamp"),
                            ),
                            modifier = Modifier.width(320.dp),
                        )
                    }
                }
            }
        }
        val application = RuntimeEnvironment.getApplication()
        val configs = listOf("light" to 1f, "dark" to 1f, "oled" to 1f, "dark" to 2f)
        for ((appearance, scale) in configs) {
            compose.runOnIdle {
                theme.value = appearance
                fontScale.value = scale
                status.value = saved
            }
            compose.onNodeWithText("Saved status").assertIsDisplayed()
            compose.onNodeWithText(application.getString(R.string.swapAndPay_status_refunded)).assertIsDisplayed()
            compose.onNodeWithText("Synthetic deposit").assertIsDisplayed()
            capture("outgoing-saved-$appearance-$scale")
            compose.runOnIdle { status.value = unavailable }
            compose.onNodeWithText("Saved status").assertDoesNotExist()
            compose.onNodeWithText("Unavailable").assertIsDisplayed()
            compose.onNodeWithText("Synthetic deposit").assertIsDisplayed()
            capture("outgoing-unavailable-$appearance-$scale")
        }
        assertCompleteText("Synthetic deposit")
        compose.onNodeWithText("Synthetic deposit").performClick()
        compose.runOnIdle { assertEquals(1, depositClicks) }
    }

    private fun assertCompleteText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow, "$text must remain complete")
    }

    private fun capture(name: String) {
        val directory = System.getProperty("private.review.captureDir")?.let(::File) ?: return
        check(directory.isDirectory || directory.mkdirs())
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(directory, "$name.png")
        check(output.createNewFile())
        output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

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
                    )
                }
            }
        }
        assertCompleteText(timestamp)
    }
}
