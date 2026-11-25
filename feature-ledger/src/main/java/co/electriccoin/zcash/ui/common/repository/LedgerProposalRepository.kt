package co.electriccoin.zcash.ui.common.repository

import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.ledger.LedgerBluetoothDevice
import cash.z.ecc.android.sdk.ledger.LedgerSigningProgress
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.LedgerBindingUnusableException
import co.electriccoin.zcash.ui.common.datasource.LedgerDeviceDataSource
import co.electriccoin.zcash.ui.common.datasource.LedgerLinkMissingException
import co.electriccoin.zcash.ui.common.datasource.LedgerSigningDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.LedgerIssue
import co.electriccoin.zcash.ui.common.model.LedgerIssueContext
import co.electriccoin.zcash.ui.common.model.LedgerSigningDevice
import co.electriccoin.zcash.ui.common.model.LedgerSigningState
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.ZecSwapComposition
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.model.toLedgerIssue
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.LEDGER_SCAN_TIMEOUT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The proposal of a Ledger account and the session that signs it on the device.
 *
 * It mirrors [KeystoneProposalRepository] without the QR-code transport: the PCZT is created with
 * the proposal, its proofs start computing straight away, and the device signs the same unredacted
 * PCZT concurrently. Cancelling a session keeps the proposal, the PCZT and the running proofs, so
 * confirming again reuses them.
 *
 * ui-lib drives the proposal through [LedgerProposalPipeline]; the signing members below are
 * reached only by the Ledger screens and use cases.
 */
interface LedgerProposalRepository : LedgerProposalPipeline {
    /**
     * Null while no session runs.
     */
    val signingState: StateFlow<LedgerSigningState?>

    /**
     * Starts a signing session unless one is running: over the link a previous attempt kept open,
     * or by looking for the device.
     */
    fun startSigning()

    /**
     * Picks the device to sign with while the session is [LedgerSigningState.Selecting].
     */
    fun selectDevice(identifier: String)

    /**
     * Starts the session again after [LedgerSigningState.Failed].
     */
    fun retry()

    /**
     * Stops the session and closes the link; the proposal, the PCZT and the proofs stay.
     */
    fun cancelSigning()

    /**
     * Fails a session that reached [LedgerSigningState.Signed] but has no proposal left to submit,
     * with an issue that offers no retry, so the sheet leaves Cancel Transaction as the way out.
     */
    fun failSignedSessionWithoutProposal()

    fun signingOwner(expectedProposal: TransactionProposal): AccountUuid?

    suspend fun submit(expectedProposal: TransactionProposal? = null): SubmitResult

    fun getProposalPCZT(): Pczt?
}

