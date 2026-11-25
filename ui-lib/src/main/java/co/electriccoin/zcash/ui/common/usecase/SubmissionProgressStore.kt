package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** Keeps the displayed proposal and its result in the same wallet session. */
class SubmissionProgressStore(
    private val accounts: AccountDataSource,
    private val walletWriteAccess: WalletWriteAccess,
) {
    private val current = MutableStateFlow<SubmissionProgress?>(null)

    fun begin(proposal: TransactionProposal, owner: AccountUuid? = selectedAccount()): SubmissionProgress? {
        val wallet = walletWriteAccess.token()
        return if (owner != null && walletWriteAccess.permits(wallet)) {
            SubmissionProgress(UUID.randomUUID().toString(), proposal, owner, wallet)
                .also { current.value = it }
        } else {
            null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(id: String?): Flow<Pair<TransactionProposal?, SubmitProposalState?>> =
        current.flatMapLatest { submission ->
            if (id != null && submission?.id == id) {
                combine(submission.state, accounts.allAccounts, walletWriteAccess.version) { result, _, _ ->
                    if (canShow(id)) submission.proposal to result else null to null
                }
            } else {
                flowOf(null to null)
            }
        }

    internal fun canShow(id: String?): Boolean = current.value?.let { submission ->
        submission.id == id && submission.owner == selectedAccount() && walletWriteAccess.permits(submission.wallet)
    } == true

    internal fun discard(id: String?) {
        val submission = current.value
        if (submission?.id == id) current.compareAndSet(submission, null)
    }

    private fun selectedAccount() = accounts.allAccounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid
}

class SubmissionProgress internal constructor(
    val id: String,
    internal val proposal: TransactionProposal,
    internal val owner: AccountUuid,
    internal val wallet: Long,
) {
    internal val state = MutableStateFlow<SubmitProposalState>(SubmitProposalState.Submitting)

    fun record(result: SubmitResult) {
        state.value = SubmitProposalState.Result(result)
    }

    fun recordFailure(error: Exception) {
        state.compareAndSet(SubmitProposalState.Submitting, SubmitProposalState.Result(SubmitResult.Error(error)))
    }
}
