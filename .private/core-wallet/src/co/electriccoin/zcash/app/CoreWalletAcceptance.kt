package co.electriccoin.zcash.app

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendData
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.TickerLocation
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import cash.z.ecc.android.sdk.model.ZecSendExt
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.lightwallet.client.model.RawTransactionUnsafe
import cash.z.ecc.android.sdk.type.ServerValidation
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.LazyTorClient
import cash.z.ecc.android.sdk.util.WalletClientFactory
import co.electriccoin.lightwallet.client.CombinedWalletClient
import co.electriccoin.lightwallet.client.ServiceMode
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import cash.z.ecc.android.sdk.internal.model.TorHttp
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.ConfirmResyncUseCase
import io.ktor.client.request.get
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import co.electriccoin.zcash.ui.common.model.*
import co.electriccoin.zcash.ui.screen.donation.*
import co.electriccoin.zcash.ui.common.donation.*
import java.math.BigDecimal
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Proxy

/** Exercises the installed app graph. No mocks, SDK-only wallet, or mainnet spending. */
class CoreWalletAcceptance {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private var approvalActions = 0
    private var reviewReadyChecks = 0
    private var reviewReadyWaitMillis = 0L
    private inline fun <reified T : Any> dependency(): T = GlobalContext.get().get()
    // The guarded host briefly preserves this fixture around explicit target-data
    // erasure. It restores only this seed/birthday fixture, never an SDK database.
    private val fixtureFile get() = File(context.filesDir, "core-private.json")
    private fun fixture() = if (fixtureFile.exists()) JSONObject(fixtureFile.readText()) else JSONObject()
    private fun save(value: JSONObject) = fixtureFile.writeText(value.toString())

