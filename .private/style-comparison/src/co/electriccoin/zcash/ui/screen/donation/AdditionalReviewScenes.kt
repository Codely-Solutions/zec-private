package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.design.component.*
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.*
import co.electriccoin.zcash.ui.screen.receive.*
import co.electriccoin.zcash.ui.screen.advancedsettings.*
import co.electriccoin.zcash.ui.screen.walletbackup.*
import co.electriccoin.zcash.ui.screen.transactiondetail.*
import co.electriccoin.zcash.ui.screen.swap.quote.*

/** Synthetic display-only state. Every payment, clipboard and destructive action is inert. */
@Composable
internal fun AdditionalReviewScene(screen: String, onBack: () -> Unit, appBar: ZashiMainTopAppBarState): Boolean {
    when (screen) {
        "receive" -> ReceiveView(
            state = ReceiveState(
                items = listOf(true, false).map { shielded ->
                    ReceiveAddressState(
                        icon = imageRes(R.drawable.ic_zec_round_full), title = stringRes("ZEC Private"),
                        subtitle = styledStringResource(if (shielded) "Shielded address" else "Transparent address"),
                        isExpanded = true,
                        colorMode = if (shielded) ReceiveAddressState.ColorMode.ZASHI else ReceiveAddressState.ColorMode.DEFAULT,
                        infoIconButton = IconButtonState(R.drawable.ic_receive_zashi_shielded_info, onClick = {}),
                        onClick = {}, isShielded = shielded, onCopyClicked = {}, onQrClicked = {}, onRequestClicked = {},
                    )
                }, isLoading = false, onBack = onBack,
            ), appBarState = appBar,
        )
        "settings" -> AdvancedSettings(
            AdvancedSettingsState(
                onBack = onBack,
                items = listOf(
                    ListItemState(title = stringRes(R.string.settings_recoveryPhrase), bigIcon = imageRes(R.drawable.ic_advanced_settings_recovery), onClick = {}),
                    ListItemState(title = stringRes(R.string.settings_exportPrivateData), bigIcon = imageRes(R.drawable.ic_advanced_settings_export), onClick = {}),
                    ListItemState(title = stringRes(R.string.settings_chooseServer), bigIcon = imageRes(R.drawable.ic_advanced_settings_choose_server), onClick = {}),
                    ListItemState(title = stringRes(R.string.currencyConversion_title), bigIcon = imageRes(R.drawable.ic_advanced_settings_currency_conversion), onClick = {}),
                ),
                deleteButton = ButtonState(stringRes(R.string.settings_deleteZashi), onClick = {}),
            )
        )
        "recovery" -> WalletBackupView(
            WalletBackupState(
                seed = SeedTextState((1..24).joinToString(" ") { "sample$it" }, isRevealed = true),
                birthday = SeedSecretState(stringRes(R.string.recoveryPhraseDisplay_birthdayTitle), stringRes("4600000"), true, null, null),
                info = IconButtonState(R.drawable.ic_help, onClick = {}),
                primaryButton = ButtonState(stringRes("Done"), onClick = onBack),
                secondaryButton = null, onBack = onBack,
                copyPhrase = ButtonState(stringRes(R.string.recovery_copy_phrase), onClick = {}),
                copyBirthday = ButtonState(stringRes(R.string.recovery_copy_birthday), onClick = {}),
            )
        )
        "activity-details" -> TransactionDetailView(
            state = TransactionDetailState(
                onBack = onBack, bookmarkButton = IconButtonState(R.drawable.ic_transaction_detail_no_bookmark, onClick = {}),
                header = TransactionDetailHeaderState(stringRes("Received"), stringRes("0.01"), listOf(imageRes(R.drawable.ic_transaction_received))),
                info = ReceiveShieldedStateFixture.new().copy(completedTimestamp = stringRes("8 November 2026, 12:00")), errorFooter = null, infoFooter = null,
                primaryButton = null, secondaryButton = null,
            ), mainAppBarState = appBar,
        )
        "swap-review", "crosspay-review" -> SwapQuoteView(
            SwapQuoteState.Success(
                title = stringRes(if (screen == "crosspay-review") "Pay now" else "Swap"), rotateIcon = screen == "swap-review",
                from = SwapTokenAmountState(amount = stringRes("0.01"), fiatAmount = stringRes("$0.40"), chain = stringRes("Zcash"), token = stringRes("ZEC"), bigIcon = imageRes(R.drawable.ic_zec_round_full), smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_shielded)),
                to = SwapTokenAmountState(amount = stringRes("0.000004"), fiatAmount = stringRes("$0.39"), chain = stringRes("Bitcoin"), token = stringRes("BTC"), bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc), smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc)),
                items = listOf(
                    SwapQuoteInfoItem(stringRes("Pay from"), styledStringResource("ZEC Private")),
                    SwapQuoteInfoItem(stringRes("Pay to"), styledStringResource("Synthetic destination")),
                    SwapQuoteInfoItem(stringRes(R.string.private_wallet_transaction_fee), styledStringResource("0.0001 ZEC")),
                ),
                amount = SwapQuoteInfoItem(stringRes("Total amount"), styledStringResource("0.0101 ZEC")),
                primaryButton = ButtonState(stringRes("Confirm"), onClick = {}),
                infoText = stringRes("Synthetic review only. No payment can be submitted."), onBack = onBack,
            )
        )
        else -> return false
    }
    return true
}
