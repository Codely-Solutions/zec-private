package co.electriccoin.zcash.ui.screen.donation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.design.component.BigIconButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.theme.AppearanceMode

import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.fixture.ObserveFiatCurrencyResultFixture
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.home.HomeState
import co.electriccoin.zcash.ui.screen.home.HomeView
import co.electriccoin.zcash.ui.screen.home.backup.WalletBackupMessageState
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAddressBookState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import co.electriccoin.zcash.ui.screen.send.view.Send
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityStateFixture
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetStateFixture

/** Production screen trees with matched synthetic state; no wallet, network or payment dependencies. */
class StyleComparisonActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        val requested = intent.getStringExtra("screen") ?: "home"
        val dark = intent.getBooleanExtra("dark", false)
        val mode = intent.getStringExtra("theme") ?: if (dark) "dark" else "light"
        setContent {
            require(mode in setOf("light", "dark", "oled"))
                ZcashTheme(
                    appearanceMode = if (mode == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                    isOledEnabled = mode == "oled",
                ) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        ComparisonScreens(requested)
                    }
                }
        }
    }
}

@Suppress("LongMethod", "MagicNumber")
@Composable
private fun ComparisonScreens(requested: String) {
    var screen by remember { mutableStateOf(requested) }
    val exchangeRate = remember {
        ObserveFiatCurrencyResultFixture.new(isLoading = false, isStale = false, isRefreshEnabled = true)
    }
    val balance = remember { Zatoshi(if (requested == "home-long") 123_456_789_012_345 else 12_500_000) }
    val account = remember {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0), Zatoshi(0))
        ZodlAccount(
            sdkAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() })),
            unifiedAddress = "synthetic-unified-address",
            transparentAddress = "synthetic-transparent-address",
            saplingAddress = "synthetic-sapling-address",
            orchardBalance = WalletBalance(balance, Zatoshi(0), Zatoshi(0), Zatoshi(0)),
            saplingBalance = zero,
            ironwoodBalance = zero,
            transparentBalance = Zatoshi(0),
            isSelected = true,
        )
    }
    val balanceState = remember {
        BalanceWidgetState.emptyPreview.copy(totalBalance = balance, exchangeRate = exchangeRate)
    }
    val appBar = remember {
        ZashiMainTopAppBarStateFixture.new(
            balanceVisibilityButton = IconButtonState(
                co.electriccoin.zcash.ui.design.R.drawable.ic_app_bar_balances_show,
                contentDescription = stringRes(R.string.hide_balances_content_description),
                onClick = {},
            ),
            settingsButton = IconButtonState(R.drawable.ic_home_more, onClick = {}),
        )
    }
    var amount by remember { mutableStateOf(AmountState.newFromZec(if (requested == "send-error") "0.01234567" else "0.01", "", false, exchangeRate)) }
    var memo by remember { mutableStateOf(MemoState.new("")) }
    var recipient by remember {
        mutableStateOf(
            if (requested == "send-error") RecipientAddressState.new(
                "synthetic-invalid-recipient-".repeat(5),
                AddressType.Invalid("Synthetic address is invalid. Check the complete recipient address."),
            ) else RecipientAddressState.new("")
        )
    }
    var memoExpanded by remember { mutableStateOf(requested == "donation-message") }
    var invitationDismissed by remember { mutableStateOf(false) }

    if (RecipientReviewScene(screen, appBar, account, balanceState)) return
    if (AdditionalReviewScene(screen, { screen = "home" }, appBar)) return
    when (screen) {
        "send", "send-error" -> Send(
            balanceWidgetState = balanceState,
            sendStage = SendStage.Form,
            onCreateZecSend = {},
            onBack = { screen = "home" },
            onQrScannerOpen = {},
            hasCameraFeature = true,
            recipientAddressState = recipient,
            onRecipientAddressChange = {
                recipient = RecipientAddressState.new(
                    it,
                    if (it.isEmpty()) null else AddressType.Invalid("Sample address only")
                )
            },
            setAmountState = { amount = it },
            amountState = amount,
            setMemoState = { memo = it },
            memoState = memo,
            selectedAccount = account,
            exchangeRateState = exchangeRate,
            sendAddressBookState = SendAddressBookState(
                mode = SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                isHintVisible = false,
                onButtonClick = {},
            ),
            zashiMainTopAppBarState = appBar,
        )
        "donation", "donation-message" -> DonationView(
            state = DonationViewState(
                amountState = amount,
                memoState = memo,
                memoExpanded = memoExpanded,
                exchangeRateState = exchangeRate,
                spendableShieldedBalance = balance,
                canReview = (amount as? AmountState.Valid)?.zatoshi?.let {
                    it.value > 0 && it <= balance && memo is MemoState.Correct
                } == true,
            ),
            onAmountChanged = { amount = it },
            onMemoChanged = { memo = it },
            onToggleMemo = { memoExpanded = !memoExpanded },
            onReview = {},
            onExternalAddresses = {},
            onBack = { screen = "home" },
        )
        else -> {
            val blocked = screen == "home-warning"
            val support = HomeDonationSupportState(
                showInvitation = !blocked && !invitationDismissed,
                onDonate = { screen = "donation" },
                onLater = { invitationDismissed = true },
                onNever = { invitationDismissed = true },
            )
            val activity = remember {
                val fixture = ActivityWidgetStateFixture.new()
                fixture.copy(
                    header = fixture.header.copy(title = stringRes("Activity")),
                    transactions = listOf(
                        ActivityStateFixture.new().copy(
                            title = stringRes("Received"),
                            subtitle = stringRes("Yesterday"),
                            bigIcon = R.drawable.ic_transaction_received,
                            smallIcon = null,
                            value = stringRes("+0.01 ZEC").withStyle(
                                StyledStringStyle(color = StringResourceColor.POSITIVE)
                            ),
                            key = "synthetic-received",
                        ),
                        ActivityStateFixture.new().copy(
                            title = stringRes("Sent"),
                            subtitle = stringRes("2 days ago"),
                            value = styledStringResource("-0.01 ZEC"),
                            smallIcon = null,
                            key = "synthetic-sent",
                        ),
                    ),
                )
            }
            val home = HomeState.preview.copy(
                firstButton = BigIconButtonState(stringRes("Receive"), R.drawable.ic_home_receive, onClick = {}),
                secondButton = BigIconButtonState(
                    stringRes("Send"), R.drawable.ic_home_send, onClick = { screen = "send" }
                ),
                thirdButton = BigIconButtonState(stringRes("Pay"), R.drawable.ic_home_pay, onClick = {}),
                fourthButton = BigIconButtonState(stringRes("Swap"), R.drawable.ic_home_swap, onClick = {}),
                message = if (blocked) WalletBackupMessageState({}, {}) else null,
            )
            HomeView(
                appBarState = appBar,
                balanceWidgetState = balanceState,
                transactionWidgetState = activity,
                state = home,
                supportAction = { DonationShortcut(support) },
                supportInvitation = if (support.showInvitation) ({ HomeDonationInvitation(support) }) else null,
                updateNotice = if (screen == "home-update" && !invitationDismissed) ({
                    co.electriccoin.zcash.ui.screen.updates.AppUpdateNotice(
                        tag = "v3.19.0-private.17", onOpen = {},
                        onDismiss = { invitationDismissed = true },
                    )
                }) else null,
            )
        }
    }
}
