package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Real repositories and shared submission pipeline; only SDK, storage and endpoint I/O are mocked. */
class SwapBroadcastDeadlineTest {
    @Test
    fun expiryDuringPreparationPreventsFirstSoftwareAndPcztBroadcast() = runBlocking {
        for (hardware in listOf(false, true)) {
            val fixture = Fixture(hardware)
            fixture.prepare()
            val result = fixture.whileSuspended { fixture.expire() }
            assertIs<IllegalStateException>(result.exceptionOrNull())
            fixture.verifyNoBroadcast()
            // The durable reference remains reserved, so repeating approval cannot create a second funding.
            assertTrue(runCatching { fixture.submit() }.isFailure)
            fixture.verifyNoBroadcast()
        }
    }

    @Test
    fun expiryDuringEndpointSelectionPreventsFirstSoftwareAndPcztBroadcast() = runBlocking {
        for (hardware in listOf(false, true)) {
            val fixture = Fixture(hardware, pauseEndpoints = true)
            fixture.prepare()
            assertIs<IllegalStateException>(fixture.whileSuspended { fixture.expire() }.exceptionOrNull())
            fixture.verifyNoBroadcast()
        }
    }

    @Test
    fun replacementDuringPreparationPreventsFirstSoftwareAndPcztBroadcast() = runBlocking {
        for (hardware in listOf(false, true)) {
            val fixture = Fixture(hardware)
            fixture.prepare()
            assertIs<IllegalStateException>(fixture.whileSuspended { fixture.clear() }.exceptionOrNull())
            fixture.verifyNoBroadcast()
        }
    }

    @Test
    fun liveReviewedSoftwareAndPcztTransactionsStillBroadcast() = runBlocking {
        for (hardware in listOf(false, true)) {
            val fixture = Fixture(hardware)
            fixture.prepare()
            assertIs<SubmitResult.Success>(fixture.whileSuspended {}.getOrThrow())
            coVerify(exactly = 1) { fixture.broadcaster.submit(fixture.transactions[0], any()) }
        }
    }

    @Test
    fun expiryAfterAnUncertainFirstLegPreservesEveryCreatedLegAndTracking() = runBlocking {
        for (hardware in listOf(false, true)) {
            val fixture = Fixture(hardware, legs = 2)
            fixture.prepare()
            coEvery { fixture.broadcaster.submit(fixture.transactions[0], any()) } answers {
                fixture.expire()
                TransactionSubmitResult.Failure(fixture.transactions[0].txId, true, -1, "synthetic uncertain response")
            }
            assertIs<SubmitResult.GrpcFailure>(fixture.whileSuspended {}.getOrThrow())
            for (transaction in fixture.transactions) {
                coVerify(exactly = 1) { fixture.broadcaster.submit(transaction, any()) }
            }
            coVerify(exactly = 1) {
                fixture.metadata.markTxAsSwap(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    account = any(), walletWriteToken = 0L,
                )
            }
        }
    }

    private class Fixture(private val hardware: Boolean, private val pauseEndpoints: Boolean = false, legs: Int = 1) {
        val synchronizer = mockk<Synchronizer>()
        val broadcaster = synchronizer.apply { every { broadcaster } returns mockk() }.broadcaster
        val metadata = mockk<MetadataRepository>(relaxed = true)
        val transactions = (1..legs).map {
            CreatedTransaction(
                FirstClassByteArray(byteArrayOf(it.toByte())), FirstClassByteArray(byteArrayOf(1, 2)), null
            )
        }
        private val endpoint = LightWalletEndpoint("synthetic.invalid", 443, true)
        private val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private var wall = Instant.parse("2099-12-31T23:59:59Z")
        private val clock = object : kotlin.time.Clock { override fun now() = wall }
        private var elapsed = 0L
        private lateinit var publication: SwapQuoteData.Success
        private val key = mockk<UnifiedSpendingKey>()
        private val accounts = mockk<AccountDataSource>()
        private val signer = mockk<co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider>()
        private val dataSource = ProposalDataSourceImpl(
            mockk { coEvery { getSynchronizer() } returns this@Fixture.synchronizer },
            mockk { every { getEndpoints() } returns listOf(endpoint) },
            mockk {
                coEvery { getServerSelection() } coAnswers {
                    if (pauseEndpoints) pause()
                    WalletServerSelection(this@Fixture.endpoint, isAutomatic = true)
                }
            }
        )
        private val tracking = ProcessSwapTransactionUseCase(metadata, mockk())
        private val software = ZashiProposalRepositoryImpl(
            accounts, dataSource, mockk { coEvery { getZashiSpendingKey() } returns key }, tracking
        )
        private val keystone = KeystoneProposalRepositoryImpl(accounts, dataSource, signer, tracking)
        private lateinit var reviewed: ExactInputSwapTransactionProposal

