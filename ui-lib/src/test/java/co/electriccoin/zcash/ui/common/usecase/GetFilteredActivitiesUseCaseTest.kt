package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.repository.TransactionFilterRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GetFilteredActivitiesUseCaseTest {
    private val activity =
        ActivityData.BySwap(
            mockk<TransactionSwapMetadata> {
                every { lastUpdated } returns Instant.EPOCH
                every { depositAddress } returns "synthetic-deposit"
                every { amountOutFormatted } returns BigDecimal("1.23456789")
                every { totalFeesUsd } returns BigDecimal.ZERO
            }
        )

    @Test
    fun incomingSwapMatchesReceivedAmount() =
        runTest {
            assertEquals(listOf(activity), filteredActivities("1.23456789"))
        }

    @Test
    fun incomingSwapDoesNotMatchUnrelatedUsdFee() =
        runTest {
            assertTrue(filteredActivities("0.00").isEmpty())
        }

    private suspend fun filteredActivities(query: String): List<ActivityData> {
        val getActivities =
            mockk<GetActivitiesUseCase> {
                every { observe() } returns flowOf(listOf(activity))
            }
        val transactionRepository =
            mockk<TransactionRepository> {
                every { observeTransactionsByMemo(any()) } returns flowOf(emptyList())
            }
        val restoreTimestamp =
            mockk<RestoreTimestampDataSource> {
                coEvery { getOrCreate() } returns Instant.EPOCH
            }
        val filters = TransactionFilterRepositoryImpl().apply { applyFulltext(query) }
        return GetFilteredActivitiesUseCase(
            getActivities = getActivities,
            transactionFilterRepository = filters,
            transactionRepository = transactionRepository,
            restoreTimestampDataSource = restoreTimestamp,
            addressBookRepository = mockk(),
            context = RuntimeEnvironment.getApplication(),
        ).observe().filterNotNull().first()
    }
}
