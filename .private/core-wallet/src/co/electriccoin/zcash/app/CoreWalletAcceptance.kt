package co.electriccoin.zcash.app

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorHttp
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import io.ktor.client.request.get
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
            when (stage) {
                "recipient" -> createWallet("recipient")
                "fund" -> {
                    createWallet("sender")
                    val state = fixture()
                    val address = state.getString("senderAddress")
                    check(address.startsWith("utest1"))
                    // Exactly one funding claim. Unknown outcome must never cause an automatic re-claim.
                    check(!state.has("claimAttempted"))
                    state.put("claimAttempted", true); save(state)
                    dependency<HttpClientProvider>().createTor().use { client ->
                        val readiness=JSONObject(client.get("https://faucet.testnet.valargroup.dev/api/status").bodyAsText())
                        check(readiness.getString("network")=="testnet" && readiness.getBoolean("ready")) { "Faucet testnet funding unavailable" }
                        val response = client.post("https://faucet.testnet.valargroup.dev/api/claim") {
                            contentType(ContentType.Application.Json)
                            setBody(JSONObject().put("address", address).toString())
                        }
                        check(response.status.value == 202) { "Faucet claim HTTP ${response.status.value}" }
                        val claim = JSONObject(response.bodyAsText())
                        state.put("claimId", claim.get("id").toString()); save(state)
                        withTimeout(600_000) {
                            while (true) {
                                val status = JSONObject(client.get(
                                    "https://faucet.testnet.valargroup.dev/api/claim/${state.getString("claimId")}" 
                                ).bodyAsText())
                                if (status.optString("status") == "sent") {
                                    state.put("fundingTxId", status.getString("txid")); save(state); break
                                }
                                check(status.optString("status") !in listOf("failed", "review")) { "Faucet did not establish payment" }
                                delay(10_000)
                            }
                        }
                    }
                    val received=withTimeout(600_000) {
                        dependency<TransactionRepository>().observeTransaction(state.getString("fundingTxId"))
                            .filterNotNull().first { it is ReceiveTransaction.Success }
                    }
                    awaitBalance(2_000_000)
                    check(balance()==received.amount.value)
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
                    check(result is SubmitResult.Success && result.txIds.size == 1) { "Broadcast did not establish a single successful transfer" }
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
                            .filterNotNull().first { it is ReceiveTransaction.Success }
                    }
                    check(transaction.amount.value == 1_000_000L)
                    check("core-testnet-acceptance" in dependency<TransactionRepository>().getMemos(transaction))
                    awaitBalance(1_000_000)
                    check(balance() == 1_000_000L)
                }
                "transport" -> {
                    dependency<SynchronizerProvider>().getSynchronizer()
                    dependency<HttpClientProvider>().createTor().use { client ->
                        check(client.get("https://faucet.testnet.valargroup.dev/healthz").status.value == 200)
                    }
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
                            val addresses = JSONArray()
                            for (host in listOf("testnet.zec.rocks", "faucet.testnet.valargroup.dev")) {
                                for (type in listOf("A", "AAAA")) {
                                    val result = JSONObject(client.get("https://dns.google/resolve?name=$host&type=$type").bodyAsText())
                                    val answers = result.optJSONArray("Answer") ?: continue
                                    for (index in 0 until answers.length()) {
                                        val answer = answers.getJSONObject(index)
                                        if (answer.optInt("type") in listOf(1, 28)) addresses.put(answer.getString("data"))
                                    }
                                }
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
            .put("network", "zcash-testnet").put("transport", "app-native-Tor")
            .put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
        if (stage in listOf("send", "restart", "restore")) receipt.put("txId", fixture().getString("txId"))
        // No exception messages, seeds, addresses, HTTP bodies or private fixture are printed.
        println("CORE_RESULT $receipt")
    }

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
