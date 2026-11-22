package co.electriccoin.zcash.ui.screen.transactiondetail.infoitems

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
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
    SubcomposeLayout(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
    ) { constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val label = subcompose("label") {
            Text(
                text = state.title.getValue(),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
        }.single().measure(childConstraints)
        val status = subcompose("status") { StatusValue(state) }.single().measure(childConstraints)
        val gap = 16.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else label.width + gap + status.width
        val stacked = label.width + gap + status.width > width
        val verticalGap = 8.dp.roundToPx()
        val height = if (stacked) label.height + verticalGap + status.height else maxOf(label.height, status.height)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            label.placeRelative(0, if (stacked) 0 else (height - label.height) / 2)
            status.placeRelative(
                width - status.width,
                if (stacked) label.height + verticalGap else (height - status.height) / 2,
            )
        }
    }
}

@Composable
private fun StatusValue(state: TransactionDetailSwapStatusRowState) {
    if (state.status != null) {
        StatusChip(state)
    } else {
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
                    if (state.mode == SWAP_INTO_ZEC) {
                        stringResource(R.string.swapAndPay_status_pendingDeposit)
                    } else {
                        stringResource(R.string.swapAndPay_status_pending)
                    }
                }

                SUCCESS -> {
                    stringResource(R.string.swapAndPay_status_success)
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
) {
    enum class Mode {
        SWAP_FROM_ZEC,
        SWAP_INTO_ZEC
    }
}

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
                    )
            )
        }
    }
