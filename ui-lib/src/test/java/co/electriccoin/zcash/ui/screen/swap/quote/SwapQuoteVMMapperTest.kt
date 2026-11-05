package co.electriccoin.zcash.ui.screen.swap.quote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import java.io.File
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The quote sheet's "Swap from" / "Pay from" row must name whichever account is selected, not a
 * hardcoded "Zodl" — a Ledger or Keystone account has to be named correctly. The row itself is
 * only shown for a cross-chain quote; a ZEC-to-ZEC on-ramp quote drops it entirely.
 *
 * Runs under Robolectric because [SwapQuoteVMMapper] resolves `FiatCurrency.USD.symbol`, which
 * needs the real ICU currency data that a plain JVM unit test does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwapQuoteVMMapperTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultUuid = AccountUuid.new(ByteArray(16) { it.toByte() })
    private val sdkAccount = Account.new(defaultUuid)
    private val mapper = SwapQuoteVMMapper()
    private val destinationAsset = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth")

    @Test
    fun theFromRowNamesTheSelectedZashiAccount() {
        val fromRow = createItems(account = zashi(), destinationAsset = destinationAsset).first()

        assertEquals(R.string.accounts_zashi, fromRow.title.resourceId())
    }

    @Test
    fun theFromRowNamesTheSelectedKeystoneAccount() {
        val fromRow = createItems(account = keystone(), destinationAsset = destinationAsset).first()

        assertEquals(R.string.accounts_keystone, fromRow.title.resourceId())
    }

    @Test
    fun theFromRowNamesTheSelectedLedgerAccount() {
        val fromRow = createItems(account = ledger(), destinationAsset = destinationAsset).first()

        assertEquals(R.string.accounts_ledger, fromRow.title.resourceId())
    }

    @Test
    fun theFromRowIsAbsentWhenTheDestinationAssetIsZec() {
        val items = createItems(account = zashi(), destinationAsset = SwapAssetTestFixture.zecAsset())

        assertEquals(
            emptyList(),
            items.filter { it.description.resourceId() == R.string.swapAndPay_swapFrom }
        )
    }

    @Test
    fun incomingQuoteFeeNoteRendersWithoutFormatConversionFailure() {
        assertQuoteRowsRender(SwapMode.FLEX_INPUT, SwapAssetTestFixture.zecAsset())
    }

    @Test
    fun outgoingExactInputFeeNoteRendersWithoutFormatConversionFailure() {
        assertQuoteRowsRender(SwapMode.EXACT_INPUT, destinationAsset)
    }

    @Test
    fun outgoingExactOutputFeeNoteRendersWithoutFormatConversionFailure() {
        assertQuoteRowsRender(SwapMode.EXACT_OUTPUT, destinationAsset)
    }

    @Test
    fun incomingQuoteCostsFitNarrowScreenWithLargeText() {
        assertQuoteRowsRender(SwapMode.FLEX_INPUT, SwapAssetTestFixture.zecAsset(), 320, 1.6f)
    }

    @Test
    fun outgoingExactInputCostsFitNarrowScreenWithLargeText() {
        assertQuoteRowsRender(SwapMode.EXACT_INPUT, destinationAsset, 320, 1.6f)
    }

    @Test
    fun outgoingExactOutputCostsFitNarrowScreenWithLargeText() {
        assertQuoteRowsRender(SwapMode.EXACT_OUTPUT, destinationAsset, 320, 1.6f)
    }

    private fun assertQuoteRowsRender(
        mode: SwapMode,
        asset: SwapAsset,
        width: Int = 360,
        fontScale: Float = 1f
    ) {
        val items = createItems(zashi(), asset, mode)
        var view: View? = null
        composeTestRule.setContent {
            view = LocalView.current
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                ZcashTheme(appearanceMode = AppearanceMode.DARK) {
                    Column(
                        Modifier.width(width.dp)
                            .background(ZashiColors.Surfaces.bgPrimary)
                            .padding(24.dp)
                            .testTag("quote-rows")
                    ) {
                        items.forEach { item -> SwapQuoteInfo(item) }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val label = composeTestRule.onNodeWithText("Provider and bridge costs")
        val included = composeTestRule.onNodeWithText("Included in the quoted amount received")
        val note = composeTestRule.onNodeWithText(
            "No affiliate fee. Unauthenticated quotes may include an extra 0.25% platform fee."
        )
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val titleBounds = included.fetchSemanticsNode().boundsInRoot
        val noteBounds = note.fetchSemanticsNode().boundsInRoot
        val rowBounds = composeTestRule.onNodeWithTag("quote-rows").fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.width >= rowBounds.width * .95f, "Provider label lost its available width")
        assertTrue(labelBounds.height < labelBounds.width * .3f, "Provider label became a vertical character column")
        assertTrue(titleBounds.top >= labelBounds.bottom, "Explanation must be below the label")
        assertTrue(noteBounds.top >= titleBounds.bottom, "Fee note must be below the explanation")
        assertTrue(noteBounds.bottom - labelBounds.top < rowBounds.width * 1.3f, "Provider block is excessively tall")
        for (node in listOf(label, included, note)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            assertFalse(layouts.single().hasVisualOverflow, "Provider text is clipped or ellipsized")
        }
        saveLayoutScreenshot(checkNotNull(view), mode, width, fontScale)
    }

    private fun saveLayoutScreenshot(view: View, mode: SwapMode, width: Int, fontScale: Float) {
        val output = System.getenv("QUOTE_LAYOUT_SCREENSHOTS") ?: return
        val bounds = composeTestRule.onNodeWithTag("quote-rows").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        composeTestRule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val crop =
            Bitmap.createBitmap(
                bitmap,
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.width.toInt(),
                bounds.height.toInt()
            )
        val target = File(output, "${mode.name.lowercase()}-$width-$fontScale.png")
        checkNotNull(target.parentFile).mkdirs()
        target.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        bitmap.recycle()
    }

    private fun createItems(
        account: WalletAccount,
        destinationAsset: SwapAsset,
        mode: SwapMode = SwapMode.EXACT_INPUT
    ): List<SwapQuoteInfoItem> {
        val state =
            mapper.createState(
                state =
                    SwapQuoteInternalState(
                        proposal = null,
                        quote = FakeSwapQuote(destinationAsset = destinationAsset, mode = mode),
                        account = account
                    ),
                onBack = {},
                onSubmitQuoteClick = {},
                onNavigateToOnRampSwap = {}
            )
        return state.items
    }

    private fun zashi(isSelected: Boolean = true) =
        ZodlAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            saplingAddress = "s",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun keystone(isSelected: Boolean = true) =
        KeystoneAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun ledger(isSelected: Boolean = true) =
        LedgerAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
            deviceIdentity = "tpk0-deadbeef",
            zip32AccountIndex = Zip32AccountIndex.new(0L),
        )

    private fun StringResource.resourceId(): Int = (this as StringResource.ByResource).resource

    private fun StyledStringResource.resourceId(): Int =
        (this as StyledStringResource.ByStringResource).resource.resourceId()
}
