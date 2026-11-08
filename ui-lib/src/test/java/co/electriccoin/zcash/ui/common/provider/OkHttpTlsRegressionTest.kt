/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.electriccoin.zcash.ui.common.provider

import java.math.BigInteger
import java.net.InetAddress
import java.net.Proxy
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local TLS coverage for the OkHttp transport dependency. No provider or wallet is contacted.
 * Compromised CA fixtures/key construction adapted from OkHttp's Apache-2.0 licensed test:
 * https://github.com/lysine-dev/okhttp/commit/6db98026dfb817a23c0282aa4e53d8187b309052
 * These are public compromised test keys, never wallet or application credentials.
 */
class OkHttpTlsRegressionTest {
    @Test
    fun compromisedIntermediateIsRejectedBeforeHttp() {
        val intermediate = HeldCertificate(
            KeyPair(emsignEvOvTlsCaG3c.publicKey, emsignEvOvTlsCaG3cPrivateKey),
            emsignEvOvTlsCaG3c,
        )
        val leaf = HeldCertificate.Builder()
            .signedBy(intermediate)
            .addSubjectAlternativeName(TEST_HOST)
            .build()
        withTlsServer(leaf, intermediate, emsignRootTlsCaG3) { server, client ->
            val failure = assertFailsWith<SSLPeerUnverifiedException> {
                client.newCall(request(server)).execute().use { }
            }
            // Require OkHttp's mitigation specifically: a platform rejection alone cannot prove it.
            assertTrue(failure.message.orEmpty().contains("blocklisted by OkHttp"))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun validLocalCertificateAllowsHttp() {
        val root = HeldCertificate.Builder().certificateAuthority(1).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName(TEST_HOST).build()
        withTlsServer(leaf, root, root.certificate) { server, client ->
            client.newCall(request(server)).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("local TLS response", response.body.string())
            }
            assertEquals("/tls-regression", server.takeRequest(1, TimeUnit.SECONDS)?.target)
        }
    }

    @Test
    fun mismatchedHostnameIsRejectedBeforeHttp() {
        val root = HeldCertificate.Builder().certificateAuthority(1).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("other.invalid").build()
        withTlsServer(leaf, root, root.certificate) { server, client ->
            assertFailsWith<SSLPeerUnverifiedException> {
                client.newCall(request(server)).execute().use { }
            }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private fun request(server: MockWebServer) = Request.Builder()
        .url("https://$TEST_HOST:${server.port}/tls-regression")
        .build()

    private fun withTlsServer(
        leaf: HeldCertificate,
        intermediate: HeldCertificate,
        root: java.security.cert.X509Certificate,
        test: (MockWebServer, OkHttpClient) -> Unit,
    ) {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(leaf, intermediate.certificate)
            .build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            // Explicit IPv4 loopback avoids external DNS, proxy configuration and interface binding.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            server.start(loopback, 0)
            server.enqueue(MockResponse(body = "local TLS response"))
            val client = OkHttpClient.Builder()
                .dns(Dns { hostname ->
                    check(hostname == TEST_HOST)
                    listOf(loopback)
                })
                .proxy(Proxy.NO_PROXY)
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .retryOnConnectionFailure(false)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            try {
                test(server, client)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private companion object {
        const val TEST_HOST = "tls-regression.invalid"
    }

    private val emsignRootTlsCaG3 =
        """
        -----BEGIN CERTIFICATE-----
        MIICKTCCAa+gAwIBAgIQDnYGcvFDRZ/I/gqwvAXjlDAKBggqhkjOPQQDAzBWMQsw
        CQYDVQQGEwJJTjElMCMGA1UECgwcZU11ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRl
        ZDEgMB4GA1UEAwwXZW1TaWduIFJvb3QgVExTIENBIC0gRzMwHhcNMjQwMjA4MDgx
        OTMyWhcNNDkwMjA3MDgxOTMyWjBWMQswCQYDVQQGEwJJTjElMCMGA1UECgwcZU11
        ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRlZDEgMB4GA1UEAwwXZW1TaWduIFJvb3Qg
        VExTIENBIC0gRzMwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAARfD9w/q1cWxL6D9bMG
        H6CQ4u+gAV6GN9s+PwQx9Bl4hC+0iU8QOk8mWO/RwXQy10DCSxT8vSIJClHKp+Ao
        4qJKdYeV0SwZ7LMQziyLfxIUOY6Grja/sQFwLKYf8EdDXAyjQjBAMB0GA1UdDgQW
        BBThOqshfPHjTVSu1HK3/YtZQiCQVDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/
        BAUwAwEB/zAKBggqhkjOPQQDAwNoADBlAjBhCFTkly7Yl7+s4hiw+k6ZJTXT2AiI
        rvmsmfqPr5E7ecwXb6kXA5LTFd6rjwTuV3sCMQC9w6ZSbVq1mveMO6dKFziOzClT
        KCT2RFT6yXtq6nzgRICgBmiXbJAXsmZ8yAwjyWc=
        -----END CERTIFICATE-----
        """.trimIndent().decodeCertificatePem()

    private val emsignEvOvTlsCaG3c =
        """
        -----BEGIN CERTIFICATE-----
        MIIDaDCCAu6gAwIBAgIPKWkmwiejoVJjkrGUgqUEMAoGCCqGSM49BAMDMFYxCzAJ
        BgNVBAYTAklOMSUwIwYDVQQKDBxlTXVkaHJhIFRlY2hub2xvZ2llcyBMaW1pdGVk
        MSAwHgYDVQQDDBdlbVNpZ24gUm9vdCBUTFMgQ0EgLSBHMzAeFw0yNTA0MzAxNTM3
        NDFaFw00MDA0MjgxODMwMDBaMEAxCzAJBgNVBAYTAlVTMRQwEgYDVQQKDAtlTXVk
        aHJhIEluYzEbMBkGA1UEAwwSRU0gT1YgVExTIENBIC0gRzNDMHYwEAYHKoZIzj0C
        AQYFK4EEACIDYgAEE4JRzVKskpjByKrZdzId65fnCb0LTKCspV3IrVHc/J0ViaFZ
        fjpRIOHv1jHGPhg1ysrimGmmLhYx6KKBgatWYW3EXZGKvAnzqw5jz3kqpNztc4e+
        N7ulaVSfHAKycO1no4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
        WUIgkFQwHQYDVR0OBBYEFDTq6gzryQra+dDF4u2beMmHnhtsMB0GA1UdJQQWMBQG
        CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
        KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
        BAGDjiEBAm4wCAYGZ4EMAQICMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
        aHR0cDovL29jc3AtYy5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
        b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNT1ZUTFNDQUczQy5wN2MwNwYDVR0f
        BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
        cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
        AwMDaAAwZQIxAMSZt2xsPeumRdeSMSQ3CbzIZeQ/q7GDCWfeE1Pq+mhgA6gRM44c
        NVF3xJ+wsxJk+wIwFJbaDMokD8Ei6eNOiA8yUMYvfzQyngmrhZQMdPCK1SewrR79
        4rSb9vGqxZzelQth
        -----END CERTIFICATE-----
        """.trimIndent().decodeCertificatePem()

    private val emsignEvOvTlsCaG3cPrivateKey = KeyFactory.getInstance("EC").run {
        val secp384r1 = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp384r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        // Publicly compromised intermediate key from the upstream regression fixture.
        generatePrivate(ECPrivateKeySpec(BigInteger("4"), secp384r1))
    }
}
