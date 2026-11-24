package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [GetReloadableSwapQuoteUseCase] wraps [GetSwapStatusUseCase.observe] into a [SwapData] that also
 * carries a [ReloadHandle]; calling `requestReload()` re-subscribes to the status flow, and identical
 * consecutive emissions are de-duplicated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetReloadableSwapQuoteUseCaseTest {
    private val depositAddress = "deposit"

    @Test
    fun wrapsSwapStatusIntoSwapData() =
        runTest {
            val statusData = SwapQuoteStatusData(isLoading = false)
            val getSwapStatus = mockk<GetSwapStatusUseCase> { every { observe(any()) } returns flowOf(statusData) }

            val result = GetReloadableSwapQuoteUseCase(getSwapStatus).observe(depositAddress).first()

            assertEquals(statusData, result.data)
            assertEquals(statusData.status, result.status)
            assertEquals(statusData.isLoading, result.isLoading)
            assertEquals(statusData.error, result.error)
            verify { getSwapStatus.observe(depositAddress) }
        }

    @Test
    fun requestReloadReSubscribesToTheStatusFlow() =
        runTest {
            val first = SwapQuoteStatusData(isLoading = true)
            val second = SwapQuoteStatusData(isLoading = false)
            val getSwapStatus =
                mockk<GetSwapStatusUseCase> {
                    every { observe(any()) } returnsMany listOf(flowOf(first), flowOf(second))
                }

            val useCase = GetReloadableSwapQuoteUseCase(getSwapStatus)
            val emissions = mutableListOf<SwapData>()
            val job = launch { useCase.observe(depositAddress).collect { emissions += it } }
            advanceUntilIdle()

            emissions.first().handle.requestReload()
            advanceUntilIdle()

            verify(exactly = 2) { getSwapStatus.observe(depositAddress) }
            assertEquals(listOf(first, second), emissions.map { it.data })
            job.cancelAndJoin()
        }

    @Test
    fun deduplicatesConsecutiveIdenticalEmissions() =
        runTest {
            val statusData = SwapQuoteStatusData(isLoading = false)
            val getSwapStatus =
                mockk<GetSwapStatusUseCase> { every { observe(any()) } returns flowOf(statusData, statusData) }

            val useCase = GetReloadableSwapQuoteUseCase(getSwapStatus)
            val emissions = mutableListOf<SwapData>()
            val job = launch { useCase.observe(depositAddress).collect { emissions += it } }
            advanceUntilIdle()

            assertEquals(1, emissions.size)
            job.cancelAndJoin()
        }

    @Test
    fun acceptedObservationReplacesOlderLiveFieldsWhileTheProviderHasNotResponded() = runTest {
        val earlier = TransactionSwapMetadata(
            depositAddress, "near", Zatoshi(0), BigDecimal.ZERO, Instant.EPOCH,
            SwapAssetTestFixture.simpleAsset(), SwapAssetTestFixture.zecSimpleAsset(), SwapMode.FLEX_INPUT,
            SwapStatus.PROCESSING, BigDecimal.ONE, ownerAccount = EditAccountFixtures.zodl(), walletWriteToken = 0,
            statusUpdatedAt = Instant.ofEpochSecond(1),
        )
        val live = mockk<SwapQuoteStatus>(relaxed = true)
        val refresh = MutableStateFlow(SwapQuoteStatusData(live, isLoading = false, savedMetadata = earlier))
        val accepted = MutableStateFlow(listOf(earlier))
        val getStatus = mockk<GetSwapStatusUseCase> { every { observe(depositAddress) } returns refresh }
        val metadata = mockk<MetadataRepository> { every { observeSwapMetadata() } returns accepted }
        val emissions = mutableListOf<SwapData>()
        backgroundScope.launch {
            GetReloadableSwapQuoteUseCase(getStatus, metadata).observe(depositAddress).collect { emissions += it }
        }
        runCurrent()
        assertEquals(live, emissions.last().status)

        val newer = earlier.copy(statusUpdatedAt = Instant.ofEpochSecond(2))
        accepted.value = listOf(newer.copy(ownerAccount = EditAccountFixtures.keystone()))
        runCurrent()
        assertEquals(earlier, emissions.last().savedMetadata)
        assertEquals(live, emissions.last().status)

        accepted.value = listOf(newer)
        runCurrent()
        assertEquals(newer, emissions.last().savedMetadata)
        assertNull(emissions.last().status)
    }
}
