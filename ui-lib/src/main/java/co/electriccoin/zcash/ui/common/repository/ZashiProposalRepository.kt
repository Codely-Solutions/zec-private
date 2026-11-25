package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapComposition
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

interface ZashiProposalRepository {
    val transactionProposal: StateFlow<TransactionProposal?>

    val submitState: StateFlow<SubmitProposalState?>

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createProposal(zecSend: ZecSend): RegularTransactionProposal

    suspend fun createDonationProposal(zecSend: ZecSend): RegularTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
        account: WalletAccount,
    ): ExactInputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
        account: WalletAccount,
    ): ExactOutputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal(): ShieldTransactionProposal

    /**
     * Adopt an already-built IMMEDIATE send-max migration [proposal] as the current transaction, so
     * the migration sweep goes through the shared submit + Transaction Progress pipeline exactly
     * like a regular send (mirrors [KeystoneProposalRepository.setMigrationSweepProposal]).
     * Returns the exact adopted review identity for the authentication handoff.
     */
    fun setMigrationSweepProposal(proposal: Proposal): MigrationSweepTransactionProposal

    suspend fun submit(
        expectedProposal: TransactionProposal? = null,
        reviewedQuote: SwapQuoteData.Success? = null,
    ): SubmitResult

    suspend fun getTransactionProposal(): TransactionProposal

    fun clear()

    fun clearIfCurrent(proposal: TransactionProposal)
}

@Suppress("TooManyFunctions")
class ZashiProposalRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
) : ZashiProposalRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    override suspend fun createProposal(zecSend: ZecSend): RegularTransactionProposal =
        createProposalInternal {
            proposalDataSource.createProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend
            )
        }

    override suspend fun createDonationProposal(zecSend: ZecSend): RegularTransactionProposal =
        createProposalInternal {
            val account = accountDataSource.getSelectedAccount()
            require(account is co.electriccoin.zcash.ui.common.model.ZodlAccount)
            require(zecSend.destination.address == DonationPolicy.recipient())
            proposalDataSource.createProposal(account, zecSend).copy(isDonation = true)
        }

    override suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal =
        createProposalInternal(includeFailureCause = false) {
            proposalDataSource.createZip321Proposal(
                account = accountDataSource.getSelectedAccount(),
                zip321Uri = zip321Uri
            )
        }

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
        account: WalletAccount,
    ): ExactInputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactInputProposal(
                account = account,
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
        account: WalletAccount,
    ): ExactOutputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactOutputProposal(
                account = account,
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createShieldProposal(): ShieldTransactionProposal =
        createProposalInternal {
            proposalDataSource.createShieldProposal(
                account = accountDataSource.getSelectedAccount(),
            )
        }

    override fun setMigrationSweepProposal(proposal: Proposal): MigrationSweepTransactionProposal =
        MigrationSweepTransactionProposal(proposal).also { sweep -> transactionProposal.update { sweep } }

    @Suppress("TooGenericExceptionCaught", "UseCheckOrError")
    override suspend fun submit(
        expectedProposal: TransactionProposal?,
        reviewedQuote: SwapQuoteData.Success?,
    ): SubmitResult =
        scope
            .async {
                val transactionProposal = transactionProposal.value
                check(expectedProposal == null || transactionProposal === expectedProposal) {
                    "The reviewed transaction changed before submission."
                }
                if (transactionProposal == null) {
                    val cause = IllegalStateException("Transaction proposal is null")
                    submitState.update { SubmitProposalState.Result(SubmitResult.Error(cause)) }
                    throw cause
                } else {
                    submitState.update { SubmitProposalState.Submitting }
                    try {
                        val swapWasReviewed = transactionProposal !is SwapTransactionProposal ||
                            transactionProposal === expectedProposal
                        check(swapWasReviewed) {
                            "Review the prepared swap transaction before sending."
                        }
                        val spendingKey = zashiSpendingKeyDataSource.getZashiSpendingKey()
                        if (transactionProposal is RegularTransactionProposal && transactionProposal.isDonation) {
                            require(transactionProposal.destination.address ==
                                DonationPolicy.recipient())
                            DonationPolicy.requireShielded(
                                transactionProposal.proposal,
                                ZecSend(
                                    transactionProposal.destination,
                                    transactionProposal.amount,
                                    transactionProposal.memo,
                                    null
                                )
                            )
                        }
                        val validateFunding: () -> Unit = {
                            requireCurrentProposal(transactionProposal)
                            if (transactionProposal is SwapTransactionProposal) {
                                transactionProposal.requireFundingApproval(reviewedQuote)
                                ZecSwapComposition.validate(transactionProposal.quote)
                            }
                        }
                        val submit: suspend (() -> Unit) -> SubmitResult = { beforeSubmission ->
                            proposalDataSource.submitTransaction(
                                proposal = transactionProposal.proposal,
                                usk = spendingKey,
                                beforeFirstSubmission = beforeSubmission,
                            )
                        }
                        val result = if (transactionProposal is SwapTransactionProposal) {
                            processSwapTransaction.submitReservedFunding(transactionProposal, validateFunding, submit)
                        } else {
                            validateFunding()
                            submit(validateFunding)
                        }
                        publishOutcome(transactionProposal, result)
                        result
                    } catch (e: Exception) {
                        publishOutcome(transactionProposal, SubmitResult.Error(e))
                        throw e
                    }
                }
            }.await()

    private fun publishOutcome(expected: TransactionProposal, result: SubmitResult) {
        submitState.update { if (transactionProposal.value === expected) SubmitProposalState.Result(result) else it }
    }

    private fun requireCurrentProposal(expected: TransactionProposal) {
        check(transactionProposal.value === expected) { "The transaction changed before submission." }
    }

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun clear() {
        transactionProposal.update { null }
        submitState.update { null }
    }

    override fun clearIfCurrent(proposal: TransactionProposal) {
        if (transactionProposal.value === proposal && transactionProposal.compareAndSet(proposal, null)) {
            submitState.update { previous -> if (transactionProposal.value == null) null else previous }
        }
    }

    private suspend inline fun <T : TransactionProposal> createProposalInternal(
        includeFailureCause: Boolean = true,
        block: () -> T,
    ): T {
        val proposal =
            try {
                block()
            } catch (e: TransactionProposalNotCreatedException) {
                currentCoroutineContext().ensureActive()
                if (includeFailureCause) {
                    Twig.error(e) { "Unable to create proposal" }
                } else {
                    Twig.error { "Unable to create ZIP321 proposal" }
                }
                transactionProposal.update { null }
                throw e
            } catch (e: InsufficientFundsException) {
                currentCoroutineContext().ensureActive()
                if (includeFailureCause) {
                    Twig.error(e) { "Insufficient funds" }
                } else {
                    Twig.error { "Insufficient funds for ZIP321 proposal" }
                }
                transactionProposal.update { null }
                throw e
            }
        currentCoroutineContext().ensureActive()
        transactionProposal.update { proposal }
        return proposal
    }
}
