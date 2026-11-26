package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.screen.send.Send

class SendTransactionAgainUseCase(
    private val prefillSendUseCase: PrefillSendUseCase,
    private val navigationRouter: NavigationRouter,
    private val rejectIfSelectedAccountCannotSpend: RejectIfSelectedAccountCannotSpendUseCase,
    private val accounts: AccountDataSource,
    private val appAccess: AppAccessSession,
    private val walletAccess: WalletWriteAccess,
) {
    suspend operator fun invoke(value: DetailedTransactionData, sourceEntryId: String) {
        if (!value.canSendAgain) return
        val owner = selectedAccountId() ?: return
        val transactionOwner = value.transaction.accountUuid
        val grant = appAccess.grant.value
        val generation = walletAccess.token()
        val walletPermit = {
            appAccess.permits(grant) && walletAccess.permits(generation) && selectedAccountId() == owner &&
                (transactionOwner == null || transactionOwner == owner)
        }
        val isAllowed = { navigationRouter.isAtEntry(sourceEntryId) && walletPermit() }
        if (isAllowed() && !rejectIfSelectedAccountCannotSpend { route ->
                stageResultFrom(sourceEntryId, isAllowed) { NavigationCommand.Forward(listOf(route)) }
            } && isAllowed()
        ) {
            navigationRouter.commitFrom(sourceEntryId, isAllowed) {
                val token = prefillSendUseCase.requestFromTransactionDetail(value, walletPermit)
                NavigationCommand.Forward(listOf(Send(isScanZip321Enabled = false, prefillToken = token)))
            }
        }
    }

    private fun selectedAccountId() =
        accounts.allAccounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid
}
