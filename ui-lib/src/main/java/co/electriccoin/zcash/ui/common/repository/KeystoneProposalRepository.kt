package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwarePolicy
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapComposition
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
    val transactionProposal: StateFlow<TransactionProposal?>

    val submitState: StateFlow<SubmitProposalState?>

    val signingPreparationIds: Flow<String?>

    fun signingPreparationId(): String?

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createProposal(zecSend: ZecSend)

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactInputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactInputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createExactOutputSwapProposal(zecSend: ZecSend, quote: SwapQuote): ExactOutputSwapTransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createZip321Proposal(zip321Uri: String): Zip321TransactionProposal

    @Throws(TransactionProposalNotCreatedException::class, InsufficientFundsException::class)
    suspend fun createShieldProposal()

    /**
     * Adopts an already-built migration send-max [Proposal] (see
     * [cash.z.ecc.android.sdk.OrchardMigrationSdk.proposeImmediateMigration]) so [createPCZTFromProposal]
     * can build a Keystone PCZT from it exactly as it would from any other proposal.
     */
    fun setMigrationSweepProposal(proposal: Proposal, amount: Zatoshi)

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

    suspend fun submit(expectedProposal: TransactionProposal? = null, preparationId: String? = null): SubmitResult

    fun clear()

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
) : KeystoneProposalRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    private val hardwareProposal = HardwareProposalBinding()

    override val signingPreparationIds = combine(transactionProposal, hardwareProposal.approved) { _, _ ->
        signingPreparationId()
    }

    override fun signingPreparationId(): String? = hardwareProposal.getApproved(transactionProposal.value)?.id

    private var pcztWithProofsJob: Job? = null

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
    ): ExactInputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactInputProposal(
                account = accountDataSource.getSelectedAccount(),
                send = zecSend,
                quote = quote
            )
        }

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal =
        createProposalInternal {
            proposalDataSource.createExactOutputProposal(
                account = accountDataSource.getSelectedAccount(),
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

    override suspend fun createShieldProposal() {
        createProposalInternal {
            proposalDataSource.createShieldProposal(
                account = accountDataSource.getSelectedAccount(),
            )
        }
    }

    override fun setMigrationSweepProposal(proposal: Proposal, amount: Zatoshi) {
        transactionProposal.update { MigrationSweepTransactionProposal(amount, proposal) }
    }

    override suspend fun createPCZTFromProposal() {
        val transactionProposal = getTransactionProposal()
        val result =
            try {
                proposalDataSource.createPcztFromProposal(
                    account = accountDataSource.getSelectedAccount(),
                    proposal = transactionProposal.proposal
                )
            } catch (e: PcztException.MultiStepProposalUnsupportedException) {
                if (transactionProposal.paysTexAddress()) throw TexUnsupportedOnKSException() else throw e
            }
        currentCoroutineContext().ensureActive()
        check(this.transactionProposal.value === transactionProposal) { "The transaction changed during preparation." }
        val prepared = hardwareProposal.prepare(transactionProposal, result)
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
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction.reserveFunding(proposal) {
                        requireCurrentApproval(prepared)
                        proposal.requireFundingApproval(prepared.reviewedQuote)
                        ZecSwapComposition.validate(proposal.quote)
                    }
                    proposal.requireFundingApproval(prepared.reviewedQuote)
                }
                requireCurrentApproval(prepared)
                val result =
                    proposalDataSource.submitTransaction(
                        pcztWithProofs = proofs,
                        pcztWithSignatures = signed,
                        beforeFirstSubmission = {
                            requireCurrentApproval(prepared)
                            if (proposal is SwapTransactionProposal) {
                                proposal.requireFundingApproval(prepared.reviewedQuote)
                                ZecSwapComposition.validate(proposal.quote)
                            }
                        }
                    )
                submitState.update { SubmitProposalState.Result(result) }
                result
            } catch (e: Exception) {
                submitState.update { SubmitProposalState.Result(SubmitResult.Error(e)) }
                throw e
            }
        }.await()

    private fun requireCurrentApproval(prepared: PreparedHardwareProposal) {
        check(hardwareProposal.getApproved(transactionProposal.value) === prepared) {
            "The reviewed transaction changed before submission."
        }
    }

    override suspend fun getTransactionProposal(): TransactionProposal = transactionProposal.filterNotNull().first()

    override fun getProposalPCZT(): Pczt? = hardwareProposal.getPrepared(transactionProposal.value)?.pczt

    override fun clear() {
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = null

        transactionProposal.update { null }
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
