package co.electriccoin.zcash.ui.common.mapper

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
    fun decodedExecutionInputDoesNotChangeQuotedIncompleteDepositRequirement() {
        val live = SwapInputProvenanceFixture.status(
            SwapCatalogTestFixture.nativeZec, SwapCatalogTestFixture.externalAssets.first(), "synthetic-deposit",
            SwapType.FLEX_INPUT, """{"amountIn":"80000000","depositedAmount":"10000000"}"""
        )
        val status = live.copy(response = live.response.copy(
            status = co.electriccoin.zcash.ui.common.model.near.SwapStatus.INCOMPLETE_DEPOSIT
        ))
        val text = assertNotNull(SwapSupportMapper().getMessage(status)).text as StyledStringResource.ByResource
        val required = text.args.first() as StyledStringResource.ByStringResource
        val amount = required.resource as StringResource.ByCurrencyNumber
        assertEquals(0, BigDecimal("0.9").compareTo(amount.amount as BigDecimal))
        assertEquals(SwapCatalogTestFixture.nativeZec.tokenTicker, amount.ticker)
    }

    @Test
    fun incompleteDepositKeepsExactRequiredTokenAmount() {
        val quoteStatus = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.INCOMPLETE_DEPOSIT
            every { quotedAmountIn } returns BigDecimal("0.22283949")
            every { amountInFormatted } returns BigDecimal("0.2")
            every { depositedAmountFormatted } returns BigDecimal("0.1")
            every { originAsset } returns SwapAssetTestFixture.zecAsset()
            every { deadline } returns Instant.fromEpochSeconds(0)
        }
        val message = assertNotNull(SwapSupportMapper().getMessage(quoteStatus))
        val text = message.text as StyledStringResource.ByResource
        val required = text.args.first() as StyledStringResource.ByStringResource
        assertEquals(
            stringResByCurrencyNumber(BigDecimal("0.12283949"), "zec", minDecimals = 0),
            required.resource
        )
    }
}
