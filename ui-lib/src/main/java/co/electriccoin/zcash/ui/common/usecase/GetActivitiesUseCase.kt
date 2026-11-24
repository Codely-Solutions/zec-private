package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.design.util.combineToFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class GetActivitiesUseCase(
    private val transactionRepository: TransactionRepository,
    private val metadataRepository: MetadataRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe() =
        combine(observeTransactions(), metadataRepository.observeSwapMetadata()) { transactions, swaps ->
            if (transactions == null || swaps == null) {
                null
            } else {
                val reachedWalletHistory = transactions.mapNotNull { entry ->
                    entry.metadata.swapMetadata?.depositAddress?.let { entry.transaction.accountUuid to it }
                }.toSet()
                val standalone = swaps.filter { swap ->
                    val owner = swap.ownerAccount?.sdkAccount?.accountUuid
                    swap.mode == SwapMode.FLEX_INPUT || (owner to swap.depositAddress) !in reachedWalletHistory
                }
                transactions
                    .sortedWith(
                        compareByDescending<ActivityData.ByTransaction> { it.timestamp }
                            .thenByDescending { it.transaction.id.txIdString() }
                    ) + standalone.map { ActivityData.BySwap(it) }
            }
        }.map {
            val endOfDay =
                LocalDateTime
                    .of(LocalDate.now(), LocalTime.MAX)
                    .toInstant(ZoneOffset.UTC)

            it?.sortedByDescending { activity ->
                when (activity) {
                    is ActivityData.ByTransaction -> activity.transaction.timestamp ?: endOfDay
                    is ActivityData.BySwap -> activity.swap.lastUpdated
                }
            }
        }.flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTransactions(): Flow<List<ActivityData.ByTransaction>?> =
        transactionRepository.transactions
            .flatMapLatest { transactions ->
                transactions
                    ?.map {
                        metadataRepository
                            .observeTransactionMetadata(it)
                            .mapLatest { metadata ->
                                ActivityData.ByTransaction(transaction = it, metadata = metadata)
                            }
                    }?.combineToFlow() ?: flowOf(null)
            }
}

sealed interface ActivityData {
    val timestamp: Instant?

    data class ByTransaction(
        val transaction: Transaction,
        val metadata: TransactionMetadata
    ) : ActivityData {
        override val timestamp = transaction.timestamp
    }

    data class BySwap(
        val swap: TransactionSwapMetadata
    ) : ActivityData {
        override val timestamp = swap.lastUpdated
    }
}
