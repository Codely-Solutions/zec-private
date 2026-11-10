package co.electriccoin.zcash.ui.screen.balances.spendable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.sdk.extension.typicalFee
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.LoadedAccountBalances
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SpendableBalanceVM(
    accountDataSource: AccountDataSource,
    private val navigationRouter: NavigationRouter,
    private val shieldFunds: ShieldFundsUseCase,
) : ViewModel() {
    val state =
        accountDataSource.selectedAccount.map { account ->
            createState(account)
        }.filterNotNull()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue =
                    createState(
                        account =
                            accountDataSource.allAccounts.value
                                .orEmpty()
                                .firstOrNull { it.isSelected }
                    )
            )

    @Suppress("ReturnCount")
    private fun createState(account: WalletAccount?): SpendableBalanceState? {
        if (account == null) return null
        val balances = account.loadedBalances ?: return null
        val canShield = account.isSpendable && balances.isShieldingAvailable
        return SpendableBalanceState(
            title = stringRes(R.string.balances_spendableBalance_title),
            message = createMessage(balances, canShield),
            positive = createPositiveButton(canShield),
            onBack = ::onBack,
            rows = createInfoRows(balances),
            shieldButton = createShieldButtonState(balances, canShield)
        )
    }

    private fun createMessage(
        balances: LoadedAccountBalances,
        canShield: Boolean
    ): StringResource {
        val pending =
            when {
                balances.pendingShieldedBalance > Zatoshi(0) -> {
                    stringRes(R.string.private_balance_pending_confirmations)
                }

                balances.isAllShielded && balances.totalTransparentBalance > Zatoshi(0) -> {
                    stringRes(R.string.private_balance_transparent_remainder)
                }

                balances.isAllShielded -> {
                    stringRes(R.string.balances_everythingDone)
                }

                balances.totalBalance > balances.spendableShieldedBalance -> {
                    stringRes(R.string.private_balance_not_yet_spendable)
                }

                else -> {
                    null
                }
            }

        val shielding =
            stringRes(
                R.string.balances_infoShielding,
                CURRENCY_TICKER,
                stringRes(Zatoshi.typicalFee, HIDDEN),
                CURRENCY_TICKER
            ).takeIf { canShield }

        return if (pending != null && shielding != null) {
            pending + stringRes("\n\n") + shielding
        } else {
            pending ?: shielding ?: stringRes("")
        }
    }

    private fun createPositiveButton(canShield: Boolean) =
        ButtonState(
            text =
                if (canShield) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.balances_dismiss)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok)
                },
            onClick = ::onBack
        )

    private fun createInfoRows(
        balances: LoadedAccountBalances
    ): List<SpendableBalanceRowState> {
        return listOfNotNull(
            SpendableBalanceRowState(
                title = stringRes(R.string.balances_spendableBalance),
                icon = imageRes(R.drawable.ic_balance_shield),
                value =
                    stringRes(balances.spendableShieldedBalance)
            ),
            SpendableBalanceRowState(
                title = stringRes(R.string.private_balance_transparent_row),
                icon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_unshielded),
                value = stringRes(balances.totalTransparentBalance)
            ).takeIf { balances.totalTransparentBalance > Zatoshi(0) },
            when {
                balances.pendingShieldedBalance > Zatoshi(0) -> {
                    SpendableBalanceRowState(
                        title = stringRes(R.string.balances_pending),
                        icon = loadingImageRes(),
                        value = stringRes(balances.pendingShieldedBalance)
                    )
                }

                balances.totalShieldedBalance > balances.spendableShieldedBalance -> {
                    SpendableBalanceRowState(
                        title = stringRes(R.string.private_balance_unavailable_row),
                        icon = loadingImageRes(),
                        value =
                            stringRes(balances.totalShieldedBalance - balances.spendableShieldedBalance)
                    )
                }

                else -> {
                    null
                }
            },
        )
    }

    private fun createShieldButtonState(
        balances: LoadedAccountBalances,
        canShield: Boolean
    ): SpendableBalanceShieldButtonState? =
        SpendableBalanceShieldButtonState(
            amount = balances.transparentBalance,
            onShieldClick = ::onShieldClick
        ).takeIf { canShield }

    private fun onBack() = navigationRouter.back()

    private fun onShieldClick() = shieldFunds(closeCurrentScreen = true)
}
