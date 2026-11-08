package co.electriccoin.zcash.ui.screen.donation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BigIconButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.home.HomeState
import co.electriccoin.zcash.ui.screen.home.HomeView
import co.electriccoin.zcash.ui.screen.home.backup.WalletBackupMessageState
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityStateFixture
import co.electriccoin.zcash.ui.screen.transactionhistory.widget.ActivityWidgetStateFixture

/** Renders production components with synthetic display data. No wallet, network or payment calls. */
class DonationReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(co.electriccoin.zcash.ui.BuildConfig.DEBUG)
        val lifecyclePrefs = getSharedPreferences("donation_ui_review_lifecycle", MODE_PRIVATE)
        lifecyclePrefs.edit().putInt("creations", lifecyclePrefs.getInt("creations", 0) + 1).commit()
        val dark = intent.getBooleanExtra("dark", true)
        val requested = intent.getStringExtra("screen") ?: "home"
        val preferences = DonationPreferences(this)
        if (requested != "revisit") {
            getSharedPreferences("private_donation_suggestions", MODE_PRIVATE).edit().clear().commit()
            if (requested == "invitation" || requested == "backup") preferences.recordSuccessfulTransaction()
            if (requested == "home") preferences.claimAutomaticIntroduction()
        }
        setContent {
            ZcashTheme(
                appearanceMode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT,
                isOledEnabled = dark,
            ) {
                var screen by remember { mutableStateOf(if (requested == "revisit") "home" else requested) }
                var amount by remember {
                    mutableStateOf(co.electriccoin.zcash.ui.screen.send.model.AmountState.newFromZec(
                        "", "", false, co.electriccoin.zcash.ui.common.wallet.ExchangeRateState.OptedOut
                    ))
                }
                var memo by remember { mutableStateOf(co.electriccoin.zcash.ui.screen.send.model.MemoState.new("")) }
                var memoExpanded by remember { mutableStateOf(false) }
                if (screen == "recovery") {
                    co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupView(
                        co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupState(
                            seed = co.electriccoin.zcash.ui.design.component.SeedTextState(
                                seed = (1..24).joinToString(" ") { "sample$it" }, isRevealed = true
                            ),
                            birthday = co.electriccoin.zcash.ui.screen.walletbackup.SeedSecretState(
                                title = stringRes(R.string.recoveryPhraseDisplay_birthdayTitle),
                                text = stringRes("4600000"), isRevealed = true, tooltip = null, onClick = null
                            ),
                            info = co.electriccoin.zcash.ui.design.component.IconButtonState(
                                icon = R.drawable.ic_help, onClick = {}
                            ),
                            primaryButton = co.electriccoin.zcash.ui.design.component.ButtonState(
                                text = stringRes("Done"), onClick = { screen = "home" }
                            ),
                            secondaryButton = null, onBack = { screen = "home" },
                            copyPhrase = co.electriccoin.zcash.ui.design.component.ButtonState(
                                text = stringRes(R.string.recovery_copy_phrase), onClick = {}
                            ),
                            copyBirthday = co.electriccoin.zcash.ui.design.component.ButtonState(
                                text = stringRes(R.string.recovery_copy_birthday), onClick = {}
                            ),
                        )
                    )
                } else if (screen == "donation") {
                    DonationView(
                        state = DonationViewState(
                            amountState = amount,
                            memoState = memo,
                            memoExpanded = memoExpanded,
                            canReview = amount is co.electriccoin.zcash.ui.screen.send.model.AmountState.Valid,
                        ),
                        onAmountChanged = { amount = it },
                        onMemoChanged = { memo = it },
                        onToggleMemo = { memoExpanded = !memoExpanded },
                        onReview = {},
                        onExternalAddresses = {},
                        onBack = { screen = "home" },
                    )
                } else {
                    val support = rememberHomeDonationSupport(
                        blocked = screen == "backup",
                        onDonate = { screen = "donation" },
                    )
                    val activity = ActivityWidgetStateFixture.new().copy(
                        header = ActivityWidgetStateFixture.new().header.copy(title = stringRes("Activity")),
                        transactions = listOf(
                            ActivityStateFixture.new().copy(
                                title = stringRes("Received"),
                                subtitle = stringRes("Yesterday"),
                                bigIcon = R.drawable.ic_transaction_received,
                                smallIcon = null,
                            ),
                            ActivityStateFixture.new().copy(
                                title = stringRes("Sent"),
                                subtitle = stringRes("2 days ago"),
                                value = styledStringResource("-0.01 ZEC"),
                                smallIcon = null,
                            ),
                        ),
                    )
                    val home = HomeState.preview.copy(
                        firstButton = BigIconButtonState(
                            stringRes("Receive"), R.drawable.ic_home_receive, onClick = {},
                        ),
                        secondButton = BigIconButtonState(
                            stringRes("Send"), R.drawable.ic_home_send, onClick = {},
                        ),
                        thirdButton = BigIconButtonState(
                            stringRes("Pay"), R.drawable.ic_home_pay, onClick = {},
                        ),
                        fourthButton = BigIconButtonState(
                            stringRes("Swap"), R.drawable.ic_home_swap, onClick = {},
                        ),
                        message = if (screen == "backup") WalletBackupMessageState({}, {}) else null,
                    )
                    HomeView(
                        appBarState = ZashiMainTopAppBarStateFixture.new(
                            balanceVisibilityButton = IconButtonState(
                                co.electriccoin.zcash.ui.design.R.drawable.ic_app_bar_balances_show,
                                contentDescription = stringRes(R.string.hide_balances_content_description),
                                onClick = {},
                            ),
                            settingsButton = IconButtonState(R.drawable.ic_home_more, onClick = {}),
                        ),
                        balanceWidgetState = BalanceWidgetState.emptyPreview.copy(totalBalance = Zatoshi(0)),
                        transactionWidgetState = activity,
                        state = home,
                        supportAction = { DonationShortcut(support) },
                        supportInvitation = if (support.showInvitation) ({ HomeDonationInvitation(support) }) else null,
                    )
                }
            }
        }
    }
}
