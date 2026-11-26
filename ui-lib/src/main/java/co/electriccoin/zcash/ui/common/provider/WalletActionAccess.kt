package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import kotlinx.coroutines.flow.combine

/** Keeps an operation with the wallet session, selected account and engine that admitted it. */
class WalletActionAccess(
    private val appAccess: AppAccessSession,
    private val walletAccess: WalletWriteAccess,
    private val accounts: AccountDataSource,
) {
    val changes by lazy {
        combine(appAccess.grant, walletAccess.admissionChanges, accounts.signingAuthorityChanges) { _, _, _ -> Unit }
    }

    suspend fun capture(account: WalletAccount): (() -> Boolean)? {
        val grant = appAccess.grant.value
        val token = walletAccess.token()
        if (!appAccess.permits(grant) || !walletAccess.permits(token)) return null
        return accounts.confirmSelection(account)?.let { selection ->
            val current = { appAccess.permits(grant) && walletAccess.permits(token) && selection.canOperate() }
            current.takeIf { it() }
        }
    }
}
