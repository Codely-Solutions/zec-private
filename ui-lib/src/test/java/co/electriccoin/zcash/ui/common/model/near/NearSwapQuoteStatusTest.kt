package co.electriccoin.zcash.ui.common.model.near

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.positiveRefund
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.GetSwapStatusUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import co.electriccoin.zcash.ui.common.model.SwapStatus as ModelSwapStatus

/** Provider settlement state remains authoritative after the funding cutoff. */
class NearSwapQuoteStatusTest {
    @Test
    fun providerExpiryRemainsTerminal() {
        val swap = nearSwapQuoteStatus(SwapStatus.EXPIRED, Clock.System.now() - 1.hours)
        assertEquals(ModelSwapStatus.EXPIRED, swap.status)
    }
    @Test
    fun statusStaysPendingAfterDepositDeadline() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.PENDING_DEPOSIT,
                deadline = Clock.System.now() - 1.hours
            )
        assertEquals(ModelSwapStatus.PENDING, swap.status)
    }

    @Test
    fun statusStaysPendingNearDepositDeadline() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.PENDING_DEPOSIT,
                deadline = Clock.System.now() + 3.minutes
            )
        assertEquals(ModelSwapStatus.PENDING, swap.status)
    }

    @Test
    fun statusIsPendingWhenPendingDepositAndDeadlineFarInFuture() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.PENDING_DEPOSIT,
                deadline = Clock.System.now() + 1.hours
            )
        assertEquals(ModelSwapStatus.PENDING, swap.status)
    }

    @Test
    fun statusPreservedWhenSuccessEvenIfDeadlinePassed() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.SUCCESS,
                deadline = Clock.System.now() - 1.hours
            )
        assertEquals(ModelSwapStatus.SUCCESS, swap.status)
    }

    @Test
    fun statusPreservedWhenFailedEvenIfDeadlinePassed() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.FAILED,
                deadline = Clock.System.now() - 1.hours
            )
        assertEquals(ModelSwapStatus.FAILED, swap.status)
    }

    @Test
    fun statusPreservedWhenRefundedEvenIfDeadlinePassed() {
        val swap =
            nearSwapQuoteStatus(
                serverStatus = SwapStatus.REFUNDED,
                deadline = Clock.System.now() - 1.hours
            )
        assertEquals(ModelSwapStatus.REFUNDED, swap.status)
    }

    @Test
    fun executedOutputUsesRawBaseUnitsWhenFormattedFieldIsMissing() {
        val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val swap = original.copy(
            response = original.response.copy(swapDetails = SwapDetails(amountOut = BigDecimal("2300001")))
        )
        assertEquals(BigDecimal("2.300001"), swap.executedAmountOut)
        assertEquals(BigDecimal("2.300001"), swap.amountOutFormatted)
        assertEquals(BigDecimal("2"), swap.quotedAmountOut)
    }

    @Test
    fun detailDoesNotPresentUnavailableQuotedFiatAsZeroDollars() {
        val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val quoteResponse = original.response.quoteResponse
        val quoteWithoutFiat = quoteResponse.copy(
            quote = quoteResponse.quote.copy(amountInUsd = BigDecimal.ZERO, amountOutUsd = BigDecimal.ZERO),
        )
        val status = original.copy(response = original.response.copy(quoteResponse = quoteWithoutFiat))
        assertEquals(null, status.amountInUsd)
        assertEquals(null, status.amountOutUsd)
        val header = CommonTransactionDetailMapper()
            .createTransactionDetailQuoteHeaderState(status, status.originAsset, status.destinationAsset)
        val input = assertNotNull(header.from)
        val output = assertNotNull(header.to)
        assertEquals(stringResByNumber(status.quotedAmountIn), input.amount)
        assertEquals(stringResByNumber(status.quotedAmountOut), output.amount)
        assertEquals(stringRes(""), input.fiatAmount)
        assertEquals(stringRes(""), output.fiatAmount)
    }

    @Test
    fun detailKeepsOutputAmountsAndUsdFromTheSameObservation() {
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        try {
            val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
            val quote = Json.encodeToString(QuoteResponseDto.serializer(), original.response.quoteResponse)
            for ((details, amount, usd) in listOf(
                Triple("""{"amountOut":"2300001","amountOutUsd":"11"}""", "2.300001", "11"),
                Triple("""{"amountOut":"2300001"}""", "2.300001", null),
                Triple("""{"amountOutUsd":"11"}""", "2", "10"),
                Triple("{}", "2", "10"),
            )) {
                val response = Json.decodeFromString<SwapStatusResponseDto>(
                    """{"quoteResponse":$quote,"status":"SUCCESS","updatedAt":"2026-11-23T00:00:00Z",
                        "swapDetails":$details}"""
                )
                val status = original.copy(response = response)
                val output = assertNotNull(
                    CommonTransactionDetailMapper()
                        .createTransactionDetailQuoteHeaderState(status, status.originAsset, status.destinationAsset).to
                )
                val label = if (amount == "2") R.string.private_quoted_output else R.string.private_provider_output
                val fiat = usd?.let { stringResByDynamicCurrencyNumber(BigDecimal(it), FiatCurrency.USD.symbol) }
                assertEquals(stringResByNumber(BigDecimal(amount)), output.amount, details)
                assertEquals(status.destinationAsset.chainName + stringRes(" · ") + stringRes(label), output.chain)
                assertEquals(fiat ?: stringRes(""), output.fiatAmount, details)
            }
        } finally {
            unmockkObject(FiatCurrency.USD)
        }
    }

    @Test
    fun settlementDecoderRejectsFractionalBaseUnitsAndKeepsSparseWholeValues() {
        val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val quote = Json.encodeToString(QuoteResponseDto.serializer(), original.response.quoteResponse)
        fun response(details: String) = Json.decodeFromString<SwapStatusResponseDto>(
            """{"quoteResponse":$quote,"status":"SUCCESS","updatedAt":"2026-11-23T00:00:00Z",
                "swapDetails":$details}"""
        )

        for (field in listOf("amountIn", "amountOut", "depositedAmount", "refundedAmount")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { response("""{"$field":"0.5"}""") }
        }
        val resolved = original.copy(response = response("""{"amountOut":"2300001.0"}"""))
        assertEquals(BigDecimal("2.3000010"), resolved.executedAmountOut)
    }

    @Test
    fun formattedOnlySettlementUsesTheResolvedPrecisionForEachLeg() {
        val sparse = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val fields = mapOf(
            "amountInFormatted" to 8, "depositedAmountFormatted" to 8,
            "refundedAmountFormatted" to 8, "amountOutFormatted" to 6,
        )
        for ((field, decimals) in fields) {
            val atomicUnit = BigDecimal.ONE.movePointLeft(decimals)
            val exact = atomicUnit.setScale(decimals + 1)
            val accepted = sparse.withDecodedDetails("""{"$field":"${exact.toPlainString()}"}""")
            val amount = when (field) {
                "amountInFormatted" -> accepted.executedAmountIn
                "amountOutFormatted" -> accepted.executedAmountOut
                "depositedAmountFormatted" -> accepted.depositedAmountFormatted
                else -> accepted.refundedFormatted
            }
            assertEquals(exact, amount)
            assertFailsWith<SwapQuoteMismatchException.AmountInconsistency> {
                sparse.withDecodedDetails("""{"$field":"${atomicUnit.movePointLeft(1).toPlainString()}"}""")
            }
        }
    }

    @Test
    fun invalidFormattedRefreshKeepsTheSavedSettlementAvailableToDetails() = runTest {
        val sparse = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val accepted = sparse.withDecodedDetails("""{"amountOutFormatted":"2.123456"}""")
        val saved = mockk<TransactionSwapMetadata> {
            every { status } returns ModelSwapStatus.SUCCESS
            every { executedAmountOut } returns accepted.executedAmountOut
        }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(DEPOSIT_ADDRESS) } returns saved
        }
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(saved) } answers {
                sparse.withDecodedDetails("""{"amountOutFormatted":"2.1234567"}""")
            }
        }

        val details = GetSwapStatusUseCase(metadata, swaps)(DEPOSIT_ADDRESS)
        assertIs<SwapQuoteMismatchException.AmountInconsistency>(details.error)
        assertNull(details.status)
        assertSame(saved, details.savedMetadata)
        assertEquals(BigDecimal("2.123456"), details.savedMetadata?.executedAmountOut)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
    }

    private fun NearSwapQuoteStatus.withDecodedDetails(details: String): NearSwapQuoteStatus {
        val quote = Json.encodeToString(QuoteResponseDto.serializer(), response.quoteResponse)
        val status = Json.decodeFromString<SwapStatusResponseDto>(
            """{"quoteResponse":$quote,"status":"SUCCESS","updatedAt":"2026-11-23T00:00:00Z","swapDetails":$details}"""
        )
        return copy(response = status)
    }

    @Test
    fun rawZeroInputDoesNotDiscardPendingStatusOrQuotedFundingRequirement() {
        val original = nearSwapQuoteStatus(SwapStatus.INCOMPLETE_DEPOSIT, Clock.System.now() + 1.hours)
        val status = original.copy(response = original.response.copy(
            swapDetails = SwapDetails(amountIn = BigDecimal.ZERO)
        ))
        assertEquals(ModelSwapStatus.INCOMPLETE_DEPOSIT, status.status)
        assertEquals(BigDecimal.ZERO.setScale(8), status.executedAmountIn)
        assertEquals(BigDecimal("1"), status.quotedAmountIn)
        assertEquals(null, status.amountInUsd)
    }

    @Test
    fun missingExecutionRemainsExplicitEvenWhenStatusIsSuccessful() {
        val swap = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        assertEquals(null, swap.executedAmountOut)
        assertEquals(BigDecimal("2"), swap.amountOutFormatted)
        assertEquals(BigDecimal("2.000000"), swap.quote.minimumAmountOut)
    }

    @Test
    fun actualPositiveRefundIsVisibleForSuccessAndFullyRefundedStatus() {
        listOf(SwapStatus.SUCCESS, SwapStatus.REFUNDED).forEach { status ->
            val original = nearSwapQuoteStatus(status, Clock.System.now() + 1.hours)
            val swap = original.copy(response = original.response.copy(
                swapDetails = SwapDetails(refundedAmount = BigDecimal("123456"))
            ))
            assertEquals(BigDecimal("0.00123456"), swap.positiveRefund)
        }
    }

    @Test
    fun missingOrZeroRefundDoesNotInferOneFromDeposit() {
        val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        assertEquals(null, original.positiveRefund)
        listOf(null, BigDecimal.ZERO).forEach { refund ->
            val swap = original.copy(response = original.response.copy(
                swapDetails = SwapDetails(
                    refundedAmountFormatted = refund,
                    depositedAmountFormatted = BigDecimal("2"),
                    amountInFormatted = BigDecimal("1")
                )
            ))
            assertEquals(null, swap.positiveRefund)
        }
    }

    @Test
    fun keepsEachReportedTransactionInItsOwnLeg() {
        val original = nearSwapQuoteStatus(SwapStatus.SUCCESS, Clock.System.now() + 1.hours)
        val swap = original.copy(response = original.response.copy(swapDetails = SwapDetails(
            originChainTxHashes = listOf(TransactionChainHash("deposit-a"), TransactionChainHash("deposit-b")),
            destinationChainTxHashes = listOf(TransactionChainHash("payment-a"))
        )))
        assertEquals(listOf("deposit-a", "deposit-b"), swap.originTransactionIds)
        assertEquals(listOf("payment-a"), swap.destinationTransactionIds)
    }

    private fun nearSwapQuoteStatus(serverStatus: SwapStatus, deadline: Instant) =
        NearSwapQuoteStatus(
            response =
                SwapStatusResponseDto(
                    quoteResponse = quoteResponse(deadline = deadline),
                    status = serverStatus,
                    updatedAt = ""
                ),
            origin = asset(assetId = ORIGIN_ID, token = "TKA", chain = "chaina", decimals = 8),
            destination = asset(assetId = DEST_ID, token = "TKB", chain = "chainb", decimals = 6),
            depositAddress = DynamicSwapAddress(DEPOSIT_ADDRESS),
            destinationAddress = DynamicSwapAddress(RECIPIENT_ADDRESS),
            refundAddress = DynamicSwapAddress(REFUND_ADDRESS),
        )

    private fun quoteResponse(deadline: Instant): QuoteResponseDto =
        QuoteResponseDto(
            timestamp = EPOCH,
            quoteRequest =
                QuoteRequest(
                    dry = false,
                    swapType = SwapType.EXACT_INPUT,
                    slippageTolerance = 100,
                    originAsset = ORIGIN_ID,
                    destinationAsset = DEST_ID,
                    amount = BigDecimal("100000000"),
                    refundTo = REFUND_ADDRESS,
                    recipient = RECIPIENT_ADDRESS,
                    deadline = deadline,
                    appFees = emptyList()
                ),
            quote =
                QuoteDetails(
                    depositAddress = DEPOSIT_ADDRESS,
                    amountIn = BigDecimal("100000000"),
                    amountInFormatted = BigDecimal("1"),
                    amountInUsd = BigDecimal("10"),
                    minAmountIn = BigDecimal("100000000"),
                    amountOut = BigDecimal("2000000"),
                    amountOutFormatted = BigDecimal("2"),
                    amountOutUsd = BigDecimal("10"),
                    minAmountOut = BigDecimal("2000000"),
                    deadline = deadline
                )
        )

    private fun asset(assetId: String, token: String, chain: String, decimals: Int): SwapAsset =
        NearSwapAsset(
            tokenTicker = token,
            tokenName = StringResource.ByString(token),
            tokenIcon = imageRes(token),
            usdPrice = null,
            assetId = assetId,
            decimals = decimals,
            blockchain =
                SwapBlockchain(
                    chainTicker = chain,
                    chainName = StringResource.ByString(chain),
                    chainIcon = imageRes(chain)
                )
        )

    private companion object {
        const val ORIGIN_ID = "tka.chaina"
        const val DEST_ID = "tkb.chainb"
        const val DEPOSIT_ADDRESS = "deposit-address"
        const val RECIPIENT_ADDRESS = "recipient-address"
        const val REFUND_ADDRESS = "refund-address"
        val EPOCH: Instant = Instant.fromEpochSeconds(0)
    }
}
