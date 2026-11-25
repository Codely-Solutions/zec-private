package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.screen.send.Send

class SendTransactionAgainUseCase(
    private val prefillSendUseCase: PrefillSendUseCase,
    private val navigationRouter: NavigationRouter,
    private val rejectIfSelectedAccountCannotSpend: RejectIfSelectedAccountCannotSpendUseCase,
    private val accounts: AccountDataSource,
    private val appAccess: AppAccessSession,
) {
    suspend operator fun invoke(value: DetailedTransactionData, sourceEntryId: String) {
        val owner = selectedAccountId() ?: return
        val transactionOwner = value.transaction.accountUuid
        val grant = appAccess.grant.value
        val isAllowed = {
            navigationRouter.isAtEntry(sourceEntryId) && appAccess.permits(grant) && selectedAccountId() == owner &&
                (transactionOwner == null || transactionOwner == owner)
        }
        if (isAllowed() && !rejectIfSelectedAccountCannotSpend { route ->
                stageResultFrom(sourceEntryId, isAllowed) { NavigationCommand.Forward(listOf(route)) }
            } && isAllowed()
        ) {
            navigationRouter.commitFrom(sourceEntryId, isAllowed) {
                prefillSendUseCase.requestFromTransactionDetail(value)
                NavigationCommand.Forward(listOf(Send(isScanZip321Enabled = false)))
            }
        }
    }

    private fun selectedAccountId() =
        accounts.allAccounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid
}
