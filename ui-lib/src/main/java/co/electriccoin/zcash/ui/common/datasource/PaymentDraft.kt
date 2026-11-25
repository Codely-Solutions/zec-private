package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface PaymentDraft {
    data class Send(val value: ZecSend) : PaymentDraft

    data class Donation(val value: ZecSend) : PaymentDraft

    data class Zip321(val uri: String) : PaymentDraft
}

class PaymentDraftOwner(
    val account: WalletAccount,
    internal val entryId: String?,
    internal val permits: () -> Boolean,
) {
    fun requireCurrent() {
        if (!permits()) throw CancellationException("Payment preparation is no longer current")
    }
}

suspend fun ProposalDataSource.preparePayment(owner: PaymentDraftOwner, draft: PaymentDraft): SendTransactionProposal {
    owner.requireCurrent()
    val account = owner.account
    return when (draft) {
        is PaymentDraft.Send -> createProposal(account, draft.value)
        is PaymentDraft.Donation -> {
            require(account is ZodlAccount && draft.value.destination.address == DonationPolicy.recipient())
            createProposal(account, draft.value).copy(isDonation = true)
        }

        is PaymentDraft.Zip321 -> createOwnedZip321Proposal(owner, draft.uri)
    }
}

private suspend fun ProposalDataSource.createOwnedZip321Proposal(
    owner: PaymentDraftOwner,
    uri: String,
): Zip321TransactionProposal = try {
    createZip321Proposal(owner.account, uri)
} catch (error: TransactionProposalNotCreatedException) {
    currentCoroutineContext().ensureActive()
    Twig.error { "Unable to create ZIP321 proposal" }
    throw error
} catch (error: InsufficientFundsException) {
    currentCoroutineContext().ensureActive()
    Twig.error { "Insufficient funds for ZIP321 proposal" }
    throw error
}

suspend fun MutableStateFlow<TransactionProposal?>.publishOwnedPayment(
    owner: PaymentDraftOwner,
    previous: TransactionProposal?,
    proposal: SendTransactionProposal,
) {
    currentCoroutineContext().ensureActive()
    owner.requireCurrent()
    if (value !== previous || !compareAndSet(previous, proposal)) {
        throw CancellationException("Another payment replaced the prepared draft")
    }
}
