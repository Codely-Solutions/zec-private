package co.electriccoin.zcash.ui.common.donation

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.isZCashAsset
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Only enrolled future swaps can consume the narrowly scoped automatic spending authorization. */
@Suppress("TooGenericExceptionCaught", "MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LongMethod")
class AutoDonationCoordinator(
    private val store: AutoDonationStore,
    private val accounts: AccountDataSource,
    private val proposals: ProposalDataSource,
    private val keys: ZashiSpendingKeyDataSource,
    private val synchronizers: SynchronizerProvider,
    private val uiProposals: ZashiProposalRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    suspend fun enroll(quote: SwapQuote, depositTxIds: List<String> = emptyList()) {
        try {
            enrollInternal(quote, depositTxIds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Failure to persist a donation enrollment must leave the swap usable.
        }
    }

    private suspend fun enrollInternal(quote: SwapQuote, depositTxIds: List<String>) {
        val consent = store.consent() ?: return
        if (quote.timestamp.epochSeconds <= consent.authorizedAtSeconds) return
        val account = accounts.getSelectedAccount() as? ZodlAccount ?: return
        if (account.sdkAccount.accountUuid.toString() != consent.accountUuid) return
        if (!quote.originAsset.isZCashAsset && !quote.destinationAsset.isZCashAsset) return

        val scanHeight = if (quote.destinationAsset.isZCashAsset) {
            synchronizers.getSynchronizer().fullyScannedHeight.value?.value ?: return
        } else 0L
        store.enroll(quote.depositAddress.address, JSONObject().put("consent", consent.id)
            .put("account", consent.accountUuid).put("incoming", quote.destinationAsset.isZCashAsset)
            .put("destination", store.hash(quote.destinationAddress.address)).put("scanHeight", scanHeight)
            .put("txids", JSONArray(depositTxIds.map(store::hash))))
    }

    fun onStatus(status: SwapQuoteStatus) {
        if (status.status != SwapStatus.SUCCESS) return
        val key = status.depositAddress.address
        val enrollment = runCatching { store.reserve(key) }.getOrNull() ?: return
        scope.launch {
            val diagnostic = AttemptDiagnostic(store)
            try {
                // Leave margin for the SDK's ten-confirmation receipt policy.
                val reason = withTimeoutOrNull(30 * 60 * 1000L) {
                    donate(status, enrollment, diagnostic)
                } ?: "Automatic donation outcome uncertain. Check Activity."
                store.outcome(key, reason)
            } catch (e: CancellationException) {
                diagnostic.failure(e)
                store.outcome(key, "Automatic donation outcome uncertain. Check Activity.")
                throw e
            } catch (e: Exception) {
                diagnostic.failure(e)
                store.outcome(key, "Automatic donation outcome uncertain. Check Activity.")
            }
        }
    }

    private fun hasActiveUiSpend(): Boolean = uiProposals.transactionProposal.value != null &&
        (uiProposals.submitState.value as? co.electriccoin.zcash.ui.common.repository.SubmitProposalState.Result)
            ?.submitResult !is SubmitResult.Success

    private suspend fun account(enrollment: JSONObject): ZodlAccount? {
        val consent = store.consent() ?: return null
        if (consent.id != enrollment.getString("consent") ||
            consent.accountUuid != enrollment.getString("account")) return null
        return accounts.getZashiAccount().takeIf { it.sdkAccount.accountUuid.toString() == consent.accountUuid }
    }

    private fun settledInput(status: SwapQuoteStatus, deposited: Long): Long? {
        val settled = status.executedAmountIn?.movePointRight(8)?.longValueExact() ?: return null
        if (settled <= 0 || settled > deposited) return null
        val refund = status.refundedFormatted?.movePointRight(8)?.longValueExact()
        if (refund == null) return settled
        if (refund < 0 || refund > deposited) return null
        if (deposited - refund != settled) return null
        return settled
    }

    private fun nativeBasis(transactions: List<TransactionOverview>, incoming: Boolean): Long? {
        if (incoming) return transactions.fold(0L) { sum, tx -> Math.addExact(sum, tx.netValue.value) }
        if (transactions.any { it.feePaid == null }) return null
        return transactions.fold(0L) { sum, tx ->
            Math.addExact(sum, Math.subtractExact(tx.netValue.value, requireNotNull(tx.feePaid).value))
        }
    }

    private suspend fun donate(
        status: SwapQuoteStatus, enrollment: JSONObject, diagnostic: AttemptDiagnostic
    ): String {
        diagnostic.stage = "native_evidence"
        val incoming = enrollment.getBoolean("incoming")
        val txids = if (incoming) status.destinationTransactionIds.map(store::hash) else {
            val values = enrollment.getJSONArray("txids")
            (0 until values.length()).map { values.getString(it) }
        }
        if (txids.isEmpty() || txids.distinct().size != txids.size) return "Skipped: missing transaction evidence"
        if (enrollment.getString("destination") != store.hash(status.destinationAddress.address)) {
            return "Skipped: destination changed"
        }
        val synchronizer = synchronizers.getSynchronizer()
        var basis: Long
        while (true) {
            val account = account(enrollment) ?: return "Skipped: authorization unavailable"
            if (hasActiveUiSpend()) return "Skipped: another transaction is under review"
            val history = synchronizer.getTransactions(account.sdkAccount.accountUuid).first()
            val matching = history.filter { store.hash(it.txId.txIdString()) in txids }
            if (matching.size == txids.size && matching.all { it.transactionState == TransactionState.Confirmed }) {
                if (incoming && matching.any { (it.minedHeight?.value ?: 0L) <= enrollment.getLong("scanHeight") }) {
                    return "Skipped: receipt predates swap enrollment"
                }
                if (matching.any { it.isSentTransaction == incoming }) return "Skipped: transaction direction differs"
                basis = nativeBasis(matching, incoming) ?: return "Skipped: missing network fee evidence"
                val evidence = if (incoming) status.executedAmountOut else status.depositedAmountFormatted
                val actual = evidence?.let { runCatching { it.movePointRight(8).longValueExact() }.getOrNull() }
                    ?: return "Skipped: missing executed amount evidence"
                if (basis != actual || basis <= 0L) return "Skipped: received or spent amount differs"
                if (!incoming) {
                    basis = settledInput(status, basis) ?: return "Skipped: inconsistent settled input or refund"
                }
                break
            }
            delay(5_000)
        }
        var failedPreparation: ReadySnapshot? = null
        var attempts = 0
        while (true) {
            diagnostic.stage = "preparation_readiness"
            val ready = withTimeoutOrNull(120_000L) {
                while (true) {
                    if (account(enrollment) == null) return@withTimeoutOrNull null
                    if (hasActiveUiSpend()) return@withTimeoutOrNull null
                    val current = readiness()
                    val failed = failedPreparation
                    if (current != null && (failed == null || current.synchronizer !== failed.synchronizer ||
                            current.scanned > failed.scanned)) return@withTimeoutOrNull current
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } ?: return if (account(enrollment) == null) "Skipped: authorization unavailable"
                else if (hasActiveUiSpend()) "Skipped: another transaction is under review"
                else "Skipped: proposal preparation did not become ready"
            diagnostic.stage = "spending_gate"
            val outcome = AutoDonationSpendingGate.mutex.withLock {
                // Readiness can change while a manual spend holds the gate.
                val current = readiness()
                if (current == null || current.synchronizer !== ready.synchronizer ||
                    current.scanned != ready.scanned) return@withLock null
                if (hasActiveUiSpend()) return@withLock "Skipped: another transaction is under review"
                val consent = store.consent() ?: return@withLock "Skipped: authorization unavailable"
                val amount = DonationPolicy.suggestedZatoshi(BigDecimal.valueOf(basis, 8), consent.percent)
                if (amount <= 0) return@withLock "Skipped: donation rounds to zero"
                val recipient = DonationPolicy.recipient() ?: return@withLock "Skipped: recipient unavailable"
                val account = account(enrollment) ?: return@withLock "Skipped: authorization unavailable"
                if ((account.spendableShieldedBalance?.value ?: 0L) <= amount) {
                    return@withLock "Skipped: insufficient shielded funds"
                }
                diagnostic.stage = "recipient"
                val send = ZecSend(WalletAddress.Unified.new(recipient), Zatoshi(amount), Memo(""), null)
                diagnostic.stage = "proposal"
                attempts++
                val proposal = try {
                    proposals.createProposal(account, send)
                } catch (e: InsufficientFundsException) {
                    diagnostic.failure(e)
                    failedPreparation = ready
                    if (attempts >= 3) return@withLock "Skipped: insufficient funds during proposal preparation"
                    return@withLock null
                }
                diagnostic.stage = "shielded_validation"
                DonationPolicy.requireShielded(proposal.proposal, send)
                val fee = proposal.proposal.totalFeeRequired().value
                if (fee >= amount) return@withLock "Skipped: network fee is at least the donation"
                if ((account(enrollment)?.spendableShieldedBalance?.value ?: 0L) < Math.addExact(amount, fee)) {
                    return@withLock "Skipped: insufficient shielded funds"
                }
                if (hasActiveUiSpend()) return@withLock "Skipped: another transaction is under review"
                if (account(enrollment) == null) return@withLock "Skipped: authorization unavailable"
                diagnostic.stage = "spending_key"
                val key = keys.getZashiSpendingKey()
                if (account(enrollment) == null || hasActiveUiSpend()) return@withLock "Skipped: authorization changed"
                DonationPolicy.requireShielded(proposal.proposal, send)
                diagnostic.stage = "submission"
                val result = proposals.submitTransaction(proposal.proposal, key)
                diagnostic.submission(result)
                diagnostic.stage = "record_transaction"
                store.recordTransactionIds(status.depositAddress.address, result.txIds)
                if (result is SubmitResult.Success) {
                    val displayAmount = BigDecimal.valueOf(amount, 8).stripTrailingZeros().toPlainString()
                    "Automatic donation of $displayAmount ZEC submitted."
                } else {
                    "Automatic donation outcome uncertain. Check Activity."
                }
            }
            if (outcome != null) return outcome
        }
    }

    private data class ReadySnapshot(val synchronizer: Synchronizer, val scanned: Long)

    private suspend fun readiness(): ReadySnapshot? {
        val current = synchronizers.getSynchronizer()
        val scanned = current.fullyScannedHeight.value?.value ?: return null
        val network = current.networkHeight.value?.value ?: return null
        if (current.status.value != Synchronizer.Status.SYNCED || scanned < network) return null
        return ReadySnapshot(current, scanned)
    }

    /** Fixed stages and classes only; never exception messages or transaction/provider contents. */
    private class AttemptDiagnostic(private val store: AutoDonationStore) {
        var stage = "reserved"
        fun failure(error: Exception) = store.diagnostic(JSONObject().put("stage", stage)
            .put("result", "exception").put("exception_class", error.javaClass.simpleName)
            .put("cause_classes", JSONArray(generateSequence(error as Throwable) { it.cause }
                .take(4).map { it.javaClass.simpleName }.toList())))
        fun submission(result: SubmitResult) {
            val value = JSONObject().put("stage", stage).put("result", result.javaClass.simpleName)
            when (result) {
                is SubmitResult.Failure -> value.put("sdk_code", result.code)
                is SubmitResult.Error -> value.put("exception_class", result.cause.javaClass.simpleName)
                is SubmitResult.GrpcFailure -> value.put("reason", result.reason?.name)
                else -> Unit
            }
            store.diagnostic(value)
        }
    }

}
