package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The proposal, device input, proofs and signatures stay with the same preparation. */
class PreparedHardwareProposal(val proposal: TransactionProposal, val pczt: Pczt) {
    val proofs = CompletableDeferred<Pczt>()

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
class HardwareProposalBinding {
    private var prepared: PreparedHardwareProposal? = null
    private var approved: PreparedHardwareProposal? = null

    @Synchronized
    fun prepare(proposal: TransactionProposal, pczt: Pczt): PreparedHardwareProposal {
        approved = null
        return PreparedHardwareProposal(proposal, pczt).also { prepared = it }
    }

    @Synchronized
    fun approve(current: TransactionProposal?, expected: TransactionProposal?) {
        val artifact = checkNotNull(prepared) { "The transaction has no prepared PCZT." }
        check(current === artifact.proposal && (expected == null || expected === current)) {
            "The reviewed transaction changed before signing."
        }
        check(current !is SwapTransactionProposal || current === expected) {
            "Review the prepared swap transaction before signing."
        }
        approved = artifact
    }

    @Synchronized
    fun getApproved(current: TransactionProposal?): PreparedHardwareProposal? =
        approved?.takeIf { it.proposal === current }

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
        prepared?.takeIf { it.proposal === current }

    @Synchronized
    fun clear() {
        approved = null
        prepared = null
    }
}
