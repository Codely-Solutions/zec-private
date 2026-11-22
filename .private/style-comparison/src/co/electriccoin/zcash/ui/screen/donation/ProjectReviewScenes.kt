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
import co.electriccoin.zcash.ui.screen.contact.ABContactView
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiState
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiView

/** Synthetic failure and recovery layouts; every storage and destructive callback is inert. */
@Composable
internal fun ProjectReviewScene(screen: String): Boolean {
    when (screen) {
        "gift-reviewed-account", "gift-unknown-account" -> co.electriccoin.zcash.ui.screen.redeemgift.RedeemGiftView(
            co.electriccoin.zcash.ui.screen.redeemgift.RedeemGiftState.ready(
                redeemable = cash.z.ecc.android.sdk.model.Zatoshi(10_000_000),
                ticker = stringRes("ZEC"),
                message = "Synthetic gift",
                walletName = stringRes("Reviewed wallet").takeIf { screen == "gift-reviewed-account" },
                onRedeem = {},
                onBack = {},
            )
        )
        "setup-incomplete", "setup-failed" -> co.electriccoin.zcash.ui.screen.onboarding.view.Onboarding(
            onImportWallet = {},
            onCreateWallet = {},
            onContinueWallet = {},
            onRetrySetup = {},
            initialization = if (screen == "setup-failed") {
                co.electriccoin.zcash.ui.common.repository.WalletInitializationState.FAILED
            } else co.electriccoin.zcash.ui.common.repository.WalletInitializationState.INCOMPLETE,
        )
        "restore-tor-enabled", "restore-tor-disabled" -> co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorView(
            co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorState(
                checkbox = CheckboxState(
                    title = stringRes(R.string.torSettingsSheet_title),
                    subtitle = stringRes(R.string.torSettingsSheet_desc),
                    isChecked = screen == "restore-tor-enabled",
                    onClick = {},
                ),
                primary = ButtonState(stringRes(R.string.root_existingWallet_restore), onClick = {}),
                secondary = ButtonState(
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_cancel), onClick = {},
                ),
                onBack = {},
            )
        )
        "app-locked" -> AppAccessBoundary(
            session = androidx.compose.runtime.remember { AppAccessSession() },
            lockedContent = {
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
        "reset-pending", "reset-retention", "reset-retention-off" -> {
            val pending = screen == "reset-pending"
            ResetZashiView(
                ResetZashiState(
                    onBack = {},
                    checkboxState = CheckboxState(
                        title = stringRes(R.string.deleteWallet_metadataWarn1),
                        subtitle = stringRes(R.string.deleteWallet_metadataWarn2),
                        isChecked = screen == "reset-retention",
                        onClick = {},
                    ),
                    buttonState = ButtonState(
                        stringRes(
                            if (pending) R.string.wallet_initialization_retry
                            else co.electriccoin.zcash.ui.design.R.string.general_confirm
                        ),
                        onClick = {},
                    ),
                    confirmationDialog = null,
                    isPending = pending,
                )
            )
        }
        else -> return false
    }
    return true
}
