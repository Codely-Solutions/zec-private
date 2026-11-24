package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.datasource.AssetNotFoundException
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsUnavailableException
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * [GetSwapStatusUseCase.invoke] emits the first non-loading [SwapQuoteStatusData]: it looks up the
 * stored metadata for the deposit address and — when present — hands it to
 * [SwapRepository.checkSwapStatus] (which validates the returned assets against it), then persists the
 * latest status to metadata and surfaces it. A missing metadata entry or a status-lookup failure is
 * surfaced as an error state with metadata left untouched. The stored-metadata/returned-asset mismatch
 * itself is covered by `SwapRepositoryImplTest`.
 */
class GetSwapStatusUseCaseTest {
    private val btc = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")
    private val zec = SwapAssetTestFixture.zecAsset()

    @Test
    fun boundDetailRetriesAStorageReadFailureWithoutLookingUpAnUnverifiedReference() = runTest {
        val account = EditAccountFixtures.zodl()
        val saved = mockk<TransactionSwapMetadata>()
        val failure = java.io.IOException("fixture storage unavailable")
        var reads = 0
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata("deposit", account) } coAnswers {
                if (++reads == 1) throw failure
                saved
            }
            coEvery { updateSwap(saved, any()) } returns saved
        }
        val settled = swapStatusResult()
        val provider = mockk<SwapRepository> { coEvery { checkSwapStatus(saved) } returns settled }
        val useCase = GetSwapStatusUseCase(metadata, provider)

        val failed = useCase.observeForAccount("deposit", account).first { !it.isLoading }
        assertSame(failure, failed.error)
        coVerify(exactly = 0) { provider.checkSwapStatus(any()) }
        val retried = useCase.observeForAccount("deposit", account).first { !it.isLoading }
        assertSame(settled, retried.status)
        coVerify(exactly = 1) { provider.checkSwapStatus(saved) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refusingAnOlderResponseKeepsTheAlreadyAcceptedLiveDetails() = runTest {
        val expected = mockk<TransactionSwapMetadata>()
        val accepted = mockk<TransactionSwapMetadata> {
            every { status } returns SwapStatus.PROCESSING
            every { executedAmountOut } returns null
            every { amountOutFormatted } returns BigDecimal("1.23")
        }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata("deposit") } returns expected
            coEvery { updateSwap(expected, any()) } returnsMany listOf(accepted, null)
            coEvery { reloadSwapMetadata(expected) } returns accepted
        }
        val processing = swapStatusResult(swapStatus = SwapStatus.PROCESSING)
        val repository = mockk<SwapRepository> { coEvery { checkSwapStatus(expected) } returns processing }
        val states = mutableListOf<SwapQuoteStatusData>()
        val observer = backgroundScope.launch {
            GetSwapStatusUseCase(metadata, repository).observe("deposit").collect { states += it }
        }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertSame(accepted, states.last().savedMetadata)
        assertSame(processing, states.last().status)
        assertIs<IllegalStateException>(states.last().error)
        observer.cancel()
    }

    @Test
    fun emitsStatusAndPersistsMetadataOnSuccess() =
        runTest {
            val metadataRepository =
                mockk<MetadataRepository>(relaxed = true) {
                    coEvery { getSwapMetadata("deposit") } returns mockk<TransactionSwapMetadata>()
                    coEvery { updateSwap(any(), any()) } coAnswers { firstArg() }
                }
            val status = swapStatusResult()
            val swapRepository =
                mockk<SwapRepository> {
                    coEvery { checkSwapStatus(any()) } returns status
                }

            val result = GetSwapStatusUseCase(metadataRepository, swapRepository).invoke("deposit")

            assertFalse(result.isLoading)
            assertNull(result.error)
            assertEquals(status, result.status)
            coVerify(exactly = 1) {
                metadataRepository.updateSwap(any(), status)
            }
        }

    @Test
    fun firstRefreshFailureRetainsSavedMetadataForPendingAndCompletedSwaps() = runTest {
        for (status in listOf(SwapStatus.PENDING, SwapStatus.SUCCESS)) {
            val saved = mockk<TransactionSwapMetadata> { every { this@mockk.status } returns status }
            val metadata = mockk<MetadataRepository>(relaxed = true) {
                coEvery { getSwapMetadata("deposit") } returns saved
                coEvery { reloadSwapMetadata(saved) } returns saved
            }
            val failure = AssetNotFoundException("nep141:btc.omft.near")
            val repository = mockk<SwapRepository> {
                coEvery { checkSwapStatus(saved) } throws failure
            }
            val result = GetSwapStatusUseCase(metadata, repository)("deposit")
            assertSame(saved, result.savedMetadata)
            assertEquals(status, result.savedMetadata?.status)
            assertSame(failure, result.error)
            assertNull(result.status)
            coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
        }
    }

    @Test
    fun providerFailureReloadsSettlementAcceptedByAnotherObserver() = runTest {
        val earlier = mockk<TransactionSwapMetadata>()
        val accepted = mockk<TransactionSwapMetadata> { every { status } returns SwapStatus.SUCCESS }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata("deposit") } returns earlier
            coEvery { reloadSwapMetadata(earlier) } returns accepted
        }
        val failure = SwapAssetsUnavailableException()
        val provider = mockk<SwapRepository> { coEvery { checkSwapStatus(earlier) } throws failure }

        val result = GetSwapStatusUseCase(metadata, provider)("deposit")

        assertSame(accepted, result.savedMetadata)
        assertSame(failure, result.error)
        assertNull(result.status)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun savedDetailsAreAvailableBeforeTheProviderFinishesAndCancellationStopsPolling() = runTest {
        val saved = mockk<TransactionSwapMetadata>()
        val metadata = mockk<MetadataRepository> { coEvery { getSwapMetadata("deposit") } returns saved }
        val delayed = CompletableDeferred<SwapQuoteStatus>()
        val repository = mockk<SwapRepository> { coEvery { checkSwapStatus(saved) } coAnswers { delayed.await() } }
        val states = mutableListOf<SwapQuoteStatusData>()
        val observer = backgroundScope.launch {
            GetSwapStatusUseCase(metadata, repository).observe("deposit").collect { states.add(it) }
        }
        runCurrent()
        assertSame(saved, states.last().savedMetadata)
        assertEquals(true, states.last().isLoading)
        observer.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { repository.checkSwapStatus(saved) }
    }

    @Test
    fun surfacesErrorAndSkipsMetadataWhenStatusLookupFails() =
        runTest {
            val saved = mockk<TransactionSwapMetadata>()
            val metadataRepository =
                mockk<MetadataRepository>(relaxed = true) {
                    coEvery { getSwapMetadata("deposit") } returns saved
                    coEvery { reloadSwapMetadata(saved) } returns null
                }
            val failure = SwapAssetsUnavailableException()
            val swapRepository =
                mockk<SwapRepository> {
                    coEvery { checkSwapStatus(any()) } throws failure
                }

            val result = GetSwapStatusUseCase(metadataRepository, swapRepository).invoke("deposit")

            assertFalse(result.isLoading)
            assertEquals(failure, result.error)
            assertNull(result.status)
            coVerify(exactly = 0) { metadataRepository.updateSwap(any(), any()) }
        }

    @Test
    fun surfacesErrorAndSkipsStatusLookupWhenNoStoredMetadata() =
        runTest {
            val metadataRepository =
                mockk<MetadataRepository>(relaxed = true) {
                    coEvery { getSwapMetadata("deposit") } returns null
                }
            val swapRepository = mockk<SwapRepository>()

            val result = GetSwapStatusUseCase(metadataRepository, swapRepository).invoke("deposit")

            assertFalse(result.isLoading)
            assertIs<IllegalStateException>(result.error)
            assertNull(result.status)
            coVerify(exactly = 0) { swapRepository.checkSwapStatus(any()) }
            coVerify(exactly = 0) { metadataRepository.updateSwap(any(), any()) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restartResumesSavedReferenceAndRefundStopsPolling() =
        runTest {
            val saved = mockk<TransactionSwapMetadata>()
            val metadataRepository =
                mockk<MetadataRepository>(relaxed = true) {
                    coEvery { getSwapMetadata("deposit") } returns saved
                    coEvery { updateSwap(saved, any()) } returns saved
                }
            val pending = swapStatusResult(swapStatus = SwapStatus.PENDING)
            val processing = swapStatusResult(swapStatus = SwapStatus.PROCESSING)
            val refunded = swapStatusResult(swapStatus = SwapStatus.REFUNDED)
            val swapRepository =
                mockk<SwapRepository> {
                    coEvery { checkSwapStatus(saved) } returnsMany listOf(pending, processing, refunded)
                }
            val useCase = GetSwapStatusUseCase(metadataRepository, swapRepository)
            val firstObserver = backgroundScope.launch { useCase.observe("deposit").collect() }
            runCurrent()
            coVerify(exactly = 1) { swapRepository.checkSwapStatus(saved) }
            firstObserver.cancel()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            coVerify(exactly = 1) { swapRepository.checkSwapStatus(saved) }

            // A fresh observer reloads the saved route and stops requesting status after refund.
            val resumed = mutableListOf<SwapQuoteStatusData>()
            backgroundScope.launch { useCase.observe("deposit").collect { resumed.add(it) } }
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(SwapStatus.REFUNDED, resumed.last().status?.status)
            assertNull(resumed.last().error)
            coVerify(exactly = 2) { metadataRepository.getSwapMetadata("deposit") }
            coVerify(exactly = 3) { swapRepository.checkSwapStatus(saved) }
            coVerify(exactly = 1) { metadataRepository.updateSwap(saved, refunded) }
            advanceTimeBy(120_000)
            runCurrent()
            coVerify(exactly = 3) { swapRepository.checkSwapStatus(saved) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retriesTransientStatusFailureAndAcceptsLateSettlement() = runTest {
        val saved = mockk<TransactionSwapMetadata>()
        val metadata = mockk<MetadataRepository>(relaxed = true) {
            coEvery { getSwapMetadata("deposit") } returns saved
            coEvery { updateSwap(saved, any()) } returns saved
            coEvery { reloadSwapMetadata(saved) } returns saved
        }
        val pending = swapStatusResult(swapStatus = SwapStatus.PENDING)
        val success = swapStatusResult(swapStatus = SwapStatus.SUCCESS)
        var attempts = 0
        val repository = mockk<SwapRepository> {
            coEvery { checkSwapStatus(saved) } answers {
                when (++attempts) {
                    1 -> pending
                    2 -> throw SwapAssetsUnavailableException()
                    else -> success
                }
            }
        }
        val states = mutableListOf<SwapQuoteStatusData>()
        backgroundScope.launch {
            GetSwapStatusUseCase(metadata, repository).observe("deposit").collect { states.add(it) }
        }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(pending, states.last().status)
        assertIs<SwapAssetsUnavailableException>(states.last().error)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(success, states.last().status)
        assertNull(states.last().error)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, attempts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun incompleteFundingGuidanceClosesOnFailureAndExpiresDuringAnUnchangedPoll() = runTest {
        val saved = mockk<TransactionSwapMetadata>()
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata("deposit") } returns saved
            coEvery { updateSwap(saved, any()) } returns saved
            coEvery { reloadSwapMetadata(saved) } returns saved
        }
        val start = Instant.parse("2026-11-24T00:00:00Z")
        var wallClockOffset = 0.milliseconds
        val clock = object : Clock {
            override fun now() = start + testScheduler.currentTime.milliseconds + wallClockOffset
        }
        val incomplete = swapStatusResult(swapStatus = SwapStatus.INCOMPLETE_DEPOSIT)
        every { incomplete.deadline } returns start + 95_000.milliseconds
        val stalledPoll = CompletableDeferred<SwapQuoteStatus>()
        var attempts = 0
        val provider = mockk<SwapRepository> {
            coEvery { checkSwapStatus(saved) } coAnswers {
                when (++attempts) {
                    2 -> throw SwapAssetsUnavailableException()
                    4 -> stalledPoll.await()
                    else -> incomplete
                }
            }
        }
        val states = mutableListOf<SwapQuoteStatusData>()
        backgroundScope.launch {
            GetSwapStatusUseCase(metadata, provider, clock = clock, timeSource = testScheduler.timeSource)
                .observe("deposit").collect { states += it }
        }
        runCurrent()
        assertTrue(states.last().isFundingWindowOpen)

        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(states.last().isFundingWindowOpen)
        assertSame(incomplete, states.last().status)
        assertIs<SwapAssetsUnavailableException>(states.last().error)

        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(states.last().isFundingWindowOpen)
        assertEquals(4, attempts)

        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(states.last().isFundingWindowOpen)
        assertSame(incomplete, states.last().status)
        wallClockOffset = (-60_000).milliseconds
        stalledPoll.complete(incomplete)
        runCurrent()
        assertFalse(states.last().isFundingWindowOpen)
    }

    private fun swapStatusResult(
        origin: SwapAsset = btc,
        destination: SwapAsset = zec,
        swapStatus: SwapStatus = SwapStatus.SUCCESS
    ): SwapQuoteStatus =
        mockk {
            every { originAsset } returns origin
            every { destinationAsset } returns destination
            every { status } returns swapStatus
            every { amountOutFormatted } returns BigDecimal("1.23")
            every { mode } returns SwapMode.EXACT_INPUT
        }
}
