package co.electriccoin.zcash.ui.screen.donation

import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.provider.IndependentPriceProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.koin.android.ext.android.inject
import java.math.BigDecimal

/** Opt-in isolated, unfunded boundary probe. No authorization, signing or submission call. */
class NativePriceProbeActivity : ComponentActivity() {
    private val wallets by inject<WalletRepository>()
    private val persisted by inject<PersistableWalletProvider>()
    private val accounts by inject<AccountDataSource>()
    private val tor by inject<IsTorEnabledStorageProvider>()
    private val swaps by inject<SwapDataSource>()
    private val prices by inject<IndependentPriceProvider>()
    private lateinit var output: TextView
    private val started = SystemClock.elapsedRealtime()
    private val receipt by lazy { filesDir.resolve("independent-price-probe.jsonl") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && intent.getBooleanExtra("isolated", false))
        output = TextView(this).apply { text = "Isolated unfunded price probe" }
        setContentView(output)
        lifecycleScope.launch(Dispatchers.IO) {
            receipt.writeText("")
            try {
                withTimeout(360_000L) {
                    check(persisted.getPersistableWallet() == null)
                    val useTor = intent.getBooleanExtra("tor", true)
                    tor.store(useTor)
                    record("creating_isolated_wallet")
                    wallets.createNewWallet()
                    withTimeout(90_000L) {
                        while (accounts.getSelectedAccount().totalBalance == null) delay(250)
                    }
                    check(accounts.getSelectedAccount().totalBalance?.value == 0L && tor.get() == useTor)
                    record("isolated_zero_balance_ready", JSONObject().put("tor", useTor))
                    val ownAddress = accounts.requestNextShieldedAddress(requireFresh = true)
                    val assets = swaps.getSupportedTokens()
                    val zec = assets.single { it.assetId == "nep141:zec.omft.near" }
                    data class Case(val name: String, val symbol: String, val amount: String, val incoming: Boolean = false,
                                    val exactOutput: Boolean = false)
                    val cases = if (intent.getBooleanExtra("minimumAndWbtc", false)) {
                        listOf(Case("btc_in", "btc", "0.001", incoming = true), Case("wbtc_out", "wbtc", "0.1"))
                    } else listOf(
                        Case("btc_in", "btc", "0.001", incoming = true),
                        Case("btc_out", "btc", "0.1"),
                        Case("btc_out_small", "btc", "0.01"),
                        Case("eth_out", "eth", "0.1"),
                        Case("sol_out", "sol", "0.1"),
                        Case("crosspay_btc", "btc", "0.0001", exactOutput = true),
                    )
                    val caseStart = intent.getIntExtra("caseStart", 0)
                    require(caseStart in cases.indices)
                    for (case in cases.drop(caseStart)) {
                        record("request_begin", JSONObject().put("case", case.name))
                        try {
                        check(accounts.getSelectedAccount().totalBalance?.value == 0L && tor.get() == useTor)
                        val asset = if (case.symbol == "wbtc") {
                            assets.single {
                                it.assetId == "nep141:eth-0x2260fac5e5542a773aa44fbcfedf7c193bc2c599.omft.near"
                            }
                        } else assets.single { it.assetId == "nep141:${case.symbol}.omft.near" }
                        val fixture = when (case.symbol) {
                            "btc" -> "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
                            "eth", "wbtc" -> "0x1111111111111111111111111111111111111111"
                            // RFC 8032 section 7.1 public-key vector encoded as base58; not a program account.
                            else -> "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"
                        }
                        val quote = swaps.requestQuote(
                            swapMode = when {
                                case.incoming -> SwapMode.FLEX_INPUT
                                case.exactOutput -> SwapMode.EXACT_OUTPUT
                                else -> SwapMode.EXACT_INPUT
                            },
                            amount = BigDecimal(case.amount),
                            refundAddress = if (case.incoming) fixture else ownAddress,
                            originAsset = if (case.incoming) asset else zec,
                            destinationAddress = if (case.incoming) ownAddress else fixture,
                            destinationAsset = if (case.incoming) zec else asset,
                            slippage = BigDecimal("0.5"),
                        )
                        val began = SystemClock.elapsedRealtime()
                        val checked = prices.check(quote, BigDecimal.ZERO)
                        val row = JSONObject().put("case", case.name)
                            .put("origin", quote.originAsset.tokenTicker).put("destination", quote.destinationAsset.tokenTicker)
                            .put("amountIn", quote.amountInFormatted.toPlainString())
                            .put("amountOut", quote.amountOutFormatted.toPlainString())
                            .put("minimumDeposit", quote.minimumAmountIn?.toPlainString())
                            .put("tor", useTor)
                            .put("checkMs", SystemClock.elapsedRealtime() - began)
                            .put("priceCheck", checked.javaClass.simpleName)
                            .put("knownNetworkFee", "0; no funded proposal created")
                        if (checked is IndependentPriceCheck.Compared) {
                            row.put("differencePercent", checked.differencePercent.toPlainString()).put("poor", checked.poor)
                        }
                        record("quote_checked", row)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val near = e as? co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
                            val text = near?.error?.message.orEmpty().lowercase()
                            val reason = when {
                                "recipient" in text || "address" in text -> "recipient_or_address_rejected"
                                "too low" in text -> "below_provider_minimum"
                                else -> "provider_or_transport_failure"
                            }
                            record("quote_failed", JSONObject().put("case", case.name)
                                .put("exceptionClass", e.javaClass.simpleName).put("reason", reason))
                        }
                    }
                    record("complete")
                }
            } catch (e: CancellationException) {
                record("cancelled", JSONObject().put("exceptionClass", e.javaClass.simpleName))
                throw e
            } catch (e: Exception) {
                record("stopped", JSONObject().put("exceptionClass", e.javaClass.simpleName))
            }
        }
    }

    private fun record(event: String, row: JSONObject = JSONObject()) {
        val line = row.put("event", event).put("elapsedMs", SystemClock.elapsedRealtime() - started).toString()
        receipt.appendText(line + "\n")
        runOnUiThread { output.text = line }
    }
}
