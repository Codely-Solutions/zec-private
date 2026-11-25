package co.electriccoin.zcash.ui.screen.transactiondetail.infoitems

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PAYMENT_SHORTFALL
import co.electriccoin.zcash.ui.common.model.settlementStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ShimmerRectangle
import co.electriccoin.zcash.ui.design.component.ZashiBadge
import co.electriccoin.zcash.ui.design.component.ZashiBadgeDefaults
import co.electriccoin.zcash.ui.design.component.heightDp
import co.electriccoin.zcash.ui.design.component.measureTextStyle
import co.electriccoin.zcash.ui.design.component.rememberZashiShimmer
import co.electriccoin.zcash.ui.design.component.widthDp
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState.Mode.SWAP_FROM_ZEC
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC
import com.valentinilk.shimmer.shimmer

@Composable
fun TransactionDetailSwapStatusRow(
    state: TransactionDetailSwapStatusRowState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.padding(end = 16.dp),
            text = state.title.getValue(),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary
        )
        if (state.status != null) {
            StatusChip(state)
        } else if (state.isLoading) {
            Box(
                modifier = Modifier.shimmer(rememberZashiShimmer())
            ) {
                val textSize =
                    measureTextStyle(
                        style = ZcashTheme.extendedTypography.transactionItemStyles.contentMedium,
                        text = stringResource(R.string.swapAndPay_status_success),
                    )

                ShimmerRectangle(
                    width = textSize.size.widthDp + 4.dp + 4.dp + 1.dp,
                    height = textSize.size.heightDp + 4.dp + 4.dp + 1.dp,
                    color = ZashiColors.Surfaces.bgTertiary,
                    shape = CircleShape
                )
            }
        } else {
            Text(
                text = stringResource(R.string.private_swap_status_unavailable),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun StatusChip(state: TransactionDetailSwapStatusRowState) {
    if (state.status == null) return

    ZashiBadge(
        text =
            when (state.status) {
                EXPIRED -> {
                    stringResource(R.string.swapAndPay_status_expired)
                }

                INCOMPLETE_DEPOSIT -> {
                    stringResource(R.string.swapAndPay_status_incompleteDeposit)
                }

                PENDING -> {
                    stringResource(when {
                        state.fundingNotSubmitted -> R.string.private_swap_funding_not_sent
                        state.mode == SWAP_INTO_ZEC -> R.string.swapAndPay_status_pendingDeposit
                        else -> R.string.swapAndPay_status_pending
                    })
                }

                SUCCESS -> {
                    stringResource(R.string.swapAndPay_status_success)
                }

                PAYMENT_SHORTFALL -> {
                    stringResource(R.string.private_payment_shortfall)
                }

                REFUNDED -> {
                    stringResource(R.string.swapAndPay_status_refunded)
                }

                FAILED -> {
                    stringResource(R.string.swapAndPay_status_failed)
                }

                PROCESSING -> {
                    if (state.mode == SWAP_INTO_ZEC) {
                        stringResource(co.electriccoin.zcash.ui.design.R.string.swapAndPay_status_processing)
                    } else {
                        stringResource(R.string.swapAndPay_status_pending)
                    }
                }
            },
        colors =
            when (state.status) {
                PROCESSING -> {
                    ZashiBadgeDefaults.hyperBlueColors()
                }

                INCOMPLETE_DEPOSIT,
                PENDING -> {
                    if (state.mode == SWAP_INTO_ZEC) {
                        ZashiBadgeDefaults.warningColors()
                    } else {
                        ZashiBadgeDefaults.hyperBlueColors()
                    }
                }

                SUCCESS -> {
                    ZashiBadgeDefaults.successColors()
                }

                EXPIRED,
                REFUNDED,
                PAYMENT_SHORTFALL,
                FAILED -> {
                    ZashiBadgeDefaults.errorColors()
                }
            },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    )
}

data class TransactionDetailSwapStatusRowState(
    val title: StringResource,
    val status: SwapStatus?,
    val mode: Mode = SWAP_FROM_ZEC,
    val isLoading: Boolean = false,
    val fundingNotSubmitted: Boolean = false,
) {
    enum class Mode {
        SWAP_FROM_ZEC,
        SWAP_INTO_ZEC
    }
}

internal fun SwapQuoteStatusData.toDetailStatusRow(
    mode: TransactionDetailSwapStatusRowState.Mode = SWAP_FROM_ZEC,
    metadata: TransactionSwapMetadata? = savedMetadata,
    outgoingStatus: SwapStatus? = null,
): TransactionDetailSwapStatusRowState {
    val savedStatus = metadata?.recordedStatus?.let { metadata.status }
        ?: metadata?.legacySuccessfulOutput?.let { SUCCESS }
    val providerStatus = status?.settlementStatus { metadata?.executedAmountOut } ?: savedStatus
    val value = outgoingStatus ?: providerStatus
    val localOutcome = isLocalFundingStatus(value, providerStatus, outgoingStatus, metadata)
    val isSaved = value != null && (status == null || error != null)
    val title = when {
        localOutcome -> R.string.private_swap_funding_status
        isSaved -> R.string.private_swap_saved_status
        else -> R.string.swapAndPay_status
    }
    return TransactionDetailSwapStatusRowState(
        title = stringRes(title),
        status = value,
        mode = mode,
        isLoading = value == null && isLoading && error == null,
        fundingNotSubmitted = metadata?.fundingNotSubmitted == true,
    )
}

private fun isLocalFundingStatus(
    value: SwapStatus?,
    providerStatus: SwapStatus?,
    outgoingStatus: SwapStatus?,
    metadata: TransactionSwapMetadata?,
): Boolean = (value == PENDING && metadata?.fundingNotSubmitted == true) ||
    (outgoingStatus != null && outgoingStatus != providerStatus)

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            TransactionDetailSwapStatusRow(
                state =
                    TransactionDetailSwapStatusRowState(
                        title = stringRes("Title"),
                        status = SUCCESS,
                    )
            )
        }
    }

@PreviewScreens
@Composable
private fun LoadingPreview() =
    ZcashTheme {
        BlankSurface {
            TransactionDetailSwapStatusRow(
                state =
                    TransactionDetailSwapStatusRowState(
                        title = stringRes("Title"),
                        status = null,
                        isLoading = true,
                    )
            )
        }
    }
