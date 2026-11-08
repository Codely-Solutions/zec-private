package co.electriccoin.zcash.ui.screen.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.withLock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateRepositoryTest {
    private val context: Context
        get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = PRODUCTION_PACKAGE
        }
    private val preferences
        get() = context.getSharedPreferences("app_update_discovery", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        preferences.edit().clear().commit()
        installVersion(100)
    }

    @Test
    fun cancelledManagedEngineCloseKeepsUiDispatcherResponsive(): Unit = runBlocking {
        val uiExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "update-test-ui")
        }
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
        val nativeEntered = CountDownLatch(1)
        val nativeRelease = CountDownLatch(1)
        val nativeFinished = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val nativeLock = ReentrantLock()
        val factory = object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine =
                object : HttpClientEngineBase("blocking-native-close-test") {
                    override val config = HttpClientEngineConfig().apply(block)
                    private val closed = AtomicBoolean(false)

                    @OptIn(io.ktor.utils.io.InternalAPI::class)
                    override suspend fun execute(data: HttpRequestData): HttpResponseData {
                        try {
                            nativeLock.withLock {
                                nativeEntered.countDown()
                                check(nativeRelease.await(10, TimeUnit.SECONDS)) { "Native fixture release deadline" }
                            }
                        } finally {
                            nativeFinished.countDown()
                        }
                        throw IOException("Synthetic native request ended")
                    }

                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        closeEntered.countDown()
                        // SDK TorHttpEngine.close uses runBlocking disposal, which waits
                        // for the same mutex held across a non-interruptible JNI request.
                        nativeLock.withLock { }
                        super.close()
                    }
                }
        }
        val provider = object : HttpClientProvider {
            // The factory overload is essential: externally supplied engines are unmanaged.
            override suspend fun create(): HttpClient = HttpClient(factory)
            override suspend fun createTor(): HttpClient = error("Unexpected client selection")
        }
        val request = scope.launch { AppUpdateRepository(context, provider).readLatestRelease() }
        var responsiveWhileNativeHeld = false
        try {
            assertTrue(nativeEntered.await(3, TimeUnit.SECONDS), "Native fixture did not start")
            request.cancel()
            assertTrue(closeEntered.await(3, TimeUnit.SECONDS), "Managed engine close was not exercised")
            val heartbeat = CountDownLatch(1)
            uiExecutor.execute { heartbeat.countDown() }
            responsiveWhileNativeHeld = heartbeat.await(1, TimeUnit.SECONDS)
        } finally {
            // The control thread always releases native work, including on the expected red failure.
            request.cancel()
            nativeRelease.countDown()
            try {
                withTimeout(5_000) { request.join() }
                assertTrue(nativeFinished.await(3, TimeUnit.SECONDS), "Native fixture survived cleanup")
            } finally {
                scope.cancel()
                uiDispatcher.close()
                assertTrue(uiExecutor.awaitTermination(3, TimeUnit.SECONDS), "UI test executor survived cleanup")
            }
        }
        assertTrue(responsiveWhileNativeHeld, "Cancelling an update must not block UI while native disposal waits")
    }

    @Test
    fun cancellationRestoresAbsentOrPreviousTimestampAndAllowsRetry(): Unit = runBlocking {
        for (previous in listOf<Long?>(null, 123L)) {
            preferences.edit().clear().apply()
            previous?.let { preferences.edit().putLong("last_check", it).apply() }
            val entered = CompletableDeferred<Unit>()
            val provider = UpdateTestClientProvider {
                entered.complete(Unit)
                awaitCancellation()
            }
            val repository = AppUpdateRepository(context, provider)
            assertTrue(repository.enabled, "Run this test against the mainnet release variant")
            val checking = launch { repository.check() }
            withTimeout(10_000) { entered.await() }
            assertIs<AppUpdateState.Checking>(repository.state.value)
            checking.cancelAndJoin()

            assertEquals(previous != null, preferences.contains("last_check"))
            previous?.let { assertEquals(it, preferences.getLong("last_check", 0)) }
            assertIs<AppUpdateState.Idle>(repository.state.value)
            provider.reply = { releaseMetadata(200) }
            repository.check()
            assertEquals(2, provider.requests)
            assertEquals(200, assertIs<AppUpdateState.Available>(repository.state.value).release.versionCode)
        }
    }

    @Test
    fun completedFailureKeepsCooldownAcrossRecreationWhileManualCheckRetries(): Unit = runBlocking {
        val provider = UpdateTestClientProvider { throw IOException("Synthetic outage") }
        val repository = AppUpdateRepository(context, provider)
        assertTrue(repository.enabled)
        repository.check()
        assertIs<AppUpdateState.Failed>(repository.state.value)
        val lastCheck = preferences.getLong("last_check", 0)
        assertTrue(lastCheck > 0)

        val recreated = AppUpdateRepository(context, provider)
        recreated.check()
        assertEquals(1, provider.requests)
        assertEquals(lastCheck, preferences.getLong("last_check", 0))
        provider.reply = { releaseMetadata(200) }
        recreated.check(manual = true)
        assertEquals(2, provider.requests)
        assertIs<AppUpdateState.Available>(recreated.state.value)
    }

    @Test
    fun dismissalSurvivesRecreationManualCheckFindsNewerAndUpgradeRetiresNotice(): Unit = runBlocking {
        val provider = UpdateTestClientProvider { releaseMetadata(200) }
        val repository = AppUpdateRepository(context, provider)
        assertTrue(repository.enabled)
        repository.check()
        assertEquals(200, repository.cachedNotice()?.versionCode)
        repository.dismiss(200)
        assertTrue(assertIs<AppUpdateState.Available>(repository.state.value).dismissed)
        assertNull(repository.cachedNotice())

        val recreated = AppUpdateRepository(context, provider)
        assertTrue(assertIs<AppUpdateState.Available>(recreated.state.value).dismissed)
        recreated.check(manual = true)
        assertTrue(assertIs<AppUpdateState.Available>(recreated.state.value).dismissed)
        provider.reply = { releaseMetadata(201) }
        recreated.check(manual = true)
        assertFalse(assertIs<AppUpdateState.Available>(recreated.state.value).dismissed)
        assertEquals(201, recreated.cachedNotice()?.versionCode)

        installVersion(201)
        val upgraded = AppUpdateRepository(context, provider)
        assertNull(upgraded.cachedNotice())
        assertIs<AppUpdateState.Idle>(upgraded.state.value)
        provider.reply = { releaseMetadata(200) }
        upgraded.check(manual = true)
        assertIs<AppUpdateState.Current>(upgraded.state.value)
        assertNull(upgraded.cachedNotice())
    }

    private fun installVersion(code: Long) {
        val info = PackageInfo().apply {
            packageName = PRODUCTION_PACKAGE
            setLongVersionCode(code)
            applicationInfo = ApplicationInfo().apply { packageName = PRODUCTION_PACKAGE }
        }
        shadowOf(context.packageManager).installPackage(info)
    }
}

private class UpdateTestClientProvider(var reply: suspend () -> String) : HttpClientProvider {
    var requests = 0
        private set

    override suspend fun create(): HttpClient = HttpClient(MockEngine { request ->
        requests++
        assertEquals("https://api.github.com/repos/Sammy-Lastre/zec-private/releases/latest", request.url.toString())
        respond(reply(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    override suspend fun createTor(): HttpClient = error("The repository must use the preference-aware client")
}

private fun releaseMetadata(code: Int): String =
    """
    {"draft":false,"prerelease":false,"tag_name":"v3.19.0-private.20","body":"Android versionCode: $code",
    "assets":[{"name":"zec-private-latest.apk","size":1024,
    "browser_download_url":"$REPOSITORY_URL/releases/download/v3.19.0-private.20/$APK_NAME"}]}
    """.trimIndent()

private const val PRODUCTION_PACKAGE = "com.personal.zecprivate.foss"
