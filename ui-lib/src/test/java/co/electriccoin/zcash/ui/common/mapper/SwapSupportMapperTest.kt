package co.electriccoin.zcash.ui.common.mapper

import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.util.StyledStringResource
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
    fun incompleteDepositKeepsExactRequiredTokenAmount() {
        val quoteStatus = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.INCOMPLETE_DEPOSIT
            every { amountInFormatted } returns BigDecimal("0.22283949")
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
