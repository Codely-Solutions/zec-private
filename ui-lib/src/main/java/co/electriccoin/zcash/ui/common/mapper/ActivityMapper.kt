package co.electriccoin.zcash.ui.common.mapper

import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PAYMENT_SHORTFALL
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.model.ZecSimpleSwapAsset
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.ActivityData
import co.electriccoin.zcash.ui.common.usecase.isUnread
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityState
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ActivityMapper {
    fun createTransactionState(
        data: ActivityData,
        restoreTimestamp: Instant?,
        onTransactionClick: (Transaction) -> Unit,
        onSwapClick: (TransactionSwapMetadata) -> Unit,
        onDisplayed: (ActivityData) -> Unit
    ): ActivityState =
        when (data) {
            is ActivityData.BySwap -> {
                ActivityState(
                    key = data.swap.depositAddress,
                    bigIcon = getSwapBigIcon(data),
                    smallIcon = null,
                    title = getSwapTitle(data),
                    subtitle = getSubtitle(data.swap.lastUpdated),
                    isShielded = false,
                    value = getSwapValue(data),
                    onClick = { onSwapClick(data.swap) },
                    isUnread = false,
                    onDisplayed = { onDisplayed(data) }
                )
            }

            is ActivityData.ByTransaction -> {
                ActivityState(
                    key = data.transaction.id.txIdString(),
                    bigIcon = getTransactionBigIcon(data),
                    smallIcon = null,
                    title = getTransactionTitle(data),
                    subtitle = getSubtitle(data.transaction.timestamp),
                    isShielded = isTransactionShielded(data),
                    value = getTransactionValue(data),
                    onClick = { onTransactionClick(data.transaction) },
                    isUnread = data.isUnread(restoreTimestamp),
                    onDisplayed = { onDisplayed(data) }
                )
            }
        }

    private fun getSwapValue(data: ActivityData.BySwap): StyledStringResource {
        val destination = data.swap.destination
        val ticker = if (destination is ZecSimpleSwapAsset) CURRENCY_TICKER else destination.tokenTicker
        val amount = stringResByCurrencyNumber(
            data.swap.amountOutFormatted,
            ticker,
            tickerLocation = TickerLocation.AFTER,
        )
        val label = when {
            data.swap.executedAmountOut != null -> null
            data.swap.quotedAmountOut != null -> R.string.private_activity_quoted_amount
            else -> R.string.private_activity_saved_amount
        }
        return (label?.let { stringRes(it, amount) } ?: amount).withStyle(
            StyledStringStyle(
                color =
                    when (data.swap.status) {
                        INCOMPLETE_DEPOSIT, PROCESSING, PENDING, SUCCESS -> StringResourceColor.PRIMARY
                        EXPIRED, REFUNDED, FAILED, PAYMENT_SHORTFALL -> StringResourceColor.NEGATIVE
                    }
            )
        )
    }

    private fun getSwapTitle(data: ActivityData.BySwap): StringResource =
        if (data.swap.mode != FLEX_INPUT) {
            stringRes(getUnmatchedFundingTitle(data))
        } else stringRes(
            when (data.swap.status) {
                INCOMPLETE_DEPOSIT, PROCESSING, PENDING -> R.string.transaction_history_swapping
                SUCCESS -> R.string.swapStatus_swapped
                PAYMENT_SHORTFALL -> R.string.private_payment_shortfall
                REFUNDED -> R.string.swapStatus_swapRefunded
                FAILED -> R.string.swapStatus_swapFailed
                EXPIRED -> R.string.swapAndPay_expiredTitle
            }
        )

    private fun getSwapBigIcon(data: ActivityData.BySwap): Int =
        if (data.swap.mode != FLEX_INPUT) {
            when (data.swap.status) {
                INCOMPLETE_DEPOSIT, PROCESSING, PENDING -> R.drawable.ic_transaction_send_pending
                SUCCESS -> R.drawable.ic_transaction_sent
                EXPIRED, REFUNDED, FAILED, PAYMENT_SHORTFALL -> R.drawable.ic_transaction_send_failed
            }
        } else when (data.swap.status) {
            INCOMPLETE_DEPOSIT, PROCESSING, PENDING -> R.drawable.ic_transaction_receive_pending
            SUCCESS -> R.drawable.ic_transaction_received
            EXPIRED, REFUNDED, FAILED, PAYMENT_SHORTFALL -> R.drawable.ic_transaction_receive_failed
        }


    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun getTransactionBigIcon(data: ActivityData.ByTransaction) =
        when (val transaction = data.transaction) {
            is ReceiveTransaction.Success -> {
                R.drawable.ic_transaction_received
            }

            is ReceiveTransaction.Pending -> {
                R.drawable.ic_transaction_receive_pending
            }

            is ReceiveTransaction.Failed -> {
                R.drawable.ic_transaction_receive_failed
            }

            is ShieldTransaction.Success -> {
                R.drawable.ic_transaction_shielded
            }

            is ShieldTransaction.Pending -> {
                R.drawable.ic_transaction_shield_pending
            }

            is ShieldTransaction.Failed -> {
                R.drawable.ic_transaction_shield_failed
            }

            is SendTransaction -> {
                if (data.metadata.swapMetadata == null) {
                    when (transaction.overview.zip318Kind) {
                        Zip318Kind.PREPARATION, Zip318Kind.TRANSFER -> {
                            when (transaction) {
                                is SendTransaction.Success -> R.drawable.ic_transaction_migration
                                is SendTransaction.Pending -> R.drawable.ic_transaction_migration_pending
                                is SendTransaction.Failed -> R.drawable.ic_transaction_migration_failed
                            }
                        }

                        Zip318Kind.NOT_CLASSIFIED, Zip318Kind.NONCONFORMING -> {
                            when (transaction) {
                                is SendTransaction.Success -> R.drawable.ic_transaction_sent
                                is SendTransaction.Pending -> R.drawable.ic_transaction_send_pending
                                is SendTransaction.Failed -> R.drawable.ic_transaction_send_failed
                            }
                        }
                    }
                } else {
                    val status = data.metadata.swapMetadata.outgoingStatus(transaction)
                    when (data.metadata.swapMetadata.mode) {
                        EXACT_INPUT, FLEX_INPUT -> {
                            when (status) {
                                INCOMPLETE_DEPOSIT, PROCESSING, PENDING -> R.drawable.ic_transaction_send_pending
                                SUCCESS -> R.drawable.ic_transaction_sent
                                EXPIRED, REFUNDED, FAILED, PAYMENT_SHORTFALL -> R.drawable.ic_transaction_send_failed
                            }
                        }

                        EXACT_OUTPUT -> {
                            when (status) {
                                INCOMPLETE_DEPOSIT, PROCESSING, PENDING -> R.drawable.ic_transaction_paying
                                SUCCESS -> R.drawable.ic_transaction_paid
                                EXPIRED, REFUNDED, FAILED, PAYMENT_SHORTFALL -> R.drawable.ic_transaction_pay_failed
                            }
                        }
                    }
                }
            }
        }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun getTransactionTitle(data: ActivityData.ByTransaction) =
        when (val transaction = data.transaction) {
            is ReceiveTransaction.Success -> {
                stringRes(R.string.transaction_received)
            }

            is ReceiveTransaction.Pending -> {
                stringRes(R.string.transaction_history_receiving)
            }

            is ReceiveTransaction.Failed -> {
                stringRes(R.string.transaction_history_receiving_failed)
            }

            is ShieldTransaction.Success -> {
                stringRes(R.string.transaction_shieldedFunds)
            }

            is ShieldTransaction.Pending -> {
                stringRes(R.string.transaction_history_shielding)
            }

            is ShieldTransaction.Failed -> {
                stringRes(R.string.transaction_failedShieldedFunds)
            }

            is SendTransaction -> {
                if (data.metadata.swapMetadata == null) {
                    when (transaction.overview.zip318Kind) {
                        Zip318Kind.PREPARATION -> {
                            when (transaction) {
                                is SendTransaction.Success -> stringRes(R.string.transaction_noteSplit)
                                is SendTransaction.Pending -> stringRes(R.string.transaction_history_noteSplitting)
                                is SendTransaction.Failed -> stringRes(R.string.transaction_history_noteSplitFailed)
                            }
                        }

                        Zip318Kind.TRANSFER -> {
                            when (transaction) {
                                is SendTransaction.Success -> stringRes(R.string.transaction_migrated)
                                is SendTransaction.Pending -> stringRes(R.string.transaction_history_migrating)
                                is SendTransaction.Failed -> stringRes(R.string.transaction_migrationFailed)
                            }
                        }

                        Zip318Kind.NOT_CLASSIFIED, Zip318Kind.NONCONFORMING -> {
                            when (transaction) {
                                is SendTransaction.Success -> stringRes(R.string.transaction_sent)
                                is SendTransaction.Pending -> stringRes(R.string.transaction_history_sending)
                                is SendTransaction.Failed -> stringRes(R.string.transaction_history_sending_failed)
                            }
                        }
                    }
                } else {
                    val status = data.metadata.swapMetadata.outgoingStatus(transaction)
                    when (data.metadata.swapMetadata.mode) {
                        EXACT_INPUT, FLEX_INPUT -> {
                            when (status) {
                                INCOMPLETE_DEPOSIT,
                                PROCESSING -> stringRes(R.string.transaction_history_swapping)

                                PENDING -> stringRes(
                                    if (data.metadata.swapMetadata.fundingNotSubmitted) {
                                        R.string.private_swap_funding_not_sent
                                    } else {
                                        R.string.transaction_history_swapping
                                    }
                                )

                                SUCCESS -> stringRes(R.string.swapStatus_swapped)
                                PAYMENT_SHORTFALL -> stringRes(R.string.private_payment_shortfall)

                                REFUNDED -> stringRes(R.string.swapStatus_swapRefunded)

                                FAILED -> stringRes(R.string.swapStatus_swapFailed)

                                EXPIRED -> stringRes(R.string.swapAndPay_expiredTitle)
                            }
                        }

                        EXACT_OUTPUT -> {
                            when (status) {
                                INCOMPLETE_DEPOSIT,
                                PROCESSING -> stringRes(R.string.transaction_history_paying)

                                PENDING -> stringRes(
                                    if (data.metadata.swapMetadata.fundingNotSubmitted) {
                                        R.string.private_swap_funding_not_sent
                                    } else {
                                        R.string.transaction_history_paying
                                    }
                                )

                                SUCCESS -> stringRes(R.string.swapStatus_paid)
                                PAYMENT_SHORTFALL -> stringRes(R.string.private_payment_shortfall)

                                REFUNDED -> stringRes(R.string.swapStatus_paymentRefunded)

                                FAILED -> stringRes(R.string.swapStatus_paymentFailed)

                                EXPIRED -> stringRes(R.string.swapStatus_paymentExpired)
                            }
                        }
                    }
                }
            }
        }

    private fun getSubtitle(timestamp: Instant?): StringResource? {
        if (timestamp == null) return null
        val transactionDate = timestamp.atZone(ZoneId.systemDefault())
        val daysBetween = ChronoUnit.DAYS.between(transactionDate.toLocalDate(), LocalDate.now())
        return when {
            LocalDate.now() == transactionDate.toLocalDate() -> {
                stringRes(R.string.filter_today)
            }

            LocalDate.now().minusDays(1) == transactionDate.toLocalDate() -> {
                stringRes(R.string.filter_yesterday)
            }

            daysBetween < MONTH_THRESHOLD -> {
                stringRes(R.string.filter_daysAgo, daysBetween.toString())
            }

            else -> {
                stringResByDateTime(zonedDateTime = transactionDate, useFullFormat = false)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun isTransactionShielded(data: ActivityData.ByTransaction) =
        data.transaction
            .transactionOutputs
            .none { output -> output.pool == TransactionPool.TRANSPARENT } &&
            data.transaction !is ShieldTransaction

    @Suppress("CyclomaticComplexMethod")
    private fun getTransactionValue(data: ActivityData.ByTransaction): StyledStringResource {
        val stringRes =
            when (data.transaction) {
                is SendTransaction.Success,
                is SendTransaction.Failed,
                is SendTransaction.Pending -> {
                    if (data.transaction.overview.zip318Kind == Zip318Kind.TRANSFER ||
                        data.transaction.overview.zip318Kind == Zip318Kind.PREPARATION
                    ) {
                        stringRes(data.transaction.amount)
                    } else {
                        stringRes("- ") + stringRes(data.transaction.amount)
                    }
                }

                is ShieldTransaction.Success,
                is ShieldTransaction.Failed,
                is ShieldTransaction.Pending,
                is ReceiveTransaction.Success,
                is ReceiveTransaction.Failed,
                is ReceiveTransaction.Pending -> {
                    stringRes(data.transaction.amount)
                }
            }

        val color =
            when (data.transaction) {
                is ReceiveTransaction.Success -> {
                    StringResourceColor.POSITIVE
                }

                is ReceiveTransaction.Pending -> {
                    StringResourceColor.PRIMARY
                }

                is ReceiveTransaction.Failed -> {
                    StringResourceColor.NEGATIVE
                }

                is ShieldTransaction.Success -> {
                    StringResourceColor.PRIMARY
                }

                is ShieldTransaction.Pending -> {
                    StringResourceColor.PRIMARY
                }

                is ShieldTransaction.Failed -> {
                    StringResourceColor.NEGATIVE
                }

                is SendTransaction -> {
                    when {
                        data.metadata.swapMetadata == null -> {
                            when (data.transaction) {
                                is SendTransaction.Success,
                                is SendTransaction.Pending -> StringResourceColor.PRIMARY

                                is SendTransaction.Failed -> StringResourceColor.NEGATIVE
                            }
                        }

                        else -> {
                            when (data.metadata.swapMetadata.outgoingStatus(data.transaction)) {
                                INCOMPLETE_DEPOSIT,
                                PENDING,
                                SUCCESS,
                                PROCESSING -> StringResourceColor.PRIMARY

                                EXPIRED,
                                REFUNDED,
                                PAYMENT_SHORTFALL,
                                FAILED -> StringResourceColor.NEGATIVE
                            }
                        }
                    }
                }
            }

        return stringRes.withStyle(StyledStringStyle(color = color))
    }
}

private const val MONTH_THRESHOLD = 30

private fun getUnmatchedFundingTitle(data: ActivityData.BySwap): Int {
    fun byMode(payment: Int, swap: Int) = if (data.swap.mode == EXACT_OUTPUT) payment else swap

    return when (data.swap.status) {
        PENDING -> if (data.swap.fundingNotSubmitted) R.string.private_swap_funding_not_sent
            else R.string.private_swap_funding_unconfirmed
        INCOMPLETE_DEPOSIT -> {
            byMode(R.string.swapStatus_paymentIncomplete, R.string.swapStatus_swapIncomplete)
        }
        PROCESSING -> byMode(R.string.swapStatus_paying, R.string.transaction_history_swapping)
        SUCCESS -> byMode(R.string.swapStatus_paid, R.string.swapStatus_swapped)
        PAYMENT_SHORTFALL -> R.string.private_payment_shortfall
        REFUNDED -> byMode(R.string.swapStatus_paymentRefunded, R.string.swapStatus_swapRefunded)
        FAILED -> byMode(R.string.swapStatus_paymentFailed, R.string.swapStatus_swapFailed)
        EXPIRED -> R.string.swapAndPay_expiredTitle
    }
}
