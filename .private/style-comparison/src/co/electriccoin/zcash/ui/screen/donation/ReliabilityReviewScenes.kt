package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.WalletInitializationState
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.design.component.*
import co.electriccoin.zcash.ui.design.util.*
import co.electriccoin.zcash.ui.screen.balances.spendable.SpendableBalanceVM
import co.electriccoin.zcash.ui.screen.balances.spendable.SpendableBalanceView
import co.electriccoin.zcash.ui.screen.home.restoring.WalletRestoringMessage
import co.electriccoin.zcash.ui.screen.home.restoring.WalletRestoringMessageState
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsState
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsView
import co.electriccoin.zcash.ui.screen.onboarding.view.Onboarding
import co.electriccoin.zcash.ui.screen.receive.*
import co.electriccoin.zcash.ui.screen.restore.seed.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject

/** Seedless layout fixtures. No wallet initialization, clipboard, network or payment action is invoked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReliabilityReviewScene(screen: String, originalAccount: ZodlAccount): Boolean {
    when (screen) {
        "reliability-onboarding", "reliability-onboarding-pending", "reliability-onboarding-failed" -> Onboarding(
            onImportWallet = {},
            onCreateWallet = {},
            initialization = when (screen) {
                "reliability-onboarding-pending" -> WalletInitializationState.RUNNING
                "reliability-onboarding-failed" -> WalletInitializationState.FAILED
                else -> WalletInitializationState.IDLE
            }
        )
        "reliability-restore-blank", "reliability-restore-paste-error" -> RestoreSeedView(
            state = RestoreSeedState(
                seed = SeedTextFieldState((1..24).map {
                    SeedWordTextFieldState(
                        innerState = SeedWordInnerTextFieldState("", TextSelection.Start),
                        isError = false, onValueChange = {}
                    )
                }),
                onBack = {},
                dialogButton = IconButtonState(R.drawable.ic_help) {},
                nextButton = null,
                pasteButton = ButtonState(stringRes(R.string.restore_seed_paste), onClick = {}),
                isPasteError = screen == "reliability-restore-paste-error"
            ),
            suggestionsState = RestoreSeedSuggestionsState(isVisible = false, suggestions = emptyList())
        )
        "reliability-balance-pending", "reliability-balance-remainder" -> {
            val account = remember(screen) {
                originalAccount.copy(
                    orchardBalance = WalletBalance(
                        Zatoshi(1_000_000),
                        Zatoshi(if (screen == "reliability-balance-pending") 200_000 else 0),
                        Zatoshi(0), Zatoshi(0)
                    ),
                    transparentBalance = Zatoshi(if (screen == "reliability-balance-remainder") 1 else 0)
                )
            }
            val accounts = remember(account) {
                reviewInterface<AccountDataSource>(mapOf(
                    "getAllAccounts" to MutableStateFlow<List<WalletAccount>?>(listOf(account)),
                    "getSelectedAccount" to MutableStateFlow<WalletAccount?>(account)
                ))
            }
            val navigation = remember { reviewInterface<NavigationRouter>(emptyMap()) }
            val shielding = koinInject<ShieldFundsUseCase>()
            // The real VM computes its initial state from the synthetic SDK balances. No flow is subscribed.
            val state = remember(account) {
                SpendableBalanceVM(accounts, navigation, shielding).state.value
            }
            SpendableBalanceView(state)
        }
        "reliability-insufficient-funds" -> InsufficientFundsView(InsufficientFundsState(onBack = {}))
        "reliability-restoring-spendable" -> BlankSurface {
            WalletRestoringMessage(
                contentPadding = PaddingValues(16.dp),
                state = WalletRestoringMessageState(isSpendable = true, progress = 53.1f, onClick = {})
            )
        }
        "reliability-receive-unified-only" -> ReceiveView(ReceiveState(
            items = listOf(ReceiveAddressState(
                icon = imageRes(R.drawable.ic_zec_round_full),
                title = stringRes(R.string.accounts_zashi_shieldedAddress),
                subtitle = styledStringResource("synthetic-shielded-address"),
                isExpanded = true, colorMode = ReceiveAddressState.ColorMode.ZASHI,
                infoIconButton = IconButtonState(R.drawable.ic_receive_zashi_shielded_info) {},
                onClick = {}, isShielded = true, onCopyClicked = {}, onQrClicked = {}, onRequestClicked = {}
            )),
            isLoading = false, onBack = {}
        ), appBarState = co.electriccoin.zcash.ui.fixture.ZashiMainTopAppBarStateFixture.new())
        else -> return false
    }
    return true
}

/** Only the account snapshot is supplied; navigation is inert and unexpected services fail closed. */
private inline fun <reified T> reviewInterface(values: Map<String, Any?>): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when {
            method.name == "toString" -> "SyntheticReviewDependency"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === args?.firstOrNull()
            values.containsKey(method.name) -> values[method.name]
            method.returnType == Void.TYPE -> null
            else -> error("Unexpected review dependency: ${method.name}")
        }
    } as T
