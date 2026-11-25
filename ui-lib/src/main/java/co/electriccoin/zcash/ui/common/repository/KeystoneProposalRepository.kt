package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.requireCurrentShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.PaymentDraftOwner
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.preparePayment
import co.electriccoin.zcash.ui.common.datasource.publishOwnedPayment
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwarePolicy
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapComposition
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.model.readKeystoneFwStamp
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKException
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
interface KeystoneProposalRepository {
    suspend fun preparePayment(draft: PaymentDraft, owner: PaymentDraftOwner): SendTransactionProposal

    val transactionProposal: StateFlow<TransactionProposal?>

    val submitState: StateFlow<SubmitProposalState?>

    val signingPreparationIds: Flow<String?>

    fun signingPreparationId(): String?

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createProposal(zecSend: ZecSend)

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
    suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal(approval: ShieldingApproval): ShieldTransactionProposal

    /**
     * Adopts an already-built migration send-max [Proposal] (see
     * [cash.z.ecc.android.sdk.OrchardMigrationSdk.proposeImmediateMigration]) so [createPCZTFromProposal]
     * can build a Keystone PCZT from it exactly as it would from any other proposal.
     */
    fun setMigrationSweepProposal(proposal: Proposal): MigrationSweepTransactionProposal

    /**
     * The SDK rejects any multi-step proposal for PCZT generation. That rejection is surfaced as
     * [TexUnsupportedOnKSException] only when the current proposal actually pays a TEX (ZIP-320)
     * address — the one flow where a multi-step proposal is the expected shape. For every other
     * proposal (shielding, migration sweeps), the raw
     * [PcztException.MultiStepProposalUnsupportedException] propagates so a TEX-specific error can
     * never be shown for a failure unrelated to TEX.
     */
    @Throws(
        PcztException.CreatePcztFromProposalException::class,
        PcztException.MultiStepProposalUnsupportedException::class,
        TexUnsupportedOnKSException::class
    )
    suspend fun createPCZTFromProposal()

    suspend fun createPCZTFromProposal(account: WalletAccount)

    fun approveForSigning(
        expectedProposal: TransactionProposal? = null,
        reviewedQuote: SwapQuoteData.Success? = null,
    )

    @Throws(IllegalStateException::class)
    suspend fun createPCZTEncoder(): UREncoder

    /**
     * Parses a Keystone-signed PCZT and enforces the firmware minimum-version gate (MOB-1510).
     * Firmware >= 2.4.6 stamps its raw internal version into the signed PCZT's proprietary
     * fields; this normalizes that stamp to display numbering and refuses signatures from
     * firmware below [KeystoneFirmwareVersion.MINIMUM_SUPPORTED] (or too old to stamp a version
     * at all) before they can reach submission.
     */
    @Throws(ParsePCZTException::class)
    suspend fun parsePCZT(ur: UR, preparationId: String): TransactionProposal

    fun signingOwner(expectedProposal: TransactionProposal): AccountUuid?

    suspend fun submit(expectedProposal: TransactionProposal? = null, preparationId: String? = null): SubmitResult

    fun clear(expected: TransactionProposal? = null)

    suspend fun getTransactionProposal(): TransactionProposal

    fun getProposalPCZT(): Pczt?
}

class ParsePCZTException : Exception()

class MissingKeystoneSigningPreparation : IllegalStateException("The signing session is unavailable.")

/**
 * The scanned signed PCZT came from Keystone firmware below
 * [KeystoneFirmwareVersion.MINIMUM_SUPPORTED]. [detected] is `null` when the firmware is too old
 * to stamp its version at all.
 */
class KeystoneFirmwareBelowMinimumException(
    val detected: KeystoneFirmwareVersion?
) : Exception(
        "Keystone firmware ${detected ?: "unstamped"} is below minimum supported " +
            "${KeystoneFirmwareVersion.MINIMUM_SUPPORTED}"
    )

sealed interface SubmitProposalState {
    data object Submitting : SubmitProposalState

    data class Result(
        val submitResult: SubmitResult
    ) : SubmitProposalState
}

