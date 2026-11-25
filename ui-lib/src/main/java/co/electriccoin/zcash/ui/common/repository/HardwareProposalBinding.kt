package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The proposal, device input, proofs and signatures stay with the same preparation. */
class PreparedHardwareProposal(
    val proposal: TransactionProposal,
    val pczt: Pczt,
    val owner: AccountUuid?,
    internal val walletGeneration: Long,
) {
    internal val id = UUID.randomUUID().toString()
    val proofs = CompletableDeferred<Pczt>()

    @Volatile
    var reviewedQuote: SwapQuoteData.Success? = null
        internal set

    @Volatile
    var signatures: Pczt? = null

    @Suppress("TooGenericExceptionCaught")
    fun startProofs(scope: CoroutineScope, addProofs: suspend (Pczt) -> Pczt): Job =
        scope.launch {
            try {
                proofs.complete(addProofs(pczt.clonePczt()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                proofs.completeExceptionally(e)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null) proofs.completeExceptionally(cause)
            }
        }
}

/** An approval is invalidated whenever its prepared artifact is replaced or cleared. */
class HardwareProposalBinding(private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess()) {
    private var prepared: PreparedHardwareProposal? = null
    private val approval = MutableStateFlow<PreparedHardwareProposal?>(null)
    internal val approved = approval.asStateFlow()

    @Synchronized
    fun prepare(proposal: TransactionProposal, pczt: Pczt, owner: AccountUuid? = null): PreparedHardwareProposal {
        approval.value = null
        val generation = walletWriteAccess.token()
        check(walletWriteAccess.permits(generation)) { "The wallet changed before signing preparation." }
        return PreparedHardwareProposal(proposal, pczt, owner, generation).also { prepared = it }
    }

    @Synchronized
    fun approve(
        current: TransactionProposal?,
        expected: TransactionProposal?,
        reviewedQuote: SwapQuoteData.Success? = null,
    ) {
        val artifact = checkNotNull(prepared) { "The transaction has no prepared PCZT." }
        check(current === artifact.proposal && (expected == null || expected === current)
            && walletWriteAccess.permits(artifact.walletGeneration)) {
            "The reviewed transaction changed before signing."
        }
        check(current !is SwapTransactionProposal || current === expected) {
            "Review the prepared swap transaction before signing."
        }
        if (current is SwapTransactionProposal) current.requireFundingApproval(reviewedQuote)
        artifact.reviewedQuote = reviewedQuote
        approval.value = artifact
    }

    @Synchronized
    fun getApproved(current: TransactionProposal?): PreparedHardwareProposal? =
        approval.value?.takeIf { it.proposal === current && walletWriteAccess.permits(it.walletGeneration) }

    fun requireApproved(
        current: TransactionProposal?,
        expected: TransactionProposal? = null
    ): PreparedHardwareProposal {
        val artifact = checkNotNull(getApproved(current)) { "The reviewed transaction changed before signing." }
        check(expected == null || expected === artifact.proposal) { "The reviewed transaction changed before signing." }
        return artifact
    }

    @Synchronized
    fun getPrepared(current: TransactionProposal?): PreparedHardwareProposal? =
        prepared?.takeIf { it.proposal === current && walletWriteAccess.permits(it.walletGeneration) }

    @Synchronized
    fun clear() {
        approval.value = null
        prepared = null
    }
}