        init {
            coEvery { metadata.getSwapMetadata(any(), any()) } returns null
            coEvery {
                metadata.markTxAsSwap(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    account = any(), walletWriteToken = 0L,
                )
            } returns true
            coEvery { broadcaster.createProposedTransactions(any(), any()) } coAnswers {
                if (!pauseEndpoints) pause()
                transactions
            }
            coEvery { broadcaster.createTransactionFromPczt(any(), any()) } coAnswers {
                if (!pauseEndpoints) pause()
                transactions
            }
            coEvery { broadcaster.submit(any(), any()) } answers {
                TransactionSubmitResult.Success(firstArg<CreatedTransaction>().txId)
            }
        }

        suspend fun prepare() {
            val quote = FakeSwapQuote(
                originAsset = SwapAssetTestFixture.zecAsset(), destinationAsset = SwapAssetTestFixture.asset(),
                mode = SwapMode.EXACT_INPUT, amountIn = BigDecimal("100000000"),
                amountInFormatted = BigDecimal.ONE, amountOutFormatted = BigDecimal.ONE,
                depositAddress = "synthetic-deposit", destinationAddress = "synthetic-recipient",
                refundAddress = "synthetic-refund"
            )
            val reviewedQuote = object : SwapQuote by quote {
                override val deadline = Instant.parse("2100-01-01T00:00:00Z")
            }
            publication = SwapQuoteData.Success(reviewedQuote, fundingClock = clock, elapsedRealtime = { elapsed })
            assertTrue(publication.canFund())
            reviewed = ExactInputSwapTransactionProposal(
                WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""),
                mockk<Proposal> { every { totalFeeRequired() } returns Zatoshi(1000) }, reviewedQuote, mockk()
            )
            if (hardware) {
                keystone.transactionProposal.value = reviewed
                coEvery { accounts.getSelectedAccount() } returns mockk<ZodlAccount> {
                    every { sdkAccount } returns mockk(relaxed = true)
                    every { isSpendable } returns true
                }
                coEvery { synchronizer.createPcztFromProposal(any(), any()) } returns Pczt(byteArrayOf(1))
                coEvery { synchronizer.addProofsToPczt(any()) } returns Pczt(byteArrayOf(2))
                coEvery { signer.parsePczt(any()) } returns
                    ("keystone:fw_version".toByteArray() + byteArrayOf(3, 13, 0, 1))
                keystone.createPCZTFromProposal()
                keystone.approveForSigning(reviewed, publication)
                keystone.parsePCZT(mockk(), checkNotNull(keystone.signingPreparationId()))
            } else {
                software.transactionProposal.value = reviewed
            }
        }

        suspend fun submit(): SubmitResult =
            if (hardware) keystone.submit(reviewed) else software.submit(reviewed, publication)

        suspend fun whileSuspended(change: () -> Unit): Result<SubmitResult> = coroutineScope {
            withTimeout(5.seconds) {
                val result = async { runCatching { submit() } }
                entered.await()
                assertFalse(result.isCompleted)
                try {
                    change()
                } finally {
                    released.complete(Unit)
                }
                result.await()
            }
        }

        fun expire() {
            wall -= 2.seconds
            elapsed = 1_000L
        }
        fun clear() { if (hardware) keystone.clear() else software.clear() }
        fun verifyNoBroadcast() { coVerify(exactly = 0) { broadcaster.submit(any(), any()) } }
        private suspend fun pause() {
            entered.complete(Unit)
            released.await()
        }
    }
}
