package zec.privateprobe

import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.internal.Backend
import cash.z.ecc.android.sdk.internal.model.TorClient
import cash.z.ecc.android.sdk.internal.model.TorHttp
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class TorQuoteTest {
    @Test fun repeatedUnfundedQuotesWithNativeTor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = Proxy.newProxyInstance(Backend::class.java.classLoader, arrayOf(Backend::class.java)) { _, _, _ ->
            error("Wallet backend access forbidden in this HTTP-only probe")
        } as Backend
        val tor = TorClient.new(java.io.File(context.cacheDir, "isolated-tor"), backend)
        try {
            repeat(3) { attempt ->
                val isolated = tor.isolatedTorClient()
                val client = HttpClient(TorHttp) { engine { this.tor = isolated; retryLimit = 1 } }
                try {
                    val body = JSONObject().apply {
                        put("dry", false); put("swapType", "FLEX_INPUT"); put("slippageTolerance", 50)
                        put("originAsset", "nep141:btc.omft.near"); put("destinationAsset", "nep141:zec.omft.near")
                        put("amount", "100000"); put("depositType", "ORIGIN_CHAIN"); put("refundType", "ORIGIN_CHAIN")
                        put("recipientType", "DESTINATION_CHAIN"); put("refundTo", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT")
                        put("recipient", "u1l9f0l4348negsncgr9pxd9d3qaxagmqv3lnexcplmufpq7muffvfaue6ksevfvd7wrz7xrvn95rc5zjtn7ugkmgh5rnxswmcj30y0pw52pn0zjvy38rn2esfgve64rj5pcmazxgpyuj"); put("appFees", org.json.JSONArray())
                        put("deadline", Instant.now().plusSeconds(7200).toString()); put("quoteWaitingTimeMs", 3000)
                    }
                    val response = client.post("https://1click.chaindefuser.com/v0/quote") {
                        contentType(ContentType.Application.Json); setBody(body.toString())
                    }
                    val text = response.bodyAsText()
                    val parsed = runCatching { JSONObject(text) }.getOrNull()
                    // Safe diagnostics: never print addresses, response bodies or request data.
                    println("TOR_QUOTE attempt=$attempt status=${response.status.value} hasQuote=${parsed?.has("quote")}")
                    check(response.status.value in 200..201 && parsed?.has("quote") == true) {
                        "Native Tor quote attempt $attempt failed: HTTP ${response.status.value}"
                    }
                } finally { client.close() }
            }
        } finally { tor.dispose() }
    }
}
