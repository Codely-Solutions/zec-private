package co.electriccoin.zcash.ui.screen.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.reviewtransaction.MessageState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionView

@Composable
internal fun UnavailableWalletAction(
    screenTitle: StringResource,
    title: StringResource,
    message: StringResource,
    action: ButtonState,
) {
    BackHandler(onBack = action.onClick)
    ReviewTransactionView(
        ReviewTransactionState(
            title = screenTitle,
            items = listOf(MessageState(title, message)),
            primaryButton = action,
            onBack = action.onClick,
        )
    )
}
