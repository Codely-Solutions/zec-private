package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.requireCurrentShieldingApproval
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val proofs = CompletableDeferred<Pczt>()

    @Volatile
    var reviewedQuote: SwapQuoteData.Success? = null
        internal set

    @Volatile
    var signatures: Pczt? = null

    suspend fun awaitProofs(): Pczt =
        try {
            proofs.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            error("The reviewed transaction changed while preparing the proof.")
        }

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
    private var canSign: (() -> Boolean)? = null
    private val approval = MutableStateFlow<PreparedHardwareProposal?>(null)
    internal val approved = approval.asStateFlow()
    private val submission = MutableStateFlow<SubmitProposalState?>(null)
    val submissionState = submission.asStateFlow()

    @Synchronized
    fun recordSubmission(owner: PreparedHardwareProposal, state: SubmitProposalState) {
        if (prepared === owner) submission.value = state
    }

    @Synchronized
    fun prepare(proposal: TransactionProposal, pczt: Pczt, owner: AccountUuid? = null): PreparedHardwareProposal {
        approval.value = null
        canSign = null
        submission.value = null
        val generation = walletWriteAccess.token()
        check(walletWriteAccess.permits(generation)) { "The wallet changed before signing preparation." }
        return PreparedHardwareProposal(proposal, pczt, owner, generation).also { prepared = it }
    }

    @Synchronized
    fun approve(
        current: TransactionProposal?,
        expected: TransactionProposal?,
        reviewedQuote: SwapQuoteData.Success? = null,
        canSign: () -> Boolean = { true },
    ) {
        val artifact = checkNotNull(prepared) { "The transaction has no prepared PCZT." }
        check(current === artifact.proposal && (expected == null || expected === current)
            && walletWriteAccess.permits(artifact.walletGeneration) && canSign()) {
            "The reviewed transaction changed before signing."
        }
        check(current !is SwapTransactionProposal || current === expected) {
            "Review the prepared swap transaction before signing."
        }
        if (current is SwapTransactionProposal) current.requireFundingApproval(reviewedQuote)
        artifact.proposal.requireCurrentShieldingApproval()
        artifact.reviewedQuote = reviewedQuote
        this.canSign = canSign
        approval.value = artifact
    }

    @Synchronized
    fun getApproved(current: TransactionProposal?): PreparedHardwareProposal? =
        approval.value?.takeIf {
            it.proposal === current && walletWriteAccess.permits(it.walletGeneration) && canSign?.invoke() == true &&
                (current !is ShieldTransactionProposal || current.approval?.isCurrent() == true)
        }

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

    fun requireProofOwner(expected: PreparedHardwareProposal, current: TransactionProposal?) {
        check(getPrepared(current) === expected) { "The transaction changed before proof preparation." }
        expected.proposal.requireCurrentShieldingApproval()
    }

    @Synchronized
    fun clear() {
        approval.value = null
        canSign = null
        prepared = null
        submission.value = null
    }
}
