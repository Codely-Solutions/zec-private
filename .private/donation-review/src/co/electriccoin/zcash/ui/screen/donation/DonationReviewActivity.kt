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
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
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
            ZcashTheme(appearanceMode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT, isOledEnabled = dark) {
                var screen by remember { mutableStateOf(if (requested == "revisit") "home" else requested) }
                var amount by remember { mutableStateOf("") }
                if (screen == "donation") {
                    DonationView(
                        state = DonationViewState(amount = amount, canReview = amount.toBigDecimalOrNull()?.signum() == 1),
                        onAmountChanged = { amount = it },
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
                            ActivityStateFixture.new().copy(title = stringRes("Received"), subtitle = stringRes("Yesterday")),
                            ActivityStateFixture.new().copy(title = stringRes("Sent"), subtitle = stringRes("2 days ago")),
                        ),
                    )
                    val home = HomeState.preview.copy(
                        firstButton = BigIconButtonState(stringRes("Receive"), R.drawable.ic_home_receive, onClick = {}),
                        secondButton = BigIconButtonState(stringRes("Send"), R.drawable.ic_home_send, onClick = {}),
                        thirdButton = BigIconButtonState(stringRes("Pay"), R.drawable.ic_home_scan, onClick = {}),
                        fourthButton = BigIconButtonState(stringRes("Swap"), R.drawable.ic_home_buy, onClick = {}),
                        message = if (screen == "backup") WalletBackupMessageState({}, {}) else null,
                    )
                    HomeView(
                        appBarState = ZashiMainTopAppBarStateFixture.new(),
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