@Suppress("TooManyFunctions")
class KeystoneProposalRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val keystoneSDKProvider: KeystoneSDKProvider,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) : KeystoneProposalRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    private val hardwareProposal = HardwareProposalBinding(walletWriteAccess)

    override val signingPreparationIds = combine(transactionProposal, hardwareProposal.approved) { _, _ ->
        signingPreparationId()
    }

    override fun signingPreparationId(): String? = hardwareProposal.getApproved(transactionProposal.value)?.id

    private var pcztWithProofsJob: Job? = null

    override suspend fun preparePayment(draft: PaymentDraft, owner: PaymentDraftOwner): SendTransactionProposal {
        val previous = transactionProposal.value
        val proposal = proposalDataSource.preparePayment(owner, draft)
        owner.requireCurrent()
        val pczt = createReviewPczt(proposal, owner.account)
        transactionProposal.publishOwnedPayment(owner, previous, proposal)
        prepareSigning(proposal, pczt, owner.account)
        return proposal
    }

    override suspend fun createProposal(zecSend: ZecSend) {
        createProposalInternal {
            proposalDataSource.createProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend
            )
        }
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

    override suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal =
        createProposalInternal(includeFailureCause = false) {
            proposalDataSource.createZip321Proposal(
                account = accountDataSource.getSelectedAccount(),
                zip321Uri = zip321Uri
            )
        }

    override suspend fun createShieldProposal(approval: ShieldingApproval): ShieldTransactionProposal {
        val preceding = transactionProposal.value
        approval.requireCurrent()
        val shield = proposalDataSource.createShieldProposal(approval.account).copy(approval = approval)
        currentCoroutineContext().ensureActive()
        approval.requireCurrent()
        check(transactionProposal.value === preceding && transactionProposal.compareAndSet(preceding, shield)) {
            "The transaction changed during shielding preparation."
        }
        return shield
    }

    override fun setMigrationSweepProposal(proposal: Proposal): MigrationSweepTransactionProposal =
        MigrationSweepTransactionProposal(proposal).also { sweep -> transactionProposal.update { sweep } }

    override suspend fun createPCZTFromProposal() =
        createAccountBoundPczt { accountDataSource.getSelectedAccount() }

    override suspend fun createPCZTFromProposal(account: WalletAccount) = createAccountBoundPczt { account }

    private suspend fun createAccountBoundPczt(resolveAccount: suspend () -> WalletAccount) {
        val transactionProposal = getTransactionProposal()
        transactionProposal.requireCurrentShieldingApproval()
        val owner = resolveAccount()
        val result = createReviewPczt(transactionProposal, owner)
        currentCoroutineContext().ensureActive()
        check(this.transactionProposal.value === transactionProposal) { "The transaction changed during preparation." }
        prepareSigning(transactionProposal, result, owner)
    }

    private suspend fun createReviewPczt(proposal: TransactionProposal, owner: WalletAccount): Pczt =
        try {
            proposalDataSource.createPcztFromProposal(account = owner, proposal = proposal.proposal)
        } catch (e: PcztException.MultiStepProposalUnsupportedException) {
            if (proposal.paysTexAddress()) throw TexUnsupportedOnKSException() else throw e
        }

    private fun prepareSigning(proposal: TransactionProposal, result: Pczt, owner: WalletAccount) {
        val prepared = hardwareProposal.prepare(proposal, result, owner.sdkAccount.accountUuid)
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = prepared.startProofs(scope, proposalDataSource::addProofsToPczt)
    }


    override fun approveForSigning(expectedProposal: TransactionProposal?, reviewedQuote: SwapQuoteData.Success?) =
        hardwareProposal.approve(transactionProposal.value, expectedProposal, reviewedQuote)

    private fun TransactionProposal.paysTexAddress(): Boolean =
        this is SendTransactionProposal && destination is WalletAddress.Tex

    override suspend fun createPCZTEncoder(): UREncoder =
        withContext(Dispatchers.IO) {
            val prepared = hardwareProposal.requireApproved(transactionProposal.value)
            val redactedPczt = proposalDataSource.redactPcztForSigner(prepared.pczt.clonePczt())
            try {
                check(hardwareProposal.getApproved(transactionProposal.value) === prepared) {
                    "The reviewed transaction changed before signing."
                }
                keystoneSDKProvider.generatePczt(pczt = redactedPczt.toByteArray())
            } catch (e: KeystoneSDKException) {
                throw IllegalStateException("Failed to generate PCZT encoder", e)
            }
        }

    override suspend fun parsePCZT(ur: UR, preparationId: String) =
        withContext(Dispatchers.IO) {
            val prepared = hardwareProposal.getApproved(transactionProposal.value)
                ?.takeIf { it.id == preparationId } ?: throw MissingKeystoneSigningPreparation()
            val parsed =
                try {
                    keystoneSDKProvider.parsePczt(ur)
                } catch (_: Exception) {
                    throw ParsePCZTException()
                }

            val stamp = parsed.readKeystoneFwStamp()
            val detected = stamp?.let(KeystoneFirmwareVersion::fromStamp)
            val outcome = KeystoneFirmwarePolicy.evaluate(detected, KeystoneFirmwareVersion.MINIMUM_SUPPORTED)
            val logMessage = {
                "Keystone firmware on signed PCZT: raw stamp ${stamp ?: "absent"}, normalized " +
                    "${detected ?: "unknown"} (required ${KeystoneFirmwareVersion.MINIMUM_SUPPORTED}) -> $outcome"
            }
            if (outcome != KeystoneFirmwarePolicy.Outcome.OK) {
                Twig.warn(logMessage)
                throw KeystoneFirmwareBelowMinimumException(detected)
            } else {
                Twig.info(logMessage)
            }

            if (hardwareProposal.getApproved(transactionProposal.value) !== prepared) {
                throw MissingKeystoneSigningPreparation()
            }
            prepared.signatures = Pczt(parsed)
            prepared.proposal
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(expectedProposal: TransactionProposal?, preparationId: String?): SubmitResult =
        scope.async {
            try {
                val prepared = hardwareProposal.requireApproved(transactionProposal.value, expectedProposal)
                if (preparationId != null && prepared.id != preparationId) throw MissingKeystoneSigningPreparation()
                val signed = checkNotNull(prepared.signatures) { "The reviewed transaction has not been signed." }
                submitState.update { SubmitProposalState.Submitting }
                val proofs = prepared.proofs.await()
                val proposal = prepared.proposal
                val validateFunding: () -> Unit = {
                    proposal.requireCurrentShieldingApproval()
                    requireCurrentApproval(prepared)
                    if (proposal is SwapTransactionProposal) {
                        proposal.requireFundingApproval(prepared.reviewedQuote)
                        ZecSwapComposition.validate(proposal.quote)
                    }
                }
                val submit: suspend (() -> Unit, () -> Unit) -> SubmitResult = { beforeCreation, beforeSubmission ->
                    proposalDataSource.submitTransaction(
                        pcztWithProofs = proofs,
                        pcztWithSignatures = signed,
                        beforeCreation = beforeCreation,
                        beforeFirstSubmission = beforeSubmission,
                    )
                }
                val result = if (proposal is SwapTransactionProposal) {
                    processSwapTransaction.submitReservedFunding(proposal, validateFunding, submit)
                } else {
                    validateFunding()
                    submit(validateFunding, validateFunding)
                }
                submitState.update { SubmitProposalState.Result(result) }
                result
            } catch (e: Exception) {
                submitState.update { SubmitProposalState.Result(SubmitResult.Error(e)) }
                throw e
            }
        }.await()

    override fun signingOwner(expectedProposal: TransactionProposal): AccountUuid? =
        hardwareProposal.getApproved(transactionProposal.value)?.takeIf { it.proposal === expectedProposal }?.owner

    private fun requireCurrentApproval(prepared: PreparedHardwareProposal) {
        check(hardwareProposal.getApproved(transactionProposal.value) === prepared) {
            "The reviewed transaction changed before submission."
        }
    }

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun getProposalPCZT(): Pczt? = hardwareProposal.getPrepared(transactionProposal.value)?.pczt

    override fun clear(expected: TransactionProposal?) {
        if (expected != null && (transactionProposal.value !== expected ||
                !transactionProposal.compareAndSet(expected, null))
        ) return
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = null

        if (expected == null) transactionProposal.update { null }
        submitState.update { null }
        hardwareProposal.clear()
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
