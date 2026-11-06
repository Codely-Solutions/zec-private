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
import cash.z.ecc.android.sdk.type.ServerValidation
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorClient
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
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.lang.reflect.Proxy

/** Exercises the installed app graph. No mocks, SDK-only wallet, or mainnet spending. */
class CoreWalletAcceptance {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private var approvalActions = 0
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
        withTimeout(900_000) {
            dependency<IsTorEnabledStorageProvider>().store(true)
            if (stage != "networkTargets") {
                val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                instrumentation.startActivitySync(intent)
                withTimeout(15_000) { dependency<ApplicationStateProvider>().isInForeground.first { it } }
            }
            when (stage) {
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
                    suspend fun openReview() {
                        reviewAttempt++
                        openPrefilledForm(destination)
                        val logBoundary = "CORE_REVIEW_BEGIN_" + reviewAttempt
                        android.util.Log.i("CoreReview", logBoundary)
                        try {
                            clickUi(context.getString(co.electriccoin.zcash.ui.design.R.string.send_review), "form_review")
                        } catch (error: Exception) {
                            proposalFailureDiagnostic(logBoundary, reviewAttempt)
                            throw error
                        }
                        val proposal = withTimeoutOrNull(60_000) {
                            proposals.transactionProposal.filterNotNull().first()
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
                        waitUi(context.getString(co.electriccoin.zcash.ui.R.string.send_confirmationTitle), "review_title")
                        waitUi("core-testnet-acceptance", "review_memo")
                        waitUi("0.01", "review_amount", exact = false)
                        check(proposal.amount.value == 1_000_000L)
                        check(proposal.destination.address == destination)
                        check(proposal.memo.value == "core-testnet-acceptance")
                    }
                    openReview()
                    check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                    withTimeout(15_000) { proposals.transactionProposal.first { it == null } }
                    check(proposals.submitState.value == null)
                    check(balance() == before)
                    // Re-enter the actual form/review path. No submission occurs on cancellation.
                    openReview()
                    state.put("sendAttempted", true).put("balanceBeforeSend", before)
                        .put("uiReview", true).put("uiCancelNoSubmission", true)
                    save(state)
                    // One real UI approval only. No direct repository submit or auth substitution.
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
                    println("CORE_SEND_DIAGNOSTIC $diagnostic")
                    save(state.put("uiApproval",true).put("initialSubmitType",result.javaClass.simpleName).put("candidateTxIds",JSONArray(result.txIds)))
                    // The app deliberately keeps attempted gRPC failures pending
                    // for SDK resubmission. Observe that exact transaction; never
                    // invoke submit again or accept an unconfirmed result.
                    check((result is SubmitResult.Success || result is SubmitResult.GrpcFailure) && result.txIds.size == 1) {
                        "Submission did not establish one accepted or SDK-pending transaction"
                    }
                    state.put("txId", result.txIds.single()); save(state)
                    checkSent()
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
                    check("core-testnet-acceptance" in dependency<TransactionRepository>().getMemos(transaction))
                    awaitBalance(1_000_000)
                    check(balance() == 1_000_000L)
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
                "networkTargets" -> {
                    check(dependency<PersistableWalletProvider>().getPersistableWallet() == null)
                    val backend=Proxy.newProxyInstance(Backend::class.java.classLoader,arrayOf(Backend::class.java)) { _, _, _ ->
                        error("Wallet access forbidden during isolated Tor bootstrap")
                    } as Backend
                    val tor=TorClient.new(File(context.cacheDir,"core-bootstrap-tor"),backend)
                    try {
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
                        tor.dispose()
                    }
                }
                else -> error("Unsupported core stage")
            }
        }
        val receipt = JSONObject().put("stage", stage).put("passed", true)
            .put("network", "zcash-testnet").put("transport", "Tor sensitive RPCs; upstream direct bulk")
            .put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
        if (stage in listOf("send", "restart", "restore")) receipt.put("txId", fixture().getString("txId"))
        if (stage=="fund") receipt.put("funding_txid",fixture().getString("fundingTxId")).put("balance_zatoshi",fixture().getLong("fundedBalance"))
            .put("app_transaction_state",fixture().getString("fundConfirmedState"))
            .put("public_relay_directory_transport", "direct HTTPS before wallet firewall; no wallet data")
            .put("public_relay_fetch_millis",fixture().getLong("publicRelayFetchMillis"))
        if (stage=="sender") receipt.put("scanned_height",fixture().getLong("preflightScannedHeight"))
            .put("network_height",fixture().getLong("preflightNetworkHeight"))
            .put("mode",fixture().getString("preflightMode"))
        if (stage=="send") receipt.put("fee_zatoshi",fixture().getLong("feeZatoshi"))
            .put("initial_submit_type",fixture().getString("initialSubmitType")).put("manual_resubmissions",0)
            .put("ui_review",fixture().getBoolean("uiReview"))
            .put("ui_cancel_no_submission",fixture().getBoolean("uiCancelNoSubmission"))
            .put("ui_approval",fixture().getBoolean("uiApproval"))
            .put("ui_approval_actions",approvalActions)
            .put("setup","production prefill bus; actual form Review and approval clicks")
            .put("biometric_prompt_executed",false)
        if (stage=="restore") receipt.put("mined_height",fixture().getLong("restoreMinedHeight"))
            .put("amount_zatoshi",1_000_000L).put("app_transaction_state",fixture().getString("restoreTransactionState"))

        // No exception messages, seeds, addresses, HTTP bodies or private fixture are printed.
        println("CORE_RESULT $receipt")
    }

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
            .put("review_title_visible", visible(co.electriccoin.zcash.ui.R.string.send_confirmationTitle))
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
            .put("log_read_failure_class", readFailureClass ?: "none"))
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
                            while (control != null && depth++ < 30) {
                                val current = control
                                probe.enabledControl = probe.enabledControl || current.isEnabled
                                probe.clickableControl = probe.clickableControl || current.isClickable
                                val button = current.className?.toString() == "android.widget.Button"
                                probe.buttonRole = probe.buttonRole || button
                                if (!current.isEnabled) {
                                    probe.disabledAncestor = true
                                    break
                                }
                                if (current.isClickable && button) {
                                    if (current.isVisibleToUser && current.isEnabled) {
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

    private fun uiDiagnostic(label: String, outcome: String, probe: UiProbe) {
        println("CORE_ACCOUNTING_DIAGNOSTIC " + JSONObject()
            .put("ui_control", label).put("ui_outcome", outcome)
            .put("root_present", probe.rootPresent).put("visible_match", probe.visibleMatch)
            .put("enabled_control", probe.enabledControl).put("clickable_control", probe.clickableControl)
            .put("disabled_ancestor", probe.disabledAncestor).put("button_role", probe.buttonRole))
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

    private suspend fun checkSent() {
        val state = fixture()
        val transaction = withTimeout(600_000) {
            dependency<TransactionRepository>().observeTransaction(state.getString("txId"))
                .filterNotNull().first { it is SendTransaction.Success }
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
            while (balance() != state.getLong("balanceBeforeSend") - 1_000_000L - fee) delay(2_000)
        }
        save(state.put("feeZatoshi", fee).put("senderConfirmedBalance", balance()))
    }
}
