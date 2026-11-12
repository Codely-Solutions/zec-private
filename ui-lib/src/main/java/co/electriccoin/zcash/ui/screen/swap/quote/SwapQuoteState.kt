package co.electriccoin.zcash.ui.screen.swap.quote

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.component.SwapTokenAmountState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource

internal sealed interface SwapQuoteState : ModalBottomSheetState {
    data class Success(
        val title: StringResource,
        val rotateIcon: Boolean,
        val from: SwapTokenAmountState,
        val to: SwapTokenAmountState,
        val items: List<SwapQuoteInfoItem>,
        val amount: SwapQuoteInfoItem,
        val primaryButton: ButtonState,
        val infoText: StringResource?,
        override val onBack: () -> Unit,
        val priceCheck: PriceCheckNoticeState? = null,
        val minimumDeposit: SwapQuoteInfoItem? = null,
    ) : SwapQuoteState

    data class Error(
        val icon: ImageResource,
        val title: StringResource,
        val subtitle: StringResource,
        val negativeButton: ButtonState,
        val positiveButton: ButtonState,
        override val onBack: () -> Unit,
    ) : SwapQuoteState
}

data class SwapQuoteInfoItem(
    val description: StringResource,
    val title: StyledStringResource,
    val subtitle: StyledStringResource? = null,
    val layout: SwapQuoteInfoLayout = SwapQuoteInfoLayout.INLINE,
    val help: StringResource? = null,
)

enum class SwapQuoteInfoLayout {
    INLINE,
    STACKED
}

internal data class PriceCheckNoticeState(
    val label: StringResource,
    val explanation: StringResource? = null,
    val isWarning: Boolean = false,
    val showInline: Boolean = true,
    val detailItems: List<SwapQuoteInfoItem> = emptyList(),
)
