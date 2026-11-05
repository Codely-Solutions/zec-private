package zec.privateprobe

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorHttp
import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.provider.*
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal

class TorQuoteTest {
 @Test fun realAppQuoteDismissAndRequestAgainOverTor() = runBlocking {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val backend = Proxy.newProxyInstance(Backend::class.java.classLoader, arrayOf(Backend::class.java)) { _, _, _ -> error("Wallet access forbidden") } as Backend
  val tor = TorClient.new(java.io.File(context.cacheDir, "isolated-tor"), backend)
  val sync = Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { _, method, args ->
   check(method.name == "validateAddress" && args?.first() == "u1l9f0l4348negsncgr9pxd9d3qaxagmqv3lnexcplmufpq7muffvfaue6ksevfvd7wrz7xrvn95rc5zjtn7ugkmgh5rnxswmcj30y0pw52pn0zjvy38rn2esfgve64rj5pcmazxgpyuj") { "Only validation of the public SDK fixture is permitted" }
   AddressType.Unified
  } as Synchronizer
  val syncProvider = Proxy.newProxyInstance(SynchronizerProvider::class.java.classLoader, arrayOf(SynchronizerProvider::class.java)) { _, method, _ ->
   check(method.name == "getSynchronizer"); sync
  } as SynchronizerProvider
  val clients = object : HttpClientProvider {
   override suspend fun create() = createTor()
   override suspend fun createTor(): HttpClient {
    val isolated = tor.isolatedTorClient()
    return HttpClient(TorHttp) {
     engine { this.tor = isolated; retryLimit = 1 }
     install(ContentNegotiation) { json() }; expectSuccess = true
    }
   }
  }
  val data = NearSwapDataSource(KtorNearApiProvider(clients), TokenIconProviderImpl(context), TokenNameProviderImpl(), BlockchainProviderImpl(context), syncProvider)
  val cache = object : SwapAssetCacheProvider {
   override suspend fun get() = emptyList<SwapAsset>()
   override suspend fun store(assets: List<SwapAsset>) = Unit
  }
  val repo = SwapRepositoryImpl(data, cache)
  try {
   withTimeout(180000) { repo.requestRefreshAssetsOnce() }
   val asset = checkNotNull(repo.assets.value.data).first { it.tokenTicker.equals("btc", true) && it.chainTicker.equals("btc", true) }
   repeat(3) { attempt ->
    repo.requestFlexInputIntoZec(BigDecimal("0.001"), "1BoatSLRHtKNngkdXEeobR76b53LETtpyT", "u1l9f0l4348negsncgr9pxd9d3qaxagmqv3lnexcplmufpq7muffvfaue6ksevfvd7wrz7xrvn95rc5zjtn7ugkmgh5rnxswmcj30y0pw52pn0zjvy38rn2esfgve64rj5pcmazxgpyuj", asset, BigDecimal("0.5"))
    val result = withTimeout(180000) { repo.quote.first { it is SwapQuoteData.Success || it is SwapQuoteData.Error } }
    val error = (result as? SwapQuoteData.Error)?.exception
    println("TOR_QUOTE attempt=$attempt appResult=${result?.javaClass?.simpleName} errorClass=${error?.javaClass?.simpleName} status=${(error as? ResponseException)?.response?.status?.value}")
    check(result is SwapQuoteData.Success) { "Real app quote failed; see safe class/status diagnostic" }
    repo.clearQuote() // Same repository cancellation/reset invoked by sheet dismissal.
    check(repo.quote.value == null)
    delay(500)
   }
  } finally { repo.clear(); tor.dispose() }
 }
}
