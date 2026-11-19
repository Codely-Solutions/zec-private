package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SaveORSwapUseCaseTest {
    private val quote = FakeSwapQuote(
        originAsset = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
        destinationAsset = SwapAssetTestFixture.zecAsset(),
        mode = SwapMode.FLEX_INPUT,
        amountIn = BigDecimal("10000000"),
        amountInFormatted = BigDecimal("0.1"),
        amountOutFormatted = BigDecimal.ONE,
        depositAddress = "deposit",
        destinationAddress = "recipient",
        refundAddress = "refund",
    )

    @Test
    fun savesPendingReferenceWithoutLeavingDepositInstructions() = runTest {
        val metadata = metadata()
        val router = mockk<NavigationRouter>(relaxed = true)
        SaveORSwapUseCase(mockk<SwapRepository>(), metadata, router).persistBeforeFunding(quote)
        coVerify(exactly = 1) {
            metadata.markTxAsSwap("deposit", "near", quote.originAsset, quote.destinationAsset, any(), any(),
                BigDecimal.ONE, SwapMode.FLEX_INPUT, SwapStatus.PENDING)
        }
        verify(exactly = 0) { router.backToRoot() }
    }

    @Test
    fun trackingSaveFailureBlocksFundingInstructions() = runTest {
        val metadata = metadata()
        coEvery { metadata.markTxAsSwap(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        assertFailsWith<IllegalStateException> {
            SaveORSwapUseCase(mockk(), metadata, mockk()).persistBeforeFunding(quote)
        }
    }

    @Test
    fun existingRecordKeepsItsSettlementState() = runTest {
        val metadata = metadata()
        coEvery { metadata.getSwapMetadata("deposit") } returns mockk<TransactionSwapMetadata>()
        SaveORSwapUseCase(mockk(), metadata, mockk()).persistBeforeFunding(quote)
        coVerify(exactly = 0) { metadata.markTxAsSwap(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun metadata(): MetadataRepository = mockk {
        coEvery { getSwapMetadata(any()) } returns null
        coEvery { markTxAsSwap(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
    }
}
