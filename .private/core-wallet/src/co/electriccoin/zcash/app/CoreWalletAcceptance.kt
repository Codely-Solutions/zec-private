package co.electriccoin.zcash.app

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
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
                    state.put("fundedBalance", balance()); save(state)
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
                    val before = balance()
                    val destination = state.getString("recipientAddress")
                    check(destination.startsWith("utest1"))
                    val address = checkNotNull(dependency<TransactionRepository>().resolveWalletAddress(destination))
                    dependency<CreateProposalUseCase>()(ZecSend(address, Zatoshi(1_000_000), Memo("core-testnet-acceptance"), null), false)
                    dependency<ZashiProposalRepository>().getTransactionProposal()
                    // Persist intent before submission; no retry after an ambiguous broadcast.
                    state.put("sendAttempted", true).put("balanceBeforeSend", before); save(state)
                    val result = dependency<ZashiProposalRepository>().submit()
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
                    save(state.put("initialSubmitType",result.javaClass.simpleName).put("candidateTxIds",JSONArray(result.txIds)))
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
                    check(withTimeout(30_000) { original.validateServerEndpoint(context,endpoint) } == ServerValidation.Valid)
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
                        check(recovered.validateServerEndpoint(context,endpoint) == ServerValidation.Valid)
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
                            val directory = JSONObject(client.get(
                                "https://onionoo.torproject.org/details?running=true&fields=or_addresses"
                            ).bodyAsText())
                            val relays = directory.getJSONArray("relays")
                            check(relays.length() in 1000..20000)
                            File(context.filesDir, "core-relays.json").writeText(directory.toString())
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
        if (stage=="sender") receipt.put("scanned_height",fixture().getLong("preflightScannedHeight"))
            .put("network_height",fixture().getLong("preflightNetworkHeight"))
            .put("mode",fixture().getString("preflightMode"))
        if (stage=="send") receipt.put("fee_zatoshi",fixture().getLong("feeZatoshi"))
            .put("initial_submit_type",fixture().getString("initialSubmitType")).put("manual_resubmissions",0)
        if (stage=="restore") receipt.put("mined_height",fixture().getLong("restoreMinedHeight"))
            .put("amount_zatoshi",1_000_000L).put("app_transaction_state",fixture().getString("restoreTransactionState"))
        // No exception messages, seeds, addresses, HTTP bodies or private fixture are printed.
        println("CORE_RESULT $receipt")
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
        check(transaction.amount.value == 1_000_000L)
        check(transaction.recipient == state.getString("recipientAddress"))
        val fee = checkNotNull(transaction.fee).value
        check(fee > 0)
        withTimeout(120_000) {
            while (balance() != state.getLong("balanceBeforeSend") - 1_000_000L - fee) delay(2_000)
        }
        save(state.put("feeZatoshi", fee).put("senderConfirmedBalance", balance()))
    }
}