@Suppress("TooManyFunctions")
class LedgerProposalRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val ledgerDeviceDataSource: LedgerDeviceDataSource,
    private val ledgerSigningDataSource: LedgerSigningDataSource,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
) : LedgerProposalRepository {
    @VisibleForTesting
    internal var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val transactionProposal = MutableStateFlow<TransactionProposal?>(null)

    override val submitState = MutableStateFlow<SubmitProposalState?>(null)

    override val signingState = MutableStateFlow<LedgerSigningState?>(null)


    private val hardwareProposal = HardwareProposalBinding()

    private var pcztWithProofsJob: Job? = null

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var closeJob: Job? = null

    @Volatile
    private var selection: CompletableDeferred<String>? = null

    /**
     * Guards [generation] together with every state write that depends on it.
     *
     * A monitor rather than a Mutex: [startSigning], [cancelSigning] and
     * [failSignedSessionWithoutProposal] take it from the UI without a coroutine, and no guarded
     * block suspends, so a session coroutine never holds it across a suspension point.
     */
    private val lock = Any()

    /**
     * Bumped whenever a session starts or is cancelled; a session only publishes state while its
     * own generation is current, so one that is still unwinding cannot overwrite its successor.
     */
    @Volatile
    private var generation = 0

    /**
     * Set when the last session connected to a Ledger other than the account's; the next scan then
     * offers the picker even for a lone device instead of reconnecting to the same wrong one.
     */
    @Volatile
    private var isPickerRequired = false

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

    override suspend fun createShieldProposal(): ShieldTransactionProposal =
        createProposalInternal {
            proposalDataSource.createShieldProposal(
                account = accountDataSource.getSelectedAccount(),
            )
        }

    override suspend fun createPCZTFromProposal() {
        val transactionProposal = getTransactionProposal()
        val owner = (transactionProposal as? SwapTransactionProposal)?.fundingAccount
            ?: accountDataSource.getSelectedAccount()
        val result =
            try {
                proposalDataSource.createPcztFromProposal(
                    account = owner,
                    proposal = transactionProposal.proposal
                )
            } catch (e: PcztException.MultiStepProposalUnsupportedException) {
                if (transactionProposal.paysTexAddress()) throw TexUnsupportedOnKSException() else throw e
            }
        currentCoroutineContext().ensureActive()
        check(this.transactionProposal.value === transactionProposal) { "The transaction changed during preparation." }
        cancelSigning()
        submitState.update { null }
        val prepared = hardwareProposal.prepare(transactionProposal, result, owner.sdkAccount.accountUuid)
        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = prepared.startProofs(scope, proposalDataSource::addProofsToPczt)
    }

    override fun approveForSigning(expectedProposal: TransactionProposal?, reviewedQuote: SwapQuoteData.Success?) =
        hardwareProposal.approve(transactionProposal.value, expectedProposal, reviewedQuote)

    private fun TransactionProposal.paysTexAddress(): Boolean =
        this is SendTransactionProposal && destination is WalletAddress.Tex

    override fun startSigning() {
        if (sessionJob?.isActive == true) return
        val prepared = hardwareProposal.getApproved(transactionProposal.value)
        if (prepared == null) {
            Twig.warn { "Ledger signing: no PCZT to sign" }
            signingState.update { LedgerSigningState.Failed(LedgerIssue.unknownWithoutRetry) }
            return
        }
        val pendingClose = closeJob
        val session = synchronized(lock) { ++generation }
        sessionJob =
            scope.launch {
                pendingClose?.join()
                runSession(session, prepared)
            }
    }

    private fun publish(
        session: Int,
        state: LedgerSigningState
    ) {
        synchronized(lock) {
            if (generation == session) signingState.value = state
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runSession(
        session: Int,
        prepared: PreparedHardwareProposal
    ) {
        try {
            val account = accountDataSource.getSelectedAccount()
            if (account !is LedgerAccount || !account.isBound) {
                Twig.warn { "Ledger signing: the selected account has no Ledger binding" }
                publish(session, LedgerSigningState.Failed(LedgerIssue.unbound))
                return
            }
            if (ledgerSigningDataSource.isLinked || scanAndConnect(session)) {
                sign(session, prepared, account)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LedgerException.DeviceMismatch) {
            isPickerRequired = true
            publishLedgerFailure(session, e)
        } catch (e: LedgerException) {
            publishLedgerFailure(session, e)
        } catch (e: LedgerLinkMissingException) {
            Twig.warn { "Ledger signing: session failed with ${e.javaClass.simpleName}" }
            publish(session, LedgerSigningState.Failed(LedgerIssue.disconnectedWhileSigning))
        } catch (e: LedgerBindingUnusableException) {
            Twig.warn { "Ledger signing: session failed with ${e.javaClass.simpleName}" }
            publish(session, LedgerSigningState.Failed(LedgerIssue.unbound))
        } catch (e: Exception) {
            Twig.warn { "Ledger signing: session failed with ${e.javaClass.simpleName}" }
            publish(session, LedgerSigningState.Failed(LedgerIssue.unknown))
        }
    }

    /**
     * Looks for the device and connects to it. Returns false once the session has failed on the way.
     */
    private suspend fun scanAndConnect(session: Int): Boolean {
        val device = scan(session) ?: return false
        return connect(session, device)
    }

    /**
     * Connects to [device] within [CONNECT_TIMEOUT], the cap enrollment puts on its pairing, so a
     * device that never finishes opening the Zcash app cannot hold the sheet on Connecting. Returns
     * false once that cap passed, with the link closed and the session failed as a disconnect.
     *
     * [LedgerSigningState.OpeningZcashApp] is shown only while this session still is on
     * [LedgerSigningState.Connecting].
     */
    private suspend fun connect(
        session: Int,
        device: LedgerBluetoothDevice
    ): Boolean {
        Twig.info { "Ledger signing: stage Connecting" }
        publish(session, LedgerSigningState.Connecting)
        return try {
            withTimeout(CONNECT_TIMEOUT) {
                ledgerSigningDataSource.connect(device) { publishOpeningZcashApp(session) }
            }
            true
        } catch (_: TimeoutCancellationException) {
            Twig.warn { "Ledger signing: connecting timed out" }
            ledgerSigningDataSource.close()
            publish(session, LedgerSigningState.Failed(LedgerIssue.disconnectedWhileSigning))
            false
        }
    }

    private fun publishOpeningZcashApp(session: Int) {
        synchronized(lock) {
            if (generation == session && signingState.value == LedgerSigningState.Connecting) {
                Twig.info { "Ledger signing: stage OpeningZcashApp" }
                signingState.value = LedgerSigningState.OpeningZcashApp
            }
        }
    }

    private fun publishLedgerFailure(
        session: Int,
        e: LedgerException
    ) {
        Twig.warn { "Ledger signing: session failed with ${e.javaClass.simpleName}" }
        publish(session, LedgerSigningState.Failed(e.toLedgerIssue(LedgerIssueContext.SIGNING)))
    }

    /**
     * Looks for the device; a lone device is connected once the list has settled, several are
     * offered for selection, and so is a lone one after the last session met the wrong Ledger.
     * Returns null when nothing was found in time, when the picker stayed empty that long, or when
     * location is off below API 31, where nothing could be found.
     */
    private suspend fun scan(session: Int): LedgerBluetoothDevice? {
        if (ledgerDeviceDataSource.isLocationOffForScan()) {
            Twig.info { "Ledger signing: location is off" }
            publish(session, LedgerSigningState.Failed(LedgerIssue.locationOff))
            return null
        }
        Twig.info { "Ledger signing: stage Scanning" }
        publish(session, LedgerSigningState.Scanning)
        val found = MutableStateFlow<List<LedgerBluetoothDevice>>(emptyList())
        val timedOut = MutableStateFlow(false)
        return coroutineScope {
            val collector =
                launch {
                    ledgerDeviceDataSource.observeDevices().collect { devices ->
                        found.update { devices }
                        updateSelecting(session, devices)
                    }
                }
            val timer =
                launch {
                    delay(LEDGER_SCAN_TIMEOUT)
                    timedOut.update { true }
                }
            val settled = awaitSettledDevices(found, timedOut)
            timer.cancel()
            if (settled == null) {
                collector.cancel()
                Twig.info { "Ledger signing: no device found" }
                publish(session, LedgerSigningState.Failed(LedgerIssue.noDevices))
                return@coroutineScope null
            }
            val device =
                if (settled.size == 1 && !isPickerRequired) {
                    settled.single()
                } else {
                    awaitSelection(session, found)
                }
            collector.cancel()
            if (device == null) {
                Twig.info { "Ledger signing: no device left to select" }
                publish(session, LedgerSigningState.Failed(LedgerIssue.noDevices))
            }
            device
        }
    }

    private fun updateSelecting(
        session: Int,
        devices: List<LedgerBluetoothDevice>
    ) {
        synchronized(lock) {
            val current = signingState.value
            if (current is LedgerSigningState.Selecting && generation == session) {
                signingState.value = current.copy(devices = devices.map { it.toSigningDevice() })
            }
        }
    }

    /**
     * Waits for a device and then for the list to settle. A lone device that vanishes while the
     * list settles is waited for again, so the picker never opens empty. Returns null once
     * [timedOut] is set without a device in range.
     */
    private suspend fun awaitSettledDevices(
        found: StateFlow<List<LedgerBluetoothDevice>>,
        timedOut: StateFlow<Boolean>
    ): List<LedgerBluetoothDevice>? {
        while (true) {
            combine(found, timedOut) { devices, isTimedOut -> devices.isNotEmpty() || isTimedOut }
                .first { it }
            if (found.value.isEmpty()) return null
            delay(SETTLE_DELAY)
            val settled = found.value
            if (settled.isNotEmpty()) return settled
        }
    }

    /**
     * Waits for the user to pick a device. Returns null once the list has stayed empty for the scan
     * timeout, as a scan that finds nothing would.
     */
    private suspend fun awaitSelection(
        session: Int,
        found: StateFlow<List<LedgerBluetoothDevice>>
    ): LedgerBluetoothDevice? {
        val devices = found.value
        var choice = CompletableDeferred<String>()
        selection = choice
        Twig.info { "Ledger signing: stage Selecting" }
        publish(
            session,
            LedgerSigningState.Selecting(devices = devices.map { it.toSigningDevice() })
        )
        val device =
            coroutineScope {
                val emptied = CompletableDeferred<Unit>()
                val emptyTimer =
                    launch {
                        found.collectLatest { current ->
                            if (current.isEmpty()) {
                                delay(LEDGER_SCAN_TIMEOUT)
                                emptied.complete(Unit)
                            }
                        }
                    }
                var picked: LedgerBluetoothDevice? = null
                while (picked == null && !emptied.isCompleted) {
                    val identifier =
                        select<String?> {
                            choice.onAwait { it }
                            emptied.onAwait { null }
                        }
                    picked = identifier?.let { id -> found.value.firstOrNull { it.identifier == id } }
                    if (picked == null && identifier != null) {
                        choice = CompletableDeferred()
                        selection = choice
                    }
                }
                emptyTimer.cancel()
                picked
            }
        selection = null
        if (device != null) {
            isPickerRequired = false
        }
        return device
    }

    private suspend fun sign(
        session: Int,
        prepared: PreparedHardwareProposal,
        account: LedgerAccount
    ) {
        check(hardwareProposal.getApproved(transactionProposal.value) === prepared) {
            "The reviewed transaction changed before signing."
        }
        Twig.info { "Ledger signing: stage Preparing" }
        publish(session, LedgerSigningState.Preparing)
        val signed =
            ledgerSigningDataSource.sign(prepared.pczt.clonePczt(), account) { progress ->
                publish(session, progress.toSigningState())
            }
        synchronized(lock) {
            if (generation != session) return
            check(hardwareProposal.getApproved(transactionProposal.value) === prepared) {
                "The reviewed transaction changed during signing."
            }
            prepared.signatures = signed
        }
        Twig.info { "Ledger signing: stage Signed" }
        publish(session, LedgerSigningState.Signed)
    }

    override fun selectDevice(identifier: String) {
        if (signingState.value !is LedgerSigningState.Selecting) return
        selection?.complete(identifier)
    }

    override fun retry() {
        if (signingState.value !is LedgerSigningState.Failed) return
        sessionJob?.cancel()
        sessionJob = null
        signingState.update { null }
        startSigning()
    }

    /**
     * The link is closed after the session has unwound; the next session waits for that close, so
     * it can never close the link the next session opens. Nothing happens when no session ever ran
     * and no link is held.
     */
    override fun cancelSigning() {
        if (isIdle) return
        synchronized(lock) { generation++ }
        sessionJob?.cancel()
        sessionJob = null
        selection = null
        hardwareProposal.getPrepared(transactionProposal.value)?.signatures = null
        signingState.update { null }
        val previousClose = closeJob
        closeJob =
            scope.launch {
                previousClose?.join()
                ledgerSigningDataSource.close()
            }
    }

    override fun failSignedSessionWithoutProposal() {
        synchronized(lock) {
            if (signingState.value == LedgerSigningState.Signed) {
                signingState.value = LedgerSigningState.Failed(LedgerIssue.unknownWithoutRetry)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(expectedProposal: TransactionProposal?): SubmitResult =
        scope.async {
            try {
                val prepared = hardwareProposal.requireApproved(transactionProposal.value, expectedProposal)
                val signed = checkNotNull(prepared.signatures) { "The reviewed transaction has not been signed." }
                submitState.update { SubmitProposalState.Submitting }
                val proofs = prepared.proofs.await()
                val proposal = prepared.proposal
                val validateFunding: () -> Unit = {
                    requireCurrentApproval(prepared)
                    if (proposal is SwapTransactionProposal) {
                        proposal.requireFundingApproval(prepared.reviewedQuote)
                        ZecSwapComposition.validate(proposal.quote)
                    }
                }
                val submit: suspend (() -> Unit) -> SubmitResult = { beforeSubmission ->
                    proposalDataSource.submitTransaction(
                        pcztWithProofs = proofs,
                        pcztWithSignatures = signed,
                        beforeFirstSubmission = beforeSubmission,
                    )
                }
                val result = if (proposal is SwapTransactionProposal) {
                    processSwapTransaction.submitReservedFunding(proposal, validateFunding, submit)
                } else {
                    validateFunding()
                    submit(validateFunding)
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

    override fun clear() {
        cancelSigning()
        isPickerRequired = false

        pcztWithProofsJob?.cancel()
        pcztWithProofsJob = null

        transactionProposal.update { null }
        submitState.update { null }
        hardwareProposal.clear()
    }

    private val isIdle: Boolean
        get() = sessionJob == null && signingState.value == null && !ledgerSigningDataSource.isLinked

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

    private companion object {
        val SETTLE_DELAY = 1.seconds

        /**
         * The cap on connecting and opening the Zcash app, the same enrollment puts on its pairing.
         */
        val CONNECT_TIMEOUT = 5.minutes
    }
}

private fun LedgerBluetoothDevice.toSigningDevice() =
    LedgerSigningDevice(
        identifier = identifier,
        name = name ?: model.productName,
    )

private fun LedgerSigningProgress.toSigningState(): LedgerSigningState =
    when (this) {
        LedgerSigningProgress.IdentifyingDevice -> LedgerSigningState.Streaming(sent = 0, total = 0)

        is LedgerSigningProgress.Streaming -> LedgerSigningState.Streaming(sent = sent, total = total)

        LedgerSigningProgress.AwaitingReviewOnDevice -> LedgerSigningState.AwaitingReview

        LedgerSigningProgress.Signing,
        LedgerSigningProgress.Complete -> LedgerSigningState.Signing
    }
