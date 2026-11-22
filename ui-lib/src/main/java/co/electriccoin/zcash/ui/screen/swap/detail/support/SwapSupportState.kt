package co.electriccoin.zcash.ui.screen.swap.detail.support

import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.StringResource

data class SwapSupportState(
    val title: StringResource,
    val message: StringResource,
    val reportIssueButton: ButtonState,
    override val onBack: () -> Unit,
    val shareQuoteButton: ButtonState? = null,
) : ModalBottomSheetState
