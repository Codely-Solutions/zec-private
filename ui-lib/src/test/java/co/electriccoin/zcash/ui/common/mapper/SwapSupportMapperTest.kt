package co.electriccoin.zcash.ui.common.mapper

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.near.SwapInputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwapSupportMapperTest {
    @Test
    fun decodedExecutionInputDoesNotChangeTheThresholdForAnyFundingMode() {
        val expected = mapOf(
            SwapType.EXACT_INPUT to BigDecimal("0.9"),
            SwapType.EXACT_OUTPUT to BigDecimal("0.895"),
            SwapType.FLEX_INPUT to BigDecimal("0.895"),
        )
        for ((mode, amount) in expected) assertModeSpecificShortfall(mode, amount)
    }

    private fun assertModeSpecificShortfall(mode: SwapType, expectedAmount: BigDecimal) {
        val live = SwapInputProvenanceFixture.status(
            SwapCatalogTestFixture.nativeZec, SwapCatalogTestFixture.externalAssets.first(), "synthetic-deposit",
            mode, """{"amountIn":"80000000","depositedAmount":"10000000"}"""
        )
        val response = live.response.quoteResponse
        val status = live.copy(response = live.response.copy(
            status = co.electriccoin.zcash.ui.common.model.near.SwapStatus.INCOMPLETE_DEPOSIT,
            quoteResponse = response.copy(quote = response.quote.copy(
                deadline = futureDeadline, minAmountIn = BigDecimal("99500000")
            )),
        ))
        val message = assertNotNull(SwapSupportMapper().getMessage(status, approvedMetadata, true))
        val text = message.text as StyledStringResource.ByResource
        val required = text.args.first() as StyledStringResource.ByStringResource
        val amount = required.resource as StringResource.ByCurrencyNumber
        assertEquals(0, expectedAmount.compareTo(amount.amount as BigDecimal))
        assertEquals(SwapCatalogTestFixture.nativeZec.tokenTicker, amount.ticker)
    }

    @Test
    fun incompleteDepositKeepsExactRequiredTokenAmount() {
        val quoteStatus = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.INCOMPLETE_DEPOSIT
            every { fundingThreshold } returns BigDecimal("0.22283949")
            every { amountInFormatted } returns BigDecimal("0.2")
            every { depositedAmountFormatted } returns BigDecimal("0.1")
            every { originAsset } returns SwapAssetTestFixture.zecAsset()
            every { deadline } returns futureDeadline
        }
        val message = assertNotNull(SwapSupportMapper().getMessage(quoteStatus, approvedMetadata, true))
        val text = message.text as StyledStringResource.ByResource
        val required = text.args.first() as StyledStringResource.ByStringResource
        assertEquals(
            stringResByCurrencyNumber(BigDecimal("0.12283949"), "zec", minDecimals = 0),
            required.resource
        )
    }
    @Test
    fun incompleteDepositWithoutAnApprovedPositiveShortfallOffersSupportWithoutAnAmount() {
        val mapper = SwapSupportMapper()
        val demo = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.INCOMPLETE_DEPOSIT
            every { fundingThreshold } returns BigDecimal.ONE
            every { deadline } returns futureDeadline
        }
        for (observed in listOf(null, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN)) {
            every { demo.depositedAmountFormatted } returns observed
            val message = assertNotNull(mapper.getMessage(demo, approvedMetadata, true))
            val text = message.text as StyledStringResource.ByResource
            assertEquals(R.string.private_swap_incomplete_check_support, text.resource)
            assertEquals(emptyList(), text.args)
        }
        every { demo.depositedAmountFormatted } returns BigDecimal("0.5")
        val legacy = assertNotNull(mapper.getMessage(demo, isFundingWindowOpen = true))
        assertEquals(
            R.string.private_swap_incomplete_check_support, (legacy.text as StyledStringResource.ByResource).resource
        )
        val stale = assertNotNull(mapper.getMessage(demo, approvedMetadata))
        assertEquals(
            R.string.private_swap_incomplete_check_support, (stale.text as StyledStringResource.ByResource).resource
        )
        every { demo.deadline } returns Instant.fromEpochSeconds(0)
        val expired = assertNotNull(mapper.getMessage(demo, approvedMetadata, true))
        assertEquals(
            R.string.private_swap_incomplete_check_support, (expired.text as StyledStringResource.ByResource).resource
        )
        assertNotNull(mapper.getButton(demo) {})
    }

    private val futureDeadline = Instant.parse("2099-01-01T00:00:00Z")
    private val approvedMetadata = mockk<TransactionSwapMetadata> {
        every { reviewedIdentity } returns "v2:fixture"
    }

}
