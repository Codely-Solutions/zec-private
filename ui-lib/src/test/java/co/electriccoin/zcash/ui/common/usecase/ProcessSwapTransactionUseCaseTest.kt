package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * [ProcessSwapTransactionUseCase] records a freshly-submitted swap: it writes the swap metadata
 * (deposit address + quote details, status PENDING) and then forwards each broadcast transaction id
 * to the swap provider as a deposit. Empty tx ids are skipped, and a per-deposit submission failure
 * is logged and swallowed so the remaining deposits still go out.
 *
 * Runs under Robolectric because the swallowed-failure path logs via `Twig` (android.util.Log).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessSwapTransactionUseCaseTest {
    private val origin = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")
    private val destinationAsset = SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")

    @Test
    fun existingTrackingIsPreservedWhenProcessingSubmission() = runTest {
        val fx = Fixtures()
        coEvery { fx.metadataRepository.getSwapMetadata(any(), any()) } returns mockk<TransactionSwapMetadata>()
        val proposal = swapProposal()
        fx.useCase(proposal, SubmitResult.Success(listOf("tx1")))
        coVerify(exactly = 0) {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        }
        coVerify(exactly = 1) { fx.swapRepository.submitDepositTransaction("tx1", proposal) }
    }

    @Test
    fun pendingTrackingMustBeSavedBeforeFunding() = runTest {
        val fx = Fixtures()
        coEvery {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        } returns false
        val proposal = swapProposal()
        assertFailsWith<IllegalStateException> { fx.useCase.reserveFunding(proposal) {} }
        coEvery {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        } returns true
        fx.useCase.reserveFunding(proposal) {}
    }

    @Test
    fun concurrentReservationsForTheSameDepositCannotBothFund() = runTest {
        val fx = Fixtures()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        coEvery {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        } coAnswers {
            firstWriteStarted.complete(Unit)
            finishFirstWrite.await()
            true
        }

        val first = async { fx.useCase.reserveFunding(swapProposal()) {} }
        firstWriteStarted.await()
        val duplicate = async {
            assertFailsWith<IllegalStateException> { fx.useCase.reserveFunding(swapProposal()) {} }
        }
        finishFirstWrite.complete(Unit)
        first.await()
        duplicate.await()

        coVerify(exactly = 1) {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        }
    }

    @Test
    fun existingDurableTrackingRejectsAFundingAttemptAfterRestart() = runTest {
        val fx = Fixtures()
        coEvery { fx.metadataRepository.getSwapMetadata(any(), any()) } returns mockk<TransactionSwapMetadata>()

        assertFailsWith<IllegalStateException> { fx.useCase.reserveFunding(swapProposal()) {} }

        coVerify(exactly = 0) {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        }
    }

    @Test
    fun supersededProposalDuringMetadataLookupNeverReservesFunding() = runTest {
        val fx = Fixtures()
        val lookupStarted = CompletableDeferred<Unit>()
        val finishLookup = CompletableDeferred<Unit>()
        var stillReviewed = true
        coEvery { fx.metadataRepository.getSwapMetadata(any(), any()) } coAnswers {
            lookupStarted.complete(Unit)
            finishLookup.await()
            null
        }
        val reservation = async {
            assertFailsWith<IllegalStateException> {
                fx.useCase.reserveFunding(swapProposal()) { check(stillReviewed) }
            }
        }

        lookupStarted.await()
        stillReviewed = false
        finishLookup.complete(Unit)
        reservation.await()

        coVerify(exactly = 0) {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        }
    }

    @Test
    fun savesSwapMetadataFromProposalAndQuote() =
        runTest {
            val fx = Fixtures()
            val reviewedAccount = mockk<WalletAccount>()
            val proposal = swapProposal(reviewedAccount)

            fx.useCase(proposal, SubmitResult.Success(listOf("tx1")))

            coVerify(exactly = 1) { fx.metadataRepository.getSwapMetadata("deposit-address", reviewedAccount) }
            coVerify(exactly = 1) {
                fx.metadataRepository.markTxAsSwap(
                    depositAddress = "deposit-address",
                    provider = "near",
                    origin = origin,
                    destination = destinationAsset,
                    totalFees = Zatoshi(1000),
                    totalFeesUsd = BigDecimal("0.5"),
                    amountOutFormatted = BigDecimal("1.23"),
                    mode = SwapMode.EXACT_INPUT,
                    status = SwapStatus.PENDING,
                    account = reviewedAccount,
                )
            }
        }

    @Test
    fun submitsEachTxIdAsADepositTransaction() =
        runTest {
            val fx = Fixtures()
            val proposal = swapProposal()

            fx.useCase(proposal, SubmitResult.Success(listOf("tx1", "tx2")))

            coVerify(exactly = 1) {
                fx.swapRepository.submitDepositTransaction(txId = "tx1", transactionProposal = proposal)
            }
            coVerify(exactly = 1) {
                fx.swapRepository.submitDepositTransaction(txId = "tx2", transactionProposal = proposal)
            }
            coVerify(exactly = 2) { fx.swapRepository.submitDepositTransaction(any(), any()) }
        }

    @Test
    fun skipsEmptyTxIds() =
        runTest {
            val fx = Fixtures()

            fx.useCase(swapProposal(), SubmitResult.Success(listOf("tx1", "", "tx2")))

            coVerify(exactly = 2) { fx.swapRepository.submitDepositTransaction(any(), any()) }
            coVerify(exactly = 0) { fx.swapRepository.submitDepositTransaction(txId = "", transactionProposal = any()) }
        }

    @Test
    fun savesMetadataButSubmitsNothingWhenThereAreNoTxIds() =
        runTest {
            val fx = Fixtures()

            fx.useCase(swapProposal(), SubmitResult.Success(emptyList()))

            coVerify(exactly = 1) {
                fx.metadataRepository.markTxAsSwap(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    account = any(),
                )
            }
            coVerify(exactly = 0) { fx.swapRepository.submitDepositTransaction(any(), any()) }
        }

    @Test
    fun continuesSubmittingRemainingDepositsWhenOneFails() =
        runTest {
            val fx = Fixtures()
            val proposal = swapProposal()
            val providerDetail = "synthetic provider detail"
            coEvery {
                fx.swapRepository.submitDepositTransaction(txId = "tx1", transactionProposal = any())
            } throws RuntimeException("Notification failed", IllegalStateException(providerDetail))

            ShadowLog.clear()
            fx.useCase(proposal, SubmitResult.Success(listOf("tx1", "tx2")))

            val logs = ShadowLog.getLogs()
            assertEquals(1, logs.count { "Unable to notify swap provider of deposit" in it.msg })
            for (entry in logs) {
                assertFalse(providerDetail in entry.msg)
                assertFalse(providerDetail in entry.throwable?.stackTraceToString().orEmpty())
            }
            coVerify(exactly = 1) {
                fx.swapRepository.submitDepositTransaction(txId = "tx1", transactionProposal = proposal)
            }
            coVerify(exactly = 1) {
                fx.swapRepository.submitDepositTransaction(txId = "tx2", transactionProposal = proposal)
            }
        }

    @Test
    fun cancellationStopsDepositNotificationsAndPreservesTracking() = runTest {
        val fx = Fixtures()
        val proposal = swapProposal()
        coEvery { fx.swapRepository.submitDepositTransaction("tx1", proposal) } throws CancellationException()

        assertFailsWith<CancellationException> {
            fx.useCase(proposal, SubmitResult.Success(listOf("tx1", "tx2")))
        }

        coVerify(exactly = 1) {
            fx.metadataRepository.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
            )
        }
        coVerify(exactly = 0) { fx.swapRepository.submitDepositTransaction("tx2", proposal) }
    }

    private suspend fun swapProposal(reviewedAccount: WalletAccount = mockk()): SwapTransactionProposal {
        val walletAddress = WalletAddress.Unified.new("deposit-address")
        val quote =
            mockk<SwapQuote> {
                every { provider } returns "near"
                every { originAsset } returns origin
                every { destinationAsset } returns this@ProcessSwapTransactionUseCaseTest.destinationAsset
                every { amountOutFormatted } returns BigDecimal("1.23")
                every { mode } returns SwapMode.EXACT_INPUT
            }
        return mockk {
            every { this@mockk.quote } returns quote
            every { fundingAccount } returns reviewedAccount
            every { destination } returns walletAddress
            every { totalFees } returns Zatoshi(1000)
            every { totalFeesUsd } returns BigDecimal("0.5")
        }
    }

    private class Fixtures {
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        val swapRepository = mockk<SwapRepository>(relaxed = true)
        val useCase = ProcessSwapTransactionUseCase(metadataRepository, swapRepository)

        init {
            coEvery { metadataRepository.getSwapMetadata(any(), any()) } returns null
            coEvery {
                metadataRepository.markTxAsSwap(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), account = any(),
                )
            } returns true
        }
    }
}
