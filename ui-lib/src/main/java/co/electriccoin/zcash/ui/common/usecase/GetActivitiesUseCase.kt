package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val accountDataSource: AccountDataSource,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe() =
        combine(
            accountDataSource.selectedAccount.map { it?.sdkAccount?.accountUuid }.distinctUntilChanged(),
            observeTransactions(),
            metadataRepository.observeSwapMetadata(),
        ) { owner, transactions, swaps ->
            if (owner == null || transactions == null || swaps == null) {
                null
            } else {
                val ownedTransactions = transactions.filter { it.transaction.accountUuid == owner }
                val reachedWalletHistory = ownedTransactions.mapNotNull { entry ->
                    entry.metadata.swapMetadata?.depositAddress
                }.toSet()
                val standalone = swaps.filter { swap ->
                    swap.ownerAccount?.sdkAccount?.accountUuid == owner &&
                        (swap.mode == SwapMode.FLEX_INPUT || swap.depositAddress !in reachedWalletHistory)
                }
                ownedTransactions
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