    @Test
    fun executeStage() = runBlocking {
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet)
        check(arguments.getString("isolatedTestnet") == "valueless-coins-only")
        val stage = checkNotNull(arguments.getString("stage"))
        val started = System.nanoTime()
        withTimeout(if (stage == "send") 1_560_000L else 900_000L) {
            dependency<IsTorEnabledStorageProvider>().store(true)
            if (stage !in listOf("networkTargets", "rpcProbe")) {
                val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                instrumentation.startActivitySync(intent)
                withTimeout(15_000) { dependency<ApplicationStateProvider>().isInForeground.first { it } }
            }
            when (stage) {
                "donationUiProbe" -> {
                    var step = "create_empty_wallet"
                    try {
                        donationUiMarker(step, "begin")
                        createWallet("uiProbe")
                        check(!checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java)).isDeviceSecure)
                        val proposals = dependency<ZashiProposalRepository>()
                        val automatic = dependency<AutoDonationStore>()
                        check(proposals.transactionProposal.value == null && proposals.submitState.value == null)
                        check(automatic.consent() == null && approvalActions == 0)
                        val account = dependency<AccountDataSource>().getZashiAccount()
                        step = "open_support"
                        donationUiMarker(step, "begin")
                        dependency<NavigationRouter>().forward(DonationArgs("2"))
                        step = "enable_choice"
                        scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_on))
                        clickUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_on), "probe_auto_on")
                        step = "set_percentage"
                        setDonationPercent("100")
                        step = "save_authorization"
                        scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_save))
                        clickUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_save), "probe_auto_save")
                        step = "verify_authorization"
                        val consent = withTimeout(15_000) {
                            while (automatic.consent()?.percent?.compareTo(BigDecimal(100)) != 0) delay(100)
                            checkNotNull(automatic.consent())
                        }
                        check(consent.accountUuid == account.sdkAccount.accountUuid.toString())
                        step = "disable_authorization"
                        AutoDonationSpendingGate.mutex.withLock { automatic.disable() }
                        check(automatic.consent() == null)
                        check(proposals.transactionProposal.value == null && proposals.submitState.value == null)
                        check(approvalActions == 0)
                        donationUiMarker("probe_complete", "authorization_verified_without_spending")
                    } catch (error: Exception) {
                        donationUiMarker(step, "failed_" + error.javaClass.simpleName)
                        throw error
                    }
                }
                "recipient" -> createWallet("recipient")
                "sender" -> createWallet("sender")
                "fund" -> {
                    val state = fixture()
                    check(state.getBoolean("claimAttempted"))
                    check(state.getString("fundingTxId").matches(Regex("[0-9a-f]{64}")))
                    checkWallet("sender")
                    val received=withTimeout(850_000) {
                        dependency<TransactionRepository>().observeTransaction(state.getString("fundingTxId"))
                            .filterNotNull().first { it is ReceiveTransaction && it.overview.minedHeight!=null }
                    }
                    awaitBalance(2_000_000)
                    check(balance()==received.amount.value)
                    withTimeout(850_000) {
                        while ((dependency<AccountDataSource>().getZashiAccount().spendableShieldedBalance?.value ?: 0L)<2_000_000L) delay(2_000)
                    }
                    // Reuse this already required spendability wait to establish the incoming
                    // confirmed state. Do not add another ten-block wait after recipient recovery.
                    val confirmed = withTimeout(60_000) {
                        dependency<TransactionRepository>().observeTransaction(state.getString("fundingTxId"))
                            .filterNotNull().first { it is ReceiveTransaction.Success }
                    }
                    state.put("fundedBalance", balance())
                        .put("fundConfirmedState",confirmed.overview.transactionState.toString())
                    save(state)
                }
                "upgrade", "restart" -> {
                    val state = fixture()
                    checkWallet("sender")
                    if (stage == "upgrade") {
                        awaitBalance(state.getLong("fundedBalance"))
                        check(balance() == state.getLong("fundedBalance"))
                        withTimeout(120_000) {
                            dependency<TransactionRepository>().observeTransaction(state.getString("fundingTxId"))
                                .filterNotNull().first { it is ReceiveTransaction.Success }
                        }
                    } else checkSent()
                }
                "send" -> {
                    val state = fixture()
                    checkWallet("sender")
                    check(!state.has("sendAttempted"))
                    awaitBalance(state.getLong("fundedBalance"))
                    val before = balance()
                    val destination = state.getString("recipientAddress")
                    check(destination.startsWith("utest1"))
                    checkNotNull(dependency<TransactionRepository>().resolveWalletAddress(destination))
                    check(!checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java)).isDeviceSecure)
                    val proposals = dependency<ZashiProposalRepository>()
                    check(proposals.submitState.value == null)
                    var reviewAttempt = 0
                    awaitReviewSync()
                    val sdk = dependency<SynchronizerProvider>().getSynchronizer()
                    val account = dependency<AccountDataSource>().getZashiAccount()
                    val multiUri = "zcash:$destination?amount=0.001&address.1=" +
                        state.getString("senderAddress") + "&amount.1=0.001"
                    val multiProposal = sdk.proposeFulfillingPaymentUri(account.sdkAccount, multiUri)
                    val multiDecoded = cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.Proposal
                        .parseFrom(multiProposal.toByteArray())
                    val requested = multiDecoded.stepsList.sumOf { step ->
                        val parsed = org.zecdev.zip321.ZIP321.request(
                            uriString = step.transactionRequest,
                            context = org.zecdev.zip321.parser.ParserContext.TESTNET,
                            validatingRecipients = null
                        ) as org.zecdev.zip321.ZIP321.ParserResult.Request
                        parsed.paymentRequest.payments.size
                    }
                    check(requested == 2)
                    val rejected = runCatching {
                        dependency<co.electriccoin.zcash.ui.common.datasource.ProposalDataSource>()
                            .createZip321Proposal(account, multiUri)
                    }.exceptionOrNull()
                    check(rejected is co.electriccoin.zcash.ui.common.datasource.UnsupportedZip321PaymentCountException)
                    check(proposals.transactionProposal.value == null && proposals.submitState.value == null)
                    save(state.put("multiRecipientSdkAccepted", true).put("multiRecipientRejectedByWallet", true)
                        .put("multiRecipientPayments", requested).put("multiRecipientSubmitted", false))
                    co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy.setIsolatedTestnetRecipient(destination)
                    co.electriccoin.zcash.ui.screen.donation.DonationPreferences(context).percent = "0.5".toBigDecimal()
                    dependency<NavigationRouter>().forward(co.electriccoin.zcash.ui.screen.donation.DonationArgs("2"))
                    // Fresh production UI consent, distinct from the legacy saved suggestion.
                    val automatic = dependency<AutoDonationStore>()
                    check(automatic.consent() == null)
                    scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_on))
                    clickUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_on), "auto_on")
                    scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_percent))
                    setDonationPercent("100")
                    scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_save))
                    clickUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_save), "auto_authorize_once")
                    val consent = withTimeout(15_000) {
                        while (automatic.consent()?.percent?.compareTo(BigDecimal(100)) != 0) delay(100)
                        checkNotNull(automatic.consent())
                    }
                    check(consent.accountUuid == account.sdkAccount.accountUuid.toString())
                    save(state.put("autoEnabledUiConsent", true))
                    // A quote created after authorization is required by production enrollment policy.
                    while (System.currentTimeMillis() / 1000L <= consent.authorizedAtSeconds) delay(100)
                    val automaticQuote = isolatedOutgoingQuote(destination, account.unifiedAddress)
                    scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_review))
                    suspend fun openReview() {
                        reviewAttempt++
                        awaitReviewSync()
                        val logBoundary = "CORE_REVIEW_BEGIN_" + reviewAttempt
                        android.util.Log.i("CoreReview", logBoundary)
                        try {
                            clickUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_review), "form_review")
                        } catch (error: Exception) {
                            proposalFailureDiagnostic(logBoundary, reviewAttempt)
                            throw error
                        }
                        val proposal = withTimeoutOrNull(60_000) {
                            var pending = proposals.transactionProposal.value
                            while (pending == null) {
                                val insufficientTitle = context.getString(
                                    co.electriccoin.zcash.ui.R.string.sheet_insufficientBalance_title
                                )
                                if (hasUiText(insufficientTitle)) {
                                    // Capture eligibility at rejection, before asynchronous sync changes it.
                                    proposalFailureDiagnostic(logBoundary, reviewAttempt)
                                    error("Core UI proposal reported insufficient funds")
                                }
                                delay(200)
                                pending = proposals.transactionProposal.value
                            }
                            pending
                        }
                        if (proposal == null) {
                            // Keep the bounded log classification last, even if input validation fails.
                            runCatching { validateSendInput(destination, "proposal_timeout") }
                            println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                                .put("ui_control", "form_review").put("ui_outcome", "proposal_timeout")
                                .put("submit_state_class", proposals.submitState.value?.javaClass?.simpleName ?: "none"))
                            proposalFailureDiagnostic(logBoundary, reviewAttempt)
                            error("Core UI review did not create a proposal")
                        }
                        check(proposal is co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal)
                        if (proposal.proposal.usesOrchardInputs()) {
                            clickUi(
                                context.getString(co.electriccoin.zcash.ui.R.string.send_orchardWarning_continue),
                                "orchard_continue"
                            )
                        }
                        // SmallTopAppBar uppercases titles; body and button text retain their case.
                        waitUi(
                            context.getString(co.electriccoin.zcash.ui.R.string.send_confirmationTitle).uppercase(),
                            "review_title"
                        )
                        waitUi("0.01", "review_amount", exact = false)
                        co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy.requireShielded(
                            proposal.proposal,
                            ZecSend(proposal.destination, proposal.amount, proposal.memo, null)
                        )
                        check(proposal.isDonation)
                        check(proposal.amount.value == 1_000_000L)
                        check(proposal.destination.address == destination)
                        check(proposal.memo.value.isEmpty())
                    }
                    openReview()
                    check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                    withTimeout(15_000) { proposals.transactionProposal.first { it == null } }
                    check(proposals.submitState.value == null)
                    check(balance() == before)
                    waitUi(context.getString(co.electriccoin.zcash.ui.R.string.private_support_review), "cancel_retained_donation")
                    // Cancellation retains Support development; do not push another route.
                    openReview()
                    state.put("sendAttempted", true).put("balanceBeforeSend", before)
                        .put("uiReview", true).put("uiCancelNoSubmission", true)
                    save(state)
                    // One real UI approval only. No direct repository submit or auth substitution.
                    val approvalBoundary = "CORE_APPROVAL_BEGIN_1"
                    android.util.Log.i("CoreReview", approvalBoundary)
                    clickUi(context.getString(co.electriccoin.zcash.ui.R.string.tabs_send), "review_approve")
                    val result = withTimeout(180_000) {
                        proposals.submitState.filterNotNull().first { it is SubmitProposalState.Result }
                    }.let { (it as SubmitProposalState.Result).submitResult }
                    save(fixture().put("uiApproval", true))
                    fun safeDescription(value: String?): String? = value
                        ?.replace(Regex("[0-9a-fA-F]{64,}"), "[hex omitted]")
                        ?.replace(Regex("utest1[a-z0-9]+"), "[isolated test address]")?.take(400)
                    val diagnostic=JSONObject().put("type",result.javaClass.simpleName)
                        .put("tx_ids",JSONArray(result.txIds)).put("manual_resubmissions",0)
                    when (result) {
                        is SubmitResult.Failure -> diagnostic.put("code",result.code).put("description",safeDescription(result.description))
                        is SubmitResult.GrpcFailure -> diagnostic.put("reason",result.reason?.name).put("description",safeDescription(result.description))
                        is SubmitResult.Partial -> diagnostic.put("statuses",JSONArray(result.statuses.map { safeDescription(it) }))
                        is SubmitResult.Error -> diagnostic.put("cause_class",result.cause.javaClass.simpleName)
                        is SubmitResult.Success -> Unit
                    }
                    if (result is SubmitResult.GrpcFailure) {
                        diagnostic.put("rpc_failure", submissionRpcDiagnostic(approvalBoundary))
                    }
                    println("CORE_SEND_DIAGNOSTIC $diagnostic")
                    save(state.put("uiApproval",true).put("initialSubmitType",result.javaClass.simpleName).put("candidateTxIds",JSONArray(result.txIds)))
                    // The app deliberately keeps attempted gRPC failures pending
                    // for SDK resubmission. Observe that exact transaction; never
                    // invoke submit again or accept an unconfirmed result.
                    check((result is SubmitResult.Success || result is SubmitResult.GrpcFailure) && result.txIds.size == 1) {
                        "Submission did not establish one accepted or SDK-pending transaction"
                    }
                    state.put("txId", result.txIds.single()); save(state)
                    dependency<AutoDonationCoordinator>().enroll(automaticQuote, result.txIds)
                    checkSent()
                    // Follow the real completion action; do not clear production proposal state by hand.
                    clickUi(context.getString(co.electriccoin.zcash.ui.R.string.send_viewTransaction), "manual_view_transaction")
                    withTimeout(15_000) { proposals.transactionProposal.first { it == null } }
                    acceptAutomaticDonation(automaticQuote)
                }
                "restore" -> {
                    val state = fixture()
                    check(dependency<PersistableWalletProvider>().getPersistableWallet() == null)
                    dependency<RestoreWalletUseCase>()(
                        SeedPhrase.new(state.getString("recipientSeed")), true,
                        BlockHeight.new(state.getLong("recipientBirthday"))
                    )
                    dependency<PersistableWalletProvider>().persistableWallet.filterNotNull().first()
                    checkWallet("recipient")
                    val transaction = withTimeout(600_000) {
                        dependency<TransactionRepository>().observeTransaction(state.getString("txId"))
                            .filterNotNull().first { it is ReceiveTransaction && it.overview.minedHeight!=null }
                    }
                    check(transaction.amount.value == 1_000_000L)
                    check(dependency<TransactionRepository>().getMemos(transaction).all { it.isEmpty() })
                    val automatic = withTimeout(600_000) {
                        dependency<TransactionRepository>().observeTransaction(state.getString("autoTxId"))
                            .filterNotNull().first { it is ReceiveTransaction && it.overview.minedHeight != null }
                    }
                    check(automatic.amount.value == state.getLong("autoAmountZatoshi"))
                    check(dependency<TransactionRepository>().getMemos(automatic).all { it.isEmpty() })
                    val totalReceived = 1_000_000L + automatic.amount.value
                    awaitBalance(totalReceived)
                    check(balance() == totalReceived)
                    state.put("autoRestoreAmountZatoshi", automatic.amount.value)
                        .put("autoRestoreMinedHeight", checkNotNull(automatic.overview.minedHeight).value)
                    save(state.put("restoreMinedHeight",checkNotNull(transaction.overview.minedHeight).value)
                        .put("restoreTransactionState",transaction.overview.transactionState.toString()))
                }
                "transport" -> {
                    val provider=dependency<SynchronizerProvider>()
                    val original=provider.getSynchronizer()
                    withTimeout(120_000) { original.status.first { it==Synchronizer.Status.SYNCED } }
                    val height=original.fullyScannedHeight.filterNotNull().first()
                    val beforeBalance=balance()
                    val fundingId=fixture().getString("fundingTxId")
                    val endpoint=dependency<PersistableWalletProvider>().requirePersistableWallet().endpoint
                    requireEndpointReady(original, "before_outage")
                    val ready=File(context.filesDir,"core-sync-ready.json")
                    ready.writeText(JSONObject().put("ready",true).put("height",height.value).toString())
                    withTimeout(60_000) {
                        while (!File(context.filesDir,"core-sync-drop").exists()) delay(100)
                    }
                    val metadataDuringOutage=withTimeoutOrNull(8_000) { original.validateServerEndpoint(context,endpoint) }
                    check(metadataDuringOutage != ServerValidation.Valid) { "Sensitive metadata succeeded while Tor was blocked" }
                    // A plain TLS handshake proves the authorized direct bulk path
                    // is reachable; no wallet RPC or wallet data is sent here.
                    withTimeout(8_000) {
                        java.net.Socket().use { plain ->
                            plain.connect(java.net.InetSocketAddress(endpoint.host,endpoint.port),6_000)
                            plain.soTimeout=6_000
                            val factory=javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                            (factory.createSocket(plain,endpoint.host,endpoint.port,true) as javax.net.ssl.SSLSocket).use {
                                it.soTimeout=6_000
                                val parameters=it.sslParameters
                                parameters.endpointIdentificationAlgorithm="HTTPS"
                                it.sslParameters=parameters
                                it.startHandshake()
                            }
                        }
                    }
                    File(context.filesDir,"core-sync-requested.json").writeText("{\"requested\":true,\"metadata_blocked\":true,\"direct_bulk_reachable\":true}")
                    dependency<ConfirmResyncUseCase>()(BlockHeight.new(height.value-1))
                    val recovered=withTimeout(180_000) { provider.synchronizer.filterNotNull().first { it!==original } }
                    withTimeout(180_000) {
                        recovered.status.first { it==Synchronizer.Status.SYNCED }
                        recovered.fullyScannedHeight.filterNotNull().first { it.value>=height.value }
                        while (balance()!=beforeBalance) delay(500)
                        dependency<TransactionRepository>().observeTransaction(fundingId).filterNotNull().first { it is ReceiveTransaction.Success }
                        requireEndpointReady(recovered, "after_outage")
                    }
                    File(context.filesDir,"core-sync-result.json").writeText(JSONObject()
                        .put("sync_restarted",true).put("synced",true).put("balance_preserved",true)
                        .put("history_preserved",true).put("scanned_height",recovered.fullyScannedHeight.value?.value).toString())
                }
                "rpcProbe" -> {
                    check(dependency<PersistableWalletProvider>().getPersistableWallet() == null)
                    val tor = createPublicProbeTor()
                    try {
                        save(fixture().put("nativeRpcProbe", nativeRpcProbe(tor, arguments.getString("probeTxId"))))
                    } finally {
                        withContext(NonCancellable) { tor.dispose() }
                    }
                }
                "networkTargets" -> {
                    check(dependency<PersistableWalletProvider>().getPersistableWallet() == null)
                    val backend=Proxy.newProxyInstance(Backend::class.java.classLoader,arrayOf(Backend::class.java)) { _, _, _ ->
                        error("Wallet access forbidden during isolated Tor bootstrap")
                    } as Backend
                    val tor=TorClient.new(File(context.cacheDir,"core-bootstrap-tor"),backend)
                    try {
                        save(fixture().put("nativeRpcProbe", nativeRpcProbe(tor, null)))
                        val isolated=tor.isolatedTorClient()
                        HttpClient(TorHttp) {
                            engine { this.tor=isolated; retryLimit=1 }
                            expectSuccess=true
                        }.use { client ->
                            val addresses = JSONObject()
                            for (host in listOf("testnet.zec.rocks", "zcashfaucet.jinolabs.xyz")) {
                                val hostAddresses=JSONArray()
                                if (host=="testnet.zec.rocks") {
                                    java.net.InetAddress.getAllByName(host).forEach { hostAddresses.put(it.hostAddress) }
                                }
                                for (type in listOf("A", "AAAA")) {
                                    val result = JSONObject(client.get("https://dns.google/resolve?name=$host&type=$type").bodyAsText())
                                    val answers = result.optJSONArray("Answer") ?: continue
                                    for (index in 0 until answers.length()) {
                                        val answer = answers.getJSONObject(index)
                                        if (answer.optInt("type") in listOf(1, 28)) hostAddresses.put(answer.getString("data"))
                                    }
                                }
                                check(hostAddresses.length()>0)
                                addresses.put(if (host=="testnet.zec.rocks") "bulk" else "sensitive",hostAddresses)
                            }
                            check(addresses.length() > 0)
                            println("CORE_TARGETS $addresses")
                            // Public firewall fixture only: no wallet, account or provider data.
                            // Sensitive requests below still use native Tor under the installed firewall.
                            val publicStarted = System.nanoTime()
                            val connection = java.net.URL(
                                "https://onionoo.torproject.org/details?running=true&fields=or_addresses"
                            ).openConnection() as javax.net.ssl.HttpsURLConnection
                            val directory = try {
                                connection.connectTimeout = 20_000
                                connection.readTimeout = 20_000
                                connection.instanceFollowRedirects = false
                                connection.useCaches = false
                                connection.setRequestProperty("Connection", "close")
                                check(connection.responseCode == 200)
                                val bytes = connection.inputStream.use { it.readNBytes(2_097_153) }
                                check(bytes.size in 1..2_097_152)
                                JSONObject(String(bytes, Charsets.UTF_8))
                            } finally {
                                connection.disconnect()
                            }
                            val relays = directory.getJSONArray("relays")
                            check(relays.length() in 1000..20000)
                            File(context.filesDir, "core-relays.json").writeText(directory.toString())
                            save(fixture().put("publicRelayFetchMillis", (System.nanoTime() - publicStarted) / 1_000_000))
                        }
                    } finally {
                        withContext(NonCancellable) { tor.dispose() }
                    }
                }
                else -> error("Unsupported core stage")
            }
        }
        val receipt = JSONObject().put("stage", stage).put("passed", true)
            .put("network", "zcash-testnet").put("transport", "Tor sensitive RPCs; upstream direct bulk")
            .put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
        if (stage == "donationUiProbe") receipt.put("donation_ui_probe", true)
            .put("fresh_ui_authorization", true).put("authorization_disabled", true)
            .put("funding_requested", false).put("proposal_created", false).put("payment_approval_actions", 0)
        if (stage in listOf("send", "restart", "restore")) receipt.put("txId", fixture().getString("txId"))
        if (stage in listOf("rpcProbe", "networkTargets", "fund")) {
            receipt.put("native_rpc_probe", fixture().getJSONObject("nativeRpcProbe"))
        }
        if (stage=="fund") receipt.put("funding_txid",fixture().getString("fundingTxId")).put("balance_zatoshi",fixture().getLong("fundedBalance"))
            .put("app_transaction_state",fixture().getString("fundConfirmedState"))
            .put("public_relay_directory_transport", "direct HTTPS before wallet firewall; no wallet data")
            .put("public_relay_fetch_millis",fixture().getLong("publicRelayFetchMillis"))
        if (stage=="sender") receipt.put("scanned_height",fixture().getLong("preflightScannedHeight"))
            .put("network_height",fixture().getLong("preflightNetworkHeight"))
            .put("mode",fixture().getString("preflightMode"))
        if (stage in listOf("fund", "upgrade")) receipt.put("database_input", databaseInputDiagnostic())
        if (stage=="send") receipt.put("fee_zatoshi",fixture().getLong("feeZatoshi"))
            .put("initial_submit_type",fixture().getString("initialSubmitType")).put("manual_resubmissions",0)
            .put("ui_review",fixture().getBoolean("uiReview"))
            .put("ui_cancel_no_submission",fixture().getBoolean("uiCancelNoSubmission"))
            .put("ui_approval",fixture().getBoolean("uiApproval"))
            .put("ui_approval_actions",approvalActions)
            .put("ui_review_sync_checks",reviewReadyChecks)
            .put("ui_review_sync_wait_millis",reviewReadyWaitMillis)
            .put("setup","production support screen; actual donation review/cancel and approval clicks")
            .put("shielded_donation",true).put("identifying_memo",false)
            .put("multi_recipient_sdk_accepted",fixture().getBoolean("multiRecipientSdkAccepted"))
            .put("multi_recipient_wallet_rejected",fixture().getBoolean("multiRecipientRejectedByWallet"))
            .put("multi_recipient_submitted",false)
            .put("biometric_prompt_executed",false)
        if (stage=="send") receipt
            .put("auto_enabled_ui_consent", fixture().getBoolean("autoEnabledUiConsent"))
            .put("auto_payment_approval_actions", fixture().getInt("autoUiApprovalActions"))
            .put("auto_exactly_once", fixture().getBoolean("autoExactlyOnce"))
            .put("auto_deposit_raw_confirmed", fixture().getBoolean("autoDepositRawConfirmed"))
            .put("auto_native_mined", fixture().getBoolean("autoNativeMined"))
            .put("auto_consent_disabled", fixture().getBoolean("autoConsentDisabled"))
            .put("auto_provider_evidence", fixture().getString("autoProviderEvidence"))
            .put("auto_amount_zatoshi", fixture().getLong("autoAmountZatoshi"))
            .put("auto_fee_zatoshi", fixture().getLong("autoFeeZatoshi"))
            .put("auto_tx_id", fixture().getString("autoTxId"))
        if (stage=="restart") receipt.put("auto_restart_mined", fixture().getBoolean("autoRestartMined"))
        if (stage=="restore") receipt
            .put("auto_received_zatoshi", fixture().getLong("autoRestoreAmountZatoshi"))
            .put("auto_mined_height", fixture().getLong("autoRestoreMinedHeight"))
            .put("mined_height",fixture().getLong("restoreMinedHeight"))
            .put("amount_zatoshi",1_000_000L).put("app_transaction_state",fixture().getString("restoreTransactionState"))

        // No exception messages, seeds, addresses, HTTP bodies or private fixture are printed.
        println("CORE_RESULT $receipt")
    }

    private suspend fun createPublicProbeTor(): TorClient {
        val backend = Proxy.newProxyInstance(Backend::class.java.classLoader, arrayOf(Backend::class.java)) { _, _, _ ->
            error("Wallet access forbidden during unfunded native RPC diagnostic")
        } as Backend
        return TorClient.new(File(context.cacheDir, "core-rpc-probe-tor"), backend)
    }

    private fun rejectionCode(description: String): Int? =
        Regex("""Failed to submit transaction \((-?\d+)\):""").find(description)
            ?.groupValues?.get(1)?.toIntOrNull()

    /** Only these fixed diagnostic categories can leave captured native exception text. */
    private fun safeRpcReasons(text: String?): List<String> {
        val lowered = text.orEmpty().lowercase()
        return listOf("timeout", "timed out", "tor", "uninitialized", "disposed", "connection refused",
            "connection reset", "tls", "certificate", "handshake", "dns", "network unreachable",
            "circuit", "bootstrap", "unavailable", "transport", "invalid argument", "deserialize")
            .filter { if (it == "tor") Regex("""\btor\b""").containsMatchIn(lowered) else lowered.contains(it) }.take(12)
    }

    /** Reproduces production's cached Group Tor client, without constructing a wallet or transaction. */
    private suspend fun groupedNativeRpcProbe(tor: TorClient): JSONObject {
        val result = JSONObject().put("wallet_access", false).put("valid_transaction_submissions", 0)
            .put("service_mode", "Tor Group").put("malformed_submit_calls", 0)
        val holder = LazyTorClient { tor.isolatedTorClient() }
        var ownedClient: CombinedWalletClient? = null
        var stage = "combined_client_create"
        val started = System.nanoTime()
        try {
            val client = WalletClientFactory(context, holder).create(
                LightWalletEndpoint(host = "testnet.zec.rocks", port = 443, isSecure = true)
            )
            ownedClient = client
            // The same deterministic, non-wallet group name is reused for height and malformed submit.
            val group = ServiceMode.Group("submit-" + "0".repeat(64))
            stage = "group_latest_height"
            val height = withTimeout(30_000) { client.getLatestBlockHeight(serviceMode = group) }
            result.put("height_response_class", height.javaClass.simpleName)
            when (height) {
                is Response.Success -> { check(height.result.value > 0); result.put("height", height.result.value) }
                is Response.Failure -> {
                    result.put("sdk_code", height.code).put("cause_class", height.cause.javaClass.simpleName)
                        .put("reason_tokens", JSONArray(safeRpcReasons(height.description)))
                    error("Unfunded grouped Tor latest-height failed")
                }
            }
            stage = "group_malformed_submit"
            result.put("malformed_submit_calls", 1)
            val rejected = withTimeout(30_000) { client.submitTransaction(tx = byteArrayOf(0), serviceMode = group) }
            result.put("submit_response_class", rejected.javaClass.simpleName)
            val code = when (rejected) {
                is Response.Success -> rejected.result.code
                is Response.Failure -> {
                    result.put("sdk_code", rejected.code).put("cause_class", rejected.cause.javaClass.simpleName)
                        .put("reason_tokens", JSONArray(safeRpcReasons(rejected.description)))
                    rejectionCode(rejected.description.orEmpty())
                }
            }
            result.put("native_rejection_code", code ?: JSONObject.NULL)
            check(code == -22) { "Grouped Tor malformed transaction must reach node deserialization rejection" }
            return result.put("passed", true)
        } catch (error: Exception) {
            result.put("probe_stage", stage).put("exception_class", error.javaClass.simpleName)
                .put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
            if (!result.has("reason_tokens")) result.put("reason_tokens", JSONArray(safeRpcReasons(error.message)))
            println("CORE_NATIVE_ERROR $result")
            throw error
        } finally {
            result.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
            withContext(NonCancellable) {
                try { ownedClient?.dispose() } finally { holder.dispose() }
            }
        }
    }

    private suspend fun nativeRpcProbe(tor: TorClient, lookupTxId: String?): JSONObject {
        val result = JSONObject().put("wallet_access", false).put("valid_transaction_submissions", 0)
        val started = System.nanoTime()
        val isolated = tor.isolatedTorClient()
        try {
            val client = isolated.createWalletClient("https://testnet.zec.rocks:443")
            try {
                val height = withTimeout(30_000) { client.getLatestBlockHeight() }
                result.put("height_response_class", height.javaClass.simpleName)
                if (height is Response.Success) {
                    result.put("height", height.result.value)
                } else {
                    val failure = height as Response.Failure
                    result.put("sdk_code", failure.code)
                    println("CORE_NATIVE_ERROR " + result.put("probe_stage", "latest_height"))
                    error("Unfunded native Tor latest-height RPC failed")
                }
                if (lookupTxId != null) {
                    check(lookupTxId.matches(Regex("[0-9a-f]{64}")))
                    val lookup = withTimeout(30_000) {
                        client.fetchTransaction(TransactionId.new(lookupTxId).value.byteArray)
                    }
                    val detail = JSONObject().put("response_class", lookup.javaClass.simpleName)
                    if (lookup is Response.Success) {
                        val transaction = lookup.result
                        detail.put("known", true).put("state", transaction.javaClass.simpleName)
                        if (transaction is RawTransactionUnsafe.MainChain) {
                            detail.put("height", transaction.height.value)
                        }
                    } else {
                        detail.put("known", false).put("sdk_code", (lookup as Response.Failure).code)
                    }
                    result.put("lookup_tx_id", lookupTxId).put("previous_test_transaction_lookup", detail)
                }
                // One malformed byte cannot be a valid signed transaction and spends no funds.
                val rejected = withTimeout(30_000) { client.submitTransaction(byteArrayOf(0)) }
                result.put("malformed_submit_calls", 1).put("submit_response_class", rejected.javaClass.simpleName)
                val code = when (rejected) {
                    is Response.Failure -> {
                        result.put("sdk_code", rejected.code)
                        rejectionCode(rejected.description.orEmpty())
                    }
                    is Response.Success -> rejected.result.code.takeIf { it != 0 }
                }
                result.put("native_rejection_code", code ?: JSONObject.NULL)
                result.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
                if (code == null || code == 0) {
                    println("CORE_NATIVE_ERROR " + result.put("probe_stage", "malformed_submit"))
                    error("Native Tor malformed-transaction RPC did not establish server rejection")
                }
                result.put("grouped_submit_probe", groupedNativeRpcProbe(tor))
                return result
            } finally {
                withContext(NonCancellable) { client.dispose() }
            }
        } catch (error: Exception) {
            result.put("exception_class", error.javaClass.simpleName)
                .put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
            println("CORE_NATIVE_ERROR $result")
            throw error
        } finally {
            withContext(NonCancellable) { isolated.dispose() }
        }
    }

    private fun submissionRpcDiagnostic(boundary: String): JSONObject {
        val result = JSONObject()
        try {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "timeout 3 logcat -d -b main -v raw --uid=" + android.os.Process.myUid()
            )
            val captured = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                String(it.readNBytes(2_097_152), Charsets.UTF_8)
            }
            val start = captured.lastIndexOf(boundary)
            result.put("approval_marker_found", start >= 0)
            if (start >= 0) {
                val logs = captured.substring(start)
                val code = rejectionCode(logs)
                result.put("native_rejection_code", code ?: JSONObject.NULL)
                result.put("height_preflight_failed", logs.contains(
                    "Could not read the server height before submitting"
                ))
                result.put("sdk_submit_failure_logged", logs.contains(
                    "FAILURE! submit transaction failed with gRPC response"
                ))
                result.put("raw_submit_results_logged", logs.contains("Internal transaction submit results:"))
                val internal = logs.lineSequence()
                    .filter { it.contains("Internal transaction submit results:") }.take(3).joinToString("\n")
                val codes = Regex("""\bcode\s*[=:]\s*(-?\d{1,8})\b""").findAll(internal)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().take(8).toList()
                result.put("internal_sdk_codes", JSONArray(codes))
                result.put("internal_reason_tokens", JSONArray(safeRpcReasons(internal)))
                result.put("internal_failure_classes", JSONArray(listOf("OverTor", "Connection",
                    "DeadlineExceeded", "Unavailable", "UninitializedTorClientException", "IllegalStateException",
                    "TimeoutCancellationException").filter { internal.contains(it) }))
                result.put("internal_tor_failure", internal.contains("isTorFailure=true") ||
                    internal.contains("isTorFailure: true"))
                val rejectionLines = logs.lineSequence().filter { it.contains("Failed to submit transaction (") }
                    .take(3).joinToString("\n")
                result.put("rejection_reason_tokens", JSONArray(safeRpcReasons(rejectionLines)))
            }
        } catch (error: Exception) {
            result.put("capture_failure_class", error.javaClass.simpleName)
        }
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun proposalFailureDiagnostic(logBoundary: String, reviewAttempt: Int) {
        var logAvailable = false
        var capped = false
        var logs = ""
        var readFailureClass: String? = null
        try {
            // Static command plus our own UID only. Never emit captured log lines or messages.
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "logcat -d -v raw --uid=" + android.os.Process.myUid() + " -t 600"
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val bytes = ByteArray(131_072)
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                capped = count == bytes.size
                val captured = String(bytes, 0, count, Charsets.UTF_8)
                val boundary = captured.lastIndexOf(logBoundary)
                logAvailable = boundary >= 0
                logs = if (logAvailable) captured.substring(boundary + logBoundary.length) else ""
            }
        } catch (error: Exception) {
            readFailureClass = error.javaClass.name
        }
        val invalidEnums = listOf("INVALID_AMOUNT", "INVALID_ADDRESS", "INVALID_MEMO")
            .filter { Regex("\\b" + it + "\\b").containsMatchIn(logs) }
        val exceptionClasses = Regex(
            "\\b(?:[a-zA-Z_$][a-zA-Z0-9_$]*\\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error)\\b"
        ).findAll(logs).map { it.value }.distinct().take(6).toList()
        val insufficient = Regex("Insufficient balance \\(have ([0-9]{1,16}), need ([0-9]{1,16}) including fee\\)")
            .find(logs)
        val selected = dependency<AccountDataSource>().getSelectedAccount()
        val zashi = dependency<AccountDataSource>().getZashiAccount()
        val synchronizer = dependency<SynchronizerProvider>().getSynchronizer()
        fun visible(id: Int): Boolean {
            val node = findUi(context.getString(id), true, false, UiProbe())
            return (node != null).also { node?.recycle() }
        }
        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
            .put("ui_control", "form_review").put("ui_outcome", "proposal_log_classification")
            .put("review_attempt", reviewAttempt)
            .put("insufficient_balance_sheet", visible(co.electriccoin.zcash.ui.R.string.sheet_insufficientBalance_title))
            .put("form_review_visible", visible(co.electriccoin.zcash.ui.design.R.string.send_review))
            .put("review_title_visible", hasUiText(
                context.getString(co.electriccoin.zcash.ui.R.string.send_confirmationTitle).uppercase()
            ))
            .put("own_uid_log_available", logAvailable).put("log_byte_cap_reached", capped)
            .put("proposal_error_logged", logs.contains("Error creating proposal"))
            .put("send_validation_failed_logged", logs.contains("Send failed with"))
            .put("invalid_enums", JSONArray(invalidEnums))
            .put("exception_classes", JSONArray(exceptionClasses))
            .put("regular_proposal_frame", logs.contains("ProposalDataSourceImpl") && logs.contains("createProposal"))
            .put("send_view_model_frame", logs.contains("SendViewModel"))
            .put("native_insufficient_balance", insufficient != null)
            .put("native_available_zatoshi", insufficient?.groupValues?.get(1)?.toLongOrNull() ?: -1L)
            .put("native_required_zatoshi", insufficient?.groupValues?.get(2)?.toLongOrNull() ?: -1L)
            .put("native_additional_change", logs.contains("The transaction requires an additional change output of"))
            .put("selected_is_zashi", selected.sdkAccount.accountUuid == zashi.sdkAccount.accountUuid)
            .put("selected_total_zatoshi", selected.totalShieldedBalance?.value ?: -1L)
            .put("selected_spendable_zatoshi", selected.spendableShieldedBalance?.value ?: -1L)
            .put("orchard_available_zatoshi", selected.orchardBalance?.available?.value ?: -1L)
            .put("ironwood_available_zatoshi", selected.ironwoodBalance?.available?.value ?: -1L)
            .put("sapling_available_zatoshi", selected.saplingBalance?.available?.value ?: -1L)
            .put("scanned_height", synchronizer.fullyScannedHeight.value?.value ?: -1L)
            .put("sync_status", synchronizer.status.first().toString())
            .put("database_input", databaseInputDiagnostic())
            .put("log_read_failure_class", readFailureClass ?: "none"))
    }

    private fun databaseInputDiagnostic(): JSONObject {
        val result = JSONObject()
        try {
            // Only this fresh isolated app's SDK database. Never export keys, addresses, memos or rows.
            val files = context.noBackupFilesDir.walkTopDown().maxDepth(4).take(1000)
                .filter { it.isFile && it.name.endsWith("data.sqlite3") }.take(2).toList()
            result.put("database_count", files.size)
            check(files.size == 1)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                files.single().path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                    android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                val queries = linkedMapOf(
                    "accounts" to "SELECT COUNT(*) FROM accounts",
                    "ironwood_note_counts" to """
                        SELECT COUNT(*), COALESCE(SUM(rn.value),0), SUM(t.block IS NULL),
                        SUM(t.mined_height IS NULL), SUM(rn.nf IS NULL),
                        SUM(rn.recipient_key_scope IS NULL), SUM(rn.commitment_tree_position IS NULL),
                        SUM(rn.witness_stabilized=1), SUM(rn.lock_expiry_height IS NOT NULL),
                        MIN(rn.lock_expiry_height), MAX(rn.lock_expiry_height)
                        FROM ironwood_received_notes rn JOIN transactions t ON t.id_tx=rn.transaction_id
                    """.trimIndent(),
                    "checkpoint_counts_and_maxima" to """
                        SELECT (SELECT COUNT(*) FROM sapling_tree_checkpoints),
                        (SELECT MAX(checkpoint_id) FROM sapling_tree_checkpoints),
                        (SELECT COUNT(*) FROM orchard_tree_checkpoints),
                        (SELECT MAX(checkpoint_id) FROM orchard_tree_checkpoints),
                        (SELECT COUNT(*) FROM ironwood_tree_checkpoints),
                        (SELECT MAX(checkpoint_id) FROM ironwood_tree_checkpoints)
                    """.trimIndent(),
                    "ironwood_shard_counts" to """
                        SELECT COUNT(*), SUM(ss.max_priority IS NULL), SUM(ss.max_priority>10)
                        FROM ironwood_received_notes rn LEFT JOIN v_ironwood_shards_scan_state ss
                        ON rn.commitment_tree_position>=ss.start_position
                        AND rn.commitment_tree_position<ss.end_position_exclusive
                    """.trimIndent(),
                    "note_height_ranges" to """
                        SELECT MIN(t.block), MAX(t.block), MIN(t.mined_height), MAX(t.mined_height),
                        MIN(t.trust_status), MAX(t.trust_status)
                        FROM ironwood_received_notes rn JOIN transactions t ON t.id_tx=rn.transaction_id
                    """.trimIndent(),
                    "legacy_tip_and_anchor" to """
                        WITH h AS (SELECT MAX(block_range_end) AS target FROM scan_queue),
                        a AS (SELECT target,
                        (SELECT MAX(checkpoint_id) FROM sapling_tree_checkpoints
                         WHERE checkpoint_id<=target-3) AS s,
                        (SELECT MAX(checkpoint_id) FROM orchard_tree_checkpoints
                         WHERE checkpoint_id<=target-3) AS o FROM h),
                        c AS (SELECT target,s,o,CASE WHEN s IS NULL THEN o WHEN o IS NULL THEN s
                        ELSE MIN(s,o) END AS anchor FROM a)
                        SELECT target,s,o,anchor,EXISTS(SELECT 1
                        FROM v_ironwood_shard_unscanned_ranges r WHERE r.block_range_start<=c.anchor
                        AND c.anchor BETWEEN r.subtree_start_height AND IFNULL(r.subtree_end_height,c.anchor))
                        FROM c
                    """.trimIndent()
                )
                for ((label, sql) in queries) {
                    try {
                        db.rawQuery(sql, null).use { cursor ->
                            check(cursor.moveToFirst() && cursor.columnCount <= 12)
                            val values = JSONArray()
                            for (column in 0 until cursor.columnCount) {
                                values.put(if (cursor.isNull(column)) JSONObject.NULL else cursor.getLong(column))
                            }
                            result.put(label, values)
                        }
                    } catch (error: Exception) {
                        result.put(label + "_error_class", error.javaClass.simpleName)
                    }
                }
            }
        } catch (error: Exception) {
            result.put("error_class", error.javaClass.simpleName)
        }
        return result
    }

    private fun validateSendInput(destination: String, label: String): String {
        try {
            // Mirror AndroidSend's prefill formatting and SendButton's independent SDK validation.
            val formatted = stringRes(Zatoshi(1_000_000), TickerLocation.HIDDEN).getString(context)
            val amount = AmountState.newFromZec(
                value = formatted,
                fiatValue = "",
                isTransparentOrTextRecipient = false,
                exchangeRateState = dependency<ExchangeRateRepository>().state.value,
            )
            val rendered = amount.value.getString(context)
            val validation = ZecSendExt.new(NUMBER_FORMAT_LOCALE, destination, rendered, "core-testnet-acceptance")
            val errors = if (validation is ZecSendExt.ZecSendValidation.Invalid) {
                validation.validationErrors.map { it.name }
            } else emptyList()
            println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                .put("ui_control", label).put("ui_outcome", "input_validation")
                .put("amount_state_class", amount.javaClass.simpleName)
                .put("sdk_validation_class", validation.javaClass.simpleName)
                .put("invalid_enums", JSONArray(errors)))
            check(amount is AmountState.Valid && amount.zatoshi.value == 1_000_000L)
            check(validation is ZecSendExt.ZecSendValidation.Valid && validation.zecSend.amount.value == 1_000_000L)
            return rendered
        } catch (error: Exception) {
            println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                .put("ui_control", label).put("ui_outcome", "input_validation_exception")
                .put("exception_class", error.javaClass.simpleName))
            throw IllegalStateException("Core UI input validation failed")
        }
    }

    private suspend fun openPrefilledForm(destination: String) {
        val renderedAmount = validateSendInput(destination, "form_prefill")
        dependency<NavigationRouter>().forward(Send())
        val prefill = dependency<PrefillSendUseCase>().request(
            PrefillSendData.All(Zatoshi(1_000_000), destination, null, listOf("core-testnet-acceptance"))
        )
        try {
            val delivered = withTimeoutOrNull(60_000) { prefill.join(); true } == true
            if (!delivered) uiDiagnostic("form_prefill", "handoff_timeout", UiProbe())
            check(delivered) { "Core UI prefill handoff timed out" }
        } finally {
            prefill.cancel()
        }
        // Channel handoff precedes address validation and Compose state updates.
        waitUi(destination, "form_recipient")
        waitUi(renderedAmount, "form_amount")
        waitUi("core-testnet-acceptance", "form_memo")
    }

    private class UiProbe {
        var rootPresent = false
        var visibleMatch = false
        var enabledControl = false
        var clickableControl = false
        var disabledAncestor = false
        var buttonRole = false
    }

    @Suppress("DEPRECATION")
    private fun findUi(text: String, exact: Boolean, clickable: Boolean, probe: UiProbe): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        probe.rootPresent = true
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var count = 0
        try {
            while (pending.isNotEmpty() && count++ < 500) {
                val node = pending.removeFirst()
                try {
                    val value = node.text?.toString().orEmpty()
                    val matches = if (exact) value == text else value.contains(text)
                    if (matches && node.isVisibleToUser) {
                        probe.visibleMatch = true
                        if (!clickable) return AccessibilityNodeInfo.obtain(node)
                        var control: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                        try {
                            var depth = 0
                            while (control != null && depth++ < 4) {
                                val current = control
                                probe.enabledControl = probe.enabledControl || current.isEnabled
                                probe.clickableControl = probe.clickableControl || current.isClickable
                                val button = current.className?.toString() == "android.widget.Button"
                                probe.buttonRole = probe.buttonRole || button
                                if (!current.isEnabled) {
                                    probe.disabledAncestor = true
                                    break
                                }
                                if (current.isClickable) {
                                    // Compose does not expose a Button class in this guest. Select the nearest
                                    // bounded ACTION_CLICK control; never climb into the screen background.
                                    val bounds = android.graphics.Rect()
                                    val labelBounds = android.graphics.Rect()
                                    current.getBoundsInScreen(bounds)
                                    node.getBoundsInScreen(labelBounds)
                                    val density = context.resources.displayMetrics.density
                                    val boundedControl = bounds.height() >= 24 * density &&
                                        bounds.height() <= 96 * density && bounds.contains(labelBounds)
                                    val clickAction = current.actionList.any {
                                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id
                                    }
                                    if (current.isVisibleToUser && boundedControl && clickAction) {
                                        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                                            .put("ui_control", "resolved_click")
                                            .put("ui_outcome", "bounded_enabled_action")
                                            .put("control_class", current.className?.toString())
                                            .put("height_dp", bounds.height() / density)
                                            .put("button_role", button))
                                        return AccessibilityNodeInfo.obtain(current)
                                    }
                                    break
                                }
                                control = current.parent
                                current.recycle()
                            }
                        } finally {
                            control?.recycle()
                        }
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().recycle()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun hasUiText(text: String): Boolean {
        val node = findUi(text, true, false, UiProbe()) ?: return false
        node.recycle()
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitReviewSync() {
        val provider = dependency<SynchronizerProvider>()
        data class Snapshot(
            val sync: Synchronizer,
            val status: Synchronizer.Status,
            val tip: BlockHeight?,
            val scanned: BlockHeight?
        )
        var last: Snapshot? = null
        val started = System.nanoTime()
        try {
            val ready = withTimeout(120_000) {
                provider.synchronizer.filterNotNull().flatMapLatest { current ->
                    combine(current.status, current.networkHeight, current.fullyScannedHeight) { status, tip, scanned ->
                        Snapshot(current, status, tip, scanned)
                    }
                }.first { snapshot ->
                    last = snapshot
                    snapshot.status == Synchronizer.Status.SYNCED && snapshot.tip != null &&
                        snapshot.scanned != null && snapshot.scanned.value >= snapshot.tip.value &&
                        provider.synchronizer.value === snapshot.sync
                }
            }
            check(provider.synchronizer.value === ready.sync)
            reviewReadyChecks++
            reviewReadyWaitMillis += (System.nanoTime() - started) / 1_000_000
            println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                .put("ui_control", "review_sync_precondition").put("ui_outcome", "current_instance_synced")
                .put("network_height", ready.tip?.value).put("scanned_height", ready.scanned?.value))
        } catch (error: Exception) {
            println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
                .put("ui_control", "review_sync_precondition").put("ui_outcome", "not_ready")
                .put("status", last?.status?.toString()).put("network_height", last?.tip?.value)
                .put("scanned_height", last?.scanned?.value).put("exception_class", error.javaClass.simpleName))
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun scrollToText(text: String) {
        repeat(8) { attempt ->
            if (hasUiText(text)) return
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@repeat
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
            pending.add(root)
            var count = 0
            var scrolled = false
            try {
                while (pending.isNotEmpty() && count++ < 500 && !scrolled) {
                    val node = pending.removeFirst()
                    try {
                        if (node.isVisibleToUser && node.isScrollable) {
                            val direction = if (attempt < 4) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            scrolled = node.performAction(direction)
                        }
                        if (!scrolled) {
                            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
                        }
                    } finally {
                        node.recycle()
                    }
                }
            } finally {
                while (pending.isNotEmpty()) pending.removeFirst().recycle()
            }
            delay(300)
        }
        waitUi(text, "scroll_target")
    }

    private fun uiDiagnostic(label: String, outcome: String, probe: UiProbe) {
        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
            .put("ui_control", label).put("ui_outcome", outcome)
            .put("root_present", probe.rootPresent).put("visible_match", probe.visibleMatch)
            .put("enabled_control", probe.enabledControl).put("clickable_control", probe.clickableControl)
            .put("disabled_ancestor", probe.disabledAncestor).put("button_role", probe.buttonRole)
            .put("confirmation_visible", hasUiText(
                context.getString(co.electriccoin.zcash.ui.R.string.send_confirmationTitle).uppercase()
            ))
            .put("insufficient_funds_visible", hasUiText(
                context.getString(co.electriccoin.zcash.ui.R.string.sheet_insufficientBalance_title)
            )))
    }

    private suspend fun awaitUi(
        text: String,
        label: String,
        exact: Boolean = true,
        clickable: Boolean = false,
    ): AccessibilityNodeInfo {
        val probe = UiProbe()
        val node = withTimeoutOrNull(60_000) {
            var found = findUi(text, exact, clickable, probe)
            while (found == null) {
                delay(200)
                found = findUi(text, exact, clickable, probe)
            }
            found
        }
        if (node == null) {
            uiDiagnostic(label, "readiness_timeout", probe)
            error("Core UI control readiness timed out")
        }
        return node
    }

    @Suppress("DEPRECATION")
    private suspend fun waitUi(text: String, label: String, exact: Boolean = true) {
        awaitUi(text, label, exact).recycle()
    }

    @Suppress("DEPRECATION")
    private suspend fun clickUi(text: String, label: String) {
        val node = awaitUi(text, label, clickable = true)
        try {
            // Readiness may be polled; an action with an ambiguous result is never retried.
            check(label != "review_approve" || approvalActions == 0) { "Core approval may be attempted only once" }
            if (label == "review_approve") approvalActions++
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0)
            val downTime = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int): Boolean {
                val event = android.view.MotionEvent.obtain(
                    downTime, android.os.SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0
                )
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                return try {
                    instrumentation.uiAutomation.injectInputEvent(event, true)
                } finally {
                    event.recycle()
                }
            }
            val down = inject(android.view.MotionEvent.ACTION_DOWN)
            android.os.SystemClock.sleep(60)
            val up = inject(android.view.MotionEvent.ACTION_UP)
            val acted = down && up
            if (!acted) {
                uiDiagnostic(label, "single_touch_rejected", UiProbe().apply {
                    rootPresent = true
                    visibleMatch = node.isVisibleToUser
                    enabledControl = node.isEnabled
                    clickableControl = node.isClickable
                })
            }
            check(acted) { "Core UI single action rejected" }
        } finally {
            node.recycle()
        }
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun createWallet(role: String) {
        check(dependency<PersistableWalletProvider>().getPersistableWallet() == null)
        dependency<WalletRepository>().createNewWallet()
        val wallet = dependency<PersistableWalletProvider>().persistableWallet.filterNotNull().first()
        check(wallet.network == ZcashNetwork.Testnet)
        val sync = dependency<SynchronizerProvider>().getSynchronizer()
        val account = dependency<AccountDataSource>().getZashiAccount()
        val address = sync.getUnifiedAddress(account.sdkAccount)
        check(address.startsWith("utest1"))
        val state = fixture().put(role + "Seed", wallet.seedPhrase.joinToString())
            .put(role + "Birthday", checkNotNull(wallet.birthday).value)
            .put(role + "Address", address)
        save(state)
        // Prove the real app is syncing before requesting scarce faucet coins.
        if (role == "sender") {
            val provider = dependency<SynchronizerProvider>()
            data class Snapshot(val sync: Synchronizer, val status: Synchronizer.Status, val tip: BlockHeight?, val scanned: BlockHeight?)
            var last: Snapshot? = null
            var observed: Synchronizer? = null
            var initial: Long? = null
            try {
                val ready = withTimeout(120_000) {
                    provider.synchronizer.filterNotNull().flatMapLatest { current ->
                        combine(current.status, current.networkHeight, current.fullyScannedHeight) { status, tip, scanned ->
                            Snapshot(current, status, tip, scanned)
                        }
                    }.first { snapshot ->
                        last = snapshot
                        if (observed !== snapshot.sync) {
                            observed = snapshot.sync
                            initial = snapshot.scanned?.value
                        }
                        val height = snapshot.scanned?.value
                        val tip = snapshot.tip?.value
                        if (initial == null && height != null) initial = height
                        height != null && tip != null && height <= tip &&
                            ((snapshot.status == Synchronizer.Status.SYNCED && height >= tip) || height > (initial ?: height))
                    }
                }
                save(fixture().put("preflightScannedHeight",checkNotNull(ready.scanned).value)
                    .put("preflightNetworkHeight",checkNotNull(ready.tip).value)
                    .put("preflightMode",if (ready.status == Synchronizer.Status.SYNCED) "caught-up" else "advancing-scan"))
                requireEndpointReady(ready.sync, "before_funding")
                // Fail parser/prefill mismatches before the host requests scarce faucet coins.
                openPrefilledForm(fixture().getString("recipientAddress"))
                // A zero-balance wallet must not resolve Review to a clickable background ancestor.
                val disabledReview = findUi(
                    context.getString(co.electriccoin.zcash.ui.design.R.string.send_review), true, true, UiProbe()
                )
                disabledReview?.recycle()
                check(disabledReview == null) { "Core UI selector escaped a disabled Review button" }
                check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            } catch (error: Exception) {
                val currentError = provider.error.value
                println("CORE_SYNC_DIAGNOSTIC " + JSONObject()
                    .put("status",last?.status?.toString() ?: "not-emitted")
                    .put("scanned_height",last?.scanned?.value ?: -1L)
                    .put("network_height",last?.tip?.value ?: -1L)
                    .put("error_type",currentError?.javaClass?.simpleName ?: "none")
                    .put("cause_class",currentError?.cause?.javaClass?.simpleName ?: "none")
                    .put("foreground",withTimeoutOrNull(1_000) { dependency<ApplicationStateProvider>().isInForeground.first() } ?: false)
                    .put("exception_class", error.javaClass.simpleName))
                throw error
            }
        }
    }

    private suspend fun checkWallet(role: String) {
        val wallet = dependency<PersistableWalletProvider>().requirePersistableWallet()
        check(wallet.network == ZcashNetwork.Testnet)
        check(wallet.seedPhrase.joinToString() == fixture().getString(role + "Seed"))
        dependency<SynchronizerProvider>().getSynchronizer()
    }

    private suspend fun requireEndpointReady(sync: Synchronizer, label: String) {
        val endpoint = dependency<PersistableWalletProvider>().requirePersistableWallet().endpoint
        // Read-only metadata readiness only; never retry a send, approval or broadcast.
        repeat(3) { attempt ->
            val result = withTimeoutOrNull(15_000) { sync.validateServerEndpoint(context, endpoint) }
            if (result == ServerValidation.Valid) return
            println("CORE_SYNC_DIAGNOSTIC " + JSONObject()
                .put("readiness", label).put("attempt", attempt + 1)
                .put("result_class", result?.javaClass?.simpleName ?: "timeout")
                .put("cause_class", (result as? ServerValidation.InValid)?.reason?.javaClass?.simpleName ?: "none"))
            if (attempt < 2) delay(2_000)
        }
        error("Core Tor metadata readiness was not established")
    }

    private suspend fun balance(): Long = dependency<AccountDataSource>().getZashiAccount().totalShieldedBalance?.value ?: -1L

    private suspend fun awaitBalance(minimum: Long) = withTimeout(600_000) {
        while (balance() < minimum) delay(2_000)
    }

    /** Only the provider result is synthetic; keys, proposals, Tor broadcast and confirmations are real. */
    private fun isolatedOutgoingQuote(destination: String, refund: String): SwapQuote {
        fun asset(token: String, chain: String, precision: Int): SwapAsset {
            val simple = dependency<co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider>().get(token, chain)
            return object : SwapAsset {
                override val tokenTicker = simple.tokenTicker
                override val tokenName = simple.tokenName
                override val tokenIcon = simple.tokenIcon
                override val blockchain = simple.blockchain
                override val usdPrice: BigDecimal? = null
                override val assetId = "isolated-$token-$chain"
                override val decimals = precision
            }
        }
        return object : SwapQuote {
            override val originAsset = asset("ZEC", "zec", 8)
            override val destinationAsset = asset("USDC", "eth", 6)
            override val depositAddress = ZcashShieldedSwapAddress(destination)
            override val destinationAddress = DynamicSwapAddress("isolated-synthetic-provider-destination")
            override val refundAddress = ZcashShieldedSwapAddress(refund)
            override val provider = "isolated-synthetic-provider"
            override val mode = SwapMode.EXACT_INPUT
            override val amountIn = BigDecimal(1_000_000)
            override val amountInFormatted = BigDecimal("0.01")
            override val amountInUsd = BigDecimal.ZERO
            override val amountOut = BigDecimal(1_000_000)
            override val amountOutFormatted = BigDecimal.ONE
            override val amountOutUsd = BigDecimal.ZERO
            override val affiliateFee = BigDecimal.ZERO
            override val affiliateFeeUsd = BigDecimal.ZERO
            override val slippage = BigDecimal.ZERO
            override val timestamp = kotlin.time.Clock.System.now()
            override val deadline = timestamp + kotlin.time.Duration.parse("15m")
        }
    }

    private fun successfulIsolatedSwap(quote: SwapQuote): SwapQuoteStatus = object : SwapQuoteStatus {
        override val originAsset = quote.originAsset
        override val destinationAsset = quote.destinationAsset
        override val depositAddress = quote.depositAddress
        override val destinationAddress = quote.destinationAddress
        override val timestamp = java.time.Instant.ofEpochSecond(quote.timestamp.epochSeconds)
        override val deadline = quote.deadline
        override val status = SwapStatus.SUCCESS
        override val isSlippageRealized = true
        override val maxSlippage = BigDecimal.ZERO
        override val mode = quote.mode
        override val amountInFormatted = quote.amountInFormatted
        override val amountInUsd = BigDecimal.ZERO
        override val amountOutFormatted = BigDecimal.ONE
        override val amountOutUsd = BigDecimal.ZERO
        override val executedAmountIn = quote.amountInFormatted
        override val executedAmountOut = BigDecimal.ONE
        override val depositedAmountFormatted = quote.amountInFormatted
        override val refundedFormatted = BigDecimal.ZERO
    }

    @Suppress("DEPRECATION")
    private fun donationEditableFields(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        check(root.packageName?.toString() == context.packageName)
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val editable = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        var count = 0
        try {
            while (pending.isNotEmpty() && count++ < 500) {
                val node = pending.removeFirst()
                try {
                    if (node.isEditable) editable.add(AccessibilityNodeInfo.obtain(node))
                    // Match the bridge's depth-first Column order: amount first, percentage second.
                    for (index in node.childCount - 1 downTo 0) node.getChild(index)?.let(pending::addFirst)
                } finally { node.recycle() }
            }
        } finally { while (pending.isNotEmpty()) pending.removeFirst().recycle() }
        return editable
    }

    @Suppress("DEPRECATION")
    private fun enabledWithAncestors(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        var parent = node.parent
        try {
            while (parent != null) {
                val current = parent
                if (!current.isEnabled) return false
                parent = current.parent
                current.recycle()
            }
        } finally { parent?.recycle() }
        return true
    }

    private fun donationUiMarker(step: String, outcome: String, fields: Int = -1, rect: String? = null) {
        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject().put("ui_control", "donation_ui_$step")
            .put("ui_outcome", outcome).put("editable_count", fields).put("target_rect", rect)
            .put("field_label", context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_percent)))
    }

    @Suppress("DEPRECATION")
    private suspend fun setDonationPercent(value: String) {
        val label = context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_percent)
        val title = context.getString(co.electriccoin.zcash.ui.R.string.private_support_title)
        donationUiMarker("percent_screen", "checking_support_screen")
        check(hasUiText(title) || hasUiText(title.uppercase()))
        scrollToText(label)
        check(hasUiText(label))
        var actionAttempted = false
        var revealRequested = false
        var amountBeforeEdit: String? = null
        try {
            withTimeout(15_000) {
                while (!actionAttempted) {
                    val fields = donationEditableFields()
                    try {
                        donationUiMarker("percent_selector", "editable_inventory", fields.size)
                        check(fields.size == 2) { "Support screen must have amount and percentage inputs" }
                        val percentage = fields[1]
                        check(percentage.text?.toString() == "0.25") { "Unexpected initial percentage" }
                        check(enabledWithAncestors(percentage))
                        if (!revealRequested || !percentage.isVisibleToUser) {
                            revealRequested = true
                            val shown = percentage.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                            donationUiMarker("percent_reveal", if (shown) "show_requested" else "scroll_required", fields.size)
                            if (!shown) scrollToText(context.getString(co.electriccoin.zcash.ui.R.string.private_support_auto_save))
                        } else {
                            val bounds = android.graphics.Rect()
                            percentage.getBoundsInScreen(bounds)
                            check(!bounds.isEmpty)
                            check(percentage.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                            val args = android.os.Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                            }
                            amountBeforeEdit = fields[0].text?.toString()
                            actionAttempted = true
                            donationUiMarker("percent_set_text", "single_action", fields.size, bounds.flattenToString())
                            check(percentage.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                        }
                    } finally { fields.forEach { it.recycle() } }
                    if (!actionAttempted) delay(150)
                }
                // Read-only polling verifies the action; an ambiguous edit is never repeated.
                while (true) {
                    val fields = donationEditableFields()
                    val verified = try {
                        fields.size == 2 && fields[1].text?.toString() == value &&
                            fields[0].text?.toString() == amountBeforeEdit && enabledWithAncestors(fields[1])
                    } finally { fields.forEach { it.recycle() } }
                    if (verified) break
                    delay(100)
                }
                donationUiMarker("percent_verify", "value_verified", 2)
            }
        } catch (error: Exception) {
            donationUiMarker("percent_failure", if (actionAttempted) "after_single_action" else "before_action")
            throw error
        }
    }

    private suspend fun acceptAutomaticDonation(quote: SwapQuote) {
        val coordinator = dependency<AutoDonationCoordinator>()
        val status = successfulIsolatedSwap(quote)
        val sync = dependency<SynchronizerProvider>().getSynchronizer()
        val account = dependency<AccountDataSource>().getZashiAccount()
        // The app marks outgoing payments successful when mined. Production automation additionally
        // requires the SDK's raw ten-confirmation state for its deposit; wait for that exact proof once.
        val depositTxId = fixture().getString("txId")
        withTimeout(1_200_000) {
            sync.getTransactions(account.sdkAccount.accountUuid).first { history ->
                history.any { it.txId.txIdString() == depositTxId && it.isSentTransaction &&
                    it.transactionState == cash.z.ecc.android.sdk.model.TransactionState.Confirmed }
            }
        }
        val before = sync.getTransactions(account.sdkAccount.accountUuid).first().map { it.txId.txIdString() }.toSet()
        val approvalsBefore = approvalActions
        val balanceBefore = balance()
        coordinator.onStatus(status)
        val transaction = withTimeout(240_000) {
            sync.getTransactions(account.sdkAccount.accountUuid).first { history ->
                history.any { it.isSentTransaction && it.txId.txIdString() !in before &&
                    it.minedHeight != null }
            }.single { it.isSentTransaction && it.txId.txIdString() !in before }
        }
        val fee = checkNotNull(transaction.feePaid).value
        check(fee > 0 && fee < 1_000_000L)
        check(transaction.netValue.value == 1_000_000L + fee)
        val displayed = dependency<TransactionRepository>().observeTransaction(transaction.txId.txIdString())
            .filterNotNull().first { it is SendTransaction.Success }
        check(displayed.recipient == fixture().getString("recipientAddress"))
        check(dependency<TransactionRepository>().getMemos(displayed).all { it.isEmpty() })
        withTimeout(120_000) { while (balance() != balanceBefore - 1_000_000L - fee) delay(1_000) }
        coordinator.onStatus(status)
        delay(10_000)
        val after = sync.getTransactions(account.sdkAccount.accountUuid).first()
        check(after.count { it.isSentTransaction && it.txId.txIdString() !in before } == 1)
        check(approvalActions == approvalsBefore)
        AutoDonationSpendingGate.mutex.withLock { dependency<AutoDonationStore>().disable() }
        check(dependency<AutoDonationStore>().consent() == null)
        save(fixture().put("autoTxId", transaction.txId.txIdString()).put("autoAmountZatoshi", 1_000_000L)
            .put("autoFeeZatoshi", fee).put("autoUiApprovalActions", 0).put("autoExactlyOnce", true)
            .put("autoDepositRawConfirmed", true).put("autoNativeMined", true).put("autoConsentDisabled", true)
            .put("autoProviderEvidence", "synthetic-success; real native shielded transaction")
            .put("senderConfirmedBalance", balance()))
    }

    private suspend fun checkSent() {
        val state = fixture()
        val transaction = withTimeout(600_000) {
            dependency<TransactionRepository>().observeTransaction(state.getString("txId"))
                .filterNotNull().first { it is SendTransaction.Success }
        }
        if (state.has("autoTxId")) {
            val automatic = withTimeout(120_000) {
                dependency<TransactionRepository>().observeTransaction(state.getString("autoTxId"))
                    .filterNotNull().first { it is SendTransaction.Success }
            }
            check(automatic.overview.minedHeight != null)
            check(automatic.amount.value == state.getLong("autoAmountZatoshi") + state.getLong("autoFeeZatoshi"))
            check(automatic.recipient == state.getString("recipientAddress"))
            check(dependency<AutoDonationStore>().consent() == null)
            save(state.put("autoRestartMined", true))
        }
        val fee = checkNotNull(transaction.fee).value
        // SDK netValue is abs(accountBalanceDelta), including the fee. The
        // freshly restored recipient independently verifies the payment itself.
        val recipientMatches = transaction.recipient == state.getString("recipientAddress")
        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
            .put("sender_debit_zatoshi",transaction.amount.value).put("fee_zatoshi",fee)
            .put("recipient_matches",recipientMatches)
            .put("mined_height",transaction.overview.minedHeight?.value))
        check(transaction.amount.value == 1_000_000L + fee)
        check(recipientMatches)
        check(fee > 0)
        withTimeout(120_000) {
            while (balance() != state.getLong("balanceBeforeSend") - 1_000_000L - fee -
                state.optLong("autoAmountZatoshi", 0L) - state.optLong("autoFeeZatoshi", 0L)) delay(2_000)
        }
        save(state.put("feeZatoshi", fee).put("senderConfirmedBalance", balance()))
    }
}
