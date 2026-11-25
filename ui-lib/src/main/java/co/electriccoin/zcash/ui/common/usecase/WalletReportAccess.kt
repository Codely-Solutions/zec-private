package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.bindReportHandoff
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.stateIn

class WalletReportAccess(
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val observeAccount: ObserveSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
) {
    internal fun captureWallet(): () -> Boolean {
        val token = walletWriteAccess.token()
        val grant = appAccessSession.grant.value
        return { appAccessSession.permits(grant) && walletWriteAccess.permits(token) }
    }

    internal fun bindToCurrentEntry(allowed: () -> Boolean): () -> Boolean =
        navigationRouter.bindReportHandoff(allowed)

    internal fun bind(scope: CoroutineScope): () -> (() -> Boolean) {
        val token = walletWriteAccess.token()
        var owner: Any? = null
        val selections = observeAccount()
            .map { it?.sdkAccount?.accountUuid }
            .distinctUntilChanged()
            .map { account -> account?.let { Any() } }
            .onEach { if (owner == null && it != null) owner = it }
            .stateIn(scope, SharingStarted.Eagerly, null)
        return {
            val grant = appAccessSession.grant.value
            bindToCurrentEntry {
                scope.isActive && owner != null && selections.value === owner &&
                    appAccessSession.permits(grant) && walletWriteAccess.permits(token)
            }
        }
    }
}
