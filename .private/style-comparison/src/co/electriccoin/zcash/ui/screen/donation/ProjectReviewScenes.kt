package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.contact.ABContactState
import co.electriccoin.zcash.ui.screen.authentication.AppAccessBoundary
import co.electriccoin.zcash.ui.screen.authentication.view.AppAccessAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.WelcomeAnimationAutostart
import co.electriccoin.zcash.ui.screen.contact.ABContactView
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiState
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiView

/** Synthetic failure and recovery layouts; every storage and destructive callback is inert. */
@Composable
internal fun ProjectReviewScene(screen: String): Boolean {
    when (screen) {
        "app-locked" -> AppAccessBoundary(
            session = androidx.compose.runtime.remember { AppAccessSession() },
            lockedContent = {
                WelcomeAnimationAutostart(showAuthLogo = true, onRetry = {})
                AppAccessAuthentication(onRetry = {}, showAuthLogo = true, welcomeAnimVisibility = false)
            },
            walletContent = {},
        )
        "contact-save-error", "contact-saving" -> ABContactView(
            ABContactState(
                info = null,
                title = stringRes("New contact"),
                walletAddress = TextFieldState(stringRes("Synthetic recipient address")) {},
                contactName = TextFieldState(stringRes("Example contact")) {},
                chain = null,
                negativeButton = ButtonState(stringRes("Cancel"), onClick = {}),
                positiveButton = ButtonState(stringRes("Save"), isLoading = screen == "contact-saving", onClick = {}),
                onBack = {},
                operationError = stringRes(R.string.contact_error_saveFailed).takeIf { screen == "contact-save-error" },
            )
        )
        "reset-pending" -> ResetZashiView(
            ResetZashiState(
                onBack = {},
                checkboxState = CheckboxState(title = stringRes("Keep local files"), isChecked = false, onClick = {}),
                buttonState = ButtonState(stringRes(R.string.wallet_initialization_retry), onClick = {}),
                confirmationDialog = null,
                isPending = true,
            )
        )
        else -> return false
    }
    return true
}
