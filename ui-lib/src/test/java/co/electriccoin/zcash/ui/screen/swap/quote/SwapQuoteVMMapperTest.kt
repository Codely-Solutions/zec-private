package co.electriccoin.zcash.ui.screen.swap.quote

import android.app.Application
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import java.io.File
import java.math.BigDecimal
import co.electriccoin.zcash.ui.design.util.withStyle
import java.util.Locale
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    @Test
    fun freshQuoteShowsSignedDifferenceAndStaleQuoteDoesNot() {
        val now = kotlin.time.Clock.System.now()
        val quote = FakeSwapQuote(
            destinationAsset = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
            originAsset = SwapAssetTestFixture.zecAsset(),
            mode = SwapMode.EXACT_INPUT,
            amountInUsd = BigDecimal("10"),
            amountOutUsd = BigDecimal("9.85"),
            timestamp = now,
            deadline = now + kotlin.time.Duration.parse("5m"),
        )
        fun map(q: FakeSwapQuote) = mapper.createState(
            SwapQuoteInternalState(null, q, zashi()), {}, {}, {}
        )
        assertEquals(
            co.electriccoin.zcash.ui.design.component.QuoteValueDifferenceState(
                percentage = co.electriccoin.zcash.ui.design.util.stringRes("-1.50%"),
                explanation = co.electriccoin.zcash.ui.design.util.stringRes(R.string.private_value_difference_known),
                isNegative = true,
                validUntilMillis = (now + kotlin.time.Duration.parse("3m")).toEpochMilliseconds(),
            ),
            map(quote).to.valueDifference,
        )
        kotlin.test.assertNull(map(quote.copy(timestamp = now - kotlin.time.Duration.parse("4m"))).to.valueDifference)
        val missingOutput = map(quote.copy(amountOutUsd = BigDecimal.ZERO))
        kotlin.test.assertNull(missingOutput.to.valueDifference)
        assertEquals(
            co.electriccoin.zcash.ui.design.util.stringRes(R.string.private_quote_estimate_unavailable),
            missingOutput.to.fiatAmount,
        )
        val missingInput = map(quote.copy(amountInUsd = BigDecimal.ZERO))
        kotlin.test.assertNull(missingInput.to.valueDifference)
        assertEquals(
            co.electriccoin.zcash.ui.design.util.stringRes(R.string.private_quote_estimate_unavailable),
            missingInput.from.fiatAmount,
        )
        assertEquals(
            co.electriccoin.zcash.ui.design.util.stringRes(R.string.private_quote_estimate_unavailable).withStyle(),
            missingInput.amount.subtitle,
        )
    }

    @Test
    fun exactOutputDisplaysActualIncludedAllowance() {
        val quote = FakeSwapQuote(
            destinationAsset = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
            originAsset = SwapAssetTestFixture.zecAsset(),
            mode = SwapMode.EXACT_OUTPUT,
            amountInFormatted = BigDecimal("0.00906756"),
            amountInUsd = BigDecimal("11.54073699"),
            amountOutUsd = BigDecimal("9.99997785"),
            inputAllowance = BigDecimal("0.00004534"),
            slippage = BigDecimal("0.5"),
        )
        val state = mapper.createState(SwapQuoteInternalState(null, quote, zashi()), {}, {}, {})
        val allowance = state.items.last()
        assertEquals(R.string.private_included_input_allowance, allowance.description.resourceId())
        assertEquals(
            co.electriccoin.zcash.ui.design.util.stringRes(cash.z.ecc.android.sdk.model.Zatoshi(4534)).withStyle(),
            allowance.title,
        )
        assertEquals(co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber(
            BigDecimal("0.0577064850"),
            cash.z.ecc.android.sdk.model.FiatCurrency.USD.symbol,
        ).withStyle(), allowance.subtitle)
        assertEquals(quote.amountInFormatted, SwapQuoteInternalState(null, quote, zashi()).total)
        val zeroAllowance = mapper.createState(
            SwapQuoteInternalState(null, quote.copy(inputAllowance = BigDecimal.ZERO), zashi()), {}, {}, {}
        ).items.last()
        val zeroFiat = (zeroAllowance.subtitle as StyledStringResource.ByStringResource).resource
        assertEquals("$0.00", zeroFiat.getString(RuntimeEnvironment.getApplication(), Locale.US))
    }

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
    fun incomingQuoteDoesNotInventAWalletTransactionFee() {
        val items = createItems(zashi(), SwapAssetTestFixture.zecAsset())
        assertFalse(items.any { it.description.resourceId() == R.string.private_wallet_transaction_fee })
    }

    @Test
    fun tokenAmountsAndMinimumKeepExactAtomicPrecision() {
        val quote = FakeSwapQuote(
            destinationAsset = SwapAssetTestFixture.zecAsset(),
            mode = SwapMode.FLEX_INPUT,
            amountInFormatted = BigDecimal("0.00012345"),
            amountOutFormatted = BigDecimal("0.12345678"),
            minimumAmountOut = BigDecimal("0.12283949"),
        )
        val state = mapper.createState(
            SwapQuoteInternalState(null, quote, zashi()),
            onBack = {},
            onSubmitQuoteClick = {},
            onNavigateToOnRampSwap = {}
        )
        val context: Application = RuntimeEnvironment.getApplication()
        assertEquals("0.00012345", state.from.amount.getString(context, Locale.US))
        assertEquals("0.12345678", state.to.amount.getString(context, Locale.US))
        assertEquals(
            "Minimum received: 0.12283949 zec",
            checkNotNull(state.infoText).getString(context, Locale.US)
        )
        val total = (state.amount.title as StyledStringResource.ByStringResource).resource
        assertEquals("0.00012345 btc", total.getString(context, Locale.US))
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
        val label = composeTestRule.onNodeWithText("NEAR platform fee")
        val included = composeTestRule.onNodeWithText("0.25% included in quote")
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val titleBounds = included.fetchSemanticsNode().boundsInRoot
        val rowBounds = composeTestRule.onNodeWithTag("quote-rows").fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.width >= rowBounds.width * .95f, "Provider label lost its available width")
        assertTrue(labelBounds.height < labelBounds.width * .3f, "Provider label became a vertical character column")
        assertTrue(titleBounds.top >= labelBounds.bottom, "Explanation must be below the label")
        assertTrue(titleBounds.bottom - labelBounds.top < rowBounds.width * .7f, "Provider block is excessively tall")
        for (node in listOf(label, included)) {
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
