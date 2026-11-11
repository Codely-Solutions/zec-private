package co.electriccoin.zcash.ui.screen.donation

import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationView
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationState
import co.electriccoin.zcash.ui.design.component.BigIconButtonState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.screen.theme.settings.*
import co.electriccoin.zcash.ui.screen.theme.darklook.*
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
    var selectedMode by remember(screen) {
        mutableStateOf(when (screen) {
            "theme-settings-light" -> AppearanceMode.LIGHT
            "theme-settings-dark" -> AppearanceMode.DARK
            else -> AppearanceMode.SYSTEM
        })
    }
    var selectedOled by remember(screen) { mutableStateOf(screen == "dark-look-oled") }
    when (screen) {
        "server-badged" -> co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerView(
            co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerState(
                connectionMode = co.electriccoin.zcash.ui.screen.chooseserver.ServerConnectionModeState(
                    automatic = RadioButtonState(stringRes("Automatic"), false, onClick = {}),
                    manual = RadioButtonState(stringRes("Manual"), true, onClick = {}),
                    automaticBadge = stringRes("Recommended"),
                ),
                fastest = co.electriccoin.zcash.ui.screen.chooseserver.ServerListState.Fastest(
                    title = stringRes("Fastest servers"),
                    servers = listOf(co.electriccoin.zcash.ui.screen.chooseserver.ServerState.Default(
                        key = "synthetic-active", radioButtonState = RadioButtonState(
                            stringRes("Example server"), true, onClick = {},
                        ), badge = stringRes("Active"),
                    )),
                    retryButton = ButtonState(stringRes("Retry"), onClick = {}), isLoading = false,
                ),
                other = co.electriccoin.zcash.ui.screen.chooseserver.ServerListState.Other(
                    title = stringRes("Other servers"), servers = emptyList(),
                ),
                saveButton = ButtonState(stringRes("Save"), onClick = {}),
                dialogState = null, onBack = onBack,
            )
        )
        "about", "about-update", "about-update-error" -> co.electriccoin.zcash.ui.screen.about.view.About(
            onBack = onBack,
            configInfo = co.electriccoin.zcash.ui.fixture.ConfigInfoFixture.new(),
            versionInfo = co.electriccoin.zcash.ui.fixture.VersionInfoFixture.new(),
            onPrivacyPolicy = {},
            onTermsOfUse = {},
            updates = {
                co.electriccoin.zcash.ui.screen.updates.AppUpdateContent(
                    checking = false, current = screen == "about",
                    failed = screen == "about-update-error",
                    availableTag = if (screen == "about-update") "v3.19.0-private.17" else null,
                    onCheck = {}, onOpenRelease = {}, onObtainium = {},
                )
            },
        )
        "selection-indicators" -> BlankSurface {
            androidx.compose.foundation.layout.Column {
                listOf(true, false).forEach { enabled ->
                    listOf(false, true).forEach { checked ->
                        androidx.compose.material3.Text("Enabled: $enabled, selected: $checked")
                        ZashiCheckboxIndicator(isChecked = checked, isEnabled = enabled)
                        ZashiRadioButton(
                            RadioButtonState(
                                text = stringRes("Selection"), isChecked = checked,
                                isEnabled = enabled, onClick = {},
                            )
                        )
                        ZashiSelectionIndicator(
                            isChecked = checked, isEnabled = enabled, circular = true, useDot = true,
                        )
                    }
                }
            }
        }
        "theme-settings", "theme-settings-system", "theme-settings-light", "theme-settings-dark" -> ThemeSettingsView(
            ThemeSettingsState(
                options = listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK).map { mode ->
                    AppearanceModeOptionState(mode, selectedMode == mode, onClick = { selectedMode = mode })
                },
                saveButton = ButtonState(stringRes(R.string.currencyConversion_saveBtn), onClick = {}),
                onBack = onBack,
            )
        )
        "dark-look", "dark-look-classic", "dark-look-oled" -> ThemeDarkLookView(
            ThemeDarkLookState(
                options = listOf(false, true).map { oled ->
                    DarkLookOptionState(oled, selectedOled == oled, onClick = { selectedOled = oled })
                },
                saveButton = ButtonState(stringRes(R.string.currencyConversion_saveBtn), onClick = {}),
                onBack = onBack,
            )
        )
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
                explorerLinks = listOf(TransactionExplorerLink(
                    label = stringRes("Zcash transaction"), transactionId = "ab".repeat(32), onOpen = {},
                )),
            ), mainAppBarState = appBar,
        )
        "minimum-deposit-review", "price-no-market",
        "swap-review", "crosspay-review", "price-normal", "price-warning", "price-unavailable", "price-checking" ->
            SwapQuoteView(
            SwapQuoteState.Success(
                title = stringRes(when (screen) {
                    "crosspay-review" -> "Pay now"
                    "minimum-deposit-review" -> "Review quote"
                    else -> "Swap"
                }),
                rotateIcon = screen == "swap-review",
                from = if (screen == "minimum-deposit-review") {
                    SwapTokenAmountState(
                        amount = stringRes("0.001"), fiatAmount = stringRes("$75.00"),
                        chain = stringRes("Bitcoin"), token = stringRes("BTC"),
                        bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                        smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    )
                } else SwapTokenAmountState(amount = stringRes("0.01"), fiatAmount = stringRes("$0.40"), chain = stringRes("Zcash"), token = stringRes("ZEC"), bigIcon = imageRes(R.drawable.ic_zec_round_full), smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_shielded)),
                to = if (screen == "minimum-deposit-review") {
                    SwapTokenAmountState(
                        amount = stringRes("0.6"), fiatAmount = stringRes("$74.00"),
                        chain = stringRes("Zcash"), token = stringRes("ZEC"),
                        bigIcon = imageRes(R.drawable.ic_zec_round_full),
                        smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_shielded),
                    )
                } else SwapTokenAmountState(amount = stringRes("0.000004"), fiatAmount = stringRes("$0.39"), chain = stringRes("Bitcoin"), token = stringRes("BTC"), bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc), smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    valueDifference = QuoteValueDifferenceState(
                        percentage = stringRes("-3.47%"),
                        explanation = stringRes(if (screen == "crosspay-review") R.string.private_value_difference_buffered else R.string.private_value_difference_known),
                        isNegative = true,
                    )),
                items = if (screen == "minimum-deposit-review") emptyList() else listOf(
                    SwapQuoteInfoItem(stringRes("Pay from"), styledStringResource("ZEC Private")),
                    SwapQuoteInfoItem(stringRes("Pay to"), styledStringResource("Synthetic destination")),
                    SwapQuoteInfoItem(stringRes(R.string.private_wallet_transaction_fee), styledStringResource("0.0001 ZEC")),
                ),
                amount = SwapQuoteInfoItem(stringRes("Total amount"), styledStringResource(if (screen == "minimum-deposit-review") "0.001 BTC" else "0.0101 ZEC")),
                primaryButton = ButtonState(
                    stringRes(if (screen == "price-warning") "Swap anyway" else "Confirm"),
                    isEnabled = screen != "price-checking", onClick = {},
                ),
                minimumDeposit = if (screen == "minimum-deposit-review") reviewMinimumDeposit() else null,
                priceCheck = when (screen) {
                    "price-no-market" -> PriceCheckNoticeState(
                        stringRes(R.string.private_price_no_market),
                        stringRes(R.string.private_price_no_market_details),
                        showInline = false,
                    )
                    "price-warning" -> PriceCheckNoticeState(
                        stringRes(R.string.private_price_poor, "24.8"),
                        stringRes(R.string.private_price_check_details), true,
                    )
                    "price-normal" -> PriceCheckNoticeState(
                        stringRes(R.string.private_price_reference),
                        stringRes(R.string.private_price_reference_details),
                        showInline = false,
                        detailItems = listOf(
                            SwapQuoteInfoItem(stringRes(R.string.private_price_reference), styledStringResource("Kraken")),
                            SwapQuoteInfoItem(stringRes(R.string.private_price_reference_difference), styledStringResource("-2.50%")),
                        ),
                    )
                    "price-unavailable" -> PriceCheckNoticeState(
                        stringRes(R.string.private_price_unavailable),
                        stringRes(R.string.private_price_unavailable_details),
                        showInline = false,
                    )
                    "price-checking" -> PriceCheckNoticeState(
                        stringRes(R.string.private_price_checking),
                        stringRes(R.string.private_price_check_details),
                    )
                    else -> null
                },
                infoText = stringRes("Synthetic review only. No payment can be submitted."), onBack = onBack,
            )
        )
        "minimum-deposit-address" -> ORSwapConfirmationView(
            ORSwapConfirmationState(
                info = IconButtonState(co.electriccoin.zcash.ui.design.R.drawable.ic_info, onClick = {}),
                bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                amount = stringRes("0.00100000"),
                amountFiat = stringRes(""),
                onAmountClick = {},
                qr = "Synthetic deposit address",
                address = styledStringResource("Synthetic deposit address"),
                copyButton = BigIconButtonState(stringRes("Copy"), R.drawable.ic_copy, onClick = {}),
                shareButton = BigIconButtonState(stringRes("Share"), R.drawable.ic_qr_code_other, onClick = {}),
                footer = styledStringResource("Synthetic review"),
                primaryButton = ButtonState(stringRes("Done"), onClick = {}),
                onBack = onBack,
                minimumDeposit = reviewMinimumDeposit(),
            )
        )
        else -> return SwapSprintScene(screen, onBack, appBar)
    }
    return true
}

private fun reviewMinimumDeposit() = SwapQuoteInfoItem(
    stringRes(R.string.private_minimum_deposit),
    styledStringResource("0.00099501 BTC"),
    help = stringRes(R.string.private_minimum_deposit_help),
)
